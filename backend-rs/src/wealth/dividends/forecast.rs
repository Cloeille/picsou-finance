//! DividendForecastService: the payments expected over a horizon, from each holding's stored
//! dividend history (declared events, then the cadence projected forward) and each SCPI's
//! declared distribution.

use std::collections::{HashMap, HashSet};

use chrono::{Datelike, Days, Months, NaiveDate};
use rust_decimal::Decimal;
use serde::Serialize;

use super::model::{Cadence, DividendEvent, sources_by_ticker_on};
use crate::AppState;
use crate::db::{AsDbHandle, DbFromRow, DbHandle};
use crate::error::AppResult;
use crate::ledger::access;
use crate::ledger::decimal::{HALF_UP, divide};
use crate::ledger::holdings;
use crate::ledger::model::{Account, AccountType};

pub const MIN_HORIZON_DAYS: i32 = 1;
pub const MAX_HORIZON_DAYS: i32 = 366;
const MONEY_SCALE: u32 = 8;
const PAYMENT_LAG_WINDOW_DAYS: i64 = 60;
const CASH_LAG_LOOKBACK_MONTHS: u32 = 24;
/// A projected date this close to an announced one is that payment, not another.
const ANNOUNCED_REPLACES_WITHIN_DAYS: i64 = 20;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Origin {
    Holding,
    Scpi,
}

impl Origin {
    fn as_str(self) -> &'static str {
        match self {
            Self::Holding => "HOLDING",
            Self::Scpi => "SCPI",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Basis {
    HistoryCadence,
    Announced,
    ScpiDeclared,
}

/// UpcomingDividendResponse.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpcomingDividendResponse {
    pub id: String,
    pub source: Origin,
    pub account_id: i64,
    pub account_name: String,
    pub ticker: Option<String>,
    pub name: String,
    pub expected_date: NaiveDate,
    pub cadence: Option<Cadence>,
    pub basis: Basis,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub quantity: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub amount_per_share: Decimal,
    pub currency: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub amount_eur: Decimal,
    pub reinvested: bool,
    pub account_type: AccountType,
    pub counts_in_budget: bool,
    pub ex_date: Option<NaiveDate>,
    pub payment_date: Option<NaiveDate>,
    pub payment_date_estimated: bool,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub amount_per_share_eur: Decimal,
}

pub fn clamp_horizon(days: i32) -> i32 {
    days.clamp(MIN_HORIZON_DAYS, MAX_HORIZON_DAYS)
}

pub fn counts_in_budget(account_type: AccountType, reinvested: bool) -> bool {
    !reinvested && !account_type.is_locked_wrapper()
}

pub fn id(origin: Origin, account_id: i64, key: Option<&str>, date: NaiveDate) -> String {
    let key = key.filter(|k| !k.trim().is_empty()).unwrap_or("-");
    format!("{}:{account_id}:{key}:{date}", origin.as_str())
}

/// upcoming(memberId, today, horizon, accountId): an unreadable account id is the usual 404.
pub async fn upcoming(
    state: &AppState,
    member_id: i64,
    today: NaiveDate,
    horizon_days: i32,
    account_id: Option<i64>,
) -> AppResult<Vec<UpcomingDividendResponse>> {
    let mut conn = state.db.acquire().await?;
    if let Some(id) = account_id {
        access::require_readable(&mut conn.as_handle(), id, member_id).await?;
    }
    let end = today + Days::new(clamp_horizon(horizon_days) as u64);
    let readable = access::readable_accounts(&mut conn.as_handle(), member_id).await?;
    let scoped: Vec<Account> = readable
        .iter()
        .filter(|a| account_id.is_none_or(|id| a.id == id))
        .cloned()
        .collect();
    if scoped.is_empty() {
        return Ok(Vec::new());
    }
    let shares = access::shares_for(&mut conn, &scoped, member_id).await?;
    let mut lines = holding_lines(
        &mut conn.as_handle(),
        &scoped,
        &readable,
        &shares,
        today,
        end,
    )
    .await?;
    lines.extend(scpi_lines(&mut conn.as_handle(), &scoped, &shares, today, end).await?);
    lines.sort_by(|a, b| {
        a.expected_date
            .cmp(&b.expected_date)
            .then_with(|| a.account_name.cmp(&b.account_name))
            .then_with(|| match (&a.ticker, &b.ticker) {
                (Some(x), Some(y)) => x.cmp(y),
                (Some(_), None) => std::cmp::Ordering::Less,
                (None, Some(_)) => std::cmp::Ordering::Greater,
                (None, None) => std::cmp::Ordering::Equal,
            })
    });
    Ok(lines)
}

fn share_of(shares: &HashMap<i64, Decimal>, id: i64) -> Decimal {
    shares.get(&id).copied().unwrap_or(Decimal::ONE_HUNDRED)
}

async fn holding_lines(
    conn: &mut DbHandle<'_>,
    accounts: &[Account],
    lag_accounts: &[Account],
    shares: &HashMap<i64, Decimal>,
    today: NaiveDate,
    end: NaiveDate,
) -> AppResult<Vec<UpcomingDividendResponse>> {
    let investment: HashMap<i64, &Account> = accounts
        .iter()
        .filter(|a| {
            a.account_type != AccountType::Crypto
                && (a.account_type.is_investment()
                    || a.account_type == AccountType::EmployeeSavings)
        })
        .map(|a| (a.id, a))
        .collect();
    if investment.is_empty() {
        return Ok(Vec::new());
    }
    let ids: Vec<i64> = investment.keys().copied().collect();
    let held: Vec<_> = holdings::find_positive_by_accounts(&mut *conn, &ids)
        .await?
        .into_iter()
        .filter(|h| h.quantity > Decimal::ZERO && !h.ticker.trim().is_empty())
        .collect();
    if held.is_empty() {
        return Ok(Vec::new());
    }
    let tickers: Vec<String> = held.iter().map(|h| h.ticker.clone()).collect();
    let by_ticker = sources_by_ticker_on(&mut *conn, &tickers).await?;
    let cash_dates = dividend_cash_dates(&mut conn.as_handle(), lag_accounts, today).await?;

    let mut lines = Vec::new();
    for holding in &held {
        let Some(source) = by_ticker.get(&holding.ticker.to_uppercase()) else {
            continue;
        };
        let Some(account) = investment.get(&holding.account_id) else {
            continue;
        };
        let dates = dates_in_window(&source.events, today);
        let cadence = cadence_of(&dates);
        let mut last = None;
        if let (Some(cadence), Some(last_date)) = (cadence, dates.last())
            && !is_stale(*last_date, cadence, today)
        {
            last = source.events.iter().find(|e| e.ex_date == *last_date);
        }
        let payable = payable_in_horizon(&source.events, today, end);
        if last.is_none() && payable.is_empty() {
            continue;
        }
        let share = share_of(shares, account.id);
        let name = holding
            .name
            .clone()
            .filter(|n| !n.trim().is_empty())
            .unwrap_or_else(|| holding.ticker.clone());
        let lag = payment_lag_days(account.id, &holding.ticker, &source.events, &cash_dates);
        let currency = source.currency.clone().unwrap_or_else(|| "EUR".to_owned());
        let line = |date: NaiveDate,
                    basis,
                    per_share: Decimal,
                    per_share_eur: Decimal,
                    ex,
                    payment,
                    estimated| {
            UpcomingDividendResponse {
                id: id(Origin::Holding, account.id, Some(&holding.ticker), date),
                source: Origin::Holding,
                account_id: account.id,
                account_name: account.name.clone(),
                ticker: Some(holding.ticker.clone()),
                name: name.clone(),
                expected_date: date,
                cadence,
                basis,
                quantity: holding.quantity,
                amount_per_share: per_share,
                currency: currency.clone(),
                amount_eur: access::weigh(holding.quantity * per_share_eur, share),
                reinvested: false,
                account_type: account.account_type,
                counts_in_budget: counts_in_budget(account.account_type, false),
                ex_date: ex,
                payment_date: payment,
                payment_date_estimated: estimated,
                amount_per_share_eur: per_share_eur,
            }
        };
        for declared in &payable {
            if declared.amount_eur < Decimal::ZERO {
                continue;
            }
            let mut payment = declared.payment_date;
            let mut estimated = false;
            if payment.is_none()
                && let Some(lag) = lag
            {
                payment = Some(declared.ex_date + Days::new(lag as u64));
                estimated = true;
            }
            lines.push(line(
                declared.ex_date,
                Basis::Announced,
                declared.amount,
                declared.amount_eur,
                Some(declared.ex_date),
                payment,
                estimated,
            ));
        }
        let (Some(last), Some(cadence)) = (last, cadence) else {
            continue;
        };
        if last.amount_eur < Decimal::ZERO {
            continue;
        }
        let last_date = last.ex_date;
        let announced = future_announced_ex_dates(&source.events, today);
        for k in 1..=24u32 {
            let Some(projected) = last_date.checked_add_months(Months::new(cadence.months() * k))
            else {
                break;
            };
            let paid = lag.map(|l| projected + Days::new(l as u64));
            if projected > end && paid.is_none_or(|p| p > end) {
                break;
            }
            if projected < today || near_announced(projected, &announced) {
                continue;
            }
            if !in_horizon(projected, paid, today, end) {
                continue;
            }
            lines.push(line(
                projected,
                Basis::HistoryCadence,
                last.amount,
                last.amount_eur,
                Some(projected),
                paid,
                lag.is_some(),
            ));
        }
    }
    Ok(lines)
}

#[derive(DbFromRow)]
struct ScpiRow {
    account_id: i64,
    isin: Option<String>,
    share_count: Decimal,
    dividend_policy: String,
    jouissance_date: Option<NaiveDate>,
    annual_distribution_per_share_eur: Option<Decimal>,
    distribution_frequency: Option<String>,
}

async fn scpi_lines(
    conn: &mut DbHandle<'_>,
    accounts: &[Account],
    shares: &HashMap<i64, Decimal>,
    today: NaiveDate,
    end: NaiveDate,
) -> AppResult<Vec<UpcomingDividendResponse>> {
    let scpi: HashMap<i64, &Account> = accounts
        .iter()
        .filter(|a| a.account_type == AccountType::Scpi)
        .map(|a| (a.id, a))
        .collect();
    if scpi.is_empty() {
        return Ok(Vec::new());
    }
    let ids: Vec<i64> = scpi.keys().copied().collect();
    let rows: Vec<ScpiRow> = crate::db::query_as::<ScpiRow>(
        "SELECT account_id, isin, share_count, dividend_policy, jouissance_date, \
         annual_distribution_per_share_eur, distribution_frequency FROM scpi_position \
         WHERE account_id = ANY($1) ORDER BY id",
    )
    .bind(&ids)
    .fetch_all(&mut *conn)
    .await?;
    let mut lines = Vec::new();
    for position in rows {
        let (Some(annual), Some(frequency)) = (
            position
                .annual_distribution_per_share_eur
                .filter(|a| *a > Decimal::ZERO),
            position.distribution_frequency.as_deref(),
        ) else {
            continue;
        };
        if position.share_count <= Decimal::ZERO {
            continue;
        }
        let Some(account) = scpi.get(&position.account_id) else {
            continue;
        };
        let monthly = frequency == "MONTHLY";
        let cadence = if monthly {
            Cadence::Monthly
        } else {
            Cadence::Quarterly
        };
        let periods = if monthly { 12 } else { 4 };
        let per_payment = divide(annual, Decimal::from(periods), MONEY_SCALE, HALF_UP);
        let reinvested = position.dividend_policy == "REINVEST";
        let amount_eur = access::weigh(
            position.share_count * per_payment,
            share_of(shares, account.id),
        );
        let ticker = account.ticker.clone().filter(|t| !t.trim().is_empty());
        let key = ticker
            .clone()
            .or_else(|| position.isin.clone().filter(|i| !i.trim().is_empty()));
        let mut month = today.with_day(1).unwrap_or(today);
        let last_month = end.with_day(1).unwrap_or(end);
        while month <= last_month {
            let pays = monthly || matches!(month.month(), 1 | 4 | 7 | 10);
            let payment = month.with_day(25).unwrap_or(month);
            let period_end = month.pred_opt().unwrap_or(month);
            let in_window = payment >= today && payment <= end;
            let enjoyed = position.jouissance_date.is_none_or(|j| period_end >= j);
            if pays && in_window && enjoyed {
                lines.push(UpcomingDividendResponse {
                    id: id(Origin::Scpi, account.id, key.as_deref(), payment),
                    source: Origin::Scpi,
                    account_id: account.id,
                    account_name: account.name.clone(),
                    ticker: ticker.clone(),
                    name: account.name.clone(),
                    expected_date: payment,
                    cadence: Some(cadence),
                    basis: Basis::ScpiDeclared,
                    quantity: position.share_count,
                    amount_per_share: per_payment,
                    currency: "EUR".to_owned(),
                    amount_eur,
                    reinvested,
                    account_type: account.account_type,
                    counts_in_budget: counts_in_budget(account.account_type, reinvested),
                    ex_date: None,
                    payment_date: Some(payment),
                    payment_date_estimated: true,
                    amount_per_share_eur: per_payment,
                });
            }
            let Some(next) = month.checked_add_months(Months::new(1)) else {
                break;
            };
            month = next;
        }
    }
    Ok(lines)
}

fn dates_in_window(events: &[DividendEvent], today: NaiveDate) -> Vec<NaiveDate> {
    let start = today.checked_sub_months(Months::new(24)).unwrap_or(today);
    let mut dates: Vec<NaiveDate> = events
        .iter()
        .map(|e| e.ex_date)
        .filter(|d| *d >= start && *d <= today)
        .collect::<HashSet<_>>()
        .into_iter()
        .collect();
    dates.sort();
    dates
}

/// The fastest cadence every gap between consecutive ex-dates fits, None for an irregular payer.
pub fn cadence_of(dates: &[NaiveDate]) -> Option<Cadence> {
    if dates.len() < 2 {
        return None;
    }
    let gaps: Vec<i64> = dates.windows(2).map(|w| (w[1] - w[0]).num_days()).collect();
    Cadence::ALL
        .into_iter()
        .find(|c| dates.len() >= c.min_events() && gaps.iter().all(|g| c.accepts_gap(*g)))
}

/// A payer silent for a cadence and a half has stopped: no projection.
pub fn is_stale(last_ex_date: NaiveDate, cadence: Cadence, today: NaiveDate) -> bool {
    let limit = last_ex_date
        .checked_add_months(Months::new(cadence.months()))
        .map(|d| d + Days::new(u64::from(cadence.months()) * 15));
    limit.is_some_and(|l| l < today)
}

fn payable_in_horizon(
    events: &[DividendEvent],
    today: NaiveDate,
    end: NaiveDate,
) -> Vec<DividendEvent> {
    events
        .iter()
        .filter(|e| e.declared || e.ex_date > today || e.payment_date.is_some())
        .filter(|e| in_horizon(e.ex_date, e.payment_date, today, end))
        .cloned()
        .collect()
}

pub fn in_horizon(
    ex_date: NaiveDate,
    payment: Option<NaiveDate>,
    today: NaiveDate,
    end: NaiveDate,
) -> bool {
    let decisive = payment.unwrap_or(ex_date);
    decisive >= today && decisive <= end
}

fn future_announced_ex_dates(events: &[DividendEvent], today: NaiveDate) -> Vec<NaiveDate> {
    events
        .iter()
        .filter(|e| e.declared && e.ex_date >= today)
        .map(|e| e.ex_date)
        .collect()
}

fn near_announced(projected: NaiveDate, announced: &[NaiveDate]) -> bool {
    announced
        .iter()
        .any(|a| (projected - *a).num_days().abs() <= ANNOUNCED_REPLACES_WITHIN_DAYS)
}

/// A DIVIDEND row of a readable account: (account, ticker, date).
type CashDate = (i64, String, NaiveDate);

async fn dividend_cash_dates(
    conn: &mut DbHandle<'_>,
    accounts: &[Account],
    today: NaiveDate,
) -> AppResult<Vec<CashDate>> {
    let ids: Vec<i64> = accounts.iter().map(|a| a.id).collect();
    if ids.is_empty() {
        return Ok(Vec::new());
    }
    let since = today
        .checked_sub_months(Months::new(CASH_LAG_LOOKBACK_MONTHS))
        .unwrap_or(today);
    Ok(crate::db::query_as::<CashDate>(
        "SELECT account_id, ticker, date FROM \"transaction\" WHERE account_id = ANY($1) \
         AND tx_type = 'DIVIDEND' AND ticker IS NOT NULL AND date >= $2",
    )
    .bind(&ids)
    .bind(since)
    .fetch_all(conn)
    .await?)
}

/// Days from ex-date to payment: the stored payment dates' median, else the median lag of the
/// account's own cash rows, else any readable account's.
fn payment_lag_days(
    account_id: i64,
    ticker: &str,
    events: &[DividendEvent],
    cash: &[CashDate],
) -> Option<i64> {
    let stored: Vec<i64> = events
        .iter()
        .filter_map(|e| e.payment_date.map(|p| (p - e.ex_date).num_days()))
        .filter(|d| (0..=PAYMENT_LAG_WINDOW_DAYS).contains(d))
        .collect();
    if let Some(lag) = median(stored) {
        return Some(lag);
    }
    let mut ex_dates: Vec<NaiveDate> = events
        .iter()
        .map(|e| e.ex_date)
        .collect::<HashSet<_>>()
        .into_iter()
        .collect();
    ex_dates.sort();
    if ex_dates.is_empty() || cash.is_empty() {
        return None;
    }
    let paired = |same_account: bool| {
        cash.iter()
            .filter(|(a, t, _)| {
                t.eq_ignore_ascii_case(ticker) && (!same_account || *a == account_id)
            })
            .filter_map(|(_, _, date)| {
                let matched = ex_dates.iter().take_while(|e| *e <= date).last()?;
                let days = (*date - *matched).num_days();
                (days <= PAYMENT_LAG_WINDOW_DAYS).then_some(days)
            })
            .collect::<Vec<_>>()
    };
    median(paired(true)).or_else(|| median(paired(false)))
}

fn median(mut lags: Vec<i64>) -> Option<i64> {
    if lags.is_empty() {
        return None;
    }
    lags.sort();
    Some(lags[(lags.len() - 1) / 2])
}
