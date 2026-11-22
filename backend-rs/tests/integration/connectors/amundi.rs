//! AmundiController and AmundiSyncService over HTTP, the sidecar stubbed.

use picsou::ledger::sidecar::SidecarBank;
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::MockServer;
use wiremock::matchers::{body_json, method, path};
use wiremock::{Mock, ResponseTemplate};

use super::support::{
    admin, answer, app_with_sidecar, count, d, fail, member, n, session_of, settle, store_session,
    sync_stored,
};

const URL: &str = "AMUNDI_AUTH_URL";
const BANK: SidecarBank = SidecarBank::Amundi;
const BASE: &str = "/api/amundi";

fn line(
    isin: &str,
    code: &str,
    label: &str,
    quantity: &str,
    value: &str,
    pnl: Option<&str>,
) -> Value {
    json!({
        "isin": isin, "fundCode": code, "label": label, "quantity": n(quantity), "unitValue": n("10"),
        "valueEur": n(value), "pnlEur": pnl.map(n)
    })
}

fn plan(id: &str, balance: &str, positions: Vec<Value>) -> Value {
    json!({
        "externalId": id, "name": "PEG", "planKind": "PEG", "employer": "ACME", "balanceEur": n(balance),
        "positions": positions, "snapshotComplete": true
    })
}

async fn synced(body: Value) -> (picsou::testing::TestApp, Value) {
    let sidecar = MockServer::start().await;
    answer(&sidecar, "/positions", body).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;
    let status = sync_stored(&app, &client, BANK, member_id, BASE).await;
    (app, status)
}

#[tokio::test]
async fn one_plan_becomes_one_account_with_its_fund_lines_and_cost_basis() {
    let (app, status) = synced(json!([plan(
        "p1",
        "1500",
        vec![
            line("FR0010000001", "C1", "Fonds A", "100", "1000", Some("200")),
            line("", "C2", "Fonds B", "50", "500", None),
        ]
    )]))
    .await;

    assert_eq!(status["syncStatus"], "SUCCESS");
    let (name, kind, provider, color): (String, String, String, String) = picsou::db::query_as(
        "SELECT name, type::text, provider, color FROM account WHERE external_account_id = 'amundi_p1'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        (
            name.as_str(),
            kind.as_str(),
            provider.as_str(),
            color.as_str()
        ),
        (
            "PEG — ACME",
            "EMPLOYEE_SAVINGS",
            "Amundi Épargne Salariale",
            "#8b5cf6"
        )
    );
    let rows: Vec<(String, Option<Decimal>, Option<String>)> = picsou::db::query_as(
        "SELECT ticker, average_buy_in, quote_currency FROM account_holding ORDER BY ticker",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            ("C2".into(), None, Some("EUR".into())),
            ("FR0010000001".into(), Some(d("8")), Some("EUR".into())),
        ],
        "a real ISIN wins, the fund code keys a line without one, no gain means no cost basis"
    );
    let invested: Decimal =
        picsou::db::query_scalar("SELECT invested_amount FROM balance_snapshot")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(invested, d("1500"), "one line without a gain: the balance");
}

#[tokio::test]
async fn zero_unit_lines_with_value_are_kept_and_empty_ones_dropped() {
    let (app, status) = synced(json!([plan(
        "p1",
        "100",
        vec![
            line("", "C1", "Fonds A", "0", "100", Some("0")),
            line("", "C2", "Fonds B", "0", "0", None),
        ]
    )]))
    .await;
    assert_eq!(status["syncStatus"], "SUCCESS");
    assert_eq!(count(&app, "SELECT count(*) FROM account_holding").await, 1);
}

#[tokio::test]
async fn the_same_fund_twice_is_merged_and_colliding_fallbacks_refused() {
    let (app, status) = synced(json!([plan(
        "p1",
        "1500",
        vec![
            line("", "C1", "Fonds A", "100", "1000", Some("100")),
            line("", "C1", "Fonds A", "50", "500", Some("50")),
        ]
    )]))
    .await;
    assert_eq!(status["syncStatus"], "SUCCESS");
    let quantity: Decimal = picsou::db::query_scalar("SELECT quantity FROM account_holding")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(quantity, d("150"));

    let (_, refused) = synced(json!([plan(
        "p1",
        "1500",
        vec![
            line("", "C1", "Fonds A", "100", "1000", None),
            line("", "C1", "Fonds Z", "50", "500", None),
        ]
    )]))
    .await;
    assert_eq!(refused["lastSyncError"], "INVALID_DATA");
}

#[tokio::test]
async fn a_disagreeing_total_an_incomplete_flag_or_duplicate_plans_keep_the_last_snapshot() {
    let mut flagged = plan("p1", "100", vec![]);
    flagged["snapshotComplete"] = json!(false);
    for (body, code) in [
        (
            json!([plan(
                "p1",
                "2000",
                vec![line("", "C1", "A", "1", "1000", None)]
            )]),
            "PORTFOLIO_INCOMPLETE",
        ),
        (json!([flagged]), "PORTFOLIO_INCOMPLETE"),
        (
            json!([plan("p1", "0", vec![]), plan("amundi_p1", "0", vec![])]),
            "INVALID_DATA",
        ),
        (
            json!([plan("p1", "0", vec![line("", "", "", "1", "0", None)])]),
            "INVALID_DATA",
        ),
    ] {
        let (app, status) = synced(body.clone()).await;
        assert_eq!(status["lastSyncError"], code, "{body}");
        assert_eq!(count(&app, "SELECT count(*) FROM account").await, 0);
    }
    let (_, within) = synced(json!([plan(
        "p1",
        "1000.04",
        vec![line("", "C1", "A", "1", "1000", None)]
    )]))
    .await;
    assert_eq!(
        within["syncStatus"], "SUCCESS",
        "rounding within tolerance reconciles"
    );
}

#[tokio::test]
async fn an_app_push_completes_with_an_explicit_null_code() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/complete"))
        .and(body_json(json!({ "processId": "p-1", "code": null })))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "sessionState": "s" })))
        .expect(1)
        .mount(&sidecar)
        .await;
    answer(&sidecar, "/positions", json!([plan("p1", "0", vec![])])).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let response = client
        .post("/api/amundi/auth/complete")
        .json(&json!({ "processId": "p-1" }))
        .send()
        .await;
    settle(&app).await;

    assert_eq!(response.status, 200, "{}", response.text());
    assert!(session_of(&app, BANK, member_id).await.is_some());
}

#[tokio::test]
async fn a_captcha_wall_and_a_wrong_code_reach_the_client_with_their_own_codes() {
    let sidecar = MockServer::start().await;
    fail(
        &sidecar,
        "/initiate",
        403,
        json!({ "detail": "CAPTCHA_BLOCKED" }),
    )
    .await;
    fail(&sidecar, "/complete", 401, json!({})).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let captcha = client
        .post("/api/amundi/auth/initiate")
        .json(&json!({ "login": "a", "password": "b" }))
        .send()
        .await;
    let otp = client
        .post("/api/amundi/auth/complete")
        .json(&json!({ "processId": "p", "code": "123456" }))
        .send()
        .await;

    assert_eq!(
        (captcha.status.as_u16(), captcha.json()["code"].clone()),
        (422, json!("CAPTCHA_BLOCKED"))
    );
    assert_eq!(otp.json()["code"], "INVALID_OTP");
}

#[tokio::test]
async fn validation_rate_limits_and_member_isolation() {
    let sidecar = MockServer::start().await;
    fail(&sidecar, "/initiate", 401, json!({})).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, member_id) = admin(&app).await;

    let bad = client
        .post("/api/amundi/auth/complete")
        .json(&json!({ "processId": "", "code": "12" }))
        .send()
        .await;
    assert_eq!(bad.status, 422);
    assert_eq!(bad.json()["errors"]["code"], r#"must match "\d{6}""#);
    for _ in 0..5 {
        client
            .post("/api/amundi/auth/initiate")
            .json(&json!({ "login": "a", "password": "b" }))
            .send()
            .await;
    }
    let limited = client
        .post("/api/amundi/auth/initiate")
        .json(&json!({ "login": "a", "password": "b" }))
        .send()
        .await;
    assert_eq!(limited.status, 429);
    assert_eq!(
        limited.json()["detail"],
        "Too many Amundi authentication attempts. Please wait before retrying."
    );

    store_session(&app, BANK, member_id, "s").await;
    let (other, _) = member(&app, "bob").await;
    assert_eq!(
        other.get("/api/amundi/status").send().await.json()["isActive"],
        false
    );
    assert_eq!(other.delete("/api/amundi/session").send().await.status, 204);
    assert!(session_of(&app, BANK, member_id).await.is_some());
}

#[tokio::test]
async fn the_eleventh_sync_from_one_ip_is_rate_limited_apart_from_logins() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    for _ in 0..10 {
        assert_eq!(client.post("/api/amundi/sync").send().await.status, 422);
    }
    let limited = client.post("/api/amundi/sync").send().await;
    assert_eq!(limited.status, 429);
    assert_eq!(
        limited.json()["detail"],
        "Too many Amundi synchronization requests. Please wait before retrying."
    );
}
