//! Helpers the identity handlers share: audit context, the re-authentication proof, profile
//! validation messages.

use axum::extract::{FromRequest, Request};
use axum::http::{HeaderMap, StatusCode, header};
use serde::de::DeserializeOwned;

use crate::AppState;
use crate::auth::AppUser;
use crate::auth::cookies;
use crate::auth::jwt::TokenType;
use crate::error::{AppError, Problem};
use crate::http::client_ip::{ClientIp, RequestOrigin};
use crate::http::json::{Errors, Lang, read_json};
use crate::setup_auth::audit::{self, AuditContext};

pub fn user_agent(headers: &HeaderMap) -> Option<&str> {
    headers
        .get(header::USER_AGENT)
        .and_then(|v| v.to_str().ok())
}

/// SetupAuditService.record with the request's client address and user agent.
pub async fn audit(
    state: &AppState,
    event: &str,
    actor: Option<&str>,
    ip: &ClientIp,
    headers: &HeaderMap,
    details: Option<&str>,
) {
    let ip = ip.as_java_string();
    let ctx = AuditContext {
        ip: &ip,
        user_agent: user_agent(headers),
    };
    audit::record(state, event, actor, &ctx, details).await;
}

/// ReAuthFailedException: 401 with title and code REAUTH_FAILED.
pub fn reauth_failed(message: &str) -> AppError {
    AppError::Problem(
        Problem::with_detail(StatusCode::UNAUTHORIZED, message)
            .title("REAUTH_FAILED")
            .code("REAUTH_FAILED"),
    )
}

/// ReAuthService.hasValidProof: a reauth_proof cookie for this account and token version.
pub fn has_valid_proof(state: &AppState, user: &AppUser, headers: &HeaderMap) -> bool {
    let Some(token) =
        cookies::read(headers, cookies::REAUTH_PROOF).filter(|t| !t.trim().is_empty())
    else {
        return false;
    };
    let Ok(claims) = state.jwt.parse(&token) else {
        return false;
    };
    claims.is(TokenType::Reauth)
        && claims.uid == Some(user.id)
        && claims.tv == Some(user.token_version)
}

pub fn require_proof(
    state: &AppState,
    user: &AppUser,
    headers: &HeaderMap,
) -> Result<(), AppError> {
    if has_valid_proof(state, user, headers) {
        Ok(())
    } else {
        Err(reauth_failed("re-auth proof missing or invalid"))
    }
}

/// A JSON body whose validation needs more than the body (the clock for @Future and @Past):
/// the handler validates it with errors().
pub struct JsonBody<T> {
    pub value: T,
    lang: Lang,
}

impl<T> JsonBody<T> {
    pub fn errors(&self) -> Errors {
        Errors::new(self.lang)
    }
}

impl<T: DeserializeOwned, S: Send + Sync> FromRequest<S> for JsonBody<T> {
    type Rejection = AppError;

    async fn from_request(request: Request, state: &S) -> Result<Self, Self::Rejection> {
        let lang = Lang::from_accept_language(
            request
                .headers()
                .get(header::ACCEPT_LANGUAGE)
                .and_then(|v| v.to_str().ok()),
        );
        Ok(Self {
            value: read_json(request, state).await?,
            lang,
        })
    }
}

/// java.net.URLEncoder.encode(s, UTF_8): unreserved stay, a space is +, the rest %XX.
pub fn java_url_encode(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => {
                out.push(char::from(byte));
            }
            b' ' => out.push('+'),
            other => out.push_str(&format!("%{other:02X}")),
        }
    }
    out
}

/// LogSanitizer.safe: a value from a request or an IdP, without line breaks or control characters.
pub fn log_safe(value: &str) -> String {
    value
        .chars()
        .map(|c| if c.is_control() { '_' } else { c })
        .collect()
}

/// A 302 as Tomcat's sendRedirect writes it: a path becomes an absolute URL on the request's
/// origin, a URL with a scheme is kept.
pub fn redirect(origin: &RequestOrigin, location: &str) -> axum::response::Response {
    use axum::response::IntoResponse;
    let mut response = StatusCode::FOUND.into_response();
    let absolute = if location.starts_with('/') {
        format!("{}{location}", origin.base_url())
    } else {
        location.to_owned()
    };
    if let Ok(value) = axum::http::HeaderValue::from_str(&absolute) {
        response.headers_mut().insert(header::LOCATION, value);
    }
    response
}
