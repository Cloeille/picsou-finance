//! WiseClient: profiles, then the SAVINGS balances of each profile. Redirects are refused, bodies
//! are capped while they are read, and the token travels only in the Authorization header.

use std::time::Duration;

use http::header::{ACCEPT, AUTHORIZATION};

use super::error::WiseError;
use super::json::{self, Profile};
use super::{MAX_TOKEN_CHARS, WiseJar};
use crate::ledger::sync_values::java_trim;

const MAX_PROFILES_BODY: usize = 256_000;
const MAX_BALANCES_BODY: usize = 1_000_000;
const UNREACHABLE: &str = "Could not reach Wise. Try again in a moment.";

/// The client every Wise request goes through: no redirects, 10 s to connect, 30 s per request.
pub(super) fn http_client() -> reqwest::Client {
    reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(30))
        .build()
        .expect("the Wise HTTP client builds with the default TLS backend")
}

/// Every jar on every profile the token can read.
pub(super) async fn fetch_jars(
    http: &reqwest::Client,
    base_url: &str,
    api_token: &str,
) -> Result<Vec<WiseJar>, WiseError> {
    let authorization = format!("Bearer {}", require_token(api_token)?);
    let base_url = base_url.trim_end_matches('/');
    let profiles = json::profiles(
        &get(
            http,
            &format!("{base_url}/v2/profiles"),
            &authorization,
            MAX_PROFILES_BODY,
            "profiles",
        )
        .await?,
    )?;
    let mut jars = Vec::new();
    for profile in profiles {
        let Profile { id, .. } = profile;
        let body = get(
            http,
            &format!("{base_url}/v4/profiles/{id}/balances?types=SAVINGS"),
            &authorization,
            MAX_BALANCES_BODY,
            "balances",
        )
        .await?;
        jars.extend(json::jars(&body, profile)?);
    }
    Ok(jars)
}

/// A Wise personal token is a UUID. Anything outside letters, digits and dashes is refused before
/// it can reach a header, so a pasted value cannot inject a second header line.
pub(super) fn require_token(api_token: &str) -> Result<&str, WiseError> {
    let token = java_trim(api_token);
    if token.is_empty()
        || token.len() > MAX_TOKEN_CHARS
        || !token
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-')
    {
        return Err(WiseError::coded(
            "This does not look like a Wise API token. Copy it again from Wise.",
            "INVALID_TOKEN",
        ));
    }
    Ok(token)
}

async fn get(
    http: &reqwest::Client,
    url: &str,
    authorization: &str,
    max_body: usize,
    call: &str,
) -> Result<String, WiseError> {
    let response = http
        .get(url)
        .header(AUTHORIZATION, authorization)
        .header(ACCEPT, "application/json")
        .send()
        .await
        .map_err(|_| WiseError::sync(UNREACHABLE))?;
    let status = response.status().as_u16();
    // Read before the status is looked at, as Java's body handler does for every status.
    let body = read_bounded(response, max_body).await?;
    match status {
        401 => Err(WiseError::coded(
            "Wise refused the API token. It may have been revoked. Create a new token in Wise and connect again.",
            "SESSION_EXPIRED",
        )),
        403 => Err(WiseError::coded(
            "Wise refused to share this data with the token. Create a read-only personal token in Wise and connect again.",
            "SESSION_EXPIRED",
        )),
        429 => Err(WiseError::coded(
            "Wise is limiting requests right now. Try again in a few minutes.",
            "RATE_LIMITED",
        )),
        300..=399 => Err(WiseError::sync(
            "Wise redirected the request, which Picsou refuses.",
        )),
        200..=299 => Ok(body),
        _ => {
            tracing::warn!("Wise {call} request failed with HTTP {status}");
            Err(WiseError::sync(format!(
                "Wise could not complete the request (HTTP {status})."
            )))
        }
    }
}

/// BoundedUtf8Body: stops at max_body bytes, a lying Content-Length included.
async fn read_bounded(
    mut response: reqwest::Response,
    max_body: usize,
) -> Result<String, WiseError> {
    let too_large = || WiseError::sync("Wise returned a response that is too large.");
    if response
        .content_length()
        .is_some_and(|length| length > max_body as u64)
    {
        return Err(too_large());
    }
    let mut body = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| WiseError::sync(UNREACHABLE))?
    {
        if body.len() + chunk.len() > max_body {
            return Err(too_large());
        }
        body.extend_from_slice(&chunk);
    }
    Ok(String::from_utf8_lossy(&body).into_owned())
}
