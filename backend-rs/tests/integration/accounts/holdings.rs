//! The holdings read model and edits, classification overrides and stored instrument logos.

use picsou::testing::TestApp;
use serde_json::json;

use super::analysis_support::{
    Acct, account, get_json, holding, num, price, share, signed_in, simple, sql,
};

#[tokio::test]
async fn another_members_holdings_are_a_404_and_a_co_owner_too_as_in_java() {
    let app = TestApp::with_admin().await;
    let (alice, alice_client) = signed_in(&app, "alice").await;
    let (bob, bob_client) = signed_in(&app, "bob").await;
    let pea = simple(&app, alice, "PEA", "0").await;
    holding(&app, pea, "PSX", "1", Some("10")).await;

    for client in [&bob_client] {
        assert_eq!(
            client
                .get(&format!("/api/accounts/{pea}/holdings"))
                .send()
                .await
                .status,
            404
        );
        let put = client
            .put(&format!("/api/accounts/{pea}/holdings/PSX"))
            .json(&json!({"quantity": 2}))
            .send()
            .await;
        assert_eq!(put.status, 404);
        assert_eq!(
            client
                .delete(&format!("/api/accounts/{pea}/holdings/PSX"))
                .send()
                .await
                .status,
            404
        );
    }
    // getHoldings is owner-scoped in Java: a co-owner reads the account elsewhere, not here.
    share(&app, pea, alice, "50").await;
    share(&app, pea, bob, "50").await;
    assert_eq!(
        bob_client
            .get(&format!("/api/accounts/{pea}/holdings"))
            .send()
            .await
            .status,
        404
    );
    assert_eq!(
        get_json(&alice_client, &format!("/api/accounts/{pea}/holdings")).await[0]["ticker"],
        "PSX"
    );
}

#[tokio::test]
async fn a_recorded_price_values_the_line_and_marks_it_stale() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = simple(&app, alice, "PEA", "0").await;
    holding(&app, pea, "PSB", "5.00000000", Some("150.00000000")).await;
    holding(&app, pea, "PSA", "3.00000000", Some("10.00000000")).await;
    holding(&app, pea, "PSZ", "0.00000000", Some("0.00000000")).await;
    price(&app, "PSB", "180.00000000").await;

    let lines = get_json(&client, &format!("/api/accounts/{pea}/holdings")).await;
    // Equal (null) stored prices come out in ticker order, as the (account, ticker) index does.
    let tickers: Vec<&str> = lines
        .as_array()
        .unwrap()
        .iter()
        .map(|l| l["ticker"].as_str().unwrap())
        .collect();
    assert_eq!(tickers, ["PSA", "PSB", "PSZ"]);
    let priced = &lines[1];
    assert_eq!(num(&priced["currentValueEur"]), "900.0000000000000000");
    assert_eq!(num(&priced["costBasisEur"]), "750.0000000000000000");
    assert_eq!(num(&priced["pnlEur"]), "150.0000000000000000");
    assert_eq!(num(&priced["pnlPercent"]), "20.0000");
    assert_eq!(priced["priceAsOf"], "2026-01-14");
    assert_eq!(priced["priceStale"], true);
    // No price of any age: the value stays unknown rather than guessed.
    let unpriced = &lines[0];
    assert!(unpriced.get("currentValueEur").is_none());
    assert!(unpriced.get("pnlPercent").is_none());
    assert_eq!(unpriced["priceStale"], false);
    // A zero keeps the column's scale, as BigDecimal.toString writes it.
    let raw = client
        .get(&format!("/api/accounts/{pea}/holdings"))
        .send()
        .await
        .text();
    assert!(
        raw.contains(r#""ticker":"PSZ","name":"PSZ name","quantity":0E-8"#),
        "{raw}"
    );
}

#[tokio::test]
async fn a_short_positions_percentage_keeps_the_sign_of_its_pnl() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let cto = simple(&app, alice, "COMPTE_TITRES", "0").await;
    holding(&app, cto, "PSS", "-10", Some("100")).await;
    price(&app, "PSS", "80").await;
    let line = &get_json(&client, &format!("/api/accounts/{cto}/holdings")).await[0];
    assert_eq!(num(&line["pnlEur"]), "200.0000000000000000");
    assert_eq!(num(&line["pnlPercent"]), "20.0000");
}

#[tokio::test]
async fn a_stored_mark_gives_a_versioned_url_and_is_served_with_an_etag() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = simple(&app, alice, "PEA", "0").await;
    holding(&app, pea, "PS W", "1", None).await;
    picsou::db::query(
        "INSERT INTO instrument_logo (ticker, status, image, content_type, image_dark, content_type_dark, \
         attempted_at, fetched_at) VALUES ('PS W', 'STORED', $1, 'image/png', $2, 'image/svg+xml', \
         '2024-05-01T10:00:00Z', '2024-05-01T10:00:00Z')",
    )
    .bind(&[1u8, 2][..])
    .bind(&[3u8, 4][..])
    .execute(&app.state.db)
    .await
    .unwrap();
    let line = &get_json(&client, &format!("/api/accounts/{pea}/holdings")).await[0];
    assert_eq!(line["logoUrl"], "/api/instrument-logos/PS%20W?v=1714557600");
    assert_eq!(
        line["logoUrlDark"],
        "/api/instrument-logos/PS%20W?v=1714557600&variant=dark"
    );

    let light = client.get("/api/instrument-logos/ps%20w").send().await;
    assert_eq!(light.status, 200);
    assert_eq!(light.body, [1, 2]);
    assert_eq!(light.header("content-type"), Some("image/png"));
    assert_eq!(
        light.header("cache-control"),
        Some("max-age=2592000, private")
    );
    assert_eq!(light.header("etag"), Some("\"1714557600-light\""));
    let dark = client
        .get("/api/instrument-logos/PS%20W?variant=dark")
        .send()
        .await;
    assert_eq!(dark.body, [3, 4]);
    assert_eq!(dark.header("etag"), Some("\"1714557600-dark\""));

    let revalidated = client
        .get("/api/instrument-logos/PS%20W")
        .header("If-None-Match", "W/\"1714557600-light\"")
        .send()
        .await;
    assert_eq!(revalidated.status, 304);
    assert!(revalidated.body.is_empty());
    assert_eq!(
        client.get("/api/instrument-logos/NONE").send().await.status,
        404
    );
    assert_eq!(
        app.client()
            .get("/api/instrument-logos/PS%20W")
            .send()
            .await
            .status,
        401
    );
}

#[tokio::test]
async fn a_dark_request_falls_back_to_the_light_mark() {
    let app = TestApp::with_admin().await;
    let (_, client) = signed_in(&app, "alice").await;
    picsou::db::query(
        "INSERT INTO instrument_logo (ticker, status, image, content_type, attempted_at, fetched_at) \
         VALUES ('PSL', 'STORED', $1, 'image/png', now(), '2024-05-01T10:00:00Z')",
    )
    .bind(&[9u8][..])
    .execute(&app.state.db)
    .await
    .unwrap();
    let served = client
        .get("/api/instrument-logos/PSL?variant=dark")
        .send()
        .await;
    assert_eq!(served.body, [9]);
    assert_eq!(served.header("etag"), Some("\"1714557600-light\""));
}

#[tokio::test]
async fn an_edit_on_a_manual_account_sets_quantity_and_buy_in_and_drops_the_providers_pnl() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = simple(&app, alice, "PEA", "0").await;
    holding(&app, pea, "PSE", "5", Some("10")).await;
    sql(
        &app,
        "UPDATE account_holding SET provider_value_eur = 70, provider_pnl_eur = 20",
    )
    .await;

    let response = client
        .put(&format!("/api/accounts/{pea}/holdings/PSE"))
        .json(&json!({"quantity": 6, "averageBuyIn": 12.5}))
        .send()
        .await;
    assert_eq!(response.status, 200, "{}", response.text());
    let line = response.json();
    // The request's own scale, as Java answers with the entity it set.
    assert_eq!(num(&line["quantity"]), "6");
    assert_eq!(num(&line["averageBuyIn"]), "12.5");
    assert!(
        line.get("currentValueEur").is_none(),
        "the provider's value went with its P&L"
    );
    let (value, pnl): (Option<rust_decimal::Decimal>, Option<rust_decimal::Decimal>) =
        picsou::db::query_as("SELECT provider_value_eur, provider_pnl_eur FROM account_holding")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!((value, pnl), (None, None));
}

#[tokio::test]
async fn a_synced_account_keeps_the_quantity_its_provider_reported() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let wallet = account(
        &app,
        Acct {
            manual: false,
            ..Acct::new(alice, "CRYPTO", "0")
        },
    )
    .await;
    holding(&app, wallet, "PSC", "2.00000000", None).await;
    let line = client
        .put(&format!("/api/accounts/{wallet}/holdings/PSC"))
        .json(&json!({"quantity": 99, "averageBuyIn": 3}))
        .send()
        .await
        .json();
    assert_eq!(num(&line["quantity"]), "2.00000000");
    assert_eq!(num(&line["averageBuyIn"]), "3");
}

#[tokio::test]
async fn an_edit_needs_a_quantity_and_an_existing_line() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = simple(&app, alice, "PEA", "0").await;
    holding(&app, pea, "PSE", "5", None).await;
    let missing = client
        .put(&format!("/api/accounts/{pea}/holdings/PSE"))
        .json(&json!({"averageBuyIn": 1}))
        .send()
        .await;
    assert_eq!(missing.status, 422);
    assert_eq!(missing.json()["errors"]["quantity"], "must not be null");
    // The ticker is matched exactly, as the repository does.
    let other = client
        .put(&format!("/api/accounts/{pea}/holdings/pse"))
        .json(&json!({"quantity": 1}))
        .send()
        .await;
    assert_eq!(other.status, 404);
    assert_eq!(other.json()["detail"], "Holding not found");
}

#[tokio::test]
async fn deleting_a_line_answers_204_then_404() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = simple(&app, alice, "PEA", "0").await;
    holding(&app, pea, "PSD", "1", None).await;
    let path = format!("/api/accounts/{pea}/holdings/PSD");
    assert_eq!(client.delete(&path).send().await.status, 204);
    assert_eq!(client.delete(&path).send().await.status, 404);
    assert_eq!(
        get_json(&client, &format!("/api/accounts/{pea}/holdings")).await,
        json!([])
    );
}

#[tokio::test]
async fn an_override_is_stored_uppercased_and_clearing_every_field_drops_it() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = simple(&app, alice, "PEA", "0").await;
    let path = format!("/api/accounts/{pea}/holdings/gold/classification");
    let saved = client
        .put(&path)
        .json(&json!({"wealthTier": "ALTERNATIVE"}))
        .send()
        .await;
    assert_eq!(saved.status, 200, "{}", saved.text());
    assert_eq!(
        saved.json(),
        json!({"ticker": "GOLD", "wealthTier": "ALTERNATIVE"})
    );
    let stored: String = picsou::db::query_scalar("SELECT ticker FROM holding_classification")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(stored, "GOLD");

    let cleared = client
        .put(&path)
        .json(&json!({"wealthTier": null, "sectorKey": null, "countryKey": null}))
        .send()
        .await;
    assert_eq!(cleared.json(), json!({"ticker": "GOLD"}));
    let rows: i64 = picsou::db::query_scalar("SELECT count(*) FROM holding_classification")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(rows, 0);
}

#[tokio::test]
async fn the_view_keeps_the_override_and_the_providers_guess_apart() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = simple(&app, alice, "PEA", "0").await;
    sql(
        &app,
        "INSERT INTO security_profile (ticker, asset_type, sector_key, country_key, status) VALUES \
         ('PSA', 'STOCK', 'technology', 'US', 'OK'), ('PSN', 'UNKNOWN', NULL, NULL, 'NEVER_FETCHED')",
    )
    .await;
    client
        .put(&format!("/api/accounts/{pea}/holdings/PSA/classification"))
        .json(&json!({"sectorKey": "energy"}))
        .send()
        .await;
    let view = get_json(
        &client,
        &format!("/api/accounts/{pea}/holdings/psa/classification"),
    )
    .await;
    assert_eq!(
        view,
        json!({"ticker": "PSA", "sectorKey": "energy", "inferredSectorKey": "technology",
               "inferredCountryKey": "US", "profileLooked": true})
    );
    // A row a sync seeded from an ISIN is not a lookup.
    let seeded = get_json(
        &client,
        &format!("/api/accounts/{pea}/holdings/PSN/classification"),
    )
    .await;
    assert_eq!(seeded, json!({"ticker": "PSN", "profileLooked": false}));
}

#[tokio::test]
async fn a_co_owner_reads_the_classification_but_cannot_rewrite_it() {
    let app = TestApp::with_admin().await;
    let (alice, _) = signed_in(&app, "alice").await;
    let (bob, bob_client) = signed_in(&app, "bob").await;
    let (_, carol_client) = signed_in(&app, "carol").await;
    let pea = simple(&app, alice, "PEA", "0").await;
    share(&app, pea, alice, "60").await;
    share(&app, pea, bob, "40").await;
    let path = format!("/api/accounts/{pea}/holdings/PSX/classification");
    assert_eq!(bob_client.get(&path).send().await.status, 200);
    let write = bob_client
        .put(&path)
        .json(&json!({"wealthTier": "EQUITY"}))
        .send()
        .await;
    assert_eq!(write.status, 403);
    assert_eq!(
        write.json()["detail"],
        "Only the owning member can modify this account"
    );
    assert_eq!(carol_client.get(&path).send().await.status, 404);
    assert_eq!(
        carol_client.put(&path).json(&json!({})).send().await.status,
        404
    );
}

#[tokio::test]
async fn a_malformed_override_is_rejected() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = simple(&app, alice, "PEA", "0").await;
    let path = format!("/api/accounts/{pea}/holdings/PSX/classification");
    let invalid = client
        .put(&path)
        .json(&json!({"countryKey": "fr", "sectorKey": "x".repeat(41)}))
        .send()
        .await;
    assert_eq!(invalid.status, 422);
    let errors = &invalid.json()["errors"];
    assert_eq!(
        errors["countryKey"],
        "must be an ISO 3166-1 alpha-2 country code"
    );
    assert_eq!(errors["sectorKey"], "size must be between 0 and 40");
    let bogus = client
        .put(&path)
        .json(&json!({"wealthTier": "BOGUS"}))
        .send()
        .await;
    assert_eq!(bogus.status, 400);
}

#[tokio::test]
async fn an_override_with_surrounding_spaces_updates_reads_and_clears_without_duplicating() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = simple(&app, alice, "PEA", "0").await;
    let path = format!("/api/accounts/{pea}/holdings/%20gold%20/classification");

    let first = client
        .put(&path)
        .json(&json!({"wealthTier": "ALTERNATIVE"}))
        .send()
        .await;
    assert_eq!(first.status, 200, "{}", first.text());
    assert_eq!(
        first.json(),
        json!({"ticker": "GOLD", "wealthTier": "ALTERNATIVE"})
    );

    let second = client
        .put(&path)
        .json(&json!({"wealthTier": "EQUITY"}))
        .send()
        .await;
    assert_eq!(second.status, 200, "{}", second.text());
    assert_eq!(
        second.json(),
        json!({"ticker": "GOLD", "wealthTier": "EQUITY"})
    );

    let view = get_json(&client, &path).await;
    assert_eq!(
        view,
        json!({"ticker": "GOLD", "wealthTier": "EQUITY", "profileLooked": false})
    );

    let cleared = client.put(&path).json(&json!({})).send().await;
    assert_eq!(cleared.status, 200, "{}", cleared.text());
    let rows: i64 = picsou::db::query_scalar("SELECT count(*) FROM holding_classification")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(rows, 0);
}

#[tokio::test]
async fn holding_update_overflowing_cost_basis_returns_validation_error() {
    let app = TestApp::with_admin().await;
    let (alice, client) = signed_in(&app, "alice").await;
    let pea = simple(&app, alice, "PEA", "0").await;
    holding(&app, pea, "PSX", "1", Some("10")).await;

    let body: serde_json::Value =
        serde_json::from_str(r#"{"quantity": 79228162514264337593543950335, "averageBuyIn": 2}"#)
            .unwrap();
    let resp = client
        .put(&format!("/api/accounts/{pea}/holdings/PSX"))
        .json(&body)
        .send()
        .await;
    assert_eq!(resp.status, 422, "{}", resp.text());
    assert!(resp.json()["errors"]["averageBuyIn"].is_string());
}
