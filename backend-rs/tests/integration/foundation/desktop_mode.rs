use picsou::testing::{ADMIN_PASSWORD, ADMIN_USERNAME, TestApp};
use reqwest::StatusCode;
use serde_json::json;

#[tokio::test]
async fn disabled_by_default_returns_404_and_keeps_setup_status_unchanged() {
    let app = TestApp::spawn().await;
    let client = app.client();

    // GET /api/setup/status omits desktopMode and desktopLockEnabled when APP_DESKTOP_MODE=false.
    let status = client.get("/api/setup/status").send().await;
    assert_eq!(status.status, StatusCode::OK);
    assert_eq!(
        status.text(),
        r#"{"state":"PENDING_ADMIN","needsSetup":true,"integrations":{"enablebanking":false,"boursobank":false,"boursedirect":false,"fortuneo":false,"traderepublic":false,"lbp":false,"finary":false,"crypto":false},"sso":{"enabled":false,"providerName":"SSO","adminBootstrap":false}}"#
    );

    // Quick-start returns 404 when desktop mode is disabled (even without a JSON body).
    let qs = client
        .post("/api/setup/desktop-quick-start")
        .json(&json!({"displayName": "Louis"}))
        .send()
        .await;
    assert_eq!(qs.status, StatusCode::NOT_FOUND);

    // Complete normal setup and verify autologin + lock endpoints return 404 when disabled.
    app.complete_setup(ADMIN_USERNAME, ADMIN_PASSWORD).await;
    assert_eq!(
        client
            .post("/api/auth/desktop-autologin")
            .send()
            .await
            .status,
        StatusCode::NOT_FOUND
    );

    let authed = app.login(ADMIN_USERNAME, ADMIN_PASSWORD).await;
    assert_eq!(
        authed.get("/api/desktop/lock").send().await.status,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        authed
            .put("/api/desktop/lock")
            .json(&json!({"enabled": true, "newPassword": "strong-password-1"}))
            .send()
            .await
            .status,
        StatusCode::NOT_FOUND
    );
}

#[tokio::test]
async fn non_loopback_requests_are_rejected_even_when_desktop_mode_is_enabled() {
    // Trust 127.0.0.1 as a proxy so X-Forwarded-For simulates a remote client IP.
    let app = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .env("PICSOU_PROXY_IP", "127.0.0.1")
        .spawn()
        .await;

    let remote_qs = app
        .client()
        .post("/api/setup/desktop-quick-start")
        .header("x-forwarded-for", "203.0.113.42")
        .json(&json!({"displayName": "Remote"}))
        .send()
        .await;
    assert_eq!(remote_qs.status, StatusCode::FORBIDDEN);

    // Complete quick-start from loopback so the user exists.
    let local_qs = app
        .client()
        .post("/api/setup/desktop-quick-start")
        .json(&json!({"displayName": "Louis"}))
        .send()
        .await;
    assert_eq!(local_qs.status, StatusCode::OK);

    // Autologin from non-loopback is rejected with 403.
    let remote_auto = app
        .client()
        .post("/api/auth/desktop-autologin")
        .header("x-forwarded-for", "203.0.113.42")
        .send()
        .await;
    assert_eq!(remote_auto.status, StatusCode::FORBIDDEN);

    // Authenticated desktop lock endpoint from non-loopback is also rejected with 403.
    let authed = app.client();
    let auto_res = authed.post("/api/auth/desktop-autologin").send().await;
    assert_eq!(auto_res.status, StatusCode::OK);

    let remote_lock_get = authed
        .get("/api/desktop/lock")
        .header("x-forwarded-for", "203.0.113.42")
        .send()
        .await;
    assert_eq!(remote_lock_get.status, StatusCode::FORBIDDEN);

    let remote_lock_put = authed
        .put("/api/desktop/lock")
        .header("x-forwarded-for", "203.0.113.42")
        .json(&json!({"enabled": true, "newPassword": "strong-password-1"}))
        .send()
        .await;
    assert_eq!(remote_lock_put.status, StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn quick_start_seeds_user_categories_and_issues_working_session() {
    let app = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .spawn()
        .await;
    let client = app.client();

    let status_before = client.get("/api/setup/status").send().await.json();
    assert_eq!(status_before["needsSetup"], true);
    assert_eq!(status_before["desktopMode"], true);
    assert_eq!(status_before["desktopLockEnabled"], false);

    let quick = client
        .post("/api/setup/desktop-quick-start")
        .json(&json!({"displayName": "Éléonore"}))
        .send()
        .await;
    assert_eq!(quick.status, StatusCode::OK);
    let user = quick.json();
    assert_eq!(user["displayName"], "Éléonore");
    assert_eq!(user["username"], "eleonore");
    assert_eq!(user["role"], "ADMIN");
    assert!(user["memberId"].as_i64().is_some());

    // Session cookies were issued immediately: authenticated routes work without login.
    let accounts = client.get("/api/accounts").send().await;
    assert_eq!(accounts.status, StatusCode::OK);

    // Default categories were seeded for the member.
    let member_id = user["memberId"].as_i64().unwrap();
    let category_count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM category WHERE member_id = $1")
            .bind(member_id)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert!(category_count >= 10, "expected seeded default categories");

    // Setup is now complete; calling quick-start again returns 410 Gone.
    let status_after = client.get("/api/setup/status").send().await.json();
    assert_eq!(status_after["needsSetup"], false);
    assert_eq!(status_after["desktopMode"], true);
    assert_eq!(status_after["desktopLockEnabled"], false);

    let repeat = client
        .post("/api/setup/desktop-quick-start")
        .json(&json!({"displayName": "Second"}))
        .send()
        .await;
    assert_eq!(repeat.status, StatusCode::GONE);
}

#[tokio::test]
async fn quick_start_defaults_blank_display_name_by_accept_language() {
    let app = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .spawn()
        .await;
    let quick = app
        .client()
        .post("/api/setup/desktop-quick-start")
        .header("accept-language", "fr-FR,fr;q=0.9")
        .json(&json!({"displayName": "   "}))
        .send()
        .await;
    assert_eq!(quick.status, StatusCode::OK);
    let user = quick.json();
    assert_eq!(user["displayName"], "Mon espace");
    assert_eq!(user["username"], "mon-espace");
}

#[tokio::test]
async fn autologin_and_password_lock_lifecycle() {
    let app = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .spawn()
        .await;

    // 1. Quick-start creates the local admin unlocked.
    let qs = app
        .client()
        .post("/api/setup/desktop-quick-start")
        .json(&json!({"displayName": "Louis"}))
        .send()
        .await;
    assert_eq!(qs.status, StatusCode::OK);

    // 2. A fresh client (simulating app relaunch without cookies) auto-logs in seamlessly.
    let relaunched = app.client();
    let auto = relaunched.post("/api/auth/desktop-autologin").send().await;
    assert_eq!(auto.status, StatusCode::OK);
    assert_eq!(auto.json()["username"], "louis");
    assert_eq!(
        relaunched.get("/api/accounts").send().await.status,
        StatusCode::OK
    );

    // 3. GET /api/desktop/lock reports false initially.
    let lock_get = relaunched.get("/api/desktop/lock").send().await;
    assert_eq!(lock_get.status, StatusCode::OK);
    assert_eq!(lock_get.json()["enabled"], false);

    // 4. Enabling lock rejects weak passwords (< 8 chars) with 422.
    let weak = relaunched
        .put("/api/desktop/lock")
        .json(&json!({"enabled": true, "newPassword": "short"}))
        .send()
        .await;
    assert_eq!(weak.status, StatusCode::UNPROCESSABLE_ENTITY);

    // 5. Enabling lock with a strong password succeeds, rotates cookies, and updates status.
    let enable = relaunched
        .put("/api/desktop/lock")
        .json(&json!({"enabled": true, "newPassword": "my-strong-password"}))
        .send()
        .await;
    assert_eq!(enable.status, StatusCode::OK);
    assert_eq!(enable.json()["enabled"], true);

    // Existing client stays authenticated thanks to rotated cookies.
    assert_eq!(
        relaunched.get("/api/desktop/lock").send().await.json()["enabled"],
        true
    );
    assert_eq!(
        app.client().get("/api/setup/status").send().await.json()["desktopLockEnabled"],
        true
    );

    // 6. Auto-login is now refused with 403 DESKTOP_LOCK_ENABLED.
    let locked_auto = app
        .client()
        .post("/api/auth/desktop-autologin")
        .send()
        .await;
    assert_eq!(locked_auto.status, StatusCode::FORBIDDEN);
    assert_eq!(locked_auto.json()["code"], "DESKTOP_LOCK_ENABLED");

    // 7. Standard password login works with the new password.
    let logged_in = app.login("louis", "my-strong-password").await;
    assert_eq!(
        logged_in.get("/api/accounts").send().await.status,
        StatusCode::OK
    );

    // 8. Disabling lock with wrong current password is rejected with 401 REAUTH_FAILED.
    let wrong_pw = logged_in
        .put("/api/desktop/lock")
        .json(&json!({"enabled": false, "currentPassword": "wrong-password"}))
        .send()
        .await;
    assert_eq!(wrong_pw.status, StatusCode::UNAUTHORIZED);
    assert_eq!(wrong_pw.json()["code"], "REAUTH_FAILED");

    // 9. Disabling lock with the right current password succeeds and re-enables auto-login.
    let disable = logged_in
        .put("/api/desktop/lock")
        .json(&json!({"enabled": false, "currentPassword": "my-strong-password"}))
        .send()
        .await;
    assert_eq!(disable.status, StatusCode::OK);
    assert_eq!(disable.json()["enabled"], false);
    assert_eq!(
        app.client().get("/api/setup/status").send().await.json()["desktopLockEnabled"],
        false
    );

    let auto_again = app
        .client()
        .post("/api/auth/desktop-autologin")
        .send()
        .await;
    assert_eq!(auto_again.status, StatusCode::OK);
    assert_eq!(auto_again.json()["username"], "louis");
}

#[tokio::test]
async fn non_admin_member_is_forbidden_from_desktop_lock_and_lock_cannot_be_overwritten() {
    let app = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .spawn()
        .await;

    let qs = app
        .client()
        .post("/api/setup/desktop-quick-start")
        .json(&json!({"displayName": "Louis"}))
        .send()
        .await;
    assert_eq!(qs.status, StatusCode::OK);

    // Create an activated non-admin family member and sign in as them.
    app.create_member("alice", "alice-secret-password").await;
    let alice = app.login("alice", "alice-secret-password").await;

    // Non-admin receives 403 on GET and PUT /api/desktop/lock.
    assert_eq!(
        alice.get("/api/desktop/lock").send().await.status,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        alice
            .put("/api/desktop/lock")
            .json(&json!({"enabled": true, "newPassword": "hijack-password-1"}))
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );

    // Admin enables the lock with a known password.
    let admin = app.client();
    assert_eq!(
        admin
            .post("/api/auth/desktop-autologin")
            .send()
            .await
            .status,
        StatusCode::OK
    );
    let enable = admin
        .put("/api/desktop/lock")
        .json(&json!({"enabled": true, "newPassword": "original-admin-pw"}))
        .send()
        .await;
    assert_eq!(enable.status, StatusCode::OK);

    // Non-admin still cannot disable the lock using their own password to escalate via autologin.
    assert_eq!(
        alice
            .put("/api/desktop/lock")
            .json(&json!({"enabled": false, "currentPassword": "alice-secret-password"}))
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );

    // Calling PUT /api/desktop/lock with enabled: true while lock is already enabled is rejected
    // with 409 Conflict, and the admin's password remains unchanged.
    let overwrite = admin
        .put("/api/desktop/lock")
        .json(&json!({"enabled": true, "newPassword": "overwritten-password"}))
        .send()
        .await;
    assert_eq!(overwrite.status, StatusCode::CONFLICT);
    assert_eq!(overwrite.json()["code"], "DESKTOP_LOCK_ALREADY_ENABLED");

    // Original password still works; overwritten password is rejected.
    let bad_login = app
        .client()
        .post("/api/auth/login")
        .json(&json!({"username": "louis", "password": "overwritten-password"}))
        .send()
        .await;
    assert_eq!(bad_login.status, StatusCode::UNAUTHORIZED);

    let good_login = app.login("louis", "original-admin-pw").await;
    assert_eq!(
        good_login.get("/api/desktop/lock").send().await.json()["enabled"],
        true
    );
}

#[tokio::test]
async fn desktop_lock_and_autologin_enforce_rate_limits() {
    let app = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .spawn()
        .await;
    let qs = app
        .client()
        .post("/api/setup/desktop-quick-start")
        .json(&json!({"displayName": "Louis"}))
        .send()
        .await;
    assert_eq!(qs.status, StatusCode::OK);

    // Enable lock once, then burn remaining DESKTOP_LOCK tokens (5/15m) with bad password checks.
    let admin = app.client();
    assert_eq!(
        admin
            .post("/api/auth/desktop-autologin")
            .send()
            .await
            .status,
        StatusCode::OK
    );
    assert_eq!(
        admin
            .put("/api/desktop/lock")
            .json(&json!({"enabled": true, "newPassword": "rate-limit-pass-1"}))
            .send()
            .await
            .status,
        StatusCode::OK
    );
    for _ in 0..4 {
        let resp = admin
            .put("/api/desktop/lock")
            .json(&json!({"enabled": false, "currentPassword": "wrong-pw"}))
            .send()
            .await;
        assert_eq!(resp.status, StatusCode::UNAUTHORIZED);
    }
    let throttled_lock = admin
        .put("/api/desktop/lock")
        .json(&json!({"enabled": false, "currentPassword": "rate-limit-pass-1"}))
        .send()
        .await;
    assert_eq!(throttled_lock.status, StatusCode::TOO_MANY_REQUESTS);

    // Burn the remaining DESKTOP_AUTOLOGIN bucket (20/1m; 1 already consumed above).
    for _ in 0..19 {
        let resp = app
            .client()
            .post("/api/auth/desktop-autologin")
            .send()
            .await;
        assert_eq!(resp.status, StatusCode::FORBIDDEN);
    }
    let throttled_auto = app
        .client()
        .post("/api/auth/desktop-autologin")
        .send()
        .await;
    assert_eq!(throttled_auto.status, StatusCode::TOO_MANY_REQUESTS);
}
