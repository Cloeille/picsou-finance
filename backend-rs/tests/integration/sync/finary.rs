//! Finary API sync tests: Clerk and Finary stubbed with wiremock, synthetic data only.

use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::model::{Account, AccountType};
use picsou::testing::{Client, TestApp, epoch};
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{body_string_contains, header, method, path, path_regex, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

type TxRow = (String, String, Decimal, Option<String>, Option<String>);
type HoldingRow = (
    String,
    Option<String>,
    Decimal,
    Option<Decimal>,
    Option<Decimal>,
    Option<Decimal>,
    Option<Decimal>,
    Option<String>,
);

const PORTFOLIO: &str = "/organizations/org-1/memberships/mem-1/portfolio";

fn d(value: &str) -> Decimal {
    value.parse().expect("decimal literal")
}

struct Finary {
    server: MockServer,
    app: TestApp,
    admin: Client,
    member_id: i64,
}

async fn ok(server: &MockServer, verb: &str, route: &str, body: Value) {
    Mock::given(method(verb))
        .and(path(route))
        .respond_with(ResponseTemplate::new(200).set_body_json(body))
        .with_priority(1)
        .mount(server)
        .await;
}

impl Finary {
    /// An app whose Clerk and Finary point at one stub, with the admin signed in.
    async fn start() -> Self {
        let server = MockServer::start().await;
        let app = TestApp::builder()
            .with_admin()
            .env("FINARY_CLERK_URL", server.uri())
            .env("FINARY_API_URL", server.uri())
            .spawn()
            .await;
        let admin = app.login_admin().await;
        let member_id =
            picsou::db::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
                .fetch_one(&app.state.db)
                .await
                .expect("admin member");
        Self {
            server,
            app,
            admin,
            member_id,
        }
    }

    async fn connect(&self) {
        let r = self
            .admin
            .post("/api/finary/login")
            .json(&json!({"email": "user@example.com", "password": "synthetic pass&1"}))
            .send()
            .await;
        assert_eq!(r.status, 200, "{}", r.text());
    }

    /// Clerk up to the JWT without a second factor, and an organization.
    async fn clerk(&self, sign_in_status: &str) {
        ok(&self.server, "GET", "/v1/environment", json!({})).await;
        ok(&self.server, "GET", "/v1/client", json!({})).await;
        ok(
            &self.server,
            "POST",
            "/v1/client/sign_ins",
            json!({"response": {"id": "sia_1", "status": sign_in_status, "createdSessionId": "sess_1"}}),
        )
        .await;
        ok(
            &self.server,
            "POST",
            "/v1/client/sessions/sess_1/touch",
            json!({}),
        )
        .await;
        ok(
            &self.server,
            "POST",
            "/v1/client/sessions/sess_1/tokens",
            json!({"jwt": "jwt-1"}),
        )
        .await;
        ok(
            &self.server,
            "GET",
            "/users/me/organizations",
            json!({"result": [{"id": "org-1", "members": [
                {"id": "mem-0", "memberType": "viewer", "user": null},
                {"id": "mem-1", "memberType": "owner", "user": {"fullname": "Synthetic User"}}
            ]}]}),
        )
        .await;
    }

    /// Every category, the loans and the transactions answer empty unless a test mounted more.
    async fn empty_portfolio(&self) {
        for route in [
            r"/portfolio/[a-z_]+/accounts$",
            r"/portfolio/[a-z_]+/transactions$",
            r"^/users/me/loans$",
        ] {
            Mock::given(method("GET"))
                .and(path_regex(route))
                .respond_with(ResponseTemplate::new(200).set_body_json(json!({"result": []})))
                .with_priority(5)
                .mount(&self.server)
                .await;
        }
    }

    async fn accounts(&self, category: &str, accounts: Value) {
        ok(
            &self.server,
            "GET",
            &format!("{PORTFOLIO}/{category}/accounts"),
            json!({"result": accounts}),
        )
        .await;
    }

    async fn transactions(&self, category: &str, txs: Value) {
        Mock::given(method("GET"))
            .and(path(format!("{PORTFOLIO}/{category}/transactions")))
            .and(query_param("page", "1"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"result": txs})))
            .with_priority(1)
            .mount(&self.server)
            .await;
    }

    async fn ready(&self) {
        self.connect().await;
        self.clerk("complete").await;
        self.empty_portfolio().await;
    }

    async fn preview(&self) -> Value {
        let r = self.admin.post("/api/finary/api-sync/preview").send().await;
        assert_eq!(r.status, 200, "{}", r.text());
        r.json()
    }

    async fn execute(&self, token: &str, mappings: Value) -> (u16, Value) {
        let r = self
            .admin
            .post("/api/finary/api-sync/execute")
            .json(&json!({"syncToken": token, "mappings": mappings}))
            .send()
            .await;
        (r.status.as_u16(), r.json())
    }

    async fn account(
        &self,
        name: &str,
        account_type: AccountType,
        f: impl FnOnce(&mut NewAccount),
    ) -> Account {
        let mut draft = NewAccount::new(self.member_id, name, account_type);
        f(&mut draft);
        accounts::insert(&self.app.state.db, &draft, epoch())
            .await
            .expect("account")
    }

    async fn reload(&self, id: i64) -> Account {
        accounts::find_by_id(&self.app.state.db, id)
            .await
            .expect("query")
            .expect("account")
    }

    async fn count(&self, sql: &str) -> i64 {
        picsou::db::query_scalar(sql)
            .fetch_one(&self.app.state.db)
            .await
            .expect("count")
    }
}

fn eur_checking(id: &str, balance: &str) -> Value {
    json!({"id": id, "name": "Checking", "balance": balance.parse::<Value>().unwrap(),
           "display_balance": balance.parse::<Value>().unwrap(), "currency": {"code": "EUR"}})
}

fn usd_wallet() -> Value {
    json!({"id": "w1", "name": "Bitcoin bridge wallet", "balance": 9179.30, "display_balance": 7920.94,
           "currency": {"code": "USD", "symbol": "$"}})
}

fn create_new(id: &str, category: &str, name: &str, account_type: &str, currency: &str) -> Value {
    json!({"finaryId": id, "finaryName": name, "finaryCategory": category, "action": "CREATE_NEW",
           "newAccount": {"name": name, "type": account_type, "provider": "Finary", "currency": currency}})
}

fn map_existing(id: &str, category: &str, target: i64) -> Value {
    json!({"finaryId": id, "finaryName": id, "finaryCategory": category, "action": "MAP_EXISTING", "targetAccountId": target})
}

fn row<'a>(preview: &'a Value, finary_id: &str) -> &'a Value {
    preview["accounts"]
        .as_array()
        .unwrap()
        .iter()
        .find(|a| a["finaryId"] == finary_id)
        .expect("preview row")
}

#[tokio::test]
async fn credentials_are_encrypted_masked_and_scoped_to_the_member() {
    let f = Finary::start().await;
    let r = f.admin.get("/api/finary/status").send().await;
    assert_eq!(r.json(), json!({"connected": false}));

    f.connect().await;
    let stored: (String, String) =
        picsou::db::query_as("SELECT email, password FROM finary_session")
            .fetch_one(&f.app.state.db)
            .await
            .unwrap();
    assert_ne!(stored.0, "user@example.com");
    assert_eq!(
        f.app.state.crypto.decrypt(&stored.0).unwrap(),
        "user@example.com"
    );
    assert_eq!(
        f.app.state.crypto.decrypt(&stored.1).unwrap(),
        "synthetic pass&1"
    );
    let status = f.admin.get("/api/finary/status").send().await.json();
    assert_eq!(status["connected"], true);
    assert_eq!(status["status"], "CONNECTED");
    assert_eq!(status["maskedEmail"], "u***@example.com");
    assert!(status.get("lastSyncedAt").is_none());

    f.app.create_member("dominique", "password-1234").await;
    let member = f.app.login("dominique", "password-1234").await;
    assert_eq!(
        member.get("/api/finary/status").send().await.json(),
        json!({"connected": false})
    );
    assert_eq!(
        member.delete("/api/finary/session").send().await.status,
        204
    );
    let r = member.post("/api/finary/check-totp").send().await;
    assert_eq!(r.status, 404);
    assert_eq!(r.json()["detail"], "No Finary session for member");
    assert_eq!(
        f.admin.get("/api/finary/status").send().await.json()["connected"],
        true
    );

    let r = f
        .admin
        .post("/api/finary/login")
        .json(&json!({"email": "x@example.com", "password": "other"}))
        .send()
        .await;
    assert_eq!(r.status, 200);
    assert_eq!(f.count("SELECT count(*) FROM finary_session").await, 1);
    assert_eq!(
        f.admin.get("/api/finary/status").send().await.json()["maskedEmail"],
        "***"
    );

    let r = f
        .admin
        .post("/api/finary/login")
        .json(&json!({"email": "only@example.com"}))
        .send()
        .await;
    assert_eq!(r.status, 500);

    assert_eq!(
        f.admin.delete("/api/finary/session").send().await.status,
        204
    );
    assert_eq!(
        f.admin.get("/api/finary/status").send().await.json(),
        json!({"connected": false})
    );
    assert_eq!(
        f.admin.delete("/api/finary/session").send().await.status,
        204
    );
}

#[tokio::test]
async fn check_totp_reads_the_second_factor_from_the_clerk_sign_in() {
    let f = Finary::start().await;
    f.connect().await;
    Mock::given(method("POST"))
        .and(path("/v1/client/sign_ins"))
        .and(query_param("__clerk_api_version", "2025-11-10"))
        .and(header("Origin", "https://app.finary.com"))
        .and(body_string_contains(
            "identifier=user%40example.com&password=synthetic+pass%261",
        ))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(
                json!({"response": {"id": "sia_1", "status": "needs_second_factor"}}),
            ),
        )
        .expect(1)
        .with_priority(1)
        .mount(&f.server)
        .await;
    f.clerk("needs_second_factor").await;
    let r = f.admin.post("/api/finary/check-totp").send().await;
    assert_eq!(r.status, 200, "{}", r.text());
    assert_eq!(r.json(), json!({"totpRequired": true}));
}

#[tokio::test]
async fn check_totp_is_false_without_a_second_factor_and_maps_clerk_failures() {
    let f = Finary::start().await;
    f.connect().await;
    f.clerk("complete").await;
    assert_eq!(
        f.admin.post("/api/finary/check-totp").send().await.json(),
        json!({"totpRequired": false})
    );

    f.server.reset().await;
    ok(&f.server, "GET", "/v1/environment", json!({})).await;
    Mock::given(method("GET"))
        .and(path("/v1/client"))
        .respond_with(ResponseTemplate::new(401).set_body_string("token=secret"))
        .mount(&f.server)
        .await;
    let r = f.admin.post("/api/finary/check-totp").send().await;
    assert_eq!(r.status, 422);
    assert_eq!(
        r.json()["detail"],
        "Invalid Finary credentials. Please check your email and password."
    );

    f.server.reset().await;
    ok(&f.server, "GET", "/v1/environment", json!({})).await;
    ok(&f.server, "GET", "/v1/client", json!({})).await;
    ok(
        &f.server,
        "POST",
        "/v1/client/sign_ins",
        json!({"response": {}}),
    )
    .await;
    let r = f.admin.post("/api/finary/check-totp").send().await;
    assert_eq!(r.status, 422);
    assert_eq!(
        r.json()["detail"],
        "Finary sign-in failed. Please check your credentials and try again."
    );

    f.server.reset().await;
    Mock::given(method("GET"))
        .and(path("/v1/environment"))
        .respond_with(ResponseTemplate::new(403).set_body_string("session=leaked"))
        .mount(&f.server)
        .await;
    let r = f.admin.post("/api/finary/check-totp").send().await;
    assert_eq!(r.status, 422);
    assert_eq!(
        r.json()["detail"],
        "Finary auth check failed: HTTP 403",
        "the upstream body never reaches the client"
    );
}

#[tokio::test]
async fn an_unreachable_clerk_is_a_bad_gateway() {
    let unused = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!("http://{}", unused.local_addr().unwrap());
    drop(unused);
    let app = TestApp::builder()
        .with_admin()
        .env("FINARY_CLERK_URL", &url)
        .env("FINARY_API_URL", &url)
        .spawn()
        .await;
    let admin = app.login_admin().await;
    admin
        .post("/api/finary/login")
        .json(&json!({"email": "user@example.com", "password": "p"}))
        .send()
        .await;
    let r = admin.post("/api/finary/api-sync/preview").send().await;
    assert_eq!(r.status, 502);
    assert_eq!(
        r.json()["detail"],
        "Finary service is temporarily unavailable. Please try again later."
    );
}

#[tokio::test]
async fn preview_without_a_session_or_a_token_fails_as_java_does() {
    let f = Finary::start().await;
    let r = f.admin.post("/api/finary/api-sync/preview").send().await;
    assert_eq!(r.status, 422);
    assert_eq!(
        r.json()["detail"],
        "Finary not connected. Please log in first."
    );
    let (status, body) = f.execute("unknown", json!([])).await;
    assert_eq!(status, 422);
    assert_eq!(
        body["detail"],
        "Sync session expired or invalid -- please start a new sync"
    );
    let r = f
        .admin
        .post("/api/finary/api-sync/execute")
        .json(&json!({"mappings": []}))
        .send()
        .await;
    assert_eq!(r.status, 500);
    let r = f.admin.post("/api/finary/api-sync/auto").send().await;
    assert_eq!(
        r.json(),
        json!({"status": "NOT_CONNECTED", "accountsSynced": 0, "newAccountCount": 0})
    );
}

#[tokio::test]
async fn preview_asks_for_the_totp_then_sends_it_as_the_second_factor() {
    let f = Finary::start().await;
    f.connect().await;
    Mock::given(method("GET"))
        .and(path("/users/me/organizations"))
        .and(header("Authorization", "Bearer jwt-1"))
        .and(header("x-finary-client-id", "webapp"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"result": [{"id": "org-1", "members": [{"id": "mem-1", "user": {"fullname": "S"}}]}]})))
        .with_priority(1)
        .expect(1)
        .mount(&f.server)
        .await;
    f.clerk("needs_second_factor").await;
    f.empty_portfolio().await;
    let r = f.admin.post("/api/finary/api-sync/preview").send().await;
    assert_eq!(r.status, 403);
    assert_eq!(r.json()["detail"], "TOTP required: account has 2FA enabled");
    let r = f
        .admin
        .post("/api/finary/api-sync/preview?totp=%20")
        .send()
        .await;
    assert_eq!(r.status, 403);

    Mock::given(method("POST"))
        .and(path("/v1/client/sign_ins/sia_1/attempt_second_factor"))
        .and(body_string_contains("strategy=totp&code=123456"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({"client": {"sessions": [{"id": "sess_1"}]}})),
        )
        .expect(1)
        .mount(&f.server)
        .await;
    let r = f
        .admin
        .post("/api/finary/api-sync/preview?totp=123456")
        .send()
        .await;
    assert_eq!(r.status, 200, "{}", r.text());
    assert_eq!(r.json()["accounts"], json!([]));
    assert_eq!(r.json()["autoMapped"], true);
    let status = f.admin.get("/api/finary/status").send().await.json();
    assert_eq!(status["lastSyncedAt"], "2026-01-15T10:00:00Z");
}

#[tokio::test]
async fn preview_lists_loans_positive_and_keeps_native_balances_without_an_eur_witness() {
    let f = Finary::start().await;
    f.ready().await;
    f.accounts("cryptos", json!([usd_wallet()])).await;
    f.accounts("checkings", json!([eur_checking("chk-1", "0.01")]))
        .await;
    ok(
        &f.server,
        "GET",
        "/users/me/loans",
        json!({"result": [{"id": "loan-1", "type": "loan", "name": "PRET CONSO Auto",
        "outstanding_amount": 12345.67, "monthly_repayment": 250.00, "currency": {"code": "EUR"},
        "institution": {"name": "Synthetic Bank"}}]}),
    )
    .await;
    let preview = f.preview().await;
    let wallet = row(&preview, "w1");
    assert_eq!(wallet["currentBalance"].to_string(), "9179.3");
    assert_eq!(wallet["nativeCurrency"], "USD");
    assert_eq!(wallet["finaryInstitution"], "Finary");
    assert_eq!(wallet["suggestedType"], "CRYPTO");
    let loan = row(&preview, "loan-1");
    assert_eq!(loan["finaryCategory"], "loans");
    assert_eq!(loan["suggestedType"], "LOAN");
    assert_eq!(loan["currentBalance"].to_string(), "12345.67");
    assert_eq!(loan["finaryInstitution"], "Synthetic Bank");
    assert_eq!(preview["autoMapped"], false);
    assert_eq!(preview["suggestedMappings"][0]["action"], "CREATE_NEW");
    assert!(
        preview["suggestedMappings"][0]
            .get("targetAccountId")
            .is_none()
    );
}

#[tokio::test]
async fn an_eur_witness_switches_every_balance_to_the_display_amount() {
    let f = Finary::start().await;
    f.ready().await;
    f.accounts("checkings", json!([eur_checking("chk-1", "10000000")]))
        .await;
    f.accounts("cryptos", json!([usd_wallet()])).await;
    let r = f.admin.post("/api/finary/api-sync/preview").send().await;
    assert!(
        r.text().contains("\"currentBalance\":1.0E7,"),
        "Jackson writes the double: {}",
        r.text()
    );
    let preview = r.json();
    assert_eq!(row(&preview, "w1")["currentBalance"].to_string(), "7920.94");
    assert_eq!(row(&preview, "w1")["nativeCurrency"], "EUR");

    let token = preview["fileToken"].as_str().unwrap();
    let (status, result) = f
        .execute(
            token,
            json!([create_new("w1", "cryptos", "Wallet", "CRYPTO", "USD")]),
        )
        .await;
    assert_eq!(status, 200, "{result}");
    assert_eq!(result["accountsCreated"], 1);
    let created = f
        .reload(result["importedAccounts"][0]["id"].as_i64().unwrap())
        .await;
    assert_eq!(created.currency, "EUR");
    assert_eq!(created.current_balance, d("7920.94"));
    assert_eq!(created.color, "#f59e0b");
    assert_eq!(
        created.external_account_id.as_deref(),
        Some("finary_cryptos_w1")
    );
    assert!(created.is_manual);
    assert_eq!(
        result["importedAccounts"][0]["currentBalance"].to_string(),
        "7920.94"
    );
}

#[tokio::test]
async fn a_disagreeing_eur_account_disproves_the_display_currency() {
    let f = Finary::start().await;
    f.ready().await;
    f.accounts("checkings", json!([eur_checking("chk-1", "5000"),
        {"id": "chk-2", "name": "Other", "balance": 2000, "display_balance": 2000.02, "currency": {"code": "eur"}}])).await;
    f.accounts("cryptos", json!([usd_wallet()])).await;
    let preview = f.preview().await;
    assert_eq!(row(&preview, "w1")["nativeCurrency"], "USD");
    assert_eq!(
        row(&preview, "chk-2")["currentBalance"].to_string(),
        "2000.0"
    );
}

#[tokio::test]
async fn execute_creates_a_loan_and_spends_the_token() {
    let f = Finary::start().await;
    f.ready().await;
    ok(&f.server, "GET", "/users/me/loans", json!({"result": [{"id": "loan-1", "name": "PRET", "outstandingAmount": 12345.67, "currency": {"code": "EUR"}}]})).await;
    let preview = f.preview().await;
    let token = preview["fileToken"].as_str().unwrap();

    f.app.create_member("dominique", "password-1234").await;
    let member = f.app.login("dominique", "password-1234").await;
    let r = member
        .post("/api/finary/api-sync/execute")
        .json(&json!({"syncToken": token, "mappings": []}))
        .send()
        .await;
    assert_eq!(r.status, 422, "another member's token is no use to them");

    let (status, result) = f.execute(token, json!([create_new("loan-1", "loans", "PRET", "LOAN", "EUR"), {"finaryId": "ghost", "finaryCategory": "loans", "action": "SKIP"}])).await;
    assert_eq!(status, 200, "{result}");
    assert_eq!(
        result,
        json!({"accountsCreated": 1, "accountsMapped": 0, "accountsSkipped": 0, "snapshotsCreated": 0,
        "transactionsImported": 0, "importedAccounts": [{"id": result["importedAccounts"][0]["id"], "name": "PRET",
        "type": "LOAN", "currentBalance": 12345.67, "color": "#6366f1"}]})
    );
    let loan = f
        .reload(result["importedAccounts"][0]["id"].as_i64().unwrap())
        .await;
    assert_eq!(loan.current_balance, d("12345.67"));
    assert_eq!(
        loan.external_account_id.as_deref(),
        Some("finary_loans_loan-1")
    );
    assert_eq!(loan.provider.as_deref(), Some("Finary"));

    let (status, body) = f.execute(token, json!([])).await;
    assert_eq!(status, 422);
    assert_eq!(
        body["detail"],
        "Sync session expired or invalid -- please start a new sync"
    );
}

#[tokio::test]
async fn the_token_expires_after_ten_minutes() {
    let f = Finary::start().await;
    f.ready().await;
    let token = f.preview().await["fileToken"].as_str().unwrap().to_owned();
    f.app.clock.advance(std::time::Duration::from_secs(11 * 60));
    let (status, _) = f.execute(&token, json!([])).await;
    assert_eq!(status, 422);
}

#[tokio::test]
async fn two_rows_onto_one_account_are_rejected_before_anything_is_written() {
    let f = Finary::start().await;
    f.ready().await;
    f.accounts("checkings", json!([eur_checking("chk-1", "1000")]))
        .await;
    f.accounts(
        "investments",
        json!([{"id": "a", "name": "PEA", "balance": 100, "currency": {"code": "EUR"}}]),
    )
    .await;
    let target = f.account("Courant", AccountType::Checking, |_| {}).await;
    let owns_a = f
        .account("PEA", AccountType::Pea, |a| {
            a.external_account_id = Some("finary_investments_a".into())
        })
        .await;
    let token = f.preview().await["fileToken"].as_str().unwrap().to_owned();

    let (status, body) = f
        .execute(
            &token,
            json!([
                map_existing("chk-1", "checkings", target.id),
                map_existing("a", "investments", target.id)
            ]),
        )
        .await;
    assert_eq!(status, 422);
    assert_eq!(
        body["detail"],
        format!(
            "Two Finary accounts are mapped onto the same Picsou account {} -- pick a different account for one of them",
            target.id
        )
    );

    let (status, _) = f
        .execute(
            &token,
            json!([
                create_new("a", "investments", "PEA", "PEA", "EUR"),
                map_existing("chk-1", "checkings", owns_a.id)
            ]),
        )
        .await;
    assert_eq!(status, 422, "the upsert target counts too");
    assert_eq!(f.reload(target.id).await, target);
    assert_eq!(f.reload(owns_a.id).await, owns_a);

    let (status, body) = f
        .execute(
            &token,
            json!([
                map_existing("chk-1", "checkings", target.id),
                create_new("a", "investments", "PEA", "PEA", "EUR")
            ]),
        )
        .await;
    assert_eq!(status, 200, "a rejection leaves the token usable: {body}");
    assert_eq!(
        body["accountsMapped"], 2,
        "CREATE_NEW upserts the account holding its external id"
    );
    assert_eq!(f.reload(owns_a.id).await.current_balance, d("100"));
}

#[tokio::test]
async fn mapping_onto_another_connectors_account_keeps_its_id_and_holdings() {
    let f = Finary::start().await;
    f.ready().await;
    f.accounts("checkings", json!([eur_checking("chk-1", "1000")]))
        .await;
    f.accounts(
        "investments",
        json!([{"id": "inv-1", "name": "Trade Republic", "balance": 500, "display_balance": 500,
        "currency": {"code": "EUR"}, "securities": [], "cryptos": [], "fonds_euro": [], "fiats": [],
        "generic_assets": [], "scpis": [], "precious_metals": []}]),
    )
    .await;
    let tr = f
        .account("Trade Republic", AccountType::CompteTitres, |a| {
            a.is_manual = false;
            a.external_account_id = Some("tr_portfolio_42".into());
            a.cash_balance = Some(d("12"));
        })
        .await;
    picsou::db::query("INSERT INTO account_holding (account_id, ticker, quantity, created_at, updated_at) VALUES ($1, 'AAPL', 3, now(), now())")
        .bind(tr.id).execute(&f.app.state.db).await.unwrap();
    let preview = f.preview().await;
    let suggestion = preview["suggestedMappings"]
        .as_array()
        .unwrap()
        .iter()
        .find(|m| m["finaryId"] == "inv-1")
        .unwrap();
    assert_eq!(
        suggestion["action"], "CREATE_NEW",
        "never suggested by name"
    );

    let (status, body) = f
        .execute(
            preview["fileToken"].as_str().unwrap(),
            json!([map_existing("inv-1", "investments", tr.id)]),
        )
        .await;
    assert_eq!(status, 200, "{body}");
    let after = f.reload(tr.id).await;
    assert_eq!(
        after.external_account_id.as_deref(),
        Some("tr_portfolio_42")
    );
    assert_eq!(after.current_balance, d("500"));
    assert_eq!(after.cash_balance, Some(d("12")));
    assert_eq!(f.count("SELECT count(*) FROM account_holding").await, 1);
}

#[tokio::test]
async fn matching_prefers_linked_ids_and_needs_a_unique_compatible_name() {
    let f = Finary::start().await;
    f.ready().await;
    f.accounts("checkings", json!([eur_checking("chk-1", "1000"), {"id": "chk-2", "name": " checking ", "balance": 1, "currency": {"code": "EUR"}}])).await;
    f.accounts("savings", json!([{"id": "sav-1", "slug": "livret", "name": "Livret", "balance": 1, "currency": {"code": "EUR"}}])).await;
    f.accounts(
        "real_estates",
        json!([{"id": "re-1", "name": "Flat", "balance": 1, "currency": {"code": "EUR"}}]),
    )
    .await;
    let by_name = f
        .account("Checking", AccountType::Checking, |a| {
            a.current_balance = d("12.5")
        })
        .await;
    let linked = f
        .account("Renamed", AccountType::Checking, |a| {
            a.external_account_id = Some("finary_checkings_chk-2".into())
        })
        .await;
    let legacy = f
        .account("Old", AccountType::Savings, |a| {
            a.external_account_id = Some("finary_Savings_livret".into())
        })
        .await;
    f.account("Flat", AccountType::Savings, |_| {}).await;
    let preview = f.preview().await;
    let target = |id: &str| {
        preview["suggestedMappings"]
            .as_array()
            .unwrap()
            .iter()
            .find(|m| m["finaryId"] == id)
            .unwrap()["targetAccountId"]
            .as_i64()
    };
    assert_eq!(target("chk-1"), Some(by_name.id));
    assert_eq!(target("chk-2"), Some(linked.id));
    assert_eq!(target("sav-1"), Some(legacy.id));
    assert_eq!(target("re-1"), None, "a SAVINGS account is no real estate");
    let existing = preview["existingPicsouAccounts"].as_array().unwrap();
    assert_eq!(existing.len(), 4);
    assert_eq!(existing[0]["currentBalance"].to_string(), "12.50000000");
    assert_eq!(existing[0]["isManual"], true);
    assert_eq!(existing[0]["listCollapsed"], false);
}

#[tokio::test]
async fn transactions_keep_their_currency_and_rebuild_the_history() {
    let f = Finary::start().await;
    f.ready().await;
    f.accounts("checkings", json!([
        {"id": "usd-1", "name": "US checking", "balance": 1000.0, "display_balance": 900.0, "currency": {"code": "USD"}},
        eur_checking("chk-1", "5000")])).await;
    f.transactions("checkings", json!([
        {"id": 1, "name": "coffee", "date": "2026-01-14T10:00:00Z", "value": -50, "transactionType": "debit", "currency": {"code": ""}, "account": {"id": "chk-1"}},
        {"id": 2, "name": "rent", "displayName": "Rent", "date": "2026-01-13T23:30:00-02:00", "value": -1000.5, "category": {"name": "Housing"}, "account": {"id": "chk-1"}},
        {"id": 3, "name": "usd", "date": "2026-01-12T10:00:00Z", "value": 100.0, "currency": {"code": "USD"}, "account": {"id": "usd-1"}}
    ])).await;
    let target = f.account("Courant", AccountType::Checking, |_| {}).await;
    picsou::db::query("INSERT INTO \"transaction\" (account_id, date, description, amount, native_currency, is_manual, created_at) VALUES ($1, '2026-01-01', 'manual', 7, 'EUR', true, now()), ($1, '2026-01-02', 'synced', 9, 'EUR', false, now())")
        .bind(target.id).execute(&f.app.state.db).await.unwrap();
    let preview = f.preview().await;
    assert_eq!(preview["totalTransactionCount"], 3);
    assert_eq!(row(&preview, "chk-1")["transactionCount"], 2);

    let (status, body) = f
        .execute(
            preview["fileToken"].as_str().unwrap(),
            json!([map_existing("chk-1", "checkings", target.id)]),
        )
        .await;
    assert_eq!(status, 200, "{body}");
    assert_eq!(body["transactionsImported"], 2);
    let rows: Vec<TxRow> = picsou::db::query_as(
        "SELECT date::text, description, amount, category, native_currency FROM \"transaction\" WHERE account_id = $1 ORDER BY date, description")
        .bind(target.id).fetch_all(&f.app.state.db).await.unwrap();
    assert_eq!(
        rows.len(),
        3,
        "the manual row survives, the synced one is replaced"
    );
    assert_eq!(rows[0].1, "manual");
    assert_eq!(
        rows[1],
        (
            "2026-01-14".into(),
            "Rent".into(),
            d("-1000.5"),
            Some("Housing".into()),
            Some("EUR".into())
        ),
        "the UTC date of the instant"
    );
    assert_eq!(rows[2].3.as_deref(), Some(""));
    let snapshots: Vec<(String, Decimal)> = picsou::db::query_as(
        "SELECT date::text, balance FROM balance_snapshot WHERE account_id = $1 ORDER BY date",
    )
    .bind(target.id)
    .fetch_all(&f.app.state.db)
    .await
    .unwrap();
    assert_eq!(
        snapshots,
        vec![
            ("2026-01-01".into(), d("6050.5")),
            ("2026-01-14".into(), d("5000")),
            ("2026-01-15".into(), d("5000"))
        ]
    );
    let after = f.reload(target.id).await;
    assert_eq!(
        after.external_account_id.as_deref(),
        Some("finary_checkings_chk-1")
    );
    assert_eq!(after.last_synced_at, Some(epoch()));
}

#[tokio::test]
async fn a_transaction_without_any_currency_aborts_the_whole_import() {
    let f = Finary::start().await;
    f.ready().await;
    f.accounts(
        "checkings",
        json!([eur_checking("chk-1", "1000"), {"id": "x-1", "name": "Mystery", "balance": 10}]),
    )
    .await;
    f.transactions("checkings", json!([{"id": 9, "name": "t", "date": "2026-01-14T10:00:00Z", "value": 1, "account": {"id": "x-1"}}])).await;
    let first = f.account("First", AccountType::Checking, |_| {}).await;
    let mystery = f.account("Mystery", AccountType::Checking, |_| {}).await;
    let preview = f.preview().await;
    let (status, body) = f
        .execute(
            preview["fileToken"].as_str().unwrap(),
            json!([
                map_existing("chk-1", "checkings", first.id),
                map_existing("x-1", "checkings", mystery.id)
            ]),
        )
        .await;
    assert_eq!(status, 422);
    assert_eq!(
        body["detail"],
        "Finary transaction 9 on account Mystery has no currency, and neither does its account"
    );
    assert_eq!(f.reload(first.id).await, first, "rolled back");
}

#[tokio::test]
async fn holdings_come_from_a_complete_snapshot_in_eur() {
    let f = Finary::start().await;
    f.ready().await;
    let fiat = json!({"id": "f1", "name": "Euro", "type": "user_fiat", "quantity": 3447.07, "display_current_value": 3447.07, "fiat": {"code": "EUR"}});
    let cash = json!({"id": "cash", "name": "Solde Espèces", "quantity": 3447.07, "display_current_value": 3447.07,
        "security": {"name": "Solde Espèces", "symbol": "000000000000"}});
    let axa = json!({"id": "s1", "name": "AXA", "type": "user_security", "quantity": 54, "display_buying_value": 1892.052,
        "display_current_value": 2420.28, "display_unrealized_pnl": 528.228, "security": {"name": "AXA", "isin": "fr0000120628"}});
    let btc = json!({"id": "h1", "type": "user_crypto", "quantity": 0.14427879, "display_buying_value": 7139.22,
        "display_current_price": 54900.27, "display_current_value": 7920.94, "display_unrealized_pnl": 781.73,
        "crypto": {"name": "Bitcoin", "code": "BTC"}});
    let fonds = json!({"id": "fe1", "name": "FDS EUROS SURAVENIR", "quantity": 1.0, "display_current_value": 20876.93});
    f.accounts("investments", json!([{"id": "pea-1", "name": "PEA", "balance": 1000, "display_balance": 1000, "currency": {"code": "EUR"},
        "securities": [null, axa, cash], "cryptos": [btc], "fonds_euro": [fonds], "fiats": [null, fiat],
        "generic_assets": [], "scpis": [], "precious_metals": []}])).await;
    let preview = f.preview().await;
    let (status, body) = f
        .execute(
            preview["fileToken"].as_str().unwrap(),
            json!([create_new("pea-1", "investments", "PEA", "PEA", "EUR")]),
        )
        .await;
    assert_eq!(status, 200, "{body}");
    let id = body["importedAccounts"][0]["id"].as_i64().unwrap();
    assert_eq!(f.reload(id).await.cash_balance, Some(d("6894.14")));
    let holdings: Vec<HoldingRow> = picsou::db::query_as(
        "SELECT ticker, name, quantity, average_buy_in, current_price, provider_value_eur, provider_pnl_eur, quote_currency FROM account_holding WHERE account_id = $1 ORDER BY ticker")
        .bind(id).fetch_all(&f.app.state.db).await.unwrap();
    assert_eq!(holdings.len(), 3);
    assert_eq!(holdings[0].0, "BTC");
    assert_eq!(holdings[0].4, Some(d("54900.27")));
    assert_eq!(holdings[1].0, "FINARY_FDS_EUROS_SURAVENIR");
    assert_eq!(
        holdings[2],
        (
            "FR0000120628".into(),
            Some("AXA".into()),
            d("54"),
            Some(d("35.038")),
            Some(d("44.82000000")),
            Some(d("2420.28")),
            Some(d("528.228")),
            Some("EUR".into())
        ),
        "an unresolved ISIN is its own ticker"
    );
}

#[tokio::test]
async fn an_incomplete_snapshot_keeps_the_existing_holdings_and_cash() {
    let f = Finary::start().await;
    f.ready().await;
    f.accounts("investments", json!([{"id": "pea-1", "name": "PEA", "balance": 1000, "display_balance": 1000, "currency": {"code": "EUR"},
        "securities": [], "cryptos": [], "fonds_euro": [], "fiats": [], "generic_assets": [], "scpis": []}])).await;
    let pea = f
        .account("PEA", AccountType::Pea, |a| a.cash_balance = Some(d("500")))
        .await;
    picsou::db::query("INSERT INTO account_holding (account_id, ticker, quantity, created_at, updated_at) VALUES ($1, 'CW8', 3, now(), now())")
        .bind(pea.id).execute(&f.app.state.db).await.unwrap();
    let preview = f.preview().await;
    let (status, _) = f
        .execute(
            preview["fileToken"].as_str().unwrap(),
            json!([map_existing("pea-1", "investments", pea.id)]),
        )
        .await;
    assert_eq!(status, 200);
    assert_eq!(f.reload(pea.id).await.cash_balance, Some(d("500")));
    assert_eq!(f.count("SELECT count(*) FROM account_holding").await, 1);
}

#[tokio::test]
async fn auto_sync_executes_when_every_account_is_mapped() {
    let f = Finary::start().await;
    f.ready().await;
    f.accounts("checkings", json!([eur_checking("chk-1", "1000")]))
        .await;
    let linked = f
        .account("Courant", AccountType::Checking, |a| {
            a.external_account_id = Some("finary_checkings_chk-1".into())
        })
        .await;
    let r = f.admin.post("/api/finary/api-sync/auto").send().await;
    assert_eq!(
        r.json(),
        json!({"status": "OK", "accountsSynced": 1, "newAccountCount": 0})
    );
    assert_eq!(f.reload(linked.id).await.current_balance, d("1000"));
    let direct = picsou::sync::finary::auto_sync(&f.app.state, f.member_id)
        .await
        .unwrap();
    assert_eq!(direct.status, picsou::sync::finary::AutoSyncStatus::Ok);

    f.accounts(
        "savings",
        json!([{"id": "new", "name": "New", "balance": 1, "currency": {"code": "EUR"}}]),
    )
    .await;
    let r = f.admin.post("/api/finary/api-sync/auto").send().await;
    assert_eq!(
        r.json(),
        json!({"status": "NEEDS_MAPPING", "accountsSynced": 0, "newAccountCount": 2})
    );
}

#[tokio::test]
async fn auto_sync_flags_a_session_that_needs_a_totp() {
    let f = Finary::start().await;
    f.connect().await;
    f.clerk("needs_second_factor").await;
    let r = f.admin.post("/api/finary/api-sync/auto").send().await;
    assert_eq!(
        r.json(),
        json!({"status": "TOTP_REQUIRED", "accountsSynced": 0, "newAccountCount": 0})
    );
    assert_eq!(
        f.admin.get("/api/finary/status").send().await.json()["status"],
        "TOTP_REQUIRED"
    );
    f.server.reset().await;
    let r = f.admin.post("/api/finary/api-sync/auto").send().await;
    assert_eq!(
        r.json()["status"],
        "TOTP_REQUIRED",
        "no sign-in is attempted while flagged"
    );
    picsou::db::query("UPDATE finary_session SET status = 'EXPIRED'")
        .execute(&f.app.state.db)
        .await
        .unwrap();
    assert_eq!(
        f.admin
            .post("/api/finary/api-sync/auto")
            .send()
            .await
            .json()["status"],
        "NOT_CONNECTED"
    );
}

#[tokio::test]
async fn a_failing_category_is_retried_then_named() {
    let f = Finary::start().await;
    f.connect().await;
    f.clerk("complete").await;
    Mock::given(method("GET"))
        .and(path(format!("{PORTFOLIO}/checkings/accounts")))
        .respond_with(ResponseTemplate::new(503))
        .expect(3)
        .mount(&f.server)
        .await;
    let r = f.admin.post("/api/finary/api-sync/preview").send().await;
    assert_eq!(r.status, 422);
    assert_eq!(
        r.json()["detail"],
        "Failed to fetch accounts from checkings"
    );
    let status = f.admin.get("/api/finary/status").send().await.json();
    assert!(
        status.get("lastSyncedAt").is_none(),
        "a failed preview writes nothing"
    );
}

#[tokio::test]
async fn transactions_page_until_a_short_batch() {
    let f = Finary::start().await;
    f.ready().await;
    f.accounts("checkings", json!([eur_checking("chk-1", "1000")]))
        .await;
    let page: Vec<Value> = (0..200).map(|i| json!({"id": i, "name": "t", "date": "2026-01-10T10:00:00Z", "value": 1, "currency": {"code": "EUR"}, "account": {"id": "chk-1"}})).collect();
    f.transactions("checkings", json!(page)).await;
    Mock::given(method("GET"))
        .and(path(format!("{PORTFOLIO}/checkings/transactions")))
        .and(query_param("page", "2"))
        .and(query_param("per_page", "200"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"result": [page[0]]})))
        .expect(1)
        .with_priority(1)
        .mount(&f.server)
        .await;
    assert_eq!(f.preview().await["totalTransactionCount"], 201);
}
