//! SimplefinHoldings: the positions a bridge reports turned into Picsou holdings, in each
//! position's own currency. Pure: currency conversion and persistence stay in the service.
//!
//! What the bridge gets wrong or leaves out (seen on Fidelity data): cost_basis is 0.00 on every
//! line, so a zero is unknown and the buy-in falls back to purchase_price; cash sits in a
//! money-market sweep (SPAXX) listed as a position, which goes to the cash balance instead.
//! complete is false when a line holding something could not be carried over: replacing a
//! portfolio with part of it would delete the rest.

use rust_decimal::Decimal;

use super::currency;
use super::json::SimplefinHolding;
use crate::ledger::decimal::{HALF_UP, divide};
use crate::ledger::sync_values::java_trim;

/// A sweep fund is priced at a dollar a share; a few cents of tolerance cover rounding.
const NAV_LOW: Decimal = Decimal::from_parts(99, 0, 0, false, 2);
const NAV_HIGH: Decimal = Decimal::from_parts(101, 0, 0, false, 2);

/// One position in its own currency; the price derives from the market value, so lots merged
/// under one ticker price at their combined worth.
#[derive(Debug, Clone, PartialEq)]
pub struct Position {
    pub ticker: String,
    pub name: Option<String>,
    pub currency: String,
    pub quantity: Decimal,
    pub average_buy_in: Option<Decimal>,
    pub market_value: Option<Decimal>,
}

impl Position {
    pub fn current_price(&self) -> Option<Decimal> {
        self.market_value
            .map(|value| divide(value, self.quantity, 8, HALF_UP))
    }
}

/// Positions plus the sweep lines summed per currency, in first-seen order.
#[derive(Debug, Clone, PartialEq)]
pub struct Mapped {
    pub positions: Vec<Position>,
    pub sweep_by_currency: Vec<(String, Decimal)>,
    pub complete: bool,
}

/// The smallest magnitude that rounds (HALF_UP, 8 places) to 10^12, the first value
/// numeric(20,8) cannot hold.
fn column_ceiling() -> Decimal {
    Decimal::from_i128_with_scale(999_999_999_999_999_999_995, 9)
}

/// numeric(20,8) holds 12 integer digits; a figure past that would fail the insert.
pub fn fits(value: Option<Decimal>) -> bool {
    value.is_none_or(|v| v.abs() < column_ceiling())
}

pub fn map(raw: &[SimplefinHolding], account_currency: &str) -> Mapped {
    let mut by_ticker: Vec<Position> = Vec::new();
    let mut sweep: Vec<(String, Decimal)> = Vec::new();
    let mut complete = true;
    for h in raw {
        if h.shares.is_some_and(|s| s.is_zero()) {
            continue; // nothing held
        }
        let currency = if readable(h) {
            currency_of(h.currency.as_deref(), account_currency)
        } else {
            None
        };
        let (Some(currency), Some(shares)) = (currency, h.shares) else {
            complete = false; // short, malformed, past the column or not a currency
            continue;
        };
        let market_value = h
            .market_value
            .filter(|v| v.is_sign_positive() && !v.is_zero());

        if let Some(value) = market_value.filter(|v| is_cash_sweep(h, *v, shares)) {
            match sweep.iter_mut().find(|(c, _)| *c == currency) {
                Some((_, sum)) => *sum += value,
                None => sweep.push((currency, value)),
            }
            continue;
        }
        let buy_in = buy_in(h, shares);
        let symbol = h.symbol.as_deref().filter(|s| !s.trim().is_empty());
        let (Some(symbol), true) = (symbol, fits(buy_in)) else {
            complete = false; // cannot be keyed, or the buy-in overflows the column
            continue;
        };
        let ticker = java_trim(symbol).to_uppercase();
        let next = Position {
            ticker: ticker.clone(),
            name: h.description.clone(),
            currency: currency.clone(),
            quantity: shares,
            average_buy_in: buy_in,
            market_value,
        };
        match by_ticker.iter_mut().find(|p| p.ticker == ticker) {
            None => by_ticker.push(next),
            Some(previous) if previous.currency == currency => match merge(previous, &next) {
                Some(merged) => *previous = merged,
                None => complete = false,
            },
            Some(_) => complete = false, // one (account, ticker) row cannot hold two currencies
        }
    }

    let mut positions = Vec::new();
    for p in by_ticker {
        if fits(Some(p.quantity)) && fits(p.market_value) && fits(p.current_price()) {
            positions.push(p);
        } else {
            complete = false; // a merged or derived figure overflows the column
        }
    }
    let mut sweeps = Vec::new();
    for (currency, amount) in sweep {
        if fits(Some(amount)) {
            sweeps.push((currency, amount));
        } else {
            complete = false;
        }
    }
    Mapped {
        positions,
        sweep_by_currency: sweeps,
        complete,
    }
}

/// A positive share count and figures that fit the columns.
fn readable(h: &SimplefinHolding) -> bool {
    h.shares
        .is_some_and(|s| s.is_sign_positive() && !s.is_zero())
        && fits(h.shares)
        && fits(h.market_value)
        && fits(h.cost_basis)
        && fits(h.purchase_price)
}

/// A real ISO 4217 code, else the account's own when none is given, else None.
fn currency_of(raw: Option<&str>, account_currency: &str) -> Option<String> {
    let code = java_trim(raw.unwrap_or_default()).to_uppercase();
    if code.is_empty() {
        return Some(account_currency.to_owned());
    }
    currency::is_known(&code).then_some(code)
}

/// A money-market sweep: a symbol ending in XX, or no symbol at all, at about a dollar a share.
fn is_cash_sweep(h: &SimplefinHolding, market_value: Decimal, shares: Decimal) -> bool {
    if let Some(symbol) = h.symbol.as_deref().filter(|s| !s.trim().is_empty())
        && !java_trim(symbol).to_uppercase().ends_with("XX")
    {
        return false;
    }
    let nav = divide(market_value, shares, 6, HALF_UP);
    nav >= NAV_LOW && nav <= NAV_HIGH
}

fn buy_in(h: &SimplefinHolding, shares: Decimal) -> Option<Decimal> {
    if let Some(cost) = h
        .cost_basis
        .filter(|c| c.is_sign_positive() && !c.is_zero())
    {
        return Some(divide(cost, shares, 8, HALF_UP));
    }
    h.purchase_price
        .filter(|p| p.is_sign_positive() && !p.is_zero())
}

/// Two lots of one ticker and currency: quantities add; the weighted buy-in and the market
/// value are kept only when both lots know them. None when a figure overflows.
fn merge(a: &Position, b: &Position) -> Option<Position> {
    let quantity = a.quantity.checked_add(b.quantity)?;
    let average_buy_in = match (a.average_buy_in, b.average_buy_in) {
        (Some(x), Some(y)) => {
            let total = x
                .checked_mul(a.quantity)?
                .checked_add(y.checked_mul(b.quantity)?)?;
            Some(divide(total, quantity, 8, HALF_UP))
        }
        _ => None,
    };
    let market_value = match (a.market_value, b.market_value) {
        (Some(x), Some(y)) => Some(x.checked_add(y)?),
        _ => None,
    };
    Some(Position {
        ticker: a.ticker.clone(),
        name: a.name.clone().or_else(|| b.name.clone()),
        currency: a.currency.clone(),
        quantity,
        average_buy_in,
        market_value,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(v: &str) -> Decimal {
        v.parse().unwrap()
    }

    fn line(symbol: Option<&str>, shares: &str, value: Option<&str>) -> SimplefinHolding {
        SimplefinHolding {
            symbol: symbol.map(str::to_owned),
            description: Some("name".into()),
            shares: Some(d(shares)),
            market_value: value.map(d),
            ..SimplefinHolding::default()
        }
    }

    #[test]
    fn the_buy_in_prefers_a_positive_cost_basis_then_the_purchase_price() {
        let mut h = line(Some("VTI"), "3", Some("300"));
        h.cost_basis = Some(d("100"));
        assert_eq!(
            map(&[h.clone()], "USD").positions[0].average_buy_in,
            Some(d("33.33333333"))
        );
        h.cost_basis = Some(d("0.00"));
        h.purchase_price = Some(d("25"));
        assert_eq!(
            map(&[h.clone()], "USD").positions[0].average_buy_in,
            Some(d("25"))
        );
        h.purchase_price = None;
        assert_eq!(map(&[h], "USD").positions[0].average_buy_in, None);
    }

    #[test]
    fn a_sweep_is_cash_only_at_about_a_dollar_a_share() {
        let mapped = map(
            &[
                line(Some("SPAXX"), "100", Some("100.50")),
                line(None, "10", Some("10")),
                line(Some("ABXX"), "10", Some("50")),
                line(Some("VTI"), "10", Some("10")),
            ],
            "USD",
        );
        assert_eq!(
            mapped.sweep_by_currency,
            vec![("USD".to_owned(), d("110.50"))]
        );
        let tickers: Vec<&str> = mapped.positions.iter().map(|p| p.ticker.as_str()).collect();
        assert_eq!(tickers, ["ABXX", "VTI"]);
        assert!(mapped.complete);
    }

    #[test]
    fn lots_merge_by_ticker_with_a_weighted_buy_in_and_combined_value() {
        let mut a = line(Some("vti"), "1", Some("100"));
        a.purchase_price = Some(d("10"));
        let mut b = line(Some("VTI"), "3", Some("330"));
        b.purchase_price = Some(d("20"));
        let mapped = map(&[a, b.clone()], "USD");
        let p = &mapped.positions[0];
        assert_eq!(
            (p.quantity, p.average_buy_in, p.market_value),
            (d("4"), Some(d("17.5")), Some(d("430")))
        );
        assert_eq!(p.current_price(), Some(d("107.50000000")));

        let unknown = line(Some("VTI"), "1", None);
        let mapped = map(&[unknown, b], "USD");
        assert_eq!(mapped.positions[0].market_value, None);
        assert_eq!(mapped.positions[0].average_buy_in, None);
    }

    #[test]
    fn unstorable_lines_make_the_list_incomplete() {
        let mut eur = line(Some("VTI"), "1", Some("1"));
        eur.currency = Some("EUR".into());
        let mut bogus_currency = line(Some("VTI"), "1", Some("1"));
        bogus_currency.currency = Some("https://points".into());
        for raw in [
            vec![line(Some("VTI"), "-1", Some("1"))],
            vec![line(None, "1", Some("50"))],
            vec![bogus_currency],
            vec![line(Some("VTI"), "1", Some("1000000000000"))],
            vec![line(Some("VTI"), "1", Some("999999999999.999999995"))],
            vec![line(Some("VTI"), "0.00000001", Some("100000"))],
            vec![line(Some("VTI"), "1", Some("1")), eur],
        ] {
            assert!(!map(&raw, "USD").complete, "{raw:?}");
        }
        assert!(
            map(
                &[line(Some("VTI"), "1", Some("999999999999.999999994"))],
                "USD"
            )
            .complete
        );
        let mut unknown_zero = line(Some("VTI"), "0", Some("1"));
        unknown_zero.currency = Some("???".into());
        let nothing = map(&[unknown_zero], "USD");
        assert!(nothing.complete && nothing.positions.is_empty());
    }

    #[test]
    fn a_line_without_currency_inherits_the_accounts_and_codes_are_normalised() {
        let mut h = line(Some("VTI"), "1", Some("1"));
        assert_eq!(map(&[h.clone()], "CAD").positions[0].currency, "CAD");
        h.currency = Some(" usd ".into());
        assert_eq!(map(&[h], "CAD").positions[0].currency, "USD");
    }
}
