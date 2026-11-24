//! The security chain after CSRF and the setup gate: authenticate the request (access JWT,
//! remember-me token, access key or MCP token, plug-in bearer checks), then authorize it against
//! the anonymous routes modules declare, the admin role and the authenticated default.

use std::sync::Arc;

use axum::extract::{Request, State};
use axum::http::{HeaderValue, Method, StatusCode, header};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};

use crate::AppState;
use crate::auth::cookies::{self, Cookies};
use crate::auth::jwt::TokenType;
use crate::auth::{
    AuthMethod, MCP_AUDIENCE, Principal, Role, SsoRevalidate, access_key, persistent, users,
};
use crate::error::{Problem, raw};
use crate::http::client_ip::RequestOrigin;
use crate::http::rate_limit::policies;
use crate::http::{decoded_path, is_backend_path};

/// A route anyone may call, in Spring's pattern syntax: * is one path segment, ** any rest.
#[derive(Debug, Clone)]
pub struct PermitRule {
    pub method: Option<Method>,
    pub pattern: String,
}

impl PermitRule {
    pub fn matches(&self, method: &Method, path: &str) -> bool {
        self.method.as_ref().is_none_or(|m| m == method) && pattern_matches(&self.pattern, path)
    }
}

pub fn pattern_matches(pattern: &str, path: &str) -> bool {
    let pattern: Vec<&str> = pattern.trim_start_matches('/').split('/').collect();
    let path: Vec<&str> = path.trim_start_matches('/').split('/').collect();
    fn walk(p: &[&str], s: &[&str]) -> bool {
        match (p.first(), s.first()) {
            (None, None) => true,
            (Some(&"**"), _) => p.len() == 1 || (0..=s.len()).any(|i| walk(&p[1..], &s[i..])),
            (Some(&"*"), Some(seg)) => !seg.is_empty() && walk(&p[1..], &s[1..]),
            (Some(a), Some(b)) => a == b && walk(&p[1..], &s[1..]),
            _ => false,
        }
    }
    walk(&pattern, &path)
}

#[derive(Clone)]
pub struct SecurityState {
    pub app: AppState,
    pub permits: Arc<Vec<PermitRule>>,
}

fn bearer(request: &Request) -> Option<String> {
    let value = request
        .headers()
        .get(header::AUTHORIZATION)?
        .to_str()
        .ok()?;
    value.strip_prefix("Bearer ").map(|t| t.trim().to_owned())
}

/// JwtTokenAuthenticator.authenticate: an access JWT of an activated user whose token version
/// still matches.
pub async fn authenticate_access_token(state: &AppState, token: &str) -> Option<Principal> {
    if token.trim().is_empty() {
        return None;
    }
    let claims = state.jwt.parse(token).ok()?;
    if !claims.is(TokenType::Access) {
        return None;
    }
    let user = users::find_by_id(&state.db, claims.uid?).await.ok()??;
    (user.activated && claims.tv == Some(user.token_version)).then(|| Principal::session(user))
}

/// JwtTokenAuthenticator.authenticateMcpToken: a type=mcp token for the picsou-mcp audience.
async fn authenticate_mcp_token(state: &AppState, token: &str) -> Option<Principal> {
    let claims = state.jwt.parse(token).ok()?;
    if claims.kind.as_deref() != Some("mcp") || !claims.audience_contains(MCP_AUDIENCE) {
        return None;
    }
    let user = users::find_by_id(&state.db, claims.uid?).await.ok()??;
    if !user.activated || claims.tv != Some(user.token_version) {
        return None;
    }
    let scopes = claims
        .scope
        .as_deref()
        .unwrap_or_default()
        .split_whitespace()
        .map(str::to_owned)
        .collect();
    Some(Principal {
        user,
        method: AuthMethod::OAuthToken { scopes },
    })
}

/// PersistentTokenAuthFilter: restores a session from a remember-me cookie, rotating it.
async fn restore_from_persistent(
    state: &AppState,
    request: &mut Request,
    cookies: &mut Cookies,
) -> Option<Principal> {
    let cookie =
        cookies::read(request.headers(), cookies::PERSISTENT).filter(|c| !c.trim().is_empty())?;
    let issued = match persistent::validate_and_rotate(state, &cookie).await {
        Ok(Some(issued)) => issued,
        Ok(None) => {
            cookies.clear_persistent();
            return None;
        }
        Err(e) => {
            tracing::warn!("Persistent token validation failed: {e}");
            return None;
        }
    };
    let session = &issued.session;
    let user = match users::find_by_id(&state.db, session.user_id).await {
        Ok(Some(user)) if user.activated => user,
        _ => {
            cookies.clear_persistent();
            return None;
        }
    };
    let sso_at = session.sso_authenticated_at;
    if let Some(at) = sso_at {
        let revalidate = chrono::Duration::from_std(state.config.sso.session_revalidate_after)
            .unwrap_or_default();
        if !state.config.sso.enabled() || at + revalidate < state.clock.now() {
            let _ = persistent::revoke_by_series(state, session.series_id).await;
            cookies.clear_persistent();
            request.extensions_mut().insert(SsoRevalidate);
            return None;
        }
    }
    if sso_at.is_none()
        && !session.trusted_for_2fa
        && crate::setup_auth::mfa::is_enabled(&state.db, user.id)
            .await
            .unwrap_or(true)
    {
        cookies.clear_persistent();
        return None;
    }
    cookies.access_and_refresh(
        &state.jwt,
        &state.jwt.access_token(&user, sso_at),
        &state
            .jwt
            .refresh_token(&user, Some(session.series_id), sso_at),
        true,
    );
    let remaining = (session.expires_at - state.clock.now())
        .num_seconds()
        .max(0);
    cookies.persistent(&issued.cookie_value, u64::try_from(remaining).unwrap_or(0));
    Some(Principal::session(user))
}

enum Outcome {
    Continue(Option<Principal>),
    Respond(Response),
}

async fn authenticate(state: &AppState, request: &mut Request, cookies: &mut Cookies) -> Outcome {
    let path = decoded_path(request.uri().path());
    if pattern_matches("/mcp/**", &path) {
        let mut principal = None;
        if let Some(token) = bearer(request).filter(|t| !t.is_empty()) {
            if token.starts_with(access_key::PREFIX) {
                match access_key::validate(state, &token).await {
                    Ok(Some(key)) => {
                        if !state
                            .limits
                            .try_consume(&policies::MCP_KEY, &key.key_id.to_string())
                        {
                            return Outcome::Respond(raw::response(
                                StatusCode::TOO_MANY_REQUESTS,
                                raw::ACCESS_KEY_RATE_LIMITED,
                            ));
                        }
                        principal = Some(Principal {
                            user: key.owner,
                            method: AuthMethod::AccessKey {
                                key_id: key.key_id,
                                scopes: key.scopes,
                            },
                        });
                    }
                    Ok(None) => {}
                    Err(e) => tracing::warn!("Access key validation failed: {e}"),
                }
            } else if let Some(mcp) = authenticate_mcp_token(state, &token).await {
                principal = Some(mcp);
            }
        }
        return Outcome::Continue(principal);
    }
    let mut principal = None;
    if is_backend_path(&path) {
        let token = cookies::read(request.headers(), cookies::ACCESS).or_else(|| {
            bearer(request).filter(|t| !t.is_empty() && !t.starts_with(access_key::PREFIX))
        });
        if let Some(token) = token {
            principal = authenticate_access_token(state, &token).await;
        }
        if principal.is_none() {
            principal = restore_from_persistent(state, request, cookies).await;
        }
    }
    if principal.is_none()
        && let Some(token) = bearer(request).filter(|t| !t.is_empty())
    {
        for authenticator in &state.bearer_authenticators {
            if let Some(found) = authenticator.authenticate(state, &path, &token).await {
                principal = Some(found);
                break;
            }
        }
    }
    Outcome::Continue(principal)
}

fn mcp_challenge(origin: &RequestOrigin) -> Response {
    let mut response = StatusCode::UNAUTHORIZED.into_response();
    let value = format!(
        "Bearer resource_metadata=\"{}/.well-known/oauth-protected-resource\"",
        origin.base_url()
    );
    if let Ok(value) = HeaderValue::from_str(&value) {
        response
            .headers_mut()
            .insert(header::WWW_AUTHENTICATE, value);
    }
    response
}

/// Middleware: authentication then authorization.
pub async fn secure(
    State(security): State<SecurityState>,
    mut request: Request,
    next: Next,
) -> Response {
    let state = &security.app;
    let secure_cookies = crate::setup_auth::settings::secure_cookies(state).await;
    let mut cookies = Cookies::new(secure_cookies);
    let principal = match authenticate(state, &mut request, &mut cookies).await {
        Outcome::Continue(p) => p,
        Outcome::Respond(response) => return with_cookies(response, &cookies),
    };
    let path = decoded_path(request.uri().path());
    let method = request.method().clone();
    let spa_read = (method == Method::GET || method == Method::HEAD) && !is_backend_path(&path);
    let permitted = spa_read
        || security
            .permits
            .iter()
            .any(|rule| rule.matches(&method, &path));
    let response = if permitted {
        if let Some(p) = principal {
            request.extensions_mut().insert(p);
        }
        next.run(request).await
    } else if pattern_matches("/api/admin/**", &path) {
        match principal {
            Some(p) if p.role() == Some(Role::Admin) => {
                request.extensions_mut().insert(p);
                next.run(request).await
            }
            Some(_) => Problem::with_detail(StatusCode::FORBIDDEN, "Access Denied").into_response(),
            None => raw::response(StatusCode::UNAUTHORIZED, raw::UNAUTHORIZED),
        }
    } else {
        match principal {
            Some(p) => {
                request.extensions_mut().insert(p);
                next.run(request).await
            }
            None if pattern_matches("/mcp/**", &path) => {
                let origin = request.extensions().get::<RequestOrigin>().cloned();
                mcp_challenge(&origin.unwrap_or(RequestOrigin {
                    scheme: "http",
                    host: "localhost".into(),
                    port: 80,
                }))
            }
            None => raw::response(StatusCode::UNAUTHORIZED, raw::UNAUTHORIZED),
        }
    };
    with_cookies(response, &cookies)
}

/// The filter's Set-Cookie headers go before the handler's, as the Java filter writes first.
fn with_cookies(mut response: Response, cookies: &Cookies) -> Response {
    if cookies.is_empty() {
        return response;
    }
    let handler_cookies: Vec<HeaderValue> = response
        .headers()
        .get_all(header::SET_COOKIE)
        .iter()
        .cloned()
        .collect();
    response.headers_mut().remove(header::SET_COOKIE);
    let filter_cookies = cookies
        .headers()
        .iter()
        .filter_map(|h| HeaderValue::from_str(h).ok());
    for value in filter_cookies.chain(handler_cookies) {
        response.headers_mut().append(header::SET_COOKIE, value);
    }
    response
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spring_patterns() {
        assert!(pattern_matches("/api/setup/**", "/api/setup"));
        assert!(pattern_matches(
            "/api/setup/**",
            "/api/setup/integrations/x/acknowledge"
        ));
        assert!(pattern_matches(
            "/api/auth/activate/*",
            "/api/auth/activate/abc"
        ));
        assert!(!pattern_matches(
            "/api/auth/activate/*",
            "/api/auth/activate/abc/def"
        ));
        assert!(!pattern_matches("/api/setup/**", "/api/setupx"));
    }
}
