//! BankTransactionImportServiceTest and BankTransactionImportServiceLedgerTest, on real rows.

use std::str::FromStr;

use chrono::{DateTime, NaiveDate, Utc};
use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::bank_import::{self, DEFAULT_INITIAL_HISTORY_DAYS, TransactionData};
use picsou::ledger::categorize;
use picsou::ledger::model::{Account, AccountType, NewTransaction, Transaction};
use picsou::ledger::transactions;
use picsou::testing::{ADMIN_USERNAME, TestApp};
use rust_decimal::Decimal;
use sqlx::PgConnection;

const DESCRIPTION: &str = "Cafe de la Place";
const EMOJI: &str = "\u{1F600}";

fn date() -> NaiveDate {
    NaiveDate::from_ymd_opt(2026, 9, 20).unwrap()
}

fn today() -> NaiveDate {
    NaiveDate::from_ymd_opt(2026, 9, 25).unwrap()
}

fn day() -> NaiveDate {
    NaiveDate::from_ymd_opt(2026, 1, 2).unwrap()
}

fn ledger_today() -> NaiveDate {
    NaiveDate::from_ymd_opt(2026, 1, 15).unwrap()
}

fn now() -> DateTime<Utc> {
    picsou::testing::epoch()
}

fn dec(value: &str) -> Decimal {
    Decimal::from_str(value)
        .or_else(|_| Decimal::from_scientific(value))
        .unwrap()
}

fn amount() -> Decimal {
    dec("-4.99")
}

struct Fixture {
    app: TestApp,
    member_id: i64,
}

impl Fixture {
    async fn new() -> Self {
        let app = TestApp::with_admin().await;
        let member_id = picsou::auth::users::find_by_username(&app.state.db, ADMIN_USERNAME)
            .await
            .unwrap()
            .unwrap()
            .member_id;
        Self { app, member_id }
    }

    async fn conn(&self) -> sqlx::pool::PoolConnection<sqlx::Postgres> {
        self.app.state.db.acquire().await.unwrap()
    }

    async fn account(
        &self,
        currency: &str,
        account_type: AccountType,
        external: Option<&str>,
    ) -> Account {
        let mut new = NewAccount::new(self.member_id, "Bank account", account_type);
        new.currency = currency.to_owned();
        new.is_manual = false;
        new.external_account_id = external.map(str::to_owned);
        accounts::insert(&self.app.state.db, &new, now())
            .await
            .unwrap()
    }

    /// The Java tests' account(55L): an EUR Enable Banking account.
    async fn eb_account(&self) -> Account {
        self.account("EUR", AccountType::Checking, Some("eb-account-55"))
            .await
    }

    /// The ledger tests' account(id): a USD account.
    async fn usd_account(&self) -> Account {
        self.account("USD", AccountType::Checking, None).await
    }

    async fn store(&self, row: &NewTransaction) -> Transaction {
        transactions::insert(&self.app.state.db, row, now())
            .await
            .unwrap()
    }

    async fn rows(&self, account: &Account) -> Vec<Transaction> {
        transactions::find_synced_by_account(&self.app.state.db, account.id, None)
            .await
            .unwrap()
    }

    async fn courses_category(&self) -> i64 {
        categorize::categories_by_slug(&self.app.state.db, self.member_id, now())
            .await
            .unwrap()["courses"]
            .id
    }

    /// importFor after the fetch: the window it would have requested, then the fetched rows.
    async fn import_for(&self, account: &Account, fetched: &[TransactionData]) -> usize {
        let mut conn = self.conn().await;
        import_for_on(&mut conn, account, fetched).await
    }

    async fn import_provided(&self, account: &Account, fetched: &[TransactionData]) -> usize {
        let mut conn = self.conn().await;
        bank_import::import_provided(
            &mut conn,
            account,
            fetched,
            ledger_today(),
            DEFAULT_INITIAL_HISTORY_DAYS,
            now(),
        )
        .await
        .unwrap()
    }
}

async fn import_for_on(
    conn: &mut PgConnection,
    account: &Account,
    fetched: &[TransactionData],
) -> usize {
    let start = bank_import::window_start(
        &mut *conn,
        account.id,
        today(),
        DEFAULT_INITIAL_HISTORY_DAYS,
    )
    .await
    .unwrap();
    bank_import::import_fetched(conn, account, start, fetched, now())
        .await
        .unwrap()
}

fn tx_data(
    id: Option<&str>,
    on: NaiveDate,
    description: Option<&str>,
    value: Decimal,
    currency: Option<&str>,
) -> TransactionData {
    TransactionData {
        external_id: id.map(str::to_owned),
        date: Some(on),
        description: description.map(str::to_owned),
        amount: Some(value),
        currency: currency.map(str::to_owned),
        category: None,
    }
}

fn data(id: Option<&str>, description: &str) -> TransactionData {
    tx_data(id, date(), Some(description), amount(), Some("EUR"))
}

fn row(id: &str, description: Option<&str>) -> TransactionData {
    tx_data(Some(id), day(), description, dec("-4.50"), Some("USD"))
}

fn stored_row(account: &Account, on: NaiveDate, description: &str, value: &str) -> NewTransaction {
    NewTransaction::new(account.id, on, description, dec(value))
}

fn eb_stored(
    account: &Account,
    transaction_id: &str,
    external_id: Option<&str>,
    category_id: Option<i64>,
) -> NewTransaction {
    let mut tx = NewTransaction::new(account.id, date(), DESCRIPTION, amount());
    tx.external_id = external_id.map(str::to_owned);
    tx.external_transaction_id = Some(transaction_id.to_owned());
    tx.category_id = category_id;
    tx.category_manual = category_id.is_some();
    tx
}

#[tokio::test]
async fn import_for_reserves_stable_ids_before_matching_the_strict_multiset_regardless_of_fetch_order()
 {
    let f = Fixture::new().await;
    let category = f.courses_category().await;
    let without_id = data(None, DESCRIPTION);
    let known_id = data(Some("eb-transaction-1"), DESCRIPTION);
    let new_id = data(Some("eb-transaction-2"), DESCRIPTION);
    for fetched in [
        vec![without_id.clone(), known_id.clone(), new_id.clone()],
        vec![known_id.clone(), without_id.clone(), new_id.clone()],
    ] {
        let account = f.eb_account().await;
        let eb = f
            .store(&eb_stored(
                &account,
                "eb-transaction-1",
                None,
                Some(category),
            ))
            .await;
        let sidecar = f
            .store(&eb_stored(
                &account,
                "bourso:sidecar-transaction-1",
                Some("sidecar-transaction-1"),
                Some(category),
            ))
            .await;

        assert_eq!(f.import_for(&account, &fetched).await, 1);

        let rows = f.rows(&account).await;
        assert_eq!(rows.len(), 3);
        assert_eq!(rows[0], eb);
        assert_eq!(rows[1], sidecar);
        assert!(eb.category_manual && eb.category_id == Some(category));
        let inserted = &rows[2];
        assert_eq!(
            inserted.external_transaction_id.as_deref(),
            Some("eb-transaction-2")
        );
        assert_eq!(inserted.date, date());
        assert_eq!(inserted.amount, amount());
        assert_eq!(inserted.description, DESCRIPTION);
    }
}

#[tokio::test]
async fn import_for_preserves_two_identical_transactions_without_provider_ids() {
    let f = Fixture::new().await;
    let account = f.eb_account().await;

    let fetched = [data(None, DESCRIPTION), data(None, DESCRIPTION)];
    assert_eq!(f.import_for(&account, &fetched).await, 2);

    let rows = f.rows(&account).await;
    assert_eq!(rows.len(), 2);
    let key = rows[0].external_transaction_id.clone().unwrap();
    assert!(key.starts_with("fp:"));
    assert_eq!(
        rows[1].external_transaction_id.as_deref(),
        Some(key.as_str())
    );
}

#[tokio::test]
async fn import_for_preserves_same_stable_id_and_content_multiplicity_across_replays_and_reordering()
 {
    let f = Fixture::new().await;
    let account = f.eb_account().await;
    let duplicate = tx_data(
        Some("eb-reused-reference"),
        date(),
        Some(DESCRIPTION),
        amount(),
        Some("EUR"),
    );
    let two = [duplicate.clone(), duplicate.clone()];
    let three = [duplicate.clone(), duplicate.clone(), duplicate];

    assert_eq!(f.import_for(&account, &two).await, 2);
    assert_eq!(f.rows(&account).await.len(), 2);
    assert_eq!(f.import_for(&account, &two).await, 0);
    assert_eq!(f.rows(&account).await.len(), 2);
    assert_eq!(f.import_for(&account, &three).await, 1);

    let rows = f.rows(&account).await;
    assert_eq!(rows.len(), 3);
    assert!(
        rows.iter()
            .all(|r| r.external_transaction_id.as_deref() == Some("eb-reused-reference"))
    );
}

#[tokio::test]
async fn import_for_rounds_amounts_to_postgres_scale_for_persistence_and_cross_source_matching() {
    let f = Fixture::new().await;
    let account = f.eb_account().await;
    let fetched = tx_data(
        Some("eb-rounded"),
        date(),
        Some(DESCRIPTION),
        dec("1.123456789"),
        Some("EUR"),
    );

    assert_eq!(
        f.import_for(&account, std::slice::from_ref(&fetched)).await,
        1
    );
    let rows = f.rows(&account).await;
    assert_eq!(rows[0].amount.to_string(), "1.12345679");
    assert_eq!(
        f.import_for(&account, std::slice::from_ref(&fetched)).await,
        0
    );

    let sidecar_account = f.eb_account().await;
    let mut sidecar = stored_row(&sidecar_account, date(), DESCRIPTION, "1.12345679");
    sidecar.external_id = Some("sidecar-id".to_owned());
    sidecar.external_transaction_id = Some("bourso:sidecar-id".to_owned());
    f.store(&sidecar).await;
    assert_eq!(f.import_for(&sidecar_account, &[fetched]).await, 0);
    assert_eq!(f.rows(&sidecar_account).await.len(), 1);
}

#[tokio::test]
async fn import_for_does_not_infer_overlap_from_date_and_amount_when_descriptions_differ() {
    let f = Fixture::new().await;
    let account = f.eb_account().await;
    f.store(&eb_stored(
        &account,
        "bourso:sidecar-transaction-1",
        Some("sidecar-transaction-1"),
        None,
    ))
    .await;

    let fetched = [data(Some("eb-transaction-1"), "Card payment")];
    assert_eq!(f.import_for(&account, &fetched).await, 1);
}

#[tokio::test]
async fn import_for_reserves_historical_ids_before_fingerprint_matching() {
    let f = Fixture::new().await;
    for on_external_id in [true, false] {
        let account = f.eb_account().await;
        let mut existing = NewTransaction::new(account.id, date(), DESCRIPTION, amount());
        if on_external_id {
            existing.external_id = Some("rev-1".to_owned());
        } else {
            existing.external_transaction_id = Some("rev-1".to_owned());
        }
        f.store(&existing).await;

        let fetched = [
            data(Some("rev-2"), DESCRIPTION),
            data(Some("rev-1"), DESCRIPTION),
        ];
        assert_eq!(f.import_for(&account, &fetched).await, 1);

        let rows = f.rows(&account).await;
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[1].external_transaction_id.as_deref(), Some("rev-2"));
    }
}

#[tokio::test]
async fn import_for_keeps_reused_reference_rows_and_deduplicates_exact_repeats_across_snapshots() {
    let f = Fixture::new().await;
    let account = f.eb_account().await;
    let mut original = stored_row(&account, date(), "Original description", "-3.00");
    original.external_transaction_id = Some("eb-reused-reference".to_owned());
    f.store(&original).await;
    let original_data = tx_data(
        Some("eb-reused-reference"),
        date(),
        Some("Original description"),
        dec("-3.00"),
        Some("EUR"),
    );
    let distinct_data = tx_data(
        Some("eb-reused-reference"),
        date(),
        Some("Distinct purchase"),
        dec("-3.50"),
        Some("EUR"),
    );

    assert_eq!(
        f.import_for(&account, &[distinct_data.clone(), original_data.clone()])
            .await,
        1
    );
    let rows = f.rows(&account).await;
    let inserted = &rows[1];
    assert_eq!(
        inserted.external_transaction_id.as_deref(),
        Some("eb-reused-reference")
    );
    assert_eq!(inserted.description, "Distinct purchase");
    assert_eq!(inserted.amount, dec("-3.50"));

    assert_eq!(
        f.import_for(&account, &[original_data, distinct_data])
            .await,
        0
    );
    assert_eq!(f.rows(&account).await.len(), 2);
}

#[tokio::test]
async fn import_for_skips_transactions_whose_effective_currency_is_not_eur() {
    let f = Fixture::new().await;
    for (account_currency, transaction_currency, expected) in [
        ("EUR", Some("GBP"), 0),
        ("GBP", None, 0),
        ("GBP", Some("EUR"), 1),
        ("EUR", Some("  "), 1),
    ] {
        let account = f
            .account(
                account_currency,
                AccountType::Checking,
                Some("eb-account-55"),
            )
            .await;
        let fetched = [tx_data(
            Some("currency-transaction"),
            date(),
            Some(DESCRIPTION),
            amount(),
            transaction_currency,
        )];

        assert_eq!(
            f.import_for(&account, &fetched).await,
            expected,
            "{account_currency}/{transaction_currency:?}"
        );

        let rows = f.rows(&account).await;
        assert_eq!(rows.len(), expected);
        assert!(rows.iter().all(|r| r.native_currency == "EUR"));
    }
}

#[tokio::test]
async fn import_for_bounds_description_and_hashes_oversized_external_id_deterministically() {
    let f = Fixture::new().await;
    let account = f.eb_account().await;
    let external_id = format!("eb-{}", "x".repeat(200));
    let description = "\u{1FA99}".repeat(256);
    let fetched = tx_data(
        Some(&external_id),
        date(),
        Some(&description),
        amount(),
        Some("EUR"),
    );

    assert_eq!(
        f.import_for(&account, std::slice::from_ref(&fetched)).await,
        1
    );

    let rows = f.rows(&account).await;
    let inserted = &rows[0];
    let stored_key = inserted.external_transaction_id.clone().unwrap();
    let expected_key = bank_import::dedup_key(&fetched);
    assert_eq!(inserted.description, "\u{1FA99}".repeat(255));
    assert_eq!(inserted.description.chars().count(), 255);
    assert!(stored_key.chars().count() <= 128);
    assert!(stored_key.starts_with("id:"));
    assert_eq!(stored_key, expected_key);
    assert_eq!(bank_import::dedup_key(&fetched), expected_key);

    assert_eq!(f.import_for(&account, &[fetched]).await, 0);
    assert_eq!(f.rows(&account).await.len(), 1);
}

async fn import_provided_as(account_type: AccountType) -> Vec<Transaction> {
    let f = Fixture::new().await;
    let account = f.account("USD", account_type, Some("sfin_X_1")).await;
    let fetched = [tx_data(
        Some("t1"),
        date(),
        Some("YOU BOUGHT ALPHABET INC"),
        dec("-1140.18"),
        Some("USD"),
    )];
    f.import_provided(&account, &fetched).await;
    f.rows(&account).await
}

#[tokio::test]
async fn import_provided_on_an_investment_account_keeps_the_rows_out_of_the_budget() {
    let rows = import_provided_as(AccountType::Brokerage).await;
    assert_eq!(rows.len(), 1);
    assert!(rows[0].budget_neutral);
}

#[tokio::test]
async fn import_provided_on_a_cash_account_leaves_the_rows_in_the_budget() {
    let rows = import_provided_as(AccountType::Checking).await;
    assert_eq!(rows.len(), 1);
    assert!(!rows[0].budget_neutral);
}

async fn import_for_as(account_type: AccountType) -> Vec<Transaction> {
    let f = Fixture::new().await;
    let account = f.account("EUR", account_type, Some("eb-account-57")).await;
    f.import_for(
        &account,
        &[data(Some("eb-transaction-57"), "Dividend payout")],
    )
    .await;
    f.rows(&account).await
}

#[tokio::test]
async fn import_for_on_an_investment_account_keeps_the_rows_out_of_the_budget() {
    let rows = import_for_as(AccountType::Brokerage).await;
    assert_eq!(rows.len(), 1);
    assert!(rows[0].budget_neutral);
}

#[tokio::test]
async fn import_for_on_a_cash_account_leaves_the_rows_in_the_budget() {
    let rows = import_for_as(AccountType::Checking).await;
    assert_eq!(rows.len(), 1);
    assert!(!rows[0].budget_neutral);
}

#[tokio::test]
async fn import_for_without_an_external_account_id_imports_nothing() {
    let f = Fixture::new().await;
    let account = f.account("EUR", AccountType::Checking, None).await;
    assert_eq!(
        f.import_for(&account, &[data(Some("eb-1"), DESCRIPTION)])
            .await,
        0
    );
    assert!(f.rows(&account).await.is_empty());
}

// ---- BankTransactionImportServiceLedgerTest ----------------------------------------------

#[tokio::test]
async fn import_provided_long_description_is_clipped_to_the_column() {
    let f = Fixture::new().await;
    for length in [255, 400] {
        let account = f.usd_account().await;
        f.import_provided(&account, &[row("tx-1", Some(&"x".repeat(length)))])
            .await;
        let rows = f.rows(&account).await;
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].description, "x".repeat(255));
        assert_eq!(rows[0].external_transaction_id.as_deref(), Some("tx-1"));
    }
}

#[tokio::test]
async fn import_provided_emoji_ending_exactly_on_the_limit_is_kept() {
    let f = Fixture::new().await;
    let account = f.usd_account().await;
    let description = format!("{}{EMOJI}", "x".repeat(253));

    f.import_provided(&account, &[row("tx-1", Some(&description))])
        .await;

    let rows = f.rows(&account).await;
    assert_eq!(rows[0].description, description);
    assert_eq!(rows[0].description.encode_utf16().count(), 255);
}

#[tokio::test]
async fn import_provided_emoji_straddling_the_limit_is_cut_on_a_code_point_boundary() {
    let f = Fixture::new().await;
    let account = f.usd_account().await;

    f.import_provided(
        &account,
        &[row(
            "tx-1",
            Some(&format!("{}{EMOJI}yyyy", "x".repeat(254))),
        )],
    )
    .await;

    assert_eq!(
        f.rows(&account).await[0].description,
        format!("{}{EMOJI}", "x".repeat(254))
    );
}

#[tokio::test]
async fn import_provided_null_description_is_stored_empty() {
    let f = Fixture::new().await;
    let account = f.usd_account().await;

    f.import_provided(&account, &[row("tx-1", None)]).await;

    assert_eq!(f.rows(&account).await[0].description, "");
}

#[tokio::test]
async fn import_provided_external_id_over255_is_hashed_with_the_stable_id_prefix() {
    let f = Fixture::new().await;
    let account = f.usd_account().await;

    f.import_provided(&account, &[row(&"t".repeat(300), Some("Coffee"))])
        .await;

    let key = f.rows(&account).await[0]
        .external_transaction_id
        .clone()
        .unwrap();
    assert!(key.starts_with("id:"));
    assert!(key.chars().count() <= 255);
}

#[tokio::test]
async fn import_provided_repeated_id_with_different_content_in_one_batch_keeps_both_rows() {
    let f = Fixture::new().await;
    let account = f.usd_account().await;

    f.import_provided(
        &account,
        &[
            row("tx-1", Some("Coffee")),
            row("tx-1", Some("Coffee again")),
        ],
    )
    .await;

    let descriptions: Vec<String> = f
        .rows(&account)
        .await
        .into_iter()
        .map(|r| r.description)
        .collect();
    assert_eq!(descriptions, ["Coffee", "Coffee again"]);
}

#[tokio::test]
async fn import_provided_amount_the_column_cannot_hold_is_skipped_and_the_others_stored() {
    let f = Fixture::new().await;
    let account = f.usd_account().await;
    let huge = tx_data(
        Some("huge"),
        day(),
        Some("Nope"),
        dec("1000000000000"),
        Some("USD"),
    );

    f.import_provided(&account, &[huge, row("tx-1", Some("Coffee"))])
        .await;

    let keys: Vec<Option<String>> = f
        .rows(&account)
        .await
        .into_iter()
        .map(|r| r.external_transaction_id)
        .collect();
    assert_eq!(keys, [Some("tx-1".to_owned())]);
}

#[tokio::test]
async fn import_provided_second_import_of_the_same_batch_inserts_nothing() {
    let f = Fixture::new().await;
    let account = f.usd_account().await;
    let batch = [row("tx-1", Some("Coffee"))];

    assert_eq!(f.import_provided(&account, &batch).await, 1);
    assert_eq!(f.import_provided(&account, &batch).await, 0);

    assert_eq!(f.rows(&account).await.len(), 1);
}

#[tokio::test]
async fn import_provided_same_id_on_two_accounts_is_stored_for_both() {
    let f = Fixture::new().await;
    let first_account = f.usd_account().await;
    let second_account = f.usd_account().await;

    let first = f
        .import_provided(&first_account, &[row("shared", Some("Transfer out"))])
        .await;
    let second = f
        .import_provided(&second_account, &[row("shared", Some("Transfer in"))])
        .await;

    assert_eq!((first, second), (1, 1));
    assert_eq!(f.rows(&first_account).await.len(), 1);
    assert_eq!(f.rows(&second_account).await.len(), 1);
}

#[tokio::test]
async fn import_provided_id_with_surrounding_spaces_matches_the_stored_row() {
    let f = Fixture::new().await;
    let account = f.usd_account().await;
    let mut stored = stored_row(&account, day(), "Coffee", "-4.50");
    stored.external_transaction_id = Some("tx-1".to_owned());
    f.store(&stored).await;

    assert_eq!(
        f.import_provided(&account, &[row(" tx-1 ", Some("Coffee"))])
            .await,
        0
    );
    assert_eq!(f.rows(&account).await.len(), 1);
}

#[tokio::test]
async fn import_provided_row_older_than_the_window_is_compared_against_stored_history() {
    let f = Fixture::new().await;
    let account = f.usd_account().await;
    let old = ledger_today() - chrono::Days::new(200);
    let mut stored = stored_row(&account, old, "Old", "-1.00");
    stored.external_transaction_id = Some("tx-old".to_owned());
    f.store(&stored).await;
    // The newest synced row is today, so the window starts a week back, after the old row.
    let mut latest = stored_row(&account, ledger_today(), "Latest", "-2.00");
    latest.external_transaction_id = Some("tx-latest".to_owned());
    f.store(&latest).await;

    let fetched = [tx_data(
        Some("tx-old"),
        old,
        Some("Old"),
        dec("-1.00"),
        Some("USD"),
    )];
    assert_eq!(f.import_provided(&account, &fetched).await, 0);
}

#[tokio::test]
async fn import_provided_no_id_and_amount_of_another_scale_matches_the_stored_row() {
    let f = Fixture::new().await;
    let account = f.usd_account().await;
    f.store(&stored_row(&account, day(), "Coffee", "12.34000000"))
        .await;

    let fetched = [tx_data(
        None,
        day(),
        Some(" Coffee "),
        dec("1.234e1"),
        Some("USD"),
    )];
    assert_eq!(f.import_provided(&account, &fetched).await, 0);
}

#[test]
fn dedup_key_amounts_equal_after_rounding_to8_decimals_share_the_fingerprint() {
    let exact = tx_data(None, day(), Some("Coffee"), dec("12.34"), Some("USD"));
    let rounds_to_same = tx_data(
        None,
        day(),
        Some("Coffee"),
        dec("12.340000001"),
        Some("USD"),
    );

    let key = bank_import::dedup_key(&rounds_to_same);
    assert!(key.starts_with("fp:"));
    assert_eq!(key, bank_import::dedup_key(&exact));
}

#[test]
fn dedup_key_amounts_differing_at_the8th_decimal_have_different_fingerprints() {
    let exact = tx_data(None, day(), Some("Coffee"), dec("12.34"), Some("USD"));
    let other = tx_data(None, day(), Some("Coffee"), dec("12.34000001"), Some("USD"));

    assert_ne!(
        bank_import::dedup_key(&other),
        bank_import::dedup_key(&exact)
    );
}

#[test]
fn dedup_key_negative_zero_shares_the_fingerprint_of_zero() {
    let zero = tx_data(None, day(), Some("Coffee"), dec("0"), Some("USD"));
    let negative_zero = tx_data(None, day(), Some("Coffee"), dec("-0.00"), Some("USD"));

    assert_eq!(
        bank_import::dedup_key(&negative_zero),
        bank_import::dedup_key(&zero)
    );
}

#[test]
fn fits_ledger_amount_inside_numeric20_8_is_accepted() {
    // Java's 1e-999999999 has no Decimal form; 1e-28 is the smallest positive Decimal.
    for amount in [
        "999999999999.99999999",
        "-999999999999.99999999",
        "-0.00",
        "1e-28",
    ] {
        assert!(bank_import::fits_ledger_amount(dec(amount)), "{amount}");
    }
}

#[test]
fn fits_ledger_amount_null_or_outside_numeric20_8_is_refused() {
    // Java's ±1e999999999 has no Decimal form; Decimal::MAX/MIN stand for them. A null amount
    // is an Option the importer filters before the check.
    for amount in [
        dec("1000000000000"),
        dec("-1000000000000"),
        Decimal::MAX,
        Decimal::MIN,
    ] {
        assert!(!bank_import::fits_ledger_amount(amount), "{amount}");
    }
}

// ---- acceptance criteria of the ingestion track -------------------------------------------

#[tokio::test]
async fn re_sync_of_the_same_transactions_inserts_nothing() {
    let f = Fixture::new().await;
    let account = f.eb_account().await;
    let fetched = [
        data(Some("eb-1"), "Coffee"),
        data(None, "Bakery"),
        data(None, "Bakery"),
        tx_data(Some("eb-2"), date(), Some("Train"), dec("-12.3"), None),
    ];

    assert_eq!(f.import_for(&account, &fetched).await, 4);
    let first = f.rows(&account).await;
    assert_eq!(f.import_for(&account, &fetched).await, 0);
    assert_eq!(f.rows(&account).await, first);
}

#[tokio::test]
async fn a_manual_category_survives_a_re_sync_unchanged() {
    let f = Fixture::new().await;
    let category = f.courses_category().await;
    let account = f.eb_account().await;
    let fetched = [data(Some("eb-1"), "Coffee"), data(None, "Bakery")];
    assert_eq!(f.import_for(&account, &fetched).await, 2);
    sqlx::query(
        "UPDATE \"transaction\" SET category_id = $1, category_manual = true WHERE account_id = $2",
    )
    .bind(category)
    .bind(account.id)
    .execute(&f.app.state.db)
    .await
    .unwrap();
    let categorized = f.rows(&account).await;

    let mut conn = f.app.state.db.begin().await.unwrap();
    assert_eq!(import_for_on(&mut conn, &account, &fetched).await, 0);
    conn.commit().await.unwrap();

    let after = f.rows(&account).await;
    assert_eq!(after, categorized);
    assert!(
        after
            .iter()
            .all(|r| r.category_id == Some(category) && r.category_manual)
    );
}
