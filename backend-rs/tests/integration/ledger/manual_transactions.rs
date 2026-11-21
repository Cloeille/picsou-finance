use std::sync::Arc;

use chrono::NaiveDate;
use picsou::error::AppError;
use picsou::http::json::{Errors, Lang, Validate};
use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::categorize::{self, CategorizationContext};
use picsou::ledger::holdings;
use picsou::ledger::instrument::TickerResult;
use picsou::ledger::manual_transactions::{
    ManualEntryContext, TransactionRequest, TransactionResponse, add_transaction,
    delete_transaction, update_transaction,
};
use picsou::ledger::merchants::KnowledgeBase;
use picsou::ledger::model::{
    Account, AccountType, NewTransaction, SnapshotSource, TransactionType,
};
use picsou::ledger::snapshots;
use picsou::ledger::transactions;
use picsou::testing::TestApp;
use rust_decimal::Decimal;
use serde_json::json;

use super::manual_ledger::{FakePrices, d, days_ago, now, statement, today};

async fn never_called(isin: &str) -> TickerResult {
    panic!("OpenFIGI must not be called for {isin}")
}

struct Fixture {
    app: TestApp,
    member: i64,
    prices: FakePrices,
}

impl Fixture {
    async fn new() -> Self {
        let app = TestApp::with_admin().await;
        let (_, member) = app.create_member("alice", "pw-alice-123").await;
        Self {
            app,
            member,
            prices: FakePrices::default(),
        }
    }

    fn ctx(&self) -> ManualEntryContext<'_> {
        ManualEntryContext {
            prices: &self.prices,
            today: today(),
            now: now(),
        }
    }

    async fn categorization(&self) -> CategorizationContext {
        categorize::load_context(
            &self.app.state.db,
            Arc::new(KnowledgeBase::default()),
            self.member,
            now(),
        )
        .await
        .unwrap()
    }

    async fn account(&self, account_type: AccountType, balance: &str, manual: bool) -> Account {
        let mut account = NewAccount::new(self.member, "Account", account_type);
        account.current_balance = d(balance);
        account.is_manual = manual;
        accounts::insert(&self.app.state.db, &account, now())
            .await
            .unwrap()
    }

    async fn reload(&self, account: &Account) -> Account {
        accounts::find_by_id(&self.app.state.db, account.id)
            .await
            .unwrap()
            .unwrap()
    }

    async fn add(
        &self,
        account_id: i64,
        req: &TransactionRequest,
    ) -> Result<TransactionResponse, AppError> {
        self.add_as(account_id, self.member, req).await
    }

    async fn add_as(
        &self,
        account_id: i64,
        member: i64,
        req: &TransactionRequest,
    ) -> Result<TransactionResponse, AppError> {
        let categorization = self.categorization().await;
        let mut conn = self.app.state.db.acquire().await.unwrap();
        add_transaction(
            &mut conn,
            self.ctx(),
            &categorization,
            account_id,
            member,
            req,
            never_called,
        )
        .await
    }

    async fn update(
        &self,
        account_id: i64,
        tx_id: i64,
        req: &TransactionRequest,
    ) -> Result<TransactionResponse, AppError> {
        let mut conn = self.app.state.db.acquire().await.unwrap();
        update_transaction(
            &mut conn,
            self.ctx(),
            account_id,
            tx_id,
            self.member,
            req,
            never_called,
        )
        .await
    }

    async fn delete(&self, account_id: i64, tx_id: i64) -> Result<(), AppError> {
        let mut conn = self.app.state.db.acquire().await.unwrap();
        delete_transaction(&mut conn, self.ctx(), account_id, tx_id, self.member).await
    }

    /// A row booked straight into the ledger: manual, or as a bank sync writes it.
    async fn booked(&self, account_id: i64, amount: &str, manual: bool) -> i64 {
        let mut tx = NewTransaction::new(account_id, days_ago(5), "Booked", d(amount));
        tx.is_manual = manual;
        transactions::insert(&self.app.state.db, &tx, now())
            .await
            .unwrap()
            .id
    }

    async fn snapshot_count(&self, account_id: i64) -> usize {
        snapshots::find_by_account(&self.app.state.db, account_id)
            .await
            .unwrap()
            .len()
    }
}

fn request(
    date: NaiveDate,
    description: &str,
    amount: &str,
    tx_type: TransactionType,
) -> TransactionRequest {
    TransactionRequest {
        date: Some(date),
        description: Some(description.to_owned()),
        amount: Some(d(amount)),
        tx_type: Some(tx_type),
        currency: Some("EUR".to_owned()),
        ..TransactionRequest::default()
    }
}

fn trade(ticker: &str, name: Option<&str>) -> TransactionRequest {
    TransactionRequest {
        ticker: Some(ticker.to_owned()),
        name: name.map(str::to_owned),
        quantity: Some(d("5")),
        price_per_unit: Some(d("100")),
        ..request(days_ago(5), "Buy", "-500", TransactionType::Buy)
    }
}

fn day(y: i32, m: u32, dd: u32) -> NaiveDate {
    NaiveDate::from_ymd_opt(y, m, dd).unwrap()
}

#[tokio::test]
async fn add_transaction_manual_cash_account_recomputes_balance_and_snapshots() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Checking, "0", true).await;
    let req = request(day(2026, 1, 15), "Salary", "1000", TransactionType::Deposit);

    let result = f.add(account.id, &req).await.unwrap();

    assert_eq!(result.amount, d("1000"));
    assert!(result.is_manual);
    assert_eq!(result.native_currency, "EUR");
    assert_eq!(f.reload(&account).await.current_balance, d("1000"));
    let history = snapshots::find_by_account(&f.app.state.db, account.id)
        .await
        .unwrap();
    let points: Vec<(NaiveDate, Decimal)> = history.iter().map(|s| (s.date, s.balance)).collect();
    assert_eq!(
        points,
        [(day(2026, 1, 15), d("1000")), (today(), d("1000"))]
    );
    assert!(
        holdings::find_by_account(&f.app.state.db, account.id)
            .await
            .unwrap()
            .is_empty()
    );
}

#[tokio::test]
async fn add_transaction_with_month_end_statements_statements_survive_and_balance_is_anchored() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Checking, "1200", true).await;
    statement(
        &f.app,
        account.id,
        days_ago(10),
        "1200",
        Some("1200"),
        now(),
        None,
    )
    .await;

    f.add(
        account.id,
        &request(
            days_ago(3),
            "History top-up",
            "50",
            TransactionType::Deposit,
        ),
    )
    .await
    .unwrap();

    // Anchored on the statement, not the transaction sum...
    assert_eq!(f.reload(&account).await.current_balance, d("1250"));
    // ...and the statement row is never deleted.
    let history = snapshots::find_by_account(&f.app.state.db, account.id)
        .await
        .unwrap();
    let rows: Vec<(NaiveDate, Decimal, SnapshotSource)> = history
        .iter()
        .map(|s| (s.date, s.balance, s.source))
        .collect();
    assert_eq!(
        rows,
        [
            (days_ago(10), d("1200"), SnapshotSource::Manual),
            (days_ago(3), d("1250"), SnapshotSource::Derived),
            (today(), d("1250"), SnapshotSource::Derived),
        ]
    );
}

#[tokio::test]
async fn add_transaction_valued_account_keeps_its_value_and_snapshots() {
    let f = Fixture::new().await;
    for account_type in [
        AccountType::Scpi,
        AccountType::RealEstate,
        AccountType::EmployeeSavings,
    ] {
        let account = f.account(account_type, "1000", true).await;
        let req = request(
            day(2026, 10, 31),
            "Dividend",
            "6.67",
            TransactionType::Dividend,
        );

        let result = f.add(account.id, &req).await.unwrap();

        assert_eq!(result.amount.to_string(), "6.67", "{account_type}");
        assert_eq!(
            f.reload(&account).await.current_balance,
            d("1000"),
            "{account_type}"
        );
        assert_eq!(f.snapshot_count(account.id).await, 0, "{account_type}");
    }
}

#[tokio::test]
async fn update_transaction_valued_account_keeps_its_value() {
    let f = Fixture::new().await;
    for account_type in [
        AccountType::Scpi,
        AccountType::RealEstate,
        AccountType::EmployeeSavings,
    ] {
        let account = f.account(account_type, "1000", true).await;
        let tx = f.booked(account.id, "6.67", true).await;
        let req = request(
            day(2026, 10, 31),
            "Dividend, corrected",
            "6.70",
            TransactionType::Dividend,
        );

        let result = f.update(account.id, tx, &req).await.unwrap();

        assert_eq!(result.amount.to_string(), "6.70");
        assert_eq!(result.description, "Dividend, corrected");
        assert_eq!(f.reload(&account).await.current_balance, d("1000"));
        assert_eq!(f.snapshot_count(account.id).await, 0);
    }
}

#[tokio::test]
async fn delete_transaction_valued_account_keeps_its_value() {
    let f = Fixture::new().await;
    for account_type in [
        AccountType::Scpi,
        AccountType::RealEstate,
        AccountType::EmployeeSavings,
    ] {
        let account = f.account(account_type, "1000", true).await;
        let tx = f.booked(account.id, "6.67", true).await;

        f.delete(account.id, tx).await.unwrap();

        // Removing the last transaction must not zero the account.
        assert!(
            transactions::find_by_id_and_account(&f.app.state.db, tx, account.id)
                .await
                .unwrap()
                .is_none()
        );
        assert_eq!(f.reload(&account).await.current_balance, d("1000"));
        assert_eq!(f.snapshot_count(account.id).await, 0);
    }
}

#[tokio::test]
async fn add_transaction_synced_cash_account_saves_transaction_but_leaves_balance_and_snapshots() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Checking, "2500", false).await;
    let req = request(
        day(2026, 5, 20),
        "Cash expense noted by hand",
        "-10",
        TransactionType::Withdrawal,
    );

    let result = f.add(account.id, &req).await.unwrap();

    assert_eq!(result.amount, d("-10"));
    assert!(
        transactions::find_by_id_and_account(&f.app.state.db, result.id, account.id)
            .await
            .unwrap()
            .is_some()
    );
    assert_eq!(f.reload(&account).await.current_balance, d("2500"));
    assert_eq!(f.snapshot_count(account.id).await, 0);
}

#[tokio::test]
async fn update_transaction_synced_account_does_not_rewrite_balance_or_snapshots() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Checking, "2500", false).await;
    let tx = f.booked(account.id, "-10", true).await;
    let req = request(
        day(2026, 5, 22),
        "Corrected annotation",
        "-15",
        TransactionType::Withdrawal,
    );

    let result = f.update(account.id, tx, &req).await.unwrap();

    assert_eq!(result.amount, d("-15"));
    assert_eq!(result.date, day(2026, 5, 22));
    assert_eq!(f.reload(&account).await.current_balance, d("2500"));
    assert_eq!(f.snapshot_count(account.id).await, 0);
}

#[tokio::test]
async fn delete_transaction_synced_account_does_not_reconstruct() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Checking, "2500", false).await;
    let tx = f.booked(account.id, "-10", true).await;

    f.delete(account.id, tx).await.unwrap();

    assert_eq!(f.reload(&account).await.current_balance, d("2500"));
    assert_eq!(f.snapshot_count(account.id).await, 0);
}

async fn some_category(f: &Fixture) -> picsou::ledger::model::Category {
    categorize::ensure_seeded(&f.app.state.db, f.member, now())
        .await
        .unwrap();
    categorize::categories_of_member(&f.app.state.db, f.member, false)
        .await
        .unwrap()
        .remove(0)
}

#[tokio::test]
async fn add_transaction_explicit_category_is_marked_manual_so_automatic_passes_keep_it() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Checking, "2500", false).await;
    let chosen = some_category(&f).await;
    let req = TransactionRequest {
        category_id: Some(chosen.id),
        ..request(
            day(2026, 10, 10),
            "DIVIDENDE TOTALENERGIES",
            "59.50",
            TransactionType::Deposit,
        )
    };

    let result = f.add(account.id, &req).await.unwrap();

    assert_eq!(result.category_id, Some(chosen.id));
    assert_eq!(result.category_name.as_deref(), Some(chosen.name.as_str()));
    let saved = transactions::find_by_id_and_account(&f.app.state.db, result.id, account.id)
        .await
        .unwrap()
        .unwrap();
    assert!(saved.category_manual);
    // The clean label is stamped either way.
    assert_eq!(
        saved.merchant_label.as_deref(),
        result.merchant_label.as_deref()
    );
    assert!(saved.merchant_label.is_some());
}

#[tokio::test]
async fn add_transaction_no_category_leaves_the_automatic_pipeline_in_charge() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Checking, "2500", false).await;
    let category = some_category(&f).await;
    sqlx::query(
        "INSERT INTO categorization_rule (member_id, match_type, pattern, category_id, priority, source, created_at) \
         VALUES ($1, 'KEYWORD', 'totalenergies', $2, 0, 'USER', $3)",
    )
    .bind(f.member)
    .bind(category.id)
    .bind(now())
    .execute(&f.app.state.db)
    .await
    .unwrap();
    let req = request(
        day(2026, 10, 10),
        "DIVIDENDE TOTALENERGIES",
        "59.50",
        TransactionType::Deposit,
    );

    let result = f.add(account.id, &req).await.unwrap();

    let saved = transactions::find_by_id_and_account(&f.app.state.db, result.id, account.id)
        .await
        .unwrap()
        .unwrap();
    assert!(!saved.category_manual);
    assert_eq!(saved.category_id, Some(category.id));
    assert_eq!(
        result.category_name.as_deref(),
        Some(category.name.as_str())
    );
}

#[tokio::test]
async fn update_transaction_explicit_category_is_marked_manual() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Checking, "2500", false).await;
    let tx = f.booked(account.id, "-10", true).await;
    let chosen = some_category(&f).await;
    let req = TransactionRequest {
        category_id: Some(chosen.id),
        ..request(
            day(2026, 10, 10),
            "DIVIDENDE TOTALENERGIES",
            "59.50",
            TransactionType::Deposit,
        )
    };

    f.update(account.id, tx, &req).await.unwrap();

    let saved = transactions::find_by_id_and_account(&f.app.state.db, tx, account.id)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(saved.category_id, Some(chosen.id));
    assert!(saved.category_manual);
}

#[tokio::test]
async fn add_transaction_category_of_another_member_is_not_found() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Checking, "0", true).await;
    let (_, other) = f.app.create_member("bob", "pw-bob-12345").await;
    categorize::ensure_seeded(&f.app.state.db, other, now())
        .await
        .unwrap();
    let theirs = categorize::categories_of_member(&f.app.state.db, other, false)
        .await
        .unwrap()
        .remove(0);
    let req = TransactionRequest {
        category_id: Some(theirs.id),
        ..request(day(2026, 1, 15), "Salary", "1000", TransactionType::Deposit)
    };

    let err = f.add(account.id, &req).await.unwrap_err();

    assert!(
        matches!(err, AppError::NotFound(ref m) if m == "Category not found"),
        "{err:?}"
    );
    assert!(
        transactions::find_by_account_newest_first(&f.app.state.db, account.id)
            .await
            .unwrap()
            .is_empty()
    );
}

#[tokio::test]
async fn add_transaction_investment_account_recomputes_holdings() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Pea, "0", true).await;

    let result = f.add(account.id, &trade("AAPL", None)).await.unwrap();

    assert_eq!(result.ticker.as_deref(), Some("AAPL"));
    let held = holdings::find_by_account(&f.app.state.db, account.id)
        .await
        .unwrap();
    assert_eq!(held.len(), 1);
    assert_eq!(held[0].ticker, "AAPL");
    assert_eq!(held[0].quantity, d("5"));
    // Neither the balance nor the history is rebuilt for an investment account.
    assert_eq!(f.reload(&account).await.current_balance, d("0"));
    assert_eq!(f.snapshot_count(account.id).await, 0);
}

#[tokio::test]
async fn add_transaction_synced_investment_account_leaves_provider_holdings_untouched() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::CompteTitres, "0", false).await;

    f.add(account.id, &trade("AAPL", None)).await.unwrap();

    assert!(
        holdings::find_by_account(&f.app.state.db, account.id)
            .await
            .unwrap()
            .is_empty()
    );
}

#[tokio::test]
async fn add_transaction_account_not_found_throws() {
    let f = Fixture::new().await;
    let req = request(today(), "Test", "10", TransactionType::Deposit);
    let err = f.add(99, &req).await.unwrap_err();
    assert!(
        matches!(err, AppError::NotFound(ref m) if m == "Account not found"),
        "{err:?}"
    );
}

#[tokio::test]
async fn every_write_refuses_another_members_account() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Checking, "0", true).await;
    let tx = f.booked(account.id, "200", true).await;
    let (_, other) = f.app.create_member("bob", "pw-bob-12345").await;
    let req = request(today(), "Test", "10", TransactionType::Deposit);

    let added = f.add_as(account.id, other, &req).await.unwrap_err();
    let mut conn = f.app.state.db.acquire().await.unwrap();
    let updated = update_transaction(
        &mut conn,
        f.ctx(),
        account.id,
        tx,
        other,
        &req,
        never_called,
    )
    .await
    .unwrap_err();
    let deleted = delete_transaction(&mut conn, f.ctx(), account.id, tx, other)
        .await
        .unwrap_err();

    for err in [added, updated, deleted] {
        assert!(
            matches!(err, AppError::NotFound(ref m) if m == "Account not found"),
            "{err:?}"
        );
    }
    let rows = transactions::find_by_account_newest_first(&f.app.state.db, account.id)
        .await
        .unwrap();
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].amount, d("200"));
}

#[tokio::test]
async fn delete_transaction_manual_deletes_and_recomputes() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Checking, "200", true).await;
    let tx = f.booked(account.id, "200", true).await;

    f.delete(account.id, tx).await.unwrap();

    assert_eq!(f.reload(&account).await.current_balance, d("0"));
    let history = snapshots::find_by_account(&f.app.state.db, account.id)
        .await
        .unwrap();
    let points: Vec<(NaiveDate, Decimal)> = history.iter().map(|s| (s.date, s.balance)).collect();
    assert_eq!(points, [(today(), d("0"))]);
}

#[tokio::test]
async fn delete_transaction_synced_transaction_throws() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Checking, "0", true).await;
    let tx = f.booked(account.id, "300", false).await;

    let err = f.delete(account.id, tx).await.unwrap_err();

    assert!(
        matches!(err, AppError::BadRequest(ref m) if m == "Cannot delete a synced transaction"),
        "{err:?}"
    );
    assert!(
        transactions::find_by_id_and_account(&f.app.state.db, tx, account.id)
            .await
            .unwrap()
            .is_some()
    );
}

#[tokio::test]
async fn update_transaction_synced_transaction_throws() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Checking, "0", true).await;
    let tx = f.booked(account.id, "300", false).await;
    let req = request(today(), "Edited", "1", TransactionType::Deposit);

    let err = f.update(account.id, tx, &req).await.unwrap_err();

    assert!(
        matches!(err, AppError::BadRequest(ref m) if m == "Cannot edit a synced transaction"),
        "{err:?}"
    );
}

#[tokio::test]
async fn delete_transaction_transaction_not_found_throws() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Checking, "0", true).await;
    let other = f.account(AccountType::Checking, "0", true).await;
    let elsewhere = f.booked(other.id, "5", true).await;

    for tx in [99_999, elsewhere] {
        let err = f.delete(account.id, tx).await.unwrap_err();
        assert!(
            matches!(err, AppError::NotFound(ref m) if m == "Transaction not found"),
            "{err:?}"
        );
    }
}

#[tokio::test]
async fn add_transaction_isin_input_resolves_ticker_name_and_description() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Pea, "0", true).await;
    let req = TransactionRequest {
        description: Some("Achat IE00B4L5Y983".to_owned()),
        ..trade("IE00B4L5Y983", None)
    };
    let categorization = f.categorization().await;
    let mut conn = f.app.state.db.acquire().await.unwrap();

    let result = add_transaction(
        &mut conn,
        f.ctx(),
        &categorization,
        account.id,
        f.member,
        &req,
        async |isin: &str| {
            assert_eq!(isin, "IE00B4L5Y983");
            TickerResult {
                ticker: "IWDA.AS".to_owned(),
                name: Some("iShares Core MSCI World UCITS ETF".to_owned()),
            }
        },
    )
    .await
    .unwrap();

    assert_eq!(result.ticker.as_deref(), Some("IWDA.AS"));
    assert_eq!(
        result.name.as_deref(),
        Some("iShares Core MSCI World UCITS ETF")
    );
    // The raw ISIN never surfaces in the row.
    assert_eq!(result.description, "iShares Core MSCI World UCITS ETF");
    let held = holdings::find_by_account(&f.app.state.db, account.id)
        .await
        .unwrap();
    assert_eq!(held[0].ticker, "IWDA.AS");
    assert_eq!(
        held[0].name.as_deref(),
        Some("iShares Core MSCI World UCITS ETF")
    );
}

#[tokio::test]
async fn add_transaction_investment_account_persists_fees() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Pea, "0", true).await;
    let req = TransactionRequest {
        fees: Some(d("1.50")),
        quantity: Some(d("10")),
        ..trade("AAPL", None)
    };

    let result = f.add(account.id, &req).await.unwrap();

    assert_eq!(result.tx_type, Some(TransactionType::Buy));
    assert_eq!(result.description, "AAPL");
    assert_eq!(result.fees.unwrap().to_string(), "1.50");
    // (10 × 100 + 1.50) / 10
    let held = holdings::find_by_account(&f.app.state.db, account.id)
        .await
        .unwrap();
    assert_eq!(held[0].average_buy_in.unwrap().to_string(), "100.15000000");
}

#[tokio::test]
async fn add_transaction_negative_fees_throws() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Pea, "0", true).await;
    let req = TransactionRequest {
        fees: Some(d("-1")),
        ..trade("AAPL", None)
    };

    let err = f.add(account.id, &req).await.unwrap_err();

    assert!(
        matches!(err, AppError::BadRequest(ref m) if m == "Fees cannot be negative"),
        "{err:?}"
    );
    assert!(
        transactions::find_by_account_newest_first(&f.app.state.db, account.id)
            .await
            .unwrap()
            .is_empty()
    );
}

#[tokio::test]
async fn add_transaction_plain_ticker_uppercases_and_user_name_wins() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Pea, "0", true).await;
    let req = TransactionRequest {
        description: Some("ignored placeholder".to_owned()),
        ..trade("iwda.as", Some("My World ETF"))
    };

    let result = f.add(account.id, &req).await.unwrap();

    assert_eq!(result.ticker.as_deref(), Some("IWDA.AS"));
    assert_eq!(result.name.as_deref(), Some("My World ETF"));
    assert_eq!(result.description, "My World ETF");
}

#[tokio::test]
async fn add_transaction_metals_account_stores_the_canonical_catalogue_ticker() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::PreciousMetals, "0", true).await;
    let result = f
        .add(account.id, &trade(" gold:napoleon-20f ", None))
        .await
        .unwrap();
    assert_eq!(result.ticker.as_deref(), Some("GOLD:NAPOLEON-20F"));
}

#[tokio::test]
async fn add_transaction_metals_account_rejects_a_ticker_outside_the_catalogue() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::PreciousMetals, "0", true).await;

    let err = f.add(account.id, &trade("AAPL", None)).await.unwrap_err();

    assert!(
        matches!(err, AppError::BadRequest(ref m) if m == "'AAPL' is not a precious metals product"),
        "{err:?}"
    );
    assert!(
        transactions::find_by_account_newest_first(&f.app.state.db, account.id)
            .await
            .unwrap()
            .is_empty()
    );
    assert!(
        holdings::find_by_account(&f.app.state.db, account.id)
            .await
            .unwrap()
            .is_empty()
    );
}

async fn metals_buy(f: &Fixture, account_id: i64) -> i64 {
    let mut tx = NewTransaction::new(account_id, days_ago(5), "Buy", d("-1420"));
    tx.is_manual = true;
    tx.tx_type = Some(TransactionType::Buy);
    tx.ticker = Some("GOLD:GRAM".to_owned());
    transactions::insert(&f.app.state.db, &tx, now())
        .await
        .unwrap()
        .id
}

#[tokio::test]
async fn update_transaction_metals_account_rejects_a_ticker_outside_the_catalogue() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::PreciousMetals, "0", true).await;
    let tx = metals_buy(&f, account.id).await;

    assert!(matches!(
        f.update(account.id, tx, &trade("GOLD:UNKNOWN", None)).await,
        Err(AppError::BadRequest(_))
    ));

    let stored = transactions::find_by_id_and_account(&f.app.state.db, tx, account.id)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(stored.ticker.as_deref(), Some("GOLD:GRAM"));
}

#[tokio::test]
async fn update_transaction_metals_account_normalizes_the_ticker() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::PreciousMetals, "0", true).await;
    let tx = metals_buy(&f, account.id).await;

    f.update(account.id, tx, &trade("gold:krugerrand", None))
        .await
        .unwrap();

    let stored = transactions::find_by_id_and_account(&f.app.state.db, tx, account.id)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(stored.ticker.as_deref(), Some("GOLD:KRUGERRAND"));
    let held = holdings::find_by_account(&f.app.state.db, account.id)
        .await
        .unwrap();
    assert_eq!(held.len(), 1);
    assert_eq!(held[0].ticker, "GOLD:KRUGERRAND");
}

#[tokio::test]
async fn add_transaction_securities_account_still_takes_any_ticker() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Pea, "0", true).await;
    let result = f.add(account.id, &trade("aapl", None)).await.unwrap();
    assert_eq!(result.ticker.as_deref(), Some("AAPL"));
}

#[tokio::test]
async fn update_transaction_keeps_stored_trade_fields_the_request_leaves_null() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::CompteTitres, "0", false).await;
    let added = f
        .add(
            account.id,
            &TransactionRequest {
                fees: Some(d("2")),
                currency: Some("USD".to_owned()),
                ..trade("AAPL", None)
            },
        )
        .await
        .unwrap();
    let req = TransactionRequest {
        currency: None,
        ..request(days_ago(4), "Edited", "-600", TransactionType::Buy)
    };

    let result = f.update(account.id, added.id, &req).await.unwrap();

    assert_eq!(result.ticker.as_deref(), Some("AAPL"));
    assert_eq!(result.quantity, Some(d("5")));
    assert_eq!(result.price_per_unit, Some(d("100")));
    assert_eq!(result.fees, Some(d("2")));
    assert_eq!(result.native_currency, "USD");
    assert_eq!(result.description, "Edited");
    assert_eq!(result.amount, d("-600"));
}

#[tokio::test]
async fn invalid_request_is_rejected_with_the_bean_validation_errors() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Checking, "0", true).await;
    let req = TransactionRequest {
        description: Some("   ".to_owned()),
        ..TransactionRequest::default()
    };

    let err = f.add(account.id, &req).await.unwrap_err();

    let AppError::Validation(errors) = err else {
        panic!("expected a validation error, got {err:?}");
    };
    assert_eq!(
        errors.get("date").map(String::as_str),
        Some("must not be null")
    );
    assert_eq!(
        errors.get("description").map(String::as_str),
        Some("must not be blank")
    );
    assert_eq!(
        errors.get("amount").map(String::as_str),
        Some("must not be null")
    );
    assert!(
        transactions::find_by_account_newest_first(&f.app.state.db, account.id)
            .await
            .unwrap()
            .is_empty()
    );
}

#[test]
fn transaction_request_reads_camel_case_and_ignores_unknown_fields() {
    let req: TransactionRequest = serde_json::from_str(
        r#"{"date": "2026-03-10", "description": "Buy", "amount": -1001.50, "txType": "BUY",
            "ticker": "AAPL", "quantity": 10, "pricePerUnit": "100", "categoryId": 55,
            "fees": 1.50, "currency": null, "somethingElse": true}"#,
    )
    .unwrap();
    assert_eq!(req.date, Some(day(2026, 3, 10)));
    assert_eq!(req.amount.unwrap().to_string(), "-1001.50");
    assert_eq!(req.tx_type, Some(TransactionType::Buy));
    assert_eq!(req.price_per_unit, Some(d("100")));
    assert_eq!(req.category_id, Some(55));
    assert_eq!(req.currency, None);
    let mut errors = Errors::new(Lang::French);
    req.validate(&mut errors);
    assert!(errors.into_result().is_ok());
}

#[tokio::test]
async fn transaction_response_omits_nulls_and_uses_the_java_field_names() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Checking, "0", true).await;
    let result = f
        .add(
            account.id,
            &request(
                day(2026, 1, 15),
                "Salary",
                "1000.50",
                TransactionType::Deposit,
            ),
        )
        .await
        .unwrap();

    let body = serde_json::to_value(&result).unwrap();
    let keys: Vec<&str> = body
        .as_object()
        .unwrap()
        .keys()
        .map(String::as_str)
        .collect();
    assert_eq!(
        keys,
        [
            "id",
            "date",
            "description",
            "amount",
            "nativeCurrency",
            "createdAt",
            "isManual",
            "txType",
            "merchantLabel",
            "accountId",
            "accountName",
            "budgetNeutral",
        ]
    );
    assert_eq!(body["date"], json!("2026-01-15"));
    assert_eq!(body["amount"].to_string(), "1000.50");
    // Read back later, the stored row carries the column's scale, as a Java read does.
    let stored = transactions::find_by_id_and_account(&f.app.state.db, result.id, account.id)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(stored.amount.to_string(), "1000.50000000");
    assert_eq!(body["txType"], json!("DEPOSIT"));
    assert_eq!(body["isManual"], json!(true));
    assert_eq!(body["budgetNeutral"], json!(false));
    assert_eq!(body["accountName"], json!("Account"));
    assert_eq!(body["createdAt"], json!("2026-03-15T12:00:00Z"));
}
