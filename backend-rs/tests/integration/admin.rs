//! Integration tests of the admin module.

use std::time::Duration;

use picsou::testing::{ADMIN_PASSWORD, TestApp};
use reqwest::StatusCode;
use serde_json::{Value, json};
use wiremock::matchers::{header, method, path, query_param};
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
        picsou::db::query_scalar("SELECT value FROM app_setting WHERE setting_key = 'ai.api-key'")
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
        picsou::db::query_scalar("SELECT count(*) FROM app_setting WHERE setting_key LIKE 'ai.%'")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(stored, 0);
}

#[tokio::test]
async fn ai_models_endpoint_needs_the_admin_role() {
    let app = TestApp::with_admin().await;
    app.create_member("bob", "bob-password-1").await;
    let bob = app.login("bob", "bob-password-1").await;
    assert_eq!(
        bob.post("/api/admin/settings/ai/models")
            .json(&json!({"provider": "none"}))
            .send()
            .await
            .status,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        app.client()
            .post("/api/admin/settings/ai/models")
            .json(&json!({"provider": "none"}))
            .send()
            .await
            .status,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn ai_models_lists_sorted_deduplicated_ids_for_openai_anthropic_and_ollama() {
    let openai = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v1/models"))
        .and(header("authorization", "Bearer sk-mistral-test"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "data": [
                {"id": "mistral-small-latest"},
                {"id": "Codestral-latest"},
                {"id": "mistral-small-latest"},
                {"id": "ministral-8b-latest"}
            ]
        })))
        .expect(1)
        .mount(&openai)
        .await;

    let anthropic = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v1/models"))
        .and(query_param("limit", "1000"))
        .and(header("x-api-key", "sk-ant-test"))
        .and(header("anthropic-version", "2023-06-01"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "data": [
                {"id": "claude-sonnet-4-5"},
                {"id": "Claude-Haiku-4-5"},
                {"id": "claude-sonnet-4-5"}
            ]
        })))
        .expect(1)
        .mount(&anthropic)
        .await;

    let ollama = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/api/tags"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "models": [
                {"name": "qwen3:0.6b"},
                {"name": "Llama3.2:latest"},
                {"name": "qwen3:0.6b"}
            ]
        })))
        .expect(1)
        .mount(&ollama)
        .await;

    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;

    let mistral_res = admin
        .post("/api/admin/settings/ai/models")
        .json(&json!({
            "provider": "mistral",
            "baseUrl": openai.uri(),
            "apiKey": "sk-mistral-test"
        }))
        .send()
        .await;
    assert_eq!(mistral_res.status, StatusCode::OK);
    assert_eq!(
        mistral_res.json(),
        json!({
            "ok": true,
            "models": ["Codestral-latest", "ministral-8b-latest", "mistral-small-latest"]
        })
    );

    let anthropic_res = admin
        .post("/api/admin/settings/ai/models")
        .json(&json!({
            "provider": "anthropic",
            "baseUrl": anthropic.uri(),
            "apiKey": "sk-ant-test"
        }))
        .send()
        .await;
    assert_eq!(anthropic_res.status, StatusCode::OK);
    assert_eq!(
        anthropic_res.json(),
        json!({
            "ok": true,
            "models": ["Claude-Haiku-4-5", "claude-sonnet-4-5"]
        })
    );

    let ollama_res = admin
        .post("/api/admin/settings/ai/models")
        .json(&json!({
            "provider": "ollama",
            "baseUrl": ollama.uri()
        }))
        .send()
        .await;
    assert_eq!(ollama_res.status, StatusCode::OK);
    assert_eq!(
        ollama_res.json(),
        json!({
            "ok": true,
            "models": ["Llama3.2:latest", "qwen3:0.6b"]
        })
    );
    let ollama_req = &ollama.received_requests().await.unwrap()[0];
    assert!(ollama_req.headers.get("authorization").is_none());
}

#[tokio::test]
async fn ai_models_handles_upstream_errors_redirects_and_unknown_providers() {
    let upstream = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v1/models"))
        .and(header("authorization", "Bearer sk-err-key"))
        .respond_with(
            ResponseTemplate::new(500).set_body_string("secret upstream stacktrace sk-err-key"),
        )
        .mount(&upstream)
        .await;

    let redirect_target = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v1/models"))
        .and(header("authorization", "Bearer sk-redir-key"))
        .respond_with(
            ResponseTemplate::new(302)
                .insert_header("location", format!("{}/leak", redirect_target.uri())),
        )
        .mount(&upstream)
        .await;

    Mock::given(method("GET"))
        .and(path("/v1/models"))
        .and(header("authorization", "Bearer sk-bad-body"))
        .respond_with(ResponseTemplate::new(200).set_body_string("<html>not json</html>"))
        .mount(&upstream)
        .await;

    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;

    let err = admin
        .post("/api/admin/settings/ai/models")
        .json(&json!({
            "provider": "openai",
            "baseUrl": upstream.uri(),
            "apiKey": "sk-err-key"
        }))
        .send()
        .await;
    assert_eq!(err.status, StatusCode::OK);
    let err_body = err.json();
    assert_eq!(err_body["ok"], false);
    assert_eq!(err_body["models"], json!([]));
    let msg = err_body["message"].as_str().unwrap();
    assert_eq!(msg, "The provider answered HTTP 500");
    assert!(
        !msg.contains("stacktrace")
            && !msg.contains("sk-err-key")
            && !msg.contains(&upstream.uri())
    );

    let bad_body = admin
        .post("/api/admin/settings/ai/models")
        .json(&json!({
            "provider": "openai",
            "baseUrl": upstream.uri(),
            "apiKey": "sk-bad-body"
        }))
        .send()
        .await;
    assert_eq!(bad_body.status, StatusCode::OK);
    assert_eq!(
        bad_body.json(),
        json!({
            "ok": false,
            "message": "The provider returned an unreadable answer",
            "models": []
        })
    );

    let redir = admin
        .post("/api/admin/settings/ai/models")
        .json(&json!({
            "provider": "openai",
            "baseUrl": upstream.uri(),
            "apiKey": "sk-redir-key"
        }))
        .send()
        .await;
    assert_eq!(redir.status, StatusCode::OK);
    assert_eq!(redir.json()["ok"], false);
    assert_eq!(redir.json()["models"], json!([]));
    assert!(
        redirect_target
            .received_requests()
            .await
            .unwrap()
            .is_empty(),
        "redirects must not be followed"
    );

    for provider in ["none", "does-not-exist"] {
        let res = admin
            .post("/api/admin/settings/ai/models")
            .json(&json!({"provider": provider}))
            .send()
            .await;
        assert_eq!(res.status, StatusCode::OK);
        assert_eq!(
            res.json(),
            json!({"ok": false, "message": "No provider selected.", "models": []})
        );
    }
}

#[tokio::test]
async fn stored_ai_key_is_only_reused_when_requested_provider_matches_saved_provider() {
    let mock = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/v1/chat/completions"))
        .and(header("authorization", "Bearer sk-saved-openai"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({"choices": [{"message": {"content": "OK"}}]})),
        )
        .expect(1)
        .mount(&mock)
        .await;
    Mock::given(method("GET"))
        .and(path("/v1/models"))
        .and(header("authorization", "Bearer sk-saved-openai"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "data": [{"id": "gpt-4o-mini"}]
        })))
        .expect(1)
        .mount(&mock)
        .await;

    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let saved = admin
        .put("/api/admin/settings/ai")
        .json(&json!({
            "provider": "openai",
            "baseUrl": mock.uri(),
            "apiKey": "sk-saved-openai"
        }))
        .send()
        .await;
    assert_eq!(saved.status, StatusCode::NO_CONTENT);

    // Different provider with blank key: stored OpenAI key must NOT be sent.
    let diff_test = admin
        .post("/api/admin/settings/ai/test")
        .json(&json!({"provider": "mistral", "baseUrl": mock.uri()}))
        .send()
        .await;
    assert_eq!(diff_test.status, StatusCode::OK);
    assert_eq!(
        diff_test.json(),
        json!({"ok": false, "message": "An API key is required for MISTRAL."})
    );

    let diff_models = admin
        .post("/api/admin/settings/ai/models")
        .json(&json!({"provider": "mistral", "baseUrl": mock.uri()}))
        .send()
        .await;
    assert_eq!(diff_models.status, StatusCode::OK);
    assert_eq!(
        diff_models.json(),
        json!({"ok": false, "message": "An API key is required for MISTRAL.", "models": []})
    );
    assert!(
        mock.received_requests().await.unwrap().is_empty(),
        "no upstream call may be made when key is missing for another provider"
    );

    // Same provider with blank key: stored OpenAI key IS reused.
    let same_test = admin
        .post("/api/admin/settings/ai/test")
        .json(&json!({"provider": "openai", "baseUrl": mock.uri()}))
        .send()
        .await
        .json();
    assert_eq!(
        same_test,
        json!({"ok": true, "message": "Connected. Model replied: OK"})
    );

    let same_models = admin
        .post("/api/admin/settings/ai/models")
        .json(&json!({"provider": "openai", "baseUrl": mock.uri()}))
        .send()
        .await
        .json();
    assert_eq!(same_models, json!({"ok": true, "models": ["gpt-4o-mini"]}));

    // Optional-key provider (custom) must NOT receive another saved provider's key.
    let custom_mock = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v1/models"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "data": [{"id": "local-only"}]
        })))
        .expect(1)
        .mount(&custom_mock)
        .await;
    let custom_models = admin
        .post("/api/admin/settings/ai/models")
        .json(&json!({"provider": "custom", "baseUrl": custom_mock.uri()}))
        .send()
        .await
        .json();
    assert_eq!(custom_models, json!({"ok": true, "models": ["local-only"]}));
    let custom_req = &custom_mock.received_requests().await.unwrap()[0];
    assert!(
        custom_req.headers.get("authorization").is_none(),
        "stored OpenAI key must not be sent to custom provider"
    );
}

#[tokio::test]
async fn custom_provider_validates_save_and_omits_authorization_with_blank_key() {
    let mock = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/v1/chat/completions"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({"choices": [{"message": {"content": "OK"}}]})),
        )
        .expect(1)
        .mount(&mock)
        .await;
    Mock::given(method("GET"))
        .and(path("/v1/models"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "data": [{"id": "local-llm"}]
        })))
        .expect(1)
        .mount(&mock)
        .await;

    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;

    let missing_both = admin
        .put("/api/admin/settings/ai")
        .json(&json!({"provider": "custom"}))
        .send()
        .await;
    assert_eq!(missing_both.status, StatusCode::UNPROCESSABLE_ENTITY);
    let errs = &missing_both.json()["errors"];
    assert_eq!(errs["baseUrl"], "must not be blank");
    assert_eq!(errs["model"], "must not be blank");

    let saved = admin
        .put("/api/admin/settings/ai")
        .json(&json!({
            "provider": "custom",
            "baseUrl": mock.uri(),
            "model": "local-llm"
        }))
        .send()
        .await;
    assert_eq!(saved.status, StatusCode::NO_CONTENT);

    let test_res = admin
        .post("/api/admin/settings/ai/test")
        .json(&json!({
            "provider": "custom",
            "baseUrl": mock.uri(),
            "model": "local-llm",
            "apiKey": ""
        }))
        .send()
        .await
        .json();
    assert_eq!(
        test_res,
        json!({"ok": true, "message": "Connected. Model replied: OK"})
    );

    let models_res = admin
        .post("/api/admin/settings/ai/models")
        .json(&json!({
            "provider": "custom",
            "baseUrl": mock.uri(),
            "apiKey": ""
        }))
        .send()
        .await
        .json();
    assert_eq!(models_res, json!({"ok": true, "models": ["local-llm"]}));

    let received = mock.received_requests().await.unwrap();
    assert_eq!(received.len(), 2);
    for req in received {
        assert!(
            req.headers.get("authorization").is_none(),
            "custom with blank key must not send Authorization header"
        );
    }
}

#[tokio::test]
async fn gemini_calls_chat_and_models_without_v1_prefix() {
    let mock = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/chat/completions"))
        .and(header("authorization", "Bearer sk-gemini-test"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({"choices": [{"message": {"content": "OK"}}]})),
        )
        .expect(1)
        .mount(&mock)
        .await;
    Mock::given(method("GET"))
        .and(path("/models"))
        .and(header("authorization", "Bearer sk-gemini-test"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "data": [{"id": "gemini-flash-latest"}]
        })))
        .expect(1)
        .mount(&mock)
        .await;

    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;

    let test_res = admin
        .post("/api/admin/settings/ai/test")
        .json(&json!({
            "provider": "gemini",
            "baseUrl": mock.uri(),
            "apiKey": "sk-gemini-test"
        }))
        .send()
        .await
        .json();
    assert_eq!(
        test_res,
        json!({"ok": true, "message": "Connected. Model replied: OK"})
    );

    let models_res = admin
        .post("/api/admin/settings/ai/models")
        .json(&json!({
            "provider": "gemini",
            "baseUrl": mock.uri(),
            "apiKey": "sk-gemini-test"
        }))
        .send()
        .await
        .json();
    assert_eq!(
        models_res,
        json!({"ok": true, "models": ["gemini-flash-latest"]})
    );
}

#[tokio::test]
async fn pages_the_ai_call_log() {
    let app = TestApp::with_admin().await;
    for i in 0..3 {
        picsou::db::query("INSERT INTO ai_call_log (created_at, provider, status, total_tokens, applied) VALUES ($1, 'OPENAI', 'OK', $2, false)")
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
    let member: i64 = picsou::db::query_scalar("SELECT member_id FROM app_user LIMIT 1")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    for bank in ["BOURSO", "TRADE_REPUBLIC"] {
        picsou::db::query("INSERT INTO sidecar_session (bank, member_id, session_state, is_active) VALUES ($1, $2, '{}', true)")
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
    picsou::db::query(
        "INSERT INTO user_mfa (user_id, enabled, totp_secret_enc) VALUES ($1, true, 'x')",
    )
    .bind(bob)
    .execute(&app.state.db)
    .await
    .unwrap();
    let admin = app.login_admin().await;
    let own: i64 =
        picsou::db::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
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
    let left: i64 = picsou::db::query_scalar("SELECT count(*) FROM user_mfa")
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
async fn admin_mfa_reset_rolls_back_when_revoking_sessions_fails() {
    let app = TestApp::with_admin().await;
    let (_bob_user, bob_member) = app.create_member("bob", "bob-password-1").await;
    let bob = app.login("bob", "bob-password-1").await;

    let init = bob
        .post("/api/auth/mfa/enroll/init")
        .json(&json!({"currentPassword": "bob-password-1"}))
        .send()
        .await;
    assert_eq!(init.status, StatusCode::OK);
    let secret = init.json()["secret"].as_str().unwrap().to_owned();
    let code = picsou::setup_auth::mfa::current_code(&app.state, &secret).unwrap();
    assert_eq!(
        bob.post("/api/auth/mfa/enroll/verify")
            .json(&json!({"code": code}))
            .send()
            .await
            .status,
        StatusCode::OK
    );

    app.clock.advance(Duration::from_secs(30));
    let trusted = app.client();
    assert_eq!(
        trusted
            .post("/api/auth/login")
            .json(&json!({"username": "bob", "password": "bob-password-1", "rememberMe": true}))
            .send()
            .await
            .status,
        StatusCode::OK
    );
    let verify_code = picsou::setup_auth::mfa::current_code(&app.state, &secret).unwrap();
    assert_eq!(
        trusted
            .post("/api/auth/mfa/verify")
            .json(&json!({"code": verify_code, "trustDevice": true}))
            .send()
            .await
            .status,
        StatusCode::OK
    );
    let persistent_cookie = trusted.cookie("persistent_token").unwrap();

    let admin = app.login_admin().await;
    install_abort_trigger(
        &app.state.db,
        "UPDATE",
        "persistent_session",
        "abort_ps_revoke_on_admin_mfa_reset",
    )
    .await;

    let failed = admin
        .delete(&format!("/api/admin/members/{bob_member}/mfa"))
        .send()
        .await;
    assert_eq!(failed.status, StatusCode::INTERNAL_SERVER_ERROR);

    drop_trigger(
        &app.state.db,
        "persistent_session",
        "abort_ps_revoke_on_admin_mfa_reset",
    )
    .await;

    let status = bob.get("/api/auth/mfa/status").send().await.json();
    assert_eq!(
        status["enabled"], true,
        "2FA must not be removed when revoking persistent sessions fails"
    );
    assert_eq!(status["remainingRecoveryCodes"], 10);

    // Once the fault is cleared, admin MFA reset disables 2FA and revokes the trusted device.
    let reset = admin
        .delete(&format!("/api/admin/members/{bob_member}/mfa"))
        .send()
        .await;
    assert_eq!(reset.status, StatusCode::NO_CONTENT);
    assert_eq!(
        bob.get("/api/auth/mfa/status").send().await.json()["enabled"],
        false
    );
    let stale_device = app.client();
    stale_device.set_cookie("persistent_token", &persistent_cookie);
    assert_eq!(
        stale_device.get("/api/auth/sessions").send().await.status,
        StatusCode::UNAUTHORIZED,
        "trusted device must be revoked after admin MFA reset succeeds"
    );
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
    let audit: i64 = picsou::db::query_scalar("SELECT count(*) FROM setup_audit WHERE event = 'admin.recovery.token-generated' AND ip IS NULL")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(audit, 1);
}
