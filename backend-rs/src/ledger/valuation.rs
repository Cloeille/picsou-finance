//! AccountService.valuation and everything built on it: the live balance, the invested amount
//! and the snapshot writes that derive one of them.
//!
//! Value and cost basis are computed together from one set of prices, so whatever is excluded
//! from one side is excluded from the other: an asset that cannot be priced leaves both.

use std::collections::{BTreeSet, HashMap};

use crate::db::DbHandle;
use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;

use super::decimal::{HALF_UP, divide, jmul, set_scale};
use super::liabilities;
use super::model::{
    Account, AccountHolding, AccountType, BalanceSnapshot, Debt, PeerLoanRepayment, PeerLoanTerms,
    SnapshotSource,
};
use super::prices::{PriceNeeds, PriceRead, Quote};
use super::sync_values::java_is_blank;
use super::{accounts, holdings, loans, peer_loans, providers, snapshots, transactions};

/// How far a quoted line value may stray from the provider's own before the quote is taken for
/// another instrument's. Two, not the 20 % a day's move would suggest: the provider value dates
/// from the last sync, which can be days old on a volatile line, while a wrong listing (a US
/// grey-market line, a currency mismatch, a GBp scale slip) misses by far more.
const PROVIDER_VALUE_TOLERANCE: Decimal = Decimal::TWO;

/// What an account is worth and what it cost.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Valuation {
    /// Current value; assets that could not be priced at all are left out.
    pub live_eur: Decimal,
    /// Cost basis over exactly the same assets.
    pub invested_eur: Decimal,
    /// False when at least one held asset had no price of any age.
    pub all_priced: bool,
    /// False only when the account holds assets and none could be valued: callers that persist
    /// a valuation must then refuse rather than record a blank.
    pub any_priced: bool,
    /// True when at least one price or line value is a recorded fallback.
    pub any_stale: bool,
}

impl Valuation {
    fn plain(live: Decimal, invested: Decimal) -> Self {
        Self {
            live_eur: live,
            invested_eur: invested,
            all_priced: true,
            any_priced: true,
            any_stale: false,
        }
    }
}

/// Batched database inputs for valuing a set of accounts without per-account queries.
#[derive(Default)]
pub struct ValuationBatch {
    pub holdings: HashMap<i64, Vec<AccountHolding>>,
    pub debts: HashMap<i64, Debt>,
    pub peer_terms: HashMap<i64, PeerLoanTerms>,
    pub peer_repayments: HashMap<i64, Vec<PeerLoanRepayment>>,
}

impl ValuationBatch {
    pub async fn load(conn: &mut DbHandle<'_>, accounts: &[Account]) -> sqlx::Result<Self> {
        if accounts.is_empty() {
            return Ok(Self::default());
        }
        let mut all_ids = Vec::with_capacity(accounts.len());
        let mut liability_ids = Vec::new();
        let mut peer_loan_ids = Vec::new();
        for a in accounts {
            all_ids.push(a.id);
            if a.account_type.is_liability() {
                liability_ids.push(a.id);
            } else if a.account_type == AccountType::PeerLoan {
                peer_loan_ids.push(a.id);
            }
        }
        let holdings = holdings::find_by_accounts(&mut *conn, &all_ids).await?;
        let debts = liabilities::debts_of_accounts(&mut *conn, &liability_ids)
            .await?
            .into_iter()
            .map(|d| (d.account_id, d))
            .collect();
        let peer_terms = liabilities::peer_loan_terms_of_accounts(&mut *conn, &peer_loan_ids)
            .await?
            .into_iter()
            .map(|t| (t.account_id, t))
            .collect();
        let mut peer_repayments: HashMap<i64, Vec<PeerLoanRepayment>> = HashMap::new();
        for r in liabilities::peer_loan_repayments_of_accounts(&mut *conn, &peer_loan_ids).await? {
            peer_repayments.entry(r.account_id).or_default().push(r);
        }
        Ok(Self {
            holdings,
            debts,
            peer_terms,
            peer_repayments,
        })
    }
}

/// Values one account from preloaded [`ValuationBatch`] data without touching the database.
pub async fn valuation_from_batch(
    prices: &dyn PriceRead,
    account: &Account,
    batch: &ValuationBatch,
    today: NaiveDate,
) -> Valuation {
    let currency = account.currency.as_str();
    let ticker = account.ticker.as_deref();
    match account.account_type {
        AccountType::Loan => {
            let outstanding = match batch
                .debts
                .get(&account.id)
                .filter(|d| loans::has_schedule(d))
            {
                Some(debt) => loans::compute_remaining_balance(debt, today),
                None => {
                    prices
                        .to_eur(Some(account.current_balance), currency, ticker, today)
                        .await
                }
            };
            return Valuation::plain(outstanding, account.current_balance);
        }
        AccountType::PeerLoan => {
            let remaining = batch.peer_terms.get(&account.id).map(|terms| {
                let repayments = batch
                    .peer_repayments
                    .get(&account.id)
                    .map(Vec::as_slice)
                    .unwrap_or(&[]);
                peer_loans::principal_remaining(terms, repayments)
            });
            let value = match remaining {
                Some(remaining) => remaining,
                None => {
                    prices
                        .to_eur(Some(account.current_balance), currency, ticker, today)
                        .await
                }
            };
            return Valuation::plain(value, value);
        }
        _ => {}
    }

    let lines = batch
        .holdings
        .get(&account.id)
        .map(Vec::as_slice)
        .unwrap_or(&[]);
    valuation_with_holdings(prices, account, lines, today).await
}

/// AccountService.valuation.
pub async fn valuation(
    conn: &mut DbHandle<'_>,
    prices: &dyn PriceRead,
    account: &Account,
    today: NaiveDate,
) -> sqlx::Result<Valuation> {
    let currency = account.currency.as_str();
    let ticker = account.ticker.as_deref();
    match account.account_type {
        AccountType::Loan => {
            // A Debt without dates has no schedule, and its remaining balance is the whole
            // borrowed amount; the balance the user typed is the better figure.
            let outstanding = match liabilities::debt_of_account(&mut *conn, account.id)
                .await?
                .filter(loans::has_schedule)
            {
                Some(debt) => loans::compute_remaining_balance(&debt, today),
                None => {
                    prices
                        .to_eur(Some(account.current_balance), currency, ticker, today)
                        .await
                }
            };
            return Ok(Valuation::plain(outstanding, account.current_balance));
        }
        AccountType::PeerLoan => {
            let remaining =
                match liabilities::peer_loan_terms_of_account(&mut *conn, account.id).await? {
                    Some(terms) => {
                        let repayments =
                            liabilities::peer_loan_repayments_of_account(&mut *conn, account.id)
                                .await?;
                        Some(peer_loans::principal_remaining(&terms, &repayments))
                    }
                    None => None,
                };
            let value = match remaining {
                Some(remaining) => remaining,
                None => {
                    prices
                        .to_eur(Some(account.current_balance), currency, ticker, today)
                        .await
                }
            };
            // Invested equals live so the receivable contributes nothing to the P&L series.
            return Ok(Valuation::plain(value, value));
        }
        _ => {}
    }

    let holdings = holdings::find_by_account(&mut *conn, account.id).await?;
    Ok(valuation_with_holdings(prices, account, &holdings, today).await)
}

async fn valuation_with_holdings(
    prices: &dyn PriceRead,
    account: &Account,
    holdings: &[AccountHolding],
    today: NaiveDate,
) -> Valuation {
    let currency = account.currency.as_str();
    let ticker = account.ticker.as_deref();
    if holdings.is_empty() {
        // A CORUM Life contract keeps its own cost basis without lines too; both figures are
        // the EUR amounts CORUM reported, so an edited account currency must not convert one.
        if let Some(invested) = account.provider_invested_eur
            && account.is_synced_corum_life()
        {
            return Valuation::plain(account.current_balance, invested);
        }
        let cash = prices
            .to_eur(Some(account.current_balance), currency, ticker, today)
            .await;
        // Cash costs what it is worth, in the same currency on both sides.
        return Valuation::plain(cash, cash);
    }

    let quotes = quotes_for(prices, account, holdings, today).await;
    let mut value = value_holdings(account, holdings, &quotes);
    if value.any_cost_basis_unknown {
        // The PER purchase basis is not reported: pair its current valuation with itself.
        let insurer = providers::is_insurer_contract(account);
        let mut baseline = if insurer {
            value.valuation.live_eur
        } else {
            account.current_balance
        };
        if !insurer && !currency.eq_ignore_ascii_case("EUR") {
            // The baseline is in EUR, while an import may store the balance in its own
            // currency; with no rate, the live value stands in.
            baseline = match prices.fx_rate_eur(&currency.to_uppercase()).await {
                Some(rate) => baseline * rate,
                None => value.valuation.live_eur,
            };
        }
        value.valuation.invested_eur = baseline;
    }
    value.valuation
}

struct HoldingsValue {
    valuation: Valuation,
    any_cost_basis_unknown: bool,
}

/// The per-line pass of valuation, with the provider-valued overrides; pure.
fn value_holdings(
    account: &Account,
    holdings: &[AccountHolding],
    quotes: &std::collections::HashMap<String, Quote>,
) -> HoldingsValue {
    let cash_balance = account.cash_balance.unwrap_or(Decimal::ZERO);
    let mut live = cash_balance;
    let mut invested = cash_balance;
    // Cost basis over every holding, priced or not: only the provider-valued override uses it,
    // because that override replaces the value with a total covering every holding too.
    let mut invested_over_all = cash_balance;
    // The provider's own line values over every holding: tells how much of its total is cash.
    let mut provider_valued_holdings = Decimal::ZERO;
    let mut all_provider_valued = true;
    let mut all_priced = true;
    let mut any_priced = false;
    let mut any_stale = false;
    let mut any_cost_basis_unknown = false;
    let insurer = providers::is_insurer_contract(account);

    for h in holdings {
        let (lookup_key, quote) = quote_for(account, h, quotes);
        // A line with an ISIN or a ticker had a price to look up; it is not "no identifier".
        let has_lookup_key = lookup_key.is_some();

        invested_over_all += cost_basis_of(h);
        match h.provider_value_eur {
            Some(v) => provider_valued_holdings += v,
            None => all_provider_valued = false,
        }

        let usable_quote = quote
            .filter(|q| !insurer || q.price > Decimal::ZERO)
            .filter(|q| {
                !provider_overrules_quote(account, h.quantity, q.price, h.provider_value_eur)
            });
        let usable_provider_value = h
            .provider_value_eur
            .filter(|v| !insurer || *v > Decimal::ZERO);
        if insurer
            && usable_quote.is_none()
            && (quote.is_some()
                || (h.provider_value_eur.is_some() && usable_provider_value.is_none()))
        {
            any_stale = true;
        }
        if let Some(q) = usable_quote {
            any_priced = true;
            if !q.live {
                any_stale = true;
            }
            live += h.quantity * q.price;
        } else if has_lookup_key && let Some(v) = usable_provider_value {
            // Unpriceable at any age, or its quote overruled by the provider (another listing's
            // price, #683), but the connector reported the line's own EUR value: the lookup
            // failed (so not all priced) yet the line is valued, cost basis included.
            all_priced = false;
            live += v;
            any_priced = true;
            if insurer {
                any_stale = true;
            }
        } else if let Some(key) = lookup_key {
            all_priced = false;
            if !h.quantity.is_zero() {
                tracing::warn!(
                    "No EUR price for holding {} (account {}) -- excluding it from both the live balance \
                     and the cost basis",
                    key,
                    account.id
                );
            }
            continue;
        } else if let Some(v) = usable_provider_value {
            // No ticker to price, but a reported line value: valued, and no lookup failed.
            live += v;
            any_priced = true;
            if insurer {
                any_stale = true;
            }
        } else {
            all_priced = false;
            continue;
        }

        let cost_basis = provider_cost_basis_eur(h).or(h.average_buy_in.map(|b| b * h.quantity));
        match cost_basis {
            Some(c) => invested += c,
            None => any_cost_basis_unknown = true,
        }
    }

    // A provider that values the account and states the contract's own cost basis (CORUM Life)
    // is taken at its word on both sides.
    if let Some(provider_invested) = account.provider_invested_eur
        && providers::is_provider_valued(account)
    {
        return HoldingsValue {
            valuation: Valuation {
                live_eur: account.current_balance,
                invested_eur: provider_invested,
                all_priced,
                any_priced: true,
                any_stale,
            },
            any_cost_basis_unknown: false,
        };
    }

    // A provider's authoritative EUR total beats a partial one (cash plus whatever resolved);
    // the cost side then covers every position too.
    if !all_priced && providers::is_provider_valued(account) {
        if account.cash_balance.is_none() && all_provider_valued && !any_cost_basis_unknown {
            // The total folds in an idle cash pocket; with every line provider-valued, the
            // residual is that cash, so it goes on the cost side too and cancels out (#106).
            let cash_pocket = account.current_balance - provider_valued_holdings;
            if cash_pocket > Decimal::ZERO {
                invested_over_all += cash_pocket;
            }
        }
        live = account.current_balance;
        invested = invested_over_all;
        any_priced = true;
    }

    HoldingsValue {
        valuation: Valuation {
            live_eur: live,
            invested_eur: invested,
            all_priced,
            any_priced,
            any_stale,
        },
        any_cost_basis_unknown,
    }
}

/// The computed figures of a single holding row, shared by the holdings API and the account
/// workbook export so both apply the exact same quote, insurer and provider-override rules.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ValuedHolding {
    pub current_price: Option<Decimal>,
    pub quote_currency: Option<String>,
    pub current_value_eur: Option<Decimal>,
    pub cost_basis_eur: Option<Decimal>,
    pub pnl_eur: Option<Decimal>,
    pub pnl_percent: Option<Decimal>,
    pub price_updated_at: Option<DateTime<Utc>>,
    pub price_as_of: Option<NaiveDate>,
    pub price_stale: bool,
}

/// Values one holding line against `quotes`, matching `AccountService.toHoldingResponse`.
pub fn value_holding(
    account: &Account,
    holding: &AccountHolding,
    quotes: &HashMap<String, Quote>,
) -> ValuedHolding {
    let mut current_price = holding.current_price;
    let quantity = holding.quantity;
    let mut quote_currency = holding.quote_currency.clone();
    let mut current_price_eur = None;
    let mut price_updated_at = None;
    let mut price_as_of = None;
    let mut price_stale = false;

    let insurer = providers::is_insurer_contract(account);
    let has_provider_value = holding.provider_value_eur.is_some();
    let provider_value = holding
        .provider_value_eur
        .filter(|v| !(insurer && *v <= Decimal::ZERO));
    let (lookup_key, quote) = quote_for(account, holding, quotes);
    if lookup_key.is_some() {
        let usable = quote.filter(|q| !insurer || q.price > Decimal::ZERO);
        let overruled = usable.is_some_and(|q| {
            provider_overrules_quote(account, quantity, q.price, holding.provider_value_eur)
        });
        if overruled {
            // The line falls back to the provider value below. Unlike a missing quote it is
            // flagged stale and dated by the provider: the figure shown is the bank's last
            // valuation, not a market price.
            price_as_of = holding.provider_price_date;
            price_stale = true;
        } else if let Some(q) = usable {
            current_price_eur = Some(q.price);
            if insurer {
                current_price = Some(q.price);
                quote_currency = Some("EUR".to_owned());
            }
            price_as_of = Some(q.as_of);
            price_stale = !q.live;
        } else if insurer && (quote.is_some() || has_provider_value) {
            price_stale = true;
        }
        price_updated_at = holding.last_synced_at;
    } else if insurer && has_provider_value {
        price_stale = true;
    }

    if insurer
        && current_price_eur.is_none()
        && let Some(value) = provider_value
    {
        // The bank's line valuation is the fallback; the unit price stays aligned with it.
        current_price = (!quantity.is_zero()).then(|| divide(value, quantity, 8, HALF_UP));
        quote_currency = Some("EUR".to_owned());
        price_as_of = holding.provider_price_date;
        price_stale = true;
    }

    let average_buy_in = holding.average_buy_in;
    let cost_basis_eur = provider_value
        .and_then(|_| provider_cost_basis_eur(holding))
        .or(average_buy_in.map(|b| jmul(b, quantity)));
    let current_value_eur = current_price_eur
        .map(|p| jmul(p, quantity))
        .or(provider_value);
    let pnl_eur = match (current_value_eur, cost_basis_eur) {
        (Some(value), Some(cost)) => Some(value - cost),
        _ => holding.provider_pnl_eur,
    };
    // abs(): a short's cost basis is negative, and the percentage keeps the P&L's own sign.
    let pnl_percent = match (pnl_eur, cost_basis_eur) {
        (Some(pnl), Some(cost)) if !cost.is_zero() => Some(jmul(
            divide(pnl, cost.abs(), 4, HALF_UP),
            Decimal::ONE_HUNDRED,
        )),
        _ => None,
    };

    ValuedHolding {
        current_price,
        quote_currency,
        current_value_eur,
        cost_basis_eur,
        pnl_eur,
        pnl_percent,
        price_updated_at,
        price_as_of,
        price_stale,
    }
}

/// Whether a synced provider-valued account's own figure for this line overrules `price_eur`.
/// OpenFIGI can resolve an ISIN to a listing Yahoo quotes at another instrument's price
/// (LU1829219390 to MULYF, 376 EUR for a 4.41 EUR ETF, #683); the bank values the position it
/// holds, so a quote it contradicts is dropped and the line keeps the provider value, as it does
/// with no quote. Manual accounts (such as Finary imports carrying a bank name and a frozen
/// `provider_value_eur`) stay outside the guard and keep their live quote.
pub fn provider_overrules_quote(
    account: &Account,
    quantity: Decimal,
    price_eur: Decimal,
    provider_value_eur: Option<Decimal>,
) -> bool {
    !account.is_manual
        && providers::is_provider_valued(account)
        && quote_contradicts_provider_value(quantity, price_eur, provider_value_eur)
}

/// `quantity x price_eur` more than [`PROVIDER_VALUE_TOLERANCE`] times off the provider value,
/// either way, or overflowing `Decimal`. Magnitudes are compared, so a short line judges like a
/// long one.
pub fn quote_contradicts_provider_value(
    quantity: Decimal,
    price_eur: Decimal,
    provider_value_eur: Option<Decimal>,
) -> bool {
    let Some(provider_value) = provider_value_eur else {
        return false;
    };
    let provider = provider_value.abs();
    if provider.is_zero() {
        return false;
    }
    let Some(quoted) = quantity.checked_mul(price_eur) else {
        return true;
    };
    let quoted = quoted.abs();
    if quoted.is_zero() {
        return false;
    }
    let beyond = |a: Decimal, b: Decimal| {
        b.checked_mul(PROVIDER_VALUE_TOLERANCE)
            .is_some_and(|m| a > m)
    };
    beyond(quoted, provider) || beyond(provider, quoted)
}

pub(crate) struct PositionValuation {
    pub value: Option<Decimal>,
    pub cost_basis: Option<Decimal>,
    pub pnl: Option<Decimal>,
    pub pnl_percent: Option<Decimal>,
}

pub(crate) fn position_valuation(
    account_id: i64,
    quantity: Decimal,
    price: Option<Decimal>,
    average_buy_in: Option<Decimal>,
) -> PositionValuation {
    let checked = |result: Option<Decimal>, field| {
        if result.is_none() {
            tracing::warn!(account_id, field, "Exchange position valuation overflow");
        }
        result
    };
    let value = price.and_then(|p| checked(p.checked_mul(quantity), "current value"));
    let cost_basis = average_buy_in.and_then(|a| checked(a.checked_mul(quantity), "cost basis"));
    let pnl = value
        .zip(cost_basis)
        .and_then(|(v, c)| checked(v.checked_sub(c), "profit and loss"));
    let pnl_percent = pnl
        .zip(cost_basis)
        .filter(|(_, c)| !c.is_zero())
        .and_then(|(p, c)| {
            // The absolute cost basis keeps the sign of the P&L itself.
            let result = p
                .checked_div(c.abs())
                .and_then(|ratio| set_scale(ratio, 4, HALF_UP).checked_mul(Decimal::ONE_HUNDRED));
            checked(result, "profit and loss percentage")
        });
    PositionValuation {
        value,
        cost_basis,
        pnl,
        pnl_percent,
    }
}
/// A holding's EUR cost basis: the provider's figure, else averageBuyIn x quantity, else zero.
pub fn cost_basis_of(holding: &AccountHolding) -> Decimal {
    provider_cost_basis_eur(holding)
        .or(holding.average_buy_in.map(|b| b * holding.quantity))
        .unwrap_or(Decimal::ZERO)
}

/// The provider's cost basis of a line: its value minus its unrealized P&L, when it reports both.
pub fn provider_cost_basis_eur(holding: &AccountHolding) -> Option<Decimal> {
    Some(holding.provider_value_eur? - holding.provider_pnl_eur?)
}

/// The holdings' non-blank tickers, upper-cased and deduplicated.
pub fn tickers_of(holdings: &[AccountHolding]) -> BTreeSet<String> {
    holdings
        .iter()
        .filter(|h| !java_is_blank(&h.ticker))
        .map(|h| h.ticker.to_uppercase())
        .collect()
}

/// The primary key a line's quote is read under in [`quotes_for`]: its ISIN when it has one,
/// else its upper-cased ticker. None for a line with neither. A crypto account keeps its tickers.
pub fn price_key(account: &Account, holding: &AccountHolding) -> Option<String> {
    if account.account_type != AccountType::Crypto
        && let Some(isin) = &holding.isin
    {
        return Some(isin.clone());
    }
    (!java_is_blank(&holding.ticker)).then(|| holding.ticker.to_uppercase())
}

/// Fallback ticker key for an ISIN-carrying line on a non-crypto account when the ISIN sources
/// returned no quote nor a recorded ISIN price (temporary until #706 removes tickers).
fn fallback_ticker_key(account: &Account, holding: &AccountHolding) -> Option<String> {
    if account.account_type == AccountType::Crypto || holding.isin.is_none() {
        return None;
    }
    (!java_is_blank(&holding.ticker)).then(|| holding.ticker.to_uppercase())
}

/// Resolves a holding's quote from `quotes`, returning `(key_used, quote)`. On a non-crypto
/// account with an ISIN, the ISIN quote wins; when no ISIN quote is present, falls back to the
/// holding's upper-cased ticker so holdings that were priced before #701 keep their price during
/// ISIN source misses, outages, or unsupported domiciles.
fn quote_for<'a>(
    account: &Account,
    holding: &AccountHolding,
    quotes: &'a HashMap<String, Quote>,
) -> (Option<String>, Option<&'a Quote>) {
    let primary = price_key(account, holding);
    if let Some(ref key) = primary
        && let Some(quote) = quotes.get(key)
    {
        return (primary, Some(quote));
    }
    if let Some(fallback) = fallback_ticker_key(account, holding)
        && let Some(quote) = quotes.get(&fallback)
    {
        return (Some(fallback), Some(quote));
    }
    (primary, None)
}

/// Quotes for the account's holdings: a line with an ISIN on a non-crypto account is priced by
/// its ISIN first, falling back to its ticker when the ISIN sources return nothing (until #706
/// removes tickers). Lines without an ISIN, and every line of a
/// CRYPTO account, are priced by ticker; a CRYPTO account is resolved crypto-only so an unmapped
/// coin is never valued at a listed equity's price.
pub async fn quotes_for(
    prices: &dyn PriceRead,
    account: &Account,
    holdings: &[AccountHolding],
    today: NaiveDate,
) -> std::collections::HashMap<String, Quote> {
    if providers::is_never_market_priced(account) {
        return Default::default();
    }
    let crypto = account.account_type == AccountType::Crypto;
    let mut tickers = BTreeSet::new();
    let mut isins = BTreeSet::new();
    for h in holdings {
        match (crypto, &h.isin) {
            (false, Some(isin)) => isins.insert(isin.clone()),
            _ if !java_is_blank(&h.ticker) => tickers.insert(h.ticker.to_uppercase()),
            _ => false,
        };
    }
    let isin_quotes = if isins.is_empty() {
        Default::default()
    } else {
        prices.isin_quotes(&isins, today).await
    };
    if !crypto {
        for h in holdings {
            if let Some(isin) = &h.isin
                && !isin_quotes.contains_key(isin)
                && !java_is_blank(&h.ticker)
            {
                tickers.insert(h.ticker.to_uppercase());
            }
        }
    }
    let mut quotes = if tickers.is_empty() {
        Default::default()
    } else {
        prices.quotes(&tickers, crypto, today).await
    };
    quotes.extend(isin_quotes);
    quotes
}

/// What valuing the account and rebuilding its manual ledger read, as the database has them
/// now: the rate of its currency and of each currency its rows were entered in, its own ticker
/// and the quotes of its holdings. For PriceRead::prefetch, before a write on it opens.
pub async fn needs_of(conn: &mut DbHandle<'_>, account: &Account) -> sqlx::Result<PriceNeeds> {
    let holdings = holdings::find_by_account(&mut *conn, account.id).await?;
    let mut needs = PriceNeeds::of(account, &holdings);
    for currency in transactions::native_currencies(&mut *conn, account.id).await? {
        needs.currency(&currency);
    }
    Ok(needs)
}

/// needs_of for the member's account with this external id, nothing when there is none yet.
/// A sync values the account as stored: with a currency or a ticker the member may have
/// edited, and with holdings it may leave alone. It announces that together with what its
/// payload brings.
pub async fn needs_of_external(
    conn: &mut DbHandle<'_>,
    member_id: i64,
    external_id: &str,
) -> sqlx::Result<PriceNeeds> {
    match accounts::find_by_external_id_and_member(&mut *conn, external_id, member_id).await? {
        Some(account) => needs_of(conn, &account).await,
        None => Ok(PriceNeeds::default()),
    }
}

/// The rate of the currency and the quote of the own ticker of each synced account of the
/// member, as stored. A connector that finds its accounts by itself (the bank connectors built on
/// SyncTemplate) announces these with what its payload brings: the member may have edited either
/// on a synced account.
pub async fn account_needs_of_member(
    conn: &mut DbHandle<'_>,
    member_id: i64,
) -> sqlx::Result<PriceNeeds> {
    let stored: Vec<(String, Option<String>)> = crate::db::query_as(
        "SELECT DISTINCT a.currency, a.ticker FROM account a \
         WHERE a.member_id = $1 AND a.deleted_at IS NULL AND a.external_account_id IS NOT NULL",
    )
    .bind(member_id)
    .fetch_all(&mut *conn)
    .await?;
    let mut needs = PriceNeeds::default();
    for (currency, ticker) in &stored {
        needs.currency(currency);
        if let Some(ticker) = ticker {
            needs.ticker(ticker);
        }
    }
    Ok(needs)
}

/// The rates rebuilding the manual ledgers of a member can read: the currencies of their
/// accounts and of the rows entered on them. For PriceRead::prefetch, before an import that
/// rebuilds whichever of them it writes into.
pub async fn currency_needs_of_member(
    conn: &mut DbHandle<'_>,
    member_id: i64,
) -> sqlx::Result<PriceNeeds> {
    let currencies: Vec<String> = crate::db::query_scalar(
        "SELECT DISTINCT a.currency FROM account a WHERE a.member_id = $1 \
         UNION SELECT DISTINCT t.native_currency FROM \"transaction\" t \
         JOIN account a ON a.id = t.account_id \
         WHERE a.member_id = $1 AND t.native_currency IS NOT NULL",
    )
    .bind(member_id)
    .fetch_all(&mut *conn)
    .await?;
    let mut needs = PriceNeeds::default();
    for currency in &currencies {
        needs.currency(currency);
    }
    Ok(needs)
}

/// AccountService.liveBalanceEur.
pub async fn live_balance_eur(
    conn: &mut DbHandle<'_>,
    prices: &dyn PriceRead,
    account: &Account,
    today: NaiveDate,
) -> sqlx::Result<Decimal> {
    Ok(valuation(conn, prices, account, today).await?.live_eur)
}

/// The live balance with the liability sign: a LOAN is negative, for net-worth sums.
pub async fn signed_live_balance_eur(
    conn: &mut DbHandle<'_>,
    prices: &dyn PriceRead,
    account: &Account,
    today: NaiveDate,
) -> sqlx::Result<Decimal> {
    let value = live_balance_eur(conn, prices, account, today).await?;
    Ok(if account.account_type == AccountType::Loan {
        -value
    } else {
        value
    })
}

/// AccountService.calculateInvestedAmount.
pub async fn invested_amount(
    conn: &mut DbHandle<'_>,
    prices: &dyn PriceRead,
    account: &Account,
    today: NaiveDate,
) -> sqlx::Result<Decimal> {
    Ok(valuation(conn, prices, account, today).await?.invested_eur)
}

/// Where a snapshot write gets its prices, date and instant.
pub struct SnapshotContext<'a> {
    pub prices: &'a dyn PriceRead,
    pub today: NaiveDate,
    pub now: DateTime<Utc>,
}

/// AccountService.upsertSnapshot(account, balance, date): the invested amount is the account's
/// current cost basis.
pub async fn upsert_snapshot(
    conn: &mut DbHandle<'_>,
    ctx: &SnapshotContext<'_>,
    account: &Account,
    balance_eur: Decimal,
    date: NaiveDate,
) -> sqlx::Result<BalanceSnapshot> {
    let invested = invested_amount(&mut *conn, ctx.prices, account, ctx.today).await?;
    snapshots::upsert(conn, account.id, balance_eur, invested, date, ctx.now).await
}

/// AccountService.upsertSnapshotFromNative: converts a balance in the account's currency to EUR
/// (at today's rate, even back-dated) before storing it; a MANUAL row keeps its native figure
/// and entry instant current.
pub async fn upsert_snapshot_from_native(
    conn: &mut DbHandle<'_>,
    ctx: &SnapshotContext<'_>,
    account: &Account,
    native_balance: Decimal,
    date: NaiveDate,
) -> sqlx::Result<BalanceSnapshot> {
    let eur = ctx
        .prices
        .to_eur(
            Some(native_balance),
            &account.currency,
            account.ticker.as_deref(),
            ctx.today,
        )
        .await;
    let snapshot = upsert_snapshot(&mut *conn, ctx, account, eur, date).await?;
    if snapshot.source == SnapshotSource::Manual {
        return snapshots::set_native_balance(conn, snapshot.id, native_balance, ctx.now).await;
    }
    Ok(snapshot)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(value: &str) -> Decimal {
        value.parse().expect("decimal literal")
    }

    #[test]
    fn quote_contradicts_provider_value_only_beyond_a_factor_of_two() {
        let qty = d("80");
        let bank = Some(d("353.08"));
        assert!(quote_contradicts_provider_value(qty, d("376.28"), bank));
        assert!(quote_contradicts_provider_value(qty, d("2.20"), bank));
        assert!(!quote_contradicts_provider_value(qty, d("8.82"), bank));
        assert!(!quote_contradicts_provider_value(qty, d("2.21"), bank));
        // Exactly twice the bank value is still the market's.
        assert!(!quote_contradicts_provider_value(
            Decimal::ONE,
            d("200"),
            Some(d("100"))
        ));
        // A short line compares magnitudes.
        assert!(quote_contradicts_provider_value(
            d("-80"),
            d("376.28"),
            Some(d("-353.08"))
        ));
        // Nothing to compare against: the quote stands.
        assert!(!quote_contradicts_provider_value(qty, d("376.28"), None));
        assert!(!quote_contradicts_provider_value(
            qty,
            d("376.28"),
            Some(Decimal::ZERO)
        ));
        assert!(!quote_contradicts_provider_value(
            Decimal::ZERO,
            d("376.28"),
            bank
        ));
    }

    #[test]
    fn quote_contradicts_provider_value_treats_overflow_as_contradiction() {
        assert!(quote_contradicts_provider_value(
            Decimal::MAX,
            d("2"),
            Some(d("353.08"))
        ));
        assert!(quote_contradicts_provider_value(
            d("-1000000000000000"),
            d("1000000000000000"),
            Some(d("-353.08"))
        ));
        // Still no contradiction when there is no non-zero provider value to compare against.
        assert!(!quote_contradicts_provider_value(
            Decimal::MAX,
            d("2"),
            None
        ));
        assert!(!quote_contradicts_provider_value(
            Decimal::MAX,
            d("2"),
            Some(Decimal::ZERO)
        ));
        // Equal extreme values do not contradict each other.
        assert!(!quote_contradicts_provider_value(
            Decimal::ONE,
            Decimal::MAX,
            Some(Decimal::MAX)
        ));
    }
}
