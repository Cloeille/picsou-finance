//! Port of InternalTransferPairingServiceTest, on database rows, and of the
//! ReceivedDividendClassifier ordering and isolation checked in MemberSyncServiceTest.

use std::str::FromStr;

use async_trait::async_trait;
use chrono::{DateTime, NaiveDate, Utc};
use picsou::db::Db;
use picsou::error::{AppError, AppResult};
use picsou::ledger::model::{AccountType, Category, NewTransaction, Transaction, TransactionType};
use picsou::ledger::transfers::{
    DividendClassifier, NoDividendClassifier, after_import, pair_recent,
};
use picsou::ledger::{accounts, categorize, transactions};
use picsou::testing::{ADMIN_USERNAME, TestApp, epoch};
use rust_decimal::Decimal;

fn today() -> NaiveDate {
    NaiveDate::from_ymd_opt(2026, 11, 14).unwrap()
}

struct Fixture {
    app: TestApp,
    member: i64,
    virement: Category,
    epargne: Category,
    courses: Category,
}

impl Fixture {
    async fn new() -> Self {
        let app = TestApp::with_admin().await;
        let member = picsou::auth::users::find_by_username(&app.state.db, ADMIN_USERNAME)
            .await
            .unwrap()
            .unwrap()
            .member_id;
        let mut by_slug = categorize::categories_by_slug(&app.state.db, member, epoch())
            .await
            .unwrap();
        Self {
            virement: by_slug.remove("virement-interne").unwrap(),
            epargne: by_slug.remove("epargne").unwrap(),
            courses: by_slug.remove("courses").unwrap(),
            app,
            member,
        }
    }

    fn db(&self) -> &Db {
        &self.app.state.db
    }

    async fn account(&self, account_type: AccountType) -> i64 {
        let new = accounts::NewAccount::new(self.member, account_type.as_str(), account_type);
        accounts::insert(self.db(), &new, epoch()).await.unwrap().id
    }

    fn row(account: i64, date: &str, amount: &str) -> NewTransaction {
        NewTransaction::new(
            account,
            NaiveDate::from_str(date).unwrap(),
            "VIR",
            Decimal::from_str(amount).unwrap(),
        )
    }

    async fn insert(&self, row: NewTransaction) -> i64 {
        transactions::insert(self.db(), &row, epoch())
            .await
            .unwrap()
            .id
    }

    async fn tx(&self, account: i64, date: &str, amount: &str) -> i64 {
        self.insert(Self::row(account, date, amount)).await
    }

    async fn load(&self, id: i64) -> Transaction {
        transactions::find_by_id_and_member(self.db(), id, self.member)
            .await
            .unwrap()
            .unwrap()
    }

    async fn category_of(&self, id: i64) -> Option<i64> {
        self.load(id).await.category_id
    }

    async fn pair(&self) -> usize {
        pair_recent(self.db(), self.member, today(), epoch())
            .await
            .unwrap()
    }
}

#[tokio::test]
async fn an_uncategorised_debit_and_credit_on_two_owned_accounts_become_an_internal_transfer() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    let out = f.tx(checking, "2026-11-02", "-500.00").await;
    let inc = f.tx(livret, "2026-11-03", "500").await;

    assert_eq!(f.pair().await, 2);

    let out_row = f.load(out).await;
    assert_eq!(out_row.category_id, Some(f.virement.id));
    assert!(!out_row.category_manual);
    assert_eq!(f.category_of(inc).await, Some(f.virement.id));
}

/// The case in the issue: one leg already a transfer, the Livret credit uncategorised.
#[tokio::test]
async fn a_leg_already_in_a_transfer_category_keeps_it_and_its_partner_is_categorised() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    let mut out = Fixture::row(checking, "2026-11-02", "-500");
    out.category_id = Some(f.epargne.id);
    let out = f.insert(out).await;
    let inc = f.tx(livret, "2026-11-02", "500").await;

    assert_eq!(f.pair().await, 1);

    assert_eq!(f.category_of(out).await, Some(f.epargne.id));
    assert_eq!(f.category_of(inc).await, Some(f.virement.id));
}

#[tokio::test]
async fn an_auto_categorised_expense_leg_is_recategorised_and_its_ai_suggestion_cleared() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    let mut out = Fixture::row(checking, "2026-11-02", "-120");
    out.category_id = Some(f.courses.id);
    let out = f.insert(out).await;
    let mut suggested = f.load(out).await;
    suggested.ai_suggested_category_id = Some(f.courses.id);
    suggested.ai_confidence = Some(55);
    transactions::update(f.db(), &suggested).await.unwrap();
    f.tx(livret, "2026-11-04", "120").await;

    assert_eq!(f.pair().await, 2);

    let out = f.load(out).await;
    assert_eq!(out.category_id, Some(f.virement.id));
    assert_eq!(out.ai_suggested_category_id, None);
    assert_eq!(out.ai_confidence, None);
}

#[tokio::test]
async fn a_manually_categorised_leg_is_never_overridden_nor_paired() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    let mut out = Fixture::row(checking, "2026-11-02", "-500");
    out.category_id = Some(f.courses.id);
    out.category_manual = true;
    let out = f.insert(out).await;
    let inc = f.tx(livret, "2026-11-02", "500").await;
    let mut hand_out = Fixture::row(checking, "2026-11-09", "-40");
    hand_out.is_manual = true;
    hand_out.category_id = Some(f.courses.id);
    let hand_out = f.insert(hand_out).await;
    let hand_in = f.tx(livret, "2026-11-09", "40").await;

    assert_eq!(f.pair().await, 0);

    assert_eq!(f.category_of(out).await, Some(f.courses.id));
    assert_eq!(f.category_of(hand_out).await, Some(f.courses.id));
    assert_eq!(f.category_of(inc).await, None);
    assert_eq!(f.category_of(hand_in).await, None);
}

#[tokio::test]
async fn legs_outside_the_window_only_make_a_group_ambiguous() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    let pea = f.account(AccountType::Pea).await;
    let ids = vec![
        f.tx(pea, "2026-08-14", "300").await,
        f.tx(checking, "2026-08-14", "-75").await,
        f.tx(livret, "2026-08-15", "75").await,
        f.tx(checking, "2026-08-17", "-300").await,
        f.tx(livret, "2026-08-18", "300").await,
        f.tx(checking, "2026-11-14", "-60").await,
        f.tx(livret, "2026-11-16", "60").await,
    ];

    assert_eq!(f.pair().await, 0);

    for id in ids {
        assert_eq!(f.category_of(id).await, None);
    }
}

#[tokio::test]
async fn two_candidate_credits_leave_the_debit_alone() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    let pea = f.account(AccountType::Pea).await;
    let ids = vec![
        f.tx(checking, "2026-11-02", "-500").await,
        f.tx(livret, "2026-11-02", "500").await,
        f.tx(pea, "2026-11-03", "500").await,
    ];

    assert_eq!(f.pair().await, 0);

    for id in ids {
        assert_eq!(f.category_of(id).await, None);
    }
}

#[tokio::test]
async fn two_candidate_debits_leave_the_credit_alone() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    let pea = f.account(AccountType::Pea).await;
    f.tx(checking, "2026-11-02", "-500").await;
    f.tx(pea, "2026-11-02", "-500").await;
    let inc = f.tx(livret, "2026-11-02", "500").await;

    assert_eq!(f.pair().await, 0);
    assert_eq!(f.category_of(inc).await, None);
}

#[tokio::test]
async fn an_ambiguous_group_does_not_block_an_unrelated_unique_pair() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    let pea = f.account(AccountType::Pea).await;
    let out = f.tx(checking, "2026-11-02", "-500").await;
    f.tx(livret, "2026-11-02", "500").await;
    f.tx(pea, "2026-11-02", "500").await;
    let rent_out = f.tx(checking, "2026-11-05", "-80").await;
    let rent_in = f.tx(pea, "2026-11-05", "80").await;

    assert_eq!(f.pair().await, 2);

    assert_eq!(f.category_of(rent_out).await, Some(f.virement.id));
    assert_eq!(f.category_of(rent_in).await, Some(f.virement.id));
    assert_eq!(f.category_of(out).await, None);
}

/// A manual leg is the credit's real partner: an unrelated debit must not take its place.
#[tokio::test]
async fn a_manual_leg_still_makes_its_group_ambiguous() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    let pea = f.account(AccountType::Pea).await;
    let mut manual_out = Fixture::row(checking, "2026-11-02", "-50");
    manual_out.category_id = Some(f.epargne.id);
    manual_out.category_manual = true;
    f.insert(manual_out).await;
    let inc = f.tx(livret, "2026-11-02", "50").await;
    let rent = f.tx(pea, "2026-11-03", "-50").await;

    assert_eq!(f.pair().await, 0);

    assert_eq!(f.category_of(rent).await, None);
    assert_eq!(f.category_of(inc).await, None);
}

/// A purchase refunded on the same account is not a transfer to an unrelated credit.
#[tokio::test]
async fn a_same_account_refund_makes_the_debit_ambiguous() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    let purchase = f.tx(checking, "2026-11-01", "-100").await;
    f.tx(checking, "2026-11-02", "100").await;
    let unrelated_in = f.tx(livret, "2026-11-02", "100").await;

    assert_eq!(f.pair().await, 0);

    assert_eq!(f.category_of(purchase).await, None);
    assert_eq!(f.category_of(unrelated_in).await, None);
}

#[tokio::test]
async fn legs_at_most_three_days_apart_are_paired_and_later_ones_are_not() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    f.tx(checking, "2026-11-01", "-500").await;
    f.tx(livret, "2026-11-04", "500").await;
    let late_out = f.tx(checking, "2026-11-01", "-70").await;
    f.tx(livret, "2026-11-05", "70").await;

    assert_eq!(f.pair().await, 2);
    assert_eq!(f.category_of(late_out).await, None);
}

#[tokio::test]
async fn different_currencies_amounts_or_a_single_account_are_not_paired() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    f.tx(checking, "2026-11-02", "-500").await;
    let mut in_usd = Fixture::row(livret, "2026-11-02", "500");
    in_usd.native_currency = "USD".to_owned();
    f.insert(in_usd).await;
    f.tx(checking, "2026-11-06", "-200").await;
    f.tx(livret, "2026-11-06", "200.01").await;
    f.tx(checking, "2026-11-10", "-30").await;
    f.tx(checking, "2026-11-11", "30").await;

    assert_eq!(f.pair().await, 0);
}

#[tokio::test]
async fn trade_legs_and_budget_neutral_rows_are_never_paired() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let pea = f.account(AccountType::Pea).await;
    let mut buy = Fixture::row(checking, "2026-11-02", "-500");
    buy.tx_type = Some(TransactionType::Buy);
    f.insert(buy).await;
    f.tx(pea, "2026-11-02", "500").await;
    let mut trading = Fixture::row(checking, "2026-11-06", "-300");
    trading.category = Some("trading".to_owned());
    f.insert(trading).await;
    let mut neutral = Fixture::row(pea, "2026-11-06", "300");
    neutral.budget_neutral = true;
    f.insert(neutral).await;

    assert_eq!(f.pair().await, 0);
}

#[tokio::test]
async fn a_second_run_changes_nothing() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    f.tx(checking, "2026-11-02", "-500").await;
    f.tx(livret, "2026-11-02", "500").await;

    assert_eq!(f.pair().await, 2);
    assert_eq!(f.pair().await, 0);
}

#[tokio::test]
async fn loan_accounts_are_left_out_so_a_repayment_stays_an_expense() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let loan = f.account(AccountType::Loan).await;
    let repayment = f.tx(checking, "2026-11-02", "-900").await;
    let principal = f.tx(loan, "2026-11-02", "900").await;

    assert_eq!(f.pair().await, 0);

    assert_eq!(f.category_of(repayment).await, None);
    assert_eq!(f.category_of(principal).await, None);
}

#[tokio::test]
async fn a_hidden_account_is_left_out() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    picsou::db::query("UPDATE account SET hidden = true WHERE id = $1")
        .bind(livret)
        .execute(f.db())
        .await
        .unwrap();
    let out = f.tx(checking, "2026-11-02", "-500").await;
    f.tx(livret, "2026-11-02", "500").await;

    assert_eq!(f.pair().await, 0);
    assert_eq!(f.category_of(out).await, None);
}

#[tokio::test]
async fn without_a_transfer_category_nothing_is_written() {
    let f = Fixture::new().await;
    picsou::db::query("UPDATE category SET archived = true WHERE id = $1")
        .bind(f.virement.id)
        .execute(f.db())
        .await
        .unwrap();
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    let out = f.tx(checking, "2026-11-02", "-500").await;
    f.tx(livret, "2026-11-02", "500").await;

    assert_eq!(f.pair().await, 0);
    assert_eq!(f.category_of(out).await, None);
}

#[tokio::test]
async fn a_transfer_slug_of_another_kind_writes_nothing() {
    let f = Fixture::new().await;
    picsou::db::query("UPDATE category SET kind = 'EXPENSE' WHERE id = $1")
        .bind(f.virement.id)
        .execute(f.db())
        .await
        .unwrap();
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    let out = f.tx(checking, "2026-11-02", "-500").await;
    f.tx(livret, "2026-11-02", "500").await;

    assert_eq!(f.pair().await, 0);
    assert_eq!(f.category_of(out).await, None);
}

/// Marks every credit labelled DIVIDENDE as a dividend, as DividendReceiptService would.
struct LabelClassifier;

#[async_trait]
impl DividendClassifier for LabelClassifier {
    async fn classify_recent(
        &self,
        db: &Db,
        _member_id: i64,
        _today: NaiveDate,
        _now: DateTime<Utc>,
    ) -> AppResult<usize> {
        let done = picsou::db::query(
            "UPDATE \"transaction\" SET tx_type = 'DIVIDEND' WHERE description = 'DIVIDENDE' AND amount > 0",
        )
        .execute(db)
        .await
        .map_err(|e| AppError::BadRequest(e.to_string()))?;
        Ok(usize::try_from(done.rows_affected()).unwrap())
    }
}

struct FailingClassifier;

#[async_trait]
impl DividendClassifier for FailingClassifier {
    async fn classify_recent(
        &self,
        _db: &Db,
        _member_id: i64,
        _today: NaiveDate,
        _now: DateTime<Utc>,
    ) -> AppResult<usize> {
        Err(AppError::BadRequest("db down".to_owned()))
    }
}

#[tokio::test]
async fn after_import_pairs_internal_transfers_after_dividends() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let pea = f.account(AccountType::Pea).await;
    let out = f.tx(checking, "2026-11-02", "-42").await;
    let mut dividend = Fixture::row(pea, "2026-11-02", "42");
    dividend.description = "DIVIDENDE".to_owned();
    let dividend = f.insert(dividend).await;

    after_import(f.db(), &LabelClassifier, f.member, today(), epoch()).await;

    let dividend = f.load(dividend).await;
    assert_eq!(dividend.tx_type, Some(TransactionType::Dividend));
    assert_eq!(dividend.category_id, None);
    assert_eq!(f.category_of(out).await, None);
}

#[tokio::test]
async fn transfer_pairing_runs_when_dividend_classification_fails() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    let out = f.tx(checking, "2026-11-02", "-500").await;
    let inc = f.tx(livret, "2026-11-02", "500").await;

    after_import(f.db(), &FailingClassifier, f.member, today(), epoch()).await;

    assert_eq!(f.category_of(out).await, Some(f.virement.id));
    assert_eq!(f.category_of(inc).await, Some(f.virement.id));
}

#[tokio::test]
async fn without_the_dividend_feature_after_import_still_pairs() {
    let f = Fixture::new().await;
    let checking = f.account(AccountType::Checking).await;
    let livret = f.account(AccountType::LivretA).await;
    let out = f.tx(checking, "2026-11-02", "-500").await;
    f.tx(livret, "2026-11-02", "500").await;

    after_import(f.db(), &NoDividendClassifier, f.member, today(), epoch()).await;

    assert_eq!(f.category_of(out).await, Some(f.virement.id));
}
