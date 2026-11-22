//! DebtRepository, PeerLoanTermsRepository and PeerLoanRepaymentRepository: the rows the
//! valuation of LOAN and PEER_LOAN accounts reads.

use super::model::{Debt, PeerLoanRepayment, PeerLoanTerms};
use crate::db::DbExec;

const DEBT_COLUMNS: &str = "id, member_id, account_id, linked_account_id, borrowed_amount, interest_rate, \
    monthly_payment, lender_name, start_date, end_date, insurance_monthly, file_fees";
const TERMS_COLUMNS: &str = "t.id, t.account_id, t.counterparty_id, t.principal, t.lent_at, t.expected_back, \
    t.annual_rate, t.rate_period, t.planned_monthly_amount, t.planned_months, t.linked_account_id, \
    t.lending_transaction_id, t.note, t.closed_at";
const REPAYMENT_COLUMNS: &str = "r.id, r.account_id, r.paid_on, r.amount, r.kind, r.note, r.cash_transaction_id, r.cash_leg_owned";

pub async fn debt_of_account<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
) -> sqlx::Result<Option<Debt>> {
    crate::db::query_as(&format!(
        "SELECT {DEBT_COLUMNS} FROM debt WHERE account_id = $1"
    ))
    .bind(account_id)
    .fetch_optional(db)
    .await
}

pub async fn debts_of_accounts<'e>(
    db: impl DbExec<'e>,
    account_ids: &[i64],
) -> sqlx::Result<Vec<Debt>> {
    crate::db::query_as(&format!(
        "SELECT {DEBT_COLUMNS} FROM debt WHERE account_id = ANY($1) ORDER BY id"
    ))
    .bind(account_ids)
    .fetch_all(db)
    .await
}

pub async fn debts_of_member<'e>(db: impl DbExec<'e>, member_id: i64) -> sqlx::Result<Vec<Debt>> {
    crate::db::query_as(&format!(
        "SELECT {DEBT_COLUMNS} FROM debt WHERE member_id = $1 ORDER BY id"
    ))
    .bind(member_id)
    .fetch_all(db)
    .await
}

/// Debts whose repayments are debited from the account.
pub async fn debts_linked_to<'e>(db: impl DbExec<'e>, account_id: i64) -> sqlx::Result<Vec<Debt>> {
    crate::db::query_as(&format!(
        "SELECT {DEBT_COLUMNS} FROM debt WHERE linked_account_id = $1 ORDER BY id"
    ))
    .bind(account_id)
    .fetch_all(db)
    .await
}

pub async fn peer_loan_terms_of_account<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
) -> sqlx::Result<Option<PeerLoanTerms>> {
    crate::db::query_as(&format!(
        "SELECT {TERMS_COLUMNS} FROM peer_loan_terms t WHERE t.account_id = $1"
    ))
    .bind(account_id)
    .fetch_optional(db)
    .await
}

/// The member's peer loans, oldest loan first.
pub async fn peer_loan_terms_of_member<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<Vec<PeerLoanTerms>> {
    crate::db::query_as(&format!(
        "SELECT {TERMS_COLUMNS} FROM peer_loan_terms t JOIN account a ON a.id = t.account_id \
         WHERE a.member_id = $1 AND a.deleted_at IS NULL ORDER BY t.lent_at, t.id"
    ))
    .bind(member_id)
    .fetch_all(db)
    .await
}

/// The account's repayment ledger in the order the calculator replays it.
pub async fn peer_loan_repayments_of_account<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
) -> sqlx::Result<Vec<PeerLoanRepayment>> {
    crate::db::query_as(&format!(
        "SELECT {REPAYMENT_COLUMNS} FROM peer_loan_repayment r WHERE r.account_id = $1 ORDER BY r.paid_on, r.id"
    ))
    .bind(account_id)
    .fetch_all(db)
    .await
}

pub async fn peer_loan_repayments_of_accounts<'e>(
    db: impl DbExec<'e>,
    account_ids: &[i64],
) -> sqlx::Result<Vec<PeerLoanRepayment>> {
    crate::db::query_as(&format!(
        "SELECT {REPAYMENT_COLUMNS} FROM peer_loan_repayment r WHERE r.account_id = ANY($1) ORDER BY r.paid_on, r.id"
    ))
    .bind(account_ids)
    .fetch_all(db)
    .await
}
