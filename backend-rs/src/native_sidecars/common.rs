//! Shared building blocks for native Rust HTTP sidecars:
//! - In-memory `PendingStore<T>` for MFA attempts parked between `/initiate` and `/complete`.
//! - Inspectable `MemoryCookieJar` implementing `reqwest::cookie::CookieStore` with exact
//!   `{"cookies": [...]}` JSON serialization matching the Python sidecars.
//! - HTML entity decoding, text extraction, and same-origin redirect validation.

use std::collections::HashMap;
use std::sync::{Arc, RwLock};
use std::time::Duration;

use chrono::{DateTime, Utc};
use http::{HeaderValue, StatusCode};
use reqwest::cookie::CookieStore;
use serde::{Deserialize, Serialize};
use tokio::sync::{Mutex, Notify};
use url::Url;

use super::NativeSidecarError;
use crate::clock::{Clock, SystemClock};

// ─── Pending second-factor store ────────────────────────────────────────────

struct PendingEntry<T> {
    value: T,
    created_at: DateTime<Utc>,
}

/// A taken pending entry paired with its original creation timestamp so callers can re-park it
/// after a rejected OTP without extending its TTL.
pub struct PendingLease<T> {
    pub value: T,
    created_at: DateTime<Utc>,
}

struct PendingMap<T> {
    entries: HashMap<String, PendingEntry<T>>,
    sweeper_active: bool,
}

/// Thread-safe in-memory store for pending authentication attempts between `/initiate` and `/complete`.
pub struct PendingStore<T: Send + 'static> {
    ttl: Duration,
    max_size: Option<usize>,
    clock: Arc<dyn Clock>,
    state: Arc<Mutex<PendingMap<T>>>,
    wake: Arc<Notify>,
}

impl<T: Send + 'static> Drop for PendingStore<T> {
    fn drop(&mut self) {
        self.wake.notify_one();
    }
}

fn is_live(now: DateTime<Utc>, created_at: DateTime<Utc>, ttl: Duration) -> bool {
    let ttl = chrono::Duration::from_std(ttl).unwrap_or(chrono::Duration::MAX);
    now - created_at < ttl
}

impl<T: Send + 'static> PendingStore<T> {
    pub fn new(ttl: Duration, max_size: Option<usize>) -> Self {
        Self::with_clock(ttl, max_size, Arc::new(SystemClock))
    }

    pub fn with_clock(ttl: Duration, max_size: Option<usize>, clock: Arc<dyn Clock>) -> Self {
        Self {
            ttl,
            max_size,
            clock,
            state: Arc::new(Mutex::new(PendingMap {
                entries: HashMap::new(),
                sweeper_active: false,
            })),
            wake: Arc::new(Notify::new()),
        }
    }

    fn spawn_sweeper_if_needed(&self, map: &mut PendingMap<T>) {
        if map.sweeper_active || map.entries.is_empty() {
            return;
        }
        if tokio::runtime::Handle::try_current().is_err() {
            return;
        }
        map.sweeper_active = true;
        let weak = Arc::downgrade(&self.state);
        let wake = self.wake.clone();
        let clock = self.clock.clone();
        let ttl = self.ttl;
        let ttl_chrono = chrono::Duration::from_std(ttl).unwrap_or(chrono::Duration::MAX);
        tokio::spawn(async move {
            loop {
                let next_sleep = {
                    let Some(state) = weak.upgrade() else {
                        return;
                    };
                    let mut guard = state.lock().await;
                    let now = clock.now();
                    guard
                        .entries
                        .retain(|_, entry| is_live(now, entry.created_at, ttl));
                    let earliest = guard
                        .entries
                        .values()
                        .map(|entry| {
                            let remaining = ttl_chrono - (now - entry.created_at);
                            remaining.to_std().unwrap_or(Duration::ZERO)
                        })
                        .min();
                    match earliest {
                        Some(dur) => dur.max(Duration::from_millis(1)),
                        None => {
                            guard.sweeper_active = false;
                            return;
                        }
                    }
                };
                tokio::select! {
                    _ = tokio::time::sleep(next_sleep) => {}
                    _ = wake.notified() => {}
                }
            }
        });
    }

    pub async fn expire(&self) {
        let now = self.clock.now();
        let mut guard = self.state.lock().await;
        guard
            .entries
            .retain(|_, entry| is_live(now, entry.created_at, self.ttl));
    }

    pub async fn put(&self, process_id: String, value: T) -> bool {
        let now = self.clock.now();
        let mut guard = self.state.lock().await;
        guard
            .entries
            .retain(|_, entry| is_live(now, entry.created_at, self.ttl));
        if let Some(max) = self.max_size
            && guard.entries.len() >= max
            && !guard.entries.contains_key(&process_id)
        {
            return false;
        }
        let created_at = guard
            .entries
            .get(&process_id)
            .map_or(now, |existing| existing.created_at);
        guard.entries.insert(
            process_id,
            PendingEntry { value, created_at },
        );
        self.spawn_sweeper_if_needed(&mut guard);
        true
    }

    pub async fn put_or_err(
        &self,
        process_id: String,
        value: T,
        detail: &str,
    ) -> Result<(), NativeSidecarError> {
        if self.put(process_id, value).await {
            Ok(())
        } else {
            Err(NativeSidecarError::new(
                StatusCode::SERVICE_UNAVAILABLE,
                detail,
            ))
        }
    }

    pub async fn take_lease(&self, process_id: &str) -> Option<PendingLease<T>> {
        let entry = {
            let mut guard = self.state.lock().await;
            guard.entries.remove(process_id)?
        };
        if !is_live(self.clock.now(), entry.created_at, self.ttl) {
            None
        } else {
            Some(PendingLease {
                value: entry.value,
                created_at: entry.created_at,
            })
        }
    }

    pub async fn take(&self, process_id: &str) -> Option<T> {
        self.take_lease(process_id).await.map(|lease| lease.value)
    }

    /// Re-parks a taken lease under its original creation timestamp. Drops the lease immediately
    /// without re-inserting if its TTL already elapsed while checked out.
    pub async fn repark(&self, process_id: String, lease: PendingLease<T>) -> bool {
        let now = self.clock.now();
        if !is_live(now, lease.created_at, self.ttl) {
            return true;
        }
        let mut guard = self.state.lock().await;
        guard
            .entries
            .retain(|_, entry| is_live(now, entry.created_at, self.ttl));
        if let Some(max) = self.max_size
            && guard.entries.len() >= max
            && !guard.entries.contains_key(&process_id)
        {
            return false;
        }
        guard.entries.insert(
            process_id,
            PendingEntry {
                value: lease.value,
                created_at: lease.created_at,
            },
        );
        if guard.sweeper_active {
            self.wake.notify_one();
        } else {
            self.spawn_sweeper_if_needed(&mut guard);
        }
        true
    }

    pub async fn repark_or_err(
        &self,
        process_id: String,
        lease: PendingLease<T>,
        detail: &str,
    ) -> Result<(), NativeSidecarError> {
        if self.repark(process_id, lease).await {
            Ok(())
        } else {
            Err(NativeSidecarError::new(
                StatusCode::SERVICE_UNAVAILABLE,
                detail,
            ))
        }
    }
}

// ─── Inspectable cookie jar ─────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct StoredCookie {
    pub name: String,
    pub value: String,
    pub domain: String,
    pub path: String,
}

#[derive(Default)]
pub struct MemoryCookieJar {
    cookies: RwLock<Vec<StoredCookie>>,
}

impl MemoryCookieJar {
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    pub fn set(&self, name: impl Into<String>, value: impl Into<String>, domain: &str, path: &str) {
        let name = name.into();
        let value = value.into();
        let domain = normalize_domain(domain);
        let path = if path.is_empty() {
            "/".to_owned()
        } else {
            path.to_owned()
        };
        let mut guard = self.cookies.write().expect("cookie jar lock poisoned");
        if let Some(existing) = guard
            .iter_mut()
            .find(|c| c.name == name && c.domain.eq_ignore_ascii_case(&domain) && c.path == path)
        {
            existing.value = value;
        } else {
            guard.push(StoredCookie {
                name,
                value,
                domain,
                path,
            });
        }
    }

    pub fn remove(&self, name: &str, domain: &str, path: &str) {
        let domain = normalize_domain(domain);
        let path = if path.is_empty() { "/" } else { path };
        let mut guard = self.cookies.write().expect("cookie jar lock poisoned");
        guard.retain(|c| {
            !(c.name == name && c.domain.eq_ignore_ascii_case(&domain) && c.path == path)
        });
    }

    pub fn has_cookie(&self, name: &str) -> bool {
        let guard = self.cookies.read().expect("cookie jar lock poisoned");
        guard.iter().any(|c| c.name == name)
    }

    pub fn get_case_insensitive(&self, name: &str) -> Option<String> {
        let guard = self.cookies.read().expect("cookie jar lock poisoned");
        guard
            .iter()
            .rev()
            .find(|c| c.name.eq_ignore_ascii_case(name))
            .map(|c| c.value.clone())
    }

    pub fn serialize_json(&self) -> String {
        let guard = self.cookies.read().expect("cookie jar lock poisoned");
        serde_json::to_string(&serde_json::json!({ "cookies": &*guard }))
            .expect("serializing cookies cannot fail")
    }

    pub fn decode_json(
        raw: &str,
        bad_detail: &str,
    ) -> Result<Vec<StoredCookie>, NativeSidecarError> {
        let bad = || NativeSidecarError::new(StatusCode::BAD_REQUEST, bad_detail);
        let decoded: serde_json::Value = serde_json::from_str(raw).map_err(|_| bad())?;
        let arr = decoded
            .as_object()
            .and_then(|obj| obj.get("cookies"))
            .and_then(serde_json::Value::as_array)
            .filter(|a| !a.is_empty())
            .ok_or_else(bad)?;

        let mut out = Vec::with_capacity(arr.len());
        for item in arr {
            let obj = item.as_object().ok_or_else(bad)?;
            let name = obj
                .get("name")
                .and_then(serde_json::Value::as_str)
                .ok_or_else(bad)?;
            let value = match obj.get("value") {
                Some(serde_json::Value::String(s)) => s.clone(),
                Some(serde_json::Value::Null) | None => String::new(),
                Some(other) => other.to_string(),
            };
            let domain = obj
                .get("domain")
                .and_then(serde_json::Value::as_str)
                .unwrap_or("")
                .to_owned();
            let path = obj
                .get("path")
                .and_then(serde_json::Value::as_str)
                .filter(|p| !p.is_empty())
                .unwrap_or("/")
                .to_owned();
            out.push(StoredCookie {
                name: name.to_owned(),
                value,
                domain,
                path,
            });
        }
        Ok(out)
    }

    pub fn restore_json(&self, raw: &str, bad_detail: &str) -> Result<(), NativeSidecarError> {
        let cookies = Self::decode_json(raw, bad_detail)?;
        for cookie in cookies {
            self.set(cookie.name, cookie.value, &cookie.domain, &cookie.path);
        }
        Ok(())
    }

    /// Restores cookies from a previous session, optionally filtering by cookie name.
    /// Returns whether `trust_cookie` was present in the decoded list.
    /// Malformed input is ignored so it never blocks a fresh login.
    pub fn restore_previous(
        &self,
        raw: Option<&str>,
        allowed: Option<&[&str]>,
        trust_cookie: &str,
    ) -> bool {
        let Some(raw) = raw.filter(|s| !s.is_empty()) else {
            return false;
        };
        let Ok(cookies) = Self::decode_json(raw, "INVALID_DATA") else {
            return false;
        };
        let mut trusted = false;
        for cookie in cookies {
            if cookie.name == trust_cookie {
                trusted = true;
            }
            if allowed.is_none_or(|list| list.contains(&cookie.name.as_str())) {
                self.set(cookie.name, cookie.value, &cookie.domain, &cookie.path);
            }
        }
        trusted
    }
}

fn normalize_domain(domain: &str) -> String {
    domain.trim().trim_start_matches('.').to_ascii_lowercase()
}

fn default_cookie_path(url: &Url) -> String {
    let path = url.path();
    if !path.starts_with('/') {
        return "/".to_owned();
    }
    match path.rfind('/') {
        Some(0) | None => "/".to_owned(),
        Some(idx) => path[..idx].to_owned(),
    }
}

fn domain_matches(cookie_domain: &str, req_host: &str) -> bool {
    if cookie_domain.is_empty() {
        return true;
    }
    let host = req_host.to_ascii_lowercase();
    let domain = normalize_domain(cookie_domain);
    if host == domain {
        return true;
    }
    host.ends_with(&format!(".{domain}"))
}

fn path_matches(cookie_path: &str, req_path: &str) -> bool {
    if cookie_path.is_empty() || cookie_path == "/" {
        return true;
    }
    if req_path == cookie_path {
        return true;
    }
    if let Some(rest) = req_path.strip_prefix(cookie_path) {
        cookie_path.ends_with('/') || rest.starts_with('/')
    } else {
        false
    }
}

impl CookieStore for MemoryCookieJar {
    fn set_cookies(&self, cookie_headers: &mut dyn Iterator<Item = &HeaderValue>, url: &Url) {
        let req_host = url.host_str().unwrap_or("");
        for header in cookie_headers {
            let Ok(raw) = header.to_str() else {
                continue;
            };
            let mut parts = raw.split(';');
            let Some(name_value) = parts.next() else {
                continue;
            };
            let Some((name, value)) = name_value.split_once('=') else {
                continue;
            };
            let name = name.trim();
            if name.is_empty() {
                continue;
            }
            let value = value.trim();
            let mut domain = req_host.to_owned();
            let mut path = default_cookie_path(url);
            let mut expired = false;

            for attr in parts {
                let attr = attr.trim();
                if let Some((k, v)) = attr.split_once('=') {
                    let k = k.trim();
                    let v = v.trim();
                    if k.eq_ignore_ascii_case("domain") && !v.is_empty() {
                        let candidate = normalize_domain(v);
                        if domain_matches(&candidate, req_host) {
                            domain = candidate;
                        }
                    } else if k.eq_ignore_ascii_case("path") && v.starts_with('/') {
                        path = v.to_owned();
                    } else if k.eq_ignore_ascii_case("max-age")
                        && v.parse::<i64>().is_ok_and(|secs| secs <= 0)
                    {
                        expired = true;
                    } else if k.eq_ignore_ascii_case("expires")
                        && let Ok(parsed) = httpdate::parse_http_date(v)
                        && parsed <= std::time::SystemTime::now()
                    {
                        expired = true;
                    }
                }
            }

            if expired {
                self.remove(name, &domain, &path);
            } else {
                self.set(name, value, &domain, &path);
            }
        }
    }

    fn cookies(&self, url: &Url) -> Option<HeaderValue> {
        let req_host = url.host_str().unwrap_or("");
        let req_path = url.path();
        let guard = self.cookies.read().expect("cookie jar lock poisoned");
        let pairs: Vec<String> = guard
            .iter()
            .filter(|c| domain_matches(&c.domain, req_host) && path_matches(&c.path, req_path))
            .map(|c| format!("{}={}", c.name, c.value))
            .collect();
        if pairs.is_empty() {
            None
        } else {
            HeaderValue::from_str(&pairs.join("; ")).ok()
        }
    }
}

// ─── URL origin & redirect safety ───────────────────────────────────────────

pub fn normalize_base_url(url: &str) -> String {
    url.trim().trim_end_matches('/').to_owned()
}

/// Resolves `target` relative to `current` and verifies it stays on the exact same
/// scheme, host, and port as `base` with no embedded credentials.
pub fn same_origin_url(base: &Url, current: &Url, target: &str) -> Option<Url> {
    let resolved = current.join(target).ok()?;
    if resolved.scheme() != base.scheme() {
        return None;
    }
    if resolved.host_str() != base.host_str() {
        return None;
    }
    if resolved.port_or_known_default() != base.port_or_known_default() {
        return None;
    }
    if !resolved.username().is_empty() || resolved.password().is_some() {
        return None;
    }
    Some(resolved)
}

// ─── HTML entity decoding and text extraction ───────────────────────────────

/// Decodes numeric (`&#233;`, `&#xE9;`) and common HTML named entities.
pub fn unescape_html(input: &str) -> String {
    if !input.contains('&') {
        return input.to_owned();
    }
    let mut out = String::with_capacity(input.len());
    let bytes = input.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'&'
            && let Some((decoded, consumed)) = decode_entity(&input[i..])
        {
            out.push_str(&decoded);
            i += consumed;
            continue;
        }
        let ch = input[i..].chars().next().expect("valid utf8 boundary");
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

fn decode_entity(s: &str) -> Option<(String, usize)> {
    let rest = s.strip_prefix('&')?;
    let semi = rest.find(';').filter(|&pos| pos > 0 && pos <= 16)?;
    let entity = &rest[..semi];
    let consumed = semi + 2; // '&' + entity + ';'

    if let Some(num) = entity.strip_prefix('#') {
        let code = if let Some(hex) = num.strip_prefix('x').or_else(|| num.strip_prefix('X')) {
            u32::from_str_radix(hex, 16).ok()?
        } else {
            num.parse::<u32>().ok()?
        };
        let ch = char::from_u32(code)?;
        return Some((ch.to_string(), consumed));
    }

    let replacement = match entity {
        "amp" => "&",
        "lt" => "<",
        "gt" => ">",
        "quot" => "\"",
        "apos" => "'",
        "nbsp" => "\u{00A0}",
        "thinsp" => "\u{2009}",
        "ensp" => "\u{2002}",
        "emsp" => "\u{2003}",
        "euro" => "€",
        "pound" => "£",
        "yen" => "¥",
        "cent" => "¢",
        "copy" => "©",
        "reg" => "®",
        "trade" => "™",
        "deg" => "°",
        "plusmn" => "±",
        "times" => "×",
        "divide" => "÷",
        "ndash" => "–",
        "mdash" => "—",
        "lsquo" => "‘",
        "rsquo" => "’",
        "ldquo" => "“",
        "rdquo" => "”",
        "laquo" => "«",
        "raquo" => "»",
        "hellip" => "…",
        "middot" => "·",
        "bull" => "•",
        "eacute" => "é",
        "egrave" => "è",
        "ecirc" => "ê",
        "euml" => "ë",
        "agrave" => "à",
        "aacute" => "á",
        "acirc" => "â",
        "auml" => "ä",
        "ccedil" => "ç",
        "icirc" => "î",
        "iuml" => "ï",
        "iacute" => "í",
        "igrave" => "ì",
        "ocirc" => "ô",
        "ouml" => "ö",
        "oacute" => "ó",
        "ograve" => "ò",
        "ugrave" => "ù",
        "ucirc" => "û",
        "uuml" => "ü",
        "uacute" => "ú",
        "yuml" => "ÿ",
        "oelig" => "œ",
        "aelig" => "æ",
        "Eacute" => "É",
        "Egrave" => "È",
        "Ecirc" => "Ê",
        "Euml" => "Ë",
        "Agrave" => "À",
        "Aacute" => "Á",
        "Acirc" => "Â",
        "Auml" => "Ä",
        "Ccedil" => "Ç",
        "Icirc" => "Î",
        "Iuml" => "Ï",
        "Ocirc" => "Ô",
        "Ouml" => "Ö",
        "Ugrave" => "Ù",
        "Ucirc" => "Û",
        "Uuml" => "Ü",
        "OElig" => "Œ",
        "AElig" => "Æ",
        _ => return None,
    };
    Some((replacement.to_owned(), consumed))
}

/// Drops HTML tags, decodes entities, and collapses all whitespace (including non-breaking spaces).
pub fn html_text(fragment: &str) -> String {
    static TAG_RE: std::sync::LazyLock<regex::Regex> =
        std::sync::LazyLock::new(|| regex::Regex::new(r"<[^>]+>").expect("static regex"));
    let stripped = TAG_RE.replace_all(fragment, " ");
    let unescaped = unescape_html(&stripped);
    unescaped.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Drops HTML tags AFTER decoding entities (matches Sofidy's `_text`).
pub fn unescape_then_strip_html_text(fragment: &str) -> String {
    static TAG_RE: std::sync::LazyLock<regex::Regex> =
        std::sync::LazyLock::new(|| regex::Regex::new(r"<[^>]+>").expect("static regex"));
    let unescaped = unescape_html(fragment);
    let stripped = TAG_RE.replace_all(&unescaped, " ");
    stripped.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Sanitize an outside-controlled string before logging: truncate and replace control chars with '?'.
pub fn safe_log(value: &str, limit: usize) -> String {
    let mut out = String::with_capacity(value.len().min(limit + 3));
    for (count, ch) in value.chars().enumerate() {
        if count >= limit {
            out.push_str("...");
            break;
        }
        let code = ch as u32;
        if code <= 0x1F || (0x7F..=0x9F).contains(&code) || code == 0x2028 || code == 0x2029 {
            out.push('?');
        } else {
            out.push(ch);
        }
    }
    out
}

/// Maximum upstream HTTP response size buffered by native sidecars (16 MiB).
pub const MAX_RESPONSE_BYTES: usize = 16 * 1024 * 1024;

/// Failure while reading a size-bounded upstream HTTP response body.
#[derive(Debug)]
pub enum ReadBodyError {
    Transport(reqwest::Error),
    TooLarge { limit: usize },
}

/// Streams a `reqwest::Response` chunk-by-chunk, failing immediately once total bytes exceed
/// `max_bytes` so a hostile or runaway upstream response can never cause unbounded memory growth.
pub async fn read_capped_bytes(
    mut resp: reqwest::Response,
    max_bytes: usize,
) -> Result<Vec<u8>, ReadBodyError> {
    if let Some(len) = resp.content_length()
        && len > max_bytes as u64
    {
        return Err(ReadBodyError::TooLarge { limit: max_bytes });
    }
    let mut buf = Vec::new();
    while let Some(chunk) = resp.chunk().await.map_err(ReadBodyError::Transport)? {
        if buf.len().saturating_add(chunk.len()) > max_bytes {
            return Err(ReadBodyError::TooLarge { limit: max_bytes });
        }
        buf.extend_from_slice(&chunk);
    }
    Ok(buf)
}

/// Streams a size-bounded upstream HTTP response body and decodes it as UTF-8 (lossy fallback,
/// matching `reqwest::Response::text`).
pub async fn read_capped_text(
    resp: reqwest::Response,
    max_bytes: usize,
) -> Result<String, ReadBodyError> {
    let bytes = read_capped_bytes(resp, max_bytes).await?;
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

pub fn build_http_client(
    jar: Arc<MemoryCookieJar>,
    user_agent: &str,
    timeout: Duration,
    extra_headers: &[(&str, &str)],
) -> Result<reqwest::Client, NativeSidecarError> {
    let mut headers = http::HeaderMap::new();
    headers.insert(
        http::header::USER_AGENT,
        HeaderValue::from_str(user_agent).expect("valid user agent"),
    );
    for (k, v) in extra_headers {
        headers.insert(
            http::header::HeaderName::from_bytes(k.as_bytes()).expect("valid header name"),
            HeaderValue::from_str(v).expect("valid header value"),
        );
    }
    reqwest::Client::builder()
        .cookie_provider(jar)
        .redirect(reqwest::redirect::Policy::none())
        .timeout(timeout)
        .default_headers(headers)
        .build()
        .map_err(|_| NativeSidecarError::new(StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"))
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicBool, Ordering};

    use super::*;

    struct DropFlag(Arc<AtomicBool>);

    impl Drop for DropFlag {
        fn drop(&mut self) {
            self.0.store(true, Ordering::SeqCst);
        }
    }

    #[tokio::test]
    async fn pending_store_sweeps_expired_on_every_put_and_enforces_cap() {
        let clock = Arc::new(crate::clock::FixedClock::new(
            DateTime::parse_from_rfc3339("2026-01-15T10:00:00Z")
                .unwrap()
                .with_timezone(&Utc),
        ));
        let store = PendingStore::with_clock(Duration::from_secs(300), Some(2), clock.clone());
        assert!(store.put("p1".to_owned(), "secret-1").await);
        clock.advance(Duration::from_secs(301));
        // Putting p2 sweeps expired p1 even though len(1) < cap(2).
        assert!(store.put("p2".to_owned(), "secret-2").await);
        assert_eq!(store.state.lock().await.entries.len(), 1);
        assert!(store.put("p3".to_owned(), "secret-3").await);
        // At cap=2, p4 is rejected.
        assert!(!store.put("p4".to_owned(), "secret-4").await);
    }

    #[tokio::test]
    async fn pending_store_put_does_not_reset_ttl_and_sweeper_drops_expired_entries() {
        let clock = Arc::new(crate::clock::FixedClock::new(
            DateTime::parse_from_rfc3339("2026-01-15T10:00:00Z")
                .unwrap()
                .with_timezone(&Utc),
        ));
        // 1. Re-putting an existing key or reparking a taken lease must preserve its original TTL.
        let store = PendingStore::with_clock(Duration::from_secs(300), Some(4), clock.clone());
        assert!(store.put("p1".to_owned(), "secret").await);
        clock.advance(Duration::from_secs(200));
        assert!(store.put("p1".to_owned(), "secret").await);
        let lease = store.take_lease("p1").await.expect("still live at 200s");
        assert!(store.repark("p1".to_owned(), lease).await);
        clock.advance(Duration::from_secs(105));
        assert!(
            store.take("p1").await.is_none(),
            "re-putting or reparking the same key must not extend its TTL"
        );

        // 2. An abandoned entry must be swept and dropped without another request arriving.
        let dropped = Arc::new(AtomicBool::new(false));
        let sweep_store = PendingStore::new(Duration::from_millis(25), Some(4));
        assert!(
            sweep_store
                .put("abandoned".to_owned(), DropFlag(dropped.clone()))
                .await
        );
        for _ in 0..40 {
            if dropped.load(Ordering::SeqCst) {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert!(
            dropped.load(Ordering::SeqCst),
            "expired pending entry must be dropped by background sweep"
        );
    }
}
