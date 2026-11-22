//! DashboardServiceTest, DashboardServiceLiabilityTest, HistoryServiceTest and
//! AccountConnectionServiceTest over HTTP.

use picsou::testing::TestApp;
use reqwest::StatusCode;
use serde_json::{Value, json};

use super::support::{PASSWORD, account, admin_member, exec, num, share, snapshot};

fn by_account(list: &Value, id: i64) -> &Value {
    list.as_array()
        .unwrap()
        .iter()
        .find(|i| i["accountId"].as_i64() == Some(id))
        .unwrap_or_else(|| panic!("no item for account {id} in {list}"))
}

#[tokio::test]
async fn dashboard_keeps_liabilities_apart_and_divides_each_side_by_its_own_total() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let cash = account(&app, me, "Courant", "CHECKING", "3000").await;
    let livret = account(&app, me, "Livret", "LIVRET_A", "1000").await;
    let card = account(&app, me, "Visa", "CREDIT_CARD", "-400").await;
    let peer = account(&app, me, "Prêt à Léa", "PEER_LOAN", "500").await;
    let body = app
        .login_admin()
        .await
        .get("/api/dashboard")
        .send()
        .await
        .json();
    assert_eq!(num(&body["totalNetWorth"]), "4100.00000000");
    assert_eq!(num(&body["totalLiabilities"]), "400.00000000");
    assert!(body.get("totalMonthlyPayment").is_none());
    let distribution = &body["distribution"];
    assert_eq!(
        distribution.as_array().unwrap().len(),
        2,
        "no peer loan, no liability"
    );
    assert_eq!(num(&by_account(distribution, cash)["percentage"]), "75.0");
    assert_eq!(
        num(&by_account(&body["assets"], peer)["percentage"]),
        "11.11"
    );
    assert_eq!(
        num(&by_account(&body["assets"], livret)["percentage"]),
        "22.22"
    );
    let liability = by_account(&body["liabilities"], card);
    assert_eq!(num(&liability["balanceEur"]), "400.00000000");
    assert_eq!(num(&liability["percentage"]), "100.0");
}

#[tokio::test]
async fn dashboard_leaves_hidden_and_child_accounts_out_of_the_totals() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let wallet = account(&app, me, "Revolut", "CHECKING", "1000").await;
    let pocket = account(&app, me, "Poche", "SAVINGS", "250").await;
    let hidden = account(&app, me, "Caché", "LIVRET_A", "7000").await;
    exec(&app, &format!("UPDATE account SET parent_account_id = {wallet} WHERE id = {pocket}; UPDATE account SET hidden = true WHERE id = {hidden}")).await;
    let body = app
        .login_admin()
        .await
        .get("/api/dashboard")
        .send()
        .await
        .json();
    assert_eq!(num(&body["totalNetWorth"]), "1000.00000000");
    assert_eq!(body["assets"].as_array().unwrap().len(), 1);
    let history = body["netWorthHistory"].as_array().unwrap();
    assert_eq!(num(&history.last().unwrap()["total"]), "1000.00000000");
}

#[tokio::test]
async fn dashboard_range_changes_only_the_history() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let id = account(&app, me, "Livret", "LIVRET_A", "1000").await;
    snapshot(&app, id, "2025-06-01", "800", "800").await;
    snapshot(&app, id, "2026-01-01", "900", "900").await;
    let admin = app.login_admin().await;
    let year = admin.get("/api/dashboard").send().await.json();
    let month = admin.get("/api/dashboard?range=1M").send().await.json();
    let all = admin.get("/api/dashboard?range=ALL").send().await.json();
    assert_eq!(year["totalNetWorth"], month["totalNetWorth"]);
    assert_eq!(year["netWorthHistory"].as_array().unwrap().len(), 3);
    assert_eq!(month["netWorthHistory"].as_array().unwrap().len(), 2);
    assert_eq!(all["netWorthHistory"], year["netWorthHistory"]);
}

#[tokio::test]
async fn a_co_owned_loan_weighs_its_payment_but_reports_its_own_progress() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let (_, bob) = app.create_member("bob", PASSWORD).await;
    let loan = account(&app, me, "Prêt", "LOAN", "150000").await;
    share(&app, loan, me, "50").await;
    share(&app, loan, bob, "50").await;
    exec(&app, &format!("INSERT INTO debt (account_id, member_id, borrowed_amount, monthly_payment) VALUES ({loan}, {me}, 200000, 1000)")).await;
    let body = app
        .login_admin()
        .await
        .get("/api/dashboard")
        .send()
        .await
        .json();
    let entry = by_account(&body["liabilities"], loan);
    assert_eq!(num(&entry["balanceEur"]), "75000.00000000");
    assert_eq!(num(&entry["monthlyPayment"]), "500.00000000");
    assert_eq!(num(&entry["percentPaid"]), "25.0");
    assert_eq!(num(&body["totalMonthlyPayment"]), "500.00000000");
    assert_eq!(num(&body["totalNetWorth"]), "-75000.00000000");
}

#[tokio::test]
async fn history_forward_fills_and_negates_loans_with_zero_invested() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let cash = account(&app, me, "Courant", "CHECKING", "500").await;
    let loan = account(&app, me, "Prêt", "LOAN", "100").await;
    snapshot(&app, cash, "2026-01-05", "400", "300").await;
    snapshot(&app, loan, "2026-01-10", "120", "120").await;
    let admin = app.login_admin().await;
    let body = admin
        .get(&format!(
            "/api/history?accountIds={cash},{loan}&months=1&split=true"
        ))
        .send()
        .await
        .json();
    let points = body.as_array().unwrap();
    assert_eq!(points.len(), 3);
    assert_eq!(points[0]["date"], "2026-01-05");
    assert_eq!(num(&points[0]["pnl"]), "100.00000000");
    assert_eq!(points[1]["date"], "2026-01-10");
    assert_eq!(num(&points[1]["total"]), "280.00000000");
    assert_eq!(
        num(&points[1]["invested"]),
        "300.00000000",
        "the loan invests nothing"
    );
    assert_eq!(
        num(&points[1]["accounts"][loan.to_string()]["invested"]),
        "0"
    );
    assert_eq!(points[2]["date"], "2026-01-15", "today is the live point");
    assert_eq!(num(&points[2]["total"]), "400.00000000");
}

#[tokio::test]
async fn history_lets_the_owner_read_an_account_they_hold_no_share_of_but_not_a_zero_share_stranger()
 {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let (_, bob) = app.create_member("bob", PASSWORD).await;
    let flat = account(&app, me, "Flat", "REAL_ESTATE", "1000").await;
    share(&app, flat, bob, "100").await;
    let admin = app.login_admin().await;
    let r = admin
        .get(&format!("/api/history?accountIds={flat}"))
        .send()
        .await;
    assert_eq!(r.status, StatusCode::OK);
    assert_eq!(
        num(&r.json()[0]["total"]),
        "0",
        "the owner's weight is zero"
    );
    let (_, carol) = app.create_member("carol", PASSWORD).await;
    let theirs = account(&app, carol, "Carol", "CHECKING", "1").await;
    let r = admin
        .get(&format!("/api/history?accountIds={flat},{theirs}"))
        .send()
        .await;
    assert_eq!(r.status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn history_requires_account_ids_and_binds_lists_like_spring() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let r = admin.get("/api/history?months=1").send().await;
    assert_eq!(r.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        r.json()["detail"],
        "Required parameter 'accountIds' is not present."
    );
    let r = admin.get("/api/history?accountIds=1,x").send().await;
    assert_eq!(r.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        admin.get("/api/history?accountIds=999").send().await.json(),
        json!([])
    );
}

#[tokio::test]
async fn pnl_counts_a_card_in_the_total_only_and_has_no_percent_without_investment() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let card = account(&app, me, "Visa", "CREDIT_CARD", "-200").await;
    let admin = app.login_admin().await;
    let body = admin
        .get(&format!("/api/history/pnl?accountIds={card}"))
        .send()
        .await
        .json();
    assert_eq!(num(&body["total"]), "-200.00000000");
    assert_eq!(num(&body["invested"]), "0");
    assert!(body.get("pnlPercent").is_none());
    let empty = admin
        .get("/api/history/pnl?accountIds=999")
        .send()
        .await
        .json();
    assert_eq!(empty, json!({"total": 0, "invested": 0, "pnl": 0}));
}

#[tokio::test]
async fn intraday_negates_a_loan_and_holds_cash_constant_over_the_day() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let cash = account(&app, me, "Courant", "CHECKING", "500").await;
    let loan = account(&app, me, "Prêt", "LOAN", "200").await;
    let body = app
        .login_admin()
        .await
        .get(&format!(
            "/api/history/net-worth/intraday?accountIds={cash},{loan}"
        ))
        .send()
        .await
        .json();
    let points = body.as_array().unwrap();
    assert_eq!(
        points.len(),
        25,
        "every hour of the last 24, both ends included"
    );
    assert_eq!(points[0]["timestamp"], "2026-01-14T11:00:00");
    for p in points {
        assert_eq!(num(&p["total"]), "300.00000000");
        assert_eq!(num(&p["invested"]), "500.00000000");
    }
}

#[tokio::test]
async fn deleting_the_last_bank_account_removes_its_requisition_but_a_sibling_keeps_it() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let req: i64 = picsou::db::query_scalar(
        "INSERT INTO requisition (requisition_id, institution_id, institution_name, status, member_id, created_at, updated_at) \
         VALUES ('r-1', 'BANK::FR', 'Ma Banque', 'LINKED', $1, now(), now()) RETURNING id",
    )
    .bind(me)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    let a = account(&app, me, "Courant", "CHECKING", "1").await;
    let b = account(&app, me, "Livret", "LIVRET_A", "1").await;
    exec(
        &app,
        &format!(
            "UPDATE account SET requisition_id = {req}, is_manual = false WHERE id IN ({a}, {b})"
        ),
    )
    .await;
    let admin = app.login_admin().await;
    let impact = admin
        .get(&format!("/api/accounts/{a}/deletion-impact"))
        .send()
        .await
        .json();
    assert_eq!(impact, json!({"removesConnection": false}));
    assert_eq!(
        admin
            .delete(&format!("/api/accounts/{a}"))
            .send()
            .await
            .status,
        StatusCode::NO_CONTENT
    );
    let impact = admin
        .get(&format!("/api/accounts/{b}/deletion-impact"))
        .send()
        .await
        .json();
    assert_eq!(
        impact,
        json!({"removesConnection": true, "connectionLabel": "Ma Banque"})
    );
    admin.delete(&format!("/api/accounts/{b}")).send().await;
    let left: i64 = picsou::db::query_scalar("SELECT count(*) FROM requisition")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(left, 0);
    assert_eq!(
        admin.get(&format!("/api/accounts/{b}")).send().await.status,
        StatusCode::NOT_FOUND
    );
}

#[tokio::test]
async fn deleting_an_account_unlinks_loans_and_tombstones_its_scpi_funds() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let house = account(&app, me, "Maison", "REAL_ESTATE", "1").await;
    let loan = account(&app, me, "Prêt", "LOAN", "1").await;
    let scpi = account(&app, me, "SCPI", "SCPI", "1").await;
    exec(&app, &format!(
        "INSERT INTO debt (account_id, member_id, linked_account_id, borrowed_amount) VALUES ({loan}, {me}, {house}, 10); \
         INSERT INTO scpi_position (account_id, member_id, share_count, corum_fund_code) VALUES ({scpi}, {me}, 1, 'CXS')"
    )).await;
    let admin = app.login_admin().await;
    let impact = admin
        .get(&format!("/api/accounts/{scpi}/deletion-impact"))
        .send()
        .await
        .json();
    assert_eq!(
        impact,
        json!({"removesConnection": true, "connectionLabel": "CORUM"})
    );
    admin.delete(&format!("/api/accounts/{house}")).send().await;
    admin.delete(&format!("/api/accounts/{scpi}")).send().await;
    let linked: Option<i64> = picsou::db::query_scalar("SELECT linked_account_id FROM debt")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(linked, None);
    let ignored: bool =
        picsou::db::query_scalar("SELECT ignored FROM scpi_provider_fund WHERE fund_code = 'CXS'")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert!(ignored);
    let loan_view = admin
        .get(&format!("/api/accounts/{loan}"))
        .send()
        .await
        .json();
    assert!(loan_view["debt"].get("linkedAccountId").is_none());
}
