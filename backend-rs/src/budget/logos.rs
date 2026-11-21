//! MerchantController and MerchantLogoService: the opt-in brand-logo proxy. Three gates in order:
//! a per-IP rate limit (429), the member's opt-in (404, so a disabled feature looks like a
//! missing logo), then the cached fetch (404 when the brand is unknown or has no logo).

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Duration;

use axum::body::Body;
use axum::extract::State;
use axum::http::{HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use bytes::Bytes;
use chrono::{DateTime, Utc};

use super::settings;
use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::http::client_ip::ClientIp;
use crate::http::json::Path;
use crate::http::rate_limit::policies;
use crate::routes::Routes;

/// A present logo is kept a day, a miss an hour.
const HIT_TTL_SECONDS: i64 = 86_400;
const MISS_TTL_SECONDS: i64 = 3_600;
const MAX_BYTES: usize = 1024 * 1024;

pub fn register(routes: &mut Routes) {
    routes.get("/api/merchants/{id}/logo", logo);
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LogoImage {
    pub bytes: Bytes,
    pub content_type: String,
}

/// MerchantLogoPort: fetches a domain's logo; None on any failure, never an error.
#[async_trait::async_trait]
pub trait LogoSource: Send + Sync {
    async fn fetch(&self, logo_domain: &str) -> Option<LogoImage>;
}

/// DuckDuckGo's public icon service (no API key): best effort, 5 s timeout, 1 MB cap. The
/// domain is only ever a path segment of the fixed host.
pub struct DuckDuckGo {
    base_url: String,
    client: reqwest::Client,
}

impl Default for DuckDuckGo {
    fn default() -> Self {
        Self::new("https://icons.duckduckgo.com")
    }
}

impl DuckDuckGo {
    pub fn new(base_url: &str) -> Self {
        Self {
            base_url: base_url.trim_end_matches('/').to_owned(),
            client: reqwest::Client::builder()
                .redirect(reqwest::redirect::Policy::none())
                .timeout(Duration::from_secs(5))
                .build()
                .unwrap_or_default(),
        }
    }
}

#[async_trait::async_trait]
impl LogoSource for DuckDuckGo {
    async fn fetch(&self, logo_domain: &str) -> Option<LogoImage> {
        let domain = logo_domain.trim().to_lowercase();
        if domain.is_empty() {
            return None;
        }
        let segment = percent_encoding::utf8_percent_encode(&domain, PATH_SEGMENT).to_string();
        let response = self
            .client
            .get(format!("{}/ip3/{segment}.ico", self.base_url))
            .send()
            .await
            .ok()?;
        if !response.status().is_success() {
            return None;
        }
        let content_type = response
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .map_or_else(|| "image/png".to_owned(), str::to_owned);
        if response
            .content_length()
            .is_some_and(|l| l > MAX_BYTES as u64)
        {
            return None;
        }
        let bytes = response.bytes().await.ok()?;
        if bytes.is_empty() || bytes.len() > MAX_BYTES {
            return None;
        }
        Some(LogoImage {
            bytes,
            content_type,
        })
    }
}

/// UriUtils.encodePathSegment: everything but RFC 3986 pchar is percent-encoded.
const PATH_SEGMENT: &percent_encoding::AsciiSet = &percent_encoding::NON_ALPHANUMERIC
    .remove(b'-')
    .remove(b'.')
    .remove(b'_')
    .remove(b'~')
    .remove(b'!')
    .remove(b'$')
    .remove(b'&')
    .remove(b'\'')
    .remove(b'(')
    .remove(b')')
    .remove(b'*')
    .remove(b'+')
    .remove(b',')
    .remove(b';')
    .remove(b'=')
    .remove(b':')
    .remove(b'@');

struct CachedLogo {
    logo: Option<LogoImage>,
    expires_at: DateTime<Utc>,
}

/// Brand id to the last fetch, present or missing.
#[derive(Default)]
pub struct LogoCache(Mutex<HashMap<i64, CachedLogo>>);

impl LogoCache {
    fn get(&self, brand_id: i64, now: DateTime<Utc>) -> Option<Option<LogoImage>> {
        let cache = self.0.lock().unwrap_or_else(|e| e.into_inner());
        cache
            .get(&brand_id)
            .filter(|c| now <= c.expires_at)
            .map(|c| c.logo.clone())
    }

    fn put(&self, brand_id: i64, logo: Option<LogoImage>, now: DateTime<Utc>) {
        let ttl = if logo.is_some() {
            HIT_TTL_SECONDS
        } else {
            MISS_TTL_SECONDS
        };
        self.0.lock().unwrap_or_else(|e| e.into_inner()).insert(
            brand_id,
            CachedLogo {
                logo,
                expires_at: now + chrono::Duration::seconds(ttl),
            },
        );
    }

    /// Drops every cached fetch.
    pub fn clear(&self) {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).clear();
    }
}

/// MerchantLogoService.getLogo: served from memory within the TTL, else fetched once.
pub async fn get_logo(state: &AppState, brand_id: i64) -> AppResult<Option<LogoImage>> {
    let now = state.clock.now();
    if let Some(cached) = state.budget.logo_cache.get(brand_id, now) {
        return Ok(cached);
    }
    let kb = state.ledger.knowledge_base(&state.db).await?;
    let domain = kb
        .find_by_id(brand_id)
        .and_then(|b| b.logo_domain.clone())
        .filter(|d| !d.trim().is_empty());
    let Some(domain) = domain else {
        state.budget.logo_cache.put(brand_id, None, now);
        return Ok(None);
    };
    let fetched = state.budget.logos.get().fetch(&domain).await;
    state.budget.logo_cache.put(brand_id, fetched.clone(), now);
    Ok(fetched)
}

fn empty(status: StatusCode) -> Response {
    status.into_response()
}

async fn logo(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Path(id): Path<i64>,
) -> AppResult<Response> {
    if !state
        .limits
        .try_consume(&policies::LOGO, &ip.as_java_string())
    {
        return Ok(empty(StatusCode::TOO_MANY_REQUESTS));
    }
    if !settings::logo_fetch_enabled(&state.db, auth.member_id).await? {
        return Ok(empty(StatusCode::NOT_FOUND));
    }
    let Some(image) = get_logo(&state, id).await? else {
        return Ok(empty(StatusCode::NOT_FOUND));
    };
    let content_type = image
        .content_type
        .parse::<mime_guess::mime::Mime>()
        .map_or_else(|_| "image/png".to_owned(), |m| m.to_string());
    let mut response = Response::new(Body::from(image.bytes));
    let headers = response.headers_mut();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_str(&content_type).unwrap_or(HeaderValue::from_static("image/png")),
    );
    headers.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("max-age=86400, private"),
    );
    Ok(response)
}
