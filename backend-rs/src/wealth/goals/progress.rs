//! GoalService.toProgressResponse and the month figures it is built from.

use std::collections::HashMap;

use chrono::{Datelike, Months, NaiveDate};
use chrono_tz::Tz;
use rust_decimal::Decimal;
use sqlx::PgConnection;

use super::dto::{GoalAllocationResponse, GoalProgressResponse, GoalType};
use super::months::{YearMonth, months_between};
use super::repo::{self, Goal, MonthTable};
use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::ledger::decimal::{HALF_UP, divide, set_scale};
use crate::ledger::model::AccountType;
use crate::ledger::prices::PriceRead;
use crate::ledger::{access, holdings, snapshots, valuation};

/// What every figure is relative to: the prices, today and the zone a creation instant is read in.
pub struct Ctx<'a> {
    pub prices: &'a dyn PriceRead,
    pub today: NaiveDate,
    pub zone: Tz,
}

impl<'a> Ctx<'a> {
    pub fn of(state: &'a AppState) -> Self {
        Self {
            prices: &*state.ledger.prices,
            today: state.clock.today(),
            zone: state.clock.zone(),
        }
    }

    fn current_month(&self) -> YearMonth {
        YearMonth::of(self.today)
    }

    pub fn created_month(&self, goal: &Goal) -> YearMonth {
        YearMonth::of(goal.created_at.with_timezone(&self.zone).date_naive())
    }
}

/// The savings-target figures of a goal.
pub struct Figures {
    pub current_total: Decimal,
    pub percent_complete: Decimal,
    pub months_left: i64,
    pub monthly_needed: Decimal,
    pub avg_monthly_contribution: Option<Decimal>,
    pub is_on_track: bool,
    pub surplus: Decimal,
}

/// Signed live balances (a LOAN counts against the goal), weighted by the goal owner's share.
async fn current_total(
    conn: &mut PgConnection,
    ctx: &Ctx<'_>,
    goal: &Goal,
) -> sqlx::Result<Decimal> {
    let shares = access::shares_for(&mut *conn, &goal.accounts, goal.member_id).await?;
    let mut total = Decimal::ZERO;
    for account in &goal.accounts {
        let live =
            valuation::signed_live_balance_eur(&mut *conn, ctx.prices, account, ctx.today).await?;
        let share = shares.get(&account.id).copied().unwrap_or_default();
        total += access::weigh(live, share);
    }
    Ok(total)
}

fn require_target(goal: &Goal) -> AppResult<(Decimal, NaiveDate)> {
    match (goal.goal_type, goal.target_amount, goal.deadline) {
        (GoalType::SavingsTarget, Some(target), Some(deadline)) => Ok((target, deadline)),
        _ => Err(not_a_savings_target()),
    }
}

/// requireSavingsTarget: the calendar, the backfills and the month amounts need a deadline.
pub fn not_a_savings_target() -> AppError {
    AppError::BadRequest(
        "This operation applies to savings goals, not to a recurring investment plan".to_owned(),
    )
}

pub async fn figures(conn: &mut PgConnection, ctx: &Ctx<'_>, goal: &Goal) -> AppResult<Figures> {
    let (target, deadline) = require_target(goal)?;
    let current_total = current_total(&mut *conn, ctx, goal).await?;
    let months_left = months_between(ctx.today, deadline).max(0);
    let needed = target - current_total;
    let monthly_needed = if months_left > 0 {
        divide(needed, Decimal::from(months_left), 2, HALF_UP)
    } else {
        needed
    };
    let percent_complete = if target > Decimal::ZERO {
        divide(current_total, target, 4, HALF_UP) * Decimal::ONE_HUNDRED
    } else {
        Decimal::ZERO
    };
    let avg_monthly_contribution = avg_monthly_contribution(&mut *conn, ctx, goal).await?;
    let is_on_track = is_on_track(&mut *conn, ctx, goal, monthly_needed).await?;
    let surplus = avg_monthly_contribution.map_or(Decimal::ZERO, |avg| avg - monthly_needed);
    Ok(Figures {
        current_total,
        percent_complete,
        months_left,
        monthly_needed,
        avg_monthly_contribution,
        is_on_track,
        surplus,
    })
}

/// The month's objective before any override: (target - current) / months left.
pub async fn monthly_needed(
    conn: &mut PgConnection,
    ctx: &Ctx<'_>,
    goal: &Goal,
) -> AppResult<Decimal> {
    Ok(figures(conn, ctx, goal).await?.monthly_needed)
}

/// Snapshot deltas over the last three months, summed over the accounts; else the mean of the
/// manual contributions; None when neither has data.
async fn avg_monthly_contribution(
    conn: &mut PgConnection,
    ctx: &Ctx<'_>,
    goal: &Goal,
) -> sqlx::Result<Option<Decimal>> {
    let since = ctx
        .today
        .checked_sub_months(Months::new(3))
        .and_then(|d| d.with_day0(0))
        .expect("a representable date");
    let mut total = Decimal::ZERO;
    let mut with_data = 0;
    for account in &goal.accounts {
        let rows = snapshots::find_recent_by_account(&mut *conn, account.id, since).await?;
        if let (Some(first), Some(last)) = (rows.first(), rows.last())
            && rows.len() >= 2
        {
            let months = months_between(first.date, last.date).max(1);
            let mut delta = last.balance - first.balance;
            if account.account_type == AccountType::Loan {
                delta = -delta;
            }
            total += divide(delta, Decimal::from(months), 2, HALF_UP);
            with_data += 1;
        }
    }
    if with_data > 0 {
        return Ok(Some(total));
    }
    let manuals = repo::month_amounts(&mut *conn, MonthTable::Manual, goal.id).await?;
    if manuals.is_empty() {
        return Ok(None);
    }
    let sum: Decimal = manuals.values().sum();
    Ok(Some(divide(sum, Decimal::from(manuals.len()), 2, HALF_UP)))
}

/// Σ effective >= Σ objective over the months from creation to the current one (excluded);
/// months without data are skipped, and no data at all is the benefit of the doubt.
async fn is_on_track(
    conn: &mut PgConnection,
    ctx: &Ctx<'_>,
    goal: &Goal,
    monthly_needed: Decimal,
) -> sqlx::Result<bool> {
    if monthly_needed <= Decimal::ZERO {
        return Ok(true);
    }
    let current = ctx.current_month();
    let mut cursor = ctx.created_month(goal);
    if cursor >= current {
        return Ok(true);
    }
    let overrides = repo::month_amounts(&mut *conn, MonthTable::Override, goal.id).await?;
    let manuals = repo::month_amounts(&mut *conn, MonthTable::Manual, goal.id).await?;
    let mut objective = Decimal::ZERO;
    let mut effective = Decimal::ZERO;
    let mut any = false;
    while cursor < current {
        let ym = cursor.to_string();
        let actual = match manuals.get(&ym) {
            Some(manual) => Some(*manual),
            None => actual_for_month(&mut *conn, ctx, goal, cursor).await?,
        };
        if let Some(actual) = actual {
            objective += overrides.get(&ym).copied().unwrap_or(monthly_needed);
            effective += actual;
            any = true;
        }
        cursor = cursor.plus_months(1);
    }
    Ok(!any || effective >= objective)
}

/// End-of-month balance minus end-of-previous-month balance, summed over the accounts that have
/// both (a LOAN paid down counts as progress); None for a future month or without data.
pub async fn actual_for_month(
    conn: &mut PgConnection,
    ctx: &Ctx<'_>,
    goal: &Goal,
    ym: YearMonth,
) -> sqlx::Result<Option<Decimal>> {
    if ym > ctx.current_month() {
        return Ok(None);
    }
    let previous_end = ym.plus_months(-1).at_end_of_month();
    let this_end = ym.at_end_of_month();
    let mut total = Decimal::ZERO;
    let mut any = false;
    for account in &goal.accounts {
        let previous =
            snapshots::find_latest_on_or_before(&mut *conn, account.id, previous_end, None).await?;
        let current =
            snapshots::find_latest_on_or_before(&mut *conn, account.id, this_end, None).await?;
        if let (Some(previous), Some(current)) = (previous, current) {
            let mut delta = current.balance - previous.balance;
            if account.account_type == AccountType::Loan {
                delta = -delta;
            }
            total += delta;
            any = true;
        }
    }
    Ok(any.then(|| set_scale(total, 2, HALF_UP)))
}

async fn allocation_responses(
    conn: &mut PgConnection,
    goal: &Goal,
) -> sqlx::Result<Vec<GoalAllocationResponse>> {
    if goal.allocations.is_empty() {
        return Ok(Vec::new());
    }
    let mut names: HashMap<String, Option<String>> = HashMap::new();
    if let Some(account) = goal.accounts.first() {
        for holding in holdings::find_by_account(&mut *conn, account.id).await? {
            names.insert(holding.ticker, holding.name);
        }
    }
    Ok(goal
        .allocations
        .iter()
        .map(|a| GoalAllocationResponse {
            ticker: a.ticker.clone(),
            name: names.get(&a.ticker).cloned().flatten(),
            monthly_amount: a.monthly_amount,
        })
        .collect())
}

/// GoalService.toProgressResponse: branches on the type, since a recurring plan has no target.
pub async fn to_response(
    state: &AppState,
    conn: &mut PgConnection,
    goal: &Goal,
) -> AppResult<GoalProgressResponse> {
    let responder = state.wealth.savings.account_responses()?;
    let mut accounts = Vec::with_capacity(goal.accounts.len());
    for account in &goal.accounts {
        accounts.push(responder.to_response(state, account).await?);
    }
    let ctx = Ctx::of(state);
    let mut response = GoalProgressResponse {
        id: goal.id,
        name: goal.name.clone(),
        goal_type: goal.goal_type,
        target_amount: None,
        deadline: None,
        created_at: goal.created_at,
        history_start_month: goal.history_start_month.clone(),
        accounts,
        current_total: Decimal::ZERO,
        percent_complete: None,
        months_left: 0,
        monthly_needed: None,
        avg_monthly_contribution: None,
        is_on_track: true,
        surplus: None,
        monthly_amount: goal.monthly_amount,
        expected_return: goal.expected_return,
        start_date: goal.start_date,
        end_date: goal.end_date,
        allocations: Vec::new(),
    };
    match goal.goal_type {
        GoalType::RecurringInvestment => {
            response.current_total = current_total(&mut *conn, &ctx, goal).await?;
            response.allocations = allocation_responses(&mut *conn, goal).await?;
        }
        GoalType::SavingsTarget => {
            let f = figures(&mut *conn, &ctx, goal).await?;
            response.target_amount = goal.target_amount;
            response.deadline = goal.deadline;
            response.current_total = f.current_total;
            response.percent_complete = Some(f.percent_complete);
            response.months_left = f.months_left;
            response.monthly_needed = Some(f.monthly_needed);
            response.avg_monthly_contribution = f.avg_monthly_contribution;
            response.is_on_track = f.is_on_track;
            response.surplus = Some(f.surplus);
        }
    }
    Ok(response)
}
