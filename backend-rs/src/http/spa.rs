//! Serves the built React app (SpaWebConfig): an existing file as is, a missing file with a
//! static extension as 404, any other path as index.html. Hashed assets under /assets/ are cached
//! a year; everything else is no-cache with a content-digest ETag.

use std::path::{Component, Path, PathBuf};
use std::time::{Duration, SystemTime};

use axum::body::Body;
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::Response;
use md5::{Digest, Md5};

const INDEX: &str = "index.html";
const ASSET_MAX_AGE: Duration = Duration::from_secs(365 * 86_400);
const STATIC_EXTENSIONS: &[&str] = &[
    "js", "css", "png", "jpg", "jpeg", "gif", "ico", "svg", "woff", "woff2", "ttf", "eot",
];

fn has_static_extension(path: &str) -> bool {
    path.rsplit_once('.').is_some_and(|(_, ext)| {
        STATIC_EXTENSIONS
            .iter()
            .any(|e| e.eq_ignore_ascii_case(ext))
    })
}

fn is_hashed_asset(path: &str) -> bool {
    path.starts_with("/assets/") && has_static_extension(path)
}

/// The file under the location, refusing any path that would leave it.
fn resolve(location: &Path, path: &str) -> Option<PathBuf> {
    let mut out = location.to_path_buf();
    for component in Path::new(path.trim_start_matches('/')).components() {
        match component {
            Component::Normal(part) => out.push(part),
            Component::CurDir => {}
            _ => return None,
        }
    }
    Some(out)
}

async fn readable_file(path: &Path) -> Option<(Vec<u8>, SystemTime)> {
    let meta = tokio::fs::metadata(path).await.ok()?;
    if !meta.is_file() {
        return None;
    }
    let content = tokio::fs::read(path).await.ok()?;
    Some((content, meta.modified().unwrap_or(SystemTime::UNIX_EPOCH)))
}

/// None means Spring's 404 (no such static resource).
pub async fn serve(location: &Path, path: &str, request_headers: &HeaderMap) -> Option<Response> {
    let requested = if path == "/" || path.is_empty() {
        format!("/{INDEX}")
    } else {
        path.to_owned()
    };
    let file = resolve(location, &requested)?;
    let (served, (content, modified)) = match readable_file(&file).await {
        Some(found) => (file, found),
        None if has_static_extension(&requested) => return None,
        None => {
            let index = location.join(INDEX);
            let found = readable_file(&index).await?;
            (index, found)
        }
    };
    let hashed = is_hashed_asset(path);
    let seconds = modified
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let etag = if hashed {
        format!("W/\"{seconds:x}-{:x}\"", content.len())
    } else {
        format!("W/\"{}\"", hex::encode(Md5::digest(&content)))
    };
    let not_modified = request_headers
        .get(header::IF_NONE_MATCH)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|v| v.split(',').any(|t| t.trim() == etag || t.trim() == "*"));
    let mut builder = Response::builder().header(header::ETAG, &etag).header(
        header::LAST_MODIFIED,
        httpdate::fmt_http_date(SystemTime::UNIX_EPOCH + Duration::from_secs(seconds)),
    );
    if hashed {
        builder = builder
            .header(
                header::CACHE_CONTROL,
                format!("max-age={}, public, immutable", ASSET_MAX_AGE.as_secs()),
            )
            .header(
                header::EXPIRES,
                httpdate::fmt_http_date(SystemTime::now() + ASSET_MAX_AGE),
            );
    } else {
        builder = builder.header(header::CACHE_CONTROL, "no-cache");
    }
    if not_modified {
        return builder
            .status(StatusCode::NOT_MODIFIED)
            .body(Body::empty())
            .ok();
    }
    let mime = mime_guess::from_path(&served).first_or_octet_stream();
    let content_type = if mime.type_() == "text" || mime.essence_str() == "application/javascript" {
        format!("{};charset=UTF-8", mime.essence_str())
    } else {
        mime.essence_str().to_owned()
    };
    let mut response = builder
        .status(StatusCode::OK)
        .header(header::CONTENT_LENGTH, content.len())
        .body(Body::from(content))
        .ok()?;
    if let Ok(value) = HeaderValue::from_str(&content_type) {
        response.headers_mut().insert(header::CONTENT_TYPE, value);
    }
    Some(response)
}
