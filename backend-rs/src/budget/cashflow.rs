//! CashflowService: income, expense and net over the current cycle or the year to date, split by
//! sign (uncategorized rows count) and bucketed per pay cycle, transfers left out.

use axum::extract::State;
use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::Serialize;

use super::cycle::{self, CashflowPeriod, CycleRange};
use super::params::Params;
use super::repo::{self, Refs};
use super::{jdec, settings};
use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::ledger::model::{CategoryKind, Transaction};
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes.get("/api/cashflow", cashflow);
}

/// A row categorized as TRANSFER: money moved between the member's own accounts.
pub fn is_transfer(refs: &Refs, tx: &Transaction) -> bool {
    refs.category(tx)
        .is_some_and(|c| c.kind == CategoryKind::Transfer)
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CashflowBucket {
    pub start: NaiveDate,
    pub end: NaiveDate,
    pub label: String,
    #[serde(with = "jdec")]
    pub income: Decimal,
    #[serde(with = "jdec")]
    pub expense: Decimal,
    #[serde(with = "jdec")]
    pub net: Decimal,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CashflowResponse {
    pub period: CashflowPeriod,
    pub from: NaiveDate,
    pub to: NaiveDate,
    #[serde(with = "jdec")]
    pub income: Decimal,
    #[serde(with = "jdec")]
    pub expense: Decimal,
    #[serde(with = "jdec")]
    pub net: Decimal,
    pub series: Vec<CashflowBucket>,
}

struct Accumulator {
    cycle: CycleRange,
    income: Decimal,
    expense: Decimal,
}

pub async fn compute(
    state: &AppState,
    member_id: i64,
    period: CashflowPeriod,
    today: NaiveDate,
) -> AppResult<CashflowResponse> {
    let day = settings::cycle_start_day(&state.db, member_id).await?;
    let (from, to) = cycle::range(period, today, day);
    let mut buckets: Vec<Accumulator> = cycle::cycles_between(from, to, day)
        .into_iter()
        .map(|cycle| Accumulator {
            cycle,
            income: Decimal::ZERO,
            expense: Decimal::ZERO,
        })
        .collect();
    let rows = repo::member_between(&state.db, member_id, from, to).await?;
    let refs = Refs::load(&state.db, &rows).await?;
    for tx in rows
        .iter()
        .filter(|tx| refs.account(tx).is_some() && !is_transfer(&refs, tx))
    {
        let cycle = cycle::cycle_for(tx.date, day);
        let index = match buckets.iter().position(|b| b.cycle.start == cycle.start) {
            Some(i) => i,
            None => {
                buckets.push(Accumulator {
                    cycle,
                    income: Decimal::ZERO,
                    expense: Decimal::ZERO,
                });
                buckets.len() - 1
            }
        };
        let bucket = &mut buckets[index];
        if tx.amount.is_sign_negative() && !tx.amount.is_zero() {
            bucket.expense += jdec::negate(tx.amount);
        } else {
            bucket.income += tx.amount;
        }
    }
    let mut income = Decimal::ZERO;
    let mut expense = Decimal::ZERO;
    let series = buckets
        .iter()
        .map(|b| {
            income += b.income;
            expense += b.expense;
            CashflowBucket {
                start: b.cycle.start,
                end: b.cycle.end,
                label: b.cycle.start.format("%Y-%m").to_string(),
                income: b.income,
                expense: b.expense,
                net: b.income - b.expense,
            }
        })
        .collect();
    Ok(CashflowResponse {
        period,
        from,
        to,
        income,
        expense,
        net: income - expense,
        series,
    })
}

async fn cashflow(
    State(state): State<AppState>,
    auth: Auth,
    params: Params,
) -> AppResult<axum::Json<CashflowResponse>> {
    let (period, anchor) = params.period_and_anchor(state.clock.today())?;
    Ok(axum::Json(
        compute(&state, auth.member_id, period, anchor).await?,
    ))
}
