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

#[tokio::test]
async fn a_recurring_plan_into_a_tracked_account_grows_as_equity() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let savings = simple(&app, alice, "SAVINGS", "0").await;
    track(&app, savings, TRACKED_ISIN).await;
    sql(
        &app,
        &format!(
            "INSERT INTO goal (id, member_id, name, type, monthly_amount, expected_return, target_amount, deadline) VALUES \
             (1, {alice}, 'Fund', 'RECURRING_INVESTMENT', 100, NULL, NULL, NULL); \
             INSERT INTO goal_account (goal_id, account_id) VALUES (1, {savings})"
        ),
    )
    .await;

    let projection = get_json(&client, "/api/analysis/projection?years=1").await;
    let last = projection["allocation"].as_array().unwrap().last().unwrap();
    let value = |name: &str| {
        let t = last["tiers"]
            .as_array()
            .unwrap()
            .iter()
            .find(|t| t["tier"] == name)
            .unwrap_or_else(|| panic!("tier {name}"));
        dec(&t["valueEur"])
    };
    assert!(
        value("EQUITY") > d("1200"),
        "the plan feeds the fund's tier"
    );
    assert_eq!(value("SAFETY_NET"), d("0"));
}

#[tokio::test]
async fn a_recurring_plan_into_a_tracked_account_overridden_to_safety_net_feeds_the_safety_net() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let savings = simple(&app, alice, "SAVINGS", "0").await;
    track(&app, savings, TRACKED_ISIN).await;
    // Unlinked, the fund's key is its ISIN: the member's override on it sets the tier.
    classify(&app, alice, TRACKED_ISIN, "SAFETY_NET").await;
    sql(
        &app,
        &format!(
            "INSERT INTO goal (id, member_id, name, type, monthly_amount, expected_return, target_amount, deadline) VALUES \
             (1, {alice}, 'Fund', 'RECURRING_INVESTMENT', 100, NULL, NULL, NULL); \
             INSERT INTO goal_account (goal_id, account_id) VALUES (1, {savings})"
        ),
    )
    .await;

    let projection = get_json(&client, "/api/analysis/projection?years=1").await;
    let last = projection["allocation"].as_array().unwrap().last().unwrap();
    let value = |name: &str| {
        let t = last["tiers"]
            .as_array()
            .unwrap()
            .iter()
            .find(|t| t["tier"] == name)
            .unwrap_or_else(|| panic!("tier {name}"));
        dec(&t["valueEur"])
    };
    assert!(
        value("SAFETY_NET") >= d("1200"),
        "the plan feeds the overridden tier"
    );
    assert_eq!(value("EQUITY"), d("0"));
}

/// Links the tracked ISIN to `IWDA.AS`, a looked-up profile spread 70/30 over two countries.
async fn link_world_fund(app: &TestApp) {
    sql(
        app,
        &format!(
            "INSERT INTO security_profile (id, ticker, asset_type, isin, status, created_at, updated_at) \
             VALUES (1, 'IWDA.AS', 'ETF', '{TRACKED_ISIN}', 'OK', now(), now()); \
             INSERT INTO security_composition_slice (profile_id, kind, label, percent) VALUES \
             (1, 'COUNTRY', 'US', 70.000), (1, 'COUNTRY', 'JP', 30.000)"
        ),
    )
    .await;
}

fn slice<'a>(breakdown: &'a Value, label: &str) -> &'a Value {
    breakdown["slices"]
        .as_array()
        .unwrap()
        .iter()
        .find(|s| s["label"] == label)
        .unwrap_or_else(|| panic!("slice {label}"))
}

#[tokio::test]
async fn a_tracked_account_is_one_line_spread_by_its_profile() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let savings = simple(&app, alice, "SAVINGS", "1000").await;
    track(&app, savings, TRACKED_ISIN).await;
    link_world_fund(&app).await;

    let body = get_json(&client, "/api/analysis/diversification").await;
    assert_eq!(num(&body["totalValueEur"]), "1000.00");
    assert_eq!(num(&slice(&body["countries"], "US")["valueEur"]), "700.00");
    assert_eq!(num(&slice(&body["countries"], "JP")["valueEur"]), "300.00");
    assert_eq!(body["securities"][0]["ticker"], "IWDA.AS");
    assert_eq!(body["securities"][0]["accountId"], json!(savings));
}

#[tokio::test]
async fn a_tracked_line_merges_with_a_held_line_of_the_same_ticker() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let broker = simple(&app, alice, "COMPTE_TITRES", "0").await;
    holding(&app, broker, "IWDA.AS", "1", None).await;
    sql(
        &app,
        &format!(
            "UPDATE account_holding SET name = 'SYNTHETIC WORLD FUND' WHERE account_id = {broker}"
        ),
    )
    .await;
    price(&app, "IWDA.AS", "500").await;
    let savings = simple(&app, alice, "SAVINGS", "1000").await;
    track(&app, savings, TRACKED_ISIN).await;
    link_world_fund(&app).await;

    let body = get_json(&client, "/api/analysis/diversification").await;
    assert_eq!(num(&body["totalValueEur"]), "1500.00");
    let securities = body["securities"].as_array().unwrap();
    assert_eq!(securities.len(), 1);
    assert_eq!(securities[0]["ticker"], "IWDA.AS");
    assert_eq!(num(&securities[0]["valueEur"]), "1500.00");
    // The held line was seen first: it names the security.
    assert_eq!(securities[0]["accountId"], json!(broker));
    assert_eq!(securities[0]["name"], "SYNTHETIC WORLD FUND");
}

#[tokio::test]
async fn an_overdrawn_tracked_account_adds_no_line() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let savings = simple(&app, alice, "SAVINGS", "1000").await;
    track(&app, savings, TRACKED_ISIN).await;
    link_world_fund(&app).await;
    // Overdrawn current accounts tracking the same fund, and another one.
    let overdrawn = simple(&app, alice, "CHECKING", "-200").await;
    track(&app, overdrawn, TRACKED_ISIN).await;
    let other = simple(&app, alice, "CHECKING", "-50").await;
    track(&app, other, "IE00B5BMR087").await;

    let body = get_json(&client, "/api/analysis/diversification").await;
    assert_eq!(num(&body["totalValueEur"]), "1000.00");
    assert_eq!(num(&body["securities"][0]["valueEur"]), "1000.00");
    // Only the fund itself is listed (its profile has no sectors), not the other overdraft.
    let unclassified = body["unclassified"].as_array().unwrap();
    assert_eq!(unclassified.len(), 1, "{unclassified:?}");
    assert_eq!(unclassified[0]["ticker"], "IWDA.AS");
    assert_eq!(num(&unclassified[0]["valueEur"]), "1000.00");
}

#[tokio::test]
async fn an_unresolved_tracked_isin_is_listed_as_unclassified() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let savings = simple(&app, alice, "SAVINGS", "1000").await;
    track(&app, savings, TRACKED_ISIN).await;

    let body = get_json(&client, "/api/analysis/diversification").await;
    assert_eq!(num(&body["totalValueEur"]), "1000.00");
    assert_eq!(body["unclassified"][0]["ticker"], TRACKED_ISIN);
    assert_eq!(num(&body["unclassified"][0]["valueEur"]), "1000.00");
    assert_eq!(body["unclassified"][0]["accountId"], json!(savings));
    assert_eq!(body["unclassified"][0]["profileLooked"], false);
}

#[tokio::test]
async fn a_tracked_fund_overridden_out_of_equity_leaves_diversification() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let savings = simple(&app, alice, "SAVINGS", "1000").await;
    track(&app, savings, TRACKED_ISIN).await;
    link_world_fund(&app).await;
    classify(&app, alice, "IWDA.AS", "SAFETY_NET").await;

    let gone = get_json(&client, "/api/analysis/diversification").await;
    assert_eq!(num(&gone["totalValueEur"]), "0.00");
    assert_eq!(gone["unclassified"], json!([]));

    // Another tracked fund left in equity still counts.
    let other = simple(&app, alice, "SAVINGS", "300").await;
    track(&app, other, "IE00B5BMR087").await;

    let body = get_json(&client, "/api/analysis/diversification").await;
    assert_eq!(num(&body["totalValueEur"]), "300.00");
    let unclassified = body["unclassified"].as_array().unwrap();
    assert_eq!(unclassified.len(), 1);
    assert_eq!(unclassified[0]["ticker"], "IE00B5BMR087");
    assert_eq!(body["countries"]["slices"], json!([]));
}

async fn set_provider(app: &TestApp, account: i64, provider: &str) {
    sql(
        app,
        &format!("UPDATE account SET provider = '{provider}' WHERE id = {account}"),
    )
    .await;
}

#[tokio::test]
async fn detected_inflow_counts_monthly_external_wires_to_broker_cash_and_pea_buys_once() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let broker_cash = simple(&app, alice, "CHECKING", "50").await;
    let pea = simple(&app, alice, "PEA", "2000").await;
    set_provider(&app, broker_cash, "BrokerAlpha").await;
    set_provider(&app, pea, "BrokerAlpha").await;

    // Test clock today is 2026-01-15: 6 rolling months are Aug 2025..=Jan 2026.
    // Oct & Nov model Trade Republic TRADING mirrored pairs; Dec models cash transfer + BUY;
    // Jan adds a 900 EUR spike in the current month, which the median across 4 active months
    // ([200, 200, 200, 900]) still summarises to 200 EUR.
    sql(
        &app,
        &format!(
            "INSERT INTO \"transaction\" (account_id, date, description, amount, type, category, tx_type, ticker, external_id) VALUES \
             ({broker_cash}, '2025-10-02', 'SEPA wire', 200, 'DEPOSIT', 'CASH', 'DEPOSIT', NULL, 'wire-10'), \
             ({broker_cash}, '2025-10-04', 'BUY World ETF', -200, 'BUY', 'TRADING', 'WITHDRAWAL', NULL, 'tr-10_cash'), \
             ({pea},         '2025-10-04', 'BUY World ETF', 200, 'BUY', 'TRADING', 'DEPOSIT', NULL, 'tr-10_inv'), \
             ({broker_cash}, '2025-11-02', 'SEPA wire', 200, 'DEPOSIT', 'CASH', 'DEPOSIT', NULL, 'wire-11'), \
             ({broker_cash}, '2025-11-05', 'BUY World ETF', -200, 'BUY', 'TRADING', 'WITHDRAWAL', NULL, 'tr-11_cash'), \
             ({pea},         '2025-11-05', 'BUY World ETF', 200, 'BUY', 'TRADING', 'DEPOSIT', NULL, 'tr-11_inv'), \
             ({broker_cash}, '2025-12-02', 'SEPA wire', 200, 'DEPOSIT', 'CASH', 'DEPOSIT', NULL, 'wire-12'), \
             ({broker_cash}, '2025-12-03', 'Transfer to PEA', -200, NULL, NULL, 'WITHDRAWAL', NULL, 'xfer-12-out'), \
             ({pea},         '2025-12-04', 'Transfer from cash', 200, NULL, NULL, 'DEPOSIT', NULL, 'xfer-12-in'), \
             ({pea},         '2025-12-05', 'BUY World ETF', -200, 'BUY', NULL, 'BUY', 'ETFW', 'buy-12'), \
             ({broker_cash}, '2026-01-03', 'Current month wire', 900, 'DEPOSIT', 'CASH', 'DEPOSIT', NULL, 'wire-01')"
        ),
    )
    .await;

    let projection = get_json(&client, "/api/analysis/projection?years=1").await;
    assert_eq!(num(&projection["monthlyInflowEur"]), "0.00");
    assert_eq!(num(&projection["detectedMonthlyInflowEur"]), "200.00");
    assert_eq!(projection["detectedInflowActiveMonths"], 4);
    assert_eq!(projection["detectedInflowAccountId"], pea);
}

#[tokio::test]
async fn detected_inflow_counts_current_and_previous_month_when_older_deposit_is_outside_window() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = simple(&app, alice, "PEA", "1500").await;

    // Test clock today is 2026-01-15 (rolling 6-month window: Aug 2025..=Jan 2026).
    // Transposed pattern: one older deposit 6 months prior (mid-Jul 2025, outside window) plus
    // two recent deposits on 2 Dec 2025 and 2 Jan 2026 (current month) of 200 EUR each.
    sql(
        &app,
        &format!(
            "INSERT INTO \"transaction\" (account_id, date, description, amount, tx_type) VALUES \
             ({pea}, '2025-07-15', 'Older deposit', 200, 'DEPOSIT'), \
             ({pea}, '2025-12-02', 'Monthly wire', 200, 'DEPOSIT'), \
             ({pea}, '2026-01-02', 'Monthly wire', 200, 'DEPOSIT')"
        ),
    )
    .await;

    let projection = get_json(&client, "/api/analysis/projection?years=1").await;
    assert_eq!(num(&projection["detectedMonthlyInflowEur"]), "200.00");
    assert_eq!(projection["detectedInflowActiveMonths"], 2);
    assert_eq!(projection["detectedInflowAccountId"], pea);
}

#[tokio::test]
async fn detected_inflow_ignores_internal_transfers_between_member_accounts() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let checking_a = simple(&app, alice, "CHECKING", "1000").await;
    let checking_b = simple(&app, alice, "CHECKING", "1000").await;
    let livret = simple(&app, alice, "LIVRET_A", "3000").await;
    let broker_cash = simple(&app, alice, "CHECKING", "500").await;
    let cto = simple(&app, alice, "COMPTE_TITRES", "2000").await;
    let pea = simple(&app, alice, "PEA", "2000").await;
    set_provider(&app, checking_a, "BankA").await;
    set_provider(&app, checking_b, "BankB").await;
    set_provider(&app, livret, "BankA").await;
    set_provider(&app, broker_cash, "BrokerAlpha").await;
    set_provider(&app, cto, "BrokerAlpha").await;
    set_provider(&app, pea, "BrokerAlpha").await;

    sql(
        &app,
        &format!(
            "INSERT INTO \"transaction\" (account_id, date, description, amount) VALUES \
             ({checking_a},  '2025-10-05', 'Internal checking', -200), ({checking_b}, '2025-10-05', 'Internal checking', 200), \
             ({checking_a},  '2025-11-05', 'Internal checking', -200), ({checking_b}, '2025-11-05', 'Internal checking', 200), \
             ({checking_a},  '2025-12-05', 'Internal checking', -200), ({checking_b}, '2025-12-05', 'Internal checking', 200), \
             ({cto},         '2025-10-31', 'Rebalance CTO to PEA', -300), ({pea}, '2025-11-01', 'Rebalance CTO to PEA', 300), \
             ({cto},         '2025-11-10', 'Rebalance CTO to PEA', -300), ({pea}, '2025-11-11', 'Rebalance CTO to PEA', 300), \
             ({cto},         '2025-12-10', 'Rebalance CTO to PEA', -300), ({pea}, '2025-12-11', 'Rebalance CTO to PEA', 300), \
             ({broker_cash}, '2025-10-12', 'Broker cash to PEA', -150), ({pea}, '2025-10-13', 'Broker cash to PEA', 150), \
             ({broker_cash}, '2025-11-12', 'Broker cash to PEA', -150), ({pea}, '2025-11-13', 'Broker cash to PEA', 150), \
             ({broker_cash}, '2025-12-12', 'Broker cash to PEA', -150), ({pea}, '2025-12-13', 'Broker cash to PEA', 150), \
             ({livret},      '2025-10-18', 'Livret to PEA', -100), ({pea}, '2025-10-19', 'Livret to PEA', 100), \
             ({livret},      '2025-11-18', 'Livret to PEA', -100), ({pea}, '2025-11-19', 'Livret to PEA', 100), \
             ({livret},      '2025-12-18', 'Livret to PEA', -100), ({pea}, '2025-12-19', 'Livret to PEA', 100)"
        ),
    )
    .await;

    let projection = get_json(&client, "/api/analysis/projection?years=1").await;
    assert!(projection.get("detectedMonthlyInflowEur").is_none());
    assert!(projection.get("detectedInflowActiveMonths").is_none());
    assert!(projection.get("detectedInflowAccountId").is_none());
}

#[tokio::test]
async fn detected_inflow_ignores_a_single_isolated_deposit() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = simple(&app, alice, "PEA", "2000").await;

    sql(
        &app,
        &format!(
            "INSERT INTO \"transaction\" (account_id, date, description, amount, tx_type) VALUES \
             ({pea}, '2025-12-10', 'One-off deposit', 600, 'DEPOSIT')"
        ),
    )
    .await;

    let projection = get_json(&client, "/api/analysis/projection?years=1").await;
    assert!(projection.get("detectedMonthlyInflowEur").is_none());
}

#[tokio::test]
async fn detected_inflow_excludes_dividends_and_interest() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let broker_cash = simple(&app, alice, "CHECKING", "200").await;
    let pea = simple(&app, alice, "PEA", "5000").await;
    set_provider(&app, broker_cash, "BrokerAlpha").await;
    set_provider(&app, pea, "BrokerAlpha").await;

    let capital_cat_id: i64 = picsou::db::query_scalar(
        "INSERT INTO category (member_id, slug, name, kind, color, is_default, archived, sort_order, created_at, updated_at) \
         VALUES ($1, 'revenus-capital', 'Revenus du capital', 'INCOME'::category_kind, '#10b981', true, false, 0, now(), now()) \
         RETURNING id",
    )
    .bind(alice)
    .fetch_one(&app.state.db)
    .await
    .unwrap();

    sql(
        &app,
        &format!(
            "INSERT INTO \"transaction\" (account_id, date, description, amount, type, category, tx_type, category_id) VALUES \
             ({pea},         '2025-10-15', 'Dividende ETF', 60, 'DIVIDEND', 'DIVIDEND', 'DIVIDEND', NULL), \
             ({pea},         '2025-11-15', 'Dividende ETF', 60, 'DIVIDEND', 'DIVIDEND', 'DIVIDEND', NULL), \
             ({pea},         '2025-12-15', 'Dividende ETF', 60, 'DIVIDEND', 'DIVIDEND', 'DIVIDEND', NULL), \
             ({broker_cash}, '2025-10-20', 'Interest', 25, 'INTEREST', 'CASH', 'DEPOSIT', NULL), \
             ({broker_cash}, '2025-11-20', 'Interest', 25, 'INTEREST', 'CASH', 'DEPOSIT', NULL), \
             ({broker_cash}, '2025-12-20', 'Interest', 25, 'INTEREST', 'CASH', 'DEPOSIT', NULL), \
             ({broker_cash}, '2025-10-22', 'Intérêts créditeurs', 30, NULL, NULL, 'DEPOSIT', NULL), \
             ({broker_cash}, '2025-11-22', 'Intérêts créditeurs', 30, NULL, NULL, 'DEPOSIT', NULL), \
             ({broker_cash}, '2025-12-22', 'Intereses abonados', 30, NULL, NULL, 'DEPOSIT', NULL), \
             ({pea},         '2025-10-25', 'Coupon trimestriel', 40, NULL, NULL, 'DEPOSIT', {capital_cat_id}), \
             ({pea},         '2025-11-25', 'Coupon trimestriel', 40, NULL, NULL, 'DEPOSIT', {capital_cat_id}), \
             ({pea},         '2025-12-25', 'Coupon trimestriel', 40, NULL, NULL, 'DEPOSIT', {capital_cat_id})"
        ),
    )
    .await;

    let yields_only = get_json(&client, "/api/analysis/projection?years=1").await;
    assert!(yields_only.get("detectedMonthlyInflowEur").is_none());

    // Real deposits on broker_cash that actually feed the PEA via TRADING buys are detected cleanly
    // without adding dividends or interest.
    sql(
        &app,
        &format!(
            "INSERT INTO \"transaction\" (account_id, date, description, amount, type, category, tx_type) VALUES \
             ({broker_cash}, '2025-10-02', 'SEPA wire', 200, 'DEPOSIT', 'CASH', 'DEPOSIT'), \
             ({broker_cash}, '2025-10-04', 'BUY World ETF', -200, 'BUY', 'TRADING', 'WITHDRAWAL'), \
             ({broker_cash}, '2025-11-02', 'SEPA wire', 200, 'DEPOSIT', 'CASH', 'DEPOSIT'), \
             ({broker_cash}, '2025-11-04', 'BUY World ETF', -200, 'BUY', 'TRADING', 'WITHDRAWAL'), \
             ({broker_cash}, '2025-12-02', 'SEPA wire', 200, 'DEPOSIT', 'CASH', 'DEPOSIT'), \
             ({broker_cash}, '2025-12-04', 'BUY World ETF', -200, 'BUY', 'TRADING', 'WITHDRAWAL')"
        ),
    )
    .await;

    let with_wires = get_json(&client, "/api/analysis/projection?years=1").await;
    assert_eq!(num(&with_wires["detectedMonthlyInflowEur"]), "200.00");
    assert_eq!(with_wires["detectedInflowActiveMonths"], 3);
}

#[tokio::test]
async fn detected_inflow_does_not_modify_existing_recurring_goal_or_scenarios() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = simple(&app, alice, "PEA", "1000").await;
    sql(
        &app,
        &format!(
            "INSERT INTO goal (id, member_id, name, type, monthly_amount, expected_return, target_amount, deadline) VALUES \
             (1, {alice}, 'PEA DCA', 'RECURRING_INVESTMENT', 100, NULL, NULL, NULL); \
             INSERT INTO goal_account (goal_id, account_id) VALUES (1, {pea}); \
             INSERT INTO \"transaction\" (account_id, date, description, amount, tx_type) VALUES \
             ({pea}, '2025-10-05', 'Monthly wire', 300, 'DEPOSIT'), \
             ({pea}, '2025-11-05', 'Monthly wire', 300, 'DEPOSIT'), \
             ({pea}, '2025-12-05', 'Monthly wire', 300, 'DEPOSIT')"
        ),
    )
    .await;

    let projection = get_json(&client, "/api/analysis/projection?years=1").await;
    assert_eq!(num(&projection["monthlyInflowEur"]), "100.00");
    assert_eq!(
        num(&projection["scenarios"][0]["points"][1]["contributedEur"]),
        "2200.00",
        "scenarios must compound only the confirmed recurring goal, never the detected inflow"
    );
    assert_eq!(num(&projection["detectedMonthlyInflowEur"]), "300.00");
    assert_eq!(projection["detectedInflowActiveMonths"], 3);
    assert_eq!(projection["detectedInflowAccountId"], pea);

    let stored_monthly: rust_decimal::Decimal =
        picsou::db::query_scalar("SELECT monthly_amount FROM goal WHERE id = 1")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(stored_monthly, d("100"));
}

#[tokio::test]
async fn detected_inflow_excludes_universal_bank_checking_with_uncategorized_salary() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let checking = simple(&app, alice, "CHECKING", "2000").await;
    let pea = simple(&app, alice, "PEA", "4000").await;
    set_provider(&app, checking, "UniversalBank").await;
    set_provider(&app, pea, "UniversalBank").await;

    // Uncategorised monthly salary on the universal bank checking account (same provider as PEA)
    // plus a 200 EUR/month transfer into the PEA: the checking account must NOT qualify as broker
    // cash, so only the 200 EUR entering the PEA is detected (never the 2500 EUR salary).
    sql(
        &app,
        &format!(
            "INSERT INTO \"transaction\" (account_id, date, description, amount, counterparty) VALUES \
             ({checking}, '2025-10-28', 'VIR ACME CORP', 2500, 'ACME CORP'), \
             ({checking}, '2025-11-28', 'VIR ACME CORP', 2500, 'ACME CORP'), \
             ({checking}, '2025-12-28', 'VIR ACME CORP', 2500, 'ACME CORP'), \
             ({checking}, '2025-10-05', 'Transfer to PEA', -200, NULL), \
             ({pea},      '2025-10-05', 'Transfer from checking', 200, NULL), \
             ({checking}, '2025-11-05', 'Transfer to PEA', -200, NULL), \
             ({pea},      '2025-11-05', 'Transfer from checking', 200, NULL), \
             ({checking}, '2025-12-05', 'Transfer to PEA', -200, NULL), \
             ({pea},      '2025-12-05', 'Transfer from checking', 200, NULL)"
        ),
    )
    .await;

    let projection = get_json(&client, "/api/analysis/projection?years=1").await;
    assert_eq!(num(&projection["detectedMonthlyInflowEur"]), "200.00");
    assert_eq!(projection["detectedInflowActiveMonths"], 3);
    assert_eq!(projection["detectedInflowAccountId"], pea);

    // A second member whose checking account at the same provider receives an uncategorised salary
    // and never feeds the PEA gets no suggestion at all.
    let (bob, bob_client) = signed_in(&app, "bob").await;
    let bob_checking = simple(&app, bob, "CHECKING", "2000").await;
    let bob_pea = simple(&app, bob, "PEA", "1000").await;
    set_provider(&app, bob_checking, "UniversalBank").await;
    set_provider(&app, bob_pea, "UniversalBank").await;
    sql(
        &app,
        &format!(
            "INSERT INTO \"transaction\" (account_id, date, description, amount) VALUES \
             ({bob_checking}, '2025-10-28', 'Virement employeur', 2400), \
             ({bob_checking}, '2025-11-28', 'Virement employeur', 2400), \
             ({bob_checking}, '2025-12-28', 'Virement employeur', 2400)"
        ),
    )
    .await;

    let bob_projection = get_json(&bob_client, "/api/analysis/projection?years=1").await;
    assert!(bob_projection.get("detectedMonthlyInflowEur").is_none());
}

#[tokio::test]
async fn detected_inflow_deducts_card_expenses_on_broker_cash_account() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let broker_cash = simple(&app, alice, "CHECKING", "300").await;
    let pea = simple(&app, alice, "PEA", "3000").await;
    set_provider(&app, broker_cash, "BrokerAlpha").await;
    set_provider(&app, pea, "BrokerAlpha").await;

    let expense_cat_id: i64 = picsou::db::query_scalar(
        "INSERT INTO category (member_id, slug, name, kind, color, is_default, archived, sort_order, created_at, updated_at) \
         VALUES ($1, 'groceries', 'Groceries', 'EXPENSE'::category_kind, '#ef4444', true, false, 0, now(), now()) \
         RETURNING id",
    )
    .bind(alice)
    .fetch_one(&app.state.db)
    .await
    .unwrap();

    // 300 EUR SEPA wire - 100 EUR debit card spend = 200 EUR net inflow per month.
    // The -200 EUR TRADING leg is still excluded so buying the ETF does not reduce net inflow.
    sql(
        &app,
        &format!(
            "INSERT INTO \"transaction\" (account_id, date, description, amount, type, category, tx_type, category_id) VALUES \
             ({broker_cash}, '2025-10-02', 'SEPA wire', 300, 'DEPOSIT', 'CASH', 'DEPOSIT', NULL), \
             ({broker_cash}, '2025-10-04', 'BUY World ETF', -200, 'BUY', 'TRADING', 'WITHDRAWAL', NULL), \
             ({broker_cash}, '2025-10-10', 'Card supermarket', -100, 'CARD', 'CARD', 'WITHDRAWAL', {expense_cat_id}), \
             ({broker_cash}, '2025-11-02', 'SEPA wire', 300, 'DEPOSIT', 'CASH', 'DEPOSIT', NULL), \
             ({broker_cash}, '2025-11-04', 'BUY World ETF', -200, 'BUY', 'TRADING', 'WITHDRAWAL', NULL), \
             ({broker_cash}, '2025-11-10', 'Card supermarket', -100, 'CARD', 'CARD', 'WITHDRAWAL', {expense_cat_id}), \
             ({broker_cash}, '2025-12-02', 'SEPA wire', 300, 'DEPOSIT', 'CASH', 'DEPOSIT', NULL), \
             ({broker_cash}, '2025-12-04', 'BUY World ETF', -200, 'BUY', 'TRADING', 'WITHDRAWAL', NULL), \
             ({broker_cash}, '2025-12-10', 'Card supermarket', -100, 'CARD', 'CARD', 'WITHDRAWAL', {expense_cat_id})"
        ),
    )
    .await;

    let projection = get_json(&client, "/api/analysis/projection?years=1").await;
    assert_eq!(num(&projection["detectedMonthlyInflowEur"]), "200.00");
    assert_eq!(projection["detectedInflowActiveMonths"], 3);
    assert_eq!(projection["detectedInflowAccountId"], pea);
}

#[tokio::test]
async fn detected_inflow_weights_fifty_percent_share_and_never_prefills_co_owned_account() {
    let app = TestApp::with_admin().await;
    let (bob, _) = signed_in(&app, "bob").await;
    let (alice, alice_client) = signed_in(&app, "alice").await;
    let bob_pea = simple(&app, bob, "PEA", "5000").await;
    share(&app, bob_pea, bob, "50").await;
    share(&app, bob_pea, alice, "50").await;

    sql(
        &app,
        &format!(
            "INSERT INTO \"transaction\" (account_id, date, description, amount, tx_type) VALUES \
             ({bob_pea}, '2025-10-05', 'Joint wire', 400, 'DEPOSIT'), \
             ({bob_pea}, '2025-11-05', 'Joint wire', 400, 'DEPOSIT'), \
             ({bob_pea}, '2025-12-05', 'Joint wire', 400, 'DEPOSIT')"
        ),
    )
    .await;

    let projection = get_json(&alice_client, "/api/analysis/projection?years=1").await;
    assert_eq!(num(&projection["detectedMonthlyInflowEur"]), "200.00");
    assert_eq!(projection["detectedInflowActiveMonths"], 3);
    assert!(
        projection.get("detectedInflowAccountId").is_none(),
        "co-owned account owned by another member must not be suggested for goal prefill"
    );
}

#[tokio::test]
async fn detected_inflow_converts_foreign_currency_and_skips_when_fx_missing() {
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
    let brokerage = simple(&app, alice, "BROKERAGE", "1000").await;
    sql(
        &app,
        &format!(
            "UPDATE account SET currency = 'USD' WHERE id = {brokerage}; \
             INSERT INTO \"transaction\" (account_id, date, description, amount, native_currency, tx_type) VALUES \
             ({brokerage}, '2025-10-05', 'USD wire', 250, 'USD', 'DEPOSIT'), \
             ({brokerage}, '2025-11-05', 'USD wire', 250, 'USD', 'DEPOSIT'), \
             ({brokerage}, '2025-12-05', 'USD wire', 250, 'USD', 'DEPOSIT')"
        ),
    )
    .await;

    // 250 USD * 0.9 = 225 EUR -> rounded to nearest 10 EUR (HALF_UP) = 230.00 EUR.
    let projection = get_json(&client, "/api/analysis/projection?years=1").await;
    assert_eq!(num(&projection["detectedMonthlyInflowEur"]), "230.00");
    assert_eq!(projection["detectedInflowActiveMonths"], 3);
    assert_eq!(projection["detectedInflowAccountId"], brokerage);

    // Without an FX rate for CHF, unconverted foreign deposits are never reported as EUR.
    let (bob, bob_client) = signed_in(&app, "bob").await;
    let chf_broker = simple(&app, bob, "BROKERAGE", "1000").await;
    sql(
        &app,
        &format!(
            "UPDATE account SET currency = 'CHF' WHERE id = {chf_broker}; \
             INSERT INTO \"transaction\" (account_id, date, description, amount, native_currency, tx_type) VALUES \
             ({chf_broker}, '2025-10-05', 'CHF wire', 500, 'CHF', 'DEPOSIT'), \
             ({chf_broker}, '2025-11-05', 'CHF wire', 500, 'CHF', 'DEPOSIT'), \
             ({chf_broker}, '2025-12-05', 'CHF wire', 500, 'CHF', 'DEPOSIT')"
        ),
    )
    .await;

    let bob_projection = get_json(&bob_client, "/api/analysis/projection?years=1").await;
    assert!(bob_projection.get("detectedMonthlyInflowEur").is_none());
}

#[tokio::test]
async fn detected_inflow_does_not_double_pair_one_debit_against_two_identical_credits() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let livret = simple(&app, alice, "LIVRET_A", "3000").await;
    let pea = simple(&app, alice, "PEA", "2000").await;

    // Each month has one -200 EUR debit on Livret A and two +200 EUR credits on the PEA within
    // +/-3 days: the debit pairs with one credit only, leaving the second 200 EUR as external inflow.
    sql(
        &app,
        &format!(
            "INSERT INTO \"transaction\" (account_id, date, description, amount) VALUES \
             ({livret}, '2025-10-10', 'Livret to PEA', -200), \
             ({pea},    '2025-10-10', 'Credit A', 200), \
             ({pea},    '2025-10-11', 'Credit B', 200), \
             ({livret}, '2025-11-10', 'Livret to PEA', -200), \
             ({pea},    '2025-11-10', 'Credit A', 200), \
             ({pea},    '2025-11-11', 'Credit B', 200), \
             ({livret}, '2025-12-10', 'Livret to PEA', -200), \
             ({pea},    '2025-12-10', 'Credit A', 200), \
             ({pea},    '2025-12-11', 'Credit B', 200)"
        ),
    )
    .await;

    let projection = get_json(&client, "/api/analysis/projection?years=1").await;
    assert_eq!(num(&projection["detectedMonthlyInflowEur"]), "200.00");
    assert_eq!(projection["detectedInflowActiveMonths"], 3);
    assert_eq!(projection["detectedInflowAccountId"], pea);
}

#[tokio::test]
async fn detected_inflow_returns_none_when_member_has_no_investment_account() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let checking = simple(&app, alice, "CHECKING", "2000").await;
    let livret = simple(&app, alice, "LIVRET_A", "5000").await;

    sql(
        &app,
        &format!(
            "INSERT INTO \"transaction\" (account_id, date, description, amount) VALUES \
             ({checking}, '2025-10-05', 'Wire in', 300), \
             ({checking}, '2025-11-05', 'Wire in', 300), \
             ({checking}, '2025-12-05', 'Wire in', 300), \
             ({livret},   '2025-10-06', 'Savings deposit', 200), \
             ({livret},   '2025-11-06', 'Savings deposit', 200), \
             ({livret},   '2025-12-06', 'Savings deposit', 200)"
        ),
    )
    .await;

    let projection = get_json(&client, "/api/analysis/projection?years=1").await;
    assert!(projection.get("detectedMonthlyInflowEur").is_none());
    assert!(projection.get("detectedInflowActiveMonths").is_none());
    assert!(projection.get("detectedInflowAccountId").is_none());
}
