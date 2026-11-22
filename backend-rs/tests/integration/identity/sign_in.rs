use picsou::testing::{ADMIN_PASSWORD, TestApp};
use reqwest::StatusCode;
use serde_json::json;

#[tokio::test]
async fn lists_the_sign_in_methods() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let methods = admin.get("/api/auth/sign-in-methods").send().await.json();
    assert_eq!(
        methods,
        json!({"hasPassword": true, "mfaEnabled": false, "identities": [], "ssoEnabled": false,
               "providerName": "SSO", "adminGroupManaged": false})
    );
}

#[tokio::test]
async fn reauth_mints_a_proof_bound_to_the_token_version() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let wrong = admin
        .post("/api/auth/reauth")
        .json(&json!({"password": "nope"}))
        .send()
        .await;
    assert_eq!(wrong.status, StatusCode::UNAUTHORIZED);
    assert_eq!(wrong.json()["code"], "REAUTH_FAILED");
    assert_eq!(wrong.json()["title"], "REAUTH_FAILED");
    assert_eq!(wrong.json()["detail"], "invalid password");
    let ok = admin
        .post("/api/auth/reauth")
        .json(&json!({"password": ADMIN_PASSWORD}))
        .send()
        .await;
    assert_eq!(ok.status, StatusCode::NO_CONTENT);
    let cookie = ok
        .set_cookies()
        .into_iter()
        .find(|c| c.starts_with("reauth_proof="))
        .unwrap();
    assert!(cookie.contains("Max-Age=300"));
    let set = admin
        .put("/api/auth/password")
        .json(&json!({"newPassword": "another-password"}))
        .send()
        .await;
    assert_eq!(set.status, StatusCode::CONFLICT);
    assert_eq!(set.json()["code"], "PASSWORD_ALREADY_SET");
    let removed = admin.delete("/api/auth/password").send().await;
    assert_eq!(removed.status, StatusCode::CONFLICT);
    assert_eq!(removed.json()["code"], "SSO_DISABLED");
    picsou::db::query("UPDATE app_user SET token_version = token_version + 1")
        .execute(&app.state.db)
        .await
        .unwrap();
    let fresh = app.login_admin().await;
    fresh.set_cookie("reauth_proof", &admin.cookie("reauth_proof").unwrap());
    let stale = fresh.delete("/api/auth/password").send().await;
    assert_eq!(
        stale.status,
        StatusCode::UNAUTHORIZED,
        "a token-version bump voids the proof"
    );
    assert_eq!(stale.json()["detail"], "re-auth proof missing or invalid");
}

#[tokio::test]
async fn reauth_requires_the_totp_when_2fa_is_on() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let secret = admin
        .post("/api/auth/mfa/enroll/init")
        .json(&json!({"currentPassword": ADMIN_PASSWORD}))
        .send()
        .await
        .json()["secret"]
        .as_str()
        .unwrap()
        .to_owned();
    let code = picsou::setup_auth::mfa::current_code(&app.state, &secret).unwrap();
    let verified = admin
        .post("/api/auth/mfa/enroll/verify")
        .json(&json!({"code": code}))
        .send()
        .await;
    assert_eq!(verified.status, StatusCode::OK);
    let without = admin
        .post("/api/auth/reauth")
        .json(&json!({"password": ADMIN_PASSWORD}))
        .send()
        .await;
    assert_eq!(without.json()["detail"], "totp required");
}

#[tokio::test]
async fn reauth_is_rate_limited_per_user() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    for _ in 0..5 {
        admin
            .post("/api/auth/reauth")
            .json(&json!({"password": "x"}))
            .send()
            .await;
    }
    let limited = admin
        .post("/api/auth/reauth")
        .json(&json!({"password": ADMIN_PASSWORD}))
        .send()
        .await;
    assert_eq!(limited.status, StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(limited.json()["code"], "RATE_LIMITED");
}

async fn passwordless_with_identity(app: &TestApp, issuer: &str) -> (picsou::testing::Client, i64) {
    let (user_id, _) = app.create_member("sso-user", "temporary-pass").await;
    let client = app.login("sso-user", "temporary-pass").await;
    let proof = client
        .post("/api/auth/reauth")
        .json(&json!({"password": "temporary-pass"}))
        .send()
        .await;
    assert_eq!(proof.status, StatusCode::NO_CONTENT);
    picsou::db::query("UPDATE app_user SET password_hash = '' WHERE id = $1")
        .bind(user_id)
        .execute(&app.state.db)
        .await
        .unwrap();
    let identity: i64 = picsou::db::query_scalar(
        "INSERT INTO user_identity (user_id, issuer, subject, last_login_at) VALUES ($1, $2, 'sub-1', now()) RETURNING id",
    )
    .bind(user_id)
    .bind(issuer)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    (client, identity)
}

#[tokio::test]
async fn the_last_identity_of_a_passwordless_account_stays_until_a_password_is_set() {
    let app = TestApp::builder()
        .env("SSO_ISSUER_URI", "https://idp.example")
        .env("SSO_CLIENT_ID", "picsou")
        .with_admin()
        .spawn()
        .await;
    let (client, identity) = passwordless_with_identity(&app, "https://idp.example").await;
    let refused = client
        .delete(&format!("/api/auth/identities/{identity}"))
        .send()
        .await;
    assert_eq!(refused.status, StatusCode::CONFLICT);
    assert_eq!(refused.json()["code"], "LAST_SIGN_IN_METHOD");
    let short = client
        .put("/api/auth/password")
        .json(&json!({"newPassword": "short"}))
        .send()
        .await;
    assert_eq!(short.status, StatusCode::UNPROCESSABLE_ENTITY);
    let set = client
        .put("/api/auth/password")
        .json(&json!({"newPassword": "a-new-password"}))
        .send()
        .await;
    assert_eq!(set.status, StatusCode::NO_CONTENT, "{}", set.text());
    let cookies = set.set_cookies();
    assert!(
        cookies
            .iter()
            .any(|c| c.starts_with("access_token=") && !c.starts_with("access_token=;"))
    );
    assert!(cookies.iter().any(|c| c.starts_with("persistent_token=;")));
    let methods = client.get("/api/auth/sign-in-methods").send().await.json();
    assert_eq!(methods["hasPassword"], true);
    assert_eq!(methods["ssoEnabled"], true);
    let proof = client
        .post("/api/auth/reauth")
        .json(&json!({"password": "a-new-password"}))
        .send()
        .await;
    assert_eq!(proof.status, StatusCode::NO_CONTENT);
    let unlinked = client
        .delete(&format!("/api/auth/identities/{identity}"))
        .send()
        .await;
    assert_eq!(unlinked.status, StatusCode::NO_CONTENT);
    let audit: i64 = picsou::db::query_scalar("SELECT count(*) FROM setup_audit WHERE event IN ('auth.password.set', 'sso.identity.unlinked')")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(audit, 2);
}

#[tokio::test]
async fn turning_password_login_off_needs_a_used_identity_of_the_current_issuer() {
    let app = TestApp::builder()
        .env("SSO_ISSUER_URI", "https://idp.example")
        .env("SSO_CLIENT_ID", "picsou")
        .with_admin()
        .spawn()
        .await;
    let (user_id, _) = app.create_member("erin", "erin-password").await;
    let client = app.login("erin", "erin-password").await;
    client
        .post("/api/auth/reauth")
        .json(&json!({"password": "erin-password"}))
        .send()
        .await;
    picsou::db::query("INSERT INTO user_identity (user_id, issuer, subject) VALUES ($1, 'https://idp.example', 's')")
        .bind(user_id)
        .execute(&app.state.db)
        .await
        .unwrap();
    let unused = client.delete("/api/auth/password").send().await;
    assert_eq!(unused.json()["code"], "IDENTITY_NOT_USED_YET");
    picsou::db::query("UPDATE user_identity SET last_login_at = now() WHERE user_id = $1")
        .bind(user_id)
        .execute(&app.state.db)
        .await
        .unwrap();
    let removed = client.delete("/api/auth/password").send().await;
    assert_eq!(removed.status, StatusCode::NO_CONTENT);
    let user = picsou::auth::users::find_by_id(&app.state.db, user_id)
        .await
        .unwrap()
        .unwrap();
    assert!(user.password_hash.is_empty());
    assert_eq!(user.token_version, 1);
    let methods = client.get("/api/auth/sign-in-methods").send().await;
    assert_eq!(methods.status, StatusCode::OK, "the reissued session works");
    assert_eq!(methods.json()["hasPassword"], false);
    let other = app.login("admin", ADMIN_PASSWORD).await;
    let reset = other
        .delete(&format!("/api/admin/members/{}/identities", user.member_id))
        .send()
        .await;
    assert_eq!(reset.status, StatusCode::NO_CONTENT);
    let after = client.get("/api/auth/sign-in-methods").send().await;
    assert_eq!(
        after.status,
        StatusCode::UNAUTHORIZED,
        "the admin reset bumps the token version"
    );
}
