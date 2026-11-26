//! An Enable Banking resync keeps the fund a member said the account tracks: the sync writes the
//! balance, never the tracked ISIN. Synthetic data only.

use picsou::ledger::sidecar::SyncStatus;
use picsou::sync::enable_banking::requisitions::RequisitionStatus;
use picsou::sync::enable_banking::resync_all_reporting;
use rust_decimal::Decimal;
use serde_json::json;
use wiremock::matchers::{method, path};
use wiremock::{Mock, ResponseTemplate};

use super::support::{Bank, bank, empty_catalog, requisition};

const UID: &str = "uid-tracked";
const ISIN: &str = "IE00B4L5Y983";

/// The account an earlier sync stored, with the fund its member set by hand.
async fn store(b: &Bank) {
    picsou::db::query(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, is_manual, color, \
         external_account_id, tracked_isin, created_at, updated_at) \
         VALUES ($1, 'Synthetic fund account', 'CHECKING', 'Synthetic Bank', 'EUR', $2, false, '#123456', $3, $4, \
         now(), now())",
    )
    .bind(b.member_id)
    .bind("1000.00".parse::<Decimal>().unwrap())
    .bind(UID)
    .bind(ISIN)
    .execute(&b.app.state.db)
    .await
    .unwrap();
}

async fn sync(b: &Bank) {
    requisition(
        &b.app,
        b.member_id,
        "sess-tracked",
        "Synthetic Bank::FR::personal",
        RequisitionStatus::Linked,
        None,
    )
    .await;
    for (route, body) in [
        (
            "/sessions/sess-tracked".to_owned(),
            json!({"accounts": [UID], "aspsp": {"name": "Synthetic Bank", "country": "FR"}}),
        ),
        (
            format!("/accounts/{UID}/details"),
            json!({"name": "Synthetic Holder", "cash_account_type": "CACC"}),
        ),
        (
            format!("/accounts/{UID}/balances"),
            json!({"balances": [{"name": "Balance", "balance_type": "CLBD", "balance_amount": {"currency": "EUR", "amount": "1200.00"}}]}),
        ),
    ] {
        Mock::given(method("GET"))
            .and(path(route))
            .respond_with(ResponseTemplate::new(200).set_body_json(body))
            .mount(&b.server)
            .await;
    }
    Mock::given(method("GET"))
        .and(path(format!("/accounts/{UID}/transactions")))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"transactions": []})))
        .mount(&b.server)
        .await;
    empty_catalog(&b.server).await;
    let result = resync_all_reporting(&b.app.state, b.member_id).await;
    assert_eq!(result.status, SyncStatus::Synced, "{result:?}");
}

#[tokio::test]
async fn an_enable_banking_resync_keeps_the_tracked_isin() {
    let b = bank().await;
    store(&b).await;
    sync(&b).await;
    let (tracked_isin, balance): (Option<String>, Decimal) = picsou::db::query_as(
        "SELECT tracked_isin, current_balance FROM account WHERE external_account_id = $1",
    )
    .bind(UID)
    .fetch_one(&b.app.state.db)
    .await
    .unwrap();
    assert_eq!(tracked_isin.as_deref(), Some(ISIN));
    assert_eq!(balance, "1200.00".parse::<Decimal>().unwrap());
}
