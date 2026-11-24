//! Per-session cookie jar preserving each cookie's domain and path across
//! `clients.boursobank.com` and `api.boursobank.com`, wire-compatible with
//! `services/bourso-auth`'s `{"cookies":[...]}` sessionState JSON.

use std::net::IpAddr;
use std::sync::RwLock;

use chrono::{DateTime, NaiveDateTime, Utc};
use http::{HeaderValue, StatusCode};
use reqwest::cookie::CookieStore;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use url::Url;

use crate::native_sidecars::NativeError;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StoredCookie {
    pub name: String,
    pub value: String,
    pub domain: String,
    pub path: String,
}

#[derive(Debug, Default)]
pub struct SessionCookieJar {
    cookies: RwLock<Vec<StoredCookie>>,
}

impl SessionCookieJar {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn set(&self, name: impl Into<String>, value: impl Into<String>, domain: &str, path: &str) {
        let name = name.into();
        let value = value.into();
        let domain = domain.to_owned();
        let path = if path.is_empty() {
            "/".to_owned()
        } else {
            path.to_owned()
        };
        let mut guard = self
            .cookies
            .write()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
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
        let mut guard = self
            .cookies
            .write()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        guard.retain(|c| {
            !(c.name == name && c.domain.eq_ignore_ascii_case(domain) && c.path == path)
        });
    }

    pub fn get(&self, name: &str) -> Option<String> {
        let guard = self
            .cookies
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        guard
            .iter()
            .rev()
            .find(|c| c.name == name)
            .map(|c| c.value.clone())
    }

    pub fn snapshot(&self) -> Vec<StoredCookie> {
        self.cookies
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone()
    }

    /// Serialize the cookie jar as compact `{"cookies":[{"name":...,"value":...,"domain":...,"path":...}]}`.
    pub fn serialize_json(&self) -> String {
        let cookies = self.snapshot();
        serde_json::to_string(&serde_json::json!({ "cookies": cookies }))
            .expect("cookie serialization cannot fail")
    }

    /// Restore cookies from a serialized `sessionState` JSON string.
    ///
    /// Rejects non-JSON, non-object, empty cookie lists, or malformed entries with `400 INVALID_DATA`.
    pub fn restore_json(&self, raw: &str) -> Result<(), NativeError> {
        let invalid = || NativeError::new(StatusCode::BAD_REQUEST, "INVALID_DATA");
        let decoded: Value = serde_json::from_str(raw).map_err(|_| invalid())?;
        let obj = decoded.as_object().ok_or_else(invalid)?;
        let list = obj
            .get("cookies")
            .and_then(Value::as_array)
            .filter(|a| !a.is_empty())
            .ok_or_else(invalid)?;
        let mut parsed = Vec::with_capacity(list.len());
        for entry in list {
            let entry = entry.as_object().ok_or_else(invalid)?;
            let name = entry
                .get("name")
                .and_then(Value::as_str)
                .ok_or_else(invalid)?;
            let value = json_field_as_string(entry.get("value"), "");
            let domain = json_field_as_string(entry.get("domain"), "");
            let raw_path = json_field_as_string(entry.get("path"), "/");
            let path = if raw_path.is_empty() {
                "/".to_owned()
            } else {
                raw_path
            };
            parsed.push(StoredCookie {
                name: name.to_owned(),
                value,
                domain,
                path,
            });
        }
        for cookie in parsed {
            self.set(cookie.name, cookie.value, &cookie.domain, &cookie.path);
        }
        Ok(())
    }
}

fn json_field_as_string(value: Option<&Value>, default: &str) -> String {
    match value {
        None | Some(Value::Null) => default.to_owned(),
        Some(Value::String(s)) => s.clone(),
        Some(other) => other.to_string(),
    }
}

impl CookieStore for SessionCookieJar {
    fn set_cookies(&self, cookie_headers: &mut dyn Iterator<Item = &HeaderValue>, url: &Url) {
        self.set_cookies_at(cookie_headers, url, Utc::now());
    }

    fn cookies(&self, url: &Url) -> Option<HeaderValue> {
        let host = url.host_str().unwrap_or("").to_ascii_lowercase();
        let req_path = url.path();
        let guard = self
            .cookies
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let pairs: Vec<String> = guard
            .iter()
            .filter(|c| domain_matches(&c.domain, &host) && path_matches(&c.path, req_path))
            .map(|c| format!("{}={}", c.name, c.value))
            .collect();
        if pairs.is_empty() {
            return None;
        }
        HeaderValue::from_str(&pairs.join("; ")).ok()
    }
}

impl SessionCookieJar {
    fn set_cookies_at(
        &self,
        cookie_headers: &mut dyn Iterator<Item = &HeaderValue>,
        url: &Url,
        now: DateTime<Utc>,
    ) {
        let default_host = url.host_str().unwrap_or("").to_ascii_lowercase();
        for header in cookie_headers {
            let Ok(raw) = header.to_str() else {
                continue;
            };
            let mut parts = raw.split(';');
            let Some(name_value) = parts.next() else {
                continue;
            };
            let Some((raw_name, raw_value)) = name_value.split_once('=') else {
                continue;
            };
            let name = raw_name.trim();
            if name.is_empty() {
                continue;
            }
            let value = raw_value.trim();
            let mut domain: Option<String> = None;
            let mut rejected_domain = false;
            let mut path = "/".to_owned();
            let mut max_age_expired: Option<bool> = None;
            let mut expires_expired: Option<bool> = None;

            for attr in parts {
                let attr = attr.trim();
                if attr.is_empty() {
                    continue;
                }
                if let Some((k, v)) = attr.split_once('=') {
                    let key = k.trim();
                    let val = v.trim();
                    if key.eq_ignore_ascii_case("domain") && !val.is_empty() {
                        let stripped = val.trim_start_matches('.').to_ascii_lowercase();
                        if !stripped.is_empty() && domain_allowed_for_host(&stripped, &default_host)
                        {
                            domain = Some(format!(".{stripped}"));
                        } else {
                            rejected_domain = true;
                        }
                    } else if key.eq_ignore_ascii_case("path") && !val.is_empty() {
                        path = val.to_owned();
                    } else if key.eq_ignore_ascii_case("max-age")
                        && let Ok(secs) = val.parse::<i64>()
                    {
                        max_age_expired = Some(secs <= 0);
                    } else if key.eq_ignore_ascii_case("expires")
                        && let Some(parsed) = parse_expires(val)
                    {
                        expires_expired = Some(parsed <= now);
                    }
                }
            }

            if rejected_domain {
                continue;
            }
            let expired = max_age_expired.or(expires_expired).unwrap_or(false);
            let target_domain = domain.unwrap_or_else(|| default_host.clone());
            if expired {
                self.remove(name, &target_domain, &path);
            } else {
                self.set(name, value, &target_domain, &path);
            }
        }
    }
}

fn domain_allowed_for_host(cookie_domain: &str, request_host: &str) -> bool {
    if request_host.is_empty() {
        return false;
    }
    if request_host == cookie_domain {
        return true;
    }
    if request_host.parse::<IpAddr>().is_ok() || !cookie_domain.contains('.') {
        return false;
    }
    request_host.ends_with(&format!(".{cookie_domain}"))
}

fn parse_expires(raw: &str) -> Option<DateTime<Utc>> {
    if let Ok(st) = httpdate::parse_http_date(raw) {
        return Some(DateTime::<Utc>::from(st));
    }
    // Netscape / PHP Set-Cookie format uses dashes in the date (e.g. "Thu, 01-Jan-1970 00:00:00 GMT").
    for fmt in [
        "%a, %d-%b-%Y %H:%M:%S GMT",
        "%a, %d-%b-%Y %H:%M:%S UTC",
        "%A, %d-%b-%Y %H:%M:%S GMT",
    ] {
        if let Ok(naive) = NaiveDateTime::parse_from_str(raw, fmt) {
            return Some(naive.and_utc());
        }
    }
    None
}

fn domain_matches(cookie_domain: &str, request_host: &str) -> bool {
    if cookie_domain.is_empty() {
        return true;
    }
    let cd = cookie_domain.to_ascii_lowercase();
    if let Some(bare) = cd.strip_prefix('.') {
        request_host == bare || request_host.ends_with(&cd)
    } else {
        request_host == cd
    }
}

fn path_matches(cookie_path: &str, request_path: &str) -> bool {
    if cookie_path.is_empty() || cookie_path == "/" {
        return true;
    }
    let trimmed = cookie_path.trim_end_matches('/');
    request_path == trimmed
        || request_path == cookie_path
        || request_path
            .strip_prefix(trimmed)
            .is_some_and(|rest| rest.starts_with('/'))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn expires_deletes_cookie_and_foreign_or_public_suffix_domain_is_rejected() {
        let jar = SessionCookieJar::new();
        let clients = Url::parse("https://clients.boursobank.com/connexion/").unwrap();
        let api = Url::parse("https://api.boursobank.com/v1/accounts").unwrap();
        let evil = Url::parse("https://evil.example/").unwrap();
        let now = DateTime::parse_from_rfc3339("2026-01-15T10:00:00Z")
            .unwrap()
            .with_timezone(&Utc);

        // Store a valid parent-domain cookie with future Expires first.
        let valid = [HeaderValue::from_static(
            "sid=live; Domain=.boursobank.com; Path=/; Expires=Fri, 16 Jan 2026 10:00:00 GMT",
        )];
        jar.set_cookies_at(&mut valid.iter(), &clients, now);
        assert_eq!(jar.get("sid").as_deref(), Some("live"));
        assert!(jar.cookies(&api).is_some());

        // Past Expires (both IMF-fixdate and dash-separated Netscape format) must remove the cookie.
        let expire_imf = [HeaderValue::from_static(
            "sid=deleted; Domain=.boursobank.com; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
        )];
        jar.set_cookies_at(&mut expire_imf.iter(), &clients, now);
        assert_eq!(
            jar.get("sid"),
            None,
            "cookie with past Expires must be removed"
        );

        let valid2 = [HeaderValue::from_static(
            "sid2=live2; Domain=.boursobank.com; Path=/",
        )];
        jar.set_cookies_at(&mut valid2.iter(), &clients, now);
        let expire_dash = [HeaderValue::from_static(
            "sid2=deleted; Domain=.boursobank.com; Path=/; Expires=Thu, 01-Jan-1970 00:00:00 GMT",
        )];
        jar.set_cookies_at(&mut expire_dash.iter(), &clients, now);
        assert_eq!(
            jar.get("sid2"),
            None,
            "cookie with past dash-formatted Expires must be removed"
        );

        // Max-Age takes precedence over Expires per RFC 6265.
        let max_age_wins = [HeaderValue::from_static(
            "sid3=fresh; Domain=.boursobank.com; Path=/; Max-Age=60; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
        )];
        jar.set_cookies_at(&mut max_age_wins.iter(), &clients, now);
        assert_eq!(jar.get("sid3").as_deref(), Some("fresh"));

        // Foreign, sibling, and single-label TLD domains must be rejected outright.
        let rejected = [
            HeaderValue::from_static("a=1; Domain=evil.example; Path=/"),
            HeaderValue::from_static("b=2; Domain=.api.boursobank.com; Path=/"),
            HeaderValue::from_static("c=3; Domain=com; Path=/"),
        ];
        jar.set_cookies_at(&mut rejected.iter(), &clients, now);
        assert_eq!(jar.get("a"), None, "foreign Domain must be rejected");
        assert_eq!(jar.get("b"), None, "sibling Domain must be rejected");
        assert_eq!(jar.get("c"), None, "TLD Domain must be rejected");
        assert!(jar.cookies(&evil).is_none());
    }
}
