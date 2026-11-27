//! The HTTP edge: forwarded headers, body limit, CORS, security headers, CSRF, setup gate,
//! problem bodies, compression and the SPA, in the order the Java filters run.

pub mod client_ip;
pub mod json;
pub mod rate_limit;
pub mod spa;

use std::panic::AssertUnwindSafe;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::task::{Context, Poll};

use axum::Router;
use axum::extract::{Request, State};
use axum::http::{HeaderMap, HeaderValue, Method, StatusCode, header};
use axum::middleware::{Next, from_fn, from_fn_with_state};
use axum::response::{IntoResponse, Response};
use tower_http::compression::CompressionLayer;
use tower_http::compression::predicate::{Predicate, SizeAbove};

use crate::AppState;
use crate::auth::cookies;
use crate::auth::layer::SecurityState;
use crate::error::{PendingProblem, Problem, raw};
use client_ip::RequestOrigin;
use futures::FutureExt;

const BACKEND_PREFIXES: &[&str] = &[
    "/api",
    "/oauth2",
    "/.well-known/oauth-",
    "/actuator",
    "/mcp",
];
const ANONYMOUS_BODY_PREFIXES: &[&str] = &["/api/setup", "/api/auth/", "/oauth2/register"];
const MAX_ANONYMOUS_BODY: u64 = 1024 * 1024;
const CSP: &str = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'";
const COMPRESSIBLE: &[&str] = &[
    "text/html",
    "text/plain",
    "text/css",
    "text/javascript",
    "application/javascript",
    "application/json",
    "application/manifest+json",
];

/// SpaPaths.isBackend: the URL space the SPA never answers.
pub fn is_backend_path(path: &str) -> bool {
    BACKEND_PREFIXES.iter().any(|p| path.starts_with(p))
}

/// The percent-decoded path routing decisions use (/%61pi is /api).
pub fn decoded_path(raw: &str) -> String {
    percent_encoding::percent_decode_str(raw)
        .decode_utf8_lossy()
        .into_owned()
}

fn header_str(headers: &HeaderMap, name: header::HeaderName) -> Option<&str> {
    headers.get(name).and_then(|v| v.to_str().ok())
}

/// Validates a `Host` header (or HTTP/2 `:authority`) for Desktop Mode to block DNS rebinding.
///
/// Only `127.0.0.1`, `localhost`, or `[::1]` are accepted; when `expected_port != 0`, the effective
/// port (explicit `:port`, or 80 when omitted) must match `expected_port`.
pub fn is_allowed_desktop_host(raw: &str, expected_port: u16) -> bool {
    if raw.is_empty()
        || raw.bytes().any(|b| {
            b.is_ascii_control()
                || b.is_ascii_whitespace()
                || matches!(b, b'@' | b'/' | b'?' | b'#')
        })
    {
        return false;
    }
    let (host, port_str) = if let Some(rest) = raw.strip_prefix('[') {
        let Some((inner, after)) = rest.split_once(']') else {
            return false;
        };
        if inner != "::1" {
            return false;
        }
        if after.is_empty() {
            ("[::1]", None)
        } else if let Some(port) = after.strip_prefix(':') {
            ("[::1]", Some(port))
        } else {
            return false;
        }
    } else if let Some((h, p)) = raw.split_once(':') {
        // Reject unbracketed IPv6 or extra colons.
        if p.contains(':') {
            return false;
        }
        (h, Some(p))
    } else {
        (raw, None)
    };

    let host_ok = host == "127.0.0.1" || host == "[::1]" || host.eq_ignore_ascii_case("localhost");
    if !host_ok {
        return false;
    }

    let parsed_port = match port_str {
        None => None,
        Some(p) => {
            if p.is_empty()
                || p.len() > 5
                || p.starts_with('0')
                || !p.bytes().all(|b| b.is_ascii_digit())
            {
                return false;
            }
            let Ok(num) = p.parse::<u16>() else {
                return false;
            };
            if num == 0 {
                return false;
            }
            Some(num)
        }
    };

    if expected_port != 0 {
        parsed_port.unwrap_or(80) == expected_port
    } else {
        true
    }
}

/// Global Desktop Mode Host allowlist middleware (runs on every request before routing).
async fn desktop_host_guard(
    State(state): State<AppState>,
    request: Request,
    next: Next,
) -> Response {
    if !state.config.desktop_mode {
        return next.run(request).await;
    }
    let mut host_headers = request.headers().get_all(header::HOST).iter();
    let candidate = match (host_headers.next(), host_headers.next()) {
        (Some(val), None) => val.to_str().ok(),
        (Some(_), Some(_)) => None,
        (None, _) => request.uri().authority().map(http::uri::Authority::as_str),
    };
    if !candidate.is_some_and(|h| is_allowed_desktop_host(h, state.config.server_port)) {
        return Problem::with_detail(
            StatusCode::FORBIDDEN,
            "Host header is not allowed in Desktop Mode.",
        )
        .into_response();
    }
    next.run(request).await
}

fn is_setup_path(path: &str) -> bool {
    path.starts_with("/api/setup/") || path == "/api/setup"
}

/// Desktop Mode guard for setup mutations: every state-changing request under `/api/setup/`
/// requires a loopback peer and client IP plus a valid Desktop session gate. `GET /api/setup/status`
/// stays open.
async fn desktop_setup_guard(
    State(state): State<AppState>,
    request: Request,
    next: Next,
) -> Response {
    if !state.config.desktop_mode {
        return next.run(request).await;
    }
    let state_changing = matches!(
        *request.method(),
        Method::POST | Method::PUT | Method::PATCH | Method::DELETE
    );
    if !state_changing {
        return next.run(request).await;
    }
    let path = decoded_path(request.uri().path());
    if !is_setup_path(&path) {
        return next.run(request).await;
    }
    let peer_loopback = request
        .extensions()
        .get::<client_ip::PeerIp>()
        .is_some_and(client_ip::PeerIp::is_loopback);
    let client_loopback = request
        .extensions()
        .get::<client_ip::ClientIp>()
        .is_some_and(client_ip::ClientIp::is_loopback);
    if !peer_loopback || !client_loopback {
        return Problem::with_detail(
            StatusCode::FORBIDDEN,
            "Desktop endpoints are restricted to local loopback connections.",
        )
        .into_response();
    }
    if !crate::setup_auth::desktop::has_valid_desktop_gate(request.headers(), &state) {
        return Problem::with_detail(
            StatusCode::FORBIDDEN,
            "Desktop session authorization required.",
        )
        .into_response();
    }
    next.run(request).await
}

/// Wraps `Limited` so `anonymous_body_limit` can tell when a streaming body tripped the 1 MiB
/// cap even if the inner extractor turned the body error into a 400.
struct TrackedLimited {
    inner: http_body_util::Limited<axum::body::Body>,
    exceeded: Arc<AtomicBool>,
}

impl http_body::Body for TrackedLimited {
    type Data = bytes::Bytes;
    type Error = axum::BoxError;

    fn poll_frame(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<Result<http_body::Frame<Self::Data>, Self::Error>>> {
        let poll = Pin::new(&mut self.inner).poll_frame(cx);
        if let Poll::Ready(Some(Err(ref err))) = poll
            && err.is::<http_body_util::LengthLimitError>()
        {
            self.exceeded.store(true, Ordering::Release);
        }
        poll
    }

    fn is_end_stream(&self) -> bool {
        self.inner.is_end_stream()
    }

    fn size_hint(&self) -> http_body::SizeHint {
        self.inner.size_hint()
    }
}

fn payload_too_large(version: http::Version) -> Response {
    let mut response = raw::response(StatusCode::PAYLOAD_TOO_LARGE, raw::PAYLOAD_TOO_LARGE);
    if version < http::Version::HTTP_2 {
        response
            .headers_mut()
            .insert(header::CONNECTION, HeaderValue::from_static("close"));
    }
    response
}

/// AnonymousBodyLimitFilter: refuses large or chunked bodies on the endpoints anyone can reach.
async fn anonymous_body_limit(request: Request, next: Next) -> Response {
    let path = decoded_path(request.uri().path());
    if ANONYMOUS_BODY_PREFIXES.iter().any(|p| path.starts_with(p)) {
        let version = request.version();
        let length = header_str(request.headers(), header::CONTENT_LENGTH)
            .and_then(|v| v.parse::<u64>().ok());
        if length.is_some_and(|l| l > MAX_ANONYMOUS_BODY)
            || request.headers().contains_key(header::TRANSFER_ENCODING)
        {
            return payload_too_large(version);
        }
        let exceeded = Arc::new(AtomicBool::new(false));
        let (parts, body) = request.into_parts();
        let limited = axum::body::Body::new(TrackedLimited {
            inner: http_body_util::Limited::new(body, MAX_ANONYMOUS_BODY as usize),
            exceeded: Arc::clone(&exceeded),
        });
        let response = next.run(Request::from_parts(parts, limited)).await;
        if exceeded.load(Ordering::Acquire) {
            return payload_too_large(version);
        }
        return response;
    }
    next.run(request).await
}

/// The API origin policy (DynamicCorsConfigurationSource): the setup wizard's origins, else
/// ALLOWED_ORIGINS; wildcard entries are dropped. None means no cross-origin access.
pub async fn cors_origins(state: &AppState) -> Option<Vec<String>> {
    let parse = |csv: &str| -> Vec<String> {
        csv.split(',')
            .map(str::trim)
            .filter(|o| !o.is_empty() && !o.contains('*'))
            .map(str::to_owned)
            .collect()
    };
    let stored =
        crate::setup_auth::settings::read(state, crate::setup_auth::settings::CORS_ALLOWED_ORIGINS)
            .await
            .ok()
            .flatten()
            .map(|v| parse(&v))
            .filter(|l| !l.is_empty());
    let origins = stored.unwrap_or_else(|| parse(&state.config.cors_allowed_origins));
    (!origins.is_empty()).then_some(origins)
}

fn origin_allowed(allowed: &[String], origin: &str) -> bool {
    let origin = origin.trim_end_matches('/');
    allowed
        .iter()
        .any(|a| a.trim_end_matches('/').eq_ignore_ascii_case(origin))
}

/// Same scheme, host and port as the request (CorsUtils / CrossSiteCookieRequestMatcher).
fn is_same_origin(origin: &RequestOrigin, url: &str) -> bool {
    let Ok(parsed) = url::Url::parse(url) else {
        return false;
    };
    let Some(host) = parsed.host_str() else {
        return false;
    };
    let default = |scheme: &str| {
        if scheme.eq_ignore_ascii_case("https") {
            443
        } else {
            80
        }
    };
    parsed.scheme().eq_ignore_ascii_case(origin.scheme)
        && host.eq_ignore_ascii_case(origin.host.trim_start_matches('[').trim_end_matches(']'))
        && parsed.port().unwrap_or_else(|| default(parsed.scheme())) == origin.port
}

const CORS_METHODS: &[&str] = &["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"];
const CORS_HEADERS: &[&str] = &["Content-Type", "Accept"];

fn invalid_cors() -> Response {
    (StatusCode::FORBIDDEN, "Invalid CORS request").into_response()
}

/// Spring's CorsFilter with the dynamic source: only /api/ routes have a policy.
async fn cors(State(state): State<AppState>, request: Request, next: Next) -> Response {
    if !request.uri().path().starts_with("/api/") {
        return next.run(request).await;
    }
    let Some(allowed) = cors_origins(&state).await else {
        return next.run(request).await;
    };
    let vary = |response: &mut Response| {
        for v in [
            "Origin",
            "Access-Control-Request-Method",
            "Access-Control-Request-Headers",
        ] {
            response
                .headers_mut()
                .append(header::VARY, HeaderValue::from_static(v));
        }
    };
    let origin_header = header_str(request.headers(), header::ORIGIN).map(str::to_owned);
    let request_origin = request.extensions().get::<RequestOrigin>().cloned();
    let is_cors = match (&origin_header, &request_origin) {
        (Some(o), Some(r)) => !is_same_origin(r, o),
        (Some(_), None) => true,
        _ => false,
    };
    if !is_cors {
        let mut response = next.run(request).await;
        vary(&mut response);
        return response;
    }
    let origin = origin_header.unwrap_or_default();
    let preflight = request.method() == Method::OPTIONS
        && request
            .headers()
            .contains_key(header::ACCESS_CONTROL_REQUEST_METHOD);
    if !origin_allowed(&allowed, &origin) {
        let mut response = invalid_cors();
        vary(&mut response);
        return response;
    }
    let origin_value = HeaderValue::from_str(&origin).unwrap_or(HeaderValue::from_static("null"));
    if preflight {
        let requested_method = header_str(request.headers(), header::ACCESS_CONTROL_REQUEST_METHOD)
            .unwrap_or_default();
        if !CORS_METHODS.contains(&requested_method) {
            return invalid_cors();
        }
        let requested_headers: Vec<String> =
            header_str(request.headers(), header::ACCESS_CONTROL_REQUEST_HEADERS)
                .unwrap_or_default()
                .split(',')
                .map(str::trim)
                .filter(|h| !h.is_empty())
                .map(str::to_owned)
                .collect();
        if requested_headers
            .iter()
            .any(|h| !CORS_HEADERS.iter().any(|a| a.eq_ignore_ascii_case(h)))
        {
            return invalid_cors();
        }
        let mut response = StatusCode::OK.into_response();
        vary(&mut response);
        let headers = response.headers_mut();
        headers.insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, origin_value);
        headers.insert(
            header::ACCESS_CONTROL_ALLOW_METHODS,
            HeaderValue::from_static("GET,POST,PUT,PATCH,DELETE,OPTIONS"),
        );
        if !requested_headers.is_empty()
            && let Ok(v) = HeaderValue::from_str(&requested_headers.join(", "))
        {
            headers.insert(header::ACCESS_CONTROL_ALLOW_HEADERS, v);
        }
        headers.insert(
            header::ACCESS_CONTROL_ALLOW_CREDENTIALS,
            HeaderValue::from_static("true"),
        );
        headers.insert(
            header::ACCESS_CONTROL_MAX_AGE,
            HeaderValue::from_static("3600"),
        );
        return response;
    }
    let mut response = next.run(request).await;
    vary(&mut response);
    response
        .headers_mut()
        .insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, origin_value);
    response.headers_mut().insert(
        header::ACCESS_CONTROL_ALLOW_CREDENTIALS,
        HeaderValue::from_static("true"),
    );
    response
}

/// The security headers Spring Security writes on every response of the chain.
async fn security_headers(State(state): State<AppState>, request: Request, next: Next) -> Response {
    let backend = is_backend_path(&decoded_path(request.uri().path()));
    let secure = request
        .extensions()
        .get::<RequestOrigin>()
        .is_some_and(RequestOrigin::is_secure);
    let mut response = next.run(request).await;
    let headers = response.headers_mut();
    if backend && !headers.contains_key(header::CACHE_CONTROL) {
        headers.insert(
            header::CACHE_CONTROL,
            HeaderValue::from_static("no-cache, no-store, max-age=0, must-revalidate"),
        );
        headers.insert(header::PRAGMA, HeaderValue::from_static("no-cache"));
        headers.insert(header::EXPIRES, HeaderValue::from_static("0"));
    }
    headers.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    headers.insert(header::X_XSS_PROTECTION, HeaderValue::from_static("0"));
    headers.insert(header::X_FRAME_OPTIONS, HeaderValue::from_static("DENY"));
    // ContentSecurityPolicyHeaderWriter leaves a policy the handler set (served logos).
    if !headers.contains_key(header::CONTENT_SECURITY_POLICY) {
        headers.insert(
            header::CONTENT_SECURITY_POLICY,
            HeaderValue::from_static(CSP),
        );
    }
    headers.insert(
        header::REFERRER_POLICY,
        HeaderValue::from_static("strict-origin-when-cross-origin"),
    );
    headers.insert(
        "permissions-policy",
        HeaderValue::from_static("camera=(), microphone=(), geolocation=()"),
    );
    if state.config.hsts_enabled && secure {
        headers.insert(
            header::STRICT_TRANSPORT_SECURITY,
            HeaderValue::from_static("max-age=31536000 ; includeSubDomains"),
        );
    }
    response
}

const CREDENTIAL_COOKIES: &[&str] = &[
    cookies::ACCESS,
    cookies::REFRESH,
    cookies::MFA_CHALLENGE,
    cookies::PERSISTENT,
    crate::setup_auth::desktop::DESKTOP_GATE_COOKIE,
];

fn is_desktop_ambient_endpoint(path: &str) -> bool {
    path == "/api/auth/desktop-autologin" || is_setup_path(path)
}

/// CrossSiteCookieRequestMatcher: a state-changing, cookie-authenticated request from another
/// origin is refused, unless that origin is on the CORS allow-list. In Desktop Mode, the
/// loopback auto-login and quick-start endpoints are also guarded even without credential cookies.
async fn cross_site_guard(State(state): State<AppState>, request: Request, next: Next) -> Response {
    let state_changing = matches!(
        *request.method(),
        Method::POST | Method::PUT | Method::PATCH | Method::DELETE
    );
    let path = decoded_path(request.uri().path());
    let desktop_ambient = state.config.desktop_mode && is_desktop_ambient_endpoint(&path);
    let has_credential_cookie = CREDENTIAL_COOKIES
        .iter()
        .any(|c| cookies::read(request.headers(), c).is_some());
    if !state_changing || (!has_credential_cookie && !desktop_ambient) {
        return next.run(request).await;
    }
    let sec_fetch_site = header_str(
        request.headers(),
        header::HeaderName::from_static("sec-fetch-site"),
    );
    let sec_fetch_cross =
        sec_fetch_site.is_some_and(|site| site != "same-origin" && site != "none");
    let origin_cross = {
        let source = header_str(request.headers(), header::ORIGIN)
            .or_else(|| header_str(request.headers(), header::REFERER));
        let origin = request.extensions().get::<RequestOrigin>();
        match (source, origin) {
            (Some(s), Some(o)) => !is_same_origin(o, s),
            (Some(_), None) => true,
            (None, _) => false,
        }
    };
    let cross = if desktop_ambient {
        sec_fetch_cross || origin_cross
    } else if sec_fetch_site.is_some() {
        sec_fetch_cross
    } else {
        origin_cross
    };
    if cross {
        let allow_listed = if desktop_ambient {
            false
        } else {
            match header_str(request.headers(), header::ORIGIN) {
                // /oauth2 as well: a frontend served from an allow-listed origin posts its SSO
                // start, its SSO callback and the consent form there.
                Some(origin) if path.starts_with("/api/") || path.starts_with("/oauth2/") => {
                    cors_origins(&state)
                        .await
                        .is_some_and(|list| origin_allowed(&list, origin))
                }
                _ => false,
            }
        };
        if !allow_listed {
            return raw::response(StatusCode::FORBIDDEN, raw::CROSS_SITE);
        }
    }
    next.run(request).await
}

/// The authorization-server and SSO filter chains answer before the API chain's SetupFilter.
const SECURITY_CHAIN_ENDPOINTS: &[&str] = &[
    "/oauth2/authorize",
    "/oauth2/token",
    "/oauth2/revoke",
    "/oauth2/introspect",
    "/oauth2/device_authorization",
    "/oauth2/jwks",
    "/.well-known/oauth-authorization-server",
];

fn allowed_during_setup(path: &str) -> bool {
    path.starts_with("/api/setup/")
        || path == "/api/setup"
        || path == "/api/auth/desktop-bootstrap"
        || path.starts_with("/oauth2/sso/")
        || SECURITY_CHAIN_ENDPOINTS.contains(&path)
        || matches!(
            path,
            "/actuator/health"
                | "/actuator/health/liveness"
                | "/actuator/health/readiness"
                | "/actuator/info"
        )
}

/// SetupFilter: everything but the wizard, the probes and the SPA waits for setup.
async fn setup_gate(State(state): State<AppState>, request: Request, next: Next) -> Response {
    if crate::setup_auth::setup::is_complete_cached(&state).await {
        return next.run(request).await;
    }
    let path = decoded_path(request.uri().path());
    if allowed_during_setup(&path) || !is_backend_path(&path) {
        return next.run(request).await;
    }
    let mut response = raw::response(StatusCode::SERVICE_UNAVAILABLE, raw::SETUP_REQUIRED);
    response
        .headers_mut()
        .insert(header::CONNECTION, HeaderValue::from_static("close"));
    response
}

/// Writes the body of every pending ProblemDetail with the request path as instance.
async fn render_problems(request: Request, next: Next) -> Response {
    let instance = request.uri().path().to_owned();
    let mut response = next.run(request).await;
    if let Some(PendingProblem(problem)) = response.extensions_mut().remove::<PendingProblem>() {
        let (parts, _) = response.into_parts();
        return Response::from_parts(parts, axum::body::Body::from(problem.render(&instance)));
    }
    response
}

/// A handler that panics answers 500, as an uncaught exception did in the Java backend. Left to
/// unwind, it closes the connection without a response and the SPA reports the server as
/// unreachable.
async fn catch_panics(request: Request, next: Next) -> Response {
    let route = format!("{} {}", request.method(), request.uri().path());
    match AssertUnwindSafe(next.run(request)).catch_unwind().await {
        Ok(response) => response,
        Err(panic) => {
            let reason = panic
                .downcast_ref::<&str>()
                .map(|s| (*s).to_owned())
                .or_else(|| panic.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "no message".to_owned());
            crate::error::AppError::Internal(format!("panic in {route}: {reason}")).into_response()
        }
    }
}

/// A bank login's completion runs to its end even when the browser leaves (#350). Approving in
/// the bank's app sends a phone's browser to the background, and the OS drops the request still
/// waiting on `/auth/complete`; axum then drops the handler with it, and the session the bank just
/// granted was never stored, so the panel's status polling waited for nothing. The handler runs as
/// its own task, so only the answer is lost; each connector's own timeouts still bound it, and a
/// panic is handed back to `catch_panics`.
async fn finish_login_completions(request: Request, next: Next) -> Response {
    if request.method() != Method::POST || !request.uri().path().ends_with("/auth/complete") {
        return next.run(request).await;
    }
    tokio::spawn(next.run(request))
        .await
        .unwrap_or_else(|e| std::panic::resume_unwind(e.into_panic()))
}

/// No route: the SPA for a browser read outside the backend, else Spring's 404.
async fn fallback(State(state): State<AppState>, request: Request) -> Response {
    let path = decoded_path(request.uri().path());
    let read = request.method() == Method::GET || request.method() == Method::HEAD;
    if read
        && !is_backend_path(&path)
        && let Some(location) = &state.config.spa_location
        && let Some(response) = spa::serve(location, &path, request.headers()).await
    {
        return response;
    }
    not_found(&path)
}

pub fn not_found(path: &str) -> Response {
    Problem::with_detail(
        StatusCode::NOT_FOUND,
        format!("No static resource {}.", path.trim_start_matches('/')),
    )
    .into_response()
}

async fn method_not_allowed(request: Request) -> Response {
    Problem::with_detail(
        StatusCode::METHOD_NOT_ALLOWED,
        format!("Method '{}' is not supported.", request.method()),
    )
    .into_response()
}

#[derive(Clone, Copy)]
struct CompressibleType;

impl Predicate for CompressibleType {
    fn should_compress<B: http_body::Body>(&self, response: &http::Response<B>) -> bool {
        let content_type = header_str(response.headers(), header::CONTENT_TYPE).unwrap_or_default();
        let essence = content_type.split(';').next().unwrap_or_default().trim();
        COMPRESSIBLE.contains(&essence)
    }
}

/// The full application router with every layer, in Java filter order (outermost first).
pub fn build(routes: crate::routes::Routes, state: AppState) -> Router {
    let (router, permits) = routes.into_parts();
    let security = SecurityState {
        app: state.clone(),
        permits: Arc::new(permits),
    };
    router
        .method_not_allowed_fallback(method_not_allowed)
        .fallback(fallback)
        .layer(from_fn(finish_login_completions))
        .layer(from_fn_with_state(security, crate::auth::layer::secure))
        .layer(from_fn_with_state(state.clone(), setup_gate))
        .layer(from_fn_with_state(state.clone(), cross_site_guard))
        .layer(from_fn(catch_panics))
        .layer(from_fn_with_state(state.clone(), security_headers))
        .layer(from_fn_with_state(state.clone(), cors))
        .layer(from_fn(anonymous_body_limit))
        .layer(from_fn_with_state(state.clone(), desktop_setup_guard))
        .layer(from_fn_with_state(state.clone(), desktop_host_guard))
        .layer(from_fn_with_state(state.clone(), client_ip::resolve))
        .layer(from_fn(render_problems))
        .layer(CompressionLayer::new().compress_when(SizeAbove::new(20).and(CompressibleType)))
        .with_state(state)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn a_login_completion_runs_to_its_end_after_the_browser_left() {
        use std::time::Duration;
        use tower::Service;

        let release = Arc::new(tokio::sync::Notify::new());
        let (done_tx, done_rx) = tokio::sync::oneshot::channel::<()>();
        let done_tx = Arc::new(std::sync::Mutex::new(Some(done_tx)));
        let handler = {
            let release = release.clone();
            move || async move {
                // The bank is still answering when the browser leaves.
                release.notified().await;
                if let Some(tx) = done_tx.lock().expect("lock").take() {
                    let _ = tx.send(());
                }
            }
        };
        let mut app: Router = Router::new()
            .route(
                "/api/caisse-epargne/auth/complete",
                axum::routing::post(handler),
            )
            .layer(from_fn(finish_login_completions));
        let request = Request::builder()
            .method(Method::POST)
            .uri("/api/caisse-epargne/auth/complete")
            .body(axum::body::Body::empty())
            .expect("build the request");

        // The OS dropped the request: its future goes away unanswered.
        let answered = tokio::time::timeout(Duration::from_millis(50), app.call(request)).await;
        assert!(answered.is_err());
        release.notify_one();

        tokio::time::timeout(Duration::from_secs(5), done_rx)
            .await
            .expect("the completion ran to its end")
            .expect("the handler finished");
    }

    #[tokio::test]
    async fn a_handler_that_panics_answers_500_with_a_problem_body() {
        use tower::Service;

        let mut app: Router = Router::new()
            .route(
                "/api/boom",
                axum::routing::get(|| async {
                    if true {
                        panic!("the secret reason");
                    }
                }),
            )
            .layer(from_fn(catch_panics))
            .layer(from_fn(render_problems));
        let request = Request::builder()
            .uri("/api/boom")
            .body(axum::body::Body::empty())
            .expect("build the request");

        let response = app.call(request).await.expect("the router answers");

        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        let body = axum::body::to_bytes(response.into_body(), usize::MAX)
            .await
            .expect("read the body");
        let problem: serde_json::Value = serde_json::from_slice(&body).expect("a JSON body");
        assert_eq!(problem["status"], 500);
        assert_eq!(problem["instance"], "/api/boom");
        assert!(
            !String::from_utf8_lossy(&body).contains("the secret reason"),
            "the panic message must stay in the log"
        );
    }

    #[tokio::test]
    async fn a_login_completion_that_panics_still_answers_500() {
        use tower::Service;

        let mut app: Router = Router::new()
            .route(
                "/api/caisse-epargne/auth/complete",
                axum::routing::post(|| async {
                    if true {
                        panic!("the secret reason");
                    }
                }),
            )
            .layer(from_fn(finish_login_completions))
            .layer(from_fn(catch_panics))
            .layer(from_fn(render_problems));
        let request = Request::builder()
            .method(Method::POST)
            .uri("/api/caisse-epargne/auth/complete")
            .body(axum::body::Body::empty())
            .expect("build the request");

        let response = app.call(request).await.expect("the router answers");

        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
    }

    #[test]
    fn desktop_host_allowlist_accepts_loopback_and_rejects_rebinding() {
        // Exact port match.
        assert!(is_allowed_desktop_host("127.0.0.1:8080", 8080));
        assert!(is_allowed_desktop_host("localhost:8080", 8080));
        assert!(is_allowed_desktop_host("LOCALHOST:8080", 8080));
        assert!(is_allowed_desktop_host("[::1]:8080", 8080));

        // Port 80 omitted.
        assert!(is_allowed_desktop_host("127.0.0.1", 80));
        assert!(is_allowed_desktop_host("localhost", 80));
        assert!(is_allowed_desktop_host("[::1]", 80));

        // Wildcard expected_port=0 accepts any valid loopback port.
        assert!(is_allowed_desktop_host("127.0.0.1:54321", 0));
        assert!(is_allowed_desktop_host("localhost", 0));
        assert!(is_allowed_desktop_host("[::1]:1234", 0));

        // Port mismatch rejected when expected_port != 0.
        assert!(!is_allowed_desktop_host("127.0.0.1:9999", 8080));
        assert!(!is_allowed_desktop_host("localhost", 8080));
        assert!(!is_allowed_desktop_host("[::1]", 8080));
        assert!(!is_allowed_desktop_host("127.0.0.1:08080", 8080));
        assert!(!is_allowed_desktop_host("127.0.0.1:0", 0));
        assert!(!is_allowed_desktop_host("127.0.0.1:", 8080));

        // DNS rebinding / spoofing / non-loopback hosts rejected.
        for bad in [
            "",
            "evil.example:8080",
            "127.0.0.1.evil.example:8080",
            "localhost.evil.example:8080",
            "evil.localhost:8080",
            "127.0.0.2:8080",
            "0.0.0.0:8080",
            "[::]:8080",
            "[::ffff:127.0.0.1]:8080",
            "::1:8080",
            "127.0.0.1@evil.example:8080",
            "localhost:8080/api",
            " 127.0.0.1:8080",
            "127.0.0.1:8080 ",
        ] {
            assert!(
                !is_allowed_desktop_host(bad, 8080),
                "expected {bad:?} to be rejected"
            );
        }
    }
}
