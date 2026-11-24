use std::collections::{BTreeSet, HashMap};
use std::str::FromStr;

use async_trait::async_trait;
use chrono::{DateTime, Days, NaiveDate, TimeZone, Utc};
use http::StatusCode;
use picsou::db::AsDbHandle;
use picsou::error::AppError;
use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::manual_ledger::{
    compute_manual_ledger_balance, plan_reconstruction, reconstruct_snapshots_from_db,
};
use picsou::ledger::model::{
    Account, AccountType, BalanceSnapshot, NewTransaction, SnapshotSource,
};
use picsou::ledger::prices::{PriceRead, Quote};
use picsou::ledger::snapshots::{self, NewSnapshot};
use picsou::ledger::transactions;
use picsou::testing::TestApp;
use rust_decimal::Decimal;

pub(super) fn d(s: &str) -> Decimal {
    Decimal::from_str(s).unwrap()
}

pub(super) fn today() -> NaiveDate {
    NaiveDate::from_ymd_opt(2026, 3, 15).unwrap()
}

pub(super) fn now() -> DateTime<Utc> {
    Utc.with_ymd_and_hms(2026, 3, 15, 12, 0, 0).unwrap()
}

pub(super) fn days_ago(n: u64) -> NaiveDate {
    today().checked_sub_days(Days::new(n)).unwrap()
}

/// FX rates only; no quote is ever priced.
#[derive(Default)]
pub(super) struct FakePrices(pub HashMap<&'static str, Decimal>);

impl FakePrices {
    pub(super) fn with(rates: &[(&'static str, &str)]) -> Self {
        Self(rates.iter().map(|(c, r)| (*c, d(r))).collect())
    }
}

#[async_trait]
impl PriceRead for FakePrices {
    async fn quotes(
        &self,
        _tickers: &BTreeSet<String>,
        _crypto_only: bool,
        _today: NaiveDate,
    ) -> HashMap<String, Quote> {
        HashMap::new()
    }

    async fn fx_rate_eur(&self, currency: &str) -> Option<Decimal> {
        self.0.get(currency).copied()
    }
}

fn row(id: i64, date: NaiveDate, balance: &str, source: SnapshotSource) -> BalanceSnapshot {
    BalanceSnapshot {
        id,
        account_id: 1,
        date,
        balance: d(balance),
        invested_amount: d(balance),
        created_at: now(),
        source,
        native_balance: None,
        statement_entered_at: None,
    }
}

fn by_date(inserts: &[(NaiveDate, Decimal)]) -> HashMap<NaiveDate, Decimal> {
    inserts.iter().copied().collect()
}

#[test]
fn reconstruct_snapshots_from_db_records_the_end_of_day_balance_under_each_day() {
    let plan = plan_reconstruction(
        d("1000"),
        &[(days_ago(1), d("-50")), (days_ago(2), d("200"))],
        &[],
        today(),
    );
    let saved = by_date(&plan.inserts);
    assert_eq!(saved[&today()], d("1000"));
    // End of yesterday: after the -50, i.e. today's balance.
    assert_eq!(saved[&days_ago(1)], d("1000"));
    // End of two days ago: before the -50, after the +200.
    assert_eq!(saved[&days_ago(2)], d("1050"));
    assert_eq!(plan.inserts[0].0, today());
}

#[test]
fn reconstruct_snapshots_from_db_preserves_hand_entered_statements() {
    let stored = [
        row(1, days_ago(40), "1000", SnapshotSource::Manual),
        row(2, days_ago(10), "1200", SnapshotSource::Manual),
    ];
    let plan = plan_reconstruction(d("1250"), &[(days_ago(3), d("50"))], &stored, today());
    assert!(plan.delete_ids.is_empty());
    let saved = by_date(&plan.inserts);
    // Only today's anchor and the transaction date are rebuilt; the statements stand.
    assert_eq!(saved.len(), 2);
    assert_eq!(saved[&today()], d("1250"));
    assert_eq!(saved[&days_ago(3)], d("1250"));
}

#[test]
fn reconstruct_snapshots_from_db_no_derived_row_before_the_oldest_manual_statement() {
    let stored = [row(1, days_ago(40), "1200", SnapshotSource::Manual)];
    let plan = plan_reconstruction(
        d("1250"),
        &[(days_ago(3), d("50")), (days_ago(60), d("200"))],
        &stored,
        today(),
    );
    let dates: Vec<NaiveDate> = plan.inserts.iter().map(|(date, _)| *date).collect();
    assert_eq!(dates, [today(), days_ago(3)]);
}

#[test]
fn reconstruct_snapshots_from_db_manual_today_wins_over_the_anchor() {
    let stored = [row(1, today(), "1300", SnapshotSource::Manual)];
    let plan = plan_reconstruction(d("1250"), &[(days_ago(1), d("50"))], &stored, today());
    // No anchor, and every transaction date falls before the oldest statement.
    assert!(plan.inserts.is_empty());
}

#[test]
fn reconstruct_snapshots_from_db_no_transaction_left_drops_stale_derived_rows_and_reanchors_today()
{
    let stored = [
        row(1, days_ago(7), "999", SnapshotSource::Derived),
        row(2, days_ago(10), "1200", SnapshotSource::Manual),
    ];
    let plan = plan_reconstruction(d("1200"), &[], &stored, today());
    assert_eq!(plan.delete_ids, [1]);
    assert_eq!(plan.inserts, [(today(), d("1200"))]);
}

#[test]
fn reconstruct_snapshots_from_db_future_statement_keeps_past_derived_history() {
    let future = today().checked_add_days(Days::new(60)).unwrap();
    let stored = [
        row(1, future, "2000", SnapshotSource::Manual),
        row(2, days_ago(20), "1100", SnapshotSource::Derived),
    ];
    let plan = plan_reconstruction(d("1250"), &[(days_ago(7), d("50"))], &stored, today());
    assert_eq!(plan.delete_ids, [2]);
    let dates: Vec<NaiveDate> = plan.inserts.iter().map(|(date, _)| *date).collect();
    assert_eq!(dates, [today(), days_ago(7)]);
}

pub(super) async fn ledger_account(app: &TestApp, currency: &str, balance: &str) -> Account {
    let (_, member) = app.create_member("alice", "pw-alice-123").await;
    let mut account = NewAccount::new(member, "Livret", AccountType::LivretA);
    account.currency = currency.to_owned();
    account.current_balance = d(balance);
    accounts::insert(&app.state.db, &account, now())
        .await
        .unwrap()
}

pub(super) async fn book(
    app: &TestApp,
    account_id: i64,
    date: NaiveDate,
    amount: &str,
    currency: &str,
    at: DateTime<Utc>,
) {
    let mut tx = NewTransaction::new(account_id, date, "Movement", d(amount));
    tx.native_currency = currency.to_owned();
    tx.is_manual = true;
    transactions::insert(&app.state.db, &tx, at).await.unwrap();
}

pub(super) async fn statement(
    app: &TestApp,
    account_id: i64,
    date: NaiveDate,
    eur: &str,
    native: Option<&str>,
    created_at: DateTime<Utc>,
    entered_at: Option<DateTime<Utc>>,
) -> BalanceSnapshot {
    let snapshot = NewSnapshot {
        source: SnapshotSource::Manual,
        native_balance: native.map(d),
        statement_entered_at: entered_at,
        ..NewSnapshot::derived(account_id, date, d(eur), d(eur))
    };
    snapshots::insert(&app.state.db, &snapshot, created_at)
        .await
        .unwrap()
}

async fn balance(
    app: &TestApp,
    account: &Account,
    prices: &FakePrices,
) -> Result<Decimal, AppError> {
    let mut conn = app.state.db.acquire().await.unwrap();
    compute_manual_ledger_balance(&mut conn.as_handle(), account, prices, today()).await
}

fn hours(h: i64) -> chrono::TimeDelta {
    chrono::TimeDelta::hours(h)
}

#[tokio::test]
async fn compute_manual_ledger_balance_without_statement_sums_every_transaction() {
    let app = TestApp::with_admin().await;
    let account = ledger_account(&app, "EUR", "0").await;
    book(&app, account.id, days_ago(5), "1200", "EUR", now()).await;
    book(&app, account.id, days_ago(2), "-200", "EUR", now()).await;
    assert_eq!(
        balance(&app, &account, &FakePrices::default())
            .await
            .unwrap(),
        d("1000")
    );
}

#[tokio::test]
async fn compute_manual_ledger_balance_anchored_on_the_latest_statement() {
    let app = TestApp::with_admin().await;
    let account = ledger_account(&app, "EUR", "0").await;
    book(&app, account.id, days_ago(20), "999", "EUR", now()).await;
    statement(
        &app,
        account.id,
        days_ago(30),
        "500",
        Some("500"),
        now(),
        None,
    )
    .await;
    statement(
        &app,
        account.id,
        days_ago(10),
        "1200",
        Some("1200"),
        now(),
        None,
    )
    .await;
    book(&app, account.id, days_ago(3), "50", "EUR", now()).await;
    // A statement dated in the future is stored but ignored until its date.
    let future = today().checked_add_days(Days::new(5)).unwrap();
    statement(&app, account.id, future, "9000", Some("9000"), now(), None).await;
    assert_eq!(
        balance(&app, &account, &FakePrices::default())
            .await
            .unwrap(),
        d("1250")
    );
}

#[tokio::test]
async fn compute_manual_ledger_balance_same_day_transaction_created_after_the_statement_is_included()
 {
    let app = TestApp::with_admin().await;
    let account = ledger_account(&app, "EUR", "0").await;
    let morning = now() - hours(1);
    statement(&app, account.id, today(), "600", Some("600"), morning, None).await;
    book(&app, account.id, today(), "-50", "EUR", now()).await;
    assert_eq!(
        balance(&app, &account, &FakePrices::default())
            .await
            .unwrap(),
        d("550")
    );
}

#[tokio::test]
async fn compute_manual_ledger_balance_same_day_transaction_created_before_the_statement_is_excluded()
 {
    let app = TestApp::with_admin().await;
    let account = ledger_account(&app, "EUR", "0").await;
    book(&app, account.id, today(), "-50", "EUR", now() - hours(1)).await;
    statement(&app, account.id, today(), "600", Some("600"), now(), None).await;
    assert_eq!(
        balance(&app, &account, &FakePrices::default())
            .await
            .unwrap(),
        d("600")
    );
}

#[tokio::test]
async fn compute_manual_ledger_balance_recycled_row_orders_by_entry_instant_not_creation() {
    let app = TestApp::with_admin().await;
    let account = ledger_account(&app, "EUR", "0").await;
    // A morning DERIVED row flipped to a statement in the evening, after a midday movement.
    book(&app, account.id, today(), "-30", "EUR", now() - hours(4)).await;
    statement(
        &app,
        account.id,
        today(),
        "570",
        Some("570"),
        now() - hours(9),
        Some(now()),
    )
    .await;
    assert_eq!(
        balance(&app, &account, &FakePrices::default())
            .await
            .unwrap(),
        d("570")
    );
}

#[tokio::test]
async fn compute_manual_ledger_balance_foreign_currency_uses_stored_native_ignoring_later_fx_moves()
{
    let app = TestApp::with_admin().await;
    let account = ledger_account(&app, "USD", "0").await;
    statement(
        &app,
        account.id,
        days_ago(10),
        "910",
        Some("1000"),
        now(),
        None,
    )
    .await;
    book(&app, account.id, days_ago(3), "50", "USD", now()).await;
    // No rate at all: touching one would fail.
    assert_eq!(
        balance(&app, &account, &FakePrices::default())
            .await
            .unwrap(),
        d("1050")
    );
}

#[tokio::test]
async fn compute_manual_ledger_balance_legacy_row_without_native_converts_the_anchor_back() {
    let app = TestApp::with_admin().await;
    let account = ledger_account(&app, "USD", "0").await;
    statement(&app, account.id, days_ago(10), "1000", None, now(), None).await;
    let result = balance(&app, &account, &FakePrices::with(&[("USD", "1.1")]))
        .await
        .unwrap();
    assert_eq!(result.to_string(), "909.09090909");
}

#[tokio::test]
async fn compute_manual_ledger_balance_missing_rate_throws_instead_of_engraving() {
    let app = TestApp::with_admin().await;
    let account = ledger_account(&app, "USD", "0").await;
    statement(&app, account.id, days_ago(10), "1000", None, now(), None).await;
    for prices in [FakePrices::default(), FakePrices::with(&[("USD", "0")])] {
        match balance(&app, &account, &prices).await {
            Err(AppError::Status { status, detail }) => {
                assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
                assert!(detail.contains("USD"), "{detail}");
            }
            other => panic!("expected a sync error, got {other:?}"),
        }
    }
}

async fn history(app: &TestApp, account_id: i64) -> Vec<(NaiveDate, Decimal, SnapshotSource)> {
    snapshots::find_by_account(&app.state.db, account_id)
        .await
        .unwrap()
        .into_iter()
        .map(|s| (s.date, s.balance, s.source))
        .collect()
}

#[tokio::test]
async fn reconstruct_snapshots_from_db_writes_the_history_in_eur_converting_the_balance_and_every_transaction()
 {
    let app = TestApp::with_admin().await;
    let account = ledger_account(&app, "USD", "1000").await;
    book(&app, account.id, days_ago(1), "100", "USD", now()).await;
    book(&app, account.id, days_ago(2), "10", "GBP", now()).await;
    book(&app, account.id, days_ago(3), "1", "EUR", now()).await;
    let prices = FakePrices::with(&[("USD", "0.9"), ("GBP", "1.2")]);

    let mut conn = app.state.db.acquire().await.unwrap();
    let written =
        reconstruct_snapshots_from_db(&mut conn.as_handle(), &account, &prices, today(), now())
            .await
            .unwrap();

    assert_eq!(written, 4);
    let derived = SnapshotSource::Derived;
    assert_eq!(
        history(&app, account.id).await,
        [
            (days_ago(3), d("798"), derived),
            (days_ago(2), d("810"), derived),
            (days_ago(1), d("900"), derived),
            (today(), d("900"), derived),
        ]
    );
}

#[tokio::test]
async fn reconstruct_snapshots_from_db_aborts_rather_than_writing_an_unconverted_amount_as_eur() {
    let app = TestApp::with_admin().await;
    let account = ledger_account(&app, "EUR", "1000").await;
    book(&app, account.id, days_ago(1), "100", "USD", now()).await;
    snapshots::insert(
        &app.state.db,
        &NewSnapshot::derived(account.id, days_ago(5), d("700"), d("700")),
        now(),
    )
    .await
    .unwrap();

    let mut conn = app.state.db.acquire().await.unwrap();
    let result = reconstruct_snapshots_from_db(
        &mut conn.as_handle(),
        &account,
        &FakePrices::default(),
        today(),
        now(),
    )
    .await;

    assert!(matches!(result, Err(AppError::Status { ref detail, .. }) if detail.contains("USD")));
    assert_eq!(
        history(&app, account.id).await,
        [(days_ago(5), d("700"), SnapshotSource::Derived)]
    );
}

#[tokio::test]
async fn reconstruct_snapshots_from_db_replaces_derived_rows_and_keeps_statements() {
    let app = TestApp::with_admin().await;
    let account = ledger_account(&app, "EUR", "1250").await;
    statement(
        &app,
        account.id,
        days_ago(10),
        "1200",
        Some("1200"),
        now(),
        None,
    )
    .await;
    snapshots::insert(
        &app.state.db,
        &NewSnapshot::derived(account.id, days_ago(5), d("1"), d("1")),
        now(),
    )
    .await
    .unwrap();
    book(&app, account.id, days_ago(3), "50", "EUR", now()).await;
    book(&app, account.id, days_ago(40), "1200", "EUR", now()).await;

    let mut conn = app.state.db.acquire().await.unwrap();
    reconstruct_snapshots_from_db(
        &mut conn.as_handle(),
        &account,
        &FakePrices::default(),
        today(),
        now(),
    )
    .await
    .unwrap();

    assert_eq!(
        history(&app, account.id).await,
        [
            (days_ago(10), d("1200"), SnapshotSource::Manual),
            (days_ago(3), d("1250"), SnapshotSource::Derived),
            (today(), d("1250"), SnapshotSource::Derived),
        ]
    );
}

#[tokio::test]
async fn reconstruct_snapshots_from_db_handles_transactions_dated_before_2000() {
    let app = TestApp::with_admin().await;
    let mut account = ledger_account(&app, "EUR", "500").await;
    let pre_2000 = NaiveDate::from_ymd_opt(1999, 6, 15).unwrap();
    let pre_2000_manual = NaiveDate::from_ymd_opt(1999, 1, 10).unwrap();
    statement(
        &app,
        account.id,
        pre_2000_manual,
        "100",
        Some("100"),
        now(),
        None,
    )
    .await;
    book(&app, account.id, pre_2000, "400", "EUR", now()).await;

    {
        let mut conn = app.state.db.acquire().await.unwrap();
        reconstruct_snapshots_from_db(
            &mut conn.as_handle(),
            &account,
            &FakePrices::default(),
            today(),
            now(),
        )
        .await
        .unwrap();
    }

    // A later edit on the account rebuilds the history again; the pre-2000 derived row must be
    // replaced rather than re-inserted over itself, and the pre-2000 statement must stay intact.
    book(&app, account.id, days_ago(2), "100", "EUR", now()).await;
    account.current_balance = d("600");
    {
        let mut conn = app.state.db.acquire().await.unwrap();
        reconstruct_snapshots_from_db(
            &mut conn.as_handle(),
            &account,
            &FakePrices::default(),
            today(),
            now(),
        )
        .await
        .unwrap();
    }

    assert_eq!(
        history(&app, account.id).await,
        [
            (pre_2000_manual, d("100"), SnapshotSource::Manual),
            (pre_2000, d("500"), SnapshotSource::Derived),
            (days_ago(2), d("600"), SnapshotSource::Derived),
            (today(), d("600"), SnapshotSource::Derived),
        ]
    );
}
