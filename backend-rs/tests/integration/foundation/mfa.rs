use std::time::Duration;

use picsou::testing::{ADMIN_PASSWORD, ADMIN_USERNAME, Client, TestApp};
use reqwest::StatusCode;
use serde_json::json;

/// Enrolls the admin and returns (secret, recovery codes).
async fn enroll(app: &TestApp, client: &Client) -> (String, Vec<String>) {
    let init = client
        .post("/api/auth/mfa/enroll/init")
        .json(&json!({"currentPassword": ADMIN_PASSWORD}))
        .send()
        .await;
    assert_eq!(init.status, StatusCode::OK);
    let init = init.json();
    let secret = init["secret"].as_str().unwrap().to_owned();
    assert_eq!(secret.len(), 32);
    assert!(
        init["qrCodeDataUri"]
            .as_str()
            .unwrap()
            .starts_with("data:image/png;base64,iVBOR")
    );
    let code = picsou::setup_auth::mfa::current_code(&app.state, &secret).unwrap();
    let verified = client
        .post("/api/auth/mfa/enroll/verify")
        .json(&json!({"code": code}))
        .send()
        .await;
    assert_eq!(verified.status, StatusCode::OK);
    let codes: Vec<String> = verified.json()["recoveryCodes"]
        .as_array()
        .unwrap()
        .iter()
        .map(|c| c.as_str().unwrap().to_owned())
        .collect();
    (secret, codes)
}

fn code(app: &TestApp, secret: &str) -> String {
    picsou::setup_auth::mfa::current_code(&app.state, secret).unwrap()
}

#[tokio::test]
async fn enrollment() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    assert_eq!(
        client.get("/api/auth/mfa/status").send().await.text(),
        r#"{"enabled":false,"remainingRecoveryCodes":0}"#
    );
    let wrong = client
        .post("/api/auth/mfa/enroll/init")
        .json(&json!({"currentPassword": "nope"}))
        .send()
        .await;
    assert_eq!(wrong.status, StatusCode::BAD_REQUEST);
    assert_eq!(wrong.json()["detail"], "Current password is incorrect");
    let not_started = client
        .post("/api/auth/mfa/enroll/verify")
        .json(&json!({"code": "123456"}))
        .send()
        .await;
    assert_eq!(not_started.json()["detail"], "MFA enrollment not started");
    let malformed = client
        .post("/api/auth/mfa/enroll/verify")
        .json(&json!({"code": "12"}))
        .send()
        .await;
    assert_eq!(
        malformed.json()["errors"],
        json!({"code": "TOTP code must be 6 digits"})
    );

    let (_, codes) = enroll(&app, &client).await;
    assert_eq!(codes.len(), 10);
    assert!(
        codes
            .iter()
            .all(|c| c.len() == 8 && c.chars().all(|ch| ch.is_ascii_digit()))
    );
    let status = client.get("/api/auth/mfa/status").send().await.json();
    assert_eq!(
        status,
        json!({"enabled": true, "enrolledAt": "2026-01-15T10:00:00Z", "remainingRecoveryCodes": 10})
    );
    let again = client
        .post("/api/auth/mfa/enroll/init")
        .json(&json!({"currentPassword": ADMIN_PASSWORD}))
        .send()
        .await;
    assert_eq!(again.json()["detail"], "MFA is already enabled");
}

#[tokio::test]
async fn the_secret_is_stored_encrypted() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let (secret, _) = enroll(&app, &client).await;
    let stored: String = picsou::db::query_scalar("SELECT totp_secret_enc FROM user_mfa")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_ne!(stored, secret);
    assert_eq!(app.state.crypto.decrypt(&stored).unwrap(), secret);
}

#[tokio::test]
async fn login_challenge() {
    let app = TestApp::with_admin().await;
    let (secret, codes) = enroll(&app, &app.login_admin().await).await;
    app.clock.advance(Duration::from_secs(30));

    let client = app.client();
    let login = client
        .post("/api/auth/login")
        .json(&json!({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD, "rememberMe": true}))
        .send()
        .await;
    assert_eq!(login.status, StatusCode::OK);
    assert_eq!(
        login.json(),
        json!({"mfaRequired": true, "username": "admin"})
    );
    let cookies = login.set_cookies();
    assert_eq!(cookies.len(), 4);
    assert!(cookies[0].starts_with("access_token=; Max-Age=0"));
    assert!(cookies[3].starts_with("mfa_challenge_token=") && cookies[3].contains("Max-Age=300"));
    assert_eq!(
        client.get("/api/auth/mfa/status").send().await.status,
        StatusCode::UNAUTHORIZED
    );

    let none = app
        .client()
        .post("/api/auth/mfa/verify")
        .json(&json!({"code": "123456"}))
        .send()
        .await;
    assert_eq!(none.status, StatusCode::UNAUTHORIZED);
    assert_eq!(none.json()["detail"], "No MFA challenge in progress");

    let wrong = client
        .post("/api/auth/mfa/verify")
        .json(&json!({"code": "000000"}))
        .send()
        .await;
    assert_eq!(wrong.status, StatusCode::BAD_REQUEST);
    assert_eq!(wrong.json()["detail"], "Invalid verification code");
    assert!(
        client.cookie("mfa_challenge_token").is_some(),
        "a wrong code keeps the challenge"
    );

    let current = code(&app, &secret);
    let verified = client
        .post("/api/auth/mfa/verify")
        .json(&json!({"code": current}))
        .send()
        .await;
    assert_eq!(verified.status, StatusCode::OK);
    assert_eq!(verified.json()["username"], "admin");
    assert!(
        client.cookie("persistent_token").is_some(),
        "the remember-me choice survives the challenge"
    );
    assert!(client.cookie("mfa_challenge_token").is_none());
    assert_eq!(
        client.get("/api/auth/mfa/status").send().await.status,
        StatusCode::OK
    );

    // The same code cannot be used twice.
    let other = app.client();
    other
        .post("/api/auth/login")
        .json(&json!({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}))
        .send()
        .await;
    let replay = other
        .post("/api/auth/mfa/verify")
        .json(&json!({"code": current}))
        .send()
        .await;
    assert_eq!(replay.status, StatusCode::BAD_REQUEST);

    // A recovery code works once.
    let recovery = other
        .post("/api/auth/mfa/verify")
        .json(&json!({"code": codes[0], "isRecoveryCode": true}))
        .send()
        .await;
    assert_eq!(recovery.status, StatusCode::OK);
    // Five verifications per IP per 15 minutes.
    app.clock.advance(Duration::from_secs(15 * 60));
    let third = app.client();
    third
        .post("/api/auth/login")
        .json(&json!({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}))
        .send()
        .await;
    let reused = third
        .post("/api/auth/mfa/verify")
        .json(&json!({"code": codes[0], "isRecoveryCode": true}))
        .send()
        .await;
    assert_eq!(reused.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        third.get("/api/auth/mfa/status").send().await.status,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn a_second_factor_presented_twice_at_the_same_instant_is_accepted_once() {
    use picsou::setup_auth::mfa::{verify_recovery_code, verify_totp};

    let app = TestApp::with_admin().await;
    let (secret, codes) = enroll(&app, &app.login_admin().await).await;
    app.clock.advance(Duration::from_secs(30));
    let user = picsou::auth::users::find_primary_active(&app.state.db)
        .await
        .expect("read the admin")
        .expect("the admin exists");
    let accepted = |a: bool, b: bool| usize::from(a) + usize::from(b);

    let totp = code(&app, &secret);
    let (first, second) = tokio::join!(
        verify_totp(&app.state, &user, &totp),
        verify_totp(&app.state, &user, &totp),
    );
    assert_eq!(accepted(first.unwrap(), second.unwrap()), 1, "TOTP code");

    let (first, second) = tokio::join!(
        verify_recovery_code(&app.state, &user, &codes[0]),
        verify_recovery_code(&app.state, &user, &codes[0]),
    );
    assert_eq!(
        accepted(first.unwrap(), second.unwrap()),
        1,
        "recovery code"
    );
}

#[tokio::test]
async fn a_trusted_device_skips_the_challenge() {
    let app = TestApp::with_admin().await;
    let (secret, _) = enroll(&app, &app.login_admin().await).await;
    app.clock.advance(Duration::from_secs(30));
    let device = app.client();
    device
        .post("/api/auth/login")
        .json(&json!({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}))
        .send()
        .await;
    let trusted = device
        .post("/api/auth/mfa/verify")
        .json(&json!({"code": code(&app, &secret), "trustDevice": true}))
        .send()
        .await;
    assert_eq!(trusted.status, StatusCode::OK);
    device.remove_cookie("access_token");
    device.remove_cookie("refresh_token");
    let again = device
        .post("/api/auth/login")
        .json(&json!({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}))
        .send()
        .await;
    assert_eq!(
        again.json()["role"],
        "ADMIN",
        "no second challenge on a trusted device"
    );
}

#[tokio::test]
async fn an_untrusted_remember_me_cookie_does_not_bypass_mfa() {
    let app = TestApp::with_admin().await;
    let remembered = app.client();
    remembered
        .post("/api/auth/login")
        .json(&json!({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD, "rememberMe": true}))
        .send()
        .await;
    let other = app.login_admin().await;
    let init = other
        .post("/api/auth/mfa/enroll/init")
        .json(&json!({"currentPassword": ADMIN_PASSWORD}))
        .send()
        .await
        .json();
    let secret = init["secret"].as_str().unwrap().to_owned();
    other
        .post("/api/auth/mfa/enroll/verify")
        .json(&json!({"code": code(&app, &secret)}))
        .send()
        .await;
    let persistent = remembered.cookie("persistent_token").unwrap();
    let fresh = app.client();
    fresh.set_cookie("persistent_token", &persistent);
    assert_eq!(
        fresh.get("/api/auth/sessions").send().await.status,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn regenerate_and_disable() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let (secret, codes) = enroll(&app, &client).await;
    app.clock.advance(Duration::from_secs(30));
    let by_recovery = client
        .post("/api/auth/mfa/recovery-codes/regenerate")
        .json(&json!({"currentPassword": ADMIN_PASSWORD, "code": codes[0]}))
        .send()
        .await;
    assert_eq!(
        by_recovery.status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "an 8-digit code fails the 6-digit pattern"
    );
    let regenerated = client
        .post("/api/auth/mfa/recovery-codes/regenerate")
        .json(&json!({"currentPassword": ADMIN_PASSWORD, "code": code(&app, &secret)}))
        .send()
        .await;
    assert_eq!(regenerated.status, StatusCode::OK);
    let fresh: Vec<String> = regenerated.json()["recoveryCodes"]
        .as_array()
        .unwrap()
        .iter()
        .map(|c| c.as_str().unwrap().to_owned())
        .collect();
    let wrong = client
        .post("/api/auth/mfa/disable")
        .json(&json!({"currentPassword": ADMIN_PASSWORD, "code": codes[0], "isRecoveryCode": true}))
        .send()
        .await;
    assert_eq!(
        wrong.status,
        StatusCode::BAD_REQUEST,
        "the old codes are gone"
    );
    let disabled = client
        .post("/api/auth/mfa/disable")
        .json(&json!({"currentPassword": ADMIN_PASSWORD, "code": fresh[0], "isRecoveryCode": true}))
        .send()
        .await;
    assert_eq!(disabled.status, StatusCode::NO_CONTENT);
    assert_eq!(
        client.get("/api/auth/mfa/status").send().await.json()["enabled"],
        false
    );
}

async fn install_abort_trigger(db: &picsou::db::Db, event: &str, table: &str, name: &str) {
    match db {
        picsou::db::Db::Postgres(pool) => {
            let fn_name = format!("{name}_fn");
            sqlx::raw_sql(&format!(
                "CREATE OR REPLACE FUNCTION {fn_name}() RETURNS trigger AS $$ \
                 BEGIN RAISE EXCEPTION 'injected failure'; END; \
                 $$ LANGUAGE plpgsql; \
                 CREATE TRIGGER {name} BEFORE {event} ON {table} \
                 FOR EACH ROW EXECUTE FUNCTION {fn_name}();"
            ))
            .execute(pool)
            .await
            .expect("install pg trigger");
        }
        picsou::db::Db::Sqlite(sq) => {
            let sql = format!(
                "CREATE TRIGGER {name} BEFORE {event} ON {table} \
                 BEGIN SELECT RAISE(ABORT, 'injected failure'); END;"
            );
            sq.with_conn(move |conn| {
                conn.execute_batch(&sql)?;
                Ok(())
            })
            .expect("install sqlite trigger");
        }
    }
}

async fn drop_trigger(db: &picsou::db::Db, table: &str, name: &str) {
    match db {
        picsou::db::Db::Postgres(pool) => {
            let fn_name = format!("{name}_fn");
            sqlx::raw_sql(&format!(
                "DROP TRIGGER IF EXISTS {name} ON {table}; \
                 DROP FUNCTION IF EXISTS {fn_name}();"
            ))
            .execute(pool)
            .await
            .expect("drop pg trigger");
        }
        picsou::db::Db::Sqlite(sq) => {
            let sql = format!("DROP TRIGGER IF EXISTS {name};");
            sq.with_conn(move |conn| {
                conn.execute_batch(&sql)?;
                Ok(())
            })
            .expect("drop sqlite trigger");
        }
    }
}

#[tokio::test]
async fn enroll_verify_rolls_back_when_storing_recovery_codes_fails() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let init = client
        .post("/api/auth/mfa/enroll/init")
        .json(&json!({"currentPassword": ADMIN_PASSWORD}))
        .send()
        .await;
    assert_eq!(init.status, StatusCode::OK);
    let secret = init.json()["secret"].as_str().unwrap().to_owned();

    install_abort_trigger(
        &app.state.db,
        "INSERT",
        "user_mfa_recovery_code",
        "abort_rc_insert",
    )
    .await;

    let failed = client
        .post("/api/auth/mfa/enroll/verify")
        .json(&json!({"code": code(&app, &secret)}))
        .send()
        .await;
    assert_eq!(failed.status, StatusCode::INTERNAL_SERVER_ERROR);

    drop_trigger(&app.state.db, "user_mfa_recovery_code", "abort_rc_insert").await;

    let status = client.get("/api/auth/mfa/status").send().await.json();
    assert_eq!(
        status["enabled"], false,
        "2FA must not stay enabled with zero recovery codes"
    );
    assert_eq!(status["remainingRecoveryCodes"], 0);

    let retried = client
        .post("/api/auth/mfa/enroll/verify")
        .json(&json!({"code": code(&app, &secret)}))
        .send()
        .await;
    assert_eq!(retried.status, StatusCode::OK);
    assert_eq!(
        retried.json()["recoveryCodes"].as_array().unwrap().len(),
        10
    );
}

#[tokio::test]
async fn regenerate_rolls_back_when_inserting_new_recovery_codes_fails() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let (secret, original_codes) = enroll(&app, &client).await;
    app.clock.advance(Duration::from_secs(30));

    install_abort_trigger(
        &app.state.db,
        "INSERT",
        "user_mfa_recovery_code",
        "abort_rc_regen",
    )
    .await;

    let failed = client
        .post("/api/auth/mfa/recovery-codes/regenerate")
        .json(&json!({"currentPassword": ADMIN_PASSWORD, "code": code(&app, &secret)}))
        .send()
        .await;
    assert_eq!(failed.status, StatusCode::INTERNAL_SERVER_ERROR);

    drop_trigger(&app.state.db, "user_mfa_recovery_code", "abort_rc_regen").await;

    let status = client.get("/api/auth/mfa/status").send().await.json();
    assert_eq!(status["enabled"], true);
    assert_eq!(
        status["remainingRecoveryCodes"], 10,
        "existing recovery codes must survive a failed regenerate"
    );

    let disabled = client
        .post("/api/auth/mfa/disable")
        .json(&json!({"currentPassword": ADMIN_PASSWORD, "code": original_codes[0], "isRecoveryCode": true}))
        .send()
        .await;
    assert_eq!(disabled.status, StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn disable_rolls_back_when_deleting_mfa_fails() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let (secret, _) = enroll(&app, &client).await;
    app.clock.advance(Duration::from_secs(30));

    install_abort_trigger(&app.state.db, "DELETE", "user_mfa", "abort_mfa_delete").await;

    let failed = client
        .post("/api/auth/mfa/disable")
        .json(&json!({"currentPassword": ADMIN_PASSWORD, "code": code(&app, &secret)}))
        .send()
        .await;
    assert_eq!(failed.status, StatusCode::INTERNAL_SERVER_ERROR);

    drop_trigger(&app.state.db, "user_mfa", "abort_mfa_delete").await;

    let status = client.get("/api/auth/mfa/status").send().await.json();
    assert_eq!(status["enabled"], true);
    assert_eq!(
        status["remainingRecoveryCodes"], 10,
        "recovery codes must not be deleted when disabling 2FA rolls back"
    );
}
