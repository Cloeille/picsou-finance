//! SCPI fund catalog: listing with the linked account, ignore and restore per member.

use picsou::ledger::accounts::NewAccount;
use picsou::ledger::model::AccountType;
use picsou::testing::TestApp;

use super::support::account;

#[tokio::test]
async fn ignoring_a_fund_unlinks_its_account_and_restore_lifts_the_tombstone() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let member: i64 =
        picsou::db::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    let scpi = account(&app, NewAccount::new(member, "CORUM XL", AccountType::Scpi))
        .await
        .id;
    picsou::db::query("INSERT INTO scpi_position (account_id, member_id, share_count, corum_fund_code) VALUES ($1, $2, 3, 'XL')")
        .bind(scpi)
        .bind(member)
        .execute(&app.state.db)
        .await
        .unwrap();
    picsou::db::query(
        "INSERT INTO scpi_provider_fund (member_id, provider, fund_code, label, last_outcome) VALUES ($1, 'CORUM', 'XL', 'CORUM XL', 'LINKED')",
    )
    .bind(member)
    .execute(&app.state.db)
    .await
    .unwrap();

    let funds = admin.get("/api/scpi/funds").send().await.json();
    assert_eq!(funds[0]["accountId"], scpi);
    assert_eq!(funds[0]["accountName"], "CORUM XL");
    assert_eq!(funds[0]["ignored"], false);
    assert_eq!(funds[0]["lastOutcome"], "LINKED");

    let response = admin.post("/api/scpi/funds/CORUM/XL/ignore").send().await;
    assert_eq!(response.status, 204);
    let funds = admin.get("/api/scpi/funds").send().await.json();
    assert_eq!(funds[0]["ignored"], true);
    assert!(funds[0].get("accountId").is_none());

    // Another member sees nothing and cannot restore it.
    app.create_member("other", "password-1234").await;
    let other = app.login("other", "password-1234").await;
    assert_eq!(
        other.get("/api/scpi/funds").send().await.json(),
        serde_json::json!([])
    );
    let response = other.post("/api/scpi/funds/CORUM/XL/restore").send().await;
    assert_eq!(response.status, 404);
    assert_eq!(response.json()["detail"], "SCPI fund not found");

    let response = admin.post("/api/scpi/funds/CORUM/XL/restore").send().await;
    assert_eq!(response.status, 204);
    assert_eq!(
        admin.get("/api/scpi/funds").send().await.json()[0]["ignored"],
        false
    );

    // Ignoring an unknown fund tombstones it ahead of the sync.
    assert_eq!(
        admin
            .post("/api/scpi/funds/SOFIDY/EP/ignore")
            .send()
            .await
            .status,
        204
    );
    let response = admin.post("/api/scpi/funds/NOPE/EP/ignore").send().await;
    assert_eq!(response.status, 400);
    assert_eq!(
        response.json()["detail"],
        "Failed to convert 'provider' with value: 'NOPE'"
    );
    let long = "X".repeat(41);
    let response = admin
        .post(&format!("/api/scpi/funds/CORUM/{long}/ignore"))
        .send()
        .await;
    assert_eq!(response.json()["detail"], "Invalid fund code");
}

#[tokio::test]
async fn listing_multiple_scpi_funds_resolves_links_across_providers_and_ignores_deleted_accounts()
{
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let member: i64 =
        picsou::db::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    let corum_acc = account(&app, NewAccount::new(member, "CORUM XL", AccountType::Scpi))
        .await
        .id;
    let sofidy_acc = account(
        &app,
        NewAccount::new(member, "Immorente", AccountType::Scpi),
    )
    .await
    .id;
    let deleted_acc = account(
        &app,
        NewAccount::new(member, "Old Louve", AccountType::Scpi),
    )
    .await
    .id;
    picsou::db::query("UPDATE account SET deleted_at = $2 WHERE id = $1")
        .bind(deleted_acc)
        .bind(app.state.clock.now())
        .execute(&app.state.db)
        .await
        .unwrap();

    picsou::db::query(
        "INSERT INTO scpi_position (account_id, member_id, share_count, corum_fund_code) VALUES ($1, $2, 3, 'XL')",
    )
    .bind(corum_acc)
    .bind(member)
    .execute(&app.state.db)
    .await
    .unwrap();
    picsou::db::query(
        "INSERT INTO scpi_position (account_id, member_id, share_count, sofidy_fund_code) VALUES ($1, $2, 5, 'IMMO')",
    )
    .bind(sofidy_acc)
    .bind(member)
    .execute(&app.state.db)
    .await
    .unwrap();
    picsou::db::query(
        "INSERT INTO scpi_position (account_id, member_id, share_count, louve_fund_code) VALUES ($1, $2, 2, 'LV1')",
    )
    .bind(deleted_acc)
    .bind(member)
    .execute(&app.state.db)
    .await
    .unwrap();

    for (provider, code, label) in [
        ("CORUM", "XL", "CORUM XL"),
        ("LOUVE", "LV1", "Louve Deleted"),
        ("SOFIDY", "IMMO", "Immorente"),
        ("SOFIDY", "UNLINKED", "Unlinked Fund"),
    ] {
        picsou::db::query(
            "INSERT INTO scpi_provider_fund (member_id, provider, fund_code, label) VALUES ($1, $2, $3, $4)",
        )
        .bind(member)
        .bind(provider)
        .bind(code)
        .bind(label)
        .execute(&app.state.db)
        .await
        .unwrap();
    }

    let funds = admin.get("/api/scpi/funds").send().await.json();
    let arr = funds.as_array().unwrap();
    assert_eq!(arr.len(), 4);
    // Ordered by provider, label: CORUM, LOUVE, SOFIDY (Immorente), SOFIDY (Unlinked Fund)
    assert_eq!(arr[0]["accountId"], corum_acc);
    assert_eq!(arr[0]["accountName"], "CORUM XL");
    assert!(arr[1].get("accountId").is_none());
    assert!(arr[1].get("accountName").is_none());
    assert_eq!(arr[2]["accountId"], sofidy_acc);
    assert_eq!(arr[2]["accountName"], "Immorente");
    assert!(arr[3].get("accountId").is_none());
}
