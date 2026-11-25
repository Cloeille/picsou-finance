//! A test app wired to a wiremock Enable Banking, signing with a key generated once per run.

use std::sync::OnceLock;

use picsou::sync::enable_banking::requisitions::{self, NewRequisition, RequisitionStatus};
use picsou::testing::{ADMIN_USERNAME, Client, TestApp};
use rsa::pkcs8::{EncodePrivateKey, EncodePublicKey, LineEnding};
use serde_json::{Value, json};
use wiremock::matchers::{method, path, path_regex};
use wiremock::{Mock, MockServer, ResponseTemplate};

pub const APP_ID: &str = "app-0001";

pub struct Keys {
    pub private_pem: String,
    pub public_pem: String,
}

pub fn keys() -> &'static Keys {
    static KEYS: OnceLock<Keys> = OnceLock::new();
    KEYS.get_or_init(|| {
        let key = rsa::RsaPrivateKey::new(&mut rsa::rand_core::OsRng, 2048).expect("rsa key");
        Keys {
            private_pem: key.to_pkcs8_pem(LineEnding::LF).expect("pem").to_string(),
            public_pem: rsa::RsaPublicKey::from(&key)
                .to_public_key_pem(LineEnding::LF)
                .expect("public pem"),
        }
    })
}

pub struct Bank {
    pub app: TestApp,
    pub server: MockServer,
    pub admin: Client,
    pub member_id: i64,
}

/// The admin signed in, Enable Banking configured through the environment.
pub async fn bank() -> Bank {
    let server = MockServer::start().await;
    let app = TestApp::builder()
        .with_admin()
        .env("ENABLEBANKING_BASE_URL", server.uri())
        .env("ENABLEBANKING_APPLICATION_ID", APP_ID)
        .env("ENABLEBANKING_PRIVATE_KEY", keys().private_pem.clone())
        .env(
            "ENABLEBANKING_PRIVATE_KEY_PATH",
            "/nonexistent/picsou/eb.pem",
        )
        .env(
            "ENABLEBANKING_REDIRECT_URI",
            "https://picsou.test/sync/callback",
        )
        .spawn()
        .await;
    let admin = app.login_admin().await;
    let member_id = admin_member(&app).await;
    Bank {
        app,
        server,
        admin,
        member_id,
    }
}

pub async fn admin_member(app: &TestApp) -> i64 {
    picsou::auth::users::find_by_username(&app.state.db, ADMIN_USERNAME)
        .await
        .unwrap()
        .unwrap()
        .member_id
}

pub async fn requisition(
    app: &TestApp,
    member_id: i64,
    session: &str,
    institution_id: &str,
    status: RequisitionStatus,
    oauth_state: Option<&str>,
) -> i64 {
    let row = requisitions::insert(
        &app.state.db,
        &NewRequisition {
            member_id,
            requisition_id: session,
            institution_id,
            institution_name: institution_id.split("::").next().unwrap(),
            logo_url: None,
            auth_link: "https://bank.test/auth",
            oauth_state: oauth_state.unwrap_or(session),
        },
        app.state.clock.now(),
    )
    .await
    .unwrap();
    picsou::db::query("UPDATE requisition SET status = $2, oauth_state = $3 WHERE id = $1")
        .bind(row.id)
        .bind(status)
        .bind(oauth_state)
        .execute(&app.state.db)
        .await
        .unwrap();
    row.id
}

pub async fn status_of(app: &TestApp, id: i64) -> (String, Option<String>, String) {
    picsou::db::query_as(
        "SELECT status::text, oauth_state, requisition_id FROM requisition WHERE id = $1",
    )
    .bind(id)
    .fetch_one(&app.state.db)
    .await
    .unwrap()
}

/// An empty catalog, so the logo lookups find nothing.
pub async fn empty_catalog(server: &MockServer) {
    Mock::given(method("GET"))
        .and(path("/aspsps"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"aspsps": []})))
        .mount(server)
        .await;
}

/// A session with one account and its details, balances and transactions.
pub async fn session(server: &MockServer, session_id: &str, uid: &str, iban: &str, amount: &str) {
    Mock::given(method("GET"))
        .and(path(format!("/sessions/{session_id}")))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "session_id": session_id, "status": "AUTHORIZED", "accounts": [uid], "aspsp": {"name": "Banque Test"}
        })))
        .mount(server)
        .await;
    account(server, uid, "Compte courant", iban, amount).await;
}

pub async fn account(server: &MockServer, uid: &str, label: &str, iban: &str, amount: &str) {
    Mock::given(method("GET"))
        .and(path(format!("/accounts/{uid}/details")))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "account_id": {"iban": iban}, "details": label, "currency": "EUR"
        })))
        .mount(server)
        .await;
    Mock::given(method("GET"))
        .and(path(format!("/accounts/{uid}/balances")))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "balances": [
                {"name": label, "balance_amount": {"amount": amount, "currency": "EUR"}, "balance_type": "CLBD"},
                {"name": label, "balance_amount": {"amount": "1.00", "currency": "EUR"}, "balance_type": "ITAV"}
            ]
        })))
        .mount(server)
        .await;
}

pub async fn no_transactions(server: &MockServer) {
    Mock::given(method("GET"))
        .and(path_regex(r"^/accounts/[^/]+/transactions$"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"transactions": []})))
        .mount(server)
        .await;
}

pub fn detail(body: &Value) -> &str {
    body["detail"].as_str().unwrap_or_default()
}
