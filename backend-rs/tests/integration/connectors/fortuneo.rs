//! FortuneoController and FortuneoSyncService over HTTP, the sidecar stubbed.

use picsou::ledger::sidecar::{SidecarBank, SidecarErrorCode};
use picsou::testing::{Client, TestApp};
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{body_json, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::support::{
    admin, answer, app_with_sidecar, count, d, fail, member, n, session_of, settle, store_session,
};

const URL: &str = "FORTUNEO_AUTH_URL";
const BANK: SidecarBank = SidecarBank::Fortuneo;

/// type, tx_type, ticker, name, quantity, price per unit, fees.
type LedgerRow = (
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<Decimal>,
    Option<Decimal>,
    Option<Decimal>,
);

fn position(isin: Option<&str>, symbol: &str, quantity: &str, value: &str) -> Value {
    json!({
        "isin": isin, "symbol": symbol, "label": format!("{symbol} SA"), "quantity": n(quantity),
        "buyingPriceEur": 80, "currentPrice": 100, "quoteCurrency": "EUR",
        "currentValueEur": n(value), "pnlEur": 200
    })
}

fn pea(positions: Vec<Value>, balance: &str, cash: &str, transactions: Vec<Value>) -> Value {
    json!({
        "externalId": "pea-1", "name": "PEA", "type": "PEA", "balanceEur": n(balance),
        "cashBalance": n(cash), "positions": positions, "transactions": transactions,
        "snapshotComplete": true
    })
}

fn checking(balance: &str, transactions: Vec<Value>) -> Value {
    json!({
        "externalId": "chk-1", "name": "Compte courant", "type": "CHECKING", "balanceEur": n(balance),
        "cashBalance": n(balance), "positions": [], "transactions": transactions,
        "snapshotComplete": true
    })
}

fn tx(id: Option<&str>, date: &str, label: &str, amount: &str) -> Value {
    json!({ "externalId": id, "date": date, "label": label, "amount": n(amount) })
}

async fn status(client: &Client) -> Value {
    client.get("/api/fortuneo/status").send().await.json()
}

struct Synced {
    app: TestApp,
    client: Client,
    member_id: i64,
    sidecar: MockServer,
}

impl Synced {
    async fn new() -> Self {
        let sidecar = MockServer::start().await;
        let app = app_with_sidecar(URL, &sidecar).await;
        let (client, member_id) = admin(&app).await;
        store_session(&app, BANK, member_id, "plain-state").await;
        Self {
            app,
            client,
            member_id,
            sidecar,
        }
    }

    /// Answers /accounts with `body` for the next sync only, runs it and returns the status.
    async fn sync(&self, body: Value) -> Value {
        Mock::given(method("POST"))
            .and(path("/accounts"))
            .respond_with(ResponseTemplate::new(200).set_body_json(body))
            .up_to_n_times(1)
            .mount(&self.sidecar)
            .await;
        let queued = self.client.post("/api/fortuneo/sync").send().await;
        assert_eq!(queued.status, 202, "{}", queued.text());
        settle(&self.app).await;
        status(&self.client).await
    }

    async fn account_id(&self) -> i64 {
        sqlx::query_scalar("SELECT id FROM account WHERE member_id = $1 ORDER BY id LIMIT 1")
            .bind(self.member_id)
            .fetch_one(&self.app.state.db)
            .await
            .unwrap()
    }

    async fn rows(&self) -> Vec<(Option<String>, String, String, Decimal)> {
        sqlx::query_as(
            "SELECT external_id, date::text, description, amount FROM \"transaction\" ORDER BY date, id",
        )
        .fetch_all(&self.app.state.db)
        .await
        .unwrap()
    }

    fn today(&self) -> chrono::NaiveDate {
        self.app.state.clock.today()
    }
}

fn day(date: chrono::NaiveDate, days_ago: u64) -> String {
    (date - chrono::Days::new(days_ago)).to_string()
}

#[tokio::test]
async fn status_without_a_session_is_inactive_and_idle() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    assert_eq!(
        status(&client).await,
        json!({ "isActive": false, "syncStatus": "IDLE" })
    );
}

#[tokio::test]
async fn an_mfa_login_relays_the_challenge_then_completes_with_the_code() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .and(body_json(json!({ "login": "jdoe", "password": "secret" })))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "processId": "ft-p-1", "mfaRequired": true, "mfaType": "sms"
        })))
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/complete"))
        .and(body_json(
            json!({ "processId": "ft-p-1", "code": "123456" }),
        ))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "sessionState": "plain-state" })),
        )
        .mount(&sidecar)
        .await;
    answer(&sidecar, "/accounts", json!([checking("10", vec![])])).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let initiated = client
        .post("/api/fortuneo/auth/initiate")
        .json(&json!({ "login": "jdoe", "password": "secret" }))
        .send()
        .await;
    assert_eq!(
        initiated.json(),
        json!({ "processId": "ft-p-1", "mfaRequired": true, "mfaType": "sms" })
    );
    assert!(session_of(&app, BANK, member_id).await.is_none());
    let completed = client
        .post("/api/fortuneo/auth/complete")
        .json(&json!({ "processId": "ft-p-1", "code": "123456" }))
        .send()
        .await;
    settle(&app).await;

    assert_eq!(completed.status, 200, "{}", completed.text());
    assert_eq!(completed.json()["isActive"], true);
    let session = session_of(&app, BANK, member_id).await.unwrap();
    assert_eq!(
        app.state.crypto.decrypt(&session.session_state).unwrap(),
        "plain-state"
    );
    assert!(
        session.encrypted_credentials.is_none(),
        "Fortuneo keeps no credentials"
    );
    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
}

#[tokio::test]
async fn a_rejected_code_keeps_its_stable_invalid_otp_code() {
    let sidecar = MockServer::start().await;
    fail(&sidecar, "/complete", 401, json!({})).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let response = client
        .post("/api/fortuneo/auth/complete")
        .json(&json!({ "processId": "ft-p-2", "code": "123456" }))
        .send()
        .await;

    assert_eq!(response.status, 422);
    assert_eq!(response.json()["code"], "INVALID_OTP");
    assert_eq!(
        response.json()["detail"],
        BANK.message(SidecarErrorCode::InvalidOtp).unwrap()
    );
}

#[tokio::test]
async fn payload_validation_rejects_an_oversized_login_and_a_malformed_code() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let initiate = client
        .post("/api/fortuneo/auth/initiate")
        .json(&json!({ "login": "x".repeat(101), "password": "secret" }))
        .send()
        .await;
    let complete = client
        .post("/api/fortuneo/auth/complete")
        .json(&json!({ "processId": "ft-p-3", "code": "12a456" }))
        .send()
        .await;

    assert_eq!(initiate.status, 422);
    assert_eq!(
        initiate.json()["errors"]["login"],
        "size must be between 0 and 100"
    );
    assert_eq!(complete.status, 422);
    assert_eq!(complete.json()["errors"]["code"], r#"must match "\d{6}""#);
    assert!(sidecar.received_requests().await.unwrap().is_empty());
}

#[tokio::test]
async fn the_sixth_authentication_attempt_is_rate_limited_and_sync_is_not() {
    let sidecar = MockServer::start().await;
    fail(&sidecar, "/initiate", 401, json!({})).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    for _ in 0..5 {
        let response = client
            .post("/api/fortuneo/auth/initiate")
            .json(&json!({ "login": "jdoe", "password": "wrong" }))
            .send()
            .await;
        assert_eq!(response.json()["code"], "INVALID_CREDENTIALS");
    }

    let limited = client
        .post("/api/fortuneo/auth/initiate")
        .json(&json!({ "login": "jdoe", "password": "wrong" }))
        .send()
        .await;

    assert_eq!(limited.status, 429);
    assert_eq!(
        limited.json()["detail"],
        "Too many Fortuneo authentication attempts. Please wait before retrying."
    );
    for _ in 0..12 {
        assert_eq!(client.post("/api/fortuneo/sync").send().await.status, 422);
    }
}

#[tokio::test]
async fn a_mixed_portfolio_commits_every_account_with_its_snapshot() {
    let s = Synced::new().await;

    let after = s
        .sync(json!([
            pea(
                vec![position(Some("FR0000120271"), "ACME", "10", "1000")],
                "1250",
                "250",
                vec![]
            ),
            checking("300", vec![]),
        ]))
        .await;

    assert_eq!(after["syncStatus"], "SUCCESS");
    let accounts: Vec<(String, String, Decimal, String)> = sqlx::query_as(
        "SELECT external_account_id, type::text, cash_balance, color FROM account ORDER BY id",
    )
    .fetch_all(&s.app.state.db)
    .await
    .unwrap();
    assert_eq!(
        accounts,
        vec![
            ("ft_pea-1".into(), "PEA".into(), d("250"), "#10b981".into()),
            (
                "ft_chk-1".into(),
                "CHECKING".into(),
                d("300"),
                "#f59e0b".into()
            ),
        ]
    );
    let snapshots: Vec<(Decimal, Decimal)> =
        sqlx::query_as("SELECT balance, invested_amount FROM balance_snapshot ORDER BY account_id")
            .fetch_all(&s.app.state.db)
            .await
            .unwrap();
    assert_eq!(
        snapshots,
        vec![(d("1250"), d("1050")), (d("300"), d("300"))]
    );
    let holding: (String, Decimal) =
        sqlx::query_as("SELECT ticker, provider_value_eur FROM account_holding")
            .fetch_one(&s.app.state.db)
            .await
            .unwrap();
    assert_eq!(holding, ("FR0000120271".into(), d("1000")));
}

#[tokio::test]
async fn every_invalid_snapshot_is_refused_before_anything_is_replaced() {
    let mut cash_with_positions = checking("1000", vec![]);
    cash_with_positions["positions"] = json!([position(None, "A", "1", "1000")]);
    let mut mismatch = checking("1000", vec![]);
    mismatch["cashBalance"] = json!(900);
    let mut no_currency = position(None, "ACME", "10", "1000");
    no_currency["quoteCurrency"] = Value::Null;
    let mut unsupported = checking("1", vec![]);
    unsupported["type"] = json!("LIVRET_A");
    let mut unknown_type = tx(Some("op-1"), "2024-01-02", "X", "1");
    unknown_type["txType"] = json!("TRANSFER");
    let mut incomplete_tx = tx(Some("op-1"), "2024-01-02", "X", "1");
    incomplete_tx["amount"] = Value::Null;
    let cases = [
        (json!([cash_with_positions]), "INVALID_DATA"),
        (json!([mismatch]), "INVALID_DATA"),
        (
            json!([pea(vec![no_currency], "1000", "0", vec![])]),
            "INVALID_DATA",
        ),
        (
            json!([pea(
                vec![position(None, "A", "10", "1000")],
                "3000",
                "0",
                vec![]
            )]),
            "PORTFOLIO_INCOMPLETE",
        ),
        (json!([unsupported]), "INVALID_DATA"),
        (
            json!([checking("1", vec![]), checking("1", vec![])]),
            "INVALID_DATA",
        ),
        (json!([checking("1", vec![unknown_type])]), "INVALID_DATA"),
        (json!([checking("1", vec![incomplete_tx])]), "INVALID_DATA"),
    ];
    let s = Synced::new().await;
    assert_eq!(
        s.sync(json!([pea(
            vec![position(None, "KEEP", "1", "100")],
            "100",
            "0",
            vec![]
        )]))
        .await["syncStatus"],
        "SUCCESS"
    );
    for (body, code) in cases {
        let after = s.sync(body.clone()).await;
        assert_eq!(after["lastSyncError"], code, "{body}");
        assert_eq!(after["isActive"], true, "{body}");
        assert_eq!(
            count(&s.app, "SELECT count(*) FROM account").await,
            1,
            "{body}"
        );
        assert_eq!(
            count(
                &s.app,
                "SELECT count(*) FROM account_holding WHERE ticker = 'KEEP'"
            )
            .await,
            1,
            "{body}"
        );
    }
}

#[tokio::test]
async fn rows_without_ids_replace_only_the_trailing_ninety_day_window() {
    let s = Synced::new().await;
    let today = s.today();
    let first = vec![
        tx(None, &day(today, 200), "OLD", "-1"),
        tx(None, &day(today, 10), "RECENT", "-2"),
    ];
    assert_eq!(
        s.sync(json!([checking("100", first.clone())])).await["syncStatus"],
        "SUCCESS"
    );
    let account_id = s.account_id().await;
    sqlx::query(
        "INSERT INTO \"transaction\" (account_id, date, description, amount, native_currency, is_manual, created_at) \
         VALUES ($1, $2, 'MANUAL', 5, 'EUR', true, now())",
    )
    .bind(account_id)
    .bind(today - chrono::Days::new(5))
    .execute(&s.app.state.db)
    .await
    .unwrap();

    let second = vec![
        tx(None, &day(today, 200), "OLD", "-1"),
        tx(None, &day(today, 3), "NEWER", "-3"),
    ];
    assert_eq!(
        s.sync(json!([checking("100", second)])).await["syncStatus"],
        "SUCCESS"
    );

    let rows: Vec<String> = s.rows().await.into_iter().map(|r| r.2).collect();
    assert_eq!(
        rows,
        vec!["MANUAL", "NEWER"],
        "the old row is outside the window both times"
    );
}

#[tokio::test]
async fn identified_rows_import_the_whole_history_and_update_in_place() {
    let s = Synced::new().await;
    let today = s.today();
    let first = vec![
        tx(Some("op-1"), &day(today, 400), "FIRST", "-1"),
        tx(Some("op-2"), &day(today, 10), "SECOND", "-2"),
        tx(Some("op-2"), &day(today, 10), "SECOND REPEATED", "-2"),
    ];
    assert_eq!(
        s.sync(json!([checking("100", first)])).await["syncStatus"],
        "SUCCESS"
    );
    let before: Vec<i64> = sqlx::query_scalar("SELECT id FROM \"transaction\" ORDER BY date")
        .fetch_all(&s.app.state.db)
        .await
        .unwrap();
    assert_eq!(before.len(), 2, "a repeated id collapses to one row");

    let second = vec![
        tx(Some("op-1"), &day(today, 400), "FIRST RENAMED", "-1"),
        tx(Some("op-2"), &day(today, 10), "SECOND", "-2"),
    ];
    assert_eq!(
        s.sync(json!([checking("100", second)])).await["syncStatus"],
        "SUCCESS"
    );

    let after: Vec<i64> = sqlx::query_scalar("SELECT id FROM \"transaction\" ORDER BY date")
        .fetch_all(&s.app.state.db)
        .await
        .unwrap();
    assert_eq!(after, before, "updated in place");
    let rows = s.rows().await;
    assert_eq!(rows[0].2, "FIRST RENAMED");
    assert_eq!(rows[1].2, "SECOND");
}

#[tokio::test]
async fn identified_rows_delete_only_inside_the_range_they_cover() {
    let s = Synced::new().await;
    let today = s.today();
    let first = vec![
        tx(Some("op-1"), &day(today, 400), "ANCIENT", "-1"),
        tx(Some("op-2"), &day(today, 20), "GONE", "-2"),
        tx(Some("op-3"), &day(today, 1), "STALE", "-3"),
        tx(Some("op-4"), &day(today, 30), "KEPT", "-4"),
    ];
    s.sync(json!([checking("100", first)])).await;

    let second = vec![tx(Some("op-4"), &day(today, 30), "KEPT", "-4")];
    assert_eq!(
        s.sync(json!([checking("100", second)])).await["syncStatus"],
        "SUCCESS"
    );

    let rows: Vec<String> = s.rows().await.into_iter().map(|r| r.2).collect();
    assert_eq!(
        rows,
        vec!["ANCIENT", "KEPT"],
        "older than the response is kept, newer than anything reported is a stale leftover"
    );
}

#[tokio::test]
async fn an_empty_answer_leaves_history_alone_and_mixed_or_blank_ids_fall_back_to_the_window() {
    let s = Synced::new().await;
    let today = s.today();
    s.sync(json!([checking(
        "100",
        vec![tx(Some("op-1"), &day(today, 300), "KEEP", "-1")]
    )]))
    .await;
    s.sync(json!([checking("100", vec![])])).await;
    assert_eq!(s.rows().await.len(), 1);

    let mixed = vec![
        tx(Some("op-2"), &day(today, 2), "WITH ID", "-2"),
        tx(Some("  "), &day(today, 1), "BLANK ID", "-3"),
    ];
    assert_eq!(
        s.sync(json!([checking("100", mixed)])).await["syncStatus"],
        "SUCCESS"
    );

    let rows = s.rows().await;
    assert_eq!(
        rows.iter()
            .map(|r| (r.0.clone(), r.2.clone()))
            .collect::<Vec<_>>(),
        vec![
            (Some("op-1".into()), "KEEP".into()),
            (None, "WITH ID".into()),
            (None, "BLANK ID".into()),
        ],
        "the window import stores no id"
    );
}

#[tokio::test]
async fn securities_ledger_fields_are_stored_on_the_holdings_ticker() {
    let s = Synced::new().await;
    let today = s.today();
    let buy = json!({
        "externalId": "op-1", "date": day(today, 5), "label": "ACHAT ACME", "amount": n("-1002.50"),
        "type": " BUY_ORDER ", "txType": "BUY", "quantity": n("10"), "unitPrice": n("100"),
        "fees": n("2.50"), "isin": "fr0000120271"
    });
    let untyped = json!({
        "externalId": "op-2", "date": day(today, 4), "label": "FRAIS", "amount": n("-1"), "type": "  "
    });

    let after = s
        .sync(json!([pea(
            vec![position(Some("FR0000120271"), "ACME", "10", "1000")],
            "1250",
            "250",
            vec![buy, untyped]
        )]))
        .await;

    assert_eq!(after["syncStatus"], "SUCCESS", "{after}");
    let rows: Vec<LedgerRow> =
        sqlx::query_as(
            "SELECT type, tx_type, ticker, name, quantity, price_per_unit, fees FROM \"transaction\" ORDER BY date",
        )
        .fetch_all(&s.app.state.db)
        .await
        .unwrap();
    assert_eq!(
        rows,
        vec![
            (
                Some("BUY_ORDER".into()),
                Some("BUY".into()),
                Some("FR0000120271".into()),
                Some("ACHAT ACME".into()),
                Some(d("10")),
                Some(d("100")),
                Some(d("2.50"))
            ),
            (None, None, None, None, None, None, None),
        ]
    );
}

#[tokio::test]
async fn a_window_import_keeps_a_dividend_recognised_before() {
    let s = Synced::new().await;
    let today = s.today();
    let coupon = vec![tx(None, &day(today, 3), "COUPON ACME", "12.30")];
    s.sync(json!([checking("100", coupon.clone())])).await;
    sqlx::query(
        "UPDATE \"transaction\" SET tx_type = 'DIVIDEND', ticker = 'ACME', name = 'Acme SA', category_manual = true",
    )
    .execute(&s.app.state.db)
    .await
    .unwrap();

    s.sync(json!([checking(
        "100",
        vec![tx(None, &day(today, 3), "COUPON ACME", "12.300")]
    )]))
    .await;

    let row: (Option<String>, Option<String>, Option<String>, bool) =
        sqlx::query_as("SELECT tx_type, ticker, name, category_manual FROM \"transaction\"")
            .fetch_one(&s.app.state.db)
            .await
            .unwrap();
    assert_eq!(
        row,
        (
            Some("DIVIDEND".into()),
            Some("ACME".into()),
            Some("Acme SA".into()),
            true
        )
    );
}

#[tokio::test]
async fn a_provider_type_other_than_a_credit_drops_the_automatic_dividend_category() {
    let s = Synced::new().await;
    let today = s.today();
    s.sync(json!([checking(
        "100",
        vec![tx(Some("op-1"), &day(today, 3), "COUPON", "5")]
    )]))
    .await;
    let category: i64 = sqlx::query_scalar(
        "INSERT INTO category (member_id, name, slug, kind, color, is_default, archived, sort_order) \
         VALUES ($1, 'Revenus du capital', 'revenus-capital', 'INCOME', '#000000', false, false, 0) \
         ON CONFLICT DO NOTHING RETURNING id",
    )
    .bind(s.member_id)
    .fetch_optional(&s.app.state.db)
    .await
    .unwrap()
    .unwrap_or_default();
    let category = if category == 0 {
        sqlx::query_scalar(
            "SELECT id FROM category WHERE member_id = $1 AND slug = 'revenus-capital'",
        )
        .bind(s.member_id)
        .fetch_one(&s.app.state.db)
        .await
        .unwrap()
    } else {
        category
    };
    sqlx::query(
        "UPDATE \"transaction\" SET tx_type = 'DIVIDEND', ticker = 'ACME', category_id = $1",
    )
    .bind(category)
    .execute(&s.app.state.db)
    .await
    .unwrap();
    let mut fee = tx(Some("op-1"), &day(today, 3), "COUPON", "5");
    fee["txType"] = json!("FEE");

    s.sync(json!([checking("100", vec![fee])])).await;

    let row: (Option<String>, Option<i64>) =
        sqlx::query_as("SELECT tx_type, category_id FROM \"transaction\"")
            .fetch_one(&s.app.state.db)
            .await
            .unwrap();
    assert_eq!(row, (Some("FEE".into()), None));
}

#[tokio::test]
async fn the_cash_history_is_reconstructed_from_the_ledger_without_overwriting() {
    let s = Synced::new().await;
    let today = s.today();
    let rows = vec![
        tx(Some("op-1"), &day(today, 5), "IN", "50"),
        tx(Some("op-2"), &day(today, 2), "OUT", "-20"),
        tx(Some("op-3"), &day(today, 2), "OUT 2", "-10"),
    ];

    s.sync(json!([checking("100", rows)])).await;

    let snapshots: Vec<(String, Decimal, Decimal)> = sqlx::query_as(
        "SELECT date::text, balance, invested_amount FROM balance_snapshot ORDER BY date",
    )
    .fetch_all(&s.app.state.db)
    .await
    .unwrap();
    assert_eq!(
        snapshots,
        vec![
            (day(today, 5), d("130"), d("130")),
            (day(today, 2), d("100"), d("100")),
            (today.to_string(), d("100"), d("100")),
        ],
        "the end of each day is today's balance minus what came after"
    );
}

#[tokio::test]
async fn the_securities_history_is_valued_from_recorded_prices() {
    let s = Synced::new().await;
    let today = s.today();
    for (days_ago, price) in [(3, "100"), (2, "110"), (1, "120")] {
        sqlx::query(
            "INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ('FR0000120271', $1, $2, now())",
        )
        .bind(today - chrono::Days::new(days_ago))
        .bind(d(price))
        .execute(&s.app.state.db)
        .await
        .unwrap();
    }
    let buy = |days_ago: u64, quantity: &str, kind: &str| {
        json!({
            "externalId": format!("op-{days_ago}"), "date": day(today, days_ago), "label": "ACME",
            "amount": n("-1"), "txType": kind, "quantity": n(quantity), "unitPrice": n("90"),
            "fees": n("1"), "isin": "FR0000120271"
        })
    };

    s.sync(json!([pea(
        vec![position(Some("FR0000120271"), "ACME", "5", "600")],
        "600",
        "0",
        vec![buy(3, "10", "BUY"), buy(2, "5", "SELL")]
    )]))
    .await;

    let snapshots: Vec<(String, Decimal, Decimal)> = sqlx::query_as(
        "SELECT date::text, balance, invested_amount FROM balance_snapshot WHERE date < $1 ORDER BY date",
    )
    .bind(today)
    .fetch_all(&s.app.state.db)
    .await
    .unwrap();
    assert_eq!(
        snapshots,
        vec![
            (day(today, 3), d("1000"), d("901")),
            (day(today, 2), d("550"), d("450.5")),
            (day(today, 1), d("600"), d("450.5")),
        ],
        "cost is quantity x price plus fees, a sale removes it at the average"
    );
}

#[tokio::test]
async fn an_expired_session_is_deactivated_and_stays_observable() {
    let sidecar = MockServer::start().await;
    fail(
        &sidecar,
        "/accounts",
        401,
        json!({ "detail": "SESSION_EXPIRED" }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;

    client.post("/api/fortuneo/sync").send().await;
    settle(&app).await;

    let after = status(&client).await;
    assert_eq!(after["isActive"], false);
    assert_eq!(after["lastSyncError"], "SESSION_EXPIRED");
    let again = client.post("/api/fortuneo/sync").send().await;
    assert_eq!(again.status, 422);
    assert_eq!(
        again.json()["detail"],
        "The Fortuneo session expired. Please reconnect."
    );
}

#[tokio::test]
async fn status_and_clear_use_only_the_current_member() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (owner, owner_id) = admin(&app).await;
    let (other, _) = member(&app, "bob").await;
    store_session(&app, BANK, owner_id, "plain-state").await;

    assert_eq!(status(&other).await["isActive"], false);
    assert_eq!(
        other.delete("/api/fortuneo/session").send().await.status,
        204
    );
    assert_eq!(status(&owner).await["isActive"], true);
    assert_eq!(
        owner.delete("/api/fortuneo/session").send().await.status,
        204
    );
    assert!(session_of(&app, BANK, owner_id).await.is_none());
}

#[tokio::test]
async fn interrupted_syncs_become_retryable_failures() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let id = store_session(&app, BANK, member_id, "plain-state").await;
    sqlx::query("UPDATE sidecar_session SET sync_status = 'QUEUED' WHERE id = $1")
        .bind(id)
        .execute(&app.state.db)
        .await
        .unwrap();
    assert_eq!(
        client.post("/api/fortuneo/sync").send().await.status,
        202,
        "an import in flight is left alone"
    );

    let recovered = picsou::connectors::fortuneo::recover_interrupted_syncs(&app.state)
        .await
        .unwrap();

    assert_eq!(recovered, 1);
    assert_eq!(status(&client).await["lastSyncError"], "INTERNAL_ERROR");
}
