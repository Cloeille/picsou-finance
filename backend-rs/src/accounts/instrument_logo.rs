//! InstrumentLogoController and the read side of InstrumentLogoService: the stored marks of
//! shares and funds, served by Picsou itself. Fetching them is track T4's.

use std::collections::{BTreeSet, HashMap};

use axum::extract::{Query, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use chrono::{DateTime, Utc};
use percent_encoding::{AsciiSet, NON_ALPHANUMERIC, utf8_percent_encode};
use serde::Deserialize;

use crate::AppState;
use crate::auth::Auth;
use crate::db::{self, DbExec};
use crate::error::AppResult;
use crate::http::json::Path;
use crate::ledger::sync_values::java_is_blank;
use crate::routes::Routes;

const PATH: &str = "/api/instrument-logos/";
const MAX_AGE_SECONDS: u64 = 30 * 24 * 3600;

/// UriUtils.encodePathSegment keeps RFC 3986 pchar: unreserved, sub-delims, ':' and '@'.
const PATH_SEGMENT: &AsciiSet = &NON_ALPHANUMERIC
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

pub fn register(routes: &mut Routes) {
    routes.get("/api/instrument-logos/{ticker}", logo);
}

/// Where the browser loads a holding's mark from (HoldingLogoUrls).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LogoUrls {
    pub light: Option<String>,
    pub dark: Option<String>,
}

/// storedUrls: the versioned URL of every stored mark among the upper-cased tickers.
pub async fn stored_urls<'e>(
    db: impl DbExec<'e>,
    tickers: &BTreeSet<String>,
) -> sqlx::Result<HashMap<String, LogoUrls>> {
    if tickers.is_empty() {
        return Ok(HashMap::new());
    }
    let keys: Vec<&str> = tickers.iter().map(String::as_str).collect();
    let rows: Vec<(String, DateTime<Utc>, Option<String>)> = db::query_as(
        "SELECT ticker, fetched_at, content_type_dark FROM instrument_logo \
         WHERE status = 'STORED' AND ticker = ANY($1) AND fetched_at IS NOT NULL",
    )
    .bind(keys.as_slice())
    .fetch_all(db)
    .await?;
    Ok(rows
        .into_iter()
        .map(|(ticker, fetched_at, dark)| {
            let base = format!(
                "{PATH}{}?v={}",
                utf8_percent_encode(&ticker, PATH_SEGMENT),
                fetched_at.timestamp()
            );
            let dark = dark.map(|_| format!("{base}&variant=dark"));
            (
                ticker,
                LogoUrls {
                    light: Some(base),
                    dark,
                },
            )
        })
        .collect())
}

#[derive(Debug, Deserialize)]
pub struct LogoQuery {
    variant: Option<String>,
}

type StoredImage = (Vec<u8>, Option<String>, Option<DateTime<Utc>>);
type LogoRow = (
    Option<Vec<u8>>,
    Option<String>,
    Option<Vec<u8>>,
    Option<String>,
    Option<DateTime<Utc>>,
);

async fn logo(
    State(state): State<AppState>,
    _auth: Auth,
    Path(ticker): Path<String>,
    Query(query): Query<LogoQuery>,
    request_headers: HeaderMap,
) -> AppResult<Response> {
    if java_is_blank(&ticker) {
        return Ok(StatusCode::NOT_FOUND.into_response());
    }
    let dark = query.variant.as_deref() == Some("dark");
    let row: Option<LogoRow> =
        db::query_as(
            "SELECT image, content_type, image_dark, content_type_dark, fetched_at FROM instrument_logo \
             WHERE ticker = $1 AND status = 'STORED' AND image IS NOT NULL",
        )
        .bind(ticker.to_uppercase())
        .fetch_optional(&state.db)
        .await?;
    let Some((Some(image), content_type, image_dark, content_type_dark, fetched_at)) = row else {
        return Ok(StatusCode::NOT_FOUND.into_response());
    };
    let (served, served_dark): (StoredImage, bool) = match image_dark {
        Some(bytes) if dark => ((bytes, content_type_dark, fetched_at), true),
        _ => ((image, content_type, fetched_at), false),
    };
    let (bytes, content_type, fetched_at) = served;
    let etag = format!(
        "\"{}{}\"",
        fetched_at.map_or(0, |t| t.timestamp()),
        if served_dark { "-dark" } else { "-light" }
    );
    let mut headers = HeaderMap::new();
    if let Some(value) = content_type.and_then(|c| HeaderValue::from_str(&c).ok()) {
        headers.insert(header::CONTENT_TYPE, value);
    }
    headers.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_str(&format!("max-age={MAX_AGE_SECONDS}, private"))
            .expect("ascii header"),
    );
    headers.insert(
        header::ETAG,
        HeaderValue::from_str(&etag).expect("ascii header"),
    );
    if not_modified(&request_headers, &etag) {
        headers.remove(header::CONTENT_TYPE);
        return Ok((StatusCode::NOT_MODIFIED, headers).into_response());
    }
    Ok((StatusCode::OK, headers, bytes).into_response())
}

/// Spring's checkNotModified on If-None-Match: weak comparison, '*' matches anything.
fn not_modified(headers: &HeaderMap, etag: &str) -> bool {
    headers
        .get_all(header::IF_NONE_MATCH)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(','))
        .map(str::trim)
        .any(|candidate| {
            candidate == "*" || candidate.strip_prefix("W/").unwrap_or(candidate) == etag
        })
}
