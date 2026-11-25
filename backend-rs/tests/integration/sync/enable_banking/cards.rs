//! SyncServiceCardAccountTest: Enable Banking card accounts (#664). A new card is created as one,
//! and an account an earlier release imported as a current account is brought up to date without
//! overriding the member's choices. Synthetic data only.

use picsou::ledger::sidecar::SyncStatus;
use picsou::sync::enable_banking::requisitions::RequisitionStatus;
use picsou::sync::enable_banking::resync_all_reporting;
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{method, path};
use wiremock::{Mock, ResponseTemplate};

use super::support::{Bank, bank, empty_catalog, requisition};

const UID: &str = "uid-card";
const HOLDER_LABEL: &str = "Carte Visa Synthetic Holder";

/// An account an earlier sync stored; by default the card as releases before #664 imported it.
struct Stored {
    external_id: &'static str,
    account_type: &'static str,
    name: &'static str,
    balance: &'static str,
    currency: &'static str,
    iban: Option<&'static str>,
    card_nature: Option<&'static str>,
}

impl Default for Stored {
    fn default() -> Self {
        Self {
            external_id: UID,
            account_type: "CHECKING",
            name: "delayed_debit",
            balance: "-474.87",
            currency: "EUR",
            iban: None,
            card_nature: None,
        }
    }
}

async fn store(b: &Bank, account: Stored) {
    picsou::db::query(&format!(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, is_manual, color, \
         external_account_id, iban, card_nature, created_at, updated_at) \
         VALUES ($1, $2, '{}', 'Synthetic Bank', $3, $4, false, '#123456', $5, $6, $7, now(), now())",
        account.account_type
    ))
    .bind(b.member_id)
    .bind(account.name)
    .bind(account.currency)
    .bind(account.balance.parse::<Decimal>().unwrap())
    .bind(account.external_id)
    .bind(account.iban)
    .bind(account.card_nature)
    .execute(&b.app.state.db)
    .await
    .unwrap();
}

fn deferred_card() -> Value {
    json!({"name": HOLDER_LABEL, "details": "delayed_debit", "cash_account_type": "CARD"})
}

fn amount_owed() -> Value {
    json!([{"name": "Balance", "balance_type": "OTHR", "balance_amount": {"currency": "EUR", "amount": "-581.81"}}])
}

fn credit_left() -> Value {
    json!([{"name": "Balance", "balance_type": "CLAV", "balance_amount": {"currency": "EUR", "amount": "2000.00"}}])
}

/// Syncs a session holding the one account `details` and `balances` describe. A named ASPSP makes
/// its balances account-scoped; without one the list counts as shared. The sync must succeed and
/// read the account's transactions.
async fn sync(b: &Bank, aspsp: Option<&str>, details: Value, balances: Value) {
    requisition(
        &b.app,
        b.member_id,
        "sess-cards",
        "Synthetic Bank::FR::personal",
        RequisitionStatus::Linked,
        None,
    )
    .await;
    let mut session = json!({"accounts": [UID]});
    if let Some(name) = aspsp {
        session["aspsp"] = json!({"name": name, "country": "FR"});
    }
    for (route, body) in [
        ("/sessions/sess-cards".to_owned(), session),
        (format!("/accounts/{UID}/details"), details),
        (
            format!("/accounts/{UID}/balances"),
            json!({ "balances": balances }),
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
        .expect(1..)
        .mount(&b.server)
        .await;
    empty_catalog(&b.server).await;
    let result = resync_all_reporting(&b.app.state, b.member_id).await;
    assert_eq!(result.status, SyncStatus::Synced, "{result:?}");
    b.server.verify().await;
}

async fn sync_card(b: &Bank, balances: Value) {
    sync(b, Some("Synthetic Bank"), deferred_card(), balances).await;
}

/// type, card nature, name, IBAN, balance and currency of the account stored under `external_id`.
type Row = (
    String,
    Option<String>,
    String,
    Option<String>,
    Decimal,
    String,
);

async fn row(b: &Bank, external_id: &str) -> Row {
    picsou::db::query_as(
        "SELECT type::text, card_nature, name, iban, current_balance, currency FROM account \
         WHERE external_account_id = $1",
    )
    .bind(external_id)
    .fetch_one(&b.app.state.db)
    .await
    .unwrap()
}

async fn count(b: &Bank, table: &str) -> i64 {
    picsou::db::query_scalar(&format!("SELECT count(*) FROM {table}"))
        .fetch_one(&b.app.state.db)
        .await
        .unwrap()
}

fn amount(text: &str) -> Decimal {
    text.parse().unwrap()
}

#[tokio::test]
async fn a_new_card_is_created_as_a_card_with_its_nature() {
    let b = bank().await;
    sync_card(&b, amount_owed()).await;
    let (account_type, nature, name, iban, balance, currency) = row(&b, UID).await;
    assert_eq!(account_type, "CREDIT_CARD");
    assert_eq!(nature.as_deref(), Some("DEFERRED_DEBIT"));
    assert_eq!(name, HOLDER_LABEL);
    assert_eq!(iban, None);
    assert_eq!(balance, amount("-581.81"));
    assert_eq!(currency, "EUR");
    assert_eq!(count(&b, "balance_snapshot").await, 1);
}

#[tokio::test]
async fn a_card_imported_as_a_current_account_becomes_a_card_and_loses_its_technical_name_and_iban()
{
    let b = bank().await;
    store(
        &b,
        Stored {
            iban: Some("FR00SYNTHETICCURRENT"),
            ..Stored::default()
        },
    )
    .await;
    sync_card(&b, amount_owed()).await;
    let (account_type, nature, name, iban, balance, _) = row(&b, UID).await;
    assert_eq!(account_type, "CREDIT_CARD");
    assert_eq!(nature.as_deref(), Some("DEFERRED_DEBIT"));
    assert_eq!(name, HOLDER_LABEL);
    assert_eq!(iban, None);
    assert_eq!(balance, amount("-581.81"));
    assert_eq!(count(&b, "account").await, 1);
}

#[tokio::test]
async fn the_type_and_the_name_a_member_chose_are_kept() {
    let b = bank().await;
    store(
        &b,
        Stored {
            account_type: "OTHER",
            name: "My card",
            ..Stored::default()
        },
    )
    .await;
    sync_card(&b, amount_owed()).await;
    let (account_type, nature, name, ..) = row(&b, UID).await;
    assert_eq!(account_type, "OTHER");
    assert_eq!(nature, None);
    assert_eq!(name, "My card");
}

#[tokio::test]
async fn a_technical_name_is_replaced_even_when_the_member_changed_the_type() {
    let b = bank().await;
    store(
        &b,
        Stored {
            account_type: "OTHER",
            ..Stored::default()
        },
    )
    .await;
    sync_card(&b, amount_owed()).await;
    let (account_type, _, name, ..) = row(&b, UID).await;
    assert_eq!(account_type, "OTHER");
    assert_eq!(name, HOLDER_LABEL);
}

#[tokio::test]
async fn a_card_without_a_readable_balance_keeps_its_stored_balance_and_writes_no_snapshot() {
    let b = bank().await;
    store(&b, Stored::default()).await;
    sync_card(&b, credit_left()).await;
    let (account_type, _, name, _, balance, _) = row(&b, UID).await;
    assert_eq!(account_type, "CREDIT_CARD");
    assert_eq!(name, HOLDER_LABEL);
    assert_eq!(balance, amount("-474.87"));
    assert_eq!(count(&b, "balance_snapshot").await, 0);
}

#[tokio::test]
async fn credit_left_stored_as_cash_waits_for_a_readable_balance_before_becoming_a_card() {
    let b = bank().await;
    store(
        &b,
        Stored {
            balance: "2000.00",
            currency: "USD",
            ..Stored::default()
        },
    )
    .await;
    sync_card(&b, credit_left()).await;
    let (account_type, nature, name, _, balance, currency) = row(&b, UID).await;
    assert_eq!(account_type, "CHECKING");
    assert_eq!(nature, None);
    assert_eq!(name, "delayed_debit");
    assert_eq!(balance, amount("2000.00"));
    assert_eq!(currency, "USD");
}

#[tokio::test]
async fn a_stored_card_never_takes_a_positive_balance() {
    let b = bank().await;
    store(
        &b,
        Stored {
            account_type: "CREDIT_CARD",
            name: HOLDER_LABEL,
            ..Stored::default()
        },
    )
    .await;
    // No named ASPSP: the list counts as shared, so the card arrives in its current-account shape.
    sync(
        &b,
        None,
        deferred_card(),
        json!([{"name": "Balance", "balance_type": "OTHR", "balance_amount": {"currency": "EUR", "amount": "1880.00"}}]),
    )
    .await;
    let (account_type, _, name, _, balance, _) = row(&b, UID).await;
    assert_eq!(account_type, "CREDIT_CARD");
    assert_eq!(name, HOLDER_LABEL);
    assert_eq!(balance, amount("-474.87"));
    assert_eq!(count(&b, "balance_snapshot").await, 0);
}

#[tokio::test]
async fn a_card_carrying_the_iban_of_its_current_account_is_neither_matched_to_it_nor_stores_it() {
    let b = bank().await;
    store(
        &b,
        Stored {
            external_id: "uid-current",
            name: "Synthetic current account",
            balance: "826.80",
            iban: Some("FR00SYNTHETIC"),
            ..Stored::default()
        },
    )
    .await;
    let mut details = deferred_card();
    details["account_id"] = json!({"iban": "FR00SYNTHETIC"});
    sync(&b, Some("Synthetic Bank"), details, amount_owed()).await;
    assert_eq!(count(&b, "account").await, 2);
    let (account_type, _, _, iban, ..) = row(&b, UID).await;
    assert_eq!(account_type, "CREDIT_CARD");
    // Two active accounts sharing (iban, currency) would break the current account's IBAN fallback.
    assert_eq!(iban, None);
    let (account_type, _, name, iban, balance, _) = row(&b, "uid-current").await;
    assert_eq!(account_type, "CHECKING");
    assert_eq!(name, "Synthetic current account");
    assert_eq!(iban.as_deref(), Some("FR00SYNTHETIC"));
    assert_eq!(balance, amount("826.80"));
}

#[tokio::test]
async fn a_new_card_without_a_readable_balance_starts_at_zero() {
    let b = bank().await;
    sync_card(&b, credit_left()).await;
    let (account_type, _, _, _, balance, _) = row(&b, UID).await;
    assert_eq!(account_type, "CREDIT_CARD");
    assert_eq!(balance, Decimal::ZERO);
    assert_eq!(count(&b, "balance_snapshot").await, 0);
}

#[tokio::test]
async fn a_known_card_nature_is_not_overwritten() {
    let b = bank().await;
    store(
        &b,
        Stored {
            account_type: "CREDIT_CARD",
            name: HOLDER_LABEL,
            card_nature: Some("CREDIT"),
            ..Stored::default()
        },
    )
    .await;
    sync_card(&b, amount_owed()).await;
    let (account_type, nature, ..) = row(&b, UID).await;
    assert_eq!(account_type, "CREDIT_CARD");
    assert_eq!(nature.as_deref(), Some("CREDIT"));
}
