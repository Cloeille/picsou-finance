//! Sign-in methods, access keys, connected apps, OAuth2 authorization server, SSO (track T1).
use crate::routes::Routes;

use std::sync::Arc;

use crate::AppState;
use crate::auth::BearerAuthenticator;

/// Bearer authenticators tried, in order, for an Authorization: Bearer token the built-in access
/// JWT and psk_ key checks do not accept (the OAuth2 MCP tokens, for instance).
pub fn bearer_authenticators() -> Vec<Arc<dyn BearerAuthenticator>> {
    Vec::new()
}

pub fn register(_routes: &mut Routes) {}

/// The IdP's end_session_endpoint for RP-initiated logout (SSO_IDP_LOGOUT), from its discovery
/// document. None while SSO is not configured.
pub async fn sso_end_session_endpoint(_state: &AppState) -> Option<String> {
    None
}
