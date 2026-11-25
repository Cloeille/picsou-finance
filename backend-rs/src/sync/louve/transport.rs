//! LouveTransport.Http: one HTTPS exchange with Louve Invest. Redirects are refused (a token or
//! an authorization code must never follow one) and bodies are capped at 2 MB.

use std::time::Duration;

use http::header::{CONTENT_TYPE, HeaderName};

/// A portfolio or a fund sheet is a few kilobytes; a price history a few dozen.
const MAX_BODY: usize = 2_000_000;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

/// The client every Louve call goes through.
pub fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(CONNECT_TIMEOUT)
        .timeout(REQUEST_TIMEOUT)
        .build()
        .expect("a TLS backend for the Louve Invest client")
}

pub struct Response {
    pub status: u16,
    pub content_type: String,
    pub session_id: Option<String>,
    pub body: String,
}

/// Louve could not be reached, or answered more than the body cap. Carries no content.
#[derive(Debug)]
pub struct Unreachable;

const SESSION_HEADER: HeaderName = HeaderName::from_static("mcp-session-id");

pub async fn post(
    http: &reqwest::Client,
    url: &str,
    headers: &[(&str, &str)],
    body: String,
) -> Result<Response, Unreachable> {
    let mut request = http.post(url).body(body);
    for (name, value) in headers {
        request = request.header(*name, *value);
    }
    let mut response = request.send().await.map_err(|_| Unreachable)?;
    let status = response.status().as_u16();
    let header = |name: &HeaderName| {
        response
            .headers()
            .get(name)
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned)
    };
    let content_type = header(&CONTENT_TYPE).unwrap_or_default();
    let session_id = header(&SESSION_HEADER);
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|_| Unreachable)? {
        if bytes.len() + chunk.len() > MAX_BODY {
            return Err(Unreachable);
        }
        bytes.extend_from_slice(&chunk);
    }
    Ok(Response {
        status,
        content_type,
        session_id,
        body: String::from_utf8_lossy(&bytes).into_owned(),
    })
}
