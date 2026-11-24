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
        .json(&json!({"displayName": "Camille"}))
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
        .desktop_client()
        .post("/api/setup/desktop-quick-start")
        .json(&json!({"displayName": "Camille"}))
        .send()
        .await;
    assert_eq!(local_qs.status, StatusCode::OK);

    // Autologin from non-loopback is rejected with 403 even with a valid desktop token.
    let remote_auto = app
        .desktop_client()
        .post("/api/auth/desktop-autologin")
        .header("x-forwarded-for", "203.0.113.42")
        .send()
        .await;
    assert_eq!(remote_auto.status, StatusCode::FORBIDDEN);

    // Authenticated desktop lock endpoint from non-loopback is also rejected with 403.
    let authed = app.desktop_client();
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
    let client = app.desktop_client();

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
        picsou::db::query_scalar("SELECT count(*) FROM category WHERE member_id = $1")
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
        .desktop_client()
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

/// Without the password lock the member has no password to prove who they are with: the app's
/// window signed them in, and that same gate lets it export their data. With the lock on, the
/// export asks for the password like anywhere else.
#[tokio::test]
async fn the_unlocked_app_exports_without_a_password_and_the_locked_one_asks_for_it() {
    let app = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .spawn()
        .await;
    let window = app.desktop_client();
    let started = window
        .post("/api/setup/desktop-quick-start")
        .json(&json!({"displayName": "Camille"}))
        .send()
        .await;
    assert_eq!(started.status, StatusCode::OK);

    let unlocked = window.post("/api/me/export").json(&json!({})).send().await;
    assert_eq!(unlocked.status, StatusCode::OK);
    assert_eq!(unlocked.header("content-type"), Some("application/zip"));

    let locked = window
        .put("/api/desktop/lock")
        .json(&json!({"enabled": true, "newPassword": "my-strong-password"}))
        .send()
        .await;
    assert_eq!(locked.status, StatusCode::OK);
    let refused = window.post("/api/me/export").json(&json!({})).send().await;
    assert_eq!(refused.status, StatusCode::UNAUTHORIZED);
    assert_eq!(refused.json()["code"], "REAUTH_FAILED");
    let proven = window
        .post("/api/me/export")
        .json(&json!({"reAuth": {"password": "my-strong-password"}}))
        .send()
        .await;
    assert_eq!(proven.status, StatusCode::OK);
}

/// The window's word is good for the account it signs in itself and for no other. A member with
/// a password of their own proves it, in the app's window as in a browser, and a browser on the
/// same machine has no gate to show.
#[tokio::test]
async fn the_export_without_a_password_is_for_the_account_the_app_signs_in_and_no_other() {
    const ALEX_PASSWORD: &str = "alex-password-1234";
    let app = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .spawn()
        .await;
    let started = app
        .desktop_client()
        .post("/api/setup/desktop-quick-start")
        .json(&json!({"displayName": "Camille"}))
        .send()
        .await;
    assert_eq!(started.status, StatusCode::OK);
    app.create_member("alex", ALEX_PASSWORD).await;
    let sign_in_as_alex = json!({"username": "alex", "password": ALEX_PASSWORD});
    let proof = json!({"reAuth": {"password": ALEX_PASSWORD}});

    let browser = app.client();
    let window = app.desktop_client();
    for (place, client) in [("a browser", &browser), ("the app's window", &window)] {
        let signed_in = client
            .post("/api/auth/login")
            .json(&sign_in_as_alex)
            .send()
            .await;
        assert_eq!(signed_in.status, StatusCode::OK, "{place}");

        let refused = client.post("/api/me/export").json(&json!({})).send().await;
        assert_eq!(refused.status, StatusCode::UNAUTHORIZED, "{place}");
        assert_eq!(refused.json()["code"], "REAUTH_FAILED", "{place}");
        let proven = client.post("/api/me/export").json(&proof).send().await;
        assert_eq!(proven.status, StatusCode::OK, "{place}");
    }
}

/// Once the app's window has traded its launch token for the gate cookie, the cookie is what
/// lets it export.
#[tokio::test]
async fn the_gate_cookie_of_the_window_stands_for_the_password_too() {
    let app = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .spawn()
        .await;
    let window = app.client();
    let token = app.desktop_token().expect("a launch token");
    let bootstrapped = window
        .get(&format!("/api/auth/desktop-bootstrap?token={token}"))
        .send()
        .await;
    assert!(
        bootstrapped.status.is_success() || bootstrapped.status.is_redirection(),
        "{}",
        bootstrapped.text()
    );
    let started = window
        .post("/api/setup/desktop-quick-start")
        .json(&json!({"displayName": "Camille"}))
        .send()
        .await;
    assert_eq!(started.status, StatusCode::OK, "{}", started.text());

    let exported = window.post("/api/me/export").json(&json!({})).send().await;

    assert_eq!(exported.status, StatusCode::OK, "{}", exported.text());
}

#[tokio::test]
async fn autologin_and_password_lock_lifecycle() {
    let app = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .spawn()
        .await;

    // 1. Quick-start creates the local admin unlocked.
    let qs = app
        .desktop_client()
        .post("/api/setup/desktop-quick-start")
        .json(&json!({"displayName": "Camille"}))
        .send()
        .await;
    assert_eq!(qs.status, StatusCode::OK);

    // 2. A fresh desktop client (simulating app relaunch without session cookies) auto-logs in seamlessly.
    let relaunched = app.desktop_client();
    let auto = relaunched.post("/api/auth/desktop-autologin").send().await;
    assert_eq!(auto.status, StatusCode::OK);
    assert_eq!(auto.json()["username"], "camille");
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

    // The app's own window is told which account the password belongs to, so the login form
    // can name it: the member never chose that username. A browser on the same machine is not.
    let from_the_app = app.desktop_client().get("/api/setup/status").send().await;
    assert_eq!(from_the_app.json()["desktopUsername"], "camille");
    let from_a_browser = app.client().get("/api/setup/status").send().await;
    assert!(from_a_browser.json().get("desktopUsername").is_none());

    // 6. Auto-login is now refused with 403 DESKTOP_LOCK_ENABLED.
    let locked_auto = app
        .desktop_client()
        .post("/api/auth/desktop-autologin")
        .send()
        .await;
    assert_eq!(locked_auto.status, StatusCode::FORBIDDEN);
    assert_eq!(locked_auto.json()["code"], "DESKTOP_LOCK_ENABLED");

    // 7. Standard password login works with the new password.
    let logged_in = app.login("camille", "my-strong-password").await;
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
        .desktop_client()
        .post("/api/auth/desktop-autologin")
        .send()
        .await;
    assert_eq!(auto_again.status, StatusCode::OK);
    assert_eq!(auto_again.json()["username"], "camille");
}

#[tokio::test]
async fn non_admin_member_is_forbidden_from_desktop_lock_and_lock_cannot_be_overwritten() {
    let app = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .spawn()
        .await;

    let qs = app
        .desktop_client()
        .post("/api/setup/desktop-quick-start")
        .json(&json!({"displayName": "Camille"}))
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
    let admin = app.desktop_client();
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
        .json(&json!({"username": "camille", "password": "overwritten-password"}))
        .send()
        .await;
    assert_eq!(bad_login.status, StatusCode::UNAUTHORIZED);

    let good_login = app.login("camille", "original-admin-pw").await;
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
        .desktop_client()
        .post("/api/setup/desktop-quick-start")
        .json(&json!({"displayName": "Camille"}))
        .send()
        .await;
    assert_eq!(qs.status, StatusCode::OK);

    // Enable lock once, then burn remaining DESKTOP_LOCK tokens (5/15m) with bad password checks.
    let admin = app.desktop_client();
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

    // Unauthorized clients without the desktop token are rejected BEFORE consuming rate-limit budget.
    for _ in 0..5 {
        assert_eq!(
            app.client()
                .post("/api/auth/desktop-autologin")
                .send()
                .await
                .status,
            StatusCode::FORBIDDEN
        );
    }

    // Burn the remaining DESKTOP_AUTOLOGIN bucket (20/1m; 1 already consumed above).
    for _ in 0..19 {
        let resp = app
            .desktop_client()
            .post("/api/auth/desktop-autologin")
            .send()
            .await;
        assert_eq!(resp.status, StatusCode::FORBIDDEN);
    }
    let throttled_auto = app
        .desktop_client()
        .post("/api/auth/desktop-autologin")
        .send()
        .await;
    assert_eq!(throttled_auto.status, StatusCode::TOO_MANY_REQUESTS);
}

async fn raw_http_head(base_url: &str, request: &[u8]) -> String {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let addr = base_url
        .strip_prefix("http://")
        .expect("http base_url for TestApp");
    let mut stream = tokio::net::TcpStream::connect(addr)
        .await
        .expect("connect to TestApp");
    stream.write_all(request).await.expect("write raw request");
    let mut buf = Vec::new();
    let mut chunk = [0u8; 4096];
    loop {
        match stream.read(&mut chunk).await {
            Ok(0) => break,
            Ok(n) => buf.extend_from_slice(&chunk[..n]),
            Err(e) if e.kind() == std::io::ErrorKind::ConnectionReset && !buf.is_empty() => break,
            Err(e) => panic!("read raw response: {e}"),
        }
    }
    let raw = String::from_utf8(buf).expect("UTF-8 HTTP response");
    raw.split_once("\r\n\r\n")
        .map(|(head, _)| head.to_owned())
        .unwrap_or(raw)
}

#[tokio::test]
async fn desktop_bootstrap_and_session_gate_enforce_single_use_token_and_fail_closed() {
    let app = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .spawn()
        .await;
    let token = app.desktop_token().expect("test desktop token").to_owned();

    // 1. Missing token/cookie -> 403 on quick-start and bootstrap.
    assert_eq!(
        app.client()
            .post("/api/setup/desktop-quick-start")
            .json(&json!({"displayName": "NoToken"}))
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        app.client()
            .get("/api/auth/desktop-bootstrap")
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );

    // 2. Wrong token -> 403 and does NOT burn the single-use bootstrap latch.
    assert_eq!(
        app.client()
            .post("/api/setup/desktop-quick-start")
            .header("x-picsou-desktop-token", "wrong-token")
            .json(&json!({"displayName": "WrongToken"}))
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        app.client()
            .get("/api/auth/desktop-bootstrap?token=wrong-token")
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );

    // 3. Valid bootstrap -> 303 See Other to `/` with HttpOnly; SameSite=Strict; Path=/api cookie.
    let webview = app.client();
    let boot = webview
        .get(&format!("/api/auth/desktop-bootstrap?token={token}"))
        .send()
        .await;
    assert_eq!(boot.status, StatusCode::SEE_OTHER);
    assert_eq!(boot.header("location"), Some("/"));
    assert_eq!(boot.header("cache-control"), Some("no-store"));
    let set_cookies = boot.set_cookies();
    assert_eq!(set_cookies.len(), 1);
    let gate_cookie = &set_cookies[0];
    assert!(
        gate_cookie.starts_with("picsou_desktop_gate="),
        "unexpected cookie: {gate_cookie}"
    );
    assert!(gate_cookie.contains("HttpOnly"), "{gate_cookie}");
    assert!(gate_cookie.contains("SameSite=Strict"), "{gate_cookie}");
    assert!(gate_cookie.contains("Path=/api"), "{gate_cookie}");

    // 4. Replaying the exact same valid bootstrap token is rejected with 403, AND the
    // X-Picsou-Desktop-Token header is now permanently dead once bootstrap has been consumed.
    let replay = app
        .client()
        .get(&format!("/api/auth/desktop-bootstrap?token={token}"))
        .send()
        .await;
    assert_eq!(replay.status, StatusCode::FORBIDDEN);
    assert_eq!(
        app.desktop_client()
            .post("/api/setup/desktop-quick-start")
            .json(&json!({"displayName": "HeaderAfterBootstrap"}))
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );

    // 5. WebView (holding only the gate cookie, no header) completes quick-start and reloads into autologin.
    let qs = webview
        .post("/api/setup/desktop-quick-start")
        .json(&json!({"displayName": "Camille"}))
        .send()
        .await;
    assert_eq!(qs.status, StatusCode::OK);

    // Simulate reload where auth cookies are gone but the desktop gate cookie remains.
    webview.remove_cookie("access_token");
    webview.remove_cookie("refresh_token");
    let auto = webview.post("/api/auth/desktop-autologin").send().await;
    assert_eq!(auto.status, StatusCode::OK);
    assert_eq!(auto.json()["username"], "camille");

    // Raw X-Picsou-Desktop-Token header is also rejected on autologin after bootstrap consumption.
    assert_eq!(
        app.desktop_client()
            .post("/api/auth/desktop-autologin")
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );

    // Another local process without cookie or header still gets 403 on autologin.
    assert_eq!(
        app.client()
            .post("/api/auth/desktop-autologin")
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );

    // 6. Fail closed when APP_DESKTOP_TOKEN is unset / blank.
    let unconfigured = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .env("APP_DESKTOP_TOKEN", "")
        .spawn()
        .await;
    assert!(unconfigured.desktop_token().is_none());
    assert_eq!(
        unconfigured
            .client()
            .get("/api/auth/desktop-bootstrap?token=anything")
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        unconfigured
            .client()
            .post("/api/setup/desktop-quick-start")
            .json(&json!({"displayName": "Camille"}))
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        unconfigured
            .client()
            .post("/api/setup/admin")
            .json(&json!({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}))
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );
    let hash = picsou::auth::password::hash(ADMIN_PASSWORD).await.unwrap();
    picsou::setup_auth::setup::seed_admin(
        &unconfigured.state,
        ADMIN_USERNAME,
        &hash,
        Some(ADMIN_USERNAME),
        None,
    )
    .await
    .unwrap();
    picsou::setup_auth::setup::mark_complete(&unconfigured.state)
        .await
        .unwrap();
    assert_eq!(
        unconfigured
            .client()
            .post("/api/auth/desktop-autologin")
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );
}

#[tokio::test]
async fn desktop_host_allowlist_blocks_dns_rebinding_and_cross_site_requests() {
    let app = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .env("ALLOWED_ORIGINS", "http://evil.example")
        .spawn()
        .await;
    let port = app.state.config.server_port;
    assert_ne!(port, 0);

    // Spoofed Host headers (DNS rebinding) are rejected with 403 on EVERY route before routing.
    for (host, path) in [
        (format!("evil.example:{port}"), "/api/setup/status"),
        (format!("evil.example:{port}"), "/actuator/health"),
        (
            format!("127.0.0.1.evil.example:{port}"),
            "/api/setup/status",
        ),
        ("127.0.0.1:9999".to_owned(), "/api/setup/status"),
    ] {
        let req = format!("GET {path} HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n");
        let head = raw_http_head(&app.base_url, req.as_bytes()).await;
        assert!(
            head.starts_with("HTTP/1.1 403 Forbidden\r\n"),
            "expected 403 for Host={host} path={path}, got: {head}"
        );
    }

    // Missing Host header (HTTP/1.0) is rejected with 403.
    let head = raw_http_head(
        &app.base_url,
        b"GET /api/setup/status HTTP/1.0\r\nConnection: close\r\n\r\n",
    )
    .await;
    assert!(
        head.starts_with("HTTP/1.0 403 Forbidden\r\n")
            || head.starts_with("HTTP/1.1 403 Forbidden\r\n"),
        "expected 403 for missing Host, got: {head}"
    );

    // Valid loopback Host headers (127.0.0.1, localhost, [::1]) on the bound port are allowed.
    for host in [
        format!("127.0.0.1:{port}"),
        format!("localhost:{port}"),
        format!("[::1]:{port}"),
    ] {
        let req =
            format!("GET /api/setup/status HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n");
        let head = raw_http_head(&app.base_url, req.as_bytes()).await;
        assert!(
            head.starts_with("HTTP/1.1 200 OK\r\n"),
            "expected 200 for Host={host}, got: {head}"
        );
    }

    // Cross-site Sec-Fetch-Site or cross-origin Origin on quick-start / autologin are rejected
    // with 403 EVEN WITHOUT credential cookies (and even if Origin is in ALLOWED_ORIGINS).
    let cross_qs = app
        .desktop_client()
        .post("/api/setup/desktop-quick-start")
        .header("sec-fetch-site", "cross-site")
        .json(&json!({"displayName": "Camille"}))
        .send()
        .await;
    assert_eq!(cross_qs.status, StatusCode::FORBIDDEN);
    assert!(cross_qs.text().contains("Cross-site request rejected"));

    let origin_qs = app
        .desktop_client()
        .post("/api/setup/desktop-quick-start")
        .header("origin", "http://evil.example")
        .json(&json!({"displayName": "Camille"}))
        .send()
        .await;
    assert_eq!(origin_qs.status, StatusCode::FORBIDDEN);
    assert!(origin_qs.text().contains("Cross-site request rejected"));

    // Complete quick-start legitimately, then verify cross-site autologin is also rejected.
    assert_eq!(
        app.desktop_client()
            .post("/api/setup/desktop-quick-start")
            .json(&json!({"displayName": "Camille"}))
            .send()
            .await
            .status,
        StatusCode::OK
    );

    let cross_auto = app
        .desktop_client()
        .post("/api/auth/desktop-autologin")
        .header("sec-fetch-site", "cross-site")
        .send()
        .await;
    assert_eq!(cross_auto.status, StatusCode::FORBIDDEN);
    assert!(cross_auto.text().contains("Cross-site request rejected"));

    let same_site_auto = app
        .desktop_client()
        .post("/api/auth/desktop-autologin")
        .header("sec-fetch-site", "same-site")
        .send()
        .await;
    assert_eq!(same_site_auto.status, StatusCode::FORBIDDEN);

    let origin_auto = app
        .desktop_client()
        .post("/api/auth/desktop-autologin")
        .header("origin", "http://evil.example")
        .send()
        .await;
    assert_eq!(origin_auto.status, StatusCode::FORBIDDEN);
    assert!(origin_auto.text().contains("Cross-site request rejected"));
}

#[tokio::test]
async fn mfa_enrollment_requires_desktop_lock_and_autologin_refuses_mfa_users() {
    let app = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .spawn()
        .await;

    let admin = app.desktop_client();
    assert_eq!(
        admin
            .post("/api/setup/desktop-quick-start")
            .json(&json!({"displayName": "Camille"}))
            .send()
            .await
            .status,
        StatusCode::OK
    );

    // 1. With Desktop lock disabled, TOTP enrollment init and verify are rejected with 400.
    let init_blocked = admin
        .post("/api/auth/mfa/enroll/init")
        .json(&json!({"currentPassword": "any-password"}))
        .send()
        .await;
    assert_eq!(init_blocked.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        init_blocked.json()["detail"],
        "Enable Desktop password lock before configuring two-factor authentication."
    );

    let verify_blocked = admin
        .post("/api/auth/mfa/enroll/verify")
        .json(&json!({"code": "123456"}))
        .send()
        .await;
    assert_eq!(verify_blocked.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        verify_blocked.json()["detail"],
        "Enable Desktop password lock before configuring two-factor authentication."
    );

    // 2. Enable Desktop lock, then enroll MFA successfully.
    assert_eq!(
        admin
            .put("/api/desktop/lock")
            .json(&json!({"enabled": true, "newPassword": "desktop-mfa-pass-1"}))
            .send()
            .await
            .status,
        StatusCode::OK
    );
    let init = admin
        .post("/api/auth/mfa/enroll/init")
        .json(&json!({"currentPassword": "desktop-mfa-pass-1"}))
        .send()
        .await;
    assert_eq!(init.status, StatusCode::OK);
    let secret = init.json()["secret"].as_str().unwrap().to_owned();
    let code = picsou::setup_auth::mfa::current_code(&app.state, &secret).unwrap();
    let verified = admin
        .post("/api/auth/mfa/enroll/verify")
        .json(&json!({"code": code}))
        .send()
        .await;
    assert_eq!(verified.status, StatusCode::OK);
    let recovery_code = verified.json()["recoveryCodes"][0]
        .as_str()
        .unwrap()
        .to_owned();

    // 3. Disabling Desktop lock while 2FA is active is refused with 409 MFA_ACTIVE.
    let disable_blocked = admin
        .put("/api/desktop/lock")
        .json(&json!({"enabled": false, "currentPassword": "desktop-mfa-pass-1"}))
        .send()
        .await;
    assert_eq!(disable_blocked.status, StatusCode::CONFLICT);
    let blocked_body = disable_blocked.json();
    assert_eq!(blocked_body["code"], "MFA_ACTIVE");
    assert_eq!(
        blocked_body["detail"],
        "Disable two-factor authentication before turning off Desktop password lock."
    );
    assert_eq!(
        admin.get("/api/desktop/lock").send().await.json()["enabled"],
        true
    );

    // 4. Defense-in-depth: even if the DB setting were flipped to false directly while 2FA is
    // active, autologin still refuses with 403 DESKTOP_LOCK_ENABLED.
    picsou::setup_auth::settings::upsert(
        &app.state.db,
        picsou::setup_auth::settings::DESKTOP_LOCK_ENABLED,
        "false",
    )
    .await
    .unwrap();
    let refused_auto = app
        .desktop_client()
        .post("/api/auth/desktop-autologin")
        .send()
        .await;
    assert_eq!(refused_auto.status, StatusCode::FORBIDDEN);
    assert_eq!(refused_auto.json()["code"], "DESKTOP_LOCK_ENABLED");
    picsou::setup_auth::settings::upsert(
        &app.state.db,
        picsou::setup_auth::settings::DESKTOP_LOCK_ENABLED,
        "true",
    )
    .await
    .unwrap();

    // 5. After disabling 2FA, disabling Desktop lock succeeds with 200 OK.
    let mfa_disabled = admin
        .post("/api/auth/mfa/disable")
        .json(&json!({
            "currentPassword": "desktop-mfa-pass-1",
            "code": recovery_code,
            "isRecoveryCode": true
        }))
        .send()
        .await;
    assert_eq!(mfa_disabled.status, StatusCode::NO_CONTENT);

    let disable_ok = admin
        .put("/api/desktop/lock")
        .json(&json!({"enabled": false, "currentPassword": "desktop-mfa-pass-1"}))
        .send()
        .await;
    assert_eq!(disable_ok.status, StatusCode::OK);
    assert_eq!(disable_ok.json()["enabled"], false);
}

#[tokio::test]
async fn classic_setup_endpoints_require_desktop_gate_and_cross_site_protection_in_desktop_mode() {
    let app = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .env("PICSOU_PROXY_IP", "127.0.0.1")
        .env("ALLOWED_ORIGINS", "http://evil.example")
        .spawn()
        .await;

    // GET /api/setup/status remains readable without the Desktop gate token.
    assert_eq!(
        app.client().get("/api/setup/status").send().await.status,
        StatusCode::OK
    );

    // Every classic setup mutation is rejected with 403 without the Desktop gate token.
    assert_eq!(
        app.client()
            .post("/api/setup/admin")
            .json(&json!({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}))
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        app.client()
            .post("/api/setup/security")
            .json(&json!({"allowedOrigins": ["http://localhost:5173"], "secureCookies": false}))
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        app.client()
            .post("/api/setup/integrations/finary/acknowledge")
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        app.client().post("/api/setup/complete").send().await.status,
        StatusCode::FORBIDDEN
    );

    // Non-loopback client IP is rejected with 403 even with a valid Desktop gate token.
    assert_eq!(
        app.desktop_client()
            .post("/api/setup/admin")
            .header("x-forwarded-for", "203.0.113.42")
            .json(&json!({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}))
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );

    // Cross-site requests to classic setup routes are rejected even with a valid Desktop gate token.
    let cross_site = app
        .desktop_client()
        .post("/api/setup/admin")
        .header("sec-fetch-site", "cross-site")
        .json(&json!({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}))
        .send()
        .await;
    assert_eq!(cross_site.status, StatusCode::FORBIDDEN);
    assert!(cross_site.text().contains("Cross-site request rejected"));

    let cross_origin = app
        .desktop_client()
        .post("/api/setup/security")
        .header("origin", "http://evil.example")
        .json(&json!({"allowedOrigins": ["http://localhost:5173"], "secureCookies": false}))
        .send()
        .await;
    assert_eq!(cross_origin.status, StatusCode::FORBIDDEN);
    assert!(cross_origin.text().contains("Cross-site request rejected"));

    // With a valid Desktop gate token from loopback, the classic setup endpoints pass the gate.
    let gated = app.desktop_client();
    assert_eq!(
        gated
            .post("/api/setup/admin")
            .json(&json!({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}))
            .send()
            .await
            .status,
        StatusCode::OK
    );
    assert_eq!(
        gated
            .post("/api/setup/security")
            .json(&json!({"allowedOrigins": ["http://localhost:5173"], "secureCookies": false}))
            .send()
            .await
            .status,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        gated
            .post("/api/setup/integrations/finary/acknowledge")
            .send()
            .await
            .status,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        gated.post("/api/setup/complete").send().await.status,
        StatusCode::NO_CONTENT
    );

    // Outside Desktop Mode, classic setup routes continue to work without any Desktop token.
    let normal = TestApp::spawn().await;
    let client = normal.client();
    assert_eq!(
        client
            .post("/api/setup/admin")
            .json(&json!({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}))
            .send()
            .await
            .status,
        StatusCode::OK
    );
    assert_eq!(
        client
            .post("/api/setup/security")
            .json(&json!({"allowedOrigins": ["http://localhost:5173"], "secureCookies": false}))
            .send()
            .await
            .status,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        client
            .post("/api/setup/integrations/finary/acknowledge")
            .send()
            .await
            .status,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        client.post("/api/setup/complete").send().await.status,
        StatusCode::NO_CONTENT
    );
}

#[tokio::test]
async fn legacy_postgres_detected_flag_is_exposed_only_when_configured_in_desktop_mode() {
    let normal_desktop = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .spawn()
        .await;
    let normal_status = normal_desktop
        .client()
        .get("/api/setup/status")
        .send()
        .await
        .json();
    assert!(normal_status.get("legacyPostgresDetected").is_none());

    let legacy_desktop = TestApp::builder()
        .env("APP_DESKTOP_MODE", "true")
        .env("APP_DESKTOP_LEGACY_POSTGRES", "true")
        .spawn()
        .await;
    let legacy_status = legacy_desktop
        .client()
        .get("/api/setup/status")
        .send()
        .await
        .json();
    assert_eq!(legacy_status["desktopMode"], true);
    assert_eq!(legacy_status["legacyPostgresDetected"], true);

    // Once Desktop quick-start completes setup, legacyPostgresDetected is no longer exposed.
    assert_eq!(
        legacy_desktop
            .desktop_client()
            .post("/api/setup/desktop-quick-start")
            .json(&json!({"displayName": "Camille"}))
            .send()
            .await
            .status,
        StatusCode::OK
    );
    let legacy_after = legacy_desktop
        .client()
        .get("/api/setup/status")
        .send()
        .await
        .json();
    assert_eq!(legacy_after["needsSetup"], false);
    assert!(legacy_after.get("legacyPostgresDetected").is_none());

    let non_desktop = TestApp::builder()
        .env("APP_DESKTOP_LEGACY_POSTGRES", "true")
        .spawn()
        .await;
    let non_desktop_status = non_desktop
        .client()
        .get("/api/setup/status")
        .send()
        .await
        .json();
    assert!(non_desktop_status.get("legacyPostgresDetected").is_none());
}
