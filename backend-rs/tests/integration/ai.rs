//! Integration tests of the ai module (track T8) over HTTP with wiremock providers.

use std::sync::Arc;
use std::time::Duration;

use chrono::NaiveDate;
use picsou::ai::DynamicCategorizer;
use picsou::budget::ai_job::CategorizationInput;
use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::model::{Account, AccountType, NewTransaction};
use picsou::ledger::transactions;
use picsou::testing::{Client, TestApp, epoch};
use reqwest::StatusCode;
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{body_string_contains, header, header_exists, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

fn dec(v: &str) -> Decimal {
    v.parse().unwrap()
}

fn day(v: &str) -> NaiveDate {
    v.parse().unwrap()
}

struct AiCtx {
    app: TestApp,
    member: i64,
    admin: Client,
    user: Client,
}

async fn setup() -> AiCtx {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let (_, member) = app.create_member("alice", "password-1234").await;
    let user = app.login("alice", "password-1234").await;
    AiCtx {
        app,
        member,
        admin,
        user,
    }
}

impl AiCtx {
    async fn account(&self, name: &str) -> Account {
        let mut draft = NewAccount::new(self.member, name, AccountType::Checking);
        draft.current_balance = dec("1000");
        accounts::insert(&self.app.state.db, &draft, epoch())
            .await
            .unwrap()
    }

    async fn tx(
        &self,
        account: &Account,
        date: &str,
        amount: &str,
        label: &str,
        category_id: Option<i64>,
    ) -> i64 {
        let mut row = NewTransaction::new(account.id, day(date), label, dec(amount));
        row.counterparty = Some(label.to_owned());
        row.merchant_label = Some(label.to_owned());
        row.category_id = category_id;
        transactions::insert(&self.app.state.db, &row, epoch())
            .await
            .unwrap()
            .id
    }

    async fn category_id_by_slug(&self, slug: &str) -> i64 {
        // Trigger default category seeding on first read.
        let r = self.user.get("/api/categories").send().await;
        assert_eq!(r.status, StatusCode::OK);
        picsou::db::query_scalar("SELECT id FROM category WHERE member_id = $1 AND slug = $2")
            .bind(self.member)
            .bind(slug)
            .fetch_one(&self.app.state.db)
            .await
            .unwrap()
    }

    async fn enable_budget_ai(&self, mode: &str, threshold: i32) {
        let r = self
            .user
            .put("/api/budget/settings")
            .json(&json!({
                "cycleStartDay": 1,
                "aiCategorizationEnabled": true,
                "aiMode": mode,
                "aiConfidenceThreshold": threshold,
            }))
            .send()
            .await;
        assert_eq!(r.status, StatusCode::OK, "{}", r.text());
    }

    async fn configure_ai(&self, payload: Value) {
        let r = self
            .admin
            .put("/api/admin/settings/ai")
            .json(&payload)
            .send()
            .await;
        assert_eq!(r.status, StatusCode::NO_CONTENT, "{}", r.text());
    }

    async fn run_ai_job(&self) -> Value {
        let start = self
            .user
            .post("/api/transactions/categorize-ai")
            .send()
            .await;
        assert_eq!(start.status, StatusCode::ACCEPTED, "{}", start.text());
        for _ in 0..200 {
            let status = self
                .user
                .get("/api/transactions/categorize-ai/status")
                .send()
                .await
                .json();
            if status["done"] == true {
                return status;
            }
            tokio::time::sleep(Duration::from_millis(15)).await;
        }
        panic!("AI categorization job did not finish in time");
    }

    async fn tx_state(&self, tx_id: i64) -> (Option<i64>, Option<i64>, Option<i32>) {
        picsou::db::query_as(
            "SELECT category_id, ai_suggested_category_id, ai_confidence \
             FROM \"transaction\" WHERE id = $1",
        )
        .bind(tx_id)
        .fetch_one(&self.app.state.db)
        .await
        .unwrap()
    }

    async fn ai_calls(&self) -> Value {
        let r = self.admin.get("/api/admin/ai-calls?limit=50").send().await;
        assert_eq!(r.status, StatusCode::OK, "{}", r.text());
        r.json()
    }
}

#[tokio::test]
async fn openai_categorizes_with_few_shot_examples_clamps_and_logs_tokens() {
    let c = setup().await;
    let mock = MockServer::start().await;

    Mock::given(method("POST"))
        .and(path("/v1/chat/completions"))
        .and(header("authorization", "Bearer sk-openai-test"))
        .and(body_string_contains("Biocoop"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": "```json\n{\"categorySlug\": \"courses\", \"confidence\": 0.95}\n```"
                }
            }],
            "usage": {"prompt_tokens": 120, "completion_tokens": 30, "total_tokens": 150}
        })))
        .expect(1)
        .mount(&mock)
        .await;

    Mock::given(method("POST"))
        .and(path("/v1/chat/completions"))
        .and(header("authorization", "Bearer sk-openai-test"))
        .and(body_string_contains("Mystery Bistro"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": "{\"categorySlug\": \"restaurants\", \"confidence\": 0.55}"
                }
            }],
            "usage": {"prompt_tokens": 110, "completion_tokens": 25, "total_tokens": 135}
        })))
        .expect(1)
        .mount(&mock)
        .await;

    c.configure_ai(json!({
        "provider": "openai",
        "model": "gpt-4o-mini",
        "baseUrl": mock.uri(),
        "apiKey": "sk-openai-test",
        "maxConcurrency": 2,
    }))
    .await;
    c.enable_budget_ai("AUTO_HIGH_CONFIDENCE", 80).await;

    let checking = c.account("Courant").await;
    let courses_id = c.category_id_by_slug("courses").await;
    let restaurants_id = c.category_id_by_slug("restaurants").await;

    // Hand-categorized past transaction becomes a few-shot example in the prompt.
    let _past = c
        .tx(
            &checking,
            "2026-01-01",
            "-28.50",
            "Monoprix",
            Some(courses_id),
        )
        .await;
    let tx_high = c
        .tx(&checking, "2026-01-05", "-42.10", "Biocoop", None)
        .await;
    let tx_low = c
        .tx(&checking, "2026-01-06", "-19.00", "Mystery Bistro", None)
        .await;

    let status = c.run_ai_job().await;
    assert_eq!(status["running"], false);
    assert_eq!(status["done"], true);
    assert_eq!(status["total"], 2);
    assert_eq!(status["processed"], 2);
    assert_eq!(status["applied"], 1);
    assert_eq!(status["suggested"], 1);
    assert!(status.get("error").is_none());

    // High confidence auto-applied; low confidence stored as suggestion.
    assert_eq!(c.tx_state(tx_high).await, (Some(courses_id), None, None));
    assert_eq!(
        c.tx_state(tx_low).await,
        (None, Some(restaurants_id), Some(55))
    );

    // Verify wire request shape (system + user messages, few-shot example, JSON schema).
    let requests = mock.received_requests().await.unwrap();
    assert_eq!(requests.len(), 2);
    let body: Value = serde_json::from_slice(&requests[0].body).unwrap();
    assert_eq!(body["model"], "gpt-4o-mini");
    assert_eq!(body["messages"][0]["role"], "system");
    assert!(
        body["messages"][0]["content"]
            .as_str()
            .unwrap()
            .contains("Choose exactly ONE category from the provided list")
    );
    let user_msg = body["messages"][1]["content"].as_str().unwrap();
    assert!(user_msg.contains("\"Monoprix\" => courses"));
    assert!(user_msg.contains("Your response should be in JSON format."));
    assert!(user_msg.contains("\"categorySlug\""));

    // Verify ai_call_log persistence & aggregated token counts.
    let calls = c.ai_calls().await;
    assert_eq!(calls["total"], 2);
    assert_eq!(calls["totalTokens"], 285);
    let items = calls["items"].as_array().unwrap();
    let biocoop = items
        .iter()
        .find(|i| i["merchantLabel"] == "Biocoop")
        .unwrap();
    assert_eq!(biocoop["provider"], "openai");
    assert_eq!(biocoop["model"], "gpt-4o-mini");
    assert_eq!(biocoop["status"], "OK");
    assert_eq!(biocoop["chosenSlug"], "courses");
    assert_eq!(biocoop["confidence"], 95);
    assert_eq!(biocoop["applied"], true);
    assert_eq!(biocoop["promptTokens"], 120);
    assert_eq!(biocoop["completionTokens"], 30);
    assert_eq!(biocoop["totalTokens"], 150);
    assert!(
        biocoop["prompt"]
            .as_str()
            .unwrap()
            .contains("Here is the JSON Schema instance")
    );
}

#[tokio::test]
async fn openrouter_uses_openai_compatible_endpoint_and_default_model() {
    let c = setup().await;
    let mock = MockServer::start().await;

    Mock::given(method("POST"))
        .and(path("/v1/chat/completions"))
        .and(header("authorization", "Bearer sk-or-test"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "choices": [{"message": {"content": "{\"categorySlug\": \"transport\", \"confidence\": 0.4}"}}],
            "usage": {"prompt_tokens": 60, "completion_tokens": 14, "total_tokens": 74}
        })))
        .expect(1)
        .mount(&mock)
        .await;

    // Blank model uses OpenRouter's default model.
    c.configure_ai(json!({
        "provider": "openrouter",
        "model": "",
        "baseUrl": mock.uri(),
        "apiKey": "sk-or-test",
    }))
    .await;
    c.enable_budget_ai("AUTO_ALL", 80).await;

    let checking = c.account("Courant").await;
    let transport_id = c.category_id_by_slug("transport").await;
    let tx_id = c
        .tx(&checking, "2026-01-05", "-12.00", "Tramway Ticket", None)
        .await;

    let status = c.run_ai_job().await;
    assert_eq!(status["applied"], 1);
    assert_eq!(status["suggested"], 0);
    assert_eq!(c.tx_state(tx_id).await, (Some(transport_id), None, None));

    let req: Value =
        serde_json::from_slice(&mock.received_requests().await.unwrap()[0].body).unwrap();
    assert_eq!(req["model"], "anthropic/claude-3.5-haiku");

    let calls = c.ai_calls().await;
    assert_eq!(calls["items"][0]["provider"], "openrouter");
    assert_eq!(calls["items"][0]["model"], "anthropic/claude-3.5-haiku");
    assert_eq!(calls["items"][0]["totalTokens"], 74);
}

#[tokio::test]
async fn anthropic_sends_max_tokens_no_temperature_and_handles_thinking_blocks() {
    let c = setup().await;
    let mock = MockServer::start().await;

    Mock::given(method("POST"))
        .and(path("/v1/messages"))
        .and(header("x-api-key", "sk-ant-test"))
        .and(header("anthropic-version", "2023-06-01"))
        .and(body_string_contains("Organic Market"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "content": [
                {"type": "thinking", "thinking": "Checking merchant...", "signature": "sig-1"},
                {"type": "redacted_thinking", "data": "opaque"},
                {"type": "text", "text": "{\"categorySlug\": \"courses\", \"confidence\": 0.88}"}
            ],
            "usage": {"input_tokens": 140, "output_tokens": 35}
        })))
        .expect(1)
        .mount(&mock)
        .await;

    Mock::given(method("POST"))
        .and(path("/v1/messages"))
        .and(header("x-api-key", "sk-ant-test"))
        .and(body_string_contains("Thinking Only Shop"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "content": [
                {"type": "thinking", "thinking": "Used up max_tokens", "signature": "sig-2"}
            ],
            "usage": {"input_tokens": 100, "output_tokens": 4096}
        })))
        .expect(1)
        .mount(&mock)
        .await;

    c.configure_ai(json!({
        "provider": "anthropic",
        "model": "claude-haiku-4-5",
        "baseUrl": mock.uri(),
        "apiKey": "sk-ant-test",
    }))
    .await;
    c.enable_budget_ai("AUTO_HIGH_CONFIDENCE", 80).await;

    let checking = c.account("Courant").await;
    let courses_id = c.category_id_by_slug("courses").await;
    let tx_ok = c
        .tx(&checking, "2026-01-05", "-35.00", "Organic Market", None)
        .await;
    let tx_empty = c
        .tx(
            &checking,
            "2026-01-06",
            "-15.00",
            "Thinking Only Shop",
            None,
        )
        .await;

    let status = c.run_ai_job().await;
    assert_eq!(status["processed"], 2);
    assert_eq!(status["applied"], 1);
    assert_eq!(status["suggested"], 0);

    assert_eq!(c.tx_state(tx_ok).await, (Some(courses_id), None, None));
    assert_eq!(c.tx_state(tx_empty).await, (None, None, None));

    // Verify Anthropic wire contract: max_tokens=4096, no temperature, top-level system.
    let req: Value =
        serde_json::from_slice(&mock.received_requests().await.unwrap()[0].body).unwrap();
    assert_eq!(req["model"], "claude-haiku-4-5");
    assert_eq!(req["max_tokens"], 4096);
    assert!(req.get("temperature").is_none());
    assert!(req["system"].is_string());
    assert_eq!(req["messages"].as_array().unwrap().len(), 1);
    assert_eq!(req["messages"][0]["role"], "user");

    let calls = c.ai_calls().await;
    let items = calls["items"].as_array().unwrap();
    let ok_log = items
        .iter()
        .find(|i| i["merchantLabel"] == "Organic Market")
        .unwrap();
    assert_eq!(ok_log["provider"], "anthropic");
    assert_eq!(ok_log["status"], "OK");
    assert_eq!(ok_log["promptTokens"], 140);
    assert_eq!(ok_log["completionTokens"], 35);
    assert_eq!(ok_log["totalTokens"], 175);

    let err_log = items
        .iter()
        .find(|i| i["merchantLabel"] == "Thinking Only Shop")
        .unwrap();
    assert_eq!(err_log["status"], "ERROR");
    assert_eq!(err_log["error"], "The model returned no text answer.");
    assert_eq!(err_log["totalTokens"], 4196);
}

#[tokio::test]
async fn ollama_works_without_api_key_strips_think_tags_and_clamps_confidence() {
    let c = setup().await;
    let mock = MockServer::start().await;

    Mock::given(method("POST"))
        .and(path("/api/chat"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "model": "qwen3:0.6b",
            "message": {
                "role": "assistant",
                "content": "<think>Fresh Market is groceries</think>\n{\"categorySlug\": \" COURSES \", \"confidence\": 1.35}"
            },
            "done": true,
            "prompt_eval_count": 88,
            "eval_count": 22
        })))
        .expect(1)
        .mount(&mock)
        .await;

    // Ollama requires no API key and defaults to qwen3:0.6b.
    c.configure_ai(json!({
        "provider": "ollama",
        "model": "",
        "baseUrl": mock.uri(),
        "apiKey": "",
    }))
    .await;
    c.enable_budget_ai("AUTO_HIGH_CONFIDENCE", 80).await;

    let checking = c.account("Courant").await;
    let courses_id = c.category_id_by_slug("courses").await;
    let tx_id = c
        .tx(&checking, "2026-01-05", "-24.50", "Fresh Market", None)
        .await;

    let status = c.run_ai_job().await;
    assert_eq!(status["applied"], 1);
    assert_eq!(c.tx_state(tx_id).await, (Some(courses_id), None, None));

    let received = &mock.received_requests().await.unwrap()[0];
    assert!(received.headers.get("authorization").is_none());
    let req: Value = serde_json::from_slice(&received.body).unwrap();
    assert_eq!(req["model"], "qwen3:0.6b");
    assert_eq!(req["stream"], false);

    let calls = c.ai_calls().await;
    let row = &calls["items"][0];
    assert_eq!(row["provider"], "ollama");
    assert_eq!(row["model"], "qwen3:0.6b");
    assert_eq!(row["chosenSlug"], "courses");
    assert_eq!(row["confidence"], 100, "confidence 1.35 clamped to 1.0");
    assert_eq!(row["promptTokens"], 88);
    assert_eq!(row["completionTokens"], 22);
    assert_eq!(row["totalTokens"], 110);
}

#[tokio::test]
async fn handles_malformed_json_unknown_slug_and_unrecognized_category() {
    let c = setup().await;
    let mock = MockServer::start().await;

    Mock::given(method("POST"))
        .and(path("/v1/chat/completions"))
        .and(body_string_contains("Shop Malformed"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "choices": [{"message": {"content": "Sorry, I cannot categorize this."}}],
            "usage": {"prompt_tokens": 40, "completion_tokens": 8, "total_tokens": 48}
        })))
        .mount(&mock)
        .await;

    Mock::given(method("POST"))
        .and(path("/v1/chat/completions"))
        .and(body_string_contains("Shop Abstain"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "choices": [{"message": {"content": "{\"categorySlug\": \"unknown\", \"confidence\": 0.2}"}}],
            "usage": {"prompt_tokens": 40, "completion_tokens": 10, "total_tokens": 50}
        })))
        .mount(&mock)
        .await;

    Mock::given(method("POST"))
        .and(path("/v1/chat/completions"))
        .and(body_string_contains("Shop Hallucinated"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "choices": [{"message": {"content": "{\"categorySlug\": \"does-not-exist\", \"confidence\": 0.99}"}}],
            "usage": {"prompt_tokens": 40, "completion_tokens": 12, "total_tokens": 52}
        })))
        .mount(&mock)
        .await;

    c.configure_ai(json!({
        "provider": "openai",
        "baseUrl": mock.uri(),
        "apiKey": "sk-test",
    }))
    .await;
    c.enable_budget_ai("AUTO_ALL", 50).await;

    let checking = c.account("Courant").await;
    let tx_bad = c
        .tx(&checking, "2026-01-05", "-10", "Shop Malformed", None)
        .await;
    let tx_abstain = c
        .tx(&checking, "2026-01-06", "-10", "Shop Abstain", None)
        .await;
    let tx_unknown_cat = c
        .tx(&checking, "2026-01-07", "-10", "Shop Hallucinated", None)
        .await;

    let status = c.run_ai_job().await;
    assert_eq!(status["done"], true);
    assert_eq!(status["processed"], 3);
    assert_eq!(status["applied"], 0);
    assert_eq!(status["suggested"], 0);

    for id in [tx_bad, tx_abstain, tx_unknown_cat] {
        assert_eq!(c.tx_state(id).await, (None, None, None));
    }

    let calls = c.ai_calls().await;
    assert_eq!(calls["total"], 3);
    assert_eq!(calls["totalTokens"], 150);
    let items = calls["items"].as_array().unwrap();

    let bad = items
        .iter()
        .find(|i| i["merchantLabel"] == "Shop Malformed")
        .unwrap();
    assert_eq!(bad["status"], "ERROR");
    assert!(bad["error"].as_str().unwrap().contains("Failed to parse"));
    assert_eq!(bad["response"], "Sorry, I cannot categorize this.");

    let abstain = items
        .iter()
        .find(|i| i["merchantLabel"] == "Shop Abstain")
        .unwrap();
    assert_eq!(abstain["status"], "EMPTY");
    assert!(abstain.get("chosenSlug").is_none());

    let hallucinated = items
        .iter()
        .find(|i| i["merchantLabel"] == "Shop Hallucinated")
        .unwrap();
    assert_eq!(hallucinated["status"], "OK");
    assert_eq!(hallucinated["chosenSlug"], "does-not-exist");
    assert_eq!(hallucinated["applied"], false);
}

#[tokio::test]
async fn handles_http_500_and_timeout_without_breaking_job_or_leaking_body() {
    let c = setup().await;
    let mock = MockServer::start().await;

    Mock::given(method("POST"))
        .and(path("/v1/chat/completions"))
        .and(body_string_contains("Server Error Shop"))
        .respond_with(
            ResponseTemplate::new(500).set_body_string("secret upstream stacktrace sk-leak"),
        )
        .mount(&mock)
        .await;

    Mock::given(method("POST"))
        .and(path("/v1/chat/completions"))
        .and(body_string_contains("Slow Shop"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_delay(Duration::from_millis(400))
                .set_body_json(json!({
                    "choices": [{"message": {"content": "{\"categorySlug\": \"courses\", \"confidence\": 0.9}"}}]
                })),
        )
        .mount(&mock)
        .await;

    // Override categorizer with a short timeout so the timeout path completes quickly.
    c.app.state.budget.ai.set(Arc::new(
        DynamicCategorizer::new(
            c.app.state.db.clone(),
            c.app.state.crypto.clone(),
            c.app.state.http.clone(),
        )
        .with_timeout(Duration::from_millis(100)),
    ));

    c.configure_ai(json!({
        "provider": "openai",
        "baseUrl": mock.uri(),
        "apiKey": "sk-test",
    }))
    .await;
    c.enable_budget_ai("AUTO_ALL", 50).await;

    let checking = c.account("Courant").await;
    let tx_500 = c
        .tx(&checking, "2026-01-05", "-10", "Server Error Shop", None)
        .await;
    let tx_slow = c
        .tx(&checking, "2026-01-06", "-10", "Slow Shop", None)
        .await;

    let status = c.run_ai_job().await;
    assert_eq!(status["done"], true);
    assert_eq!(status["processed"], 2);
    assert_eq!(status["applied"], 0);
    assert!(status.get("error").is_none());
    assert_eq!(c.tx_state(tx_500).await, (None, None, None));
    assert_eq!(c.tx_state(tx_slow).await, (None, None, None));

    let calls = c.ai_calls().await;
    let items = calls["items"].as_array().unwrap();

    let err_500 = items
        .iter()
        .find(|i| i["merchantLabel"] == "Server Error Shop")
        .unwrap();
    assert_eq!(err_500["status"], "ERROR");
    assert_eq!(err_500["error"], "The provider answered HTTP 500");
    assert!(!err_500["error"].as_str().unwrap().contains("sk-leak"));

    let err_timeout = items
        .iter()
        .find(|i| i["merchantLabel"] == "Slow Shop")
        .unwrap();
    assert_eq!(err_timeout["status"], "ERROR");
    assert!(
        err_timeout["error"]
            .as_str()
            .unwrap()
            .contains("Connection failed")
    );
    assert!(err_timeout["latencyMs"].as_i64().unwrap() >= 80);
}

#[tokio::test]
async fn disabled_missing_key_and_dynamic_config_switch_take_effect_without_restart() {
    let c = setup().await;
    let mock = MockServer::start().await;

    Mock::given(method("POST"))
        .and(path("/v1/chat/completions"))
        .and(header_exists("authorization"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "choices": [{"message": {"content": "{\"categorySlug\": \"courses\", \"confidence\": 0.92}"}}],
            "usage": {"prompt_tokens": 50, "completion_tokens": 10, "total_tokens": 60}
        })))
        .expect(1)
        .mount(&mock)
        .await;

    c.enable_budget_ai("AUTO_HIGH_CONFIDENCE", 80).await;
    let checking = c.account("Courant").await;
    let courses_id = c.category_id_by_slug("courses").await;
    let tx_id = c
        .tx(&checking, "2026-01-05", "-30", "Corner Grocer", None)
        .await;

    // 1. No provider configured yet -> DISABLED, no HTTP calls.
    let s1 = c.run_ai_job().await;
    assert_eq!(s1["applied"], 0);
    assert_eq!(c.tx_state(tx_id).await, (None, None, None));
    assert_eq!(c.ai_calls().await["items"][0]["status"], "DISABLED");
    assert_eq!(c.ai_calls().await["items"][0]["provider"], "none");

    // 2. OpenAI configured without required API key -> DISABLED, no HTTP calls.
    c.configure_ai(json!({
        "provider": "openai",
        "baseUrl": mock.uri(),
        "apiKey": "",
    }))
    .await;
    let s2 = c.run_ai_job().await;
    assert_eq!(s2["applied"], 0);
    assert!(mock.received_requests().await.unwrap().is_empty());
    let calls_after_missing_key = c.ai_calls().await;
    assert_eq!(calls_after_missing_key["total"], 2);
    assert!(
        calls_after_missing_key["items"]
            .as_array()
            .unwrap()
            .iter()
            .all(|r| r["status"] == "DISABLED")
    );

    // 3. Configure valid API key via PUT /api/admin/settings/ai -> takes effect immediately!
    c.configure_ai(json!({
        "provider": "openai",
        "baseUrl": mock.uri(),
        "apiKey": "sk-live-key",
    }))
    .await;
    let s3 = c.run_ai_job().await;
    assert_eq!(s3["applied"], 1);
    assert_eq!(c.tx_state(tx_id).await, (Some(courses_id), None, None));
    assert_eq!(mock.received_requests().await.unwrap().len(), 1);

    // 4. Turn AI back off via PUT /api/admin/settings/ai -> back to DISABLED immediately.
    c.configure_ai(json!({"provider": "none"})).await;
    let tx2 = c
        .tx(&checking, "2026-01-06", "-15", "Another Grocer", None)
        .await;
    let s4 = c.run_ai_job().await;
    assert_eq!(s4["applied"], 0);
    assert_eq!(c.tx_state(tx2).await, (None, None, None));
    assert_eq!(
        mock.received_requests().await.unwrap().len(),
        1,
        "provider must not be called after disabling"
    );
}

#[tokio::test]
async fn suggest_mode_and_max_concurrency_setting_work_end_to_end() {
    let c = setup().await;
    let mock = MockServer::start().await;

    Mock::given(method("POST"))
        .and(path("/v1/chat/completions"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "choices": [{"message": {"content": "{\"categorySlug\": \"courses\", \"confidence\": 0.99}"}}],
            "usage": {"prompt_tokens": 30, "completion_tokens": 8, "total_tokens": 38}
        })))
        .expect(3)
        .mount(&mock)
        .await;

    c.configure_ai(json!({
        "provider": "openai",
        "baseUrl": mock.uri(),
        "apiKey": "sk-test",
        "maxConcurrency": 2,
    }))
    .await;
    assert_eq!(c.app.state.budget.ai.get().max_concurrency().await, 2);

    c.enable_budget_ai("SUGGEST", 50).await;
    let checking = c.account("Courant").await;
    let courses_id = c.category_id_by_slug("courses").await;
    let tx1 = c.tx(&checking, "2026-01-01", "-10", "Shop A", None).await;
    let tx2 = c.tx(&checking, "2026-01-02", "-10", "Shop B", None).await;
    let tx3 = c.tx(&checking, "2026-01-03", "-10", "Shop C", None).await;

    let status = c.run_ai_job().await;
    assert_eq!(status["total"], 3);
    assert_eq!(status["processed"], 3);
    assert_eq!(status["applied"], 0);
    assert_eq!(status["suggested"], 3);

    for id in [tx1, tx2, tx3] {
        assert_eq!(c.tx_state(id).await, (None, Some(courses_id), Some(99)));
    }
}

#[tokio::test]
async fn edge_cases_empty_categories_unreadable_body_kept_key_and_concurrency_clamping() {
    let c = setup().await;
    let mock = MockServer::start().await;

    Mock::given(method("POST"))
        .and(path("/v1/chat/completions"))
        .and(header("authorization", "Bearer sk-initial"))
        .respond_with(ResponseTemplate::new(200).set_body_string("<html>proxy error</html>"))
        .expect(1)
        .mount(&mock)
        .await;

    c.configure_ai(json!({
        "provider": "openai",
        "model": "gpt-4o-mini",
        "baseUrl": mock.uri(),
        "apiKey": "sk-initial",
    }))
    .await;

    // Updating model with blank apiKey keeps the previously stored encrypted key.
    c.configure_ai(json!({
        "provider": "openai",
        "model": "gpt-4.1-mini",
        "baseUrl": mock.uri(),
        "apiKey": "   ",
    }))
    .await;

    let ai = c.app.state.budget.ai.get();
    assert_eq!(
        ai.provider().await,
        ("openai".to_owned(), Some("gpt-4.1-mini".to_owned()))
    );

    // Empty categories list short-circuits with EMPTY and makes zero HTTP calls.
    let input = CategorizationInput {
        merchant_label: "Any Shop".to_owned(),
        description: "Any Shop".to_owned(),
        amount: dec("-10.00"),
    };
    let empty_cats = ai.categorize(&input, &[], &[]).await;
    assert_eq!(empty_cats.status, "EMPTY");
    assert!(empty_cats.suggestion.is_none());
    assert!(mock.received_requests().await.unwrap().is_empty());

    // Unreadable non-JSON 200 body degrades cleanly to ERROR without exposing body.
    c.enable_budget_ai("AUTO_ALL", 50).await;
    let checking = c.account("Courant").await;
    let _tx = c
        .tx(&checking, "2026-01-05", "-10", "Proxy Glitch Shop", None)
        .await;
    let status = c.run_ai_job().await;
    assert_eq!(status["done"], true);
    assert_eq!(status["applied"], 0);
    let log = &c.ai_calls().await["items"][0];
    assert_eq!(log["status"], "ERROR");
    assert_eq!(log["error"], "The provider returned an unreadable answer");

    // Out-of-bounds or invalid ai.max-concurrency rows in DB clamp to [1, 16] with default 4.
    for (raw, expected) in [("0", 1), ("99", 16), ("not-a-number", 4), ("8", 8)] {
        picsou::db::query(
            "INSERT INTO app_setting (setting_key, value) VALUES ('ai.max-concurrency', $1) \
             ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value",
        )
        .bind(raw)
        .execute(&c.app.state.db)
        .await
        .unwrap();
        assert_eq!(ai.max_concurrency().await, expected);
    }
}

#[tokio::test]
async fn anthropic_does_not_follow_cross_host_redirect_or_leak_api_key() {
    let c = setup().await;
    let primary = MockServer::start().await;
    let foreign = MockServer::start().await;
    let foreign_url = format!("http://localhost:{}/steal", foreign.address().port());

    Mock::given(method("POST"))
        .and(path("/v1/messages"))
        .respond_with(ResponseTemplate::new(307).insert_header("location", foreign_url.as_str()))
        .expect(1)
        .mount(&primary)
        .await;

    Mock::given(path("/steal"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "content": [{"type": "text", "text": "{\"categorySlug\": \"courses\", \"confidence\": 0.9}"}]
        })))
        .mount(&foreign)
        .await;

    c.configure_ai(json!({
        "provider": "anthropic",
        "model": "claude-haiku-4-5",
        "baseUrl": primary.uri(),
        "apiKey": "sk-ant-redirect-secret",
    }))
    .await;
    c.enable_budget_ai("AUTO_ALL", 50).await;

    let checking = c.account("Courant").await;
    let _tx = c
        .tx(
            &checking,
            "2026-01-05",
            "-18.00",
            "Redirect Test Shop",
            None,
        )
        .await;

    let status = c.run_ai_job().await;
    assert_eq!(status["done"], true);
    assert_eq!(status["applied"], 0);

    let foreign_reqs = foreign.received_requests().await.unwrap();
    assert!(
        foreign_reqs.is_empty(),
        "AI provider client must not follow redirects to another host (received {} request(s), headers={:?})",
        foreign_reqs.len(),
        foreign_reqs.first().map(|r| &r.headers)
    );

    let log = &c.ai_calls().await["items"][0];
    assert_eq!(log["status"], "ERROR");
    assert_eq!(log["error"], "The provider answered HTTP 307");
}
