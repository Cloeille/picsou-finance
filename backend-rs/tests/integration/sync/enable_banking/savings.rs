//! Enable Banking savings accounts: an account the provider declares with the ISO 20022 cash
//! account type `SVGS` is created as Savings, and one an earlier release imported as a current
//! account is brought up to date without overriding the member's choice. Synthetic data only.

use picsou::ledger::sidecar::SyncStatus;
use picsou::sync::enable_banking::requisitions::RequisitionStatus;
use picsou::sync::enable_banking::resync_all_reporting;
use rust_decimal::Decimal;
use serde_json::json;
use wiremock::matchers::{method, path};
use wiremock::{Mock, ResponseTemplate};

use super::support::{Bank, bank, empty_catalog, requisition};

const UID: &str = "uid-savings";

/// An account an earlier sync stored with `account_type`.
async fn store(b: &Bank, account_type: &str) {
    picsou::db::query(&format!(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, is_manual, color, \
         external_account_id, created_at, updated_at) \
         VALUES ($1, 'Synthetic jar', '{account_type}', 'Synthetic Bank', 'EUR', 100, false, '#123456', $2, now(), now())"
    ))
    .bind(b.member_id)
    .bind(UID)
    .execute(&b.app.state.db)
    .await
    .unwrap();
}

/// Syncs a session holding the one account, declared with `cash_account_type`.
async fn sync(b: &Bank, cash_account_type: &str) {
    requisition(
        &b.app,
        b.member_id,
        "sess-savings",
        "Synthetic Bank::FR::personal",
        RequisitionStatus::Linked,
        None,
    )
    .await;
    let details = json!({"name": "", "details": "Synthetic jar", "usage": "PRIV",
                         "cash_account_type": cash_account_type, "currency": "EUR"});
    let balances = json!({"balances": [{"name": "Available", "balance_type": "ITAV",
                                        "balance_amount": {"currency": "EUR", "amount": "1200.00"}}]});
    for (route, body) in [
        (
            "/sessions/sess-savings".to_owned(),
            json!({"accounts": [UID], "aspsp": {"name": "Synthetic Bank", "country": "FR"}}),
        ),
        (format!("/accounts/{UID}/details"), details),
        (format!("/accounts/{UID}/balances"), balances),
        (
            format!("/accounts/{UID}/transactions"),
            json!({"transactions": []}),
        ),
    ] {
        Mock::given(method("GET"))
            .and(path(route))
            .respond_with(ResponseTemplate::new(200).set_body_json(body))
            .mount(&b.server)
            .await;
    }
    empty_catalog(&b.server).await;
    let result = resync_all_reporting(&b.app.state, b.member_id).await;
    assert_eq!(result.status, SyncStatus::Synced, "{result:?}");
}

/// Type and balance of the synced account.
async fn row(b: &Bank) -> (String, Decimal) {
    picsou::db::query_as(
        "SELECT type::text, current_balance FROM account WHERE external_account_id = $1",
    )
    .bind(UID)
    .fetch_one(&b.app.state.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn a_new_savings_account_is_created_as_savings() {
    let b = bank().await;
    sync(&b, "SVGS").await;
    let (account_type, balance) = row(&b).await;
    assert_eq!(account_type, "SAVINGS");
    assert_eq!(balance, "1200.00".parse::<Decimal>().unwrap());
}

#[tokio::test]
async fn a_savings_account_imported_as_a_current_account_becomes_savings() {
    let b = bank().await;
    store(&b, "CHECKING").await;
    sync(&b, "SVGS").await;
    assert_eq!(row(&b).await.0, "SAVINGS");
}

#[tokio::test]
async fn the_type_a_member_chose_is_kept() {
    let b = bank().await;
    store(&b, "COMPTE_TITRES").await;
    sync(&b, "SVGS").await;
    assert_eq!(row(&b).await.0, "COMPTE_TITRES");
}

#[tokio::test]
async fn a_current_account_stays_a_current_account() {
    let b = bank().await;
    store(&b, "CHECKING").await;
    sync(&b, "CACC").await;
    assert_eq!(row(&b).await.0, "CHECKING");
}

#[tokio::test]
async fn a_savings_account_the_bank_stops_declaring_svgs_stays_savings() {
    let b = bank().await;
    store(&b, "SAVINGS").await;
    sync(&b, "CACC").await;
    assert_eq!(row(&b).await.0, "SAVINGS");
}
