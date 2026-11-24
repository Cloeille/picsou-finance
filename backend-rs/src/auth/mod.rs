//! Authentication: JWT session cookies, remember-me tokens, access keys, and the Auth extractor
//! handlers use to get the signed-in user, the effective member and how they authenticated.

pub mod access_key;
pub mod cookies;
pub mod jwt;
pub mod layer;
pub mod password;
pub mod persistent;
pub mod users;

use axum::extract::{FromRequestParts, OptionalFromRequestParts};
use axum::http::request::Parts;
use axum::response::{IntoResponse, Response};
use http::StatusCode;

use crate::AppState;
use crate::error::{AppError, raw};
pub use users::{AppUser, Role};

/// The audience of the MCP tokens minted by the OAuth2 authorization server.
pub const MCP_AUDIENCE: &str = "picsou-mcp";

/// How the request authenticated.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AuthMethod {
    /// A web or native-app session: access JWT (cookie or Bearer) or a remember-me token.
    Session,
    /// A personal access key (psk_), limited to its scopes, never impersonating.
    AccessKey { key_id: i64, scopes: Vec<String> },
    /// An OAuth2 token for the MCP surface, limited to its scopes.
    OAuthToken { scopes: Vec<String> },
}

/// The authenticated principal the authentication layer stores in the request extensions.
#[derive(Debug, Clone)]
pub struct Principal {
    pub user: AppUser,
    pub method: AuthMethod,
}

impl Principal {
    pub fn session(user: AppUser) -> Self {
        Self {
            user,
            method: AuthMethod::Session,
        }
    }

    /// The Spring role authority; scoped credentials carry scopes only, never a role.
    pub fn role(&self) -> Option<Role> {
        matches!(self.method, AuthMethod::Session).then_some(self.user.role)
    }

    pub fn scopes(&self) -> &[String] {
        match &self.method {
            AuthMethod::Session => &[],
            AuthMethod::AccessKey { scopes, .. } | AuthMethod::OAuthToken { scopes } => scopes,
        }
    }
}

/// A plug-in check for an Authorization: Bearer token the built-in checks did not accept
/// (OAuth2 tokens, SSO). Track T1 registers its own in identity::bearer_authenticators.
#[async_trait::async_trait]
pub trait BearerAuthenticator: Send + Sync {
    async fn authenticate(&self, state: &AppState, path: &str, token: &str) -> Option<Principal>;
}

/// Set by the remember-me check when an SSO session must re-validate at the IdP.
#[derive(Debug, Clone, Copy)]
pub struct SsoRevalidate;

/// The signed-in user for a handler. Rejects with the 401 problem when there is none.
#[derive(Debug, Clone)]
pub struct Auth {
    pub user: AppUser,
    pub method: AuthMethod,
    /// The member the request acts for: the user's own, or the managed profile an admin selected
    /// with ?memberId= (UserContext.currentMemberId).
    pub member_id: i64,
}

impl Auth {
    pub fn role(&self) -> Option<Role> {
        matches!(self.method, AuthMethod::Session).then_some(self.user.role)
    }

    pub fn is_admin(&self) -> bool {
        self.user.role == Role::Admin
    }

    pub fn has_scope(&self, scope: &str) -> bool {
        match &self.method {
            AuthMethod::Session => false,
            AuthMethod::AccessKey { scopes, .. } | AuthMethod::OAuthToken { scopes } => {
                scopes.iter().any(|s| s == scope)
            }
        }
    }

    /// The member override an admin asked for, applied only to managed (not yet activated)
    /// members; a scoped credential never impersonates.
    async fn effective_member(
        state: &AppState,
        principal: &Principal,
        query: Option<&str>,
    ) -> Result<i64, AppError> {
        let own = principal.user.member_id;
        if principal.method != AuthMethod::Session || principal.user.role != Role::Admin {
            return Ok(own);
        }
        let Some(param) = query.and_then(member_id_param) else {
            return Ok(own);
        };
        let Ok(requested) = param.parse::<i64>() else {
            return Ok(own);
        };
        if requested == own {
            return Ok(requested);
        }
        let independent = users::find_by_member_id(&state.db, requested)
            .await?
            .is_some_and(|u| u.activated);
        if independent {
            return Err(AppError::status(
                StatusCode::FORBIDDEN,
                "Cannot access an independent member's data",
            ));
        }
        Ok(requested)
    }
}

fn member_id_param(query: &str) -> Option<String> {
    url::form_urlencoded::parse(query.as_bytes())
        .find(|(k, _)| k == "memberId")
        .map(|(_, v)| v.into_owned())
        .filter(|v| !v.trim().is_empty())
}

pub struct Unauthenticated;

impl IntoResponse for Unauthenticated {
    fn into_response(self) -> Response {
        raw::response(StatusCode::UNAUTHORIZED, raw::UNAUTHORIZED)
    }
}

pub enum AuthRejection {
    Unauthenticated,
    Error(AppError),
}

impl IntoResponse for AuthRejection {
    fn into_response(self) -> Response {
        match self {
            Self::Unauthenticated => Unauthenticated.into_response(),
            Self::Error(e) => e.into_response(),
        }
    }
}

impl FromRequestParts<AppState> for Auth {
    type Rejection = AuthRejection;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let principal = parts
            .extensions
            .get::<Principal>()
            .cloned()
            .ok_or(AuthRejection::Unauthenticated)?;
        let member_id = Self::effective_member(state, &principal, parts.uri.query())
            .await
            .map_err(AuthRejection::Error)?;
        Ok(Self {
            user: principal.user,
            method: principal.method,
            member_id,
        })
    }
}

impl OptionalFromRequestParts<AppState> for Auth {
    type Rejection = AppError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Option<Self>, Self::Rejection> {
        let Some(principal) = parts.extensions.get::<Principal>().cloned() else {
            return Ok(None);
        };
        let member_id = Self::effective_member(state, &principal, parts.uri.query()).await?;
        Ok(Some(Self {
            user: principal.user,
            method: principal.method,
            member_id,
        }))
    }
}
