//! SimplefinClient over HTTPS: redirects refused, credentials only in the Authorization header,
//! bodies capped while they are read.

use reqwest::Method;
use url::Url;

use super::json::{self, AccountSet};
use super::urls;
use super::{Hooks, SyncError};
use crate::ledger::sync_values::java_trim;
use chrono::NaiveDate;

const MAX_CLAIM_BODY: usize = 8_192;
const MAX_ACCOUNTS_BODY: usize = 2_000_000;

const UNREACHABLE: &str = "Could not reach the SimpleFIN server. Try again in a moment.";
const TOO_LARGE: &str = "SimpleFIN returned a response that is too large.";

struct Response {
    status: u16,
    body: String,
}

/// One exchange. The body is read up to max_body bytes, a lying Content-Length included.
async fn send(
    hooks: &Hooks,
    method: Method,
    url: Url,
    authorization: Option<&str>,
    max_body: usize,
) -> Result<Response, SyncError> {
    let url = hooks.dispatch(url);
    let mut request = hooks
        .http
        .request(method.clone(), url)
        .header(reqwest::header::ACCEPT, "application/json");
    if method == Method::POST {
        request = request.header(reqwest::header::CONTENT_LENGTH, "0");
    }
    if let Some(authorization) = authorization {
        request = request.header(reqwest::header::AUTHORIZATION, authorization);
    }
    // The error is never logged or wrapped: reqwest's message quotes the URL.
    let mut response = request
        .send()
        .await
        .map_err(|_| SyncError::new(UNREACHABLE))?;
    let status = response.status().as_u16();
    if response
        .content_length()
        .is_some_and(|length| length > max_body as u64)
    {
        return Err(SyncError::new(TOO_LARGE));
    }
    let mut body = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| SyncError::new(UNREACHABLE))?
    {
        if body.len() + chunk.len() > max_body {
            return Err(SyncError::new(TOO_LARGE));
        }
        body.extend_from_slice(&chunk);
    }
    Ok(Response {
        status,
        body: String::from_utf8_lossy(&body).into_owned(),
    })
}

fn require_success(response: &Response, call: &str) -> Result<(), SyncError> {
    let status = response.status;
    if (300..400).contains(&status) {
        return Err(SyncError::new(
            "The SimpleFIN server redirected the request, which Picsou refuses.",
        ));
    }
    if !(200..300).contains(&status) {
        tracing::warn!("SimpleFIN {call} failed with HTTP {status}");
        return Err(SyncError::new(format!(
            "SimpleFIN could not complete the request (HTTP {status})."
        )));
    }
    Ok(())
}

/// Exchanges a setup token for an access URL. The token cannot be claimed twice.
pub async fn claim(hooks: &Hooks, setup_token: &str) -> Result<String, SyncError> {
    let url = urls::claim_url(setup_token)?;
    let response = send(hooks, Method::POST, url, None, MAX_CLAIM_BODY).await?;
    if response.status == 403 {
        return Err(SyncError::new(
            "This SimpleFIN setup token is invalid or has already been used. Create a new one and paste it again.",
        ));
    }
    require_success(&response, "claim")?;
    let mut access_url = java_trim(&response.body);
    if access_url.len() >= 2 && access_url.starts_with('"') && access_url.ends_with('"') {
        access_url = java_trim(&access_url[1..access_url.len() - 1]);
    }
    urls::require_access_url(access_url)
}

/// Accounts and posted transactions on or after start.
pub async fn fetch_accounts(
    hooks: &Hooks,
    access_url: &str,
    start: NaiveDate,
) -> Result<AccountSet, SyncError> {
    let request = urls::accounts_request(access_url, start)?;
    let response = send(
        hooks,
        Method::GET,
        request.url,
        Some(&request.authorization),
        MAX_ACCOUNTS_BODY,
    )
    .await?;
    if response.status == 403 {
        return Err(SyncError::coded(
            "SimpleFIN refused the stored access. It may have been revoked. Disconnect and connect with a new setup token.",
            "SESSION_EXPIRED",
        ));
    }
    if response.status == 402 {
        return Err(SyncError::new(
            "SimpleFIN requires payment before it will return accounts.",
        ));
    }
    require_success(&response, "accounts")?;
    let set = json::parse(&response.body)?;
    if set.accounts.is_empty() && !set.errors.is_empty() {
        return Err(SyncError::new(set.errors.join(" ")));
    }
    Ok(set)
}
