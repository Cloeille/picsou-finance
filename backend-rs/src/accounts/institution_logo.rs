//! InstitutionLogoController and InstitutionLogoService: the opt-in logo of the bank behind an
//! aggregator account, fetched by domain from the icon service and cached in memory.
//!
//! Neither the domain nor the bytes are trusted: the domain only ever travels as a path segment
//! to the fixed icon host, and the bytes are served only when they are a small raster image,
//! with the type their bytes have, never the upstream's label. An SVG or HTML "icon" served
//! from this origin would run script, so it is refused.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use axum::extract::State;
use axum::response::{IntoResponse, Response};
use chrono::{DateTime, Utc};
use futures::StreamExt;
use http::{HeaderValue, StatusCode, header};
use percent_encoding::{AsciiSet, CONTROLS, utf8_percent_encode};

use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::http::client_ip::ClientIp;
use crate::http::json::Path;
use crate::http::rate_limit::policies;
use crate::ledger::accounts;
use crate::ledger::sync_values::java_is_blank;
use crate::routes::Routes;

const HIT_TTL_SECONDS: i64 = 86_400;
const MISS_TTL_SECONDS: i64 = 3_600;
/// Real favicons are a few KB; anything bigger is not one.
pub const MAX_LOGO_BYTES: usize = 128 * 1024;
const MAX_CACHE_ENTRIES: usize = 256;
const FETCH_TIMEOUT: Duration = Duration::from_secs(5);
const MAX_FETCH_BYTES: usize = 1024 * 1024;

pub fn register(routes: &mut Routes) {
    routes.get("/api/accounts/{id}/institution-logo", institution_logo);
}

/// A logo: the bytes and the MIME type to send back.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LogoImage {
    pub bytes: Vec<u8>,
    pub content_type: String,
}

/// MerchantLogoPort: a brand logo by domain, None on any failure (never an error).
#[async_trait]
pub trait MerchantLogoPort: Send + Sync {
    async fn fetch(&self, domain: &str) -> Option<LogoImage>;
}

/// UriUtils.encodePathSegment: everything but the RFC 3986 pchar set is percent-encoded.
const PATH_SEGMENT: &AsciiSet = &CONTROLS
    .add(b' ')
    .add(b'"')
    .add(b'#')
    .add(b'%')
    .add(b'/')
    .add(b'<')
    .add(b'>')
    .add(b'?')
    .add(b'[')
    .add(b'\\')
    .add(b']')
    .add(b'^')
    .add(b'`')
    .add(b'{')
    .add(b'|')
    .add(b'}');

/// DuckDuckGoLogoProvider: {base}/ip3/{domain}.ico, 5 s timeout, 1 MB body cap.
pub struct DuckDuckGoLogos {
    pub http: reqwest::Client,
    pub base_url: String,
}

#[async_trait]
impl MerchantLogoPort for DuckDuckGoLogos {
    async fn fetch(&self, domain: &str) -> Option<LogoImage> {
        if java_is_blank(domain) {
            return None;
        }
        let segment = utf8_percent_encode(&domain.trim().to_lowercase(), PATH_SEGMENT).to_string();
        let url = format!("{}/ip3/{segment}.ico", self.base_url.trim_end_matches('/'));
        let response = match self.http.get(&url).timeout(FETCH_TIMEOUT).send().await {
            Ok(r) if r.status().is_success() => r,
            Ok(_) => return None,
            Err(e) => {
                tracing::debug!("Logo fetch failed for {domain}: {e}");
                return None;
            }
        };
        let content_type = response
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("image/png")
            .to_owned();
        let mut body = Vec::new();
        let mut stream = response.bytes_stream();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.ok()?;
            if body.len() + chunk.len() > MAX_FETCH_BYTES {
                return None;
            }
            body.extend_from_slice(&chunk);
        }
        (!body.is_empty()).then_some(LogoImage {
            bytes: body,
            content_type,
        })
    }
}

/// The image re-labelled by what its bytes are, or None if it is not a small raster image.
pub fn vetted(image: LogoImage) -> Option<LogoImage> {
    let b = &image.bytes;
    if b.len() < 12 || b.len() > MAX_LOGO_BYTES {
        return None;
    }
    let content_type = if b.starts_with(&[0x89, b'P', b'N', b'G']) {
        "image/png"
    } else if b.starts_with(&[0xFF, 0xD8, 0xFF]) {
        "image/jpeg"
    } else if b.starts_with(b"GIF8") {
        "image/gif"
    } else if b.starts_with(&[0, 0, 1, 0]) {
        "image/x-icon"
    } else if b.starts_with(b"RIFF") && &b[8..12] == b"WEBP" {
        "image/webp"
    } else {
        return None;
    };
    Some(LogoImage {
        bytes: image.bytes,
        content_type: content_type.to_owned(),
    })
}

#[derive(Clone)]
struct Cached {
    logo: Option<LogoImage>,
    expires_at: DateTime<Utc>,
}

type Slot = Arc<tokio::sync::Mutex<Option<Cached>>>;

/// The per-domain cache, bounded in entries; a miss is remembered for an hour, a hit for a day.
/// One slot per domain is held while its fetch runs, so a page of cards for one bank costs one
/// upstream request.
#[derive(Default)]
pub struct InstitutionLogos {
    slots: Mutex<HashMap<String, Slot>>,
}

impl InstitutionLogos {
    pub async fn logo(
        &self,
        port: &dyn MerchantLogoPort,
        domain: &str,
        now: DateTime<Utc>,
    ) -> Option<LogoImage> {
        let slot = {
            let mut slots = self.slots.lock().unwrap_or_else(|e| e.into_inner());
            match slots.get(domain) {
                Some(slot) => Some(slot.clone()),
                None => {
                    if slots.len() >= MAX_CACHE_ENTRIES {
                        // A slot being filled is kept; an expired or empty one goes.
                        slots.retain(|_, s| {
                            s.try_lock()
                                .map_or(true, |c| c.as_ref().is_some_and(|c| now < c.expires_at))
                        });
                    }
                    if slots.len() >= MAX_CACHE_ENTRIES {
                        None
                    } else {
                        let slot = Slot::default();
                        slots.insert(domain.to_owned(), slot.clone());
                        Some(slot)
                    }
                }
            }
        };
        let Some(slot) = slot else {
            // Full of live entries: serve, do not store.
            return port.fetch(domain).await.and_then(vetted);
        };
        let mut cached = slot.lock().await;
        if let Some(c) = cached.as_ref()
            && now < c.expires_at
        {
            return c.logo.clone();
        }
        let fetched = port.fetch(domain).await.and_then(vetted);
        let ttl = if fetched.is_some() {
            HIT_TTL_SECONDS
        } else {
            MISS_TTL_SECONDS
        };
        *cached = Some(Cached {
            logo: fetched.clone(),
            expires_at: now + chrono::Duration::seconds(ttl),
        });
        fetched
    }
}

/// BudgetSettingsService.logoFetchEnabled: the member's opt-in, off without a settings row.
async fn logo_fetch_enabled(state: &AppState, member_id: i64) -> sqlx::Result<bool> {
    let enabled: Option<bool> =
        sqlx::query_scalar("SELECT logo_fetch_enabled FROM budget_settings WHERE member_id = $1")
            .bind(member_id)
            .fetch_optional(&state.db)
            .await?;
    Ok(enabled.unwrap_or(false))
}

/// Same gates as the merchant logos: the per-IP bucket, then the member's opt-in (a 404 when
/// off, so the avatar falls back to its monogram as for a missing logo).
async fn institution_logo(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Path(id): Path<i64>,
) -> AppResult<Response> {
    if !state
        .limits
        .try_consume(&policies::LOGO, &ip.as_java_string())
    {
        return Ok(StatusCode::TOO_MANY_REQUESTS.into_response());
    }
    if !logo_fetch_enabled(&state, auth.member_id).await? {
        return Ok(StatusCode::NOT_FOUND.into_response());
    }
    let Some(account) = accounts::find_by_id_and_member(&state.db, id, auth.member_id).await?
    else {
        return Ok(StatusCode::NOT_FOUND.into_response());
    };
    let Some(domain) = account.institution_domain.filter(|d| !java_is_blank(d)) else {
        return Ok(StatusCode::NOT_FOUND.into_response());
    };
    let port = DuckDuckGoLogos {
        http: state.http.clone(),
        base_url: state.config.duckduckgo_icons_url.clone(),
    };
    let Some(image) = state
        .accounts
        .institution_logos
        .logo(&port, &domain, state.clock.now())
        .await
    else {
        return Ok(StatusCode::NOT_FOUND.into_response());
    };
    let mut response = image.bytes.into_response();
    let headers = response.headers_mut();
    if let Ok(value) = HeaderValue::from_str(&image.content_type) {
        headers.insert(header::CONTENT_TYPE, value);
    }
    // A second line: rendered as a document, it would have no scripts and no origin.
    headers.insert(
        header::CONTENT_SECURITY_POLICY,
        HeaderValue::from_static("default-src 'none'; sandbox"),
    );
    headers.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("max-age=86400, private"),
    );
    Ok(response)
}
