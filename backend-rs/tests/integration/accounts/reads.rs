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
async fn dashboard_totals_ignore_the_tracked_fund() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let jar = account(&app, me, "Actions en EUR", "SAVINGS", "1000").await;
    let admin = app.login_admin().await;
    let before = admin.get("/api/dashboard").send().await.json();
    assert_eq!(num(&before["totalNetWorth"]), "1000.00000000");
    exec(
        &app,
        &format!("UPDATE account SET tracked_isin = 'IE00B4L5Y983' WHERE id = {jar}"),
    )
    .await;
    let after = admin.get("/api/dashboard").send().await.json();
    assert_eq!(after["totalNetWorth"], before["totalNetWorth"]);
    assert_eq!(after["assets"], before["assets"]);
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

#[tokio::test]
async fn deletion_impact_falls_back_to_provider_when_requisition_institution_name_is_null() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let req: i64 = picsou::db::query_scalar(
        "INSERT INTO requisition (requisition_id, institution_id, institution_name, status, member_id, created_at, updated_at) \
         VALUES ('r-null', 'BANK::FR', NULL, 'LINKED', $1, now(), now()) RETURNING id",
    )
    .bind(me)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    let a = account(&app, me, "Courant", "CHECKING", "10").await;
    exec(
        &app,
        &format!(
            "UPDATE account SET requisition_id = {req}, provider = 'Acme Bank', is_manual = false WHERE id = {a}"
        ),
    )
    .await;
    let admin = app.login_admin().await;
    let impact = admin
        .get(&format!("/api/accounts/{a}/deletion-impact"))
        .send()
        .await
        .json();
    assert_eq!(
        impact,
        json!({"removesConnection": true, "connectionLabel": "Acme Bank"})
    );
}

#[tokio::test]
async fn loan_summary_returns_400_on_overflowing_negative_amortisation_or_excessive_term() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let loan = account(&app, me, "Prêt", "LOAN", "100000").await;
    exec(
        &app,
        &format!(
            "INSERT INTO debt (account_id, member_id, borrowed_amount, interest_rate, monthly_payment, start_date, end_date) \
             VALUES ({loan}, {me}, 100000, 1.0, 0, '2025-01-01', '2105-01-01')"
        ),
    )
    .await;

    let admin = app.login_admin().await;
    let resp = admin
        .get(&format!("/api/accounts/{loan}/loan-summary"))
        .send()
        .await;
    assert_eq!(resp.status, StatusCode::BAD_REQUEST, "{}", resp.text());
    assert!(
        resp.json()["detail"]
            .as_str()
            .is_some_and(|s| !s.is_empty()),
        "400 response must carry an explanatory message"
    );

    // Reading the account itself must not fail even if a stored debt overflows.
    let acct_resp = admin.get(&format!("/api/accounts/{loan}")).send().await;
    assert_eq!(acct_resp.status, StatusCode::OK, "{}", acct_resp.text());

    // Saving an overflowing or overly long loan via PUT /debt is also rejected with 400.
    let put_overflow = admin
        .put(&format!("/api/accounts/{loan}/debt"))
        .json(&json!({
            "borrowedAmount": 100000,
            "interestRate": 1.0,
            "monthlyPayment": 0,
            "startDate": "2025-01-01",
            "endDate": "2105-01-01"
        }))
        .send()
        .await;
    assert_eq!(
        put_overflow.status,
        StatusCode::BAD_REQUEST,
        "{}",
        put_overflow.text()
    );

    let put_too_long = admin
        .put(&format!("/api/accounts/{loan}/debt"))
        .json(&json!({
            "borrowedAmount": 10000,
            "interestRate": 0,
            "monthlyPayment": 1,
            "startDate": "1000-01-01",
            "endDate": "5000-01-01"
        }))
        .send()
        .await;
    assert_eq!(
        put_too_long.status,
        StatusCode::BAD_REQUEST,
        "{}",
        put_too_long.text()
    );
}

#[tokio::test]
async fn dashboard_and_account_list_keep_their_figures_with_batched_reads() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;

    // 10 accounts across types: 4 PEA with holdings, 2 checking, 2 real estate, 2 linked loans.
    let mut pea_ids = Vec::new();
    for i in 0..4 {
        let pea = account(&app, me, &format!("PEA {i}"), "PEA", "0").await;
        exec(
            &app,
            &format!(
                "INSERT INTO account_holding (account_id, ticker, quantity, average_buy_in, created_at, updated_at) \
                 VALUES ({pea}, 'PSBATCH{i}', 2, 100, now(), now()); \
                 INSERT INTO price_snapshot (ticker, date, price_eur) \
                 VALUES ('PSBATCH{i}', '2026-01-14', 150)"
            ),
        )
        .await;
        pea_ids.push(pea);
    }
    for i in 0..2 {
        account(&app, me, &format!("Cash {i}"), "CHECKING", "1000").await;
    }
    for i in 0..2 {
        let house = account(&app, me, &format!("House {i}"), "REAL_ESTATE", "200000").await;
        let loan = account(&app, me, &format!("Loan {i}"), "LOAN", "100000").await;
        exec(
            &app,
            &format!(
                "INSERT INTO real_estate_metadata (account_id, member_id, purchase_price, valuation_mode) \
                 VALUES ({house}, {me}, 180000, 'ESTIMATED'); \
                 INSERT INTO debt (account_id, member_id, linked_account_id, borrowed_amount, monthly_payment) \
                 VALUES ({loan}, {me}, {house}, 150000, 800)"
            ),
        )
        .await;
    }

    let admin = app.login_admin().await;

    let dashboard = admin.get("/api/dashboard").send().await.json();
    // 4 PEA (4 * 2 * 150 = 1200) + 2 checking (2000) + 2 houses (400000) - 2 loans (200000) = 203200.
    assert_eq!(num(&dashboard["totalNetWorth"]), "203200.0000000000000000");
    assert_eq!(num(&dashboard["totalLiabilities"]), "200000.00000000");
    assert_eq!(num(&dashboard["totalMonthlyPayment"]), "1600.00");
    let history = dashboard["netWorthHistory"].as_array().unwrap();
    assert_eq!(
        num(&history.last().unwrap()["total"]),
        "203200.0000000000000000"
    );

    let accounts_list = admin.get("/api/accounts").send().await.json();
    let arr = accounts_list.as_array().unwrap();
    assert_eq!(arr.len(), 10);
    for pea in &pea_ids {
        let row = arr.iter().find(|a| a["id"].as_i64() == Some(*pea)).unwrap();
        assert_eq!(num(&row["currentBalanceEur"]), "300.0000000000000000");
    }
}

#[tokio::test]
async fn dashboard_includes_debt_payment_and_progress_for_credit_card_liability() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let card = account(&app, me, "Credit Card", "CREDIT_CARD", "-600").await;
    let admin = app.login_admin().await;

    let put = admin
        .put(&format!("/api/accounts/{card}/debt"))
        .json(&json!({
            "borrowedAmount": 1000,
            "interestRate": 0,
            "monthlyPayment": 120,
            "startDate": "2025-01-01",
            "endDate": "2027-01-01"
        }))
        .send()
        .await;
    assert_eq!(put.status, StatusCode::OK, "{}", put.text());

    let dashboard = admin.get("/api/dashboard").send().await.json();
    let entry = by_account(&dashboard["liabilities"], card);
    assert_eq!(num(&entry["balanceEur"]), "600.00000000");
    assert_eq!(num(&entry["monthlyPayment"]), "120.00");
    assert_eq!(num(&entry["percentPaid"]), "40.0");
    assert_eq!(num(&dashboard["totalMonthlyPayment"]), "120.00");

    // Valuation of the card itself must still ignore the debt schedule (LOAN only).
    let list = admin.get("/api/accounts").send().await.json();
    let row = list
        .as_array()
        .unwrap()
        .iter()
        .find(|a| a["id"].as_i64() == Some(card))
        .unwrap();
    assert_eq!(num(&row["currentBalanceEur"]), "-600.00000000");
    assert!(row.get("debt").is_none());
}

#[tokio::test]
async fn dashboard_values_synced_corum_life_with_ticker_and_zero_holdings_via_to_eur() {
    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let contract = account(&app, me, "Corum Life", "ASSURANCE_VIE", "10").await;
    let card = account(&app, me, "Corum Card", "CREDIT_CARD", "-4").await;
    exec(
        &app,
        &format!(
            "UPDATE account SET is_manual = false, external_account_id = 'corum_life_c1', \
             provider_invested_eur = 100, currency = 'EUR', ticker = 'PSCORUM' WHERE id = {contract}; \
             UPDATE account SET is_manual = false, external_account_id = 'corum_life_c2', \
             provider_invested_eur = 100, currency = 'EUR', ticker = 'PSCORUM' WHERE id = {card}; \
             INSERT INTO price_snapshot (ticker, date, price_eur) VALUES ('PSCORUM', '2026-01-14', 50)"
        ),
    )
    .await;

    let dashboard = app
        .login_admin()
        .await
        .get("/api/dashboard")
        .send()
        .await
        .json();
    assert_eq!(
        num(&by_account(&dashboard["assets"], contract)["balanceEur"]),
        "500.0000000000000000"
    );
    assert_eq!(
        num(&by_account(&dashboard["liabilities"], card)["balanceEur"]),
        "200.0000000000000000"
    );
    assert_eq!(num(&dashboard["totalLiabilities"]), "200.0000000000000000");
    assert_eq!(num(&dashboard["totalNetWorth"]), "300.0000000000000000");
}

#[tokio::test]
async fn loan_summary_weighs_money_fields_by_each_members_share_and_allows_co_owner_reads() {
    use picsou::ledger::access;
    use picsou::ledger::decimal::{HALF_UP, set_scale};
    use rust_decimal::Decimal;

    let app = TestApp::with_admin().await;
    let me = admin_member(&app).await;
    let (_, bob) = app.create_member("bob", PASSWORD).await;
    let (_, _carol) = app.create_member("carol", PASSWORD).await;
    let loan = account(&app, me, "Mortgage", "LOAN", "12000").await;

    let admin = app.login_admin().await;
    let bob_client = app.login("bob", PASSWORD).await;
    let carol_client = app.login("carol", PASSWORD).await;

    // TestApp's fixed clock is 2026-01-15: 12 monthly installments from 2025-07-15 to 2026-07-15
    // puts 6 installments paid and 6 remaining, with non-zero capital, interest, insurance and fees.
    let put_debt = admin
        .put(&format!("/api/accounts/{loan}/debt"))
        .json(&json!({
            "borrowedAmount": 12000,
            "interestRate": 0.06,
            "insuranceMonthly": 20,
            "fileFees": 120,
            "startDate": "2025-07-15",
            "endDate": "2026-07-15"
        }))
        .send()
        .await;
    assert_eq!(put_debt.status, StatusCode::OK, "{}", put_debt.text());

    // 100% owned (no ownership rows): owner sees the full loan, stranger gets 404.
    let full_resp = admin
        .get(&format!("/api/accounts/{loan}/loan-summary"))
        .send()
        .await;
    assert_eq!(full_resp.status, StatusCode::OK, "{}", full_resp.text());
    let full = full_resp.json();
    assert_eq!(full["summary"]["totalInstallments"], 12);
    assert_eq!(full["summary"]["paidInstallments"], 6);
    assert_eq!(full["summary"]["remainingInstallments"], 6);
    assert_eq!(full["summary"]["endDate"], "2026-07-15");
    assert_eq!(num(&full["summary"]["totalCapitalCost"]), "12000.00");
    assert_eq!(num(&full["summary"]["monthlyInsurance"]), "20.00");
    assert_eq!(num(&full["summary"]["fileFees"]), "120.00");
    assert_eq!(full["schedule"].as_array().unwrap().len(), 12);

    assert_eq!(
        carol_client
            .get(&format!("/api/accounts/{loan}/loan-summary"))
            .send()
            .await
            .status,
        StatusCode::NOT_FOUND
    );

    let money_summary_fields = [
        "monthlyPayment",
        "monthlyCapital",
        "monthlyInterest",
        "monthlyInsurance",
        "totalCost",
        "totalCapitalCost",
        "totalInterestCost",
        "totalInsuranceCost",
        "fileFees",
        "totalRepaid",
        "capitalRepaid",
        "interestRepaid",
        "insuranceRepaid",
        "remainingBalance",
    ];
    let money_row_fields = [
        "capital",
        "interest",
        "insurance",
        "totalPayment",
        "remainingBalance",
    ];
    let expected_scaled = |raw: &Value, pct: Decimal| -> String {
        let val: Decimal = num(raw).parse().unwrap();
        set_scale(access::weigh(val, pct), 2, HALF_UP).to_string()
    };

    // 50/50 co-ownership: both owner and co-owner read 200 with every money field halved,
    // while counts, dates and percentages stay unchanged.
    let split = admin
        .put(&format!("/api/accounts/{loan}/ownership"))
        .json(&json!({
            "shares": [
                { "memberId": me, "sharePercent": 50 },
                { "memberId": bob, "sharePercent": 50 }
            ]
        }))
        .send()
        .await;
    assert_eq!(split.status, StatusCode::OK, "{}", split.text());

    let owner_half_resp = admin
        .get(&format!("/api/accounts/{loan}/loan-summary"))
        .send()
        .await;
    assert_eq!(
        owner_half_resp.status,
        StatusCode::OK,
        "{}",
        owner_half_resp.text()
    );
    let owner_half = owner_half_resp.json();

    let bob_half_resp = bob_client
        .get(&format!("/api/accounts/{loan}/loan-summary"))
        .send()
        .await;
    assert_eq!(
        bob_half_resp.status,
        StatusCode::OK,
        "co-owner must be able to read the loan summary: {}",
        bob_half_resp.text()
    );
    let bob_half = bob_half_resp.json();
    assert_eq!(owner_half, bob_half);

    // Stranger still gets 404.
    assert_eq!(
        carol_client
            .get(&format!("/api/accounts/{loan}/loan-summary"))
            .send()
            .await
            .status,
        StatusCode::NOT_FOUND
    );

    let fifty = Decimal::from(50);
    for key in [
        "totalInstallments",
        "paidInstallments",
        "remainingInstallments",
        "endDate",
        "capitalRepaidPct",
    ] {
        assert_eq!(
            owner_half["summary"][key], full["summary"][key],
            "summary.{key} must not be scaled"
        );
    }
    for key in money_summary_fields {
        assert_eq!(
            num(&owner_half["summary"][key]),
            expected_scaled(&full["summary"][key], fifty),
            "summary.{key} must be halved"
        );
    }
    let full_rows = full["schedule"].as_array().unwrap();
    let half_rows = owner_half["schedule"].as_array().unwrap();
    assert_eq!(half_rows.len(), full_rows.len());
    for (i, (orig, weighed)) in full_rows.iter().zip(half_rows.iter()).enumerate() {
        assert_eq!(weighed["number"], orig["number"], "row {i} number");
        assert_eq!(weighed["date"], orig["date"], "row {i} date");
        for key in money_row_fields {
            assert_eq!(
                num(&weighed[key]),
                expected_scaled(&orig[key], fifty),
                "row {i}.{key} must be halved"
            );
        }
    }

    // Clearing the split restores 100% unchanged output for owner and revokes co-owner access.
    let clear = admin
        .put(&format!("/api/accounts/{loan}/ownership"))
        .json(&json!({ "shares": [] }))
        .send()
        .await;
    assert_eq!(clear.status, StatusCode::OK, "{}", clear.text());

    let restored = admin
        .get(&format!("/api/accounts/{loan}/loan-summary"))
        .send()
        .await
        .json();
    assert_eq!(restored, full);
    assert_eq!(
        bob_client
            .get(&format!("/api/accounts/{loan}/loan-summary"))
            .send()
            .await
            .status,
        StatusCode::NOT_FOUND
    );

    // Zero-share owner (co-owner holds 100%): owner still reads 200 with zeroed money fields
    // and intact counts/dates/percentages; co-owner sees the full loan unchanged.
    share(&app, loan, bob, "100").await;
    let owner_zero_resp = admin
        .get(&format!("/api/accounts/{loan}/loan-summary"))
        .send()
        .await;
    assert_eq!(
        owner_zero_resp.status,
        StatusCode::OK,
        "{}",
        owner_zero_resp.text()
    );
    let owner_zero = owner_zero_resp.json();
    for key in [
        "totalInstallments",
        "paidInstallments",
        "remainingInstallments",
        "endDate",
        "capitalRepaidPct",
    ] {
        assert_eq!(owner_zero["summary"][key], full["summary"][key]);
    }
    for key in money_summary_fields {
        assert_eq!(num(&owner_zero["summary"][key]), "0.00", "summary.{key}");
    }
    for (i, row) in owner_zero["schedule"]
        .as_array()
        .unwrap()
        .iter()
        .enumerate()
    {
        assert_eq!(row["number"], full_rows[i]["number"]);
        assert_eq!(row["date"], full_rows[i]["date"]);
        for key in money_row_fields {
            assert_eq!(num(&row[key]), "0.00", "row {i}.{key}");
        }
    }
    let bob_full = bob_client
        .get(&format!("/api/accounts/{loan}/loan-summary"))
        .send()
        .await
        .json();
    assert_eq!(bob_full, full);
}
