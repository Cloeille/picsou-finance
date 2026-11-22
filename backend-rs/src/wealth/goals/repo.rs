//! GoalRepository, GoalMonthOverrideRepository, GoalManualContributionRepository and the
//! goal_account / goal_allocation rows a Goal owns.

use std::collections::HashMap;

use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;

use super::dto::GoalType;
use crate::db::{DbExec, DbFromRow, DbHandle};
use crate::error::AppError;
use crate::ledger::model::{ACCOUNT_COLUMNS, Account};

/// A goal with the accounts it tracks (live ones only, as the entity's @SQLRestriction reads
/// them) and its monthly split.
#[derive(Debug, Clone, PartialEq)]
pub struct Goal {
    pub id: i64,
    pub member_id: i64,
    pub name: String,
    pub goal_type: GoalType,
    pub target_amount: Option<Decimal>,
    pub deadline: Option<NaiveDate>,
    pub monthly_amount: Option<Decimal>,
    pub expected_return: Option<Decimal>,
    pub start_date: Option<NaiveDate>,
    pub end_date: Option<NaiveDate>,
    pub history_start_month: Option<String>,
    pub created_at: DateTime<Utc>,
    pub accounts: Vec<Account>,
    pub allocations: Vec<Allocation>,
}

#[derive(Debug, Clone, PartialEq, DbFromRow)]
pub struct Allocation {
    pub ticker: String,
    pub monthly_amount: Decimal,
}

#[derive(DbFromRow)]
struct GoalRow {
    id: i64,
    member_id: i64,
    name: String,
    #[sqlx(rename = "type")]
    goal_type: String,
    target_amount: Option<Decimal>,
    deadline: Option<NaiveDate>,
    monthly_amount: Option<Decimal>,
    expected_return: Option<Decimal>,
    start_date: Option<NaiveDate>,
    end_date: Option<NaiveDate>,
    history_start_month: Option<String>,
    created_at: DateTime<Utc>,
}

const GOAL_COLUMNS: &str = "id, member_id, name, type, target_amount, deadline, monthly_amount, \
    expected_return, start_date, end_date, history_start_month, created_at";

/// ResourceNotFoundException.goal.
pub fn goal_not_found() -> AppError {
    AppError::NotFound("Goal not found".to_owned())
}

async fn hydrate(conn: &mut DbHandle<'_>, row: GoalRow) -> sqlx::Result<Goal> {
    let accounts = accounts_of(&mut *conn, row.id).await?;
    let allocations = crate::db::query_as::<Allocation>(
        "SELECT ticker, monthly_amount FROM goal_allocation WHERE goal_id = $1 ORDER BY id",
    )
    .bind(row.id)
    .fetch_all(&mut *conn)
    .await?;
    Ok(Goal {
        id: row.id,
        member_id: row.member_id,
        name: row.name,
        goal_type: GoalType::from_db(&row.goal_type),
        target_amount: row.target_amount,
        deadline: row.deadline,
        monthly_amount: row.monthly_amount,
        expected_return: row.expected_return,
        start_date: row.start_date,
        end_date: row.end_date,
        history_start_month: row.history_start_month,
        created_at: row.created_at,
        accounts,
        allocations,
    })
}

async fn accounts_of(conn: &mut DbHandle<'_>, goal_id: i64) -> sqlx::Result<Vec<Account>> {
    crate::db::query_as::<Account>(&format!(
        "SELECT {ACCOUNT_COLUMNS} FROM account a JOIN goal_account ga ON ga.account_id = a.id \
         WHERE ga.goal_id = $1 AND a.deleted_at IS NULL ORDER BY a.created_at, a.id"
    ))
    .bind(goal_id)
    .fetch_all(conn)
    .await
}

/// findByIdAndMemberId.
pub async fn find(conn: &mut DbHandle<'_>, id: i64, member_id: i64) -> sqlx::Result<Option<Goal>> {
    let row: Option<GoalRow> = crate::db::query_as::<GoalRow>(&format!(
        "SELECT {GOAL_COLUMNS} FROM goal WHERE id = $1 AND member_id = $2"
    ))
    .bind(id)
    .bind(member_id)
    .fetch_optional(&mut *conn)
    .await?;
    match row {
        Some(row) => Ok(Some(hydrate(conn, row).await?)),
        None => Ok(None),
    }
}

/// findAllByMemberIdOrderByCreatedAtAsc.
pub async fn find_all(conn: &mut DbHandle<'_>, member_id: i64) -> sqlx::Result<Vec<Goal>> {
    let rows: Vec<GoalRow> = crate::db::query_as::<GoalRow>(&format!(
        "SELECT {GOAL_COLUMNS} FROM goal WHERE member_id = $1 ORDER BY created_at, id"
    ))
    .bind(member_id)
    .fetch_all(&mut *conn)
    .await?;
    let mut goals = Vec::with_capacity(rows.len());
    for row in rows {
        goals.push(hydrate(&mut *conn, row).await?);
    }
    Ok(goals)
}

/// Inserts the goal row and returns its id and creation instant.
pub async fn insert(conn: &mut DbHandle<'_>, goal: &Goal, now: DateTime<Utc>) -> sqlx::Result<i64> {
    let id: i64 = crate::db::query_scalar::<i64>(
        "INSERT INTO goal (member_id, name, type, target_amount, deadline, monthly_amount, expected_return, \
         start_date, end_date, history_start_month, created_at, updated_at) \
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $11) RETURNING id",
    )
    .bind(goal.member_id)
    .bind(&goal.name)
    .bind(goal.goal_type.as_str())
    .bind(goal.target_amount)
    .bind(goal.deadline)
    .bind(goal.monthly_amount)
    .bind(goal.expected_return)
    .bind(goal.start_date)
    .bind(goal.end_date)
    .bind(&goal.history_start_month)
    .bind(now)
    .fetch_one(&mut *conn)
    .await?;
    replace_links(conn, id, goal).await?;
    Ok(id)
}

/// Writes every column of the goal and replaces its accounts and split.
pub async fn update(conn: &mut DbHandle<'_>, goal: &Goal, now: DateTime<Utc>) -> sqlx::Result<()> {
    crate::db::query(
        "UPDATE goal SET name = $2, type = $3, target_amount = $4, deadline = $5, monthly_amount = $6, \
         expected_return = $7, start_date = $8, end_date = $9, history_start_month = $10, updated_at = $11 \
         WHERE id = $1",
    )
    .bind(goal.id)
    .bind(&goal.name)
    .bind(goal.goal_type.as_str())
    .bind(goal.target_amount)
    .bind(goal.deadline)
    .bind(goal.monthly_amount)
    .bind(goal.expected_return)
    .bind(goal.start_date)
    .bind(goal.end_date)
    .bind(&goal.history_start_month)
    .bind(now)
    .execute(&mut *conn)
    .await?;
    replace_links(conn, goal.id, goal).await
}

async fn replace_links(conn: &mut DbHandle<'_>, goal_id: i64, goal: &Goal) -> sqlx::Result<()> {
    crate::db::query("DELETE FROM goal_account WHERE goal_id = $1")
        .bind(goal_id)
        .execute(&mut *conn)
        .await?;
    for account in &goal.accounts {
        crate::db::query("INSERT INTO goal_account (goal_id, account_id) VALUES ($1, $2)")
            .bind(goal_id)
            .bind(account.id)
            .execute(&mut *conn)
            .await?;
    }
    crate::db::query("DELETE FROM goal_allocation WHERE goal_id = $1")
        .bind(goal_id)
        .execute(&mut *conn)
        .await?;
    for line in &goal.allocations {
        crate::db::query(
            "INSERT INTO goal_allocation (goal_id, ticker, monthly_amount, created_at, updated_at) \
             VALUES ($1, $2, $3, NOW(), NOW())",
        )
        .bind(goal_id)
        .bind(&line.ticker)
        .bind(line.monthly_amount)
        .execute(&mut *conn)
        .await?;
    }
    Ok(())
}

pub async fn set_history_start(
    db: impl DbExec<'_>,
    goal_id: i64,
    month: &str,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    crate::db::query("UPDATE goal SET history_start_month = $2, updated_at = $3 WHERE id = $1")
        .bind(goal_id)
        .bind(month)
        .bind(now)
        .execute(db)
        .await
        .map(drop)
}

/// The goal row and everything hanging off it (ON DELETE CASCADE).
pub async fn delete(db: impl DbExec<'_>, goal_id: i64) -> sqlx::Result<()> {
    crate::db::query("DELETE FROM goal WHERE id = $1")
        .bind(goal_id)
        .execute(db)
        .await
        .map(drop)
}

/// The two per-month amount tables: objective overrides and manual contributions.
#[derive(Debug, Clone, Copy)]
pub enum MonthTable {
    Override,
    Manual,
}

impl MonthTable {
    fn name(self) -> &'static str {
        match self {
            Self::Override => "goal_month_override",
            Self::Manual => "goal_manual_contribution",
        }
    }
}

/// findByGoalId as a month -> amount map.
pub async fn month_amounts(
    db: impl DbExec<'_>,
    table: MonthTable,
    goal_id: i64,
) -> sqlx::Result<HashMap<String, Decimal>> {
    let rows: Vec<(String, Decimal)> = crate::db::query_as::<(String, Decimal)>(&format!(
        "SELECT year_month, amount FROM {} WHERE goal_id = $1",
        table.name()
    ))
    .bind(goal_id)
    .fetch_all(db)
    .await?;
    Ok(rows.into_iter().collect())
}

/// findByGoalIdAndYearMonth.
pub async fn month_amount(
    db: impl DbExec<'_>,
    table: MonthTable,
    goal_id: i64,
    year_month: &str,
) -> sqlx::Result<Option<Decimal>> {
    crate::db::query_scalar::<Decimal>(&format!(
        "SELECT amount FROM {} WHERE goal_id = $1 AND year_month = $2",
        table.name()
    ))
    .bind(goal_id)
    .bind(year_month)
    .fetch_optional(db)
    .await
}

/// Upserts the month's amount; a new manual contribution records the member who entered it.
pub async fn set_month_amount(
    db: impl DbExec<'_>,
    table: MonthTable,
    goal_id: i64,
    year_month: &str,
    amount: Decimal,
    member_id: i64,
) -> sqlx::Result<()> {
    let query = match table {
        MonthTable::Override => crate::db::query(
            "INSERT INTO goal_month_override (goal_id, year_month, amount) VALUES ($1, $2, $3) \
             ON CONFLICT (goal_id, year_month) DO UPDATE SET amount = EXCLUDED.amount",
        )
        .bind(goal_id)
        .bind(year_month)
        .bind(amount),
        MonthTable::Manual => crate::db::query(
            "INSERT INTO goal_manual_contribution (goal_id, year_month, amount, member_id) \
             VALUES ($1, $2, $3, $4) \
             ON CONFLICT (goal_id, year_month) DO UPDATE SET amount = EXCLUDED.amount",
        )
        .bind(goal_id)
        .bind(year_month)
        .bind(amount)
        .bind(member_id),
    };
    query.execute(db).await.map(drop)
}

pub async fn delete_month_amount(
    db: impl DbExec<'_>,
    table: MonthTable,
    goal_id: i64,
    year_month: &str,
) -> sqlx::Result<()> {
    crate::db::query(&format!(
        "DELETE FROM {} WHERE goal_id = $1 AND year_month = $2",
        table.name()
    ))
    .bind(goal_id)
    .bind(year_month)
    .execute(db)
    .await
    .map(drop)
}
