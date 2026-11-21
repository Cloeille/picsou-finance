//! The HTTP edge: forwarded headers, body limit, CORS, security headers, CSRF, setup gate,
//! problem bodies, compression and the SPA, in the order the Java filters run.

pub mod client_ip;
pub mod json;
pub mod rate_limit;
pub mod spa;

use std::sync::Arc;

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

/// AnonymousBodyLimitFilter: refuses large or chunked bodies on the endpoints anyone can reach.
async fn anonymous_body_limit(request: Request, next: Next) -> Response {
    let path = decoded_path(request.uri().path());
    if ANONYMOUS_BODY_PREFIXES.iter().any(|p| path.starts_with(p)) {
        let length = header_str(request.headers(), header::CONTENT_LENGTH)
            .and_then(|v| v.parse::<u64>().ok());
        if length.is_some_and(|l| l > MAX_ANONYMOUS_BODY)
            || request.headers().contains_key(header::TRANSFER_ENCODING)
        {
            let mut response = raw::response(StatusCode::PAYLOAD_TOO_LARGE, raw::PAYLOAD_TOO_LARGE);
            response
                .headers_mut()
                .insert(header::CONNECTION, HeaderValue::from_static("close"));
            return response;
        }
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
];

/// CrossSiteCookieRequestMatcher: a state-changing, cookie-authenticated request from another
/// origin is refused, unless that origin is on the CORS allow-list.
async fn cross_site_guard(State(state): State<AppState>, request: Request, next: Next) -> Response {
    let state_changing = matches!(
        *request.method(),
        Method::POST | Method::PUT | Method::PATCH | Method::DELETE
    );
    if !state_changing
        || !CREDENTIAL_COOKIES
            .iter()
            .any(|c| cookies::read(request.headers(), c).is_some())
    {
        return next.run(request).await;
    }
    let cross = match header_str(
        request.headers(),
        header::HeaderName::from_static("sec-fetch-site"),
    ) {
        Some(site) => site != "same-origin" && site != "none",
        None => {
            let source = header_str(request.headers(), header::ORIGIN)
                .or_else(|| header_str(request.headers(), header::REFERER));
            let origin = request.extensions().get::<RequestOrigin>();
            match (source, origin) {
                (Some(s), Some(o)) => !is_same_origin(o, s),
                (Some(_), None) => true,
                (None, _) => false,
            }
        }
    };
    if cross {
        let allow_listed = match header_str(request.headers(), header::ORIGIN) {
            Some(origin) if request.uri().path().starts_with("/api/") => cors_origins(&state)
                .await
                .is_some_and(|list| origin_allowed(&list, origin)),
            _ => false,
        };
        if !allow_listed {
            return raw::response(StatusCode::FORBIDDEN, raw::CROSS_SITE);
        }
    }
    next.run(request).await
}

fn allowed_during_setup(path: &str) -> bool {
    path.starts_with("/api/setup/")
        || path == "/api/setup"
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
        .layer(from_fn_with_state(security, crate::auth::layer::secure))
        .layer(from_fn_with_state(state.clone(), setup_gate))
        .layer(from_fn_with_state(state.clone(), cross_site_guard))
        .layer(from_fn_with_state(state.clone(), security_headers))
        .layer(from_fn_with_state(state.clone(), cors))
        .layer(from_fn(anonymous_body_limit))
        .layer(from_fn_with_state(state.clone(), client_ip::resolve))
        .layer(from_fn(render_problems))
        .layer(CompressionLayer::new().compress_when(SizeAbove::new(20).and(CompressibleType)))
        .with_state(state)
}
