use std::time::Duration;

use picsou::testing::{ADMIN_PASSWORD, ADMIN_USERNAME, TestApp};
use reqwest::StatusCode;
use serde_json::json;

fn login_body(username: &str, password: &str, remember: bool) -> serde_json::Value {
    json!({"username": username, "password": password, "rememberMe": remember})
}

#[tokio::test]
async fn wrong_password_and_unknown_user_answer_alike() {
    let app = TestApp::with_admin().await;
    let client = app.client();
    let wrong = client
        .post("/api/auth/login")
        .json(&login_body(ADMIN_USERNAME, "wrong-password", false))
        .send()
        .await;
    let unknown = client
        .post("/api/auth/login")
        .json(&login_body("nobody", "wrong-password", false))
        .send()
        .await;
    assert_eq!(wrong.status, StatusCode::UNAUTHORIZED);
    assert_eq!(wrong.text(), unknown.text());
    assert_eq!(
        wrong.text(),
        r#"{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Invalid credentials","instance":"/api/auth/login"}"#
    );
    assert!(wrong.set_cookies().is_empty());
}

#[tokio::test]
async fn pending_activation_costs_a_bcrypt_round_too() {
    let app = TestApp::with_admin().await;
    app.create_member("pending", "irrelevant-1").await;
    sqlx::query("UPDATE app_user SET password_hash = '' WHERE username = 'pending'")
        .execute(&app.state.db)
        .await
        .unwrap();
    let client = app.client();
    let started = std::time::Instant::now();
    let response = client
        .post("/api/auth/login")
        .json(&login_body("pending", "whatever-pw", false))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::UNAUTHORIZED);
    assert!(
        started.elapsed() >= Duration::from_millis(50),
        "a dummy bcrypt comparison ran"
    );
}

#[tokio::test]
async fn session_login_sets_browser_session_cookies() {
    let app = TestApp::with_admin().await;
    let client = app.client();
    let response = client
        .post("/api/auth/login")
        .json(&login_body(ADMIN_USERNAME, ADMIN_PASSWORD, false))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::OK);
    assert_eq!(
        response.text(),
        r#"{"role":"ADMIN","displayName":"admin","username":"admin","memberId":1}"#
    );
    let cookies = response.set_cookies();
    assert_eq!(cookies.len(), 2);
    assert!(cookies[0].starts_with("access_token=ey"));
    assert!(
        cookies[0].ends_with("; Path=/; HttpOnly; SameSite=Lax"),
        "{}",
        cookies[0]
    );
    assert!(!cookies[0].contains("Max-Age"));
    assert!(cookies[1].starts_with("refresh_token=ey"));
    assert_eq!(
        client.get("/api/auth/mfa/status").send().await.status,
        StatusCode::OK
    );
}

#[tokio::test]
async fn remember_me_issues_a_persistent_series() {
    let app = TestApp::with_admin().await;
    let client = app.client();
    let response = client
        .post("/api/auth/login")
        .json(&login_body(ADMIN_USERNAME, ADMIN_PASSWORD, true))
        .send()
        .await;
    let cookies = response.set_cookies();
    assert_eq!(cookies.len(), 3);
    assert!(cookies[0].contains("; Max-Age=900; Path=/"));
    assert!(cookies[1].contains("; Max-Age=604800; Path=/"));
    assert!(cookies[2].starts_with("persistent_token="));
    assert!(cookies[2].contains("; Max-Age=7776000; Path=/"));
    let refresh = client.cookie("refresh_token").unwrap();
    let claims = decode_claims(&refresh);
    let series = client
        .cookie("persistent_token")
        .unwrap()
        .split(':')
        .next()
        .unwrap()
        .to_owned();
    assert_eq!(
        claims["sid"], series,
        "the refresh token is bound to the series"
    );
    assert_eq!(claims["type"], "refresh");
    assert_eq!(claims["tv"], 0);
}

#[tokio::test]
async fn jwt_claims_match_the_java_tokens() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let token = client.cookie("access_token").unwrap();
    let header = decode_segment(token.split('.').next().unwrap());
    assert_eq!(
        header,
        json!({"alg": "HS384"}),
        "a 48-byte secret signs HS384, as jjwt does"
    );
    let claims = decode_claims(&token);
    let now = picsou::testing::epoch().timestamp();
    assert_eq!(
        claims,
        json!({"sub": "admin", "uid": 1, "role": "ADMIN", "type": "access", "tv": 0, "iat": now, "exp": now + 900})
    );
}

#[tokio::test]
async fn refresh_rotates_and_fails_cleanly() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    app.clock.advance(Duration::from_secs(60));
    let refreshed = client.post("/api/auth/refresh").send().await;
    assert_eq!(refreshed.status, StatusCode::OK);
    assert_eq!(refreshed.json()["username"], "admin");
    assert_eq!(refreshed.set_cookies().len(), 2);

    let anonymous = app.client().post("/api/auth/refresh").send().await;
    assert_eq!(anonymous.status, StatusCode::UNAUTHORIZED);
    assert_eq!(anonymous.json()["detail"], "No refresh token");
    assert_eq!(
        anonymous.set_cookies(),
        [
            "access_token",
            "refresh_token",
            "mfa_challenge_token",
            "persistent_token",
            "reauth_proof"
        ]
        .map(|n| format!("{n}=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax"))
    );
}

#[tokio::test]
async fn access_tokens_expire_with_the_clock() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    app.clock.advance(Duration::from_secs(15 * 60 + 1));
    let expired = client.get("/api/auth/mfa/status").send().await;
    assert_eq!(expired.status, StatusCode::UNAUTHORIZED);
    assert_eq!(
        expired.text(),
        "{\"status\":401,\"title\":\"Unauthorized\",\"detail\":\"Authentication required\"}\n"
    );
    assert_eq!(
        client.post("/api/auth/refresh").send().await.status,
        StatusCode::OK
    );
    assert_eq!(
        client.get("/api/auth/mfa/status").send().await.status,
        StatusCode::OK
    );
}

#[tokio::test]
async fn changing_the_password_revokes_every_other_token() {
    let app = TestApp::with_admin().await;
    let elsewhere = app.login_admin().await;
    let here = app.login_admin().await;
    let wrong = here
        .post("/api/auth/change-password")
        .json(&json!({"currentPassword": "nope", "newPassword": "a-new-password"}))
        .send()
        .await;
    assert_eq!(wrong.status, StatusCode::UNAUTHORIZED);
    let changed = here
        .post("/api/auth/change-password")
        .json(&json!({"currentPassword": ADMIN_PASSWORD, "newPassword": "a-new-password"}))
        .send()
        .await;
    assert_eq!(changed.status, StatusCode::OK);
    assert_eq!(
        changed.json(),
        json!({"message": "Password updated successfully"})
    );
    assert_eq!(
        here.get("/api/auth/sessions").send().await.status,
        StatusCode::OK
    );
    assert_eq!(
        elsewhere.get("/api/auth/sessions").send().await.status,
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        elsewhere.post("/api/auth/refresh").send().await.status,
        StatusCode::UNAUTHORIZED
    );
    app.login(ADMIN_USERNAME, "a-new-password").await;
}

#[tokio::test]
async fn remember_me_restores_and_detects_replay() {
    let app = TestApp::with_admin().await;
    let client = app.client();
    client
        .post("/api/auth/login")
        .json(&login_body(ADMIN_USERNAME, ADMIN_PASSWORD, true))
        .send()
        .await;
    let original = client.cookie("persistent_token").unwrap();
    client.remove_cookie("access_token");
    client.remove_cookie("refresh_token");

    let restored = client.get("/api/auth/sessions").send().await;
    assert_eq!(restored.status, StatusCode::OK);
    let set = restored.set_cookies();
    assert!(
        set[0].starts_with("access_token=")
            && set[1].starts_with("refresh_token=")
            && set[2].starts_with("persistent_token=")
    );
    let rotated = client.cookie("persistent_token").unwrap();
    assert_ne!(rotated, original);

    // Another tab still holding the pre-rotation token within the grace window is accepted.
    let tab = app.client();
    tab.set_cookie("persistent_token", &original);
    assert_eq!(
        tab.get("/api/auth/sessions").send().await.status,
        StatusCode::OK
    );

    // Past the grace window the old token is a replay: the whole series is revoked.
    app.clock.advance(Duration::from_secs(31));
    let thief = app.client();
    thief.set_cookie("persistent_token", &original);
    let replay = thief.get("/api/auth/sessions").send().await;
    assert_eq!(replay.status, StatusCode::UNAUTHORIZED);
    assert_eq!(
        replay.set_cookies(),
        ["persistent_token=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax"]
    );
    let owner = app.client();
    owner.set_cookie(
        "persistent_token",
        &client.cookie("persistent_token").unwrap(),
    );
    assert_eq!(
        owner.get("/api/auth/sessions").send().await.status,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn logout_revokes_the_series() {
    let app = TestApp::with_admin().await;
    let client = app.client();
    client
        .post("/api/auth/login")
        .json(&login_body(ADMIN_USERNAME, ADMIN_PASSWORD, true))
        .send()
        .await;
    let persistent = client.cookie("persistent_token").unwrap();
    let out = client.post("/api/auth/logout").send().await;
    assert_eq!(out.status, StatusCode::NO_CONTENT);
    assert_eq!(out.set_cookies().len(), 5);
    let later = app.client();
    later.set_cookie("persistent_token", &persistent);
    assert_eq!(
        later.post("/api/auth/refresh").send().await.status,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn login_is_rate_limited_per_ip() {
    let app = TestApp::with_admin().await;
    let client = app.client();
    for _ in 0..5 {
        client
            .post("/api/auth/login")
            .json(&login_body(ADMIN_USERNAME, "wrong-password", false))
            .send()
            .await;
    }
    let limited = client
        .post("/api/auth/login")
        .json(&login_body(ADMIN_USERNAME, ADMIN_PASSWORD, false))
        .send()
        .await;
    assert_eq!(limited.status, StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(
        limited.json()["detail"],
        "Too many login attempts. Try again in 15 minutes."
    );
    app.clock.advance(Duration::from_secs(15 * 60));
    assert_eq!(
        client
            .post("/api/auth/login")
            .json(&login_body(ADMIN_USERNAME, ADMIN_PASSWORD, false))
            .send()
            .await
            .status,
        StatusCode::OK
    );
}

#[tokio::test]
async fn deactivated_accounts_get_no_session() {
    let app = TestApp::with_admin().await;
    app.create_member("sleeper", "sleeper-password").await;
    sqlx::query("UPDATE app_user SET is_activated = false WHERE username = 'sleeper'")
        .execute(&app.state.db)
        .await
        .unwrap();
    let response = app
        .client()
        .post("/api/auth/login")
        .json(&login_body("sleeper", "sleeper-password", false))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::FORBIDDEN);
    assert_eq!(
        response.json()["detail"],
        "Account not activated. Use your activation link to set a password."
    );
}

#[tokio::test]
async fn username_changes() {
    let app = TestApp::with_admin().await;
    app.create_member("taken", "taken-password").await;
    let client = app.login_admin().await;
    let invalid = client
        .patch("/api/auth/username")
        .json(&json!({"newUsername": "x"}))
        .send()
        .await;
    assert_eq!(invalid.status, StatusCode::UNPROCESSABLE_ENTITY);
    let conflict = client
        .patch("/api/auth/username")
        .json(&json!({"newUsername": "taken"}))
        .send()
        .await;
    assert_eq!(conflict.status, StatusCode::CONFLICT);
    assert_eq!(conflict.json()["detail"], "Username already taken");
    let same = client
        .patch("/api/auth/username")
        .json(&json!({"newUsername": "admin"}))
        .send()
        .await;
    assert_eq!(same.json(), json!({"username": "admin"}));
    assert!(same.set_cookies().is_empty());
    let renamed = client
        .patch("/api/auth/username")
        .json(&json!({"newUsername": "boss"}))
        .send()
        .await;
    assert_eq!(renamed.json(), json!({"username": "boss"}));
    assert_eq!(
        decode_claims(&client.cookie("access_token").unwrap())["sub"],
        "boss"
    );
    assert_eq!(
        client.post("/api/auth/refresh").send().await.json()["username"],
        "boss"
    );
}

#[tokio::test]
async fn invitation_activation() {
    let app = TestApp::with_admin().await;
    let member: i64 = sqlx::query_scalar(
        "INSERT INTO family_member (display_name, is_managed) VALUES ('Kid', true) RETURNING id",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO app_user (username, password_hash, member_id, role, is_activated, activation_token, activation_token_expires) \
         VALUES ('kid', '', $1, 'MEMBER', false, 'invite-token', $2)",
    )
    .bind(member)
    .bind(picsou::testing::epoch() + chrono::Duration::days(1))
    .execute(&app.state.db)
    .await
    .unwrap();
    let client = app.client();
    let unknown = client
        .post("/api/auth/activate/nope")
        .json(&json!({"password": "kid-password", "acknowledgedWarning": true}))
        .send()
        .await;
    assert_eq!(unknown.status, StatusCode::UNAUTHORIZED);
    let unacknowledged = client
        .post("/api/auth/activate/invite-token")
        .json(&json!({"password": "kid-password", "acknowledgedWarning": false}))
        .send()
        .await;
    assert_eq!(
        unacknowledged.json()["errors"],
        json!({"acknowledgedWarning": "must be true"})
    );

    app.clock.advance(Duration::from_secs(2 * 86_400));
    let expired = client
        .post("/api/auth/activate/invite-token")
        .json(&json!({"password": "kid-password", "acknowledgedWarning": true}))
        .send()
        .await;
    assert_eq!(expired.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        expired.text(),
        r#"{"error":"Activation token has expired"}"#
    );
    app.clock.set(picsou::testing::epoch());

    let activated = client
        .post("/api/auth/activate/invite-token")
        .json(&json!({"password": "kid-password", "acknowledgedWarning": true}))
        .send()
        .await;
    assert_eq!(
        activated.json(),
        json!({"message": "Account activated successfully"})
    );
    app.login("kid", "kid-password").await;
    let reused = client
        .post("/api/auth/activate/invite-token")
        .json(&json!({"password": "kid-password", "acknowledgedWarning": true}))
        .send()
        .await;
    assert_eq!(reused.status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn admin_routes_need_the_admin_role() {
    let app = TestApp::with_admin().await;
    app.create_member("member", "member-password").await;
    let member = app.login("member", "member-password").await;
    let denied = member.get("/api/admin/anything").send().await;
    assert_eq!(denied.status, StatusCode::FORBIDDEN);
    let admin = app.login_admin().await;
    assert_eq!(
        admin.get("/api/admin/anything").send().await.status,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        app.client().get("/api/admin/anything").send().await.status,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn access_keys_authenticate_the_mcp_surface_only() {
    let app = TestApp::with_admin().await;
    let key = app.create_access_key(1, &["accounts:read"]).await;
    let client = app.client();
    let anonymous = client.get("/mcp").send().await;
    assert_eq!(anonymous.status, StatusCode::UNAUTHORIZED);
    assert_eq!(
        anonymous.header("www-authenticate"),
        Some(
            format!(
                "Bearer resource_metadata=\"{}/.well-known/oauth-protected-resource\"",
                app.base_url
            )
            .as_str()
        )
    );
    let authed = reqwest::Client::new()
        .get(format!("{}/mcp", app.base_url))
        .bearer_auth(&key)
        .send()
        .await
        .unwrap();
    assert_eq!(
        authed.status(),
        StatusCode::OK,
        "authenticated access key opens the MCP SSE endpoint"
    );
    assert!(
        authed
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .is_some_and(|ct| ct.starts_with("text/event-stream"))
    );
    drop(authed);
    assert_eq!(
        client
            .get("/mcp")
            .bearer("psk_wrongwrongwrongwrongwrongwrongwron")
            .send()
            .await
            .status,
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        client
            .get("/api/auth/sessions")
            .bearer(&key)
            .send()
            .await
            .status,
        StatusCode::UNAUTHORIZED
    );
    let session = app.login_admin().await;
    let cookie_on_mcp = session.get("/mcp").send().await;
    assert_eq!(
        cookie_on_mcp.status,
        StatusCode::UNAUTHORIZED,
        "web session cookies must not authenticate /mcp"
    );
    assert_eq!(
        cookie_on_mcp.header("www-authenticate"),
        Some(
            format!(
                "Bearer resource_metadata=\"{}/.well-known/oauth-protected-resource\"",
                app.base_url
            )
            .as_str()
        )
    );
}

#[tokio::test]
async fn a_bearer_access_token_works_like_the_cookie() {
    let app = TestApp::with_admin().await;
    let token = app.login_admin().await.cookie("access_token").unwrap();
    assert_eq!(
        app.client()
            .get("/api/auth/sessions")
            .bearer(&token)
            .send()
            .await
            .status,
        StatusCode::OK
    );
}

fn decode_segment(segment: &str) -> serde_json::Value {
    use base64::Engine;
    serde_json::from_slice(
        &base64::engine::general_purpose::URL_SAFE_NO_PAD
            .decode(segment)
            .unwrap(),
    )
    .unwrap()
}

fn decode_claims(token: &str) -> serde_json::Value {
    decode_segment(token.split('.').nth(1).unwrap())
}
