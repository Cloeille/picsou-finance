//! LouveController: /api/louve. The redirect URI is the configured origin the browser is on plus
//! the SPA's callback path; request headers such as Host or X-Forwarded-* never shape it.

use axum::Json as JsonResponse;
use axum::extract::State;
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde::Deserialize;

use super::service::{self, CALLBACK_PATH};
use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult, Problem};
use crate::http::client_ip::ClientIp;
use crate::http::json::{Errors, Json, Validate};
use crate::http::rate_limit::{Policy, policies};

/// The browser's origin is not one of the instance's configured origins.
const ORIGIN_NOT_ALLOWED: &str = "ORIGIN_NOT_ALLOWED";
/// The configured origin is plain HTTP on a non-loopback host.
const ORIGIN_NOT_SECURE: &str = "ORIGIN_NOT_SECURE";
const LOOPBACK_HOSTS: &[&str] = &["localhost", "127.0.0.1", "[::1]", "::1"];
const AUTH_LIMITED: &str =
    "Too many Louve Invest connection attempts. Please wait before retrying.";

/// iss is required: Louve advertises RFC 9207, so a callback without it is refused.
#[derive(Debug, Deserialize)]
pub struct CompleteRequest {
    code: Option<String>,
    state: Option<String>,
    iss: Option<String>,
}

impl Validate for CompleteRequest {
    fn validate(&self, errors: &mut Errors) {
        errors
            .not_blank("code", self.code.as_deref())
            .size("code", self.code.as_deref(), 0, 2048)
            .not_blank("state", self.state.as_deref())
            .size("state", self.state.as_deref(), 0, 512)
            .not_blank("iss", self.iss.as_deref())
            .size("iss", self.iss.as_deref(), 0, 200);
    }
}

fn too_many(message: &str) -> Response {
    Problem::with_detail(StatusCode::TOO_MANY_REQUESTS, message).into_response()
}

fn limited(state: &AppState, policy: &Policy, ip: &ClientIp) -> bool {
    !state.limits.try_consume(policy, &ip.as_java_string())
}

fn bad_request(code: &str, message: &str) -> Response {
    Problem::with_detail(StatusCode::BAD_REQUEST, message)
        .code(code)
        .into_response()
}

pub async fn start(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    headers: HeaderMap,
) -> AppResult<Response> {
    if limited(&state, &policies::LOUVE_AUTH, &ip) {
        return Ok(too_many(AUTH_LIMITED));
    }
    let origin = headers.get(header::ORIGIN).and_then(|v| v.to_str().ok());
    let Some(origin) = allowed_origin(&state, origin).await else {
        return Ok(bad_request(
            ORIGIN_NOT_ALLOWED,
            "Add this Picsou address to the allowed origins in Administration > Security, then connect Louve Invest again.",
        ));
    };
    let Some(redirect_uri) = redirect_uri(&origin) else {
        return Ok(bad_request(
            ORIGIN_NOT_SECURE,
            "Louve Invest needs Picsou to be reached over HTTPS.",
        ));
    };
    let started = service::start_connection(&state, auth.member_id, &redirect_uri)
        .await
        .map_err(AppError::from)?;
    Ok(JsonResponse(started).into_response())
}

pub async fn complete(
    State(state): State<AppState>,
    auth: Auth,
    ip: ClientIp,
    Json(body): Json<CompleteRequest>,
) -> AppResult<Response> {
    if limited(&state, &policies::LOUVE_AUTH, &ip) {
        return Ok(too_many(AUTH_LIMITED));
    }
    let status = service::complete_connection(
        &state,
        auth.member_id,
        body.code.as_deref().unwrap_or_default(),
        body.state.as_deref().unwrap_or_default(),
        body.iss.as_deref().unwrap_or_default(),
    )
    .await
    .map_err(AppError::from)?;
    Ok(JsonResponse(status).into_response())
}

pub async fn sync(State(state): State<AppState>, auth: Auth, ip: ClientIp) -> AppResult<Response> {
    if limited(&state, &policies::SYNC, &ip) {
        return Ok(too_many(
            "Too many Louve Invest synchronization requests. Please wait before retrying.",
        ));
    }
    let status = service::sync(&state, auth.member_id)
        .await
        .map_err(AppError::from)?;
    Ok(JsonResponse(status).into_response())
}

pub async fn status(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<JsonResponse<service::ConnectionStatusResponse>> {
    Ok(JsonResponse(
        service::get_status(&state, auth.member_id).await?,
    ))
}

/// Disconnects and revokes the grant on Louve (best effort).
pub async fn disconnect(State(state): State<AppState>, auth: Auth) -> AppResult<StatusCode> {
    service::delete_connection(&state, auth.member_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

/// The browser's Origin when it is one of the instance's public origins (the CORS policy: the
/// Administration > Security list, else ALLOWED_ORIGINS), both sides in normal form.
async fn allowed_origin(state: &AppState, origin: Option<&str>) -> Option<String> {
    let wanted = normalize(origin?)?;
    let allowed = crate::http::cors_origins(state).await?;
    allowed
        .iter()
        .any(|a| normalize(a).as_deref() == Some(wanted.as_str()))
        .then_some(wanted)
}

/// An origin with its scheme and host lower-cased, its default port and a bare trailing slash
/// dropped. None for a blank value, a wildcard or anything that is not scheme://host[:port].
fn normalize(origin: &str) -> Option<String> {
    if origin.trim().is_empty() || origin.contains('*') {
        return None;
    }
    let parsed = url::Url::parse(origin.trim()).ok()?;
    let host = parsed.host_str().filter(|h| !h.is_empty())?.to_lowercase();
    let scheme = parsed.scheme().to_lowercase();
    let port = match parsed.port() {
        Some(443) if scheme == "https" => None,
        Some(80) if scheme == "http" => None,
        other => other,
    };
    let path = match parsed.path() {
        "/" | "" => "",
        path => path,
    };
    let query = parsed.query().map(|q| format!("?{q}")).unwrap_or_default();
    let fragment = parsed
        .fragment()
        .map(|f| format!("#{f}"))
        .unwrap_or_default();
    let user_info = if parsed.username().is_empty() && parsed.password().is_none() {
        String::new()
    } else {
        match parsed.password() {
            Some(password) => format!("{}:{password}@", parsed.username()),
            None => format!("{}@", parsed.username()),
        }
    };
    let port = port.map(|p| format!(":{p}")).unwrap_or_default();
    Some(format!(
        "{scheme}://{user_info}{host}{port}{path}{query}{fragment}"
    ))
}

/// origin plus the callback path, when the origin is HTTPS, or plain HTTP on a loopback host.
fn redirect_uri(origin: &str) -> Option<String> {
    let parsed = url::Url::parse(origin).ok()?;
    let scheme = parsed.scheme().to_lowercase();
    let host = parsed.host_str().unwrap_or_default().to_lowercase();
    let secure = scheme == "https" || (scheme == "http" && LOOPBACK_HOSTS.contains(&host.as_str()));
    let bare = matches!(parsed.path(), "" | "/")
        && parsed.query().is_none()
        && parsed.fragment().is_none()
        && parsed.username().is_empty()
        && parsed.password().is_none();
    if !secure || host.is_empty() || !bare {
        return None;
    }
    let port = parsed.port().map(|p| format!(":{p}")).unwrap_or_default();
    Some(format!("{scheme}://{host}{port}{CALLBACK_PATH}"))
}

#[cfg(test)]
mod tests {
    use super::{normalize, redirect_uri};

    #[test]
    fn a_default_port_on_either_side_still_matches() {
        assert_eq!(
            normalize("https://picsou.example:443").as_deref(),
            Some("https://picsou.example")
        );
        assert_eq!(
            normalize("http://localhost:80").as_deref(),
            Some("http://localhost")
        );
        assert_eq!(
            normalize("https://PICSOU.example:443/").as_deref(),
            Some("https://picsou.example")
        );
        assert_eq!(
            normalize("https://picsou.example:8443").as_deref(),
            Some("https://picsou.example:8443")
        );
        assert_eq!(normalize(" "), None);
        assert_eq!(normalize("*"), None);
    }

    #[test]
    fn plain_http_is_only_accepted_on_loopback() {
        assert_eq!(
            redirect_uri("http://127.0.0.1:5173").as_deref(),
            Some("http://127.0.0.1:5173/sync/louve/callback")
        );
        assert_eq!(
            redirect_uri("http://localhost:5173").as_deref(),
            Some("http://localhost:5173/sync/louve/callback")
        );
        assert_eq!(redirect_uri("http://192.0.2.10:8080"), None);
        assert_eq!(
            redirect_uri("https://Picsou.Example").as_deref(),
            Some("https://picsou.example/sync/louve/callback")
        );
        assert_eq!(redirect_uri("https://picsou.example/path"), None);
        assert_eq!(redirect_uri("https://user@picsou.example"), None);
    }
}
