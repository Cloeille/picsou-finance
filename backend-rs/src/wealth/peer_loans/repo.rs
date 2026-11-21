//! peer_loan_terms, peer_loan_repayment and peer_counterparty rows.

use chrono::{DateTime, Utc};
use sqlx::{PgConnection, PgExecutor};

use crate::ledger::decimal::{HALF_UP, set_scale};
use crate::ledger::model::{PeerLoanRepayment, PeerLoanTerms};

const TERMS_COLUMNS: &str = "id, account_id, counterparty_id, principal, lent_at, expected_back, annual_rate, \
    rate_period, planned_monthly_amount, planned_months, linked_account_id, lending_transaction_id, note, closed_at";

const REPAYMENT_COLUMNS: &str =
    "id, account_id, paid_on, amount, kind, note, cash_transaction_id, cash_leg_owned";

pub async fn find_terms<'e>(
    db: impl PgExecutor<'e>,
    account_id: i64,
) -> sqlx::Result<Option<PeerLoanTerms>> {
    let terms: Option<PeerLoanTerms> = sqlx::query_as(&format!(
        "SELECT {TERMS_COLUMNS} FROM peer_loan_terms WHERE account_id = $1"
    ))
    .bind(account_id)
    .fetch_optional(db)
    .await?;
    Ok(terms.map(with_column_scale))
}

/// sqlx decodes a NUMERIC zero without its scale; JDBC keeps the column's (0.000000).
fn with_column_scale(mut terms: PeerLoanTerms) -> PeerLoanTerms {
    terms.annual_rate = terms
        .annual_rate
        .map(|rate| if rate.is_zero() { set_scale(rate, 6, HALF_UP) } else { rate });
    terms
}

/// findForUpdateByAccountId: the row, locked until the transaction ends.
pub async fn find_terms_for_update(
    conn: &mut PgConnection,
    account_id: i64,
) -> sqlx::Result<Option<PeerLoanTerms>> {
    let terms: Option<PeerLoanTerms> = sqlx::query_as(&format!(
        "SELECT {TERMS_COLUMNS} FROM peer_loan_terms WHERE account_id = $1 FOR UPDATE"
    ))
    .bind(account_id)
    .fetch_optional(conn)
    .await?;
    Ok(terms.map(with_column_scale))
}

pub async fn lending_transaction_linked<'e>(
    db: impl PgExecutor<'e>,
    transaction_id: i64,
) -> sqlx::Result<bool> {
    sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM peer_loan_terms WHERE lending_transaction_id = $1)",
    )
    .bind(transaction_id)
    .fetch_one(db)
    .await
}

/// Inserts the terms when id is 0, updates them otherwise; returns the id.
pub async fn save_terms(
    conn: &mut PgConnection,
    terms: &PeerLoanTerms,
    now: DateTime<Utc>,
) -> sqlx::Result<i64> {
    let sql = if terms.id == 0 {
        "INSERT INTO peer_loan_terms (account_id, counterparty_id, principal, lent_at, expected_back, \
         annual_rate, rate_period, planned_monthly_amount, planned_months, linked_account_id, \
         lending_transaction_id, note, closed_at, created_at, updated_at) \
         VALUES ($2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $15) RETURNING id"
    } else {
        "UPDATE peer_loan_terms SET account_id = $2, counterparty_id = $3, principal = $4, lent_at = $5, \
         expected_back = $6, annual_rate = $7, rate_period = $8, planned_monthly_amount = $9, \
         planned_months = $10, linked_account_id = $11, lending_transaction_id = $12, note = $13, \
         closed_at = $14, updated_at = $15 WHERE id = $1 RETURNING id"
    };
    sqlx::query_scalar(sql)
        .bind(terms.id)
        .bind(terms.account_id)
        .bind(terms.counterparty_id)
        .bind(terms.principal)
        .bind(terms.lent_at)
        .bind(terms.expected_back)
        .bind(terms.annual_rate)
        .bind(terms.rate_period)
        .bind(terms.planned_monthly_amount)
        .bind(terms.planned_months)
        .bind(terms.linked_account_id)
        .bind(terms.lending_transaction_id)
        .bind(&terms.note)
        .bind(terms.closed_at)
        .bind(now)
        .fetch_one(conn)
        .await
}

/// findByAccountIdOrderByPaidOnAscIdAsc.
pub async fn repayments<'e>(
    db: impl PgExecutor<'e>,
    account_id: i64,
) -> sqlx::Result<Vec<PeerLoanRepayment>> {
    sqlx::query_as(&format!(
        "SELECT {REPAYMENT_COLUMNS} FROM peer_loan_repayment WHERE account_id = $1 ORDER BY paid_on, id"
    ))
    .bind(account_id)
    .fetch_all(db)
    .await
}

pub async fn find_repayment<'e>(
    db: impl PgExecutor<'e>,
    id: i64,
    account_id: i64,
) -> sqlx::Result<Option<PeerLoanRepayment>> {
    sqlx::query_as(&format!(
        "SELECT {REPAYMENT_COLUMNS} FROM peer_loan_repayment WHERE id = $1 AND account_id = $2"
    ))
    .bind(id)
    .bind(account_id)
    .fetch_optional(db)
    .await
}

pub async fn cash_transaction_linked<'e>(
    db: impl PgExecutor<'e>,
    transaction_id: i64,
) -> sqlx::Result<bool> {
    sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM peer_loan_repayment WHERE cash_transaction_id = $1)",
    )
    .bind(transaction_id)
    .fetch_one(db)
    .await
}

/// Inserts the repayment (its id is ignored) and returns the new id.
pub async fn insert_repayment(
    conn: &mut PgConnection,
    repayment: &PeerLoanRepayment,
    now: DateTime<Utc>,
) -> sqlx::Result<i64> {
    sqlx::query_scalar(
        "INSERT INTO peer_loan_repayment (account_id, paid_on, amount, kind, note, cash_transaction_id, \
         cash_leg_owned, created_at, updated_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $8) RETURNING id",
    )
    .bind(repayment.account_id)
    .bind(repayment.paid_on)
    .bind(repayment.amount)
    .bind(repayment.kind)
    .bind(&repayment.note)
    .bind(repayment.cash_transaction_id)
    .bind(repayment.cash_leg_owned)
    .bind(now)
    .fetch_one(conn)
    .await
}

pub async fn update_repayment(
    conn: &mut PgConnection,
    repayment: &PeerLoanRepayment,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    sqlx::query(
        "UPDATE peer_loan_repayment SET paid_on = $2, amount = $3, kind = $4, note = $5, \
         cash_transaction_id = $6, cash_leg_owned = $7, updated_at = $8 WHERE id = $1",
    )
    .bind(repayment.id)
    .bind(repayment.paid_on)
    .bind(repayment.amount)
    .bind(repayment.kind)
    .bind(&repayment.note)
    .bind(repayment.cash_transaction_id)
    .bind(repayment.cash_leg_owned)
    .bind(now)
    .execute(conn)
    .await
    .map(|_| ())
}

pub async fn delete_repayment(conn: &mut PgConnection, id: i64) -> sqlx::Result<()> {
    sqlx::query("DELETE FROM peer_loan_repayment WHERE id = $1")
        .bind(id)
        .execute(conn)
        .await
        .map(|_| ())
}

/// findByMemberIdAndNameIgnoreCase, or a new row.
pub async fn counterparty_id(
    conn: &mut PgConnection,
    member_id: i64,
    name: &str,
    now: DateTime<Utc>,
) -> sqlx::Result<i64> {
    let existing: Option<i64> = sqlx::query_scalar(
        "SELECT id FROM peer_counterparty WHERE member_id = $1 AND lower(name) = lower($2) ORDER BY id LIMIT 1",
    )
    .bind(member_id)
    .bind(name)
    .fetch_optional(&mut *conn)
    .await?;
    if let Some(id) = existing {
        return Ok(id);
    }
    sqlx::query_scalar(
        "INSERT INTO peer_counterparty (member_id, name, created_at, updated_at) VALUES ($1, $2, $3, $3) RETURNING id",
    )
    .bind(member_id)
    .bind(name)
    .bind(now)
    .fetch_one(conn)
    .await
}
