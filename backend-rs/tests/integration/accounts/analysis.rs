//! The wealth analysis endpoints: allocation targets, pyramid, diversification, projection,
//! expense estimate and the profile refresh.

use picsou::testing::TestApp;
use serde_json::{Value, json};
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::analysis_support::{
    Acct, account, d, dec, get_json, holding, num, price, share, signed_in, simple, sql,
};

fn tier<'a>(pyramid: &'a Value, name: &str) -> &'a Value {
    pyramid["tiers"]
        .as_array()
        .unwrap()
        .iter()
        .find(|t| t["tier"] == name)
        .unwrap_or_else(|| panic!("tier {name}"))
}

#[tokio::test]
async fn an_unconfigured_member_reads_the_defaults_without_a_row_being_written() {
    let app = TestApp::with_admin().await;
    let (_, client) = signed_in(&app, "alice").await;
    let targets = get_json(&client, "/api/analysis/allocation-targets").await;
    assert_eq!(
        targets.to_string(),
        r#"{"safetyNetMonths":6,"realEstatePct":30.00,"equityPct":50.00,"cryptoPct":10.00,"alternativePct":10.00}"#
    );
    let rows: i64 = picsou::db::query_scalar("SELECT count(*) FROM member_allocation_profile")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(rows, 0);
}

#[tokio::test]
async fn targets_not_summing_to_one_hundred_are_a_422_and_missing_ones_say_so_first() {
    let app = TestApp::with_admin().await;
    let (_, client) = signed_in(&app, "alice").await;
    let off = client
        .put("/api/analysis/allocation-targets")
        .json(&json!({"safetyNetMonths": 6, "realEstatePct": 40, "equityPct": 40, "cryptoPct": 10, "alternativePct": 5}))
        .send()
        .await;
    assert_eq!(off.status, 422);
    assert_eq!(
        off.json()["errors"],
        json!({"summingToOneHundred": "allocation targets must sum to 100"})
    );
    let partial = client
        .put("/api/analysis/allocation-targets")
        .json(&json!({"monthlyEssentialExpenses": -1, "safetyNetMonths": 30, "realEstatePct": 40, "equityPct": -1}))
        .send()
        .await;
    assert_eq!(
        partial.json()["errors"],
        json!({
            "monthlyEssentialExpenses": "must be greater than or equal to 0",
            "safetyNetMonths": "must be less than or equal to 24",
            "equityPct": "must be greater than or equal to 0",
            "cryptoPct": "must not be null",
            "alternativePct": "must not be null",
        })
    );
}

#[tokio::test]
async fn replacing_the_targets_creates_then_updates_one_row_and_answers_the_request_scale() {
    let app = TestApp::with_admin().await;
    let (_, client) = signed_in(&app, "alice").await;
    let body = json!({"monthlyEssentialExpenses": 1500.5, "safetyNetMonths": 3, "realEstatePct": 40,
                      "equityPct": 45.5, "cryptoPct": 14.5, "alternativePct": 0});
    let saved = client
        .put("/api/analysis/allocation-targets")
        .json(&body)
        .send()
        .await;
    assert_eq!(saved.status, 200, "{}", saved.text());
    assert_eq!(saved.json(), body);
    let cleared = json!({"safetyNetMonths": 6, "realEstatePct": 30, "equityPct": 50, "cryptoPct": 10, "alternativePct": 10});
    client
        .put("/api/analysis/allocation-targets")
        .json(&cleared)
        .send()
        .await;
    let read = get_json(&client, "/api/analysis/allocation-targets").await;
    assert!(
        read.get("monthlyEssentialExpenses").is_none(),
        "cleared expenses stay unknown"
    );
    assert_eq!(num(&read["equityPct"]), "50.00");
    let rows: i64 = picsou::db::query_scalar("SELECT count(*) FROM member_allocation_profile")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(rows, 1);
}

#[tokio::test]
async fn an_empty_portfolio_scores_nothing_and_divides_by_nothing() {
    let app = TestApp::with_admin().await;
    let (_, client) = signed_in(&app, "alice").await;
    let pyramid = get_json(&client, "/api/analysis/pyramid").await;
    assert_eq!(num(&pyramid["totalAssetsEur"]), "0.00");
    assert!(pyramid["score"].get("global").is_none());
    assert!(pyramid["score"].get("allocation").is_none());
    assert_eq!(pyramid["alerts"], json!([]));
    assert_eq!(pyramid["safetyNet"]["known"], false);
}

#[tokio::test]
async fn accounts_land_in_their_tiers_and_cash_sits_outside_the_allocation() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let checking = simple(&app, alice, "CHECKING", "2000").await;
    simple(&app, alice, "LIVRET_A", "5000").await;
    simple(&app, alice, "PEA", "6000").await;
    simple(&app, alice, "CRYPTO", "2000").await;
    simple(&app, alice, "OTHER", "2000").await;
    simple(&app, alice, "CREDIT_CARD", "-700").await;
    simple(&app, alice, "PEER_LOAN", "900").await;
    account(
        &app,
        Acct {
            parent: Some(checking),
            ..Acct::new(alice, "SAVINGS", "400")
        },
    )
    .await;
    account(
        &app,
        Acct {
            hidden: true,
            ..Acct::new(alice, "PEA", "99999")
        },
    )
    .await;

    let pyramid = get_json(&client, "/api/analysis/pyramid").await;
    // Liabilities, the peer loan, the pocket and the hidden account are all out.
    assert_eq!(num(&pyramid["totalAssetsEur"]), "17000.00");
    assert_eq!(num(&pyramid["safetyNet"]["valueEur"]), "5000.00");
    assert_eq!(num(&pyramid["safetyNet"]["dailyCashEur"]), "2000.00");
    assert_eq!(num(&pyramid["allocatableEur"]), "10000.00");
    assert_eq!(num(&tier(&pyramid, "EQUITY")["actualPercent"]), "60.00");
    assert_eq!(num(&tier(&pyramid, "EQUITY")["targetEur"]), "5000.00");
    assert_eq!(num(&tier(&pyramid, "EQUITY")["gapPercent"]), "10.00");
    assert!(
        pyramid["tiers"]
            .as_array()
            .unwrap()
            .iter()
            .all(|t| t["tier"] != "SAFETY_NET")
    );
    // Without stated expenses the cushion is unrated, so only the allocation term scores.
    assert!(pyramid["safetyNet"].get("score").is_none());
    assert_eq!(pyramid["score"]["allocation"], 70);
    assert_eq!(pyramid["score"]["global"], 70);
    let alerts: Vec<&str> = pyramid["alerts"]
        .as_array()
        .unwrap()
        .iter()
        .map(|a| a["code"].as_str().unwrap())
        .collect();
    assert_eq!(alerts, ["EMPTY_TIER"]);
}

#[tokio::test]
async fn property_enters_net_of_its_mortgage_and_reports_the_loan_to_value() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let flat = simple(&app, alice, "REAL_ESTATE", "200000").await;
    let loan = simple(&app, alice, "LOAN", "150000").await;
    sql(
        &app,
        &format!(
            "INSERT INTO debt (id, member_id, account_id, linked_account_id, borrowed_amount) \
             VALUES (1, {alice}, {loan}, {flat}, 180000)"
        ),
    )
    .await;
    let pyramid = get_json(&client, "/api/analysis/pyramid").await;
    assert_eq!(num(&pyramid["totalAssetsEur"]), "50000.00");
    assert_eq!(
        dec(&tier(&pyramid, "REAL_ESTATE")["valueEur"]),
        d("50000.00")
    );
    assert_eq!(num(&pyramid["score"]["loanToValue"]), "75.00");
    assert_eq!(num(&pyramid["score"]["leverageBonus"]), "5.00");
    // One flat is the whole patrimoine: named whatever the targets say.
    assert_eq!(pyramid["alerts"][0]["code"], "SINGLE_ASSET_CONCENTRATION");
    assert_eq!(num(&pyramid["alerts"][0]["percent"]), "400.00");

    sql(
        &app,
        &format!("UPDATE account SET hidden = true WHERE id IN ({flat}, {loan})"),
    )
    .await;
    let hidden = get_json(&client, "/api/analysis/pyramid").await;
    assert_eq!(num(&hidden["totalAssetsEur"]), "0.00");
    assert!(hidden["score"].get("loanToValue").is_none());
}

#[tokio::test]
async fn a_shared_account_is_weighed_exactly_once() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let (bob, bob_client) = signed_in(&app, "bob").await;
    let joint = simple(&app, alice, "LIVRET_A", "10000").await;
    share(&app, joint, alice, "50").await;
    share(&app, joint, bob, "50").await;
    for c in [&client, &bob_client] {
        let pyramid = get_json(c, "/api/analysis/pyramid").await;
        assert_eq!(num(&pyramid["safetyNet"]["valueEur"]), "5000.00");
    }
}

#[tokio::test]
async fn lines_are_split_by_override_then_provider_tier_then_coin_registry() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let cto = account(
        &app,
        Acct {
            cash: Some("100"),
            ..Acct::new(alice, "COMPTE_TITRES", "0")
        },
    )
    .await;
    holding(&app, cto, "PSGOLD", "1", None).await;
    holding(&app, cto, "PSSCPI", "1", None).await;
    holding(&app, cto, "ETH", "1", None).await;
    holding(&app, cto, "PSSHARE", "1", None).await;
    for (ticker, eur) in [
        ("PSGOLD", "1000"),
        ("PSSCPI", "2000"),
        ("ETH", "3000"),
        ("PSSHARE", "4000"),
    ] {
        price(&app, ticker, eur).await;
    }
    sql(
        &app,
        "UPDATE account_holding SET provider_wealth_tier = 'REAL_ESTATE' WHERE ticker = 'PSSCPI'",
    )
    .await;
    client
        .put(&format!(
            "/api/accounts/{cto}/holdings/psgold/classification"
        ))
        .json(&json!({"wealthTier": "ALTERNATIVE"}))
        .send()
        .await;

    let pyramid = get_json(&client, "/api/analysis/pyramid").await;
    assert_eq!(
        dec(&tier(&pyramid, "ALTERNATIVE")["valueEur"]),
        d("1000.00000000")
    );
    assert_eq!(
        dec(&tier(&pyramid, "REAL_ESTATE")["valueEur"]),
        d("2000.00000000")
    );
    assert_eq!(
        dec(&tier(&pyramid, "CRYPTO")["valueEur"]),
        d("3000.00000000")
    );
    // The envelope's cash stays with the account's own tier.
    assert_eq!(
        dec(&tier(&pyramid, "EQUITY")["valueEur"]),
        d("4100.00000000")
    );
    assert_eq!(num(&pyramid["totalAssetsEur"]), "10100.00");
    assert_eq!(num(&pyramid["score"]["cryptoTopTenShare"]), "100.00");
}

#[tokio::test]
async fn a_stated_cushion_is_scored_and_an_idle_surplus_is_called_out() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    simple(&app, alice, "LIVRET_A", "30000").await;
    simple(&app, alice, "PEA", "10000").await;
    client
        .put("/api/analysis/allocation-targets")
        .json(
            &json!({"monthlyEssentialExpenses": 1000, "safetyNetMonths": 6, "realEstatePct": 0,
                      "equityPct": 100, "cryptoPct": 0, "alternativePct": 0}),
        )
        .send()
        .await;
    let pyramid = get_json(&client, "/api/analysis/pyramid").await;
    let cushion = &pyramid["safetyNet"];
    assert_eq!(num(&cushion["targetEur"]), "6000.00");
    assert_eq!(num(&cushion["coverage"]), "5.0000");
    assert_eq!(num(&cushion["excessEur"]), "24000.00");
    // 60 % of the patrimoine idle beyond need saturates the penalty: 100 - 40.
    assert_eq!(cushion["score"], 60);
    assert_eq!(pyramid["score"]["allocation"], 100);
    assert_eq!(pyramid["score"]["global"], 84);
    let overfunded = pyramid["alerts"]
        .as_array()
        .unwrap()
        .iter()
        .find(|a| a["code"] == "CUSHION_OVERFUNDED")
        .unwrap();
    assert_eq!(num(&overfunded["percent"]), "60.00");
}

#[tokio::test]
async fn a_co_owner_of_an_account_with_holdings_sees_their_share_in_pyramid_and_diversification() {
    let app = TestApp::with_admin().await;
    let (alice, _) = signed_in(&app, "alice").await;
    let (bob, bob_client) = signed_in(&app, "bob").await;
    let (_, carol_client) = signed_in(&app, "carol").await;
    let pea = simple(&app, alice, "PEA", "0").await;
    holding(&app, pea, "PSX", "1", None).await;
    price(&app, "PSX", "1000").await;
    share(&app, pea, alice, "50").await;
    share(&app, pea, bob, "50").await;

    let pyramid_resp = bob_client.get("/api/analysis/pyramid").send().await;
    assert_eq!(pyramid_resp.status, 200, "{}", pyramid_resp.text());
    let pyramid = pyramid_resp.json();
    assert_eq!(num(&pyramid["totalAssetsEur"]), "500.00");
    assert_eq!(dec(&tier(&pyramid, "EQUITY")["valueEur"]), d("500.00"));

    let div_resp = bob_client.get("/api/analysis/diversification").send().await;
    assert_eq!(div_resp.status, 200, "{}", div_resp.text());
    let div = div_resp.json();
    assert_eq!(num(&div["totalValueEur"]), "500.00");
    assert_eq!(div["unclassified"][0]["ticker"], "PSX");
    assert_eq!(num(&div["unclassified"][0]["valueEur"]), "500.00");

    // A member without a share still sees none of the shared account.
    let carol_pyramid = get_json(&carol_client, "/api/analysis/pyramid").await;
    assert_eq!(num(&carol_pyramid["totalAssetsEur"]), "0.00");
    let carol_div = get_json(&carol_client, "/api/analysis/diversification").await;
    assert_eq!(num(&carol_div["totalValueEur"]), "0.00");
}

#[tokio::test]
async fn diversification_looks_through_funds_and_lists_what_it_cannot_place() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = simple(&app, alice, "PEA", "0").await;
    let hidden = account(
        &app,
        Acct {
            hidden: true,
            ..Acct::new(alice, "COMPTE_TITRES", "0")
        },
    )
    .await;
    let wallet = simple(&app, alice, "CRYPTO", "0").await;
    holding(&app, pea, "PSFUND", "10", None).await;
    holding(&app, pea, "PSSHARE", "1", None).await;
    holding(&app, pea, "PSNEW", "1", None).await;
    holding(&app, hidden, "PSSHARE", "1", None).await;
    holding(&app, wallet, "ETH", "1", None).await;
    for (ticker, eur) in [
        ("PSFUND", "100"),
        ("PSSHARE", "500"),
        ("PSNEW", "250"),
        ("ETH", "3000"),
    ] {
        price(&app, ticker, eur).await;
    }
    sql(
        &app,
        "INSERT INTO security_profile (id, ticker, asset_type, sector_key, country_key, status) VALUES \
         (1, 'PSFUND', 'ETF', NULL, NULL, 'OK'), (2, 'PSSHARE', 'STOCK', 'technology', NULL, 'OK');
         INSERT INTO security_composition_slice (profile_id, kind, label, percent) VALUES \
         (1, 'SECTOR', 'technology', 50.000), (1, 'SECTOR', 'energy', 20.000), (1, 'COUNTRY', 'US', 100.000)",
    )
    .await;

    let body = get_json(&client, "/api/analysis/diversification").await;
    // The hidden account counts here (Java does not filter it); the crypto wallet does not.
    assert_eq!(num(&body["totalValueEur"]), "2250.00");
    let sectors = &body["sectors"];
    // 700 of the fund's 1000 are disclosed; the share adds its whole 1000.
    assert_eq!(num(&sectors["classifiedValueEur"]), "1700.00");
    assert_eq!(sectors["basis"], "MIXED");
    assert_eq!(sectors["slices"][0]["label"], "technology");
    assert_eq!(num(&sectors["slices"][0]["valueEur"]), "1500.00");
    assert_eq!(sectors["slices"][0]["contributorCount"], 2);
    assert_eq!(body["countries"]["basis"], "EXPOSURE");
    let unclassified = body["unclassified"].as_array().unwrap();
    assert_eq!(unclassified.len(), 2);
    assert_eq!(unclassified[0]["ticker"], "PSSHARE");
    assert_eq!(unclassified[0]["sectorMissing"], false);
    assert_eq!(unclassified[0]["countryMissing"], true);
    assert_eq!(unclassified[0]["profileLooked"], true);
    assert_eq!(unclassified[1]["ticker"], "PSNEW");
    assert_eq!(unclassified[1]["profileLooked"], false);
    assert_eq!(unclassified[1]["accountId"], pea);
}

#[tokio::test]
async fn a_hand_classified_line_leaves_the_list_and_counts_as_classified() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = simple(&app, alice, "PEA", "0").await;
    holding(&app, pea, "PSFCPE", "1", None).await;
    price(&app, "PSFCPE", "800").await;
    client
        .put(&format!(
            "/api/accounts/{pea}/holdings/PSFCPE/classification"
        ))
        .json(&json!({"sectorKey": "private_equity", "countryKey": "XU"}))
        .send()
        .await;
    let body = get_json(&client, "/api/analysis/diversification").await;
    assert_eq!(body["unclassified"], json!([]));
    assert_eq!(num(&body["coveragePercent"]), "100.00");
    assert_eq!(body["securities"][0]["ticker"], "PSFCPE");
    // An override to another tier takes the line out of the equity sleeve altogether.
    client
        .put(&format!(
            "/api/accounts/{pea}/holdings/PSFCPE/classification"
        ))
        .json(&json!({"wealthTier": "ALTERNATIVE"}))
        .send()
        .await;
    let gone = get_json(&client, "/api/analysis/diversification").await;
    assert_eq!(num(&gone["totalValueEur"]), "0.00");
    assert_eq!(gone["sectors"]["slices"], json!([]));
}

#[tokio::test]
async fn the_projection_compounds_the_investable_base_only() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    simple(&app, alice, "CHECKING", "5000").await;
    simple(&app, alice, "REAL_ESTATE", "300000").await;
    let livret = simple(&app, alice, "LIVRET_A", "1000").await;
    simple(&app, alice, "PEA", "1000").await;
    sql(
        &app,
        &format!(
            "INSERT INTO goal (id, member_id, name, type, monthly_amount, expected_return, target_amount, deadline) VALUES \
             (1, {alice}, 'Livret', 'RECURRING_INVESTMENT', 100, 1.700, NULL, NULL), \
             (2, {alice}, 'Car', 'SAVINGS_TARGET', 500, NULL, 9000, '2027-06-30'); \
             INSERT INTO goal_account (goal_id, account_id) VALUES (1, {livret}), (2, {livret})"
        ),
    )
    .await;
    let projection = get_json(&client, "/api/analysis/projection?years=0").await;
    // Clamped to one year; the house and the current account are not compounded.
    assert_eq!(projection["years"], 1);
    assert_eq!(num(&projection["baseValueEur"]), "2000.00");
    assert_eq!(num(&projection["monthlyInflowEur"]), "100.00");
    let scenarios = projection["scenarios"].as_array().unwrap();
    assert_eq!(scenarios.len(), 4);
    assert_eq!(scenarios[0]["points"][0]["date"], "2026-01-31");
    assert_eq!(scenarios[0]["points"][1]["date"], "2027-01-31");
    assert_eq!(num(&scenarios[0]["riskyDelta"]), "-2.5");
    assert_eq!(num(&scenarios[2]["riskyDelta"]), "0");
    // The passbook plan keeps its own 1.7 % on every curve: the spread lands on risky tiers.
    assert_eq!(num(&scenarios[0]["points"][1]["contributedEur"]), "3200.00");
    let ends: Vec<String> = scenarios
        .iter()
        .map(|s| num(&s["points"][1]["valueEur"]))
        .collect();
    assert!(
        ends.windows(2)
            .all(|w| w[0].parse::<f64>().unwrap() < w[1].parse::<f64>().unwrap()),
        "{ends:?}"
    );
    let mix = &projection["allocation"][0]["tiers"];
    assert_eq!(mix.as_array().unwrap().len(), 5);
    assert!(
        mix[0].get("targetPercent").is_none(),
        "the cushion has no percentage target"
    );
    assert_eq!(
        client
            .get("/api/analysis/projection?years=abc")
            .send()
            .await
            .status,
        400
    );
}

#[tokio::test]
async fn the_estimate_averages_months_that_carried_spending_and_drops_transfers() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let checking = simple(&app, alice, "CHECKING", "0").await;
    let livret = simple(&app, alice, "LIVRET_A", "0").await;
    sql(
        &app,
        &format!(
            "INSERT INTO transaction (account_id, date, description, amount) VALUES \
             ({checking}, '2025-12-05', 'Loyer', -900), ({checking}, '2025-12-10', 'Courses', -100.50), \
             ({checking}, '2025-12-15', 'Vers mon livret', -500), ({livret}, '2025-12-16', 'Virement', 500), \
             ({checking}, '2025-11-05', 'Loyer', -900), ({checking}, '2025-10-05', 'VIR INTERNE', -300), \
             ({checking}, '2026-01-05', 'Ce mois-ci', -5000), ({checking}, '2025-06-30', 'Trop ancien', -5000), \
             ({livret}, '2025-11-20', 'Livret', -77)"
        ),
    )
    .await;
    let estimate = get_json(&client, "/api/analysis/essential-expenses/estimate").await;
    // December and November fed it; October's only debit was an internal transfer.
    assert_eq!(
        estimate,
        json!({"estimate": 950.25, "monthsObserved": 2, "excludedTransferCount": 2})
    );
}

#[tokio::test]
async fn without_history_the_estimate_says_so_rather_than_zero() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    assert_eq!(
        get_json(&client, "/api/analysis/essential-expenses/estimate").await,
        json!({"monthsObserved": 0, "excludedTransferCount": 0})
    );
    simple(&app, alice, "CHECKING", "0").await;
    assert_eq!(
        get_json(&client, "/api/analysis/essential-expenses/estimate").await,
        json!({"monthsObserved": 0, "excludedTransferCount": 0})
    );
}

#[tokio::test]
async fn a_joint_current_account_is_counted_at_the_members_share() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let (bob, _) = signed_in(&app, "bob").await;
    let joint = simple(&app, alice, "CHECKING", "0").await;
    share(&app, joint, alice, "50").await;
    share(&app, joint, bob, "50").await;
    sql(&app, &format!("INSERT INTO transaction (account_id, date, description, amount) VALUES ({joint}, '2025-12-05', 'Loyer', -1000)")).await;
    let estimate = get_json(&client, "/api/analysis/essential-expenses/estimate").await;
    assert_eq!(num(&estimate["estimate"]), "500.00");
}

#[tokio::test]
async fn a_profile_refresh_answers_202_with_the_tickers_it_will_consider() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let refreshed = client
        .post("/api/analysis/security-profiles/refresh")
        .send()
        .await;
    assert_eq!(refreshed.status, 202);
    assert_eq!(
        refreshed.json(),
        json!({"queuedTickers": 0, "alreadyRunning": false})
    );
    let pea = simple(&app, alice, "PEA", "0").await;
    holding(&app, pea, "PSA", "1", None).await;
    holding(&app, pea, "PSB", "1", None).await;
    let refreshed = client
        .post("/api/analysis/security-profiles/refresh")
        .send()
        .await;
    assert_eq!(refreshed.status, 202);
    assert_eq!(refreshed.json()["queuedTickers"], 2);
}

#[tokio::test]
async fn targets_summing_past_decimal_max_return_validation_error_without_panicking() {
    let app = TestApp::with_admin().await;
    let (_, client) = signed_in(&app, "alice").await;
    let body: Value = serde_json::from_str(
        r#"{"safetyNetMonths": 6, "realEstatePct": 79228162514264337593543950335, "equityPct": 1, "cryptoPct": 0, "alternativePct": 0}"#,
    )
    .unwrap();
    let resp = client
        .put("/api/analysis/allocation-targets")
        .json(&body)
        .send()
        .await;
    assert_eq!(resp.status, 422, "{}", resp.text());
    assert_eq!(
        resp.json()["errors"],
        json!({"summingToOneHundred": "allocation targets must sum to 100"})
    );
}

const TRACKED_ISIN: &str = "IE00B4L5Y983";

/// Declares the account's tracked fund the way a saved form would, without the resolution.
async fn track(app: &TestApp, account: i64, isin: &str) {
    sql(
        app,
        &format!("UPDATE account SET tracked_isin = '{isin}' WHERE id = {account}"),
    )
    .await;
}

async fn classify(app: &TestApp, member: i64, ticker: &str, tier: &str) {
    sql(
        app,
        &format!(
            "INSERT INTO holding_classification (member_id, ticker, wealth_tier, created_at, updated_at) \
             VALUES ({member}, '{ticker}', '{tier}', now(), now())"
        ),
    )
    .await;
}

#[tokio::test]
async fn a_tracked_savings_account_counts_as_equity() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let savings = simple(&app, alice, "SAVINGS", "1000").await;
    track(&app, savings, TRACKED_ISIN).await;

    let pyramid = get_json(&client, "/api/analysis/pyramid").await;
    assert_eq!(dec(&tier(&pyramid, "EQUITY")["valueEur"]), d("1000"));
    assert_eq!(
        tier(&pyramid, "EQUITY")["accounts"][0]["accountId"],
        json!(savings)
    );
    assert_eq!(dec(&pyramid["safetyNet"]["valueEur"]), d("0"));
    assert_eq!(dec(&pyramid["safetyNet"]["dailyCashEur"]), d("0"));
}

#[tokio::test]
async fn an_override_on_the_tracked_ticker_wins() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = simple(&app, alice, "PEA", "1000").await;
    track(&app, pea, TRACKED_ISIN).await;
    sql(
        &app,
        &format!(
            "INSERT INTO security_profile (ticker, asset_type, isin, status, created_at, updated_at) \
             VALUES ('IWDA.AS', 'ETF', '{TRACKED_ISIN}', 'OK', now(), now())"
        ),
    )
    .await;
    classify(&app, alice, "IWDA.AS", "SAFETY_NET").await;

    let pyramid = get_json(&client, "/api/analysis/pyramid").await;
    assert_eq!(dec(&pyramid["safetyNet"]["valueEur"]), d("1000"));
    assert_eq!(dec(&tier(&pyramid, "EQUITY")["valueEur"]), d("0"));
}

#[tokio::test]
async fn a_tracked_account_with_holdings_uses_its_holdings() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let savings = account(
        &app,
        Acct {
            cash: Some("0"),
            ..Acct::new(alice, "SAVINGS", "0")
        },
    )
    .await;
    track(&app, savings, TRACKED_ISIN).await;
    holding(&app, savings, "PSX", "1", None).await;
    price(&app, "PSX", "1000").await;

    let pyramid = get_json(&client, "/api/analysis/pyramid").await;
    // An unclassified line stays with the account's own tier, as without a tracked fund.
    assert_eq!(dec(&pyramid["safetyNet"]["valueEur"]), d("1000"));
    assert_eq!(dec(&tier(&pyramid, "EQUITY")["valueEur"]), d("0"));
}

#[tokio::test]
async fn a_shared_tracked_account_counts_the_members_share() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let (bob, _) = signed_in(&app, "bob").await;
    let savings = simple(&app, alice, "SAVINGS", "1000").await;
    track(&app, savings, TRACKED_ISIN).await;
    share(&app, savings, alice, "50").await;
    share(&app, savings, bob, "50").await;

    let pyramid = get_json(&client, "/api/analysis/pyramid").await;
    assert_eq!(dec(&tier(&pyramid, "EQUITY")["valueEur"]), d("500"));
    assert_eq!(dec(&pyramid["safetyNet"]["valueEur"]), d("0"));
}

#[tokio::test]
async fn a_foreign_currency_tracked_account_counts_in_eur() {
    let yahoo = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/USDEUR=X"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "chart": {"result": [{
                "meta": {"currency": "EUR", "regularMarketPrice": 0.9},
                "timestamp": [1768471200],
                "indicators": {"quote": [{"close": [0.9]}]}
            }], "error": null}
        })))
        .mount(&yahoo)
        .await;
    let app = TestApp::builder()
        .with_admin()
        .env("YAHOO_FINANCE_BASE_URL", yahoo.uri())
        .spawn()
        .await;
    let (alice, client) = signed_in(&app, "alice").await;
    let dollars = simple(&app, alice, "SAVINGS", "1000").await;
    sql(
        &app,
        &format!("UPDATE account SET currency = 'USD' WHERE id = {dollars}"),
    )
    .await;
    track(&app, dollars, TRACKED_ISIN).await;

    let pyramid = get_json(&client, "/api/analysis/pyramid").await;
    assert_eq!(dec(&tier(&pyramid, "EQUITY")["valueEur"]), d("900"));
    assert_eq!(dec(&pyramid["safetyNet"]["valueEur"]), d("0"));
}

#[tokio::test]
async fn a_hidden_tracked_account_stays_out() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let hidden = account(
        &app,
        Acct {
            hidden: true,
            ..Acct::new(alice, "SAVINGS", "1000")
        },
    )
    .await;
    track(&app, hidden, TRACKED_ISIN).await;

    let pyramid = get_json(&client, "/api/analysis/pyramid").await;
    assert_eq!(num(&pyramid["totalAssetsEur"]), "0.00");
    assert_eq!(dec(&tier(&pyramid, "EQUITY")["valueEur"]), d("0"));
}

#[tokio::test]
async fn a_tracked_current_account_overridden_to_safety_net_is_daily_cash() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let cushion = simple(&app, alice, "CHECKING", "1000").await;
    track(&app, cushion, TRACKED_ISIN).await;
    classify(&app, alice, TRACKED_ISIN, "SAFETY_NET").await;
    // The same kind of account tracking a fund left in equity is invested, not daily cash.
    let invested = simple(&app, alice, "CHECKING", "300").await;
    track(&app, invested, "IE00B5BMR087").await;

    let pyramid = get_json(&client, "/api/analysis/pyramid").await;
    assert_eq!(dec(&pyramid["safetyNet"]["dailyCashEur"]), d("1000"));
    assert_eq!(dec(&pyramid["safetyNet"]["valueEur"]), d("0"));
    assert_eq!(dec(&tier(&pyramid, "EQUITY")["valueEur"]), d("300"));
}
