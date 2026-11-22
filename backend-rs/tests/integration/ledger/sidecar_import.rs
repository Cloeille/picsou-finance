//! SharedSidecarTransactionImportServiceTest, on real rows.

use std::str::FromStr;
use std::sync::Arc;

use chrono::{DateTime, NaiveDate, Utc};
use picsou::db::AsDbHandle;
use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::categorize::{self, CategorizationContext};
use picsou::ledger::merchants::KnowledgeBase;
use picsou::ledger::model::{Account, AccountType, CategoryKind, NewTransaction, Transaction};
use picsou::ledger::sidecar::SidecarTransaction;
use picsou::ledger::sidecar_import;
use picsou::ledger::transactions;
use picsou::testing::{ADMIN_USERNAME, TestApp};
use regex::Regex;
use rust_decimal::Decimal;

fn date() -> NaiveDate {
    NaiveDate::from_ymd_opt(2026, 9, 20).unwrap()
}

fn now() -> DateTime<Utc> {
    picsou::testing::epoch()
}

fn dec(value: &str) -> Decimal {
    Decimal::from_str(value).unwrap()
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

    async fn account_in(&self, currency: &str, balance: &str) -> Account {
        let mut new = NewAccount::new(self.member_id, "Test account", AccountType::Checking);
        new.currency = currency.to_owned();
        new.current_balance = dec(balance);
        new.is_manual = false;
        accounts::insert(&self.app.state.db, &new, now())
            .await
            .unwrap()
    }

    async fn account(&self) -> Account {
        self.account_in("EUR", "0").await
    }

    async fn context(&self) -> CategorizationContext {
        let db = &self.app.state.db;
        let knowledge_base = Arc::new(KnowledgeBase::load(db).await.unwrap());
        categorize::load_context(db, knowledge_base, self.member_id, now())
            .await
            .unwrap()
    }

    async fn category(&self, slug: &str) -> i64 {
        categorize::categories_by_slug(&self.app.state.db, self.member_id, now())
            .await
            .unwrap()[slug]
            .id
    }

    async fn keyword_rule(&self, pattern: &str, category_id: i64) {
        picsou::db::query(
            "INSERT INTO categorization_rule (member_id, match_type, pattern, category_id, priority) \
             VALUES ($1, 'KEYWORD', $2, $3, 0)",
        )
        .bind(self.member_id)
        .bind(pattern)
        .bind(category_id)
        .execute(&self.app.state.db)
        .await
        .unwrap();
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

    async fn import(
        &self,
        account: &Account,
        rows: &[SidecarTransaction],
        ctx: &CategorizationContext,
        prefix: &str,
    ) -> usize {
        let mut conn = self.app.state.db.acquire().await.unwrap();
        sidecar_import::import(&mut conn.as_handle(), account, rows, ctx, prefix, now())
            .await
            .unwrap()
    }
}

fn sidecar(
    external_id: Option<&str>,
    description: &str,
    amount: &str,
    counterparty: Option<&str>,
    kind: Option<CategoryKind>,
) -> SidecarTransaction {
    SidecarTransaction {
        external_id: external_id.map(str::to_owned),
        date: Some(date()),
        description: Some(description.to_owned()),
        amount: Some(dec(amount)),
        counterparty: counterparty.map(str::to_owned),
        kind: kind.map(|k| k.as_str().to_owned()),
        currency: None,
    }
}

/// The Java test's transaction(..): counterparty "Merchant", the category kind as the row kind.
fn transaction(
    external_id: &str,
    description: &str,
    amount: &str,
    kind: CategoryKind,
) -> SidecarTransaction {
    sidecar(
        Some(external_id),
        description,
        amount,
        Some("Merchant"),
        Some(kind),
    )
}

fn legacy(
    account: &Account,
    description: &str,
    amount: &str,
    external_id: &str,
    transaction_id: Option<&str>,
) -> NewTransaction {
    let mut row = NewTransaction::new(account.id, date(), description, dec(amount));
    row.external_id = Some(external_id.to_owned());
    row.external_transaction_id = transaction_id.map(str::to_owned);
    row
}

fn external_ids(rows: &[Transaction]) -> Vec<&str> {
    rows.iter()
        .map(|r| r.external_id.as_deref().unwrap_or_default())
        .collect()
}

#[tokio::test]
async fn resync_with_stable_provider_id_imports_only_once() {
    let f = Fixture::new().await;
    let account = f.account().await;
    let ctx = f.context().await;
    let coffee = [transaction(
        "rev-1",
        "Coffee",
        "-3.50",
        CategoryKind::Expense,
    )];

    assert_eq!(f.import(&account, &coffee, &ctx, "bourso").await, 1);
    assert_eq!(f.import(&account, &coffee, &ctx, "bourso").await, 0);

    let rows = f.rows(&account).await;
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].external_id.as_deref(), Some("rev-1"));
    assert_eq!(
        rows[0].external_transaction_id.as_deref(),
        Some("bourso:rev-1")
    );
}

#[tokio::test]
async fn legacy_revolut_row_matching_snapshot_id_does_not_consume_replacement_regardless_of_snapshot_order()
 {
    let f = Fixture::new().await;
    let ctx = f.context().await;
    let old = transaction("rev-1", "Coffee", "-3.50", CategoryKind::Expense);
    let new = transaction("rev-2", "Coffee", "-3.50", CategoryKind::Expense);
    for snapshot in [[old.clone(), new.clone()], [new.clone(), old.clone()]] {
        let account = f.account().await;
        f.store(&legacy(&account, "Coffee", "-3.50", "rev-1", None))
            .await;

        assert_eq!(f.import(&account, &snapshot, &ctx, "revolut").await, 1);
        let rows = f.rows(&account).await;
        assert_eq!(external_ids(&rows), ["rev-1", "rev-2"]);
        assert_eq!(f.import(&account, &snapshot, &ctx, "revolut").await, 0);
        assert_eq!(f.rows(&account).await.len(), 2);
    }
}

#[tokio::test]
async fn normalizes_amounts_to_postgres_scale_for_persistence_and_stable_replay() {
    let f = Fixture::new().await;
    let account = f.account().await;
    let ctx = f.context().await;
    let fetched = [transaction(
        "rounded-ref",
        "Coffee",
        "1.123456789",
        CategoryKind::Expense,
    )];

    assert_eq!(f.import(&account, &fetched, &ctx, "bourso").await, 1);

    let rows = f.rows(&account).await;
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].amount.to_string(), "1.12345679");
    assert_eq!(f.import(&account, &fetched, &ctx, "bourso").await, 0);
    assert_eq!(f.rows(&account).await.len(), 1);
}

#[tokio::test]
async fn matches_legacy_rows_after_amount_and_description_persistence_normalization() {
    let f = Fixture::new().await;
    let account = f.account().await;
    let ctx = f.context().await;
    let coin = "\u{1FA99}";
    let stored = f
        .store(&legacy(
            &account,
            &coin.repeat(255),
            "1.12345679",
            "eb-rounded-reference",
            Some("eb-rounded-reference"),
        ))
        .await;
    let fetched = [SidecarTransaction {
        external_id: Some("bourso-rounded-reference".to_owned()),
        date: Some(date()),
        description: Some(coin.repeat(260)),
        amount: Some(dec("1.123456789")),
        counterparty: Some("Merchant".to_owned()),
        kind: Some("EXPENSE".to_owned()),
        currency: None,
    }];

    assert_eq!(f.import(&account, &fetched, &ctx, "bourso").await, 0);

    assert_eq!(f.rows(&account).await, [stored]);
}

#[tokio::test]
async fn truncates_provider_text_and_hashes_oversized_external_id() {
    let f = Fixture::new().await;
    let account = f.account().await;
    let ctx = f.context().await;
    let external_id = format!("rev-{}", "x".repeat(300));
    let row = sidecar(
        Some(&external_id),
        &"D".repeat(300),
        "-1.00",
        Some(&"C".repeat(300)),
        None,
    );

    assert_eq!(f.import(&account, &[row], &ctx, "revolut").await, 1);

    let imported = &f.rows(&account).await[0];
    assert_eq!(imported.description.chars().count(), 255);
    assert_eq!(
        imported.counterparty.as_deref().map(|c| c.chars().count()),
        Some(255)
    );
    assert!(
        Regex::new("^[0-9a-f]{64}$")
            .unwrap()
            .is_match(imported.external_id.as_deref().unwrap())
    );
    assert!(
        imported
            .external_transaction_id
            .as_deref()
            .unwrap()
            .encode_utf16()
            .count()
            <= 128
    );
}

#[tokio::test]
async fn skips_unconverted_foreign_currency_transactions_without_changing_account_balance() {
    let f = Fixture::new().await;
    let account = f.account_in("USD", "123.45").await;
    let ctx = f.context().await;
    let row = [transaction(
        "usd-1",
        "Coffee",
        "-3.50",
        CategoryKind::Expense,
    )];

    assert_eq!(f.import(&account, &row, &ctx, "revolut").await, 0);

    assert!(f.rows(&account).await.is_empty());
    let reloaded = accounts::find_by_id(&f.app.state.db, account.id)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(reloaded.current_balance, dec("123.45"));
    assert_eq!(reloaded.currency, "USD");
}

#[tokio::test]
async fn matches_legacy_enable_banking_row_by_strict_fingerprint_without_changing_its_category() {
    let f = Fixture::new().await;
    let account = f.account().await;
    let ctx = f.context().await;
    let mut row = legacy(
        &account,
        "  VIREMENT   INTERNE ",
        "-10.00000000",
        "eb-reference",
        Some("eb-reference"),
    );
    row.category_id = Some(f.category("courses").await);
    row.category_manual = true;
    let stored = f.store(&row).await;

    let fetched = [transaction(
        "bourso-ref",
        "Virement Interne",
        "-10",
        CategoryKind::Transfer,
    )];
    assert_eq!(f.import(&account, &fetched, &ctx, "bourso").await, 0);

    assert_eq!(f.rows(&account).await, [stored]);
}

#[tokio::test]
async fn retains_two_legitimate_identical_operations_with_different_provider_ids() {
    let f = Fixture::new().await;
    let account = f.account().await;
    let ctx = f.context().await;
    let fetched = [
        transaction("id-one", "Market", "-12.00", CategoryKind::Expense),
        transaction("id-two", "Market", "-12", CategoryKind::Expense),
    ];

    assert_eq!(f.import(&account, &fetched, &ctx, "bourso").await, 2);

    assert_eq!(external_ids(&f.rows(&account).await), ["id-one", "id-two"]);
}

#[tokio::test]
async fn legacy_overlap_is_consumed_once_and_does_not_collapse_an_additional_identical_operation() {
    let f = Fixture::new().await;
    let account = f.account().await;
    let ctx = f.context().await;
    f.store(&legacy(
        &account,
        "Rent",
        "-100.00",
        "eb-row",
        Some("eb-row"),
    ))
    .await;
    let fetched = [
        transaction("new-id-one", "rent", "-100", CategoryKind::Expense),
        transaction("new-id-two", " Rent ", "-100.00", CategoryKind::Expense),
    ];

    assert_eq!(f.import(&account, &fetched, &ctx, "bourso").await, 1);
    assert_eq!(f.rows(&account).await.len(), 2);
    assert_eq!(f.import(&account, &fetched, &ctx, "bourso").await, 0);
    assert_eq!(f.rows(&account).await.len(), 2);
}

#[tokio::test]
async fn transfer_kind_wins_over_an_expense_rule() {
    let f = Fixture::new().await;
    let account = f.account().await;
    f.keyword_rule("transfer", f.category("courses").await)
        .await;
    let ctx = f.context().await;
    let transfer = f.category("virement-interne").await;

    let fetched = [transaction(
        "transfer-leg",
        "Transfer",
        "-30",
        CategoryKind::Transfer,
    )];
    assert_eq!(f.import(&account, &fetched, &ctx, "revolut").await, 1);

    assert_eq!(f.rows(&account).await[0].category_id, Some(transfer));
}

#[tokio::test]
async fn ordinary_current_expense_is_persisted_with_expense_category_for_budget_reads() {
    let f = Fixture::new().await;
    let account = f.account().await;
    let groceries = f.category("courses").await;
    f.keyword_rule("market", groceries).await;
    let ctx = f.context().await;

    let fetched = [sidecar(
        Some("grocery-1"),
        "Market",
        "-12.50",
        None,
        Some(CategoryKind::Transfer),
    )];
    assert_eq!(f.import(&account, &fetched, &ctx, "bourso").await, 1);

    let stored = &f.rows(&account).await[0];
    assert!(!stored.is_manual);
    assert_eq!(stored.category_id, Some(groceries));
    assert_eq!(stored.date, date());
    assert_eq!(stored.amount, dec("-12.50"));
}

#[tokio::test]
async fn stores_the_legs_own_currency_and_skips_a_foreign_leg_on_an_eur_account() {
    let f = Fixture::new().await;
    let account = f.account().await;
    let ctx = f.context().await;
    let mut exchanged_in = sidecar(Some("exchange-1"), "Exchanged from BTC", "4.80", None, None);
    exchanged_in.currency = Some("eur".to_owned());
    // A distinct id, so only the currency check (not batch dedup) can keep it out.
    let mut foreign_leg = sidecar(
        Some("exchange-2"),
        "Exchanged to EUR",
        "-0.00005",
        None,
        None,
    );
    foreign_leg.currency = Some("BTC".to_owned());
    let untagged = transaction("card-1", "Coffee", "-3.50", CategoryKind::Expense);

    assert_eq!(
        f.import(
            &account,
            &[exchanged_in, foreign_leg, untagged],
            &ctx,
            "revolut"
        )
        .await,
        2
    );

    let rows = f.rows(&account).await;
    let descriptions: Vec<&str> = rows.iter().map(|r| r.description.as_str()).collect();
    assert_eq!(descriptions, ["Exchanged from BTC", "Coffee"]);
    assert!(rows.iter().all(|r| r.native_currency == "EUR"));
}

#[tokio::test]
async fn identical_fingerprint_on_another_account_is_not_a_overlap_match() {
    let f = Fixture::new().await;
    let first = f.account().await;
    let second = f.account().await;
    let ctx = f.context().await;
    let same = [transaction(
        "provider-ref",
        "Rent",
        "-100",
        CategoryKind::Expense,
    )];

    assert_eq!(f.import(&first, &same, &ctx, "bourso").await, 1);
    assert_eq!(f.import(&second, &same, &ctx, "bourso").await, 1);
    assert_eq!(f.rows(&first).await.len() + f.rows(&second).await.len(), 2);
}

// ---- acceptance criteria of the ingestion track -------------------------------------------

#[tokio::test]
async fn re_sync_of_the_same_transactions_inserts_nothing() {
    let f = Fixture::new().await;
    let account = f.account().await;
    let ctx = f.context().await;
    let fetched = [
        transaction("rev-1", "Coffee", "-3.50", CategoryKind::Expense),
        sidecar(None, "Bakery", "-2.10", None, None),
        sidecar(None, "Bakery", "-2.10", None, None),
    ];

    assert_eq!(f.import(&account, &fetched, &ctx, "revolut").await, 3);
    let first = f.rows(&account).await;
    assert!(
        first[1]
            .external_transaction_id
            .as_deref()
            .unwrap()
            .ends_with(":0")
    );
    assert!(
        first[2]
            .external_transaction_id
            .as_deref()
            .unwrap()
            .ends_with(":1")
    );
    assert_eq!(f.import(&account, &fetched, &ctx, "revolut").await, 0);
    assert_eq!(f.rows(&account).await, first);
}

#[tokio::test]
async fn a_manual_category_survives_a_re_sync_unchanged() {
    let f = Fixture::new().await;
    let account = f.account().await;
    let category = f.category("loisirs").await;
    let ctx = f.context().await;
    let fetched = [
        transaction("rev-1", "Coffee", "-3.50", CategoryKind::Expense),
        sidecar(None, "Bakery", "-2.10", None, None),
    ];
    assert_eq!(f.import(&account, &fetched, &ctx, "revolut").await, 2);
    picsou::db::query(
        "UPDATE \"transaction\" SET category_id = $1, category_manual = true WHERE account_id = $2",
    )
    .bind(category)
    .bind(account.id)
    .execute(&f.app.state.db)
    .await
    .unwrap();
    let categorized = f.rows(&account).await;

    let mut conn = f.app.state.db.begin().await.unwrap();
    let inserted = sidecar_import::import(
        &mut conn.as_handle(),
        &account,
        &fetched,
        &ctx,
        "revolut",
        now(),
    )
    .await
    .unwrap();
    conn.commit().await.unwrap();

    assert_eq!(inserted, 0);
    let after = f.rows(&account).await;
    assert_eq!(after, categorized);
    assert!(
        after
            .iter()
            .all(|r| r.category_id == Some(category) && r.category_manual)
    );
}
