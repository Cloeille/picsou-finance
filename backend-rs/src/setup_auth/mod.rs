//! The foundation endpoints: SetupController, AuthController, MfaController, SessionController.

pub mod audit;
pub mod auth;
pub mod desktop;
pub mod enable_banking;
pub mod mfa;
pub mod sessions;
pub mod settings;
pub mod setup;

use http::Method;

use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .permit_all(None, "/api/setup/**")
        .permit_all(Some(Method::POST), "/api/auth/login")
        .permit_all(Some(Method::POST), "/api/auth/desktop-autologin")
        .permit_all(Some(Method::POST), "/api/auth/refresh")
        .permit_all(Some(Method::POST), "/api/auth/logout")
        .permit_all(Some(Method::POST), "/api/auth/mfa/verify")
        .permit_all(Some(Method::POST), "/api/auth/activate/*");

    routes
        .get("/api/setup/status", setup::status)
        .post("/api/setup/admin", setup::admin)
        .post("/api/setup/security", setup::security)
        .post(
            "/api/setup/integrations/enablebanking/config",
            setup::enable_banking_config,
        )
        .post(
            "/api/setup/integrations/enablebanking/keypair",
            setup::enable_banking_keypair,
        )
        .post(
            "/api/setup/integrations/enablebanking/keypair/import",
            setup::enable_banking_import,
        )
        .post(
            "/api/setup/integrations/enablebanking/test",
            setup::enable_banking_test,
        )
        .get(
            "/api/setup/integrations/boursobank/health",
            setup::bourso_health,
        )
        .post(
            "/api/setup/integrations/crypto/generate-key",
            setup::crypto_generate_key,
        )
        .post(
            "/api/setup/integrations/{key}/acknowledge",
            setup::acknowledge,
        )
        .post("/api/setup/complete", setup::complete)
        .post("/api/setup/desktop-quick-start", desktop::quick_start);

    routes
        .post("/api/auth/login", auth::login)
        .post("/api/auth/desktop-autologin", desktop::autologin)
        .post("/api/auth/mfa/verify", auth::mfa_verify)
        .post("/api/auth/refresh", auth::refresh)
        .post("/api/auth/logout", auth::logout)
        .patch("/api/auth/username", auth::change_username)
        .post("/api/auth/change-password", auth::change_password)
        .post("/api/auth/activate/{token}", auth::activate);

    routes
        .get("/api/auth/mfa/status", mfa::status)
        .post("/api/auth/mfa/enroll/init", mfa::enroll_init)
        .post("/api/auth/mfa/enroll/verify", mfa::enroll_verify)
        .post("/api/auth/mfa/disable", mfa::disable)
        .post("/api/auth/mfa/recovery-codes/regenerate", mfa::regenerate);

    routes
        .get("/api/auth/sessions", sessions::list)
        .delete("/api/auth/sessions", sessions::revoke_all_except_current)
        .delete("/api/auth/sessions/{id}", sessions::revoke);

    routes
        .get("/api/desktop/lock", desktop::lock_status)
        .put("/api/desktop/lock", desktop::update_lock);
}
