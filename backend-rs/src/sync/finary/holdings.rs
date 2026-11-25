//! FinaryHoldingsImporter: Finary positions onto account_holding rows, leftover cash onto the
//! account. Only a complete snapshot in a proven EUR display currency replaces them.

use std::collections::HashMap;
use std::sync::LazyLock;

use crate::db::DbHandle;
use chrono::{DateTime, Utc};
use regex::Regex;
use rust_decimal::Decimal;

use super::IsinLookup;
use super::upstream::{FinaryAccount, Position};
use crate::error::AppResult;
use crate::ledger::decimal::{HALF_UP, divide};
use crate::ledger::holding_dedup::{HoldingAgg, vwap_merge};
use crate::ledger::holdings;
use crate::ledger::instrument::is_isin;
use crate::ledger::model::{Account, HoldingDraft, normalize_isin};
use crate::ledger::sync_values::{java_is_blank, java_trim};

pub const TICKER_MAX: usize = 100;
const NAME_MAX: usize = 255;
const BLANK_ISIN: &str = "000000000000";

/// Java's (?i) folds ASCII only, and its \s is ASCII whitespace.
static CASH_NAME: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i:solde)[ \t\n\x0B\x0C\r]+(?i:esp)[eè](?i:ces)|\b(?i:cash)\b|^(?i:euro)$")
        .expect("static regex")
});

fn complete_snapshot(acc: &FinaryAccount) -> bool {
    acc.securities.is_some()
        && acc.cryptos.is_some()
        && acc.fonds_euro.is_some()
        && acc.fiats.is_some()
        && acc.generic_assets.is_some()
        && acc.scpis.is_some()
        && acc.precious_metals.is_some()
}

fn flatten(list: &Option<Vec<Option<Position>>>) -> impl Iterator<Item = &Position> {
    list.iter().flatten().flatten()
}

/// Every non-fiat list, in the importer's order.
fn positions(acc: &FinaryAccount) -> Vec<&Position> {
    [
        &acc.securities,
        &acc.cryptos,
        &acc.fonds_euro,
        &acc.generic_assets,
        &acc.scpis,
        &acc.precious_metals,
    ]
    .into_iter()
    .flat_map(flatten)
    .collect()
}

pub fn is_cash(position: &Position) -> bool {
    if position.fiat.is_some() {
        return true;
    }
    if position
        .kind
        .as_deref()
        .is_some_and(|t| t.to_lowercase().contains("fiat"))
    {
        return true;
    }
    if let Some(security) = &position.security {
        if security.symbol.as_deref() == Some(BLANK_ISIN)
            || security.isin.as_deref() == Some(BLANK_ISIN)
        {
            return true;
        }
        if security.isin.is_none() && CASH_NAME.is_match(security.name.as_deref().unwrap_or("")) {
            return true;
        }
    }
    position.security.is_none()
        && position.crypto.is_none()
        && CASH_NAME.is_match(position.name.as_deref().unwrap_or(""))
}

/// None when a cash line has no EUR value: counting it as zero would understate the cash.
fn sum_cash(acc: &FinaryAccount) -> Option<Decimal> {
    flatten(&acc.fiats)
        .chain(positions(acc).into_iter().filter(|p| is_cash(p)))
        .try_fold(Decimal::ZERO, |sum, line| {
            Some(sum + line.display_current_value?)
        })
}

fn first_non_blank<'a>(values: &[Option<&'a str>]) -> Option<&'a str> {
    values.iter().flatten().copied().find(|v| !java_is_blank(v))
}

fn truncate(value: &str, max: usize) -> String {
    value.chars().take(max).collect()
}

pub fn sanitize_ticker(raw: Option<&str>) -> Option<String> {
    let raw = raw.filter(|r| !java_is_blank(r))?;
    let upper = java_trim(raw).to_uppercase();
    let mut cleaned = String::with_capacity(upper.len());
    for c in upper.chars() {
        let c = if c.is_ascii_uppercase() || c.is_ascii_digit() || matches!(c, '.' | '_' | '-') {
            c
        } else {
            '_'
        };
        // A supplementary character is two UTF-16 units in Java, both replaced; the run collapses.
        if !(c == '_' && cleaned.ends_with('_')) {
            cleaned.push(c);
        }
    }
    let cleaned = cleaned.strip_prefix('_').unwrap_or(&cleaned);
    let cleaned = cleaned.strip_suffix('_').unwrap_or(cleaned);
    (!cleaned.is_empty()).then(|| truncate(cleaned, TICKER_MAX))
}

/// (ticker, name) of a position.
async fn resolve(
    position: &Position,
    isin_lookup: &dyn IsinLookup,
) -> (Option<String>, Option<String>, Option<String>) {
    if let Some(crypto) = &position.crypto {
        let code = first_non_blank(&[crypto.code.as_deref(), crypto.symbol.as_deref()]);
        return (
            sanitize_ticker(first_non_blank(&[
                code,
                crypto.name.as_deref(),
                position.id.as_deref(),
            ])),
            first_non_blank(&[crypto.name.as_deref(), code]).map(str::to_owned),
            None,
        );
    }
    if let Some(security) = &position.security {
        let isin = security.isin.as_deref();
        if let Some(isin) = isin.filter(|i| is_isin(i)) {
            let resolved = isin_lookup.resolve(isin).await;
            let ticker = first_non_blank(&[Some(resolved.ticker.as_str()), Some(isin)]);
            let name =
                first_non_blank(&[resolved.name.as_deref(), security.name.as_deref(), ticker]);
            return (
                sanitize_ticker(ticker),
                name.map(str::to_owned),
                normalize_isin(Some(isin)),
            );
        }
        let fallback = first_non_blank(&[
            isin,
            security.symbol.as_deref(),
            security.slug.as_deref(),
            security.name.as_deref(),
            position.id.as_deref(),
        ]);
        return (
            sanitize_ticker(fallback),
            first_non_blank(&[security.name.as_deref(), fallback]).map(str::to_owned),
            normalize_isin(isin),
        );
    }
    let name = first_non_blank(&[position.name.as_deref(), position.id.as_deref()]);
    (
        sanitize_ticker(Some(&format!("FINARY_{}", name.unwrap_or("null")))),
        name.map(str::to_owned),
        None,
    )
}

/// Asks for the ticker of every security of the account, before the write transaction opens.
/// The resolver remembers each answer, so `import` then resolves from memory: asked inside the
/// transaction, OpenFIGI would keep SQLite's only writer for as long as it takes to answer.
pub async fn resolve_first(isin_lookup: &dyn IsinLookup, finary: &FinaryAccount) {
    for position in positions(finary) {
        let isin = position
            .security
            .as_ref()
            .and_then(|security| security.isin.as_deref())
            .filter(|isin| is_isin(isin));
        if position.crypto.is_none()
            && let Some(isin) = isin
        {
            isin_lookup.resolve(isin).await;
        }
    }
}

/// HoldingDedup reads an unknown average as zero; one unknown line leaves the merge unknown.
fn merge_lines(a: &HoldingAgg, b: &HoldingAgg) -> HoldingAgg {
    let mut merged = vwap_merge(a, b);
    if a.average_buy_in.is_none() || b.average_buy_in.is_none() {
        merged.average_buy_in = None;
    }
    merged
}

/// One unknown line makes the merged provider total unknown.
fn add(a: Option<Decimal>, b: Option<Decimal>) -> Option<Decimal> {
    Some(a? + b?)
}

struct Line {
    agg: HoldingAgg,
    isin: Option<String>,
    value_eur: Option<Decimal>,
    pnl_eur: Option<Decimal>,
}

/// importHoldings: sets account.cash_balance (the caller saves the account) and replaces the
/// holdings; returns how many were written.
pub async fn import(
    conn: &mut DbHandle<'_>,
    isin_lookup: &dyn IsinLookup,
    account: &mut Account,
    finary: &FinaryAccount,
    display_is_eur: bool,
    now: DateTime<Utc>,
) -> AppResult<usize> {
    if !complete_snapshot(finary) {
        tracing::warn!(
            "Keeping the holdings of account {}: Finary's payload omits a position list",
            account.id
        );
        return Ok(0);
    }
    if !display_is_eur {
        tracing::warn!(
            "Keeping the holdings of account {}: Finary's display currency is not proven EUR",
            account.id
        );
        return Ok(0);
    }
    let Some(cash) = sum_cash(finary) else {
        tracing::warn!(
            "Keeping the holdings of account {}: a cash line has no EUR value",
            account.id
        );
        return Ok(0);
    };
    let positions = positions(finary);
    if positions
        .iter()
        .any(|p| !is_cash(p) && p.quantity.is_none())
    {
        tracing::warn!(
            "Keeping the holdings of account {}: a position has no quantity",
            account.id
        );
        return Ok(0);
    }

    account.cash_balance = (!cash.is_zero()).then_some(cash);
    if positions.is_empty() {
        holdings::delete_by_account(&mut *conn, account.id).await?;
        return Ok(0);
    }

    let mut order: Vec<String> = Vec::new();
    let mut lines: HashMap<String, Line> = HashMap::new();
    for position in positions {
        if is_cash(position) {
            continue;
        }
        let Some(quantity) = position.quantity.filter(|q| !q.is_zero()) else {
            continue;
        };
        let (ticker, name, isin) = resolve(position, isin_lookup).await;
        let Some(ticker) = ticker else {
            tracing::warn!(
                "Skipping Finary position without a ticker on account {}",
                account.id
            );
            continue;
        };
        let value_eur = position.display_current_value;
        let pnl_eur = position
            .display_unrealized_pnl
            .or(position.display_current_upnl);
        let price_eur = position
            .display_current_price
            .or_else(|| value_eur.map(|v| divide(v, quantity, 8, HALF_UP)));
        let average = position
            .display_buying_value
            .map(|v| divide(v, quantity, 8, HALF_UP));
        let agg = HoldingAgg {
            quantity,
            average_buy_in: average,
            current_price: price_eur,
            name,
        };
        match lines.get_mut(&ticker) {
            Some(line) => {
                line.agg = merge_lines(&line.agg, &agg);
                if line.isin.is_none() {
                    line.isin = isin;
                }
                line.value_eur = add(line.value_eur, value_eur);
                line.pnl_eur = add(line.pnl_eur, pnl_eur);
            }
            None => {
                order.push(ticker.clone());
                lines.insert(
                    ticker,
                    Line {
                        agg,
                        isin,
                        value_eur,
                        pnl_eur,
                    },
                );
            }
        }
    }

    holdings::delete_by_account(&mut *conn, account.id).await?;
    let mut saved = 0;
    for ticker in order {
        let line = &lines[&ticker];
        if line.agg.quantity.is_zero() {
            continue;
        }
        let draft = HoldingDraft {
            ticker,
            isin: line.isin.clone(),
            name: line.agg.name.as_deref().map(|n| truncate(n, NAME_MAX)),
            quantity: line.agg.quantity,
            average_buy_in: line.agg.average_buy_in,
            current_price: line.agg.current_price,
            quote_currency: Some("EUR".to_owned()),
            provider_value_eur: line.value_eur,
            provider_price_date: None,
            provider_pnl_eur: line.pnl_eur,
            last_synced_at: Some(now),
            provider_wealth_tier: None,
        };
        holdings::insert(&mut *conn, account.id, &draft, now).await?;
        saved += 1;
    }
    Ok(saved)
}
