//! Fixtures shared by the connector tests: an app whose sidecar URL points at a wiremock server,
//! the members, and reads of the session row a sync leaves behind.

use picsou::connectors::sidecar::session::{self, SidecarSession};
use picsou::ledger::sidecar::SidecarBank;
use picsou::testing::{ADMIN_USERNAME, Client, TestApp};
use rust_decimal::Decimal;
use wiremock::MockServer;

pub fn d(value: &str) -> Decimal {
    value.parse().expect("decimal literal")
}

/// A set-up app whose `url_var` sidecar (BOURSE_DIRECT_AUTH_URL, ...) is the wiremock server.
pub async fn app_with_sidecar(url_var: &str, sidecar: &MockServer) -> TestApp {
    TestApp::builder()
        .with_admin()
        .env(url_var, sidecar.uri())
        .spawn()
        .await
}

/// The signed-in admin and their member id.
pub async fn admin(app: &TestApp) -> (Client, i64) {
    let client = app.login_admin().await;
    let member_id = picsou::db::query_scalar("SELECT member_id FROM app_user WHERE username = $1")
        .bind(ADMIN_USERNAME)
        .fetch_one(&app.state.db)
        .await
        .expect("admin member");
    (client, member_id)
}

/// A second member with a login, signed in.
pub async fn member(app: &TestApp, username: &str) -> (Client, i64) {
    let (_, member_id) = app.create_member(username, "password-1234").await;
    (app.login(username, "password-1234").await, member_id)
}

/// Waits until every queued sync job has run.
pub async fn settle(app: &TestApp) {
    app.state.connectors.jobs.wait_idle().await;
}

pub async fn session_of(
    app: &TestApp,
    bank: SidecarBank,
    member_id: i64,
) -> Option<SidecarSession> {
    session::find(&app.state.db, bank, member_id)
        .await
        .expect("session read")
}

/// Stores an active, idle session as a completed login leaves it, without calling the sidecar.
pub async fn store_session(
    app: &TestApp,
    bank: SidecarBank,
    member_id: i64,
    plain_state: &str,
) -> i64 {
    let encrypted = app.state.crypto.encrypt(plain_state).expect("encrypt");
    picsou::db::query_scalar(
        "INSERT INTO sidecar_session (bank, member_id, session_state, last_validated_at, is_active, sync_status) \
         VALUES ($1, $2, $3, now(), true, 'IDLE') RETURNING id",
    )
    .bind(bank.as_str())
    .bind(member_id)
    .bind(encrypted)
    .fetch_one(&app.state.db)
    .await
    .expect("session")
}

/// A JSON number from its decimal text, so test payloads never go through a float.
pub fn n(number: &str) -> serde_json::Value {
    serde_json::from_str(number).expect("JSON number")
}

/// Mounts a 200 JSON answer on POST `route`.
pub async fn answer(sidecar: &wiremock::MockServer, route: &str, body: serde_json::Value) {
    wiremock::Mock::given(wiremock::matchers::method("POST"))
        .and(wiremock::matchers::path(route))
        .respond_with(wiremock::ResponseTemplate::new(200).set_body_json(body))
        .mount(sidecar)
        .await;
}

/// Mounts an error answer on POST `route`.
pub async fn fail(
    sidecar: &wiremock::MockServer,
    route: &str,
    status: u16,
    body: serde_json::Value,
) {
    wiremock::Mock::given(wiremock::matchers::method("POST"))
        .and(wiremock::matchers::path(route))
        .respond_with(wiremock::ResponseTemplate::new(status).set_body_json(body))
        .mount(sidecar)
        .await;
}

/// Stores a session for the admin, POSTs `sync_path`, waits for the job and returns the status.
pub async fn sync_stored(
    app: &TestApp,
    client: &Client,
    bank: SidecarBank,
    member_id: i64,
    base: &str,
) -> serde_json::Value {
    store_session(app, bank, member_id, "plain-state").await;
    let queued = client.post(&format!("{base}/sync")).send().await;
    assert_eq!(queued.status, 202, "{}", queued.text());
    settle(app).await;
    client.get(&format!("{base}/status")).send().await.json()
}

/// Rows of one query, decoded as text tuples for compact assertions.
pub async fn count(app: &TestApp, sql: &str) -> i64 {
    picsou::db::query_scalar(sql)
        .fetch_one(&app.state.db)
        .await
        .expect("count")
}
