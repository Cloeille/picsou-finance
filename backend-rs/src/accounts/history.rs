//! HistoryService: the net-worth series, its intraday counterpart and the P&L over a set of
//! accounts, each account weighted by the viewer's share.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};

use crate::db::DbHandle;
use axum::extract::{RawQuery, State};
use chrono::{Duration, Months, NaiveDate, NaiveDateTime, Timelike};
use rust_decimal::{Decimal, RoundingStrategy};
use serde::Serialize;

use super::jdecimal;
use super::query::Params;
use super::scale::column;
use super::scale::{add, sub};
use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::ledger::decimal::divide;
use crate::ledger::model::{Account, AccountHolding, AccountType, BalanceSnapshot};
use crate::ledger::{access, accounts, holdings, prices, providers, snapshots, valuation};

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountPoint {
    #[serde(with = "jdecimal")]
    pub total: Decimal,
    #[serde(with = "jdecimal::option")]
    pub invested: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub pnl: Option<Decimal>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NetWorthPoint {
    pub date: NaiveDate,
    #[serde(with = "jdecimal")]
    pub total: Decimal,
    #[serde(with = "jdecimal::option")]
    pub invested: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub pnl: Option<Decimal>,
    pub accounts: Option<HashMap<i64, AccountPoint>>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NetWorthIntradayPoint {
    pub timestamp: NaiveDateTime,
    #[serde(with = "jdecimal")]
    pub total: Decimal,
    #[serde(with = "jdecimal::option")]
    pub invested: Option<Decimal>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PnlResponse {
    #[serde(with = "jdecimal")]
    pub total: Decimal,
    #[serde(with = "jdecimal::option")]
    pub invested: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub pnl: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub pnl_percent: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub value_at_from: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub range_pnl: Option<Decimal>,
    #[serde(with = "jdecimal::option")]
    pub range_pnl_percent: Option<Decimal>,
}

const HUNDRED: Decimal = Decimal::from_parts(100, 0, 0, false, 0);

/// LocalDate.minusMonths, which also takes a negative count.
pub fn minus_months(date: NaiveDate, months: i32) -> NaiveDate {
    let n = Months::new(months.unsigned_abs());
    if months >= 0 {
        date.checked_sub_months(n).unwrap_or(NaiveDate::MIN)
    } else {
        date.checked_add_months(n).unwrap_or(NaiveDate::MAX)
    }
}

type Shares = HashMap<i64, Decimal>;

fn weigh(amount: Decimal, shares: &Shares, account_id: i64) -> Decimal {
    access::weigh(
        amount,
        shares.get(&account_id).copied().unwrap_or(Decimal::ZERO),
    )
}

fn share_of(shares: &Shares, account_id: i64) -> Decimal {
    shares.get(&account_id).copied().unwrap_or(Decimal::ZERO)
}

/// assertReadable: a 404 for any account the viewer neither owns nor holds a share of.
async fn assert_readable(
    conn: &mut DbHandle<'_>,
    list: &[Account],
    member_id: i64,
) -> AppResult<Shares> {
    let shares = access::shares_for(&mut *conn, list, member_id).await?;
    for account in list {
        if share_of(&shares, account.id) <= Decimal::ZERO && account.member_id != member_id {
            return Err(accounts::account_not_found());
        }
    }
    Ok(shares)
}

/// hasCompleteCostBasis: every line carries a cost basis and no cash pocket hides an unknown one.
fn has_complete_cost_basis(account: &Account, lines: &[AccountHolding]) -> bool {
    !lines.is_empty()
        && account.cash_balance.is_none_or(|c| c <= Decimal::ZERO)
        && lines.iter().all(|h| {
            h.average_buy_in.is_some()
                || (h.provider_value_eur.is_some() && h.provider_pnl_eur.is_some())
        })
}

struct ForwardFill {
    dates: BTreeSet<NaiveDate>,
    balance: HashMap<i64, BTreeMap<NaiveDate, Decimal>>,
    invested: HashMap<i64, BTreeMap<NaiveDate, Decimal>>,
}

async fn forward_fill(
    conn: &mut DbHandle<'_>,
    from: NaiveDate,
    list: &[Account],
) -> sqlx::Result<ForwardFill> {
    let ids: Vec<i64> = list.iter().map(|a| a.id).collect();
    let mut fill = ForwardFill {
        dates: BTreeSet::new(),
        balance: HashMap::new(),
        invested: HashMap::new(),
    };
    for row in snapshots::forward_fill_data(&mut *conn, from, Some(&ids)).await? {
        fill.balance
            .entry(row.account_id)
            .or_default()
            .insert(row.date, column(row.balance, 8));
        fill.invested
            .entry(row.account_id)
            .or_default()
            .insert(row.date, column(row.invested_amount, 8));
        fill.dates.insert(row.date);
    }
    // An insurer contract is observed rarely: its last statement before the window carries in.
    for account in list.iter().filter(|a| providers::is_insurer_contract(a)) {
        let Some(day_before) = from.pred_opt() else {
            continue;
        };
        if let Some(s) =
            snapshots::find_latest_on_or_before(&mut *conn, account.id, day_before, None).await?
        {
            fill.balance
                .entry(account.id)
                .or_default()
                .insert(s.date, column(s.balance, 8));
            fill.invested
                .entry(account.id)
                .or_default()
                .insert(s.date, column(s.invested_amount, 8));
        }
    }
    Ok(fill)
}

fn floor<K: Ord + Copy, V: Copy>(map: Option<&BTreeMap<K, V>>, key: K) -> Option<V> {
    map.and_then(|m| m.range(..=key).next_back().map(|(_, v)| *v))
}

/// buildHistory.
pub async fn build_history(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    account_ids: &[i64],
    months: i32,
    split: bool,
    member_id: i64,
) -> AppResult<Vec<NetWorthPoint>> {
    let mut list = accounts::find_by_ids(&mut *conn, account_ids).await?;
    super::scale::fix_accounts(&mut list);
    if list.is_empty() {
        return Ok(Vec::new());
    }
    let shares = assert_readable(&mut *conn, &list, member_id).await?;
    let today = state.clock.today();
    let prices = &*state.ledger.prices;
    let batch = valuation::ValuationBatch::load(&mut *conn, &list).await?;
    let mut valuations = HashMap::with_capacity(list.len());
    for account in &list {
        valuations.insert(
            account.id,
            valuation::valuation_from_batch(prices, account, &batch, today).await,
        );
    }
    build_history_preloaded(
        state,
        conn,
        &list,
        &shares,
        months,
        split,
        &batch,
        &valuations,
    )
    .await
}

/// buildHistory over accounts, shares, holdings/debts, and live valuations already loaded by the caller.
#[allow(clippy::too_many_arguments)]
pub(crate) async fn build_history_preloaded(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    list: &[Account],
    shares: &Shares,
    months: i32,
    split: bool,
    batch: &valuation::ValuationBatch,
    valuations: &HashMap<i64, valuation::Valuation>,
) -> AppResult<Vec<NetWorthPoint>> {
    if list.is_empty() {
        return Ok(Vec::new());
    }
    let today = state.clock.today();
    let from = minus_months(today, months);
    let fill = forward_fill(&mut *conn, from, list).await?;
    let per_ids: HashSet<i64> = list
        .iter()
        .filter(|a| providers::is_insurer_contract(a) && share_of(shares, a.id) > Decimal::ZERO)
        .map(|a| a.id)
        .collect();
    let mut unknown_basis: HashSet<i64> = HashSet::new();
    for account in list.iter().filter(|a| per_ids.contains(&a.id)) {
        let lines = batch
            .holdings
            .get(&account.id)
            .map(Vec::as_slice)
            .unwrap_or(&[]);
        if !has_complete_cost_basis(account, lines) {
            unknown_basis.insert(account.id);
        }
    }

    let mut result = Vec::new();
    for &date in &fill.dates {
        let mut agg_total = Decimal::ZERO;
        let mut agg_invested = Decimal::ZERO;
        let mut agg_pnl = Decimal::ZERO;
        let mut unknown = false;
        let mut points = split.then(HashMap::new);
        for account in list {
            let id = account.id;
            let is_loan = account.account_type == AccountType::Loan;
            let flat = account.account_type.is_liability()
                || account.account_type == AccountType::PeerLoan;
            let bal = floor(fill.balance.get(&id), date);
            let inv = floor(fill.invested.get(&id), date);
            if per_ids.contains(&id) && bal.is_none() {
                unknown = true;
                continue;
            }
            let raw = weigh(bal.unwrap_or(Decimal::ZERO), shares, id);
            let total = if is_loan { -raw } else { raw };
            agg_total = add(agg_total, total);
            let unknown_account =
                unknown_basis.contains(&id) || (per_ids.contains(&id) && inv.is_none());
            let invested = if flat {
                Some(Decimal::ZERO)
            } else if unknown_account {
                None
            } else {
                Some(inv.map_or(raw, |v| weigh(v, shares, id)))
            };
            match invested {
                None => unknown = true,
                Some(v) => agg_invested = add(agg_invested, v),
            }
            let pnl = if flat {
                Some(Decimal::ZERO)
            } else {
                invested.map(|v| sub(total, v))
            };
            if let Some(p) = pnl {
                agg_pnl = add(agg_pnl, p);
            }
            if let Some(points) = points.as_mut() {
                points.insert(
                    id,
                    AccountPoint {
                        total,
                        invested,
                        pnl,
                    },
                );
            }
        }
        result.push(NetWorthPoint {
            date,
            total: agg_total,
            invested: (!unknown).then_some(agg_invested),
            pnl: (!unknown).then_some(agg_pnl),
            accounts: points,
        });
    }

    let mut live_total = Decimal::ZERO;
    let mut live_invested = Decimal::ZERO;
    let mut live_pnl = Decimal::ZERO;
    let mut unknown_live = false;
    let mut live_points = split.then(HashMap::new);
    for account in list {
        let id = account.id;
        let v = valuations[&id];
        let live = weigh(v.live_eur, shares, id);
        let invested = (!unknown_basis.contains(&id)).then(|| weigh(v.invested_eur, shares, id));
        let flat =
            account.account_type.is_liability() || account.account_type == AccountType::PeerLoan;
        let total = if account.account_type == AccountType::Loan {
            -live
        } else {
            live
        };
        live_total = add(live_total, total);
        if !flat && let Some(i) = invested {
            live_invested = add(live_invested, i);
        }
        let pnl = if flat {
            Some(Decimal::ZERO)
        } else {
            invested.map(|i| sub(live, i))
        };
        match pnl {
            None => unknown_live = true,
            Some(p) => live_pnl = add(live_pnl, p),
        }
        if let Some(points) = live_points.as_mut() {
            let invested = if flat { Some(Decimal::ZERO) } else { invested };
            points.insert(
                id,
                AccountPoint {
                    total,
                    invested,
                    pnl,
                },
            );
        }
    }
    let live_point = NetWorthPoint {
        date: today,
        total: live_total,
        invested: (!unknown_live).then_some(live_invested),
        pnl: (!unknown_live).then_some(live_pnl),
        accounts: live_points,
    };
    match result.iter().rposition(|p| p.date == today) {
        Some(i) => result[i] = live_point,
        None => result.push(live_point),
    }
    Ok(result)
}

pub async fn get_history(
    State(state): State<AppState>,
    auth: Auth,
    RawQuery(query): RawQuery,
) -> AppResult<axum::Json<Vec<NetWorthPoint>>> {
    let params = Params::parse(query.as_deref());
    let ids = params.required_longs("accountIds")?;
    let months = params.int("months", 12)?;
    let split = params.boolean("split", false)?;
    let mut conn = state.db.pool_handle();
    Ok(axum::Json(
        build_history(&state, &mut conn, &ids, months, split, auth.member_id).await?,
    ))
}

#[derive(Clone)]
struct HoldingData {
    series: Option<(String, bool)>,
    quantity: Decimal,
    provider_value_eur: Option<Decimal>,
    /// The line's value held flat over the 24 hours: an ISIN line has no hourly series (#702),
    /// and a never-market-priced account is never priced from market quotes.
    flat_value_eur: Option<Decimal>,
}

/// buildIntradayHistory: one point an hour over the last 24 hours.
pub async fn get_intraday(
    State(state): State<AppState>,
    auth: Auth,
    RawQuery(query): RawQuery,
) -> AppResult<axum::Json<Vec<NetWorthIntradayPoint>>> {
    let ids = Params::parse(query.as_deref()).required_longs("accountIds")?;
    let member_id = auth.member_id;
    let mut conn = state.db.pool_handle();
    let mut list = accounts::find_by_ids(&mut conn, &ids).await?;
    super::scale::fix_accounts(&mut list);
    if list.is_empty() {
        return Ok(axum::Json(Vec::new()));
    }
    let shares = assert_readable(&mut conn, &list, member_id).await?;
    let prices = &*state.ledger.prices;
    let now = state.clock.local_now();
    let from = now - Duration::hours(24);
    let today = state.clock.today();

    let mut account_holdings: HashMap<i64, Vec<HoldingData>> = HashMap::new();
    let mut holdings_invested: HashMap<i64, Decimal> = HashMap::new();
    let mut cash: HashMap<i64, Decimal> = HashMap::new();
    let mut bank_balance: HashMap<i64, Decimal> = HashMap::new();
    let mut per_snapshots: HashMap<i64, BTreeMap<NaiveDate, BalanceSnapshot>> = HashMap::new();
    let mut per_live_balance: HashMap<i64, Decimal> = HashMap::new();
    let mut per_live_invested: HashMap<i64, Decimal> = HashMap::new();
    let mut all_series: BTreeSet<(String, bool)> = BTreeSet::new();
    let mut per_ids: HashSet<i64> = HashSet::new();
    let mut unknown_basis: HashSet<i64> = HashSet::new();

    for account in &list {
        let id = account.id;
        if providers::is_insurer_contract(account) {
            per_ids.insert(id);
            let lines = holdings::find_by_account(&mut conn, id).await?;
            if share_of(&shares, id) > Decimal::ZERO && !has_complete_cost_basis(account, &lines) {
                unknown_basis.insert(id);
            }
            let by_date = snapshots::find_by_account(&mut conn, id)
                .await?
                .into_iter()
                .map(|mut s| {
                    super::scale::fix_snapshot(&mut s);
                    (s.date, s)
                })
                .collect();
            per_snapshots.insert(id, by_date);
            let v = valuation::valuation(&mut conn, prices, account, today).await?;
            per_live_balance.insert(id, weigh(v.live_eur, &shares, id));
            per_live_invested.insert(id, weigh(v.invested_eur, &shares, id));
            account_holdings.insert(id, Vec::new());
            continue;
        }
        let lines = holdings::find_by_account(&mut conn, id).await?;
        if lines.is_empty() {
            let balance = match snapshots::find_by_account_and_date(&mut conn, id, today).await? {
                Some(s) => s.balance,
                None => valuation::live_balance_eur(&mut conn, prices, account, today).await?,
            };
            bank_balance.insert(id, weigh(balance, &shares, id));
            account_holdings.insert(id, Vec::new());
            holdings_invested.insert(id, Decimal::ZERO);
            continue;
        }
        let crypto = account.account_type == AccountType::Crypto;
        let never_market_priced = providers::is_never_market_priced(account);
        let by_isin = !crypto && !never_market_priced && lines.iter().any(|h| h.isin.is_some());
        let quotes = if by_isin {
            valuation::quotes_for(prices, account, &lines, today).await
        } else {
            HashMap::new()
        };
        let mut data = Vec::with_capacity(lines.len());
        let mut invested = Decimal::ZERO;
        let mut any_cost_unknown = false;
        for h in &lines {
            // An ISIN line is never priced through a ticker's hourly series (#702), and a
            // never-market-priced account is never priced from market quotes at all: both stay
            // flat at the value the daily chart gives them now (the ISIN quote or the bank's
            // value; always the provider value for never-market-priced accounts).
            let isin_line = !crypto && h.isin.is_some();
            let flat_line = isin_line || never_market_priced;
            let series = (!flat_line).then(|| (h.ticker.to_uppercase(), crypto));
            let flat_value_eur = flat_line
                .then(|| valuation::value_holding(account, h, &quotes).current_value_eur)
                .flatten();
            let provider_cost = valuation::provider_cost_basis_eur(h);
            if provider_cost.is_none() && h.average_buy_in.is_none() {
                any_cost_unknown = true;
            }
            invested = add(
                invested,
                provider_cost
                    .unwrap_or_else(|| h.quantity * h.average_buy_in.unwrap_or(Decimal::ZERO)),
            );
            if let Some(s) = &series {
                all_series.insert(s.clone());
            }
            data.push(HoldingData {
                series,
                quantity: h.quantity,
                provider_value_eur: h.provider_value_eur,
                flat_value_eur,
            });
        }
        let pocket = account.cash_balance.unwrap_or(Decimal::ZERO);
        cash.insert(id, pocket);
        account_holdings.insert(id, data);
        let invested = if any_cost_unknown || account.provider_invested_eur.is_some() {
            weigh(
                valuation::valuation(&mut conn, prices, account, today)
                    .await?
                    .invested_eur,
                &shares,
                id,
            )
        } else {
            weigh(invested + pocket, &shares, id)
        };
        holdings_invested.insert(id, invested);
    }

    let mut series_prices: HashMap<(String, bool), BTreeMap<NaiveDateTime, Decimal>> =
        HashMap::new();
    for series in &all_series {
        let found = state
            .accounts
            .intraday
            .prices_eur(&series.0, series.1, from, now)
            .await;
        if !found.is_empty() {
            series_prices.insert(series.clone(), found);
        }
    }

    let truncate = |t: NaiveDateTime| {
        t.with_minute(0)
            .and_then(|t| t.with_second(0))
            .and_then(|t| t.with_nanosecond(0))
            .unwrap_or(t)
    };
    let current_hour = truncate(now);
    let mut result = Vec::new();
    let mut ts = truncate(from);
    while ts <= now {
        let mut agg_total = Decimal::ZERO;
        let mut agg_invested = Decimal::ZERO;
        let mut unknown = false;
        let mut observed = false;
        for account in &list {
            let id = account.id;
            let is_loan = account.account_type == AccountType::Loan;
            if per_ids.contains(&id) {
                let owned = share_of(&shares, id) > Decimal::ZERO;
                let (balance, invested) = if ts == current_hour {
                    let invested = (!unknown_basis.contains(&id)).then(|| per_live_invested[&id]);
                    (per_live_balance[&id], invested)
                } else {
                    let Some(snapshot) = per_snapshots
                        .get(&id)
                        .and_then(|m| m.range(..=ts.date()).next_back().map(|(_, s)| s))
                    else {
                        if owned {
                            unknown = true;
                        }
                        continue;
                    };
                    let unknown_account = owned && unknown_basis.contains(&id);
                    if unknown_account {
                        unknown = true;
                    }
                    let invested =
                        (!unknown_account).then(|| weigh(snapshot.invested_amount, &shares, id));
                    (weigh(snapshot.balance, &shares, id), invested)
                };
                if unknown_basis.contains(&id) {
                    unknown = true;
                }
                agg_total = add(agg_total, balance);
                match invested {
                    None => {
                        if owned {
                            unknown = true;
                        }
                    }
                    Some(i) => agg_invested = add(agg_invested, i),
                }
                observed = true;
                continue;
            }
            observed = true;
            let lines = account_holdings
                .get(&id)
                .map(Vec::as_slice)
                .unwrap_or_default();
            if lines.is_empty() {
                let balance = bank_balance.get(&id).copied().unwrap_or(Decimal::ZERO);
                let value = if is_loan { -balance } else { balance };
                agg_total = add(agg_total, value);
                if !account.account_type.is_liability()
                    && account.account_type != AccountType::PeerLoan
                {
                    agg_invested = add(agg_invested, value);
                }
            } else {
                let mut market = Decimal::ZERO;
                for line in lines {
                    if let Some(v) = line.flat_value_eur {
                        market = add(market, v);
                        continue;
                    }
                    let price = line
                        .series
                        .as_ref()
                        .and_then(|s| series_prices.get(s))
                        .and_then(|m| m.range(..=ts).next_back().map(|(_, p)| *p));
                    // A quote the provider contradicts is another listing's: valuation() drops it
                    // for the daily chart, so the hourly one must too (#683).
                    let overruled = |p: Decimal| {
                        valuation::provider_overrules_quote(
                            account,
                            line.quantity,
                            p,
                            line.provider_value_eur,
                        )
                    };
                    if let Some(p) = price.filter(|p| !overruled(*p)) {
                        market = add(market, line.quantity * p);
                    } else if let Some(v) = line.provider_value_eur {
                        market = add(market, v);
                    }
                }
                let market = weigh(
                    market + cash.get(&id).copied().unwrap_or(Decimal::ZERO),
                    &shares,
                    id,
                );
                if is_loan {
                    agg_total = sub(agg_total, market);
                } else {
                    agg_total = add(agg_total, market);
                    agg_invested = add(
                        agg_invested,
                        holdings_invested.get(&id).copied().unwrap_or(Decimal::ZERO),
                    );
                }
            }
        }
        if observed {
            result.push(NetWorthIntradayPoint {
                timestamp: ts,
                total: agg_total,
                invested: (!unknown).then_some(agg_invested),
            });
        }
        ts += Duration::hours(1);
    }
    Ok(axum::Json(result))
}

/// buildPnl: value, cost basis and P&L of the accounts now, and the price-driven P&L of their
/// holdings since from when it is given.
pub async fn get_pnl(
    State(state): State<AppState>,
    auth: Auth,
    RawQuery(query): RawQuery,
) -> AppResult<axum::Json<PnlResponse>> {
    let params = Params::parse(query.as_deref());
    let ids = params.required_longs("accountIds")?;
    let from_date = params.date("from")?;
    let mut conn = state.db.pool_handle();
    let mut list = accounts::find_by_ids(&mut conn, &ids).await?;
    super::scale::fix_accounts(&mut list);
    let empty = |total| PnlResponse {
        total,
        invested: Some(Decimal::ZERO),
        pnl: Some(Decimal::ZERO),
        pnl_percent: None,
        value_at_from: None,
        range_pnl: None,
        range_pnl_percent: None,
    };
    if list.is_empty() {
        return Ok(axum::Json(empty(Decimal::ZERO)));
    }
    let shares = assert_readable(&mut conn, &list, auth.member_id).await?;
    let prices = &*state.ledger.prices;
    let today = state.clock.today();
    let mut live_total = Decimal::ZERO;
    let mut live_invested = Decimal::ZERO;
    let mut live_value = Decimal::ZERO;
    let mut range_holdings: Vec<(&Account, AccountHolding)> = Vec::new();
    let mut unknown_basis = false;
    for account in &list {
        let lines = holdings::find_by_account(&mut conn, account.id).await?;
        let per = providers::is_insurer_contract(account);
        let v = valuation::valuation(&mut conn, prices, account, today).await?;
        let live = weigh(v.live_eur, &shares, account.id);
        if account.account_type == AccountType::Loan {
            live_total = sub(live_total, live);
        } else if account.account_type.is_liability()
            || account.account_type == AccountType::PeerLoan
        {
            live_total = add(live_total, live);
        } else {
            live_total = add(live_total, live);
            if per
                && share_of(&shares, account.id) > Decimal::ZERO
                && !has_complete_cost_basis(account, &lines)
            {
                unknown_basis = true;
            } else {
                live_value = add(live_value, live);
                live_invested = add(live_invested, weigh(v.invested_eur, &shares, account.id));
            }
        }
        // A never-market-priced line has no move of its own, like an unmatched one.
        if !per && !providers::is_never_market_priced(account) {
            range_holdings.extend(lines.into_iter().map(|h| (account, h)));
        }
    }
    let pnl = sub(live_value, live_invested);
    let percent = |gain: Decimal, base: Decimal| {
        (base > Decimal::ZERO).then(|| {
            divide(
                gain * HUNDRED,
                base,
                1,
                RoundingStrategy::MidpointAwayFromZero,
            )
        })
    };
    let mut response = PnlResponse {
        total: live_total,
        invested: Some(live_invested),
        pnl: Some(pnl),
        pnl_percent: percent(pnl, live_invested),
        value_at_from: None,
        range_pnl: None,
        range_pnl_percent: None,
    };
    if unknown_basis {
        response.invested = None;
        response.pnl = None;
        response.pnl_percent = None;
    }
    let Some(from_date) = from_date else {
        return Ok(axum::Json(response));
    };
    if range_holdings.is_empty() || unknown_basis {
        return Ok(axum::Json(response));
    }
    let mut value_at_from = Decimal::ZERO;
    let mut live_matched = Decimal::ZERO;
    let mut matched = 0;
    let mut snaps: HashMap<String, Option<Decimal>> = HashMap::new();
    let distinct_isins: BTreeSet<String> = range_holdings
        .iter()
        .filter(|(account, _)| account.account_type != AccountType::Crypto)
        .filter_map(|(_, h)| h.isin.clone())
        .collect();
    let isin_lives = if distinct_isins.is_empty() {
        HashMap::new()
    } else {
        prices.isin_quotes(&distinct_isins, today).await
    };
    let mut lives: HashMap<String, Option<Decimal>> = HashMap::new();
    for (account, h) in &range_holdings {
        // Both sides of an ISIN line are ISIN prices (#702): the price recorded under the ISIN,
        // and the ISIN quote. A line missing either stays out, never mixed with a ticker's.
        let isin = h
            .isin
            .as_ref()
            .filter(|_| account.account_type != AccountType::Crypto);
        let key = isin.unwrap_or(&h.ticker);
        if !snaps.contains_key(key) {
            let snap = prices::latest_price_on_or_before(&mut conn, key, from_date).await?;
            snaps.insert(key.clone(), snap.map(|s| s.price_eur));
        }
        let Some(then) = snaps[key] else { continue };
        let now_price = match isin {
            Some(isin) => isin_lives.get(isin).map(|q| q.price),
            None => {
                if !lives.contains_key(&h.ticker) {
                    let quote = prices.quote(&h.ticker, false, today).await;
                    lives.insert(h.ticker.clone(), quote.map(|q| q.price));
                }
                lives[&h.ticker]
            }
        };
        let Some(now_price) = now_price else {
            continue;
        };
        // Another listing's price, as valuation() judges it (#683): its move is not the
        // position's, so the line stays out of both sides like any unmatched holding.
        if valuation::provider_overrules_quote(account, h.quantity, now_price, h.provider_value_eur)
        {
            continue;
        }
        value_at_from = add(value_at_from, weigh(h.quantity * then, &shares, account.id));
        live_matched = add(
            live_matched,
            weigh(h.quantity * now_price, &shares, account.id),
        );
        matched += 1;
    }
    if matched == 0 {
        return Ok(axum::Json(response));
    }
    let range_pnl = sub(live_matched, value_at_from);
    response.value_at_from = Some(value_at_from);
    response.range_pnl = Some(range_pnl);
    response.range_pnl_percent = percent(range_pnl, value_at_from);
    Ok(axum::Json(response))
}
