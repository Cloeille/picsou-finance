//! AccountService.valuation and everything built on it: the live balance, the invested amount
//! and the snapshot writes that derive one of them.
//!
//! Value and cost basis are computed together from one set of prices, so whatever is excluded
//! from one side is excluded from the other: an asset that cannot be priced leaves both.

use std::collections::BTreeSet;

use crate::db::DbHandle;
use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;

use super::liabilities;
use super::model::{Account, AccountHolding, AccountType, BalanceSnapshot, SnapshotSource};
use super::prices::{PriceNeeds, PriceRead, Quote};
use super::sync_values::java_is_blank;
use super::{accounts, holdings, loans, peer_loans, providers, snapshots, transactions};

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
    if holdings.is_empty() {
        // A CORUM Life contract keeps its own cost basis without lines too; both figures are
        // the EUR amounts CORUM reported, so an edited account currency must not convert one.
        if let Some(invested) = account.provider_invested_eur
            && account.is_synced_corum_life()
        {
            return Ok(Valuation::plain(account.current_balance, invested));
        }
        let cash = prices
            .to_eur(Some(account.current_balance), currency, ticker, today)
            .await;
        // Cash costs what it is worth, in the same currency on both sides.
        return Ok(Valuation::plain(cash, cash));
    }

    let quotes = quotes_for(prices, account, &holdings, today).await;
    let mut value = value_holdings(account, &holdings, &quotes);
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
    Ok(value.valuation)
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
        let has_ticker = !java_is_blank(&h.ticker);
        let quote = has_ticker
            .then(|| quotes.get(&h.ticker.to_uppercase()))
            .flatten();

        invested_over_all += cost_basis_of(h);
        match h.provider_value_eur {
            Some(v) => provider_valued_holdings += v,
            None => all_provider_valued = false,
        }

        let usable_quote = quote.filter(|q| !insurer || q.price > Decimal::ZERO);
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
        } else if has_ticker && let Some(v) = usable_provider_value {
            // Unpriceable at any age, but the connector reported the line's own EUR value: the
            // lookup failed (so not all priced) yet the line is valued, cost basis included.
            all_priced = false;
            live += v;
            any_priced = true;
            if insurer {
                any_stale = true;
            }
        } else if has_ticker {
            all_priced = false;
            if !h.quantity.is_zero() {
                tracing::warn!(
                    "No EUR price for holding {} (account {}) -- excluding it from both the live balance \
                     and the cost basis",
                    h.ticker,
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

/// Quotes for the account's holdings in one call; a CRYPTO account is resolved crypto-only so
/// an unmapped coin is never valued at a listed equity's price.
pub async fn quotes_for(
    prices: &dyn PriceRead,
    account: &Account,
    holdings: &[AccountHolding],
    today: NaiveDate,
) -> std::collections::HashMap<String, Quote> {
    if providers::is_never_market_priced(account) {
        return Default::default();
    }
    let tickers = tickers_of(holdings);
    if tickers.is_empty() {
        return Default::default();
    }
    prices
        .quotes(&tickers, account.account_type == AccountType::Crypto, today)
        .await
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
