//! The one multipart/form-data read the CSV uploads need: a named file part, as Spring's
//! @RequestParam MultipartFile resolves it (10 MB, as spring.servlet.multipart allows).

use axum::extract::Request;
use http::StatusCode;

use crate::error::{AppError, AppResult};

const MAX_REQUEST_BYTES: usize = 10 * 1024 * 1024;

fn boundary(content_type: &str) -> Option<String> {
    let mut params = content_type.split(';');
    let essence = params.next()?.trim();
    if !essence.eq_ignore_ascii_case("multipart/form-data") {
        return None;
    }
    params.find_map(|p| {
        let (key, value) = p.split_once('=')?;
        key.trim()
            .eq_ignore_ascii_case("boundary")
            .then(|| value.trim().trim_matches('"').to_owned())
    })
}

fn find(haystack: &[u8], needle: &[u8], from: usize) -> Option<usize> {
    haystack
        .get(from..)?
        .windows(needle.len())
        .position(|w| w == needle)
        .map(|p| p + from)
}

/// The name and filename parameters of a Content-Disposition header.
fn disposition(headers: &str) -> (Option<String>, bool) {
    let mut name = None;
    let mut has_filename = false;
    for line in headers.split("\r\n") {
        let Some((key, value)) = line.split_once(':') else {
            continue;
        };
        if !key.trim().eq_ignore_ascii_case("content-disposition") {
            continue;
        }
        for param in value.split(';').skip(1) {
            if let Some((k, v)) = param.split_once('=') {
                match k.trim().to_ascii_lowercase().as_str() {
                    "name" => name = Some(v.trim().trim_matches('"').to_owned()),
                    "filename" => has_filename = true,
                    _ => {}
                }
            }
        }
    }
    (name, has_filename)
}

/// The file part's content as text (malformed UTF-8 replaced, as InputStreamReader does).
pub async fn file_part(request: Request, field: &str) -> AppResult<String> {
    let content_type = request
        .headers()
        .get(http::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or_default()
        .to_owned();
    let Some(boundary) = boundary(&content_type) else {
        return Err(AppError::Internal(
            "Current request is not a multipart request".to_owned(),
        ));
    };
    let body = axum::body::to_bytes(request.into_body(), MAX_REQUEST_BYTES)
        .await
        .map_err(|_| {
            AppError::status(
                StatusCode::PAYLOAD_TOO_LARGE,
                "Maximum upload size exceeded",
            )
        })?;
    let delimiter = format!("--{boundary}").into_bytes();
    let malformed = || AppError::Internal("Failed to parse multipart servlet request".to_owned());
    let mut cursor = find(&body, &delimiter, 0).ok_or_else(malformed)? + delimiter.len();
    loop {
        if body[cursor..].starts_with(b"--") {
            break;
        }
        let headers_start = cursor + 2;
        let headers_end = find(&body, b"\r\n\r\n", headers_start).ok_or_else(malformed)?;
        let headers = String::from_utf8_lossy(&body[headers_start..headers_end]);
        let content_start = headers_end + 4;
        let mut closing = b"\r\n".to_vec();
        closing.extend_from_slice(&delimiter);
        let content_end = find(&body, &closing, content_start).ok_or_else(malformed)?;
        let (name, has_filename) = disposition(&headers);
        if has_filename && name.as_deref() == Some(field) {
            return Ok(String::from_utf8_lossy(&body[content_start..content_end]).into_owned());
        }
        cursor = content_end + closing.len();
    }
    Err(AppError::BadRequest(format!(
        "Required part '{field}' is not present."
    )))
}
