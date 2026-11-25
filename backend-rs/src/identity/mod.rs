//! Sign-in methods, access keys, the member profile, the OAuth2 authorization server and the SSO
//! sign-in chain (track T1).

pub mod access_keys;
pub mod common;
pub mod oauth;
pub mod profile;
pub mod scopes;
pub mod sign_in;
pub mod sso;

use std::sync::Arc;

use http::Method;

use crate::AppState;
use crate::auth::BearerAuthenticator;
use crate::routes::Routes;

/// In-memory identity state: the SSO discovery cache and the pending SSO handshakes.
#[derive(Default)]
pub struct Runtime {
    pub discovery: sso::Discovery,
    pub handshakes: sso::Handshakes,
}

/// Bearer authenticators tried after the built-in checks. The foundation already accepts the
/// authorization server's tokens (type=access on /api, type=mcp on /mcp), so none is needed.
pub fn bearer_authenticators() -> Vec<Arc<dyn BearerAuthenticator>> {
    Vec::new()
}

pub fn register(routes: &mut Routes) {
    routes
        .permit_all(Some(Method::GET), "/api/auth/sso")
        .permit_all(Some(Method::GET), "/.well-known/oauth-protected-resource")
        .permit_all(Some(Method::GET), "/.well-known/oauth-authorization-server")
        .permit_all(Some(Method::POST), "/oauth2/register")
        .permit_all(None, "/oauth2/authorize")
        .permit_all(Some(Method::POST), "/oauth2/token")
        .permit_all(Some(Method::POST), "/oauth2/revoke")
        .permit_all(Some(Method::POST), "/oauth2/introspect")
        .permit_all(Some(Method::POST), "/oauth2/device_authorization")
        .permit_all(Some(Method::GET), "/oauth2/device_verification")
        .permit_all(Some(Method::GET), "/oauth2/jwks")
        .permit_all(None, "/oauth2/sso/**");

    routes
        .get("/api/auth/sso", sign_in::sso_info)
        .get("/api/auth/sign-in-methods", sign_in::sign_in_methods)
        .post("/api/auth/reauth", sign_in::reauth)
        .delete("/api/auth/password", sign_in::remove_password)
        .put("/api/auth/password", sign_in::set_password)
        .delete("/api/auth/identities/{id}", sign_in::unlink_identity);

    routes
        .get("/api/access-keys", access_keys::list)
        .post("/api/access-keys", access_keys::create)
        .delete("/api/access-keys/{id}", access_keys::delete);

    routes
        .get("/api/me/profile", profile::get)
        .put("/api/me/profile", profile::replace);

    routes
        .get("/api/connected-apps", oauth::apps::list)
        .delete("/api/connected-apps/{id}", oauth::apps::revoke)
        .get("/api/oauth2/consent-info", oauth::clients::consent_info)
        .post("/oauth2/register", oauth::clients::register)
        .get(
            "/.well-known/oauth-protected-resource",
            oauth::server::protected_resource_metadata,
        );

    routes
        .get(
            "/.well-known/oauth-authorization-server",
            oauth::server::authorization_server_metadata,
        )
        .get("/oauth2/authorize", oauth::server::authorize)
        .post("/oauth2/authorize", oauth::server::authorize)
        .post("/oauth2/token", oauth::server::token)
        .post("/oauth2/revoke", oauth::server::client_authenticated)
        .post("/oauth2/introspect", oauth::server::client_authenticated)
        .post(
            "/oauth2/device_authorization",
            oauth::server::client_authenticated,
        )
        .get(
            "/oauth2/device_verification",
            oauth::server::device_verification,
        )
        .get("/oauth2/jwks", oauth::server::jwks)
        .get("/oauth2/sso/{*rest}", sso::chain)
        .post("/oauth2/sso/{*rest}", sso::chain);
}

/// Startup work of the identity module: the first-party OAuth client (seedIosClientRunner).
pub async fn on_startup(state: &AppState) -> anyhow::Result<()> {
    oauth::clients::seed_ios_client(state).await?;
    Ok(())
}

/// The IdP's end_session_endpoint for RP-initiated logout (SSO_IDP_LOGOUT), from its discovery
/// document. None while SSO is not configured.
pub async fn sso_end_session_endpoint(state: &AppState) -> Option<String> {
    sso::end_session_endpoint(state).await
}
