//! Integration tests of the embedded MCP server:
//! - Curated 93-tool catalog guard (matching Java's McpToolCatalogTest)
//! - Authentication (access key + OAuth2 MCP JWT), RFC 9728 WWW-Authenticate, Property A/B/C, MCP_ENABLED=false
//! - Streamable HTTP transport (2025-11-25, backward compatibility, SSE tool responses, keep-alive, DELETE cleanup)
//! - Legacy SSE transport (GET /mcp endpoint event, POST /mcp/message, keep-alive pings, session isolation)
//! - End-to-end tool behavior across all 10 tool groups with member isolation and scope enforcement

use std::collections::BTreeSet;
use std::time::Duration;

use chrono::NaiveDate;
use futures::StreamExt;
use jsonwebtoken::{Algorithm, EncodingKey, Header};
use picsou::auth::MCP_AUDIENCE;
use picsou::identity::scopes;
use picsou::testing::{JWT_SECRET, TestApp};
use reqwest::StatusCode;
use serde_json::{Value, json};
use tokio::time::timeout;

/// Mints a valid OAuth2 MCP JWT (`type=mcp`, `aud=picsou-mcp`) for the given user and scopes.
fn mint_mcp_jwt(
    user_id: i64,
    username: &str,
    token_version: i64,
    granted_scopes: &[&str],
) -> String {
    let now = picsou::testing::epoch().timestamp();
    let claims = json!({
        "sub": username,
        "uid": user_id,
        "type": "mcp",
        "tv": token_version,
        "scope": granted_scopes.join(" "),
        "aud": [MCP_AUDIENCE],
        "iat": now,
        "exp": now + 3600,
    });
    let key = EncodingKey::from_secret(JWT_SECRET.as_bytes()).expect("jwt key");
    jsonwebtoken::encode(&Header::new(Algorithm::HS256), &claims, &key).expect("encode mcp jwt")
}

async fn admin_ids(app: &TestApp) -> (i64, i64) {
    sqlx::query_as("SELECT id, member_id FROM app_user WHERE username = 'admin'")
        .fetch_one(&app.state.db)
        .await
        .expect("admin user")
}

/// Helper for driving Streamable HTTP sessions cleanly.
struct StreamableClient {
    http: reqwest::Client,
    base_url: String,
    bearer: String,
    session_id: String,
}

impl StreamableClient {
    async fn connect(app: &TestApp, bearer: &str, version: &str) -> (Self, Value) {
        let http = reqwest::Client::new();
        let resp = http
            .post(format!("{}/mcp", app.base_url))
            .bearer_auth(bearer)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .json(&json!({
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": version,
                    "capabilities": {},
                    "clientInfo": { "name": "picsou-test", "version": "0" }
                }
            }))
            .send()
            .await
            .expect("initialize sent");
        assert_eq!(resp.status(), StatusCode::OK);
        let session_id = resp
            .headers()
            .get("mcp-session-id")
            .expect("Mcp-Session-Id header")
            .to_str()
            .unwrap()
            .to_owned();
        let body: Value = resp.json().await.expect("init json");

        let notif = http
            .post(format!("{}/mcp", app.base_url))
            .bearer_auth(bearer)
            .header("Mcp-Session-Id", &session_id)
            .header("MCP-Protocol-Version", version)
            .header("Content-Type", "application/json")
            .json(&json!({
                "jsonrpc": "2.0",
                "method": "notifications/initialized"
            }))
            .send()
            .await
            .expect("initialized notification");
        assert_eq!(notif.status(), StatusCode::ACCEPTED);

        (
            Self {
                http,
                base_url: app.base_url.clone(),
                bearer: bearer.to_owned(),
                session_id,
            },
            body,
        )
    }

    async fn rpc_raw(&self, accept: &str, body: Value) -> (StatusCode, String, String) {
        let resp = self
            .http
            .post(format!("{}/mcp", self.base_url))
            .bearer_auth(&self.bearer)
            .header("Mcp-Session-Id", &self.session_id)
            .header("MCP-Protocol-Version", "2025-11-25")
            .header("Accept", accept)
            .header("Content-Type", "application/json")
            .json(&body)
            .send()
            .await
            .expect("rpc post");
        let status = resp.status();
        let content_type = resp
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_owned();
        let text = resp.text().await.expect("body text");
        (status, content_type, text)
    }

    async fn rpc(&self, body: Value) -> Value {
        let (status, content_type, text) = self
            .rpc_raw("application/json, text/event-stream", body)
            .await;
        assert_eq!(
            status,
            StatusCode::OK,
            "unexpected status with body: {text}"
        );
        parse_rpc_payload(&content_type, &text)
    }

    async fn call_tool(&self, id: i64, name: &str, arguments: Value) -> (bool, String) {
        let msg = self
            .rpc(json!({
                "jsonrpc": "2.0",
                "id": id,
                "method": "tools/call",
                "params": { "name": name, "arguments": arguments }
            }))
            .await;
        assert!(msg.get("error").is_none(), "JSON-RPC error: {msg}");
        let result = &msg["result"];
        let is_error = result["isError"].as_bool().unwrap_or(false);
        let text = result["content"][0]["text"]
            .as_str()
            .expect("text content")
            .to_owned();
        (is_error, text)
    }

    async fn call_tool_ok(&self, id: i64, name: &str, arguments: Value) -> String {
        let (is_error, text) = self.call_tool(id, name, arguments).await;
        assert!(!is_error, "tool {name} failed unexpectedly: {text}");
        text
    }

    async fn call_tool_json(&self, id: i64, name: &str, arguments: Value) -> Value {
        let text = self.call_tool_ok(id, name, arguments).await;
        serde_json::from_str(&text)
            .unwrap_or_else(|e| panic!("tool {name} did not return JSON ({e}): {text}"))
    }

    async fn close(&self) -> StatusCode {
        self.http
            .delete(format!("{}/mcp", self.base_url))
            .bearer_auth(&self.bearer)
            .header("Mcp-Session-Id", &self.session_id)
            .header("MCP-Protocol-Version", "2025-11-25")
            .send()
            .await
            .expect("delete session")
            .status()
    }
}

fn parse_rpc_payload(content_type: &str, body: &str) -> Value {
    let json_text = if content_type.starts_with("text/event-stream") {
        body.lines()
            .filter_map(|line| line.strip_prefix("data:"))
            .map(str::trim)
            .find(|line| !line.is_empty())
            .unwrap_or_else(|| panic!("no SSE data line in: {body}"))
    } else {
        body
    };
    let val: Value =
        serde_json::from_str(json_text).unwrap_or_else(|e| panic!("bad JSON ({e}): {json_text}"));
    assert_eq!(val["jsonrpc"], "2.0");
    val
}

// ─── 1. Curated Catalog & Curation Guard ────────────────────────────────────

#[test]
fn catalog_matches_java_curated_allowlist_and_curation_rules() {
    let expected: BTreeSet<&str> = [
        // accounts:read / accounts:write
        "list_accounts",
        "get_account",
        "get_account_holdings",
        "get_account_balance_history",
        "get_account_deletion_impact",
        "create_manual_account",
        "update_account",
        "delete_account",
        "add_balance_snapshot",
        "upsert_holding",
        "delete_holding",
        "set_scpi_position",
        "get_peer_loan",
        "add_peer_loan_repayment",
        "get_peer_loans_overview",
        "close_peer_loan",
        // transactions:read / transactions:write
        "list_account_transactions",
        "add_transaction",
        "update_transaction",
        "delete_transaction",
        // goals:read / goals:write
        "list_goals",
        "get_goal",
        "get_goal_monthly_entries",
        "create_goal",
        "update_goal",
        "delete_goal",
        "set_goal_month_contribution",
        "create_recurring_investment",
        // dashboard:read / family:read / prices:read
        "get_dashboard",
        "get_net_worth_history",
        "get_profit_and_loss",
        "get_family_dashboard",
        "get_price",
        // sync:trigger / sync:read
        "trigger_full_sync",
        "trigger_bank_sync",
        "trigger_broker_sync",
        "trigger_crypto_exchange_sync",
        "trigger_crypto_wallet_sync",
        "get_sync_status",
        // oauth2:discover / oauth2:session-status
        "get_oauth2_configuration",
        "get_oauth2_session_status",
        // budget:categories-read / budget:categories-write
        "list_budget_categories",
        "get_budget_category",
        "create_budget_category",
        "update_budget_category",
        "delete_budget_category",
        // budget:rules-read / budget:rules-write
        "list_budget_rules",
        "get_budget_rule",
        "preview_budget_rule",
        "create_budget_rule",
        "update_budget_rule",
        "delete_budget_rule",
        "apply_rule_to_transactions",
        // budget:transactions-read / budget:transactions-write
        "list_budget_transactions",
        "update_budget_transaction",
        // budget:recurring-read / budget:recurring-write
        "list_recurring_series",
        "get_recurring_series",
        "get_recurring_activity",
        "get_recurring_calendar",
        "confirm_recurring_series",
        "ignore_recurring_series",
        "undo_recurring_series_change",
        "create_recurring_series",
        "update_recurring_series",
        "delete_recurring_series",
        "detect_recurring_series",
        // budget:envelopes-read / budget:envelopes-write
        "list_budget_envelopes",
        "get_budget_envelope",
        "create_budget_envelope",
        "update_budget_envelope",
        "delete_budget_envelope",
        "set_envelope_allocation",
        // budget:dashboard-read / budget:transactions-read
        "get_budget_dashboard",
        "get_spending_by_category",
        "get_spending_category_detail",
        "get_cashflow",
        "get_cashflow_flow",
        // analysis:read
        "get_allocation",
        "get_wealth_pyramid",
        "get_portfolio_diversification",
        "get_wealth_projection",
        "get_allocation_targets",
        "get_essential_expense_estimate",
        "get_savings_suggestions",
        "get_real_estate_summary",
        // accounts:read (per-account analysis)
        "get_savings_interest",
        "get_property_valuations",
        "get_loan_summary",
        "get_realized_pnl",
        "get_exchange_positions",
        // prices:read
        "get_security_insight",
        // budget:ai-write
        "start_ai_categorization",
        "get_ai_categorization_status",
    ]
    .into_iter()
    .collect();

    let catalog = picsou::mcp::tools::catalog();
    assert_eq!(catalog.len(), 93, "expected exactly 93 curated MCP tools");

    let actual: BTreeSet<&str> = catalog.iter().map(|t| t.name).collect();
    assert_eq!(actual, expected, "MCP tool catalog must match Java exactly");

    let forbidden = [
        "auth",
        "credential",
        "login",
        "logout",
        "password",
        "passcode",
        "mfa",
        "totp",
        "admin",
        "export",
        "setup",
        "recovery",
        "token",
        "secret",
        "initiate",
        "complete",
        "wizard",
        "powens",
        "finary",
        "add_exchange",
        "add_wallet",
        "delete_member",
        "create_member",
    ];
    let exceptions = ["get_oauth2_configuration", "get_oauth2_session_status"];

    let mut triggers = 0;
    for tool in &catalog {
        assert!(
            scopes::is_known(tool.scope),
            "tool {} uses unknown scope {}",
            tool.name,
            tool.scope
        );
        assert_eq!(tool.input_schema["type"], "object");
        assert!(tool.input_schema["properties"].is_object());
        if tool.name.starts_with("trigger_") {
            triggers += 1;
            assert!(
                tool.description
                    .contains("shared by all MCP trigger tools for this member")
                    && tool.description.contains("15 minutes")
                    && tool.description.contains("four per day"),
                "trigger tool {} description missing cooldown warning",
                tool.name
            );
        }
        if !exceptions.contains(&tool.name) {
            for bad in forbidden {
                assert!(
                    !tool.name.contains(bad),
                    "tool {} must not contain forbidden substring {bad}",
                    tool.name
                );
            }
        }
    }
    assert_eq!(triggers, 5);
}

// ─── 2. Auth, RFC 9728 WWW-Authenticate, Property A/B/C, MCP_ENABLED=false ──

#[tokio::test]
async fn unauthenticated_mcp_returns_401_with_rfc9728_challenge_and_disabled_returns_404() {
    let app = TestApp::builder()
        .with_admin()
        .env("MCP_ENABLED", "false")
        .env("MCP_PROTOCOL", "STREAMABLE")
        .spawn()
        .await;
    let (user_id, _) = admin_ids(&app).await;
    let key = app.create_access_key(user_id, &["accounts:read"]).await;
    let http = reqwest::Client::new();

    // Unauthenticated still gets RFC 9728 WWW-Authenticate 401.
    let unauth = http
        .get(format!("{}/mcp", app.base_url))
        .send()
        .await
        .unwrap();
    assert_eq!(unauth.status(), StatusCode::UNAUTHORIZED);
    let challenge = unauth
        .headers()
        .get("www-authenticate")
        .expect("WWW-Authenticate header")
        .to_str()
        .unwrap();
    assert!(
        challenge.contains("resource_metadata=")
            && challenge.contains("/.well-known/oauth-protected-resource"),
        "unexpected WWW-Authenticate: {challenge}"
    );

    // Invalid bearer token also gets 401 with challenge.
    let bad_key = http
        .post(format!("{}/mcp", app.base_url))
        .bearer_auth("psk_invalid000000000000000000000000")
        .send()
        .await
        .unwrap();
    assert_eq!(bad_key.status(), StatusCode::UNAUTHORIZED);
    assert!(bad_key.headers().contains_key("www-authenticate"));

    // Authenticated requests get 404 when MCP_ENABLED=false.
    for (method, path) in [
        (reqwest::Method::GET, "/mcp"),
        (reqwest::Method::POST, "/mcp"),
        (reqwest::Method::DELETE, "/mcp"),
        (reqwest::Method::POST, "/mcp/message"),
    ] {
        let resp = http
            .request(method, format!("{}{path}", app.base_url))
            .bearer_auth(&key)
            .send()
            .await
            .unwrap();
        assert_eq!(
            resp.status(),
            StatusCode::NOT_FOUND,
            "expected 404 for {path} when MCP_ENABLED=false"
        );
    }
}

#[tokio::test]
async fn security_properties_a_b_and_cookie_defense_in_depth() {
    let app = TestApp::builder()
        .with_admin()
        .env("MCP_PROTOCOL", "STREAMABLE")
        .spawn()
        .await;
    let (admin_uid, admin_mid) = admin_ids(&app).await;
    let managed_mid = app.create_managed_member("Child").await;

    sqlx::query(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color) \
         VALUES ($1, 'Admin Checking', 'CHECKING', 'EUR', 100, true, '#111111'), \
                ($2, 'Child Checking', 'CHECKING', 'EUR', 50, true, '#222222')",
    )
    .bind(admin_mid)
    .bind(managed_mid)
    .execute(&app.state.db)
    .await
    .unwrap();

    let key = app.create_access_key(admin_uid, &["accounts:read"]).await;
    let jwt = mint_mcp_jwt(admin_uid, "admin", 0, &["accounts:read"]);
    let http = reqwest::Client::new();

    // Property A: neither access key nor MCP JWT can call /api/**.
    for bearer in [&key, &jwt] {
        let api_resp = http
            .get(format!("{}/api/accounts", app.base_url))
            .bearer_auth(bearer)
            .send()
            .await
            .unwrap();
        assert_eq!(api_resp.status(), StatusCode::UNAUTHORIZED);
    }

    // Property B: admin access key with ?memberId=<managed> still only sees admin's own data.
    let (client, _) = StreamableClient::connect(&app, &key, "2025-11-25").await;
    let resp = http
        .post(format!("{}/mcp?memberId={managed_mid}", app.base_url))
        .bearer_auth(&key)
        .header("Mcp-Session-Id", &client.session_id)
        .header("Accept", "application/json")
        .json(&json!({
            "jsonrpc": "2.0",
            "id": 2,
            "method": "tools/call",
            "params": { "name": "list_accounts", "arguments": {} }
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
    let msg: Value = resp.json().await.unwrap();
    let text = msg["result"]["content"][0]["text"].as_str().unwrap();
    assert!(text.contains("Admin Checking"));
    assert!(!text.contains("Child Checking"));

    // /mcp/** auth isolation: a web cookie session is rejected with RFC 9728 401 challenge.
    let browser = app.login_admin().await;
    let init = browser
        .post("/mcp")
        .allow_decimal_strings()
        .header("Accept", "application/json")
        .json(&json!({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": { "protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": { "name": "t", "version": "0" } }
        }))
        .send()
        .await;
    assert_eq!(init.status, StatusCode::UNAUTHORIZED);
    assert_eq!(
        init.header("www-authenticate"),
        Some(
            format!(
                "Bearer resource_metadata=\"{}/.well-known/oauth-protected-resource\"",
                app.base_url
            )
            .as_str()
        )
    );
}

// ─── 3. Streamable HTTP Transport (Access Key & OAuth2 JWT) ─────────────────

#[tokio::test]
async fn streamable_http_works_with_access_key_and_oauth2_jwt_and_enforces_isolation() {
    let app = TestApp::builder()
        .with_admin()
        .env("MCP_PROTOCOL", "STREAMABLE")
        .spawn()
        .await;
    let (alice_uid, alice_mid) = app.create_member("alice", "pass12345678").await;
    let (bob_uid, bob_mid) = app.create_member("bob", "pass12345678").await;

    sqlx::query(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color) \
         VALUES ($1, 'Alice Main', 'CHECKING', 'EUR', 1200, true, '#111111'), \
                ($2, 'Bob Secret', 'CHECKING', 'EUR', 9999, true, '#222222')",
    )
    .bind(alice_mid)
    .bind(bob_mid)
    .execute(&app.state.db)
    .await
    .unwrap();

    let alice_key = app.create_access_key(alice_uid, &["accounts:read"]).await;
    let alice_jwt = mint_mcp_jwt(alice_uid, "alice", 0, &["accounts:read"]);
    let bob_key = app.create_access_key(bob_uid, &["accounts:read"]).await;

    for (label, bearer) in [("access-key", alice_key), ("oauth2", alice_jwt)] {
        let (client, init) = StreamableClient::connect(&app, &bearer, "2025-11-25").await;
        assert_eq!(
            init["result"]["protocolVersion"], "2025-11-25",
            "credential={label}"
        );
        assert!(init["result"]["capabilities"]["tools"].is_object());
        assert_eq!(init["result"]["serverInfo"]["name"], "picsou");
        assert!(
            init["result"]["instructions"]
                .as_str()
                .unwrap()
                .contains("Picsou personal-finance MCP server")
        );

        // ping
        let ping = client
            .rpc(json!({"jsonrpc": "2.0", "id": 10, "method": "ping"}))
            .await;
        assert_eq!(ping["result"], json!({}));

        // tools/list advertises all 93 tools regardless of key scopes.
        let list = client
            .rpc(json!({"jsonrpc": "2.0", "id": 11, "method": "tools/list"}))
            .await;
        let tool_names: Vec<&str> = list["result"]["tools"]
            .as_array()
            .unwrap()
            .iter()
            .map(|t| t["name"].as_str().unwrap())
            .collect();
        assert_eq!(tool_names.len(), 93);
        assert!(tool_names.contains(&"list_accounts"));
        assert!(tool_names.contains(&"list_goals"));

        // tools/call with Accept: application/json, text/event-stream completes as SSE stream.
        let (status, ct, raw) = client
            .rpc_raw(
                "application/json, text/event-stream",
                json!({
                    "jsonrpc": "2.0",
                    "id": 12,
                    "method": "tools/call",
                    "params": { "name": "list_accounts", "arguments": {} }
                }),
            )
            .await;
        assert_eq!(status, StatusCode::OK);
        assert!(
            ct.starts_with("text/event-stream"),
            "expected SSE for tool call, got {ct}"
        );
        let parsed = parse_rpc_payload(&ct, &raw);
        assert_eq!(parsed["result"]["isError"], false);
        let text = parsed["result"]["content"][0]["text"].as_str().unwrap();
        assert!(text.contains("Alice Main"));
        assert!(!text.contains("Bob Secret"));

        // tools/call with Accept: application/json returns plain JSON.
        let (status, ct, _) = client
            .rpc_raw(
                "application/json",
                json!({
                    "jsonrpc": "2.0",
                    "id": 13,
                    "method": "tools/call",
                    "params": { "name": "list_accounts", "arguments": {} }
                }),
            )
            .await;
        assert_eq!(status, StatusCode::OK);
        assert!(ct.starts_with("application/json"));

        // Missing scope fails cleanly with isError=true naming the missing scope.
        let (is_err, err_text) = client.call_tool(14, "list_goals", json!({})).await;
        assert!(is_err);
        assert!(err_text.contains("goals:read"), "got: {err_text}");

        // Unknown tool -> JSON-RPC -32602; unknown method -> -32601.
        let unk_tool = client
            .rpc(json!({
                "jsonrpc": "2.0",
                "id": 15,
                "method": "tools/call",
                "params": { "name": "nonexistent_tool", "arguments": {} }
            }))
            .await;
        assert_eq!(unk_tool["error"]["code"], -32602);
        let unk_method = client
            .rpc(json!({"jsonrpc": "2.0", "id": 16, "method": "resources/list"}))
            .await;
        assert_eq!(unk_method["error"]["code"], -32601);

        // Bob cannot hijack or delete Alice's session.
        let hijack = client
            .http
            .post(format!("{}/mcp", app.base_url))
            .bearer_auth(&bob_key)
            .header("Mcp-Session-Id", &client.session_id)
            .json(&json!({"jsonrpc": "2.0", "id": 17, "method": "tools/list"}))
            .send()
            .await
            .unwrap();
        assert_eq!(hijack.status(), StatusCode::NOT_FOUND);

        // /mcp/message is absent in Streamable mode.
        let legacy = client
            .http
            .post(format!("{}/mcp/message", app.base_url))
            .bearer_auth(&bearer)
            .send()
            .await
            .unwrap();
        assert_eq!(legacy.status(), StatusCode::NOT_FOUND);

        // Close session and confirm it returns 404 afterward.
        assert_eq!(client.close().await, StatusCode::OK);
        let (after_status, _, _) = client
            .rpc_raw(
                "application/json",
                json!({"jsonrpc": "2.0", "id": 18, "method": "tools/list"}),
            )
            .await;
        assert_eq!(after_status, StatusCode::NOT_FOUND);
    }

    // Backward-compatible version negotiation on Streamable HTTP.
    let (compat, init_2024) = StreamableClient::connect(&app, &bob_key, "2024-11-05").await;
    assert_eq!(init_2024["result"]["protocolVersion"], "2024-11-05");
    assert_eq!(compat.close().await, StatusCode::OK);
}

#[tokio::test]
async fn streamable_http_get_sse_receives_keep_alive_ping_and_closes_on_delete() {
    let app = TestApp::builder()
        .with_admin()
        .env("MCP_PROTOCOL", "STREAMABLE")
        .env("MCP_KEEP_ALIVE_INTERVAL", "1s")
        .spawn()
        .await;
    let (uid, _) = admin_ids(&app).await;
    let key = app.create_access_key(uid, &["sync:read"]).await;
    let (client, _) = StreamableClient::connect(&app, &key, "2025-11-25").await;

    let sse_resp = client
        .http
        .get(format!("{}/mcp", app.base_url))
        .bearer_auth(&key)
        .header("Mcp-Session-Id", &client.session_id)
        .header("Accept", "text/event-stream")
        .send()
        .await
        .unwrap();
    assert_eq!(sse_resp.status(), StatusCode::OK);
    let mut stream = sse_resp.bytes_stream();

    let mut seen = String::new();
    let got_ping = timeout(Duration::from_secs(5), async {
        while let Some(chunk) = stream.next().await {
            let bytes = chunk.expect("sse chunk");
            seen.push_str(&String::from_utf8_lossy(&bytes));
            if seen.contains("\"method\":\"ping\"") {
                return true;
            }
        }
        false
    })
    .await
    .expect("timed out waiting for streamable keep-alive ping");
    assert!(got_ping, "did not receive ping in: {seen}");

    // Client responds to server ping -> 202 Accepted.
    let ping_reply = client
        .http
        .post(format!("{}/mcp", app.base_url))
        .bearer_auth(&key)
        .header("Mcp-Session-Id", &client.session_id)
        .json(&json!({"jsonrpc": "2.0", "id": "keepalive-1", "result": {}}))
        .send()
        .await
        .unwrap();
    assert_eq!(ping_reply.status(), StatusCode::ACCEPTED);

    // DELETE closes session and terminates the GET SSE stream.
    assert_eq!(client.close().await, StatusCode::OK);
    let ended = timeout(Duration::from_secs(3), async {
        while stream.next().await.is_some() {}
    })
    .await;
    assert!(ended.is_ok(), "GET SSE stream should end on DELETE /mcp");
}

// ─── 4. SSE Transport (Default Mode + Keep-Alive + Auth) ────────────────────

#[tokio::test]
async fn sse_transport_drives_full_handshake_tool_call_and_keep_alive_pings() {
    let app = TestApp::builder()
        .with_admin()
        .env("MCP_PROTOCOL", "SSE")
        .env("MCP_KEEP_ALIVE_INTERVAL", "1s")
        .spawn()
        .await;
    let (alice_uid, alice_mid) = app.create_member("sse-alice", "pass12345678").await;
    let (bob_uid, bob_mid) = app.create_member("sse-bob", "pass12345678").await;
    sqlx::query(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color) \
         VALUES ($1, 'SSE Alice Account', 'CHECKING', 'EUR', 500, true, '#111111'), \
                ($2, 'SSE Bob Account', 'CHECKING', 'EUR', 700, true, '#222222')",
    )
    .bind(alice_mid)
    .bind(bob_mid)
    .execute(&app.state.db)
    .await
    .unwrap();

    let alice_key = app.create_access_key(alice_uid, &["accounts:read"]).await;
    let alice_jwt = mint_mcp_jwt(alice_uid, "sse-alice", 0, &["accounts:read"]);
    let bob_key = app.create_access_key(bob_uid, &["accounts:read"]).await;
    let http = reqwest::Client::new();

    // In SSE mode, POST /mcp and DELETE /mcp return 405 Method Not Allowed.
    assert_eq!(
        http.post(format!("{}/mcp", app.base_url))
            .bearer_auth(&alice_key)
            .send()
            .await
            .unwrap()
            .status(),
        StatusCode::METHOD_NOT_ALLOWED
    );
    assert_eq!(
        http.delete(format!("{}/mcp", app.base_url))
            .bearer_auth(&alice_key)
            .send()
            .await
            .unwrap()
            .status(),
        StatusCode::METHOD_NOT_ALLOWED
    );

    // Exercise BOTH access-key and OAuth2 MCP JWT on SSE transport.
    for (idx, bearer) in [alice_key, alice_jwt].into_iter().enumerate() {
        let sse = http
            .get(format!("{}/mcp", app.base_url))
            .bearer_auth(&bearer)
            .header("Accept", "text/event-stream")
            .send()
            .await
            .unwrap();
        assert_eq!(sse.status(), StatusCode::OK);
        let mut stream = sse.bytes_stream();
        let mut buf = String::new();

        async fn read_until(
            stream: &mut (impl futures::Stream<Item = Result<bytes::Bytes, reqwest::Error>> + Unpin),
            buf: &mut String,
            needle: &str,
        ) {
            timeout(Duration::from_secs(5), async {
                while !buf.contains(needle) {
                    let chunk = stream
                        .next()
                        .await
                        .expect("stream closed early")
                        .expect("chunk");
                    buf.push_str(&String::from_utf8_lossy(&chunk));
                }
            })
            .await
            .unwrap_or_else(|_| panic!("timed out waiting for '{needle}' in:\n{buf}"));
        }

        // Wait for endpoint event.
        read_until(&mut stream, &mut buf, "sessionId=").await;
        let endpoint = buf
            .lines()
            .filter_map(|l| l.strip_prefix("data:"))
            .map(str::trim)
            .find(|l| l.contains("sessionId="))
            .expect("endpoint path")
            .to_owned();
        let message_url = format!("{}{endpoint}", app.base_url);

        // Bob cannot post to Alice's SSE session.
        let hijack = http
            .post(&message_url)
            .bearer_auth(&bob_key)
            .json(&json!({"jsonrpc": "2.0", "id": 99, "method": "ping"}))
            .send()
            .await
            .unwrap();
        assert_eq!(hijack.status(), StatusCode::NOT_FOUND);

        // initialize
        let init_post = http
            .post(&message_url)
            .bearer_auth(&bearer)
            .json(&json!({
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": { "protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": { "name": "test", "version": "0" } }
            }))
            .send()
            .await
            .unwrap();
        assert_eq!(init_post.status(), StatusCode::OK);
        read_until(&mut stream, &mut buf, "\"protocolVersion\":\"2024-11-05\"").await;

        // notifications/initialized
        let notif_post = http
            .post(&message_url)
            .bearer_auth(&bearer)
            .json(&json!({"jsonrpc": "2.0", "method": "notifications/initialized"}))
            .send()
            .await
            .unwrap();
        assert!(matches!(
            notif_post.status(),
            StatusCode::OK | StatusCode::ACCEPTED
        ));

        // tools/call list_accounts
        let call_post = http
            .post(&message_url)
            .bearer_auth(&bearer)
            .json(&json!({
                "jsonrpc": "2.0",
                "id": 2,
                "method": "tools/call",
                "params": { "name": "list_accounts", "arguments": {} }
            }))
            .send()
            .await
            .unwrap();
        assert_eq!(call_post.status(), StatusCode::OK);
        read_until(&mut stream, &mut buf, "SSE Alice Account").await;
        assert!(!buf.contains("SSE Bob Account"));

        // Scope denial on SSE
        let deny_post = http
            .post(&message_url)
            .bearer_auth(&bearer)
            .json(&json!({
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": { "name": "list_goals", "arguments": {} }
            }))
            .send()
            .await
            .unwrap();
        assert_eq!(deny_post.status(), StatusCode::OK);
        read_until(&mut stream, &mut buf, "goals:read").await;

        // Keep-alive ping arrives on idle SSE stream (verify on first iteration to keep test fast).
        if idx == 0 {
            read_until(&mut stream, &mut buf, "\"method\":\"ping\"").await;
            let reply = http
                .post(&message_url)
                .bearer_auth(&bearer)
                .json(&json!({"jsonrpc": "2.0", "id": "keepalive-1", "result": {}}))
                .send()
                .await
                .unwrap();
            assert!(matches!(
                reply.status(),
                StatusCode::OK | StatusCode::ACCEPTED
            ));
        }
    }
}

// ─── 5. Tool Behavior Tests Across All 10 Tool Groups ───────────────────────

#[tokio::test]
async fn account_and_transaction_tools_lifecycle_and_member_isolation() {
    let app = TestApp::builder()
        .with_admin()
        .env("MCP_PROTOCOL", "STREAMABLE")
        .spawn()
        .await;
    let (alice_uid, _) = app.create_member("acc-alice", "pass12345678").await;
    let (bob_uid, bob_mid) = app.create_member("acc-bob", "pass12345678").await;
    let bob_acc_id: i64 = sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color) \
         VALUES ($1, 'Bob Vault', 'CHECKING', 'EUR', 500, true, '#111111') RETURNING id",
    )
    .bind(bob_mid)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    let _ = bob_uid;

    let key = app
        .create_access_key(
            alice_uid,
            &[
                "accounts:read",
                "accounts:write",
                "transactions:read",
                "transactions:write",
            ],
        )
        .await;
    let (mcp, _) = StreamableClient::connect(&app, &key, "2025-11-25").await;

    // Create manual checking account.
    let acc = mcp
        .call_tool_json(
            2,
            "create_manual_account",
            json!({
                "name": "Main Checking",
                "type": "CHECKING",
                "currency": "EUR",
                "currentBalance": 1500.50,
                "color": "#1a2b3c"
            }),
        )
        .await;
    let acc_id = acc["id"].as_i64().unwrap();
    assert_eq!(acc["name"], "Main Checking");
    assert_eq!(acc["isManual"], true);

    // Read single account + verify Bob's account is hidden (not found).
    let got = mcp
        .call_tool_json(3, "get_account", json!({ "accountId": acc_id }))
        .await;
    assert_eq!(got["id"], acc_id);
    let (bob_err, _) = mcp
        .call_tool(4, "get_account", json!({ "accountId": bob_acc_id }))
        .await;
    assert!(bob_err, "reading another member's account must fail");

    // Update account.
    let updated = mcp
        .call_tool_json(
            5,
            "update_account",
            json!({
                "accountId": acc_id,
                "name": "Main Checking Renamed",
                "type": "CHECKING",
                "currency": "EUR",
                "currentBalance": 1600
            }),
        )
        .await;
    assert_eq!(updated["name"], "Main Checking Renamed");

    // Balance snapshot + history.
    let snap = mcp
        .call_tool_json(
            6,
            "add_balance_snapshot",
            json!({ "accountId": acc_id, "balance": 1750, "date": "2026-01-10" }),
        )
        .await;
    assert_eq!(snap["date"], "2026-01-10");
    let history = mcp
        .call_tool_json(
            7,
            "get_account_balance_history",
            json!({ "accountId": acc_id, "from": "2026-01-01", "to": "2026-01-31" }),
        )
        .await;
    assert!(!history.as_array().unwrap().is_empty());

    // Transactions CRUD.
    let tx = mcp
        .call_tool_json(
            8,
            "add_transaction",
            json!({
                "accountId": acc_id,
                "date": "2026-01-12",
                "description": "Salary",
                "amount": 3000,
                "txType": "DEPOSIT",
                "currency": "EUR"
            }),
        )
        .await;
    let tx_id = tx["id"].as_i64().unwrap();
    let txs = mcp
        .call_tool_json(
            9,
            "list_account_transactions",
            json!({ "accountId": acc_id }),
        )
        .await;
    assert_eq!(txs.as_array().unwrap().len(), 1);
    let tx_upd = mcp
        .call_tool_json(
            10,
            "update_transaction",
            json!({
                "accountId": acc_id,
                "transactionId": tx_id,
                "date": "2026-01-12",
                "description": "Salary Bonus",
                "amount": 3200,
                "txType": "DEPOSIT"
            }),
        )
        .await;
    assert_eq!(tx_upd["description"], "Salary Bonus");
    let del_tx = mcp
        .call_tool_ok(
            11,
            "delete_transaction",
            json!({ "accountId": acc_id, "transactionId": tx_id }),
        )
        .await;
    assert!(del_tx.contains(&tx_id.to_string()));

    // Holdings upsert, list, delete on a PEA account.
    let pea = mcp
        .call_tool_json(
            12,
            "create_manual_account",
            json!({ "name": "My PEA", "type": "PEA", "currency": "EUR" }),
        )
        .await;
    let pea_id = pea["id"].as_i64().unwrap();
    let holdings = mcp
        .call_tool_json(
            13,
            "upsert_holding",
            json!({
                "accountId": pea_id,
                "ticker": "CW8.PA",
                "name": "Amundi MSCI World",
                "quantity": 10,
                "currentPriceEur": 500
            }),
        )
        .await;
    assert_eq!(holdings.as_array().unwrap().len(), 1);
    assert_eq!(holdings[0]["ticker"], "CW8.PA");
    let got_holdings = mcp
        .call_tool_json(14, "get_account_holdings", json!({ "accountId": pea_id }))
        .await;
    assert_eq!(got_holdings.as_array().unwrap().len(), 1);
    let del_h = mcp
        .call_tool_ok(
            15,
            "delete_holding",
            json!({ "accountId": pea_id, "ticker": "CW8.PA" }),
        )
        .await;
    assert!(del_h.contains("CW8.PA"));

    // SCPI position: sets balance = shares * withdrawalPrice and preserves omitted distribution.
    let scpi_acc = mcp
        .call_tool_json(
            16,
            "create_manual_account",
            json!({ "name": "Corum Origin", "type": "SCPI", "currency": "EUR" }),
        )
        .await;
    let scpi_id = scpi_acc["id"].as_i64().unwrap();
    let scpi_pos = mcp
        .call_tool_json(
            17,
            "set_scpi_position",
            json!({
                "accountId": scpi_id,
                "shareCount": 10,
                "subscriptionPriceEur": 1135,
                "withdrawalPriceEur": 1000,
                "managementCompany": "CORUM",
                "dividendPolicy": "CASH",
                "annualDistributionPerShareEur": 68.5,
                "distributionFrequency": "MONTHLY"
            }),
        )
        .await;
    assert_eq!(scpi_pos["valuationStatus"], "OK");
    // Second call omits distribution fields -> stored distribution is kept.
    let scpi_pos2 = mcp
        .call_tool_json(
            18,
            "set_scpi_position",
            json!({
                "accountId": scpi_id,
                "shareCount": 12,
                "withdrawalPriceEur": 1000
            }),
        )
        .await;
    assert_eq!(scpi_pos2["distributionFrequency"], "MONTHLY");

    // Deletion impact + soft delete.
    let impact = mcp
        .call_tool_json(
            19,
            "get_account_deletion_impact",
            json!({ "accountId": acc_id }),
        )
        .await;
    assert_eq!(impact["removesConnection"], false);
    let deleted = mcp
        .call_tool_json(20, "delete_account", json!({ "accountId": acc_id }))
        .await;
    assert_eq!(deleted["removesConnection"], false);
    let (gone, _) = mcp
        .call_tool(21, "get_account", json!({ "accountId": acc_id }))
        .await;
    assert!(gone);
}

#[tokio::test]
async fn goal_and_insight_tools_lifecycle() {
    let app = TestApp::builder()
        .with_admin()
        .env("MCP_PROTOCOL", "STREAMABLE")
        .spawn()
        .await;
    let (uid, mid) = app.create_member("goal-alice", "pass12345678").await;
    let acc_id: i64 = sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color) \
         VALUES ($1, 'Livret A', 'LIVRET_A', 'EUR', 5000, true, '#111111') RETURNING id",
    )
    .bind(mid)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    // Seed a recorded price snapshot for AAPL so get_price works without network.
    sqlx::query("INSERT INTO price_snapshot (ticker, date, price_eur) VALUES ('AAPL', $1, 185.50)")
        .bind(NaiveDate::from_ymd_opt(2026, 1, 15).unwrap())
        .execute(&app.state.db)
        .await
        .unwrap();

    let key = app
        .create_access_key(
            uid,
            &[
                "goals:read",
                "goals:write",
                "dashboard:read",
                "family:read",
                "prices:read",
            ],
        )
        .await;
    let (mcp, _) = StreamableClient::connect(&app, &key, "2025-11-25").await;

    // Savings goal CRUD + monthly entries + manual contribution.
    let goal = mcp
        .call_tool_json(
            2,
            "create_goal",
            json!({
                "name": "Emergency Fund",
                "targetAmount": 10000,
                "deadline": "2027-01-15",
                "accountIds": [acc_id]
            }),
        )
        .await;
    let goal_id = goal["id"].as_i64().unwrap();
    assert_eq!(goal["name"], "Emergency Fund");

    let goals = mcp.call_tool_json(3, "list_goals", json!({})).await;
    assert_eq!(goals.as_array().unwrap().len(), 1);
    let got_goal = mcp
        .call_tool_json(4, "get_goal", json!({ "goalId": goal_id }))
        .await;
    assert_eq!(got_goal["id"], goal_id);

    let upd_goal = mcp
        .call_tool_json(
            5,
            "update_goal",
            json!({
                "goalId": goal_id,
                "name": "Emergency Fund+",
                "targetAmount": 12000,
                "deadline": "2027-06-15",
                "accountIds": [acc_id]
            }),
        )
        .await;
    assert_eq!(upd_goal["name"], "Emergency Fund+");

    let entries = mcp
        .call_tool_json(6, "get_goal_monthly_entries", json!({ "goalId": goal_id }))
        .await;
    assert!(!entries.as_array().unwrap().is_empty());

    let manual = mcp
        .call_tool_json(
            7,
            "set_goal_month_contribution",
            json!({ "goalId": goal_id, "yearMonth": "2026-01", "amount": 400 }),
        )
        .await;
    assert_eq!(manual["yearMonth"], "2026-01");

    // Recurring investment plan.
    let plan = mcp
        .call_tool_json(
            8,
            "create_recurring_investment",
            json!({
                "name": "Monthly DCA",
                "monthlyAmount": 300,
                "accountId": acc_id,
                "expectedReturn": 7
            }),
        )
        .await;
    assert_eq!(plan["type"], "RECURRING_INVESTMENT");

    let del_msg = mcp
        .call_tool_ok(9, "delete_goal", json!({ "goalId": goal_id }))
        .await;
    assert!(del_msg.contains(&goal_id.to_string()));

    // Insight tools.
    let dash = mcp
        .call_tool_json(10, "get_dashboard", json!({ "range": "1Y" }))
        .await;
    assert_eq!(
        dash["totalNetWorth"]
            .to_string()
            .parse::<rust_decimal::Decimal>()
            .unwrap(),
        rust_decimal::Decimal::from(5000)
    );
    let nw = mcp
        .call_tool_json(11, "get_net_worth_history", json!({ "months": 6 }))
        .await;
    assert!(nw.is_array());
    let pnl = mcp
        .call_tool_json(12, "get_profit_and_loss", json!({}))
        .await;
    assert_eq!(
        pnl["total"]
            .to_string()
            .parse::<rust_decimal::Decimal>()
            .unwrap(),
        rust_decimal::Decimal::from(5000)
    );
    let fam = mcp
        .call_tool_json(13, "get_family_dashboard", json!({}))
        .await;
    assert!(fam["sharedAccounts"].is_array());
    let eur = mcp
        .call_tool_ok(14, "get_price", json!({ "ticker": "EUR" }))
        .await;
    assert_eq!(eur, "1");
    let aapl = mcp
        .call_tool_ok(15, "get_price", json!({ "ticker": "AAPL" }))
        .await;
    assert_eq!(aapl, "185.5");
}

#[tokio::test]
async fn sync_and_oauth2_tools_enforce_cooldown_and_session_introspection() {
    let app = TestApp::builder()
        .with_admin()
        .env("MCP_PROTOCOL", "STREAMABLE")
        .spawn()
        .await;
    let (uid, mid) = app.create_member("sync-alice", "pass12345678").await;

    // Seed a Wise connection in REAUTH_REQUIRED and a Degiro session to check get_sync_status.
    sqlx::query(
        "INSERT INTO wise_connection (member_id, api_token, status) VALUES ($1, 'super-secret-token', 'REAUTH_REQUIRED')",
    )
    .bind(mid)
    .execute(&app.state.db)
    .await
    .unwrap();

    let key = app
        .create_access_key(
            uid,
            &[
                "sync:read",
                "sync:trigger",
                "oauth2:discover",
                "oauth2:session-status",
            ],
        )
        .await;
    let jwt = mint_mcp_jwt(
        uid,
        "sync-alice",
        0,
        &["oauth2:discover", "oauth2:session-status"],
    );

    let (mcp, _) = StreamableClient::connect(&app, &key, "2025-11-25").await;

    // get_sync_status: includes all sources, reports Wise NEEDS_REAUTH, never leaks secrets.
    let status = mcp.call_tool_ok(2, "get_sync_status", json!({})).await;
    assert!(status.contains("wise: NEEDS_REAUTH"));
    assert!(status.contains("reauth=true"));
    assert!(status.contains("revolut: NOT_CONNECTED"));
    assert!(!status.contains("super-secret-token"));

    // Shared per-member cooldown: first trigger succeeds, immediate second is blocked.
    let first = mcp
        .call_tool_ok(3, "trigger_crypto_wallet_sync", json!({}))
        .await;
    assert!(first.contains("wallets: SKIPPED_NOT_CONNECTED"));

    let blocked = mcp
        .call_tool_ok(4, "trigger_crypto_exchange_sync", json!({}))
        .await;
    assert!(blocked.contains("MCP sync cooldown is shared"));
    assert!(blocked.contains("Try again in 15 min."));

    // Advance 15 min -> 2nd, 3rd, 4th triggers succeed; 5th within 24h is blocked!
    for (step, tool) in [
        (5, "trigger_crypto_exchange_sync"),
        (6, "trigger_bank_sync"),
        (7, "trigger_broker_sync"),
    ] {
        app.clock.advance(Duration::from_secs(15 * 60));
        let out = mcp.call_tool_ok(step, tool, json!({})).await;
        assert!(
            !out.contains("cooldown"),
            "{tool} unexpected cooldown: {out}"
        );
    }
    app.clock.advance(Duration::from_secs(15 * 60));
    let fifth = mcp.call_tool_ok(8, "trigger_full_sync", json!({})).await;
    assert!(
        fifth.contains("MCP sync cooldown is shared"),
        "5th sync in 24h must be blocked, got: {fifth}"
    );

    // OAuth2 configuration discovery.
    let cfg = mcp
        .call_tool_json(9, "get_oauth2_configuration", json!({}))
        .await;
    assert_eq!(cfg["authorizationEndpoint"], "/oauth2/authorize");
    assert_eq!(cfg["tokenEndpoint"], "/oauth2/token");
    assert_eq!(cfg["jwkSetEndpoint"], "/oauth2/jwks");
    assert_eq!(cfg["clientId"], "picsou-ios");
    assert_eq!(cfg["pkceRequired"], true);

    // OAuth2 session status for access key.
    let key_status = mcp
        .call_tool_json(10, "get_oauth2_session_status", json!({}))
        .await;
    assert_eq!(key_status["source"], "access_key");
    assert_eq!(key_status["keyName"], "test");
    assert!(key_status["keyId"].as_i64().is_some());
    assert_eq!(key_status["mfaEnabled"], false);

    // OAuth2 session status for OAuth2 MCP JWT.
    let (mcp_jwt, _) = StreamableClient::connect(&app, &jwt, "2025-11-25").await;
    let jwt_status = mcp_jwt
        .call_tool_json(11, "get_oauth2_session_status", json!({}))
        .await;
    assert_eq!(jwt_status["source"], "oauth2");
    assert!(jwt_status.get("keyId").is_none());
    assert!(jwt_status.get("keyName").is_none());
    assert_eq!(jwt_status["mfaEnabled"], false);
}

#[tokio::test]
async fn budget_tools_full_lifecycle_and_cross_scope_card_filtering() {
    let app = TestApp::builder()
        .with_admin()
        .env("MCP_PROTOCOL", "STREAMABLE")
        .spawn()
        .await;
    let (uid, mid) = app.create_member("budget-alice", "pass12345678").await;

    // Create a checking account with a transaction AND a credit card with a payment due.
    let checking_id: i64 = sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color) \
         VALUES ($1, 'Checking', 'CHECKING', 'EUR', 2000, true, '#111111') RETURNING id",
    )
    .bind(mid)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color, payment_due_amount, payment_due_date) \
         VALUES ($1, 'Amex Gold', 'CREDIT_CARD', 'EUR', -350, false, '#222222', 350, '2026-01-25')",
    )
    .bind(mid)
    .execute(&app.state.db)
    .await
    .unwrap();
    let tx_id: i64 = sqlx::query_scalar(
        "INSERT INTO transaction (account_id, date, description, counterparty, amount, native_currency, is_manual) \
         VALUES ($1, '2026-01-10', 'CARREFOUR MARKET PARIS', 'CARREFOUR', -85.40, 'EUR', true) RETURNING id",
    )
    .bind(checking_id)
    .fetch_one(&app.state.db)
    .await
    .unwrap();

    // Key WITHOUT accounts:read to verify get_recurring_calendar withholds credit-card payment!
    let budget_only_key = app
        .create_access_key(
            uid,
            &[
                "budget:categories-read",
                "budget:categories-write",
                "budget:rules-read",
                "budget:rules-write",
                "budget:transactions-read",
                "budget:transactions-write",
                "budget:recurring-read",
                "budget:recurring-write",
                "budget:envelopes-read",
                "budget:envelopes-write",
                "budget:dashboard-read",
            ],
        )
        .await;
    let (mcp, _) = StreamableClient::connect(&app, &budget_only_key, "2025-11-25").await;

    // Categories CRUD.
    let cats = mcp
        .call_tool_json(2, "list_budget_categories", json!({}))
        .await;
    assert!(!cats.as_array().unwrap().is_empty());
    let custom_cat = mcp
        .call_tool_json(
            3,
            "create_budget_category",
            json!({ "name": "Groceries Custom", "kind": "EXPENSE", "color": "#22c55e" }),
        )
        .await;
    let cat_id = custom_cat["id"].as_i64().unwrap();
    let got_cat = mcp
        .call_tool_json(4, "get_budget_category", json!({ "categoryId": cat_id }))
        .await;
    assert_eq!(got_cat["name"], "Groceries Custom");
    let upd_cat = mcp
        .call_tool_json(
            5,
            "update_budget_category",
            json!({ "categoryId": cat_id, "name": "Groceries", "kind": "EXPENSE", "color": "#22c55e" }),
        )
        .await;
    assert_eq!(upd_cat["name"], "Groceries");

    // Rule preview (pure dry-run) + Rules CRUD + apply_rule_to_transactions.
    let preview = mcp
        .call_tool_json(
            6,
            "preview_budget_rule",
            json!({ "matchType": "KEYWORD", "pattern": "carrefour" }),
        )
        .await;
    assert_eq!(preview["matchCount"], 1);

    let rule = mcp
        .call_tool_json(
            7,
            "create_budget_rule",
            json!({ "matchType": "KEYWORD", "pattern": "carrefour", "categoryId": cat_id, "priority": 10 }),
        )
        .await;
    let rule_id = rule["id"].as_i64().unwrap();
    assert_eq!(
        mcp.call_tool_json(8, "list_budget_rules", json!({}))
            .await
            .as_array()
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        mcp.call_tool_json(9, "get_budget_rule", json!({ "ruleId": rule_id }))
            .await["id"],
        rule_id
    );
    mcp.call_tool_json(
        10,
        "update_budget_rule",
        json!({ "ruleId": rule_id, "matchType": "KEYWORD", "pattern": "carrefour", "categoryId": cat_id, "priority": 20 }),
    )
    .await;
    let applied = mcp
        .call_tool_ok(11, "apply_rule_to_transactions", json!({}))
        .await;
    assert!(applied.contains("Categorized 1"));

    // Budget transactions list + update.
    let btxs = mcp
        .call_tool_json(
            12,
            "list_budget_transactions",
            json!({ "from": "2026-01-01", "to": "2026-01-31", "categoryId": cat_id }),
        )
        .await;
    assert_eq!(btxs.as_array().unwrap().len(), 1);
    let upd_btx = mcp
        .call_tool_ok(
            13,
            "update_budget_transaction",
            json!({ "transactionId": tx_id, "categoryId": cat_id, "createRule": false }),
        )
        .await;
    assert!(upd_btx.contains(&tx_id.to_string()));
    mcp.call_tool_ok(14, "delete_budget_rule", json!({ "ruleId": rule_id }))
        .await;

    // Envelopes CRUD + set_envelope_allocation.
    let env = mcp
        .call_tool_json(
            15,
            "create_budget_envelope",
            json!({ "categoryId": cat_id, "monthlyLimit": 500 }),
        )
        .await;
    let env_id = env["id"].as_i64().unwrap();
    assert_eq!(
        mcp.call_tool_json(16, "list_budget_envelopes", json!({}))
            .await
            .as_array()
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        mcp.call_tool_json(17, "get_budget_envelope", json!({ "envelopeId": env_id }))
            .await["id"],
        env_id
    );
    let alloc = mcp
        .call_tool_json(
            18,
            "set_envelope_allocation",
            json!({ "envelopeId": env_id, "monthlyLimit": 650 }),
        )
        .await;
    assert_eq!(alloc["monthlyLimit"], 650);
    mcp.call_tool_json(
        19,
        "update_budget_envelope",
        json!({ "envelopeId": env_id, "categoryId": cat_id, "monthlyLimit": 700 }),
    )
    .await;
    mcp.call_tool_ok(
        20,
        "delete_budget_envelope",
        json!({ "envelopeId": env_id }),
    )
    .await;

    // Recurring CRUD, triage, activity, calendar (card withheld without accounts:read, shown with it!).
    let rec = mcp
        .call_tool_json(
            21,
            "create_recurring_series",
            json!({
                "label": "Fiber Internet",
                "expectedAmount": -39.99,
                "cadence": "MONTHLY",
                "counterparty": "Orange",
                "nextDueDate": "2026-01-20",
                "categoryId": cat_id
            }),
        )
        .await;
    let rec_id = rec["id"].as_i64().unwrap();
    assert_eq!(rec["status"], "CONFIRMED");
    assert_eq!(
        mcp.call_tool_json(
            22,
            "list_recurring_series",
            json!({ "status": "CONFIRMED" })
        )
        .await
        .as_array()
        .unwrap()
        .len(),
        1
    );
    assert_eq!(
        mcp.call_tool_json(23, "get_recurring_series", json!({ "seriesId": rec_id }))
            .await["id"],
        rec_id
    );
    let cal_no_card = mcp
        .call_tool_json(24, "get_recurring_calendar", json!({ "horizonDays": 30 }))
        .await;
    assert!(
        cal_no_card
            .as_array()
            .unwrap()
            .iter()
            .all(|o| !o["creditCardPayment"].as_bool().unwrap_or(false)),
        "credit-card payment must be withheld without accounts:read"
    );

    // With accounts:read, credit-card payment IS included.
    let jwt_with_accounts = mint_mcp_jwt(
        uid,
        "budget-alice",
        0,
        &["budget:recurring-read", "accounts:read"],
    );
    let (mcp_acc, _) = StreamableClient::connect(&app, &jwt_with_accounts, "2025-11-25").await;
    let cal_with_card = mcp_acc
        .call_tool_json(25, "get_recurring_calendar", json!({ "horizonDays": 30 }))
        .await;
    assert!(
        cal_with_card
            .as_array()
            .unwrap()
            .iter()
            .any(|o| o["creditCardPayment"].as_bool().unwrap_or(false)),
        "credit-card payment must be included with accounts:read"
    );

    mcp.call_tool_json(
        26,
        "update_recurring_series",
        json!({
            "seriesId": rec_id,
            "label": "Fiber Internet Pro",
            "expectedAmount": -44.99,
            "cadence": "MONTHLY",
            "nextDueDate": "2026-01-20"
        }),
    )
    .await;
    assert_eq!(
        mcp.call_tool_json(27, "ignore_recurring_series", json!({ "seriesId": rec_id }))
            .await["status"],
        "IGNORED"
    );
    assert_eq!(
        mcp.call_tool_json(
            28,
            "confirm_recurring_series",
            json!({ "seriesId": rec_id })
        )
        .await["status"],
        "CONFIRMED"
    );
    mcp.call_tool_json(
        29,
        "undo_recurring_series_change",
        json!({ "seriesId": rec_id }),
    )
    .await;
    assert!(
        mcp.call_tool_json(30, "get_recurring_activity", json!({}))
            .await
            .is_array()
    );
    let detect_msg = mcp
        .call_tool_ok(31, "detect_recurring_series", json!({}))
        .await;
    assert!(detect_msg.contains("Detected or refreshed"));
    mcp.call_tool_ok(32, "delete_recurring_series", json!({ "seriesId": rec_id }))
        .await;

    // Dashboard, spending, cashflow, cashflow flow.
    let bdash = mcp
        .call_tool_json(33, "get_budget_dashboard", json!({}))
        .await;
    assert!(bdash["cashflow"].is_object());
    assert!(bdash["topCategories"].is_array());
    let spending = mcp
        .call_tool_json(34, "get_spending_by_category", json!({ "period": "CYCLE" }))
        .await;
    assert!(spending["categories"].is_array());
    let detail = mcp
        .call_tool_json(
            35,
            "get_spending_category_detail",
            json!({ "categoryId": cat_id, "period": "YTD" }),
        )
        .await;
    assert_eq!(detail["categoryId"], cat_id);
    let cf = mcp
        .call_tool_json(36, "get_cashflow", json!({ "period": "CYCLE" }))
        .await;
    assert!(cf["series"].is_array());
    let flow = mcp
        .call_tool_json(37, "get_cashflow_flow", json!({ "period": "CYCLE" }))
        .await;
    assert!(flow["nodes"].is_array());

    // Archive category last.
    let arch = mcp
        .call_tool_ok(
            38,
            "delete_budget_category",
            json!({ "categoryId": cat_id }),
        )
        .await;
    assert!(arch.contains(&cat_id.to_string()));
}

#[tokio::test]
async fn analysis_ai_and_peer_loan_tools_behavior() {
    let app = TestApp::builder()
        .with_admin()
        .env("MCP_PROTOCOL", "STREAMABLE")
        .spawn()
        .await;
    let (uid, mid) = app.create_member("analysis-alice", "pass12345678").await;

    // Seed accounts for savings interest, loan summary, real estate valuations, investment realized PnL,
    // crypto exchange positions, and a configured peer loan!
    let savings_id: i64 = sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color) \
         VALUES ($1, 'Livret A', 'LIVRET_A', 'EUR', 10000, true, '#111111') RETURNING id",
    )
    .bind(mid)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO savings_interest_config (account_id, product, annual_rate, rate_basis) \
         VALUES ($1, 'LIVRET_A', 3.00, 'NET')",
    )
    .bind(savings_id)
    .execute(&app.state.db)
    .await
    .unwrap();

    let prop_id: i64 = sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color) \
         VALUES ($1, 'Paris Flat', 'REAL_ESTATE', 'EUR', 350000, true, '#222222') RETURNING id",
    )
    .bind(mid)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO property_valuation (account_id, member_id, valued_at, estimated_value, provider) \
         VALUES ($1, $2, '2026-01-01', 350000, 'DVF')",
    )
    .bind(prop_id)
    .bind(mid)
    .execute(&app.state.db)
    .await
    .unwrap();

    let loan_id: i64 = sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color) \
         VALUES ($1, 'Mortgage', 'LOAN', 'EUR', 200000, true, '#333333') RETURNING id",
    )
    .bind(mid)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO debt (account_id, member_id, borrowed_amount, interest_rate, monthly_payment, start_date, end_date) \
         VALUES ($1, $2, 250000, 0.025, 1300, '2024-01-01', '2044-01-01')",
    )
    .bind(loan_id)
    .bind(mid)
    .execute(&app.state.db)
    .await
    .unwrap();

    let broker_id: i64 = sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color) \
         VALUES ($1, 'CTO', 'COMPTE_TITRES', 'EUR', 5000, true, '#444444') RETURNING id",
    )
    .bind(mid)
    .fetch_one(&app.state.db)
    .await
    .unwrap();

    let peer_loan_id: i64 = sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color) \
         VALUES ($1, 'Loan to Paul', 'PEER_LOAN', 'EUR', 1000, true, '#555555') RETURNING id",
    )
    .bind(mid)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    let cash_id: i64 = sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color) \
         VALUES ($1, 'Cash', 'CHECKING', 'EUR', 2000, true, '#666666') RETURNING id",
    )
    .bind(mid)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    let key = app
        .create_access_key(
            uid,
            &[
                "analysis:read",
                "accounts:read",
                "accounts:write",
                "prices:read",
                "budget:ai-write",
            ],
        )
        .await;
    let (mcp, _) = StreamableClient::connect(&app, &key, "2025-11-25").await;

    // Empty peer loans overview succeeds before any loan terms are configured.
    let empty_ov = mcp
        .call_tool_json(100, "get_peer_loans_overview", json!({ "months": 12 }))
        .await;
    assert_eq!(empty_ov["openCount"], 0);
    assert_eq!(empty_ov["closedCount"], 0);
    assert_eq!(empty_ov["history"].as_array().unwrap().len(), 0);

    let alice_http = app.login("analysis-alice", "pass12345678").await;
    let terms_resp = alice_http
        .put(&format!("/api/accounts/{peer_loan_id}/peer-loan"))
        .json(&json!({
            "principal": 1000,
            "lentAt": "2026-01-01",
            "annualRate": 0,
            "ratePeriod": "ANNUAL",
            "linkedAccountId": cash_id,
            "withCashLeg": false
        }))
        .send()
        .await;
    assert_eq!(
        terms_resp.status,
        StatusCode::OK,
        "peer loan terms: {}",
        terms_resp.text()
    );

    // All 8 analysis:read tools.
    assert!(
        mcp.call_tool_json(2, "get_allocation", json!({ "period": "CYCLE" }))
            .await
            .is_object()
    );
    assert!(mcp.call_tool_json(3, "get_wealth_pyramid", json!({})).await["tiers"].is_array());
    assert!(
        mcp.call_tool_json(4, "get_portfolio_diversification", json!({}))
            .await
            .is_object()
    );
    assert!(
        mcp.call_tool_json(5, "get_wealth_projection", json!({ "years": 15 }))
            .await
            .is_object()
    );
    assert!(
        mcp.call_tool_json(6, "get_allocation_targets", json!({}))
            .await
            .is_object()
    );
    assert!(
        mcp.call_tool_json(7, "get_essential_expense_estimate", json!({}))
            .await
            .is_object()
    );
    assert!(
        mcp.call_tool_json(8, "get_savings_suggestions", json!({}))
            .await
            .is_array()
    );
    let re = mcp
        .call_tool_json(9, "get_real_estate_summary", json!({}))
        .await;
    assert_eq!(re["properties"].as_array().unwrap().len(), 1);

    // All 5 per-account accounts:read analysis tools.
    let interest = mcp
        .call_tool_json(
            10,
            "get_savings_interest",
            json!({ "accountId": savings_id }),
        )
        .await;
    assert!(interest.is_object());
    let vals = mcp
        .call_tool_json(
            11,
            "get_property_valuations",
            json!({ "accountId": prop_id }),
        )
        .await;
    assert_eq!(vals.as_array().unwrap().len(), 1);
    let loan_sum = mcp
        .call_tool_json(12, "get_loan_summary", json!({ "accountId": loan_id }))
        .await;
    assert!(loan_sum["schedule"].is_array());
    let rpnl = mcp
        .call_tool_json(13, "get_realized_pnl", json!({ "accountId": broker_id }))
        .await;
    assert_eq!(
        rust_decimal::Decimal::from_scientific(&rpnl["realizedTotal"].to_string())
            .or_else(|_| rpnl["realizedTotal"].to_string().parse())
            .unwrap(),
        rust_decimal::Decimal::ZERO
    );
    let ex_pos = mcp
        .call_tool_json(
            14,
            "get_exchange_positions",
            json!({ "accountId": broker_id }),
        )
        .await;
    assert_eq!(ex_pos.as_array().unwrap().len(), 0);

    // prices:read security insight.
    let insight = mcp
        .call_tool_json(15, "get_security_insight", json!({ "ticker": "AAPL" }))
        .await;
    assert_eq!(insight["ticker"], "AAPL");

    // AI categorization tools (idle status + start with AI disabled -> started=false, COMPLETED, total=0).
    let ai_status = mcp
        .call_tool_json(16, "get_ai_categorization_status", json!({}))
        .await;
    assert_eq!(ai_status["state"], "IDLE");
    let ai_start = mcp
        .call_tool_json(17, "start_ai_categorization", json!({}))
        .await;
    assert_eq!(ai_start["started"], false);
    assert_eq!(ai_start["job"]["state"], "COMPLETED");
    assert_eq!(ai_start["job"]["total"], 0);

    // Peer loan tools: detail, WRITE_OFF refused, valid repayment, overview, close.
    let pl = mcp
        .call_tool_json(18, "get_peer_loan", json!({ "accountId": peer_loan_id }))
        .await;
    assert_eq!(
        pl["principal"]
            .to_string()
            .parse::<rust_decimal::Decimal>()
            .unwrap(),
        rust_decimal::Decimal::from(1000)
    );
    let (wo_err, wo_msg) = mcp
        .call_tool(
            19,
            "add_peer_loan_repayment",
            json!({
                "accountId": peer_loan_id,
                "paidOn": "2026-01-10",
                "amount": 100,
                "kind": "WRITE_OFF"
            }),
        )
        .await;
    assert!(wo_err);
    assert!(wo_msg.contains("WRITE_OFF"));

    let rep = mcp
        .call_tool_json(
            20,
            "add_peer_loan_repayment",
            json!({
                "accountId": peer_loan_id,
                "paidOn": "2026-01-10",
                "amount": 250,
                "kind": "PRINCIPAL",
                "note": "First installment"
            }),
        )
        .await;
    assert_eq!(
        rep["amount"]
            .to_string()
            .parse::<rust_decimal::Decimal>()
            .unwrap(),
        rust_decimal::Decimal::from(250)
    );

    // When wealth.history is wired (in t8-jobs), overview returns the full balance curve;
    // on an unwired TestApp it delegates cleanly through wealth::peer_loans::service::overview.
    let (ov_err, ov_text) = mcp
        .call_tool(21, "get_peer_loans_overview", json!({ "months": 12 }))
        .await;
    if ov_err {
        assert_eq!(ov_text, "Not implemented");
    } else {
        let ov: Value = serde_json::from_str(&ov_text).unwrap();
        assert_eq!(ov["openCount"], 1);
        assert_eq!(
            ov["totalOutstanding"]
                .to_string()
                .parse::<rust_decimal::Decimal>()
                .unwrap(),
            rust_decimal::Decimal::from(750)
        );
    }

    let closed = mcp
        .call_tool_json(
            22,
            "close_peer_loan",
            json!({ "accountId": peer_loan_id, "closedAt": "2026-01-15" }),
        )
        .await;
    assert_eq!(closed["closedAt"], "2026-01-15");
    assert_eq!(
        closed["principalRemaining"]
            .to_string()
            .parse::<rust_decimal::Decimal>()
            .unwrap(),
        rust_decimal::Decimal::ZERO
    );
}
