//! CaisseEpargnePerService: the insurer's complete valuation of a PER or an assurance vie,
//! validated and resolved before the sync transaction, written inside it, and enriched after it.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::sync::LazyLock;

use crate::db::DbHandle;
use chrono::{DateTime, Months, NaiveDate, Utc};
use regex::Regex;
use rust_decimal::Decimal;
use serde::Deserialize;

use super::Bank;
use crate::AppState;
use crate::connectors::sidecar::SyncError;
use crate::connectors::sidecar::template::error;
use crate::ledger::decimal::{HALF_UP, divide};
use crate::ledger::holdings;
use crate::ledger::model::HoldingDraft;
use crate::ledger::sidecar::SidecarErrorCode;

static ISIN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[A-Z]{2}[A-Z0-9]{10}$").expect("regex"));
static TICKER: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[A-Z0-9][A-Z0-9.-]{0,29}$").expect("regex"));

/// Complete insurer valuation: euro-fund pocket plus owned units, not the product catalogue.
/// Missing positions are a legacy shape, never an empty portfolio.
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PerDetails {
    balance: Option<Decimal>,
    euro_fund_balance: Option<Decimal>,
    valuation_date: Option<NaiveDate>,
    positions: Option<Vec<PerPosition>>,
}

/// The bank NAV is a fallback, not an acquisition price nor a historical market quote.
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct PerPosition {
    isin: Option<String>,
    name: Option<String>,
    quantity: Option<Decimal>,
    unit_price: Option<Decimal>,
    value: Option<Decimal>,
    price_date: Option<NaiveDate>,
    compartment_id: Option<i32>,
}

pub struct Prepared {
    pub balance: Decimal,
    pub euro_fund_balance: Decimal,
    pub valuation_date: NaiveDate,
    positions: Vec<PreparedPosition>,
    isin_by_ticker: BTreeMap<String, String>,
    verified_tickers: BTreeSet<String>,
}

struct PreparedPosition {
    ticker: String,
    name: String,
    quantity: Decimal,
    unit_price: Decimal,
    value: Decimal,
    price_date: NaiveDate,
}

struct Aggregated {
    isin: String,
    quantity: Decimal,
    value: Decimal,
    name: Option<String>,
    price_date: NaiveDate,
}

struct Resolved {
    isin: String,
    ticker: String,
    name: String,
    quantity: Decimal,
    unit_price: Decimal,
    value: Decimal,
    price_date: NaiveDate,
    verified: bool,
}

fn invalid() -> SyncError {
    error(
        SidecarErrorCode::UpstreamFormatChanged,
        "Caisse d'Epargne returned an invalid PER valuation",
    )
}

fn outside_tolerance(left: Decimal, right: Decimal) -> bool {
    (left - right).abs() > Decimal::new(2, 2)
}

fn blank(value: Option<&str>) -> bool {
    value.is_none_or(|v| v.trim().is_empty())
}

/// Validates and resolves a complete valuation without writing anything; None for the legacy
/// balance-only shape.
pub async fn prepare(
    state: &AppState,
    _bank: &Bank,
    detail: Option<PerDetails>,
    currency: Option<&str>,
) -> Result<Option<Prepared>, SyncError> {
    let Some(detail) = detail else {
        return Ok(None);
    };
    let (Some(balance), Some(euro_fund_balance), Some(valuation_date), Some(raw_positions)) = (
        detail.balance,
        detail.euro_fund_balance,
        detail.valuation_date,
        detail.positions,
    ) else {
        return Err(invalid());
    };
    if currency != Some("EUR") || balance < Decimal::ZERO || euro_fund_balance < Decimal::ZERO {
        return Err(invalid());
    }
    let mut grouped: Vec<Aggregated> = Vec::new();
    let mut seen_compartments = HashSet::new();
    let mut uc_total = Decimal::ZERO;
    for position in raw_positions {
        let (isin, quantity, value, price_date) =
            validate(&position, valuation_date, &mut seen_compartments)?;
        uc_total = uc_total.checked_add(value).ok_or_else(invalid)?;
        match grouped.iter_mut().find(|g| g.isin == isin) {
            Some(group) => {
                group.quantity = group.quantity.checked_add(quantity).ok_or_else(invalid)?;
                group.value = group.value.checked_add(value).ok_or_else(invalid)?;
                if blank(group.name.as_deref()) {
                    group.name.clone_from(&position.name);
                }
                group.price_date = group.price_date.min(price_date);
            }
            None => grouped.push(Aggregated {
                isin,
                quantity,
                value,
                name: position.name,
                price_date,
            }),
        }
    }
    let reconciled = uc_total
        .checked_add(euro_fund_balance)
        .ok_or_else(invalid)?;
    if outside_tolerance(reconciled, balance) {
        return Err(invalid());
    }

    let mut resolved = Vec::with_capacity(grouped.len());
    for group in grouped {
        resolved.push(resolve(state, group).await);
    }
    let mut isins_by_ticker: HashMap<&str, HashSet<&str>> = HashMap::new();
    for position in &resolved {
        isins_by_ticker
            .entry(&position.ticker)
            .or_default()
            .insert(&position.isin);
    }
    let collides = |ticker: &str| isins_by_ticker.get(ticker).is_some_and(|i| i.len() > 1);

    let mut positions = Vec::with_capacity(resolved.len());
    let mut isin_by_ticker = BTreeMap::new();
    let mut verified_tickers = BTreeSet::new();
    for position in &resolved {
        // Two ISINs resolving to one ticker each keep their own raw ISIN.
        let collision = collides(&position.ticker);
        let ticker = if collision {
            position.isin.clone()
        } else {
            position.ticker.clone()
        };
        if position.verified && !collision {
            isin_by_ticker.insert(ticker.clone(), position.isin.clone());
            verified_tickers.insert(ticker.clone());
        }
        positions.push(PreparedPosition {
            ticker,
            name: position.name.clone(),
            quantity: position.quantity,
            unit_price: position.unit_price,
            value: position.value,
            price_date: position.price_date,
        });
    }
    Ok(Some(Prepared {
        balance,
        euro_fund_balance,
        valuation_date,
        positions,
        isin_by_ticker,
        verified_tickers,
    }))
}

/// The position's ISIN, quantity, value and price date once it is known to be consistent.
fn validate(
    position: &PerPosition,
    valuation_date: NaiveDate,
    seen_compartments: &mut HashSet<(String, i32)>,
) -> Result<(String, Decimal, Decimal, NaiveDate), SyncError> {
    let isin = position
        .isin
        .clone()
        .filter(|i| ISIN.is_match(i))
        .ok_or_else(invalid)?;
    let quantity = position
        .quantity
        .filter(|q| *q >= Decimal::ZERO)
        .ok_or_else(invalid)?;
    let value = position
        .value
        .filter(|v| *v >= Decimal::ZERO)
        .ok_or_else(invalid)?;
    let price_date = position
        .price_date
        .filter(|d| *d <= valuation_date)
        .ok_or_else(invalid)?;
    let compartment = position
        .compartment_id
        .filter(|c| *c >= 0)
        .ok_or_else(invalid)?;
    if position
        .name
        .as_deref()
        .is_some_and(|n| n.encode_utf16().count() > 100)
        || position.unit_price.is_some_and(|p| p < Decimal::ZERO)
        || !seen_compartments.insert((isin.clone(), compartment))
    {
        return Err(invalid());
    }
    if quantity > Decimal::ZERO && position.unit_price.is_none_or(|p| p <= Decimal::ZERO) {
        return Err(invalid());
    }
    let line_value = match position.unit_price {
        Some(price) => quantity.checked_mul(price).ok_or_else(invalid)?,
        None => Decimal::ZERO,
    };
    if outside_tolerance(line_value, value) {
        return Err(invalid());
    }
    Ok((isin, quantity, value, price_date))
}

/// A resolved ticker is an optimization: the raw ISIN and the bank valuation are authoritative.
async fn resolve(state: &AppState, group: Aggregated) -> Resolved {
    let unit_price = if group.quantity.is_zero() {
        Decimal::ZERO
    } else {
        divide(group.value, group.quantity, 8, HALF_UP)
    };
    let mut name = if blank(group.name.as_deref()) {
        group.isin.clone()
    } else {
        group.name.clone().unwrap_or_default()
    };
    let mut ticker = group.isin.clone();
    let mut verified = false;
    let seams = &state.connectors.seams;
    let result = seams.isin.resolve(&group.isin).await;
    let candidate = result.ticker.to_uppercase();
    if candidate != group.isin
        && TICKER.is_match(&candidate)
        && seams.symbol_catalog.has_quote(&candidate).await
    {
        ticker = candidate;
        verified = true;
        if blank(group.name.as_deref())
            && let Some(found) = result
                .name
                .filter(|n| !n.trim().is_empty() && n.encode_utf16().count() <= 100)
        {
            name = found;
        }
    }
    Resolved {
        isin: group.isin,
        ticker,
        name,
        quantity: group.quantity,
        unit_price,
        value: group.value,
        price_date: group.price_date,
        verified,
    }
}

/// Replaces the position set inside the sync transaction, on the account it just saved. Lines are
/// updated in place by ticker, never recreated: the insurer reports no acquisition cost, so a
/// buy-in the member typed survives. Only a ticker no longer reported is removed.
pub async fn apply(
    conn: &mut DbHandle<'_>,
    account_id: i64,
    prepared: &Prepared,
    synced_at: DateTime<Utc>,
) -> sqlx::Result<()> {
    let mut stored: HashMap<String, _> = holdings::find_by_account(&mut *conn, account_id)
        .await?
        .into_iter()
        .map(|h| (h.ticker.clone(), h))
        .collect();
    let kept: Vec<String> = prepared
        .positions
        .iter()
        .map(|p| p.ticker.clone())
        .collect();
    holdings::delete_by_account_except(&mut *conn, account_id, &kept).await?;
    for position in &prepared.positions {
        let existing = stored.remove(&position.ticker);
        let draft = HoldingDraft {
            ticker: position.ticker.clone(),
            name: Some(position.name.clone()),
            quantity: position.quantity,
            average_buy_in: existing.as_ref().and_then(|h| h.average_buy_in),
            current_price: Some(position.unit_price),
            quote_currency: Some("EUR".to_owned()),
            provider_value_eur: Some(position.value),
            provider_price_date: Some(position.price_date),
            // The insurer gives no P&L; one left from an earlier edit no longer matches the line.
            provider_pnl_eur: None,
            last_synced_at: Some(synced_at),
            provider_wealth_tier: existing.as_ref().and_then(|h| h.provider_wealth_tier),
        };
        match existing {
            Some(holding) => holdings::update(&mut *conn, holding.id, &draft, synced_at).await?,
            None => holdings::insert(&mut *conn, account_id, &draft, synced_at).await?,
        };
    }
    Ok(())
}

/// Security identity and market history, once the sync committed. The bank NAVs themselves are
/// never written as price snapshots.
pub async fn backfill(state: &AppState, prepared: &Prepared) {
    let seams = &state.connectors.seams;
    if !prepared.isin_by_ticker.is_empty() {
        seams
            .security_identities
            .record(&prepared.isin_by_ticker, state.clock.now())
            .await;
    }
    if prepared.verified_tickers.is_empty() {
        return;
    }
    let from = state
        .clock
        .today()
        .checked_sub_months(Months::new(12))
        .unwrap_or(NaiveDate::MIN);
    if let Err(e) = seams
        .price_backfill
        .backfill(&prepared.verified_tickers, from)
        .await
    {
        tracing::warn!("BPCE price backfill failed; enrichment will retry on the next sync: {e}");
    }
}
