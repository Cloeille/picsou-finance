//! Detected monthly investment inflow for the wealth projection.
//!
//! Scans the last six rolling calendar months (including the current in-progress month) for
//! regular net external cash inflows into the member's investment perimeter: investment accounts
//! (`Equity` / `Crypto` wealth tiers) plus dedicated broker cash accounts.
//!
//! A `CHECKING` account qualifies as a dedicated broker cash account only when:
//! 1. Its provider also holds at least one of the member's investment accounts;
//! 2. It actually feeds a same-provider investment account during the 6-month window (either via
//!    a paired transfer into that investment account or via `TRADING`/`BUY` debits);
//! 3. It does not receive salary-like credits (categorised non-capital `INCOME`, salary/payroll
//!    labels, or recurring credits >= 500 EUR from the same emitter in >= 2 distinct months).
//!
//! Internal transfers within the perimeter (for instance broker cash -> PEA, CTO -> PEA),
//! transfers out of savings passbooks (`SafetyNet` -> investment), trade executions (`BUY`/`SELL`/
//! Trade Republic `TRADING` rows), dividends, interest, fees, and budget-neutral rows are
//! excluded so contributions are never double-counted or inflated by yields or capital gains.
//! Everyday debit-card expenses on a broker cash account are intentionally kept so they reduce
//! that month's net inflow.
//!
//! Foreign-currency transactions are converted using `PriceRead::fx_rate_eur`; any month
//! containing a perimeter row whose currency has no available FX rate is excluded rather than
//! adding an unconverted amount as EUR.
//!
//! The detected figure is offered as a suggestion only and is never injected into the projection.

use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

use chrono::{Datelike, Days, Months, NaiveDate};
use regex::Regex;
use rust_decimal::Decimal;
use rust_decimal::prelude::ToPrimitive;
use unicode_normalization::UnicodeNormalization;

use super::math::scale2;
use super::pyramid::WealthPyramidResponse;
use crate::accounts::classification::overrides_of;
use crate::accounts::tracked_fund;
use crate::db::{self, DbHandle};
use crate::ledger::access::{self, weigh};
use crate::ledger::decimal::{HALF_UP, divide, set_scale};
use crate::ledger::model::{
    Account, AccountType, AssetClass, CategoryKind, TRANSACTION_COLUMNS, Transaction,
    TransactionType, WealthTier,
};
use crate::ledger::prices::PriceRead;
use crate::ledger::sync_values::java_is_blank;

const WINDOW_MONTHS: usize = 6;
const RECENT_WINDOW_MONTHS: usize = 3;
const MIN_ACTIVE_MONTHS_IN_RECENT_WINDOW: usize = 2;
const TRANSFER_MATCH_DAYS: i64 = 3;
const CAPITAL_INCOME_SLUG: &str = "revenus-capital";
const SALARY_MIN_RECURRING_EUR: i64 = 500;
const SALARY_MIN_RECURRING_MONTHS: usize = 2;

/// Non-contribution yield / capital-gain labels on cash credits when providers do not stamp a
/// dedicated `tx_type` or category (e.g. bank-synced interest or dividend payouts).
static YIELD_LABELS: LazyLock<Vec<Regex>> = LazyLock::new(|| {
    [
        r"\bdivid",
        r"\bcoupon\b",
        r"\binterets?\b",
        r"\binterests?\b",
        r"\binteres(es)?\b",
        r"\bzinsen\b",
        r"\bplus[\s-]value",
        r"\bcapital\s+gain",
        r"\bstaking\b",
    ]
    .iter()
    .map(|p| Regex::new(&format!("(?i){p}")).expect("yield label regex"))
    .collect()
});

/// Salary / employer payroll labels used to disqualify everyday bank checking accounts even when
/// transactions are not categorised yet.
static SALARY_LABELS: LazyLock<Vec<Regex>> = LazyLock::new(|| {
    [
        r"\bsalaires?\b",
        r"\bsalary\b",
        r"\bpayroll\b",
        r"\bemployeur\b",
        r"\bremuneration\b",
        r"\bgehalt\b",
        r"\bnomina\b",
    ]
    .iter()
    .map(|p| Regex::new(&format!("(?i){p}")).expect("salary label regex"))
    .collect()
});

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DetectedInflow {
    pub monthly_eur: Decimal,
    pub active_months: usize,
    pub account_id: Option<i64>,
}

#[derive(Debug, Clone)]
struct CategoryMeta {
    kind: CategoryKind,
    slug: Option<String>,
}

/// Per-call FX cache so each non-EUR currency is queried at most once, and missing rates are
/// surfaced as `None` instead of falling back to unconverted native amounts.
struct FxCache<'a> {
    prices: &'a dyn PriceRead,
    rates: HashMap<String, Option<Decimal>>,
}

impl<'a> FxCache<'a> {
    fn new(prices: &'a dyn PriceRead) -> Self {
        Self {
            prices,
            rates: HashMap::new(),
        }
    }

    async fn convert_to_eur(&mut self, amount: Decimal, currency: &str) -> Option<Decimal> {
        if currency.eq_ignore_ascii_case("EUR") {
            return Some(amount);
        }
        let code = currency.trim().to_uppercase();
        if code.is_empty() {
            return None;
        }
        let rate = match self.rates.get(&code) {
            Some(cached) => *cached,
            None => {
                let fetched = self.prices.fx_rate_eur(&code).await;
                self.rates.insert(code, fetched);
                fetched
            }
        }?;
        Some(amount * rate)
    }
}

#[derive(Default)]
struct AccountActivity {
    direct: HashMap<i64, Decimal>,
    internal: HashMap<i64, Decimal>,
    buy: HashMap<i64, Decimal>,
    broker_provider: HashMap<String, Decimal>,
}

fn cents(amount: Decimal) -> i64 {
    (set_scale(amount, 2, HALF_UP) * Decimal::ONE_HUNDRED)
        .to_i64()
        .unwrap_or_default()
}

fn provider_key(account: &Account) -> Option<String> {
    account
        .provider
        .as_deref()
        .map(str::trim)
        .filter(|p| !p.is_empty())
        .map(str::to_lowercase)
}

fn fold_accents(input: &str) -> String {
    input
        .nfd()
        .filter(|c| !unicode_normalization::char::is_combining_mark(*c))
        .collect()
}

fn is_yield_description(description: &str) -> bool {
    if java_is_blank(description) {
        return false;
    }
    let folded = fold_accents(description);
    YIELD_LABELS.iter().any(|rx| rx.is_match(&folded))
}

fn is_salary_description(description: &str) -> bool {
    if java_is_blank(description) {
        return false;
    }
    let folded = fold_accents(description);
    SALARY_LABELS.iter().any(|rx| rx.is_match(&folded))
}

/// Normalised emitter key for detecting recurring salary-like credits on a candidate checking
/// account: prefers `counterparty`, then `merchant_label`, then a cleaned description.
fn emitter_key(tx: &Transaction) -> Option<String> {
    let raw = tx
        .counterparty
        .as_deref()
        .filter(|s| !java_is_blank(s))
        .or_else(|| tx.merchant_label.as_deref().filter(|s| !java_is_blank(s)))
        .or_else(|| (!java_is_blank(&tx.description)).then_some(tx.description.as_str()))?;
    let folded = fold_accents(raw).to_lowercase();
    let tokens: Vec<String> = folded
        .split(|c: char| !c.is_alphabetic())
        .filter(|tok| tok.len() >= 2)
        .map(str::to_owned)
        .collect();
    (!tokens.is_empty()).then(|| tokens.join(" "))
}

fn is_trade_or_yield_kind(kind: Option<&str>) -> bool {
    let Some(raw) = kind.map(str::trim).filter(|s| !s.is_empty()) else {
        return false;
    };
    raw.eq_ignore_ascii_case("BUY")
        || raw.eq_ignore_ascii_case("SELL")
        || raw.eq_ignore_ascii_case("SAVINGS_PLAN_EXECUTED")
        || raw.eq_ignore_ascii_case("FEE")
        || raw.eq_ignore_ascii_case("DIVIDEND")
        || raw.eq_ignore_ascii_case("INTEREST")
        || raw.eq_ignore_ascii_case("COUPON")
        || raw.eq_ignore_ascii_case("DISTRIBUTION")
        || raw.eq_ignore_ascii_case("EARNINGS")
        || raw.eq_ignore_ascii_case("CAPITAL_GAIN")
        || raw.eq_ignore_ascii_case("PLUS_VALUE")
        || raw.eq_ignore_ascii_case("REWARD")
        || raw.eq_ignore_ascii_case("STAKING")
}

fn is_trade_or_yield_raw_category(category: Option<&str>) -> bool {
    let Some(raw) = category.map(str::trim).filter(|s| !s.is_empty()) else {
        return false;
    };
    raw.eq_ignore_ascii_case("TRADING")
        || raw.eq_ignore_ascii_case("DIVIDEND")
        || raw.eq_ignore_ascii_case("INTEREST")
        || raw.eq_ignore_ascii_case("EARNINGS")
        || raw.eq_ignore_ascii_case("COUPON")
        || raw.eq_ignore_ascii_case("REWARD")
}

fn is_non_contribution_row(tx: &Transaction, category: Option<&CategoryMeta>) -> bool {
    if tx.budget_neutral || tx.amount.is_zero() {
        return true;
    }
    if tx.ticker.as_deref().is_some_and(|tk| !java_is_blank(tk)) {
        return true;
    }
    if tx.tx_type.is_some_and(|k| {
        matches!(
            k,
            TransactionType::Buy
                | TransactionType::Sell
                | TransactionType::Dividend
                | TransactionType::Fee
        )
    }) {
        return true;
    }
    if is_trade_or_yield_kind(tx.kind.as_deref())
        || is_trade_or_yield_raw_category(tx.category.as_deref())
    {
        return true;
    }
    if let Some(meta) = category
        && (meta.slug.as_deref() == Some(CAPITAL_INCOME_SLUG) || meta.kind == CategoryKind::Income)
    {
        return true;
    }
    tx.amount > Decimal::ZERO && is_yield_description(&tx.description)
}

/// Whether `tx` is a securities purchase leg debited from a broker cash account (e.g. Trade
/// Republic `TRADING` / `BUY` / `SAVINGS_PLAN_EXECUTED` debit).
fn is_broker_cash_trading_debit(tx: &Transaction) -> bool {
    if tx.budget_neutral || tx.amount >= Decimal::ZERO {
        return false;
    }
    tx.tx_type == Some(TransactionType::Buy)
        || tx
            .category
            .as_deref()
            .is_some_and(|c| c.eq_ignore_ascii_case("TRADING"))
        || tx.kind.as_deref().is_some_and(|k| {
            k.eq_ignore_ascii_case("BUY") || k.eq_ignore_ascii_case("SAVINGS_PLAN_EXECUTED")
        })
}

/// Whether `tx` represents a securities purchase or internal investment leg targeting an
/// investment account (used only to rank which investment account to prefill).
fn is_investment_buy_activity(tx: &Transaction) -> bool {
    if tx.budget_neutral || tx.amount.is_zero() {
        return false;
    }
    tx.tx_type == Some(TransactionType::Buy)
        || (tx
            .category
            .as_deref()
            .is_some_and(|c| c.eq_ignore_ascii_case("TRADING"))
            && tx.amount > Decimal::ZERO)
}

fn within_match_window(a: NaiveDate, b: NaiveDate) -> bool {
    (a - b).num_days().abs() <= TRANSFER_MATCH_DAYS
}

/// Zero-based month index backwards from the current month (`0` = current in-progress month,
/// `WINDOW_MONTHS - 1` = five months ago). Returns `None` for future dates or dates outside the
/// 6-month rolling window.
fn month_offset(date: NaiveDate, today: NaiveDate) -> Option<usize> {
    if date > today {
        return None;
    }
    let this_abs = today.year() * 12 + today.month0() as i32;
    let tx_abs = date.year() * 12 + date.month0() as i32;
    let diff = this_abs - tx_abs;
    (0..WINDOW_MONTHS as i32)
        .contains(&diff)
        .then_some(diff as usize)
}

/// Rounds a positive monthly figure to the nearest 10 EUR (HALF_UP).
/// Returns `None` when the rounded value is zero.
fn round_to_ten(amount: Decimal) -> Option<Decimal> {
    if amount <= Decimal::ZERO {
        return None;
    }
    let rounded = divide(amount, Decimal::TEN, 0, HALF_UP) * Decimal::TEN;
    (rounded > Decimal::ZERO).then(|| scale2(rounded))
}

/// Evaluates regularity and the median of active months from monthly net inflows ordered
/// newest-first (`monthly[0]` = current in-progress month, `monthly[1..6]` = last 5 complete
/// months).
///
/// - The current month counts only when strictly positive: an in-progress month can add an active
///   month but never penalise regularity or drag the net total negative.
/// - Regularity: at least 2 of the last 3 months (current month included) must have a strictly
///   positive net inflow.
/// - Net disinvestment guard: `monthly[0].max(0) + sum(monthly[1..])` must stay strictly positive.
/// - Amount: median of strictly positive monthly net inflows in the 6-month window, rounded to the
///   nearest 10 EUR (HALF_UP).
fn summarize_monthly(monthly: &[Decimal; WINDOW_MONTHS]) -> Option<(Decimal, usize)> {
    let active_recent = monthly[..RECENT_WINDOW_MONTHS]
        .iter()
        .filter(|v| **v > Decimal::ZERO)
        .count();
    if active_recent < MIN_ACTIVE_MONTHS_IN_RECENT_WINDOW {
        return None;
    }
    let window_net =
        monthly[0].max(Decimal::ZERO) + monthly[1..].iter().fold(Decimal::ZERO, |acc, v| acc + *v);
    if window_net <= Decimal::ZERO {
        return None;
    }
    let mut active: Vec<Decimal> = monthly
        .iter()
        .copied()
        .filter(|v| *v > Decimal::ZERO)
        .collect();
    if active.is_empty() {
        return None;
    }
    active.sort();
    let active_months = active.len();
    let mid = active_months / 2;
    let median = if active_months % 2 == 1 {
        active[mid]
    } else {
        divide(active[mid - 1] + active[mid], Decimal::TWO, 4, HALF_UP)
    };
    let rounded = round_to_ten(median)?;
    Some((rounded, active_months))
}

async fn load_categories(
    conn: &mut DbHandle<'_>,
    rows: &[Transaction],
) -> sqlx::Result<HashMap<i64, CategoryMeta>> {
    let ids: Vec<i64> = rows
        .iter()
        .filter_map(|t| t.category_id)
        .collect::<HashSet<_>>()
        .into_iter()
        .collect();
    if ids.is_empty() {
        return Ok(HashMap::new());
    }
    let found: Vec<(i64, CategoryKind, Option<String>)> =
        db::query_as("SELECT id, kind::text AS kind, slug FROM category WHERE id = ANY($1)")
            .bind(&ids)
            .fetch_all(&mut *conn)
            .await?;
    Ok(found
        .into_iter()
        .map(|(id, kind, slug)| (id, CategoryMeta { kind, slug }))
        .collect())
}

/// Filters candidate `CHECKING` accounts sharing a provider with an investment account down to
/// genuine broker cash pockets:
/// - rejects accounts with salary-like credits (categorised non-capital `INCOME`, salary/payroll
///   labels, or recurring credits >= 500 EUR from the same emitter in >= 2 distinct months);
/// - requires evidence that the account actually feeds a same-provider investment account in the
///   6-month window (either a `TRADING`/`BUY` debit or a transfer paired with a credit on a
///   same-provider investment account).
async fn qualify_broker_cash_accounts(
    candidate_broker_cash: &HashSet<i64>,
    investment_ids: &HashSet<i64>,
    by_id: &HashMap<i64, &Account>,
    rows: &[Transaction],
    categories: &HashMap<i64, CategoryMeta>,
    fx: &mut FxCache<'_>,
    today: NaiveDate,
) -> HashSet<i64> {
    if candidate_broker_cash.is_empty() {
        return HashSet::new();
    }

    let salary_threshold = Decimal::from(SALARY_MIN_RECURRING_EUR);
    let mut disqualified: HashSet<i64> = HashSet::new();
    let mut recurring_credits: HashMap<(i64, String), HashSet<(i32, u32)>> = HashMap::new();

    for tx in rows {
        if !candidate_broker_cash.contains(&tx.account_id)
            || tx.budget_neutral
            || tx.amount <= Decimal::ZERO
            || tx.date > today
        {
            continue;
        }
        let cat = tx.category_id.and_then(|id| categories.get(&id));
        if let Some(meta) = cat
            && meta.kind == CategoryKind::Income
            && meta.slug.as_deref() != Some(CAPITAL_INCOME_SLUG)
        {
            disqualified.insert(tx.account_id);
            continue;
        }
        if is_salary_description(&tx.description) {
            disqualified.insert(tx.account_id);
            continue;
        }
        if month_offset(tx.date, today).is_none() || is_non_contribution_row(tx, cat) {
            continue;
        }
        if let Some(eur) = fx.convert_to_eur(tx.amount, &tx.native_currency).await
            && eur >= salary_threshold
            && let Some(emitter) = emitter_key(tx)
        {
            let months = recurring_credits
                .entry((tx.account_id, emitter))
                .or_default();
            months.insert((tx.date.year(), tx.date.month()));
            if months.len() >= SALARY_MIN_RECURRING_MONTHS {
                disqualified.insert(tx.account_id);
            }
        }
    }

    let eligible: HashSet<i64> = candidate_broker_cash
        .iter()
        .copied()
        .filter(|id| !disqualified.contains(id))
        .collect();
    if eligible.is_empty() {
        return HashSet::new();
    }

    // Index eligible credits on investment accounts by (provider, currency, cents) to detect
    // paired transfers from a candidate broker cash account to a same-provider investment account.
    let mut inv_credits: HashMap<(String, String, i64), Vec<NaiveDate>> = HashMap::new();
    for tx in rows {
        if !investment_ids.contains(&tx.account_id) || tx.amount <= Decimal::ZERO {
            continue;
        }
        let cat = tx.category_id.and_then(|id| categories.get(&id));
        if is_non_contribution_row(tx, cat) {
            continue;
        }
        let Some(provider) = by_id.get(&tx.account_id).and_then(|a| provider_key(a)) else {
            continue;
        };
        inv_credits
            .entry((
                provider,
                tx.native_currency.to_uppercase(),
                cents(tx.amount),
            ))
            .or_default()
            .push(tx.date);
    }

    let mut qualified: HashSet<i64> = HashSet::new();
    for tx in rows {
        if !eligible.contains(&tx.account_id) || qualified.contains(&tx.account_id) {
            continue;
        }
        if tx.amount >= Decimal::ZERO {
            continue;
        }
        if month_offset(tx.date, today).is_some() && is_broker_cash_trading_debit(tx) {
            qualified.insert(tx.account_id);
            continue;
        }
        let cat = tx.category_id.and_then(|id| categories.get(&id));
        if is_non_contribution_row(tx, cat) {
            continue;
        }
        let Some(provider) = by_id.get(&tx.account_id).and_then(|a| provider_key(a)) else {
            continue;
        };
        let key = (
            provider,
            tx.native_currency.to_uppercase(),
            cents(tx.amount.abs()),
        );
        if inv_credits.get(&key).is_some_and(|dates| {
            dates.iter().any(|&credit_date| {
                within_match_window(tx.date, credit_date)
                    && (month_offset(credit_date, today).is_some()
                        || month_offset(tx.date, today).is_some())
            })
        }) {
            qualified.insert(tx.account_id);
        }
    }

    qualified
}

/// Detects a regular monthly net external investment inflow over the last 6 rolling calendar
/// months (current month included when positive).
pub async fn detect(
    conn: &mut DbHandle<'_>,
    prices: &dyn PriceRead,
    pyramid: &WealthPyramidResponse,
    member_id: i64,
    today: NaiveDate,
) -> sqlx::Result<Option<DetectedInflow>> {
    let readable: Vec<Account> = access::readable_accounts(&mut *conn, member_id)
        .await?
        .into_iter()
        .filter(|a| {
            !a.hidden
                && a.parent_account_id.is_none()
                && !a.account_type.is_liability()
                && a.account_type != AccountType::PeerLoan
        })
        .collect();
    if readable.is_empty() {
        return Ok(None);
    }

    let shares = access::shares_for(&mut *conn, &readable, member_id).await?;
    let overrides = overrides_of(&mut *conn, member_id).await?;
    let tracked = tracked_fund::keys(&mut *conn, &readable.iter().collect::<Vec<_>>()).await?;

    let mut investment_ids: HashSet<i64> = HashSet::new();
    let mut broker_providers: HashSet<String> = HashSet::new();
    let mut savings_ids: HashSet<i64> = HashSet::new();
    for account in &readable {
        let tier = match tracked.get(&account.id) {
            Some(key) => tracked_fund::tier(key, &overrides),
            None => WealthTier::of(account.account_type),
        };
        if matches!(tier, WealthTier::Equity | WealthTier::Crypto) {
            investment_ids.insert(account.id);
            if let Some(p) = provider_key(account) {
                broker_providers.insert(p);
            }
        } else if AssetClass::of(account.account_type) == AssetClass::Savings {
            savings_ids.insert(account.id);
        }
    }
    if investment_ids.is_empty() {
        return Ok(None);
    }

    let candidate_broker_cash: HashSet<i64> = readable
        .iter()
        .filter(|a| {
            a.account_type == AccountType::Checking
                && !investment_ids.contains(&a.id)
                && provider_key(a).is_some_and(|p| broker_providers.contains(&p))
        })
        .map(|a| a.id)
        .collect();

    let this_month = today.with_day(1).expect("first of month");
    let from = this_month - Months::new((WINDOW_MONTHS - 1) as u32);
    let gap = Days::new(TRANSFER_MATCH_DAYS as u64);

    // Load transactions across investment accounts, candidate broker cash accounts, and savings
    // passbooks so internal transfers within the perimeter or from savings passbooks can be paired
    // across month and window boundaries.
    let query_ids: Vec<i64> = investment_ids
        .iter()
        .chain(candidate_broker_cash.iter())
        .chain(savings_ids.iter())
        .copied()
        .collect();
    let rows: Vec<Transaction> = db::query_as(&format!(
        "SELECT {TRANSACTION_COLUMNS} FROM \"transaction\" t WHERE t.account_id = ANY($1) \
         AND t.date BETWEEN $2 AND $3 ORDER BY t.date, t.id"
    ))
    .bind(&query_ids)
    .bind(from - gap)
    .bind(today + gap)
    .fetch_all(&mut *conn)
    .await?;
    if rows.is_empty() {
        return Ok(None);
    }

    let categories = load_categories(conn, &rows).await?;
    let by_id: HashMap<i64, &Account> = readable.iter().map(|a| (a.id, a)).collect();
    let mut fx = FxCache::new(prices);

    let broker_cash_ids = qualify_broker_cash_accounts(
        &candidate_broker_cash,
        &investment_ids,
        &by_id,
        &rows,
        &categories,
        &mut fx,
        today,
    )
    .await;
    let perimeter_ids: HashSet<i64> = investment_ids
        .iter()
        .chain(broker_cash_ids.iter())
        .copied()
        .collect();

    // Index eligible cash debits across the perimeter and savings passbooks so internal moves
    // (broker cash -> PEA, CTO -> PEA, Livret A -> PEA) are paired 1-to-1 and excluded.
    let mut debits_by_key: HashMap<(String, i64), Vec<usize>> = HashMap::new();
    for (idx, tx) in rows.iter().enumerate() {
        let in_perimeter = perimeter_ids.contains(&tx.account_id);
        let in_savings = savings_ids.contains(&tx.account_id);
        if (!in_perimeter && !in_savings) || tx.amount >= Decimal::ZERO {
            continue;
        }
        let cat = tx.category_id.and_then(|id| categories.get(&id));
        if is_non_contribution_row(tx, cat) {
            continue;
        }
        debits_by_key
            .entry((tx.native_currency.to_uppercase(), cents(tx.amount.abs())))
            .or_default()
            .push(idx);
    }

    let mut excluded_pair_legs: HashSet<usize> = HashSet::new();
    let mut consumed_debits: HashSet<usize> = HashSet::new();
    let mut activity = AccountActivity::default();

    for (credit_idx, tx) in rows.iter().enumerate() {
        if !perimeter_ids.contains(&tx.account_id) || tx.amount <= Decimal::ZERO {
            continue;
        }
        let cat = tx.category_id.and_then(|id| categories.get(&id));
        if is_non_contribution_row(tx, cat) {
            continue;
        }
        let key = (tx.native_currency.to_uppercase(), cents(tx.amount));
        let Some(candidates) = debits_by_key.get(&key) else {
            continue;
        };
        if let Some(&debit_idx) = candidates.iter().find(|&&d_idx| {
            !consumed_debits.contains(&d_idx)
                && rows[d_idx].account_id != tx.account_id
                && within_match_window(rows[d_idx].date, tx.date)
        }) {
            consumed_debits.insert(debit_idx);
            excluded_pair_legs.insert(credit_idx);
            excluded_pair_legs.insert(debit_idx);
            // Remember broker-cash -> investment internal moves so the destination investment
            // account wins as the suggested account for prefilling.
            if investment_ids.contains(&tx.account_id)
                && broker_cash_ids.contains(&rows[debit_idx].account_id)
                && month_offset(tx.date, today).is_some()
                && let Some(eur) = fx
                    .convert_to_eur(tx.amount.abs(), &tx.native_currency)
                    .await
            {
                let weighted = weigh(eur, shares.get(&tx.account_id).copied().unwrap_or_default());
                *activity
                    .internal
                    .entry(tx.account_id)
                    .or_insert(Decimal::ZERO) += weighted;
            }
        }
    }

    let mut monthly = [Decimal::ZERO; WINDOW_MONTHS];
    let mut unpriced_months = [false; WINDOW_MONTHS];

    for (idx, tx) in rows.iter().enumerate() {
        let Some(offset) = month_offset(tx.date, today) else {
            continue;
        };
        if !perimeter_ids.contains(&tx.account_id) {
            continue;
        }
        let share = shares.get(&tx.account_id).copied().unwrap_or_default();
        if investment_ids.contains(&tx.account_id)
            && is_investment_buy_activity(tx)
            && let Some(eur) = fx
                .convert_to_eur(tx.amount.abs(), &tx.native_currency)
                .await
        {
            *activity.buy.entry(tx.account_id).or_insert(Decimal::ZERO) += weigh(eur, share);
        }
        if excluded_pair_legs.contains(&idx) {
            continue;
        }
        let cat = tx.category_id.and_then(|id| categories.get(&id));
        if is_non_contribution_row(tx, cat) {
            continue;
        }
        let Some(magnitude_eur) = fx
            .convert_to_eur(tx.amount.abs(), &tx.native_currency)
            .await
        else {
            unpriced_months[offset] = true;
            continue;
        };
        let signed_eur = if tx.amount < Decimal::ZERO {
            -magnitude_eur
        } else {
            magnitude_eur
        };
        let weighted = weigh(signed_eur, share);
        monthly[offset] += weighted;

        if weighted > Decimal::ZERO {
            if investment_ids.contains(&tx.account_id) {
                *activity
                    .direct
                    .entry(tx.account_id)
                    .or_insert(Decimal::ZERO) += weighted;
            } else if broker_cash_ids.contains(&tx.account_id)
                && let Some(provider) = by_id.get(&tx.account_id).and_then(|a| provider_key(a))
            {
                *activity
                    .broker_provider
                    .entry(provider)
                    .or_insert(Decimal::ZERO) += weighted;
            }
        }
    }

    for (value, unpriced) in monthly.iter_mut().zip(unpriced_months) {
        if unpriced {
            *value = Decimal::ZERO;
        }
    }

    let Some((monthly_eur, active_months)) = summarize_monthly(&monthly) else {
        return Ok(None);
    };

    let account_id =
        pick_primary_investment_account(&readable, &investment_ids, member_id, pyramid, &activity);

    Ok(Some(DetectedInflow {
        monthly_eur,
        active_months,
        account_id,
    }))
}

fn pick_primary_investment_account(
    readable: &[Account],
    investment_ids: &HashSet<i64>,
    member_id: i64,
    pyramid: &WealthPyramidResponse,
    activity: &AccountActivity,
) -> Option<i64> {
    let mut pyramid_values: HashMap<i64, Decimal> = HashMap::new();
    for line in &pyramid.tiers {
        for acc in &line.accounts {
            *pyramid_values
                .entry(acc.account_id)
                .or_insert(Decimal::ZERO) += acc.value_eur;
        }
    }

    // Only accounts owned by the member can be attached to a goal (`POST /api/goals` rejects
    // co-owned accounts owned by someone else).
    let candidates: Vec<&Account> = readable
        .iter()
        .filter(|a| investment_ids.contains(&a.id) && a.member_id == member_id)
        .collect();
    if candidates.is_empty() {
        return None;
    }

    let activity_of = |id: i64| {
        activity.direct.get(&id).copied().unwrap_or_default()
            + activity.internal.get(&id).copied().unwrap_or_default()
            + activity.buy.get(&id).copied().unwrap_or_default()
    };
    let value_of = |a: &Account| {
        pyramid_values
            .get(&a.id)
            .copied()
            .unwrap_or(a.current_balance)
    };
    let provider_flow_of = |a: &Account| {
        provider_key(a)
            .and_then(|p| activity.broker_provider.get(&p).copied())
            .unwrap_or_default()
    };

    candidates
        .into_iter()
        .max_by(|a, b| {
            activity_of(a.id)
                .cmp(&activity_of(b.id))
                .then_with(|| provider_flow_of(a).cmp(&provider_flow_of(b)))
                .then_with(|| value_of(a).cmp(&value_of(b)))
                .then_with(|| b.id.cmp(&a.id))
        })
        .map(|a| a.id)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(s: &str) -> Decimal {
        s.parse().expect("decimal")
    }

    #[test]
    fn rounds_to_nearest_ten_eur_half_up() {
        assert_eq!(round_to_ten(d("196.67")), Some(d("200.00")));
        assert_eq!(round_to_ten(d("204.99")), Some(d("200.00")));
        assert_eq!(round_to_ten(d("205.00")), Some(d("210.00")));
        assert_eq!(round_to_ten(d("14.00")), Some(d("10.00")));
        assert_eq!(round_to_ten(d("4.99")), None);
        assert_eq!(round_to_ten(d("0")), None);
    }

    #[test]
    fn month_offset_covers_six_rolling_months_including_current_month() {
        let today = NaiveDate::from_ymd_opt(2026, 11, 27).unwrap();
        assert_eq!(
            month_offset(NaiveDate::from_ymd_opt(2026, 11, 2).unwrap(), today),
            Some(0)
        );
        assert_eq!(
            month_offset(NaiveDate::from_ymd_opt(2026, 10, 2).unwrap(), today),
            Some(1)
        );
        assert_eq!(
            month_offset(NaiveDate::from_ymd_opt(2026, 6, 15).unwrap(), today),
            Some(5)
        );
        // Six calendar months earlier (May) falls outside the rolling 6-month window.
        assert_eq!(
            month_offset(NaiveDate::from_ymd_opt(2026, 5, 15).unwrap(), today),
            None
        );
        // Future dates in the current month are ignored.
        assert_eq!(
            month_offset(NaiveDate::from_ymd_opt(2026, 11, 28).unwrap(), today),
            None
        );
    }

    #[test]
    fn requires_regularity_and_uses_median_of_active_months() {
        // One isolated lump sum is rejected.
        let isolated = [d("600"), d("0"), d("0"), d("0"), d("0"), d("0")];
        assert_eq!(summarize_monthly(&isolated), None);

        // Stopped investing more than three months ago is rejected.
        let stopped = [d("0"), d("0"), d("0"), d("200"), d("200"), d("200")];
        assert_eq!(summarize_monthly(&stopped), None);

        // Current month + previous month active (e.g. Oct + Nov): median is 200 across 2 active months.
        let current_and_prev = [d("200"), d("200"), d("0"), d("0"), d("0"), d("0")];
        assert_eq!(summarize_monthly(&current_and_prev), Some((d("200.00"), 2)));

        // Two of the last three months with a gap in between: median is not diluted by the empty month.
        let two_of_three = [d("200"), d("0"), d("200"), d("0"), d("0"), d("0")];
        assert_eq!(summarize_monthly(&two_of_three), Some((d("200.00"), 2)));

        // Current month empty (not yet invested this month), previous two months active -> passes.
        let partial_empty = [d("0"), d("200"), d("200"), d("200"), d("0"), d("0")];
        assert_eq!(summarize_monthly(&partial_empty), Some((d("200.00"), 3)));

        // Current month negative (partial withdrawal before monthly wire) cannot penalise or drag total.
        let partial_negative = [d("-100"), d("200"), d("200"), d("0"), d("0"), d("0")];
        assert_eq!(summarize_monthly(&partial_negative), Some((d("200.00"), 2)));

        // Outlier spike in one month does not distort the median.
        let with_spike = [d("900"), d("200"), d("200"), d("200"), d("0"), d("0")];
        assert_eq!(summarize_monthly(&with_spike), Some((d("200.00"), 4)));

        // Even number of active months averages the two middle values and rounds to 10 EUR.
        let even_active = [d("150"), d("200"), d("0"), d("0"), d("0"), d("0")];
        assert_eq!(summarize_monthly(&even_active), Some((d("180.00"), 2)));

        // Six steady months return 200 across 6 active months.
        let six_months = [d("200"), d("200"), d("200"), d("200"), d("200"), d("200")];
        assert_eq!(summarize_monthly(&six_months), Some((d("200.00"), 6)));

        // Net withdrawals in complete months pull the window negative -> nothing suggested.
        let net_outflow = [d("200"), d("-600"), d("200"), d("0"), d("0"), d("0")];
        assert_eq!(summarize_monthly(&net_outflow), None);
    }

    #[test]
    fn flags_dividend_interest_and_capital_gain_labels_without_touching_transfers() {
        for label in [
            "Interest",
            "Intérêts créditeurs",
            "Interets mensuels",
            "Intereses abonados",
            "Interés mensual",
            "Dividende TotalEnergies",
            "Coupon obligation",
            "Zinsen",
            "Plus-value réalisée",
            "Capital gain distribution",
            "Staking reward",
        ] {
            assert!(is_yield_description(label), "expected yield: {label}");
        }
        for transfer in [
            "Virement SEPA entrant",
            "Versement mensuel PEA",
            "Alimentation compte espèces",
            "SEPA Credit Transfer",
        ] {
            assert!(
                !is_yield_description(transfer),
                "unexpected yield: {transfer}"
            );
        }
    }

    #[test]
    fn flags_salary_descriptions_without_touching_regular_wires() {
        for label in [
            "Virement salaire",
            "SALAIRE NOVEMBRE",
            "Monthly Payroll",
            "Employer Salary",
            "Virement employeur ACME",
            "Rémunération mensuelle",
            "Gehalt Oktober",
            "Nómina mensual",
        ] {
            assert!(is_salary_description(label), "expected salary: {label}");
        }
        for transfer in [
            "Virement SEPA entrant",
            "Alimentation compte espèces",
            "SEPA Credit Transfer",
        ] {
            assert!(
                !is_salary_description(transfer),
                "unexpected salary: {transfer}"
            );
        }
    }
}
