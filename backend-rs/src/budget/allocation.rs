//! AllocationService: the current stock by asset class (CURRENT / SAVINGS / INVESTMENT, OTHER left
//! out) valued in EUR as the dashboard does, and the money transferred into savings and
//! investment accounts over the period.

use std::collections::{BTreeMap, HashMap};

use axum::extract::State;
use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::Serialize;

use super::cycle::{self, CashflowPeriod};
use super::params::Params;
use super::repo::{self, Refs};
use super::{jdec, settings};
use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::ledger::accounts;
use crate::ledger::decimal::{HALF_UP, divide};
use crate::ledger::model::{Account, AssetClass};
use crate::ledger::valuation;
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes.get("/api/allocation", allocation);
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AllocationStock {
    pub asset_class: AssetClass,
    #[serde(with = "jdec")]
    pub amount: Decimal,
    #[serde(with = "jdec")]
    pub percent: Decimal,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AllocationContribution {
    pub account_id: i64,
    pub account_name: String,
    pub asset_class: AssetClass,
    pub color: String,
    #[serde(with = "jdec")]
    pub amount: Decimal,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AllocationResponse {
    pub period: CashflowPeriod,
    pub from: NaiveDate,
    pub to: NaiveDate,
    #[serde(with = "jdec")]
    pub total_stock: Decimal,
    pub stock: Vec<AllocationStock>,
    #[serde(with = "jdec")]
    pub total_contributions: Decimal,
    pub contributions: Vec<AllocationContribution>,
}

/// Values each account once, on demand: a skipped account is never valued.
struct Values<'a> {
    state: &'a AppState,
    today: NaiveDate,
    by_id: HashMap<i64, Decimal>,
}

impl Values<'_> {
    async fn eur(&mut self, account: &Account) -> AppResult<Decimal> {
        if let Some(value) = self.by_id.get(&account.id) {
            return Ok(*value);
        }
        let mut conn = self.state.db.acquire().await?;
        let value =
            valuation::live_balance_eur(&mut conn, &*self.state.ledger.prices, account, self.today)
                .await?;
        self.by_id.insert(account.id, value);
        Ok(value)
    }
}

fn merge(by_class: &mut BTreeMap<AssetClass, Decimal>, class: AssetClass, amount: Decimal) {
    match by_class.get_mut(&class) {
        Some(sum) => *sum += amount,
        None => {
            by_class.insert(class, amount);
        }
    }
}

/// BigDecimal.max: the receiver on a tie (Ord::max would return the argument, another scale).
fn java_max(a: Decimal, b: Decimal) -> Decimal {
    if a >= b { a } else { b }
}

/// BigDecimal.min: the receiver on a tie.
fn java_min(a: Decimal, b: Decimal) -> Decimal {
    if a <= b { a } else { b }
}

fn percent_of(amount: Decimal, total: Decimal) -> Decimal {
    if total.is_zero() {
        return Decimal::ZERO;
    }
    divide(amount * Decimal::ONE_HUNDRED, total, 2, HALF_UP)
}

async fn stock(state: &AppState, member_id: i64) -> AppResult<Vec<AllocationStock>> {
    let accounts = accounts::find_visible_by_member(&state.db, member_id).await?;
    let by_id: HashMap<i64, &Account> = accounts.iter().map(|a| (a.id, a)).collect();
    let mut values = Values {
        state,
        today: state.clock.today(),
        by_id: HashMap::new(),
    };
    let mut by_class: BTreeMap<AssetClass, Decimal> = BTreeMap::new();
    // What is left of each parent wallet after the sub-accounts taken out of it so far.
    let mut parent_left: HashMap<i64, Decimal> = HashMap::new();
    for account in &accounts {
        let class = AssetClass::of(account.account_type);
        if class == AssetClass::Other {
            continue;
        }
        let Some(parent_id) = account.parent_account_id else {
            let value = values.eur(account).await?;
            merge(&mut by_class, class, value);
            continue;
        };
        // A pocket or money-box: its wallet's balance already includes it. It moves from the
        // wallet's class to its own, between zero and what is left of the wallet, and only when
        // the wallet is listed, in the allocation, and of another class.
        let Some(parent) = by_id.get(&parent_id) else {
            continue;
        };
        let parent_class = AssetClass::of(parent.account_type);
        if parent_class == AssetClass::Other || parent_class == class {
            continue;
        }
        let left = match parent_left.get(&parent.id) {
            Some(left) => *left,
            None => values.eur(parent).await?,
        };
        let value = values.eur(account).await?;
        let moved = java_min(
            java_max(value, Decimal::ZERO),
            java_max(left, Decimal::ZERO),
        );
        parent_left.insert(parent.id, left - moved);
        merge(&mut by_class, parent_class, jdec::negate(moved));
        merge(&mut by_class, class, moved);
    }
    let total = by_class.values().fold(Decimal::ZERO, |acc, v| acc + *v);
    Ok(by_class
        .into_iter()
        .map(|(asset_class, amount)| AllocationStock {
            asset_class,
            amount,
            percent: percent_of(amount, total),
        })
        .collect())
}

async fn contributions(
    state: &AppState,
    member_id: i64,
    from: NaiveDate,
    to: NaiveDate,
) -> AppResult<Vec<AllocationContribution>> {
    let rows = repo::member_transfers_between(&state.db, member_id, from, to).await?;
    let refs = Refs::load(&state.db, &rows).await?;
    let mut by_account: Vec<AllocationContribution> = Vec::new();
    for tx in &rows {
        // Only money arriving in a savings or investment account counts; the matching outflow
        // from the current account is ignored.
        if tx.amount <= Decimal::ZERO {
            continue;
        }
        let account = refs.account(tx);
        let class = AssetClass::of(account.account_type);
        if !class.tracks_contributions() {
            continue;
        }
        match by_account.iter_mut().find(|c| c.account_id == account.id) {
            Some(existing) => existing.amount += tx.amount,
            None => by_account.push(AllocationContribution {
                account_id: account.id,
                account_name: account.name.clone(),
                asset_class: class,
                color: account.color.clone(),
                amount: tx.amount,
            }),
        }
    }
    by_account.sort_by_key(|c| std::cmp::Reverse(c.amount));
    Ok(by_account)
}

pub async fn compute(
    state: &AppState,
    member_id: i64,
    period: CashflowPeriod,
    today: NaiveDate,
) -> AppResult<AllocationResponse> {
    let day = settings::cycle_start_day(&state.db, member_id).await?;
    let (from, to) = cycle::range(period, today, day);
    let stock = stock(state, member_id).await?;
    let total_stock = stock.iter().fold(Decimal::ZERO, |acc, s| acc + s.amount);
    let contributions = contributions(state, member_id, from, to).await?;
    let total_contributions = contributions
        .iter()
        .fold(Decimal::ZERO, |acc, c| acc + c.amount);
    Ok(AllocationResponse {
        period,
        from,
        to,
        total_stock,
        stock,
        total_contributions,
        contributions,
    })
}

async fn allocation(
    State(state): State<AppState>,
    auth: Auth,
    params: Params,
) -> AppResult<axum::Json<AllocationResponse>> {
    let (period, anchor) = params.period_and_anchor(state.clock.today())?;
    Ok(axum::Json(
        compute(&state, auth.member_id, period, anchor).await?,
    ))
}
