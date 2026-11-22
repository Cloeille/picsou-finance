//! Wise jars: /api/wise over HTTP, Wise stubbed with wiremock (WISE_API_URL).

use std::time::Duration;

use picsou::ledger::sidecar::SyncStatus;
use picsou::sync::wise;
use picsou::testing::{Client, TestApp};
use reqwest::StatusCode;
use serde_json::{Value, json};
use wiremock::matchers::{header, method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

const TOKEN: &str = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
const PASSWORD: &str = "password-1234";

/// Shape of a live GET /v4/profiles/{id}/balances answer, values changed.
const PERSONAL_JARS: &str = r#"[
  {"id": 101, "currency": "EUR", "type": "STANDARD", "name": null, "investmentState": "NOT_INVESTED",
   "amount": {"value": 3399.46, "currency": "EUR"}, "totalWorth": {"value": 3399.46, "currency": "EUR"},
   "visible": true, "primary": true},
  {"id": 102, "currency": "EUR", "type": "SAVINGS", "name": "Coup dur", "investmentState": "NOT_INVESTED",
   "amount": {"value": 2002.08, "currency": "EUR"}, "cashAmount": {"value": 2002.08, "currency": "EUR"},
   "totalWorth": {"value": 2002.08, "currency": "EUR"}, "visible": true, "primary": false},
  {"id": 103, "currency": "usd", "type": "SAVINGS", "name": "Interest", "investmentState": "INVESTED",
   "amount": {"value": 10.00, "currency": "USD"}, "cashAmount": {"value": 0, "currency": "USD"},
   "totalWorth": {"value": 512.34, "currency": "USD"}, "visible": true, "primary": false}
]"#;

struct Wise {
    app: TestApp,
    server: MockServer,
}

impl Wise {
    async fn start() -> Self {
        let server = MockServer::start().await;
        let app = TestApp::builder()
            .env("WISE_API_URL", server.uri())
            .with_admin()
            .spawn()
            .await;
        Self { app, server }
    }

    /// A member with a login: (member id, signed-in client).
    async fn member(&self, name: &str) -> (i64, Client) {
        let (_, member_id) = self.app.create_member(name, PASSWORD).await;
        (member_id, self.app.login(name, PASSWORD).await)
    }

    async fn answer(&self, url_path: &str, status: u16, body: &str) {
        Mock::given(method("GET"))
            .and(path(url_path))
            .respond_with(
                ResponseTemplate::new(status).set_body_raw(body.to_owned(), "application/json"),
            )
            .mount(&self.server)
            .await;
    }

    /// Profiles 11 (personal) and the given balances on it, both only for the bearer token.
    async fn jars(&self, token: &str, balances: &str) {
        Mock::given(method("GET"))
            .and(path("/v2/profiles"))
            .and(header("authorization", format!("Bearer {token}").as_str()))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_raw(r#"[{"id": 11, "type": "PERSONAL"}]"#, "application/json"),
            )
            .mount(&self.server)
            .await;
        Mock::given(method("GET"))
            .and(path("/v4/profiles/11/balances"))
            .and(query_param("types", "SAVINGS"))
            .and(header("authorization", format!("Bearer {token}").as_str()))
            .respond_with(
                ResponseTemplate::new(200).set_body_raw(balances.to_owned(), "application/json"),
            )
            .mount(&self.server)
            .await;
    }

    async fn requests(&self) -> usize {
        self.server
            .received_requests()
            .await
            .unwrap_or_default()
            .len()
    }

    async fn connection(&self, member_id: i64) -> Option<(i64, String, String)> {
        picsou::db::query_as(
            "SELECT id, api_token, status FROM wise_connection WHERE member_id = $1",
        )
        .bind(member_id)
        .fetch_optional(&self.app.state.db)
        .await
        .expect("wise_connection")
    }

    async fn status(&self, member_id: i64) -> String {
        self.connection(member_id).await.expect("a connection").2
    }

    async fn jar_accounts(&self, member_id: i64) -> Vec<(String, String, String)> {
        picsou::db::query_as(
            "SELECT external_account_id, name, type::text FROM account WHERE member_id = $1 \
             AND deleted_at IS NULL ORDER BY external_account_id",
        )
        .bind(member_id)
        .fetch_all(&self.app.state.db)
        .await
        .expect("accounts")
    }

    fn pass_the_rate_limit(&self) {
        self.app.clock.advance(Duration::from_secs(61));
    }
}

async fn connect(client: &Client, token: &str) -> picsou::testing::Response {
    client
        .post("/api/wise/connect")
        .json(&json!({ "token": token }))
        .send()
        .await
}

/// Profiles body and status, balances body and status (0: not requested), expected code, detail.
type FailureCase<'a> = (&'a str, u16, &'a str, u16, Option<&'a str>, &'a str);

fn number(value: &Value) -> String {
    assert!(value.is_number(), "{value} is a JSON number");
    value.to_string()
}

fn names(body: &Value) -> Vec<&str> {
    body.as_array()
        .expect("an account list")
        .iter()
        .map(|a| a["name"].as_str().expect("name"))
        .collect()
}

#[tokio::test]
async fn status_without_a_connection_says_only_not_connected() {
    let wise = Wise::start().await;
    let (_, client) = wise.member("alice").await;

    let response = client.get("/api/wise/status").send().await;

    assert_eq!(response.status, StatusCode::OK);
    assert_eq!(response.json(), json!({ "connected": false }));
}

#[tokio::test]
async fn connect_stores_the_token_encrypted_and_imports_every_profiles_jars() {
    let wise = Wise::start().await;
    let (member_id, client) = wise.member("alice").await;
    Mock::given(method("GET"))
        .and(path("/v2/profiles"))
        .and(header("authorization", format!("Bearer {TOKEN}").as_str()))
        .and(header("accept", "application/json"))
        .respond_with(ResponseTemplate::new(200).set_body_raw(
            r#"[{"id": 11, "type": "PERSONAL"}, {"id": 22, "type": "BUSINESS"}]"#,
            "application/json",
        ))
        .expect(1)
        .mount(&wise.server)
        .await;
    wise.answer("/v4/profiles/11/balances", 200, PERSONAL_JARS)
        .await;
    wise.answer(
        "/v4/profiles/22/balances",
        200,
        r#"[{"id": 201, "currency": "GBP", "type": "SAVINGS", "name": "Tax", "totalWorth": {"value": 7, "currency": "GBP"}}]"#,
    )
    .await;

    let response = connect(&client, &format!(" {TOKEN}\n")).await;

    assert_eq!(response.status, StatusCode::OK, "{}", response.text());
    assert!(!response.text().contains(TOKEN));
    let body = response.json();
    assert_eq!(names(&body), ["Coup dur", "Interest", "Tax (Business)"]);
    let jar = &body[0];
    assert_eq!(jar["type"], "SAVINGS");
    assert_eq!(jar["provider"], "Wise Jars");
    assert_eq!(jar["currency"], "EUR");
    assert_eq!(number(&jar["currentBalance"]), "2002.08");
    assert_eq!(number(&jar["currentBalanceEur"]), "2002.08");
    assert_eq!(jar["isManual"], false);
    assert_eq!(jar["color"], "#9fe870");
    assert_eq!(jar["hidden"], false);
    assert_eq!(jar["listCollapsed"], false);
    assert_eq!(jar["lastSyncedAt"], "2026-01-15T10:00:00Z");
    assert!(jar.get("isOwner").is_none() && jar.get("sharePercent").is_none());
    assert_eq!(body[1]["currency"], "USD");
    assert_eq!(number(&body[1]["currentBalance"]), "512.34");
    assert_eq!(body[2]["currency"], "GBP");

    let requests = wise.server.received_requests().await.expect("recorded");
    assert_eq!(requests.len(), 3);
    assert!(requests.iter().all(|r| !r.url.as_str().contains(TOKEN)));
    assert!(requests.iter().all(
        |r| r.headers.get("authorization").and_then(|v| v.to_str().ok())
            == Some(format!("Bearer {TOKEN}").as_str())
    ));

    let (connection_id, stored, status) = wise.connection(member_id).await.expect("stored");
    assert_eq!(status, "CONNECTED");
    assert_ne!(stored, TOKEN);
    assert_eq!(
        wise.app.state.crypto.decrypt(&stored).expect("decrypts"),
        TOKEN
    );
    assert_eq!(
        wise.jar_accounts(member_id).await,
        [
            ("wise_jar_102".into(), "Coup dur".into(), "SAVINGS".into()),
            ("wise_jar_103".into(), "Interest".into(), "SAVINGS".into()),
            (
                "wise_jar_201".into(),
                "Tax (Business)".into(),
                "SAVINGS".into()
            ),
        ]
    );
    let snapshot: (String,) = picsou::db::query_as(
        "SELECT s.balance::text FROM balance_snapshot s JOIN account a ON a.id = s.account_id \
         WHERE a.external_account_id = 'wise_jar_102' AND s.date = $1",
    )
    .bind(wise.app.state.clock.today())
    .fetch_one(&wise.app.state.db)
    .await
    .expect("today's snapshot");
    assert_eq!(
        snapshot
            .0
            .parse::<rust_decimal::Decimal>()
            .unwrap()
            .normalize()
            .to_string(),
        "2002.08"
    );

    let status = client.get("/api/wise/status").send().await;
    assert!(!status.text().contains(TOKEN));
    assert_eq!(
        status.json(),
        json!({
            "connected": true,
            "connectionId": connection_id,
            "status": "CONNECTED",
            "lastSyncedAt": "2026-01-15T10:00:00Z",
            "maskedToken": "\u{2022}\u{2022}\u{2022}\u{2022}e1f0",
        })
    );
}

#[tokio::test]
async fn a_refused_token_stores_nothing_and_answers_the_reauth_code() {
    let wise = Wise::start().await;
    let (member_id, client) = wise.member("alice").await;
    wise.answer("/v2/profiles", 401, r#"{"error": "invalid_token"}"#)
        .await;

    let response = connect(&client, TOKEN).await;

    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert!(!response.text().contains(TOKEN));
    let body = response.json();
    assert_eq!(body["code"], "SESSION_EXPIRED");
    assert_eq!(
        body["detail"],
        "Wise refused the API token. It may have been revoked. Create a new token in Wise and connect again."
    );
    assert_eq!(body["instance"], "/api/wise/connect");
    assert!(wise.connection(member_id).await.is_none());
    assert!(wise.jar_accounts(member_id).await.is_empty());
}

#[tokio::test]
async fn wise_failures_map_to_their_codes_and_messages() {
    let wise = Wise::start().await;
    let (member_id, client) = wise.member("alice").await;
    let profiles_ok = r#"[{"id": 11, "type": "PERSONAL"}]"#;
    let huge = format!("[{}]", "0,".repeat(130_000) + "0");
    let cases: [FailureCase; 6] = [
        (
            profiles_ok,
            200,
            "",
            403,
            Some("SESSION_EXPIRED"),
            "Wise refused to share this data with the token. Create a read-only personal token in Wise and connect again.",
        ),
        (
            "",
            429,
            "",
            0,
            Some("RATE_LIMITED"),
            "Wise is limiting requests right now. Try again in a few minutes.",
        ),
        (
            "<html>oops</html>",
            500,
            "",
            0,
            None,
            "Wise could not complete the request (HTTP 500).",
        ),
        (
            "",
            302,
            "",
            0,
            None,
            "Wise redirected the request, which Picsou refuses.",
        ),
        (
            r#"{"profiles": []}"#,
            200,
            "",
            0,
            None,
            "Wise returned a response Picsou could not read.",
        ),
        (
            &huge,
            200,
            "",
            0,
            None,
            "Wise returned a response that is too large.",
        ),
    ];
    for (profiles, profiles_status, balances, balances_status, code, detail) in cases {
        wise.server.reset().await;
        wise.pass_the_rate_limit();
        if profiles_status == 302 {
            Mock::given(method("GET"))
                .and(path("/v2/profiles"))
                .respond_with(
                    ResponseTemplate::new(302)
                        .insert_header("location", format!("{}/elsewhere", wise.server.uri())),
                )
                .mount(&wise.server)
                .await;
        } else {
            wise.answer("/v2/profiles", profiles_status, profiles).await;
        }
        if balances_status != 0 {
            wise.answer("/v4/profiles/11/balances", balances_status, balances)
                .await;
        }

        let response = connect(&client, TOKEN).await;

        assert_eq!(
            response.status,
            StatusCode::UNPROCESSABLE_ENTITY,
            "{detail}"
        );
        let body = response.json();
        assert_eq!(body["detail"], detail);
        assert_eq!(body.get("code").and_then(Value::as_str), code, "{detail}");
        assert!(!response.text().contains("oops"));
        if balances_status == 0 {
            assert_eq!(
                wise.requests().await,
                1,
                "no further request after {detail}"
            );
        }
    }
    assert!(wise.connection(member_id).await.is_none());
}

#[tokio::test]
async fn a_malformed_token_or_an_invalid_body_never_reaches_wise() {
    let wise = Wise::start().await;
    let (_, client) = wise.member("alice").await;

    for token in ["Bearer abc", "abc\r\nX-Injected: 1", "jeton avec espaces"] {
        let response = connect(&client, token).await;
        assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
        let body = response.json();
        assert_eq!(body["code"], "INVALID_TOKEN");
        assert_eq!(
            body["detail"],
            "This does not look like a Wise API token. Copy it again from Wise."
        );
    }

    let blank = connect(&client, "   ").await;
    assert_eq!(blank.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        blank.json()["errors"],
        json!({ "token": "must not be blank" })
    );
    let missing = client
        .post("/api/wise/connect")
        .json(&json!({ "memberId": 1 }))
        .send()
        .await;
    assert_eq!(
        missing.json()["errors"],
        json!({ "token": "must not be blank" })
    );
    let huge = "a".repeat(513);
    let too_long = connect(&client, &huge).await;
    assert_eq!(too_long.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        too_long.json()["errors"],
        json!({ "token": "size must be between 0 and 512" })
    );
    assert!(!too_long.text().contains(&huge));
    let unreadable = client
        .post("/api/wise/connect")
        .body("application/json", "{\"token\":")
        .send()
        .await;
    assert_eq!(unreadable.status, StatusCode::BAD_REQUEST);

    assert_eq!(wise.requests().await, 0);
}

#[tokio::test]
async fn sync_without_a_connection_is_a_sync_error() {
    let wise = Wise::start().await;
    let (_, client) = wise.member("alice").await;

    let response = client.post("/api/wise/sync").send().await;

    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    let body = response.json();
    assert_eq!(
        body["detail"],
        "No Wise connection. Connect with an API token first."
    );
    assert!(body.get("code").is_none());
    assert_eq!(wise.requests().await, 0);
}

#[tokio::test]
async fn a_resync_updates_the_balance_and_keeps_the_members_name_and_type() {
    let wise = Wise::start().await;
    let (member_id, client) = wise.member("alice").await;
    wise.jars(TOKEN, &PERSONAL_JARS.replace("\"usd\"", "\"EUR\""))
        .await;
    assert_eq!(connect(&client, TOKEN).await.status, StatusCode::OK);
    picsou::db::query("UPDATE account SET name = 'Fonds d''urgence', type = 'LIVRET_A' WHERE external_account_id = 'wise_jar_102'")
        .execute(&wise.app.state.db)
        .await
        .expect("member edit");
    wise.server.reset().await;
    wise.jars(TOKEN, &PERSONAL_JARS.replace("2002.08", "2500.00"))
        .await;
    wise.app.clock.advance(Duration::from_secs(86_400));
    // The session does not outlive a day.
    let client = wise.app.login("alice", PASSWORD).await;

    let response = client.post("/api/wise/sync").send().await;

    assert_eq!(response.status, StatusCode::OK, "{}", response.text());
    let body = response.json();
    assert_eq!(names(&body), ["Fonds d'urgence", "Interest"]);
    assert_eq!(body[0]["type"], "LIVRET_A");
    assert_eq!(number(&body[0]["currentBalance"]), "2500.00");
    assert_eq!(body[0]["lastSyncedAt"], "2026-01-16T10:00:00Z");
    assert_eq!(body[1]["currency"], "USD");
    let snapshots: i64 = picsou::db::query_scalar(
        "SELECT count(*) FROM balance_snapshot s JOIN account a ON a.id = s.account_id \
         WHERE a.external_account_id = 'wise_jar_102'",
    )
    .fetch_one(&wise.app.state.db)
    .await
    .expect("snapshots");
    assert_eq!(snapshots, 2);
    let status = client.get("/api/wise/status").send().await.json();
    assert_eq!(status["lastSyncedAt"], "2026-01-16T10:00:00Z");
    assert_eq!(wise.jar_accounts(member_id).await.len(), 2);
}

#[tokio::test]
async fn unusable_jars_are_skipped_and_a_deleted_jar_is_not_resurrected() {
    let wise = Wise::start().await;
    let (member_id, client) = wise.member("alice").await;
    let long_name = "N".repeat(150);
    wise.jars(
        TOKEN,
        &format!(
            r#"[
              {{"id": 1, "currency": "XAU", "type": "SAVINGS", "name": "Gold", "totalWorth": {{"value": 1}}}},
              {{"id": 2, "currency": "EUR", "type": "SAVINGS", "name": "Voyage", "amount": {{"value": 1.11}}}},
              {{"id": 3, "currency": "EUR", "type": "SAVINGS", "name": "Huge", "totalWorth": {{"value": 1000000000000}}}},
              {{"id": 4, "currency": "EUR", "type": "SAVINGS", "name": " ", "totalWorth": {{"value": 5}}}},
              {{"id": 5, "currency": "EUR", "type": "SAVINGS", "name": "{long_name}", "totalWorth": {{"value": 6}}}},
              {{"id": 6, "type": "SAVINGS", "name": "No currency", "totalWorth": {{"value": 1}}}},
              {{"id": 7, "currency": "EUR", "type": "SAVINGS", "name": "Text amount", "totalWorth": {{"value": "1"}}}},
              {{"currency": "EUR", "type": "SAVINGS", "name": "No id", "totalWorth": {{"value": 1}}}}
            ]"#
        ),
    )
    .await;

    let response = connect(&client, TOKEN).await;

    assert_eq!(response.status, StatusCode::OK, "{}", response.text());
    let body = response.json();
    assert_eq!(names(&body), ["Voyage", "Jar EUR", &"N".repeat(100)]);
    assert_eq!(number(&body[0]["currentBalance"]), "1.11");

    picsou::db::query(
        "UPDATE account SET deleted_at = now() WHERE external_account_id = 'wise_jar_2'",
    )
    .execute(&wise.app.state.db)
    .await
    .expect("soft delete");
    let resynced = client.post("/api/wise/sync").send().await;
    assert_eq!(resynced.status, StatusCode::OK);
    assert_eq!(names(&resynced.json()), ["Jar EUR", &"N".repeat(100)]);
    let rows: i64 = picsou::db::query_scalar(
        "SELECT count(*) FROM account WHERE external_account_id = 'wise_jar_2'",
    )
    .fetch_one(&wise.app.state.db)
    .await
    .expect("count");
    assert_eq!(rows, 1);
    assert_eq!(wise.jar_accounts(member_id).await.len(), 2);
}

#[tokio::test]
async fn a_failed_sync_marks_the_connection_and_the_reporting_entry_point_classifies_it() {
    let wise = Wise::start().await;
    let (member_id, client) = wise.member("alice").await;
    wise.jars(TOKEN, "[]").await;
    assert_eq!(connect(&client, TOKEN).await.status, StatusCode::OK);

    wise.server.reset().await;
    wise.answer("/v2/profiles", 401, "").await;
    let refused = client.post("/api/wise/sync").send().await;
    assert_eq!(refused.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(refused.json()["code"], "SESSION_EXPIRED");
    assert_eq!(wise.status(member_id).await, "REAUTH_REQUIRED");
    assert_eq!(
        client.get("/api/wise/status").send().await.json()["status"],
        "REAUTH_REQUIRED"
    );
    let reported = wise::resync_reporting(&wise.app.state, member_id).await;
    assert_eq!(reported.source, "wise");
    assert_eq!(reported.status, SyncStatus::NeedsReauth);
    assert_eq!(reported.message, "Reauthentication required");

    wise.server.reset().await;
    wise.answer("/v2/profiles", 429, "").await;
    let limited = client.post("/api/wise/sync").send().await;
    assert_eq!(limited.json()["code"], "RATE_LIMITED");
    assert_eq!(wise.status(member_id).await, "ERROR");
    let reported = wise::resync_reporting(&wise.app.state, member_id).await;
    assert_eq!(
        (reported.status, reported.message.as_str()),
        (SyncStatus::Failed, "Sync failed")
    );

    wise.server.reset().await;
    wise.jars(TOKEN, "[]").await;
    let reported = wise::resync_reporting(&wise.app.state, member_id).await;
    assert_eq!(
        (reported.status, reported.message.as_str()),
        (SyncStatus::Synced, "")
    );
    assert_eq!(wise.status(member_id).await, "CONNECTED");

    let (other, _) = wise.member("bob").await;
    let skipped = wise::resync_reporting(&wise.app.state, other).await;
    assert_eq!(
        (skipped.status, skipped.message.as_str()),
        (SyncStatus::SkippedNotConnected, "No connection")
    );
}

#[tokio::test]
async fn connecting_again_replaces_the_token_in_place_and_clears_the_error() {
    let wise = Wise::start().await;
    let (member_id, client) = wise.member("alice").await;
    wise.jars(TOKEN, "[]").await;
    assert_eq!(connect(&client, TOKEN).await.status, StatusCode::OK);
    let (connection_id, _, _) = wise.connection(member_id).await.expect("stored");
    picsou::db::query("UPDATE wise_connection SET status = 'ERROR'")
        .execute(&wise.app.state.db)
        .await
        .expect("error status");
    let short = "abcd1234";
    wise.jars(short, "[]").await;

    let response = connect(&client, short).await;

    assert_eq!(response.status, StatusCode::OK);
    assert_eq!(response.json(), json!([]));
    let (id, stored, status) = wise.connection(member_id).await.expect("stored");
    assert_eq!((id, status.as_str()), (connection_id, "CONNECTED"));
    assert_eq!(
        wise.app.state.crypto.decrypt(&stored).expect("decrypts"),
        short
    );
    let masked = client.get("/api/wise/status").send().await.json();
    assert_eq!(masked["maskedToken"], "\u{2022}\u{2022}\u{2022}\u{2022}");
}

#[tokio::test]
async fn an_undecryptable_token_shows_an_error_and_asks_to_connect_again() {
    let wise = Wise::start().await;
    let (member_id, client) = wise.member("alice").await;
    wise.jars(TOKEN, "[]").await;
    assert_eq!(connect(&client, TOKEN).await.status, StatusCode::OK);
    picsou::db::query("UPDATE wise_connection SET api_token = 'bm90IGEgY2lwaGVydGV4dA=='")
        .execute(&wise.app.state.db)
        .await
        .expect("broken token");
    let before = wise.requests().await;

    let status = client.get("/api/wise/status").send().await.json();
    assert_eq!(status["connected"], true);
    assert_eq!(status["status"], "ERROR");
    assert_eq!(status["maskedToken"], "\u{2022}\u{2022}\u{2022}\u{2022}");

    let response = client.post("/api/wise/sync").send().await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        response.json()["detail"],
        "Could not decrypt the stored Wise token. The encryption key may have changed. Connect again with a new token."
    );
    assert_eq!(wise.status(member_id).await, "ERROR");
    assert_eq!(wise.requests().await, before);
}

#[tokio::test]
async fn every_endpoint_acts_for_the_signed_in_member_only() {
    let wise = Wise::start().await;
    let (alice, alice_client) = wise.member("alice").await;
    let (bob, bob_client) = wise.member("bob").await;
    wise.jars(TOKEN, PERSONAL_JARS).await;
    assert_eq!(connect(&alice_client, TOKEN).await.status, StatusCode::OK);

    let status = bob_client
        .get(&format!("/api/wise/status?memberId={alice}"))
        .send()
        .await;
    assert_eq!(status.json(), json!({ "connected": false }));
    let sync = bob_client
        .post(&format!("/api/wise/sync?memberId={alice}"))
        .send()
        .await;
    assert_eq!(sync.status, StatusCode::UNPROCESSABLE_ENTITY);
    let cleared = bob_client
        .delete(&format!("/api/wise/connection?memberId={alice}"))
        .send()
        .await;
    assert_eq!(cleared.status, StatusCode::NO_CONTENT);
    assert!(wise.connection(alice).await.is_some());
    assert!(wise.jar_accounts(bob).await.is_empty());
    assert_eq!(
        wise::find_connection(&wise.app.state.db, alice)
            .await
            .expect("read")
            .map(|c| c.status),
        Some("CONNECTED".to_owned())
    );

    let disconnected = alice_client.delete("/api/wise/connection").send().await;
    assert_eq!(disconnected.status, StatusCode::NO_CONTENT);
    assert!(disconnected.body.is_empty());
    assert!(wise.connection(alice).await.is_none());
    assert_eq!(wise.jar_accounts(alice).await.len(), 2);
    assert_eq!(
        alice_client.get("/api/wise/status").send().await.json(),
        json!({ "connected": false })
    );
    assert!(
        !wise::delete_connection(&wise.app.state.db, alice)
            .await
            .expect("delete")
    );
}

#[tokio::test]
async fn connect_and_sync_share_one_per_ip_budget_of_six_a_minute() {
    let wise = Wise::start().await;
    let (_, client) = wise.member("alice").await;
    for _ in 0..3 {
        assert_eq!(
            connect(&client, "not a token").await.json()["code"],
            "INVALID_TOKEN"
        );
        assert_eq!(
            client.post("/api/wise/sync").send().await.status,
            StatusCode::UNPROCESSABLE_ENTITY
        );
    }

    let rejected = connect(&client, "not a token").await;

    assert_eq!(rejected.status, StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(
        rejected.header("content-type"),
        Some("application/problem+json")
    );
    let body = rejected.json();
    assert_eq!(body["title"], "Too Many Requests");
    assert_eq!(
        body["detail"],
        "Too many Wise requests. Please wait a moment before trying again."
    );
    assert_eq!(
        client.post("/api/wise/sync").send().await.status,
        StatusCode::TOO_MANY_REQUESTS
    );
    assert_eq!(
        client.get("/api/wise/status").send().await.status,
        StatusCode::OK
    );
    assert_eq!(
        client.delete("/api/wise/connection").send().await.status,
        StatusCode::NO_CONTENT
    );

    wise.app.clock.advance(Duration::from_secs(60));
    assert_eq!(
        client.post("/api/wise/sync").send().await.status,
        StatusCode::UNPROCESSABLE_ENTITY
    );
}
