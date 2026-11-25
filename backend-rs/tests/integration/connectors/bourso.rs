//! BoursoController and BoursoSyncService over HTTP, the sidecar stubbed.

use picsou::ledger::sidecar::{SidecarBank, SidecarErrorCode};
use picsou::testing::{Client, TestApp};
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{body_json, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::support::{
    admin, answer, app_with_sidecar, count, d, fail, member, n, session_of, settle, store_session,
};

const URL: &str = "BOURSO_AUTH_URL";
const BANK: SidecarBank = SidecarBank::Bourso;
const IBAN: &str = "FR7630006000011234567890189";

/// external id, type, provider, cash balance, color, IBAN.
type AccountRow = (
    String,
    String,
    String,
    Option<Decimal>,
    String,
    Option<String>,
);

fn position(isin: Option<&str>, symbol: &str, quantity: &str, value: &str) -> Value {
    json!({
        "isin": isin, "symbol": symbol, "label": format!("{symbol} SA"), "quantity": n(quantity),
        "buyingPriceEur": 80, "currentPrice": 100, "quoteCurrency": "EUR",
        "currentValueEur": n(value), "pnlEur": 200
    })
}

fn checking(balance: &str) -> Value {
    json!({
        "externalId": "chk-1", "name": "Compte courant", "type": "CHECKING", "balanceEur": n(balance),
        "positions": [], "snapshotComplete": true, "iban": IBAN, "transactions": []
    })
}

fn pea(positions: Vec<Value>, balance: &str, cash: &str) -> Value {
    json!({
        "externalId": "pea-1", "name": "PEA", "type": "PEA", "balanceEur": n(balance),
        "cashBalance": n(cash), "positions": positions, "snapshotComplete": true
    })
}

fn life(positions: Vec<Value>, balance: &str, cash: &str, lines: Option<i32>) -> Value {
    json!({
        "externalId": "av-1", "name": "Assurance vie", "type": "ASSURANCE_VIE", "balanceEur": n(balance),
        "cashBalance": n(cash), "positions": positions, "snapshotComplete": true,
        "valuationLineCount": lines
    })
}

async fn status(client: &Client) -> Value {
    client.get("/api/bourso/status").send().await.json()
}

/// Syncs a stored session against `body` and returns the status after the job.
async fn synced(body: Value) -> (TestApp, Client, i64, Value, MockServer) {
    let sidecar = MockServer::start().await;
    answer(&sidecar, "/accounts", body).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;
    let queued = client.post("/api/bourso/sync").send().await;
    assert_eq!(queued.status, 202, "{}", queued.text());
    settle(&app).await;
    let after = status(&client).await;
    (app, client, member_id, after, sidecar)
}

async fn stored_credentials(app: &TestApp, member_id: i64) -> Option<String> {
    session_of(app, BANK, member_id)
        .await
        .and_then(|s| s.encrypted_credentials)
        .map(|c| app.state.crypto.decrypt(&c).expect("decrypt"))
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
async fn a_pending_app_push_stores_nothing_until_complete_then_keeps_the_credentials() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .and(body_json(
            json!({ "customerId": "12345678", "password": "246810" }),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "processId": "bourso-p-1", "mfaRequired": true, "mfaType": "app"
        })))
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/complete"))
        .and(body_json(
            json!({ "processId": "bourso-p-1", "code": null }),
        ))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "sessionState": "plain-state" })),
        )
        .expect(1)
        .mount(&sidecar)
        .await;
    answer(&sidecar, "/accounts", json!([checking("100.50")])).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let initiated = client
        .post("/api/bourso/auth/initiate")
        .json(&json!({ "customerId": "12345678", "password": "246810" }))
        .send()
        .await;
    assert_eq!(initiated.status, 200);
    assert_eq!(
        initiated.json(),
        json!({ "processId": "bourso-p-1", "mfaRequired": true, "mfaType": "app" })
    );
    assert!(session_of(&app, BANK, member_id).await.is_none());

    let completed = client
        .post("/api/bourso/auth/complete")
        .json(&json!({ "processId": "bourso-p-1", "code": "ignored" }))
        .send()
        .await;
    settle(&app).await;

    assert_eq!(completed.status, 200, "{}", completed.text());
    assert_eq!(completed.json()["isActive"], true);
    let stored: Value = serde_json::from_str(
        &stored_credentials(&app, member_id)
            .await
            .expect("credentials"),
    )
    .unwrap();
    assert_eq!(
        stored,
        json!({ "customerId": "12345678", "password": "246810" })
    );
    let session = session_of(&app, BANK, member_id).await.unwrap();
    assert_eq!(
        app.state.crypto.decrypt(&session.session_state).unwrap(),
        "plain-state"
    );
    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
}

#[tokio::test]
async fn initiate_without_mfa_stores_the_session_with_its_credentials_and_imports() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "mfaRequired": false, "sessionState": "plain-state" }),
    )
    .await;
    Mock::given(method("POST"))
        .and(path("/accounts"))
        .and(body_json(json!({ "sessionState": "plain-state" })))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([checking("20810.50")])))
        .expect(1)
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let response = client
        .post("/api/bourso/auth/initiate")
        .json(&json!({ "customerId": "12345678", "password": "246810" }))
        .send()
        .await;
    settle(&app).await;

    assert_eq!(response.status, 200);
    assert_eq!(response.json(), json!({ "mfaRequired": false }));
    assert!(stored_credentials(&app, member_id).await.is_some());
    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
}

#[tokio::test]
async fn completing_another_members_login_is_an_expired_attempt() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "processId": "bourso-p-2", "mfaRequired": true, "mfaType": "app" }),
    )
    .await;
    answer(
        &sidecar,
        "/complete",
        json!({ "sessionState": "plain-state" }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (owner, _) = admin(&app).await;
    let (other, other_id) = member(&app, "bob").await;
    owner
        .post("/api/bourso/auth/initiate")
        .json(&json!({ "customerId": "12345678", "password": "246810" }))
        .send()
        .await;

    let response = other
        .post("/api/bourso/auth/complete")
        .json(&json!({ "processId": "bourso-p-2" }))
        .send()
        .await;

    assert_eq!(response.status, 422);
    assert_eq!(response.json()["code"], "AUTH_ATTEMPT_EXPIRED");
    assert_eq!(
        response.json()["detail"],
        "BoursoBank authentication attempt expired"
    );
    assert!(session_of(&app, BANK, other_id).await.is_none());
}

#[tokio::test]
async fn a_disconnect_drops_the_pending_login_so_no_credentials_are_kept() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "processId": "bourso-p-3", "mfaRequired": true, "mfaType": "app" }),
    )
    .await;
    answer(
        &sidecar,
        "/complete",
        json!({ "sessionState": "plain-state" }),
    )
    .await;
    answer(&sidecar, "/accounts", json!([checking("1")])).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    client
        .post("/api/bourso/auth/initiate")
        .json(&json!({ "customerId": "12345678", "password": "246810" }))
        .send()
        .await;

    let cleared = client.delete("/api/bourso/session").send().await;
    client
        .post("/api/bourso/auth/complete")
        .json(&json!({ "processId": "bourso-p-3" }))
        .send()
        .await;
    settle(&app).await;

    assert_eq!(cleared.status, 204);
    assert!(session_of(&app, BANK, member_id).await.is_some());
    assert!(stored_credentials(&app, member_id).await.is_none());
}

#[tokio::test]
async fn an_unconfirmed_push_and_an_unsupported_factor_reach_the_client_as_their_codes() {
    let sidecar = MockServer::start().await;
    fail(&sidecar, "/complete", 401, json!({})).await;
    fail(
        &sidecar,
        "/initiate",
        422,
        json!({ "detail": "MFA_TYPE_UNSUPPORTED" }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let complete = client
        .post("/api/bourso/auth/complete")
        .json(&json!({ "processId": "bourso-p-4" }))
        .send()
        .await;
    let initiate = client
        .post("/api/bourso/auth/initiate")
        .json(&json!({ "customerId": "12345678", "password": "246810" }))
        .send()
        .await;

    assert_eq!(complete.status, 422);
    assert_eq!(complete.json()["code"], "APP_VALIDATION_TIMEOUT");
    assert_eq!(
        complete.json()["detail"],
        BANK.message(SidecarErrorCode::AppValidationTimeout)
            .unwrap()
    );
    assert_eq!(initiate.status, 422);
    assert_eq!(initiate.json()["code"], "MFA_TYPE_UNSUPPORTED");
}

#[tokio::test]
async fn payload_validation_rejects_missing_and_oversized_fields_before_the_budget() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    for _ in 0..6 {
        let initiate = client
            .post("/api/bourso/auth/initiate")
            .json(&json!({ "customerId": "x".repeat(101) }))
            .send()
            .await;
        assert_eq!(initiate.status, 422);
        let errors = &initiate.json()["errors"];
        assert_eq!(errors["customerId"], "size must be between 0 and 100");
        assert_eq!(errors["password"], "must not be blank");
    }
    let complete = client
        .post("/api/bourso/auth/complete")
        .json(&json!({ "processId": " " }))
        .send()
        .await;

    assert_eq!(complete.status, 422);
    assert_eq!(complete.json()["errors"]["processId"], "must not be blank");
    assert!(sidecar.received_requests().await.unwrap().is_empty());
}

#[tokio::test]
async fn the_sixth_authentication_attempt_is_rate_limited_on_its_own_budget() {
    let sidecar = MockServer::start().await;
    fail(&sidecar, "/initiate", 401, json!({})).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    for _ in 0..5 {
        let response = client
            .post("/api/bourso/auth/initiate")
            .json(&json!({ "customerId": "12345678", "password": "wrong" }))
            .send()
            .await;
        assert_eq!(response.json()["code"], "INVALID_CREDENTIALS");
    }

    let limited = client
        .post("/api/bourso/auth/complete")
        .json(&json!({ "processId": "bourso-p-5" }))
        .send()
        .await;
    let sync = client.post("/api/bourso/sync").send().await;

    assert_eq!(limited.status, 429);
    assert_eq!(
        limited.json()["detail"],
        "Too many BoursoBank authentication attempts. Please wait before retrying."
    );
    assert_eq!(sync.status, 422, "the sync budget is separate");
}

#[tokio::test]
async fn the_eleventh_sync_from_one_ip_is_rate_limited() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    for _ in 0..10 {
        let response = client.post("/api/bourso/sync").send().await;
        assert_eq!(response.status, 422);
        assert_eq!(response.json()["code"], "SESSION_EXPIRED");
    }

    let limited = client.post("/api/bourso/sync").send().await;

    assert_eq!(limited.status, 429);
    assert_eq!(
        limited.json()["detail"],
        "Too many BoursoBank synchronization requests. Please wait before retrying."
    );
}

#[tokio::test]
async fn a_sync_writes_every_account_with_its_own_envelope() {
    let (app, _, member_id, after, _sidecar) = synced(json!([
        checking("20810.50"),
        {
            "externalId": "bourso_liv-1", "name": "Livret A", "type": "LIVRET_A", "balanceEur": 1500,
            "cashBalance": 1500, "positions": [], "snapshotComplete": true
        },
        pea(vec![position(Some("FR0000120271"), "1rPACME", "10", "1000")], "1250", "250"),
    ]))
    .await;

    assert_eq!(after["syncStatus"], "SUCCESS");
    let rows: Vec<AccountRow> = picsou::db::query_as(
        "SELECT external_account_id, type::text, provider, cash_balance, color, iban FROM account \
             WHERE member_id = $1 ORDER BY id",
    )
    .bind(member_id)
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            (
                "bourso_chk-1".into(),
                "CHECKING".into(),
                "BoursoBank".into(),
                None,
                "#ec4899".into(),
                Some(IBAN.into())
            ),
            (
                "bourso_liv-1".into(),
                "LIVRET_A".into(),
                "BoursoBank".into(),
                None,
                "#f59e0b".into(),
                None
            ),
            (
                "bourso_pea-1".into(),
                "PEA".into(),
                "BoursoBank".into(),
                Some(d("250")),
                "#10b981".into(),
                None
            ),
        ],
        "a cash account has no cash leg"
    );
    let snapshots: Vec<(Decimal, Decimal)> = picsou::db::query_as(
        "SELECT balance, invested_amount FROM balance_snapshot ORDER BY account_id",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        snapshots,
        vec![
            (d("20810.50"), d("20810.50")),
            (d("1500"), d("1500")),
            (d("1250"), d("1050")),
        ],
        "a cash account's basis is derived, a PEA's is cash plus 10 x 80"
    );
    let holding: (String, Decimal, Decimal, Option<String>) = picsou::db::query_as(
        "SELECT ticker, provider_value_eur, provider_pnl_eur, isin FROM account_holding",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        holding,
        (
            "FR0000120271".into(),
            d("1000"),
            d("200"),
            Some("FR0000120271".into())
        )
    );
    let isin: String =
        picsou::db::query_scalar("SELECT isin FROM security_profile WHERE ticker = 'FR0000120271'")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(isin, "FR0000120271");
}

#[tokio::test]
async fn the_transactions_of_a_cash_account_are_imported_once() {
    let mut account = checking("100");
    account["transactions"] = json!([
        { "externalId": "op-1", "date": "2024-03-01", "description": "CB BOULANGERIE", "amount": n("-4.20") },
        { "externalId": "op-2", "date": "2024-03-02", "description": "VIR SALAIRE", "amount": n("2000.00") }
    ]);
    let (app, client, _, _, _sidecar) = synced(json!([account])).await;
    let again = client.post("/api/bourso/sync").send().await;
    assert_eq!(again.status, 202);
    settle(&app).await;
    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");

    let keys: Vec<(String, Decimal)> = picsou::db::query_as(
        "SELECT external_transaction_id, amount FROM \"transaction\" ORDER BY date",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        keys,
        vec![
            ("bourso:op-1".into(), d("-4.20")),
            ("bourso:op-2".into(), d("2000.00"))
        ]
    );
}

#[tokio::test]
async fn a_legacy_account_with_the_same_verified_iban_is_taken_over() {
    let sidecar = MockServer::start().await;
    answer(&sidecar, "/accounts", json!([checking("20810.50")])).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let legacy: i64 = picsou::db::query_scalar(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, is_manual, color, iban, \
         external_account_id) VALUES ($1, 'Boursorama Banque', 'CHECKING', 'Boursorama Banque', 'EUR', 1, false, \
         '#123456', $2, 'eb-account-id') RETURNING id",
    )
    .bind(member_id)
    .bind(IBAN)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    store_session(&app, BANK, member_id, "plain-state").await;

    client.post("/api/bourso/sync").send().await;
    settle(&app).await;

    let rows: Vec<(i64, String, String, String)> = picsou::db::query_as(
        "SELECT id, external_account_id, provider, color FROM account WHERE member_id = $1",
    )
    .bind(member_id)
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![(
            legacy,
            "bourso_chk-1".into(),
            "BoursoBank".into(),
            "#123456".into()
        )]
    );
}

#[tokio::test]
async fn an_invalid_iban_matches_nothing_and_a_deleted_alias_is_not_resurrected() {
    let mut wrong_checksum = checking("1");
    wrong_checksum["iban"] = json!("FR7630006000011234567890188");
    let sidecar = MockServer::start().await;
    answer(&sidecar, "/accounts", json!([wrong_checksum])).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    picsou::db::query(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, is_manual, color, iban) \
         VALUES ($1, 'Old', 'CHECKING', 'Boursorama', 'EUR', 1, false, '#123456', 'FR7630006000011234567890188')",
    )
    .bind(member_id)
    .execute(&app.state.db)
    .await
    .unwrap();
    store_session(&app, BANK, member_id, "plain-state").await;
    client.post("/api/bourso/sync").send().await;
    settle(&app).await;
    assert_eq!(
        count(
            &app,
            "SELECT count(*) FROM account WHERE external_account_id = 'bourso_chk-1'"
        )
        .await,
        1,
        "an unverified IBAN creates a new account"
    );

    let (deleted_app, deleted_client, deleted_member, _deleted_sidecar) = {
        let sidecar = MockServer::start().await;
        answer(&sidecar, "/accounts", json!([checking("1")])).await;
        let app = app_with_sidecar(URL, &sidecar).await;
        let (client, member_id) = admin(&app).await;
        (app, client, member_id, sidecar)
    };
    picsou::db::query(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, is_manual, color, iban, \
         deleted_at) VALUES ($1, 'Old', 'CHECKING', 'Boursorama', 'EUR', 1, false, '#123456', $2, now())",
    )
    .bind(deleted_member)
    .bind(IBAN)
    .execute(&deleted_app.state.db)
    .await
    .unwrap();
    store_session(&deleted_app, BANK, deleted_member, "plain-state").await;
    deleted_client.post("/api/bourso/sync").send().await;
    settle(&deleted_app).await;
    assert_eq!(status(&deleted_client).await["syncStatus"], "SUCCESS");
    assert_eq!(
        count(
            &deleted_app,
            "SELECT count(*) FROM account WHERE deleted_at IS NULL"
        )
        .await,
        0
    );
}

#[tokio::test]
async fn a_life_contract_reconciles_within_a_cent_per_line() {
    let supports = vec![
        position(Some("IE00BYX2JD69"), "1rASUSW", "100", "1200.01"),
        position(Some("LU0123456789"), "0P0000ABCD", "40", "999.99"),
    ];
    let (app, _, _, after, _sidecar) = synced(json!([life(
        supports.clone(),
        "3300.03",
        "1100.00",
        Some(3)
    )]))
    .await;
    assert_eq!(after["syncStatus"], "SUCCESS");
    let (kind, cash, color): (String, Decimal, String) =
        picsou::db::query_as("SELECT type::text, cash_balance, color FROM account")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(
        (kind.as_str(), cash, color.as_str()),
        ("ASSURANCE_VIE", d("1100.00"), "#8b5cf6")
    );
    assert_eq!(count(&app, "SELECT count(*) FROM account_holding").await, 2);

    let (_, _, _, short, _s1) = synced(json!([life(
        supports.clone(),
        "5300.00",
        "1100.00",
        Some(3)
    )]))
    .await;
    assert_eq!(short["lastSyncError"], "PORTFOLIO_INCOMPLETE");
    let (_, _, _, uncounted, _s2) =
        synced(json!([life(supports.clone(), "3300.00", "1100.00", None)])).await;
    assert_eq!(uncounted["lastSyncError"], "INVALID_DATA");
    let (_, _, _, too_few, _s3) =
        synced(json!([life(supports, "3300.00", "1100.00", Some(1))])).await;
    assert_eq!(too_few["lastSyncError"], "INVALID_DATA");
}

#[tokio::test]
async fn a_portfolio_that_does_not_reconcile_keeps_the_last_good_one() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/accounts"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([pea(
            vec![position(None, "1rPACME", "10", "1000")],
            "1000",
            "0"
        )])))
        .up_to_n_times(1)
        .mount(&sidecar)
        .await;
    answer(
        &sidecar,
        "/accounts",
        json!([pea(
            vec![position(None, "1rPACME", "10", "1000")],
            "4000",
            "0"
        )]),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;
    client.post("/api/bourso/sync").send().await;
    settle(&app).await;

    client.post("/api/bourso/sync").send().await;
    settle(&app).await;

    let after = status(&client).await;
    assert_eq!(after["lastSyncError"], "PORTFOLIO_INCOMPLETE");
    let kept: (String, Decimal) =
        picsou::db::query_as("SELECT ticker, quantity FROM account_holding")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(
        kept,
        ("1rPACME".into(), d("10")),
        "the symbol is the ticker without an ISIN"
    );
    let balance: Decimal = picsou::db::query_scalar("SELECT current_balance FROM account")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(balance, d("1000"));
}

#[tokio::test]
async fn lines_merge_only_when_they_carry_the_same_isin() {
    let (app, _, _, merged, _sidecar) = synced(json!([pea(
        vec![
            position(Some("FR0000120271"), "1rPACME", "10", "1000"),
            position(Some("fr0000120271"), "1rPACME", "30", "3000"),
        ],
        "4000",
        "0"
    )]))
    .await;
    assert_eq!(merged["syncStatus"], "SUCCESS");
    let holding: (Decimal, Decimal) =
        picsou::db::query_as("SELECT quantity, provider_value_eur FROM account_holding")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(holding, (d("40"), d("4000")));

    let cases = [
        vec![
            position(Some("FR0000120271"), "A", "10", "1000"),
            position(None, "FR0000120271", "10", "1000"),
        ],
        vec![
            position(None, "SYM", "10", "1000"),
            position(None, "SYM", "10", "1000"),
        ],
    ];
    for positions in cases {
        let (app, _, _, after, _sidecar) = synced(json!([pea(positions, "2000", "0")])).await;
        assert_eq!(after["lastSyncError"], "INVALID_DATA");
        assert_eq!(count(&app, "SELECT count(*) FROM account").await, 0);
    }
}

#[tokio::test]
async fn every_invalid_snapshot_is_refused_whole() {
    let mut cash_with_positions = checking("1000");
    cash_with_positions["positions"] = json!([position(None, "A", "1", "1000")]);
    let mut incomplete = checking("1");
    incomplete["snapshotComplete"] = json!(false);
    let mut loan = checking("1");
    loan["type"] = json!("LOAN");
    let mut no_cash = pea(vec![], "0", "0");
    no_cash["cashBalance"] = Value::Null;
    let cases = [
        (json!([cash_with_positions]), "INVALID_DATA"),
        (json!([incomplete]), "PORTFOLIO_INCOMPLETE"),
        (json!([loan]), "INVALID_DATA"),
        (json!([no_cash]), "INVALID_DATA"),
        (json!([checking("1"), checking("2")]), "INVALID_DATA"),
        (json!([]), "PORTFOLIO_INCOMPLETE"),
    ];
    for (body, code) in cases {
        let (app, _, _, after, _sidecar) = synced(body.clone()).await;
        assert_eq!(after["lastSyncError"], code, "{body}");
        assert_eq!(after["isActive"], true, "{body}");
        assert_eq!(
            count(&app, "SELECT count(*) FROM account").await,
            0,
            "{body}"
        );
    }
}

#[tokio::test]
async fn an_account_the_member_deleted_is_not_resurrected() {
    let sidecar = MockServer::start().await;
    answer(&sidecar, "/accounts", json!([checking("1")])).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    picsou::db::query(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, is_manual, color, \
         external_account_id, deleted_at) VALUES ($1, 'Gone', 'CHECKING', 'BoursoBank', 'EUR', 1, false, '#000000', \
         'bourso_chk-1', now())",
    )
    .bind(member_id)
    .execute(&app.state.db)
    .await
    .unwrap();
    store_session(&app, BANK, member_id, "plain-state").await;

    client.post("/api/bourso/sync").send().await;
    settle(&app).await;

    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
    assert_eq!(
        count(
            &app,
            "SELECT count(*) FROM account WHERE deleted_at IS NULL"
        )
        .await,
        0
    );
}

/// A stored session with credentials, as a completed login with app push leaves it.
async fn store_session_with_credentials(app: &TestApp, member_id: i64) {
    let id = store_session(app, BANK, member_id, "old-state").await;
    let credentials = app
        .state
        .crypto
        .encrypt(r#"{"customerId":"12345678","password":"246810"}"#)
        .unwrap();
    picsou::db::query("UPDATE sidecar_session SET encrypted_credentials = $2 WHERE id = $1")
        .bind(id)
        .bind(credentials)
        .execute(&app.state.db)
        .await
        .unwrap();
}

async fn expired_accounts(sidecar: &MockServer) {
    Mock::given(method("POST"))
        .and(path("/accounts"))
        .and(body_json(json!({ "sessionState": "old-state" })))
        .respond_with(
            ResponseTemplate::new(401).set_body_json(json!({ "detail": "SESSION_EXPIRED" })),
        )
        .mount(sidecar)
        .await;
}

#[tokio::test]
async fn an_expired_session_logs_in_again_once_and_keeps_the_fresh_state() {
    let sidecar = MockServer::start().await;
    expired_accounts(&sidecar).await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .and(body_json(
            json!({ "customerId": "12345678", "password": "246810" }),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "mfaRequired": false, "sessionState": "fresh-state"
        })))
        .expect(1)
        .mount(&sidecar)
        .await;
    Mock::given(method("POST"))
        .and(path("/accounts"))
        .and(body_json(json!({ "sessionState": "fresh-state" })))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([checking("5")])))
        .expect(1)
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session_with_credentials(&app, member_id).await;

    client.post("/api/bourso/sync").send().await;
    settle(&app).await;

    assert_eq!(status(&client).await["syncStatus"], "SUCCESS");
    let session = session_of(&app, BANK, member_id).await.unwrap();
    assert_eq!(
        app.state.crypto.decrypt(&session.session_state).unwrap(),
        "fresh-state"
    );
    assert_eq!(count(&app, "SELECT count(*) FROM account").await, 1);
}

#[tokio::test]
async fn an_expired_session_that_cannot_log_in_unattended_asks_to_reconnect() {
    let without_credentials = MockServer::start().await;
    expired_accounts(&without_credentials).await;
    let app = app_with_sidecar(URL, &without_credentials).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "old-state").await;
    client.post("/api/bourso/sync").send().await;
    settle(&app).await;
    let after = status(&client).await;
    assert_eq!(
        (after["isActive"].clone(), after["lastSyncError"].clone()),
        (json!(false), json!("SESSION_EXPIRED"))
    );
    let calls = without_credentials.received_requests().await.unwrap();
    assert!(
        calls.iter().all(|r| r.url.path() != "/initiate"),
        "no login without credentials"
    );

    let push = MockServer::start().await;
    expired_accounts(&push).await;
    answer(
        &push,
        "/initiate",
        json!({ "processId": "bourso-p-6", "mfaRequired": true, "mfaType": "app" }),
    )
    .await;
    let app = app_with_sidecar(URL, &push).await;
    let (client, member_id) = admin(&app).await;
    store_session_with_credentials(&app, member_id).await;
    client.post("/api/bourso/sync").send().await;
    settle(&app).await;
    let after = status(&client).await;
    assert_eq!(after["isActive"], false);
    assert_eq!(after["lastSyncError"], "SESSION_EXPIRED");
    let logins = push.received_requests().await.unwrap();
    assert_eq!(
        logins
            .iter()
            .filter(|r| r.url.path() == "/initiate")
            .count(),
        1
    );
}

#[tokio::test]
async fn rejected_stored_credentials_are_cleared() {
    let sidecar = MockServer::start().await;
    expired_accounts(&sidecar).await;
    fail(&sidecar, "/initiate", 401, json!({})).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session_with_credentials(&app, member_id).await;

    client.post("/api/bourso/sync").send().await;
    settle(&app).await;

    let after = status(&client).await;
    assert_eq!(after["lastSyncError"], "INVALID_CREDENTIALS");
    assert!(stored_credentials(&app, member_id).await.is_none());
}

#[tokio::test]
async fn a_transient_failure_keeps_the_session_usable() {
    let sidecar = MockServer::start().await;
    fail(&sidecar, "/accounts", 503, json!({})).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    store_session(&app, BANK, member_id, "plain-state").await;

    client.post("/api/bourso/sync").send().await;
    settle(&app).await;

    let after = status(&client).await;
    assert_eq!(after["isActive"], true);
    assert_eq!(after["lastSyncError"], "UPSTREAM_UNAVAILABLE");
}

#[tokio::test]
async fn status_and_clear_use_only_the_current_member() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (owner, owner_id) = admin(&app).await;
    let (other, _) = member(&app, "bob").await;
    store_session(&app, BANK, owner_id, "plain-state").await;

    assert_eq!(status(&other).await["isActive"], false);
    assert_eq!(other.delete("/api/bourso/session").send().await.status, 204);
    assert!(session_of(&app, BANK, owner_id).await.is_some());
    assert_eq!(status(&owner).await["isActive"], true);
    assert_eq!(owner.delete("/api/bourso/session").send().await.status, 204);
    assert!(session_of(&app, BANK, owner_id).await.is_none());
}

#[tokio::test]
async fn interrupted_syncs_become_retryable_failures() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let id = store_session(&app, BANK, member_id, "plain-state").await;
    picsou::db::query("UPDATE sidecar_session SET sync_status = 'RUNNING' WHERE id = $1")
        .bind(id)
        .execute(&app.state.db)
        .await
        .unwrap();

    let recovered = picsou::connectors::bourso::recover_interrupted_syncs(&app.state)
        .await
        .unwrap();

    assert_eq!(recovered, 1);
    let after = status(&client).await;
    assert_eq!(after["syncStatus"], "FAILED");
    assert_eq!(after["lastSyncError"], "INTERNAL_ERROR");
}
