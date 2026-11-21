//! Integration tests of the admin module.

use std::time::Duration;

use picsou::testing::{ADMIN_PASSWORD, TestApp};
use reqwest::StatusCode;
use serde_json::{Value, json};
use wiremock::matchers::{header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

#[tokio::test]
async fn reads_and_writes_the_settings() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let settings = admin.get("/api/admin/settings").send().await.json();
    assert_eq!(
        settings["security"],
        json!({"allowedOrigins": [], "secureCookies": false})
    );
    assert_eq!(settings["enableBanking"]["privateKeyPresent"], false);
    assert_eq!(settings["integrations"]["crypto"], false);
    assert_eq!(
        settings["ai"],
        json!({"provider": "none", "model": "", "baseUrl": "", "apiKeyPresent": false, "maxConcurrency": 4})
    );
    assert_eq!(
        settings["telemetry"],
        json!({"available": false, "consent": "UNSET"})
    );
    let security = admin
        .put("/api/admin/settings/security")
        .json(&json!({"allowedOrigins": ["https://a.example"], "secureCookies": true}))
        .send()
        .await;
    assert_eq!(security.status, StatusCode::NO_CONTENT);
    let wildcard = admin
        .put("/api/admin/settings/security")
        .json(&json!({"allowedOrigins": ["https://*.example"]}))
        .send()
        .await;
    assert_eq!(
        wildcard.json()["detail"],
        "Wildcard origins are not allowed with credentialed CORS; list explicit origins."
    );
    let toggled = admin
        .patch("/api/admin/settings/integrations/crypto?enabled=on")
        .send()
        .await;
    assert_eq!(toggled.status, StatusCode::NO_CONTENT);
    let missing = admin
        .patch("/api/admin/settings/integrations/crypto")
        .send()
        .await;
    assert_eq!(
        missing.json()["detail"],
        "Required parameter 'enabled' is not present."
    );
    let eb = admin
        .put("/api/admin/settings/enablebanking")
        .json(&json!({"applicationId": "app-1", "redirectUri": "https://cb.example"}))
        .send()
        .await;
    assert_eq!(eb.status, StatusCode::NO_CONTENT);
    let ai = admin
        .put("/api/admin/settings/ai")
        .json(&json!({"provider": "anthropic", "apiKey": "sk-ant-secret", "maxConcurrency": 3}))
        .send()
        .await;
    assert_eq!(ai.status, StatusCode::NO_CONTENT);
    let stored: String =
        sqlx::query_scalar("SELECT value FROM app_setting WHERE setting_key = 'ai.api-key'")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert!(!stored.contains("sk-ant"), "the key is encrypted at rest");
    let config = picsou::admin::settings::ai_config(&app.state)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(config.api_key.as_deref(), Some("sk-ant-secret"));
    assert_eq!(config.effective_model(), "claude-haiku-4-5");
    let settings = admin.get("/api/admin/settings").send().await.json();
    assert_eq!(
        settings["security"],
        json!({"allowedOrigins": ["https://a.example"], "secureCookies": true})
    );
    assert_eq!(settings["integrations"]["crypto"], true);
    assert_eq!(settings["enableBanking"]["applicationId"], "app-1");
    assert_eq!(settings["ai"]["apiKeyPresent"], true);
    assert_eq!(settings["ai"]["maxConcurrency"], 3);
    let off = admin
        .put("/api/admin/settings/ai")
        .json(&json!({"provider": "none"}))
        .send()
        .await;
    assert_eq!(off.status, StatusCode::NO_CONTENT);
    assert_eq!(
        admin.get("/api/admin/settings").send().await.json()["ai"]["apiKeyPresent"],
        false
    );
}

#[tokio::test]
async fn admin_endpoints_need_the_admin_role() {
    let app = TestApp::with_admin().await;
    app.create_member("bob", "bob-password-1").await;
    let bob = app.login("bob", "bob-password-1").await;
    assert_eq!(
        bob.get("/api/admin/settings").send().await.status,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        app.client().get("/api/admin/settings").send().await.status,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn reloads_cors_from_the_environment() {
    let app = TestApp::builder()
        .env(
            "ALLOWED_ORIGINS",
            " https://one.example, ,https://two.example",
        )
        .with_admin()
        .spawn()
        .await;
    let admin = app.login_admin().await;
    let reloaded = admin
        .post("/api/admin/settings/cors/reload-from-env")
        .send()
        .await;
    assert_eq!(
        reloaded.json(),
        json!({"allowedOrigins": ["https://one.example", "https://two.example"]})
    );
}

#[tokio::test]
async fn the_ai_test_calls_the_provider_without_saving() {
    let provider = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/v1/chat/completions"))
        .and(header("authorization", "Bearer sk-live"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({"choices": [{"message": {"content": " OK \n"}}]})),
        )
        .mount(&provider)
        .await;
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let ok = admin
        .post("/api/admin/settings/ai/test")
        .json(&json!({"provider": "openai", "baseUrl": provider.uri(), "apiKey": "sk-live"}))
        .send()
        .await
        .json();
    assert_eq!(
        ok,
        json!({"ok": true, "message": "Connected. Model replied: OK"})
    );
    let no_key = admin
        .post("/api/admin/settings/ai/test")
        .json(&json!({"provider": "openai", "baseUrl": provider.uri()}))
        .send()
        .await
        .json();
    assert_eq!(
        no_key,
        json!({"ok": false, "message": "An API key is required for OPENAI."})
    );
    let rejected = admin
        .post("/api/admin/settings/ai/test")
        .json(&json!({"provider": "openrouter", "baseUrl": provider.uri(), "apiKey": "sk-wrong"}))
        .send()
        .await
        .json();
    assert_eq!(rejected["ok"], false);
    let none = admin
        .post("/api/admin/settings/ai/test")
        .json(&json!({"provider": "none"}))
        .send()
        .await
        .json();
    assert_eq!(
        none,
        json!({"ok": false, "message": "No provider selected."})
    );
    let stored: i64 =
        sqlx::query_scalar("SELECT count(*) FROM app_setting WHERE setting_key LIKE 'ai.%'")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(stored, 0);
}

#[tokio::test]
async fn pages_the_ai_call_log() {
    let app = TestApp::with_admin().await;
    for i in 0..3 {
        sqlx::query("INSERT INTO ai_call_log (created_at, provider, status, total_tokens, applied) VALUES ($1, 'OPENAI', 'OK', $2, false)")
            .bind(picsou::testing::epoch() + chrono::Duration::minutes(i))
            .bind(10 * (i as i32 + 1))
            .execute(&app.state.db)
            .await
            .unwrap();
    }
    let admin = app.login_admin().await;
    let page = admin
        .get("/api/admin/ai-calls?limit=2&offset=3")
        .send()
        .await
        .json();
    assert_eq!(page["total"], 3);
    assert_eq!(page["totalTokens"], 60);
    assert_eq!(
        page["items"].as_array().unwrap().len(),
        1,
        "offset 3 with limit 2 is page 1"
    );
    assert_eq!(page["items"][0]["totalTokens"], 10);
    let first = admin.get("/api/admin/ai-calls?limit=0").send().await.json();
    assert_eq!(
        first["items"][0]["totalTokens"], 30,
        "newest first, limit clamped to 1"
    );
    assert_eq!(first["items"].as_array().unwrap().len(), 1);
    assert!(first["items"][0].get("model").is_none());
}

#[tokio::test]
async fn shows_and_clears_the_enable_banking_call_log() {
    let app = TestApp::with_admin().await;
    for status in [200, 500] {
        app.state.admin.eb_calls.log(picsou::admin::CallEntry {
            timestamp: picsou::testing::epoch(),
            method: "GET".into(),
            url: "https://api.enablebanking.com/aspsps".into(),
            request_body: None,
            response_status: status,
            response_body: Some("{}".into()),
        });
    }
    let admin = app.login_admin().await;
    let log = admin
        .get("/api/admin/enablebanking/call-log")
        .send()
        .await
        .json();
    assert_eq!(log[0]["responseStatus"], 500, "newest first");
    assert_eq!(log[1]["timestamp"], "2026-01-15T10:00:00Z");
    assert_eq!(
        admin
            .delete("/api/admin/enablebanking/call-log")
            .send()
            .await
            .status,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        admin
            .get("/api/admin/enablebanking/call-log")
            .send()
            .await
            .json(),
        json!([])
    );
}

#[tokio::test]
async fn instance_status_probes_only_the_sidecars_in_use() {
    let sidecar = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/health"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({"base": "sidecar-python-old"})),
        )
        .mount(&sidecar)
        .await;
    let app = TestApp::builder()
        .env("BOURSO_AUTH_URL", sidecar.uri())
        .env("TR_AUTH_URL", "http://127.0.0.1:9")
        .with_admin()
        .spawn()
        .await;
    let member: i64 = sqlx::query_scalar("SELECT member_id FROM app_user LIMIT 1")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    for bank in ["BOURSO", "TRADE_REPUBLIC"] {
        sqlx::query("INSERT INTO sidecar_session (bank, member_id, session_state, is_active) VALUES ($1, $2, '{}', true)")
            .bind(bank)
            .bind(member)
            .execute(&app.state.db)
            .await
            .unwrap();
    }
    let admin = app.login_admin().await;
    let status = admin.get("/api/admin/instance-status").send().await.json();
    assert!(
        status.get("update").is_some_and(Value::is_null),
        "update is always present"
    );
    assert_eq!(
        status["issues"],
        json!([
            {"code": "SIDECAR_OUTDATED", "sidecar": "bourso-auth", "expectedBase": "sidecar-python"},
            {"code": "SIDECAR_UNREACHABLE", "sidecar": "tr-auth", "expectedBase": "sidecar-chromium"},
        ])
    );
}

#[tokio::test]
async fn resets_another_members_mfa_but_never_their_own() {
    let app = TestApp::with_admin().await;
    let (bob, bob_member) = app.create_member("bob", "bob-password-1").await;
    sqlx::query("INSERT INTO user_mfa (user_id, enabled, totp_secret_enc) VALUES ($1, true, 'x')")
        .bind(bob)
        .execute(&app.state.db)
        .await
        .unwrap();
    let admin = app.login_admin().await;
    let own: i64 = sqlx::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    let mine = admin
        .delete(&format!("/api/admin/members/{own}/mfa"))
        .send()
        .await;
    assert_eq!(mine.status, StatusCode::FORBIDDEN);
    assert!(mine.body.is_empty());
    let reset = admin
        .delete(&format!("/api/admin/members/{bob_member}/mfa"))
        .send()
        .await;
    assert_eq!(reset.status, StatusCode::NO_CONTENT);
    let left: i64 = sqlx::query_scalar("SELECT count(*) FROM user_mfa")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(left, 0);
    let unknown = admin.delete("/api/admin/members/424242/mfa").send().await;
    assert_eq!(unknown.json()["detail"], "Member not found");
    let self_unlink = admin
        .delete(&format!("/api/admin/members/{own}/identities"))
        .send()
        .await;
    assert_eq!(self_unlink.json()["code"], "SELF_UNLINK_FORBIDDEN");
}

#[tokio::test]
async fn telemetry_needs_a_dsn_and_consent() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let refused = admin
        .put("/api/admin/settings/telemetry")
        .json(&json!({"enabled": true}))
        .send()
        .await;
    assert_eq!(refused.status, StatusCode::CONFLICT);
    assert_eq!(
        refused.json()["detail"],
        "Telemetry is not configured on this instance"
    );
    let config = admin.get("/api/telemetry/config").send().await.json();
    assert_eq!(config["dsn"], Value::Null);
    assert_eq!(config["consent"], "UNSET");
    assert_eq!(config["available"], false);
}

#[tokio::test]
async fn the_tunnel_forwards_scrubbed_events_to_the_configured_collector() {
    let collector = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/api/42/envelope/"))
        .and(header(
            "x-sentry-auth",
            "Sentry sentry_version=7, sentry_key=pub, sentry_client=picsou-tunnel",
        ))
        .respond_with(ResponseTemplate::new(200))
        .mount(&collector)
        .await;
    let dsn = format!("{}/42", collector.uri().replace("http://", "http://pub@"));
    let app = TestApp::builder()
        .env("APP_TELEMETRY_DSN", &dsn)
        .with_admin()
        .spawn()
        .await;
    let admin = app.login_admin().await;
    let disabled = admin
        .post("/api/telemetry/tunnel")
        .body("text/plain", "{}\n")
        .send()
        .await;
    assert_eq!(disabled.status, StatusCode::NO_CONTENT);
    assert!(
        collector.received_requests().await.unwrap().is_empty(),
        "nothing leaves without consent"
    );
    assert_eq!(
        admin
            .put("/api/admin/settings/telemetry")
            .json(&json!({"enabled": true}))
            .send()
            .await
            .status,
        StatusCode::NO_CONTENT
    );
    let config = admin.get("/api/telemetry/config").send().await.json();
    assert_eq!(config["dsn"], dsn);
    assert_eq!(config["enabled"], true);
    let envelope = "{\"dsn\":\"https://x@evil.example/1\"}\n{\"type\":\"event\"}\n{\"level\":\"error\",\"user\":{\"email\":\"a@b.c\"},\"message\":\"jane@x.example paid 12,50 €\"}\n";
    let sent = admin
        .post("/api/telemetry/tunnel")
        .body("text/plain", envelope)
        .send()
        .await;
    assert_eq!(sent.status, StatusCode::NO_CONTENT);
    let mut forwarded = Vec::new();
    for _ in 0..50 {
        forwarded = collector.received_requests().await.unwrap();
        if !forwarded.is_empty() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    assert_eq!(forwarded.len(), 1);
    let body = String::from_utf8(forwarded[0].body.clone()).unwrap();
    assert!(
        body.starts_with(&format!("{{\"dsn\":\"{dsn}\"}}\n")),
        "{body}"
    );
    assert!(body.contains("{\"level\":\"error\"}"));
    assert!(!body.contains("jane") && !body.contains("a@b.c"));
    let too_large = admin
        .post("/api/telemetry/tunnel")
        .body("text/plain", "x".repeat(200 * 1024 + 1))
        .send()
        .await;
    assert_eq!(too_large.status, StatusCode::PAYLOAD_TOO_LARGE);
}

#[tokio::test]
async fn admin_recovery_deactivates_every_admin_with_a_one_hour_link() {
    let app = TestApp::builder()
        .env("ADMIN_RECOVERY_ENABLED", "true")
        .with_admin()
        .spawn()
        .await;
    picsou::admin::recovery::run(&app.state).await.unwrap();
    let admin = picsou::auth::users::find_by_username(&app.state.db, "admin")
        .await
        .unwrap()
        .unwrap();
    assert!(!admin.activated);
    assert_eq!(admin.token_version, 1);
    assert_eq!(
        admin.activation_token_expires,
        Some(picsou::testing::epoch() + chrono::Duration::hours(1))
    );
    let token = admin.activation_token.unwrap();
    assert_eq!(token.len(), 43);
    let login = app
        .client()
        .post("/api/auth/login")
        .json(&json!({"username": "admin", "password": ADMIN_PASSWORD}))
        .send()
        .await;
    assert_eq!(
        login.status,
        StatusCode::FORBIDDEN,
        "the login points to the recovery link"
    );
    let audit: i64 = sqlx::query_scalar("SELECT count(*) FROM setup_audit WHERE event = 'admin.recovery.token-generated' AND ip IS NULL")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(audit, 1);
}
