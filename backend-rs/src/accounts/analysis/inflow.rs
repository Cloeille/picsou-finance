//! Detected monthly investment inflow for the wealth projection.
//!
//! Scans the last three to six complete calendar months for regular net external cash inflows
//! into the member's investment perimeter: investment accounts (`Equity` / `Crypto` wealth tiers)
//! plus dedicated broker cash accounts (`CHECKING` accounts whose provider also holds an
//! investment account and that do not receive non-capital `INCOME` such as a salary).
//!
//! Internal transfers within the perimeter (for instance broker cash -> PEA, CTO -> PEA),
//! transfers out of savings passbooks (`SafetyNet` -> investment), trade executions (`BUY`/`SELL`/
//! Trade Republic `TRADING` rows), dividends, interest, fees, and budget-neutral rows are
//! excluded so contributions are never double-counted or inflated by yields or capital gains.
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

const MIN_WINDOW_MONTHS: usize = 3;
const MAX_WINDOW_MONTHS: usize = 6;
const MIN_ACTIVE_MONTHS_IN_RECENT_WINDOW: usize = 2;
const TRANSFER_MATCH_DAYS: i64 = 3;
const CAPITAL_INCOME_SLUG: &str = "revenus-capital";

/// Non-contribution yield / capital-gain labels on cash credits when providers do not stamp a
/// dedicated `tx_type` or category (e.g. bank-synced interest or dividend payouts).
static YIELD_LABELS: LazyLock<Vec<Regex>> = LazyLock::new(|| {
    [
        r"\bdivid",
        r"\bcoupon\b",
        r"\binterets?\b",
        r"\binterests?\b",
        r"\bzinsen\b",
        r"\bplus[\s-]value",
        r"\bcapital\s+gain",
        r"\bstaking\b",
    ]
    .iter()
    .map(|p| Regex::new(&format!("(?i){p}")).expect("yield label regex"))
    .collect()
});

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DetectedInflow {
    pub monthly_eur: Decimal,
    pub window_months: usize,
    pub account_id: Option<i64>,
}

#[derive(Debug, Clone)]
struct CategoryMeta {
    kind: CategoryKind,
    slug: Option<String>,
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

fn is_non_contribution_row(
    tx: &Transaction,
    category: Option<&CategoryMeta>,
    is_broker_cash: bool,
) -> bool {
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
    if let Some(meta) = category {
        // Capital income (dividends/interest) or salary is never a contribution; on a broker cash
        // account, everyday debit-card expenses are also excluded from investment wire netting.
        if meta.slug.as_deref() == Some(CAPITAL_INCOME_SLUG)
            || meta.kind == CategoryKind::Income
            || (is_broker_cash && meta.kind == CategoryKind::Expense)
        {
            return true;
        }
    }
    if tx.amount > Decimal::ZERO && is_yield_description(&tx.description) {
        return true;
    }
    false
}

/// Whether `tx` represents a securities purchase or internal investment leg targeting an
/// investment account (used only to rank which investment account to prefill).
fn is_investment_buy_activity(tx: &Transaction) -> bool {
    if tx.budget_neutral || tx.amount.is_zero() {
        return false;
    }
    if tx.tx_type == Some(TransactionType::Buy) {
        return true;
    }
    if tx
        .category
        .as_deref()
        .is_some_and(|c| c.eq_ignore_ascii_case("TRADING"))
        && tx.amount > Decimal::ZERO
    {
        return true;
    }
    false
}

fn within_match_window(a: NaiveDate, b: NaiveDate) -> bool {
    (a - b).num_days().abs() <= TRANSFER_MATCH_DAYS
}

/// Zero-based month index backwards from the last complete month (`0` = previous month,
/// `MAX_WINDOW_MONTHS - 1` = six months ago). Returns `None` outside `[from, to]`.
fn month_offset(date: NaiveDate, this_month: NaiveDate) -> Option<usize> {
    let this_abs = this_month.year() * 12 + this_month.month0() as i32;
    let tx_abs = date.year() * 12 + date.month0() as i32;
    let diff = this_abs - tx_abs;
    if diff >= 1 && (diff as usize) <= MAX_WINDOW_MONTHS {
        Some((diff - 1) as usize)
    } else {
        None
    }
}

/// Rounds a positive monthly average to the nearest 10 EUR (HALF_UP).
/// Returns `None` when the rounded value is zero.
fn round_to_ten(amount: Decimal) -> Option<Decimal> {
    if amount <= Decimal::ZERO {
        return None;
    }
    let rounded = divide(amount, Decimal::TEN, 0, HALF_UP) * Decimal::TEN;
    (rounded > Decimal::ZERO).then(|| scale2(rounded))
}

/// Evaluates the regularity and adaptive 3..=6 month average from monthly net inflows ordered
/// newest-first (`monthly[0]` = last complete month).
fn summarize_monthly(monthly: &[Decimal; MAX_WINDOW_MONTHS]) -> Option<(Decimal, usize)> {
    let active_recent = monthly[..MIN_WINDOW_MONTHS]
        .iter()
        .filter(|v| **v > Decimal::ZERO)
        .count();
    if active_recent < MIN_ACTIVE_MONTHS_IN_RECENT_WINDOW {
        return None;
    }
    let oldest_active = monthly.iter().rposition(|v| *v > Decimal::ZERO)?;
    let window_months = (oldest_active + 1).clamp(MIN_WINDOW_MONTHS, MAX_WINDOW_MONTHS);
    let total = monthly[..window_months]
        .iter()
        .fold(Decimal::ZERO, |acc, v| acc + *v);
    if total <= Decimal::ZERO {
        return None;
    }
    let avg = divide(total, Decimal::from(window_months), 4, HALF_UP);
    let rounded = round_to_ten(avg)?;
    Some((rounded, window_months))
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

/// Detects a regular monthly net external investment inflow over the last 3 to 6 complete months.
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
    let from = this_month - Months::new(MAX_WINDOW_MONTHS as u32);
    let to = this_month - Days::new(1);
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
    .bind(to + gap)
    .fetch_all(&mut *conn)
    .await?;
    if rows.is_empty() {
        return Ok(None);
    }

    let categories = load_categories(conn, &rows).await?;

    // A CHECKING account at a universal bank that receives non-capital INCOME (e.g. salary) is
    // an everyday bank account, not a dedicated broker cash pocket.
    let mut salary_checking_ids: HashSet<i64> = HashSet::new();
    for tx in &rows {
        if !candidate_broker_cash.contains(&tx.account_id) || tx.amount <= Decimal::ZERO {
            continue;
        }
        if let Some(meta) = tx.category_id.and_then(|id| categories.get(&id))
            && meta.kind == CategoryKind::Income
            && meta.slug.as_deref() != Some(CAPITAL_INCOME_SLUG)
        {
            salary_checking_ids.insert(tx.account_id);
        }
    }

    let broker_cash_ids: HashSet<i64> = candidate_broker_cash
        .into_iter()
        .filter(|id| !salary_checking_ids.contains(id))
        .collect();
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
        if !in_perimeter && !in_savings {
            continue;
        }
        if tx.amount >= Decimal::ZERO {
            continue;
        }
        let cat = tx.category_id.and_then(|id| categories.get(&id));
        if is_non_contribution_row(tx, cat, broker_cash_ids.contains(&tx.account_id)) {
            continue;
        }
        debits_by_key
            .entry((tx.native_currency.to_uppercase(), cents(tx.amount.abs())))
            .or_default()
            .push(idx);
    }

    let mut excluded_pair_legs: HashSet<usize> = HashSet::new();
    let mut consumed_debits: HashSet<usize> = HashSet::new();
    let mut internal_investment_inflows: HashMap<i64, Decimal> = HashMap::new();

    for (credit_idx, tx) in rows.iter().enumerate() {
        if !perimeter_ids.contains(&tx.account_id) || tx.amount <= Decimal::ZERO {
            continue;
        }
        let cat = tx.category_id.and_then(|id| categories.get(&id));
        if is_non_contribution_row(tx, cat, broker_cash_ids.contains(&tx.account_id)) {
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
                && month_offset(tx.date, this_month).is_some()
            {
                let eur = prices
                    .to_eur(Some(tx.amount.abs()), &tx.native_currency, None, today)
                    .await;
                let weighted = weigh(eur, shares.get(&tx.account_id).copied().unwrap_or_default());
                *internal_investment_inflows
                    .entry(tx.account_id)
                    .or_insert(Decimal::ZERO) += weighted;
            }
        }
    }

    let mut monthly = [Decimal::ZERO; MAX_WINDOW_MONTHS];
    let mut direct_investment_inflows: HashMap<i64, Decimal> = HashMap::new();
    let mut broker_cash_provider_inflows: HashMap<String, Decimal> = HashMap::new();
    let mut buy_activity_by_investment: HashMap<i64, Decimal> = HashMap::new();
    let by_id: HashMap<i64, &Account> = readable.iter().map(|a| (a.id, a)).collect();

    for (idx, tx) in rows.iter().enumerate() {
        let Some(offset) = month_offset(tx.date, this_month) else {
            continue;
        };
        if !perimeter_ids.contains(&tx.account_id) {
            continue;
        }
        let share = shares.get(&tx.account_id).copied().unwrap_or_default();
        if investment_ids.contains(&tx.account_id) && is_investment_buy_activity(tx) {
            let eur = prices
                .to_eur(Some(tx.amount.abs()), &tx.native_currency, None, today)
                .await;
            *buy_activity_by_investment
                .entry(tx.account_id)
                .or_insert(Decimal::ZERO) += weigh(eur, share);
        }
        if excluded_pair_legs.contains(&idx) {
            continue;
        }
        let is_broker_cash = broker_cash_ids.contains(&tx.account_id);
        let cat = tx.category_id.and_then(|id| categories.get(&id));
        if is_non_contribution_row(tx, cat, is_broker_cash) {
            continue;
        }
        let magnitude_eur = prices
            .to_eur(Some(tx.amount.abs()), &tx.native_currency, None, today)
            .await;
        let signed_eur = if tx.amount < Decimal::ZERO {
            -magnitude_eur
        } else {
            magnitude_eur
        };
        let weighted = weigh(signed_eur, share);
        monthly[offset] += weighted;

        if weighted > Decimal::ZERO {
            if investment_ids.contains(&tx.account_id) {
                *direct_investment_inflows
                    .entry(tx.account_id)
                    .or_insert(Decimal::ZERO) += weighted;
            } else if is_broker_cash
                && let Some(provider) = by_id.get(&tx.account_id).and_then(|a| provider_key(a))
            {
                *broker_cash_provider_inflows
                    .entry(provider)
                    .or_insert(Decimal::ZERO) += weighted;
            }
        }
    }

    let Some((monthly_eur, window_months)) = summarize_monthly(&monthly) else {
        return Ok(None);
    };

    let account_id = pick_primary_investment_account(
        &readable,
        &investment_ids,
        pyramid,
        &direct_investment_inflows,
        &internal_investment_inflows,
        &buy_activity_by_investment,
        &broker_cash_provider_inflows,
    );

    Ok(Some(DetectedInflow {
        monthly_eur,
        window_months,
        account_id,
    }))
}

fn pick_primary_investment_account(
    readable: &[Account],
    investment_ids: &HashSet<i64>,
    pyramid: &WealthPyramidResponse,
    direct_inflows: &HashMap<i64, Decimal>,
    internal_inflows: &HashMap<i64, Decimal>,
    buy_activity: &HashMap<i64, Decimal>,
    broker_provider_inflows: &HashMap<String, Decimal>,
) -> Option<i64> {
    let mut pyramid_values: HashMap<i64, Decimal> = HashMap::new();
    for line in &pyramid.tiers {
        for acc in &line.accounts {
            *pyramid_values
                .entry(acc.account_id)
                .or_insert(Decimal::ZERO) += acc.value_eur;
        }
    }

    let candidates: Vec<&Account> = readable
        .iter()
        .filter(|a| investment_ids.contains(&a.id))
        .collect();
    if candidates.is_empty() {
        return None;
    }

    let activity_of = |id: i64| {
        direct_inflows.get(&id).copied().unwrap_or_default()
            + internal_inflows.get(&id).copied().unwrap_or_default()
            + buy_activity.get(&id).copied().unwrap_or_default()
    };
    let value_of = |a: &Account| {
        pyramid_values
            .get(&a.id)
            .copied()
            .unwrap_or(a.current_balance)
    };
    let provider_flow_of = |a: &Account| {
        provider_key(a)
            .and_then(|p| broker_provider_inflows.get(&p).copied())
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
    fn requires_at_least_two_active_months_in_the_last_three() {
        // One isolated lump sum is rejected.
        let isolated = [d("600"), d("0"), d("0"), d("0"), d("0"), d("0")];
        assert_eq!(summarize_monthly(&isolated), None);

        // Stopped investing more than three months ago is rejected.
        let stopped = [d("0"), d("0"), d("0"), d("200"), d("200"), d("200")];
        assert_eq!(summarize_monthly(&stopped), None);

        // Two of the last three months pass, averaged over 3 months and rounded to 10 EUR.
        let two_of_three = [d("200"), d("0"), d("200"), d("0"), d("0"), d("0")];
        assert_eq!(summarize_monthly(&two_of_three), Some((d("130.00"), 3)));

        // Three steady months average over 3 months.
        let three_months = [d("200"), d("200"), d("200"), d("0"), d("0"), d("0")];
        assert_eq!(summarize_monthly(&three_months), Some((d("200.00"), 3)));

        // Six steady months average over 6 months.
        let six_months = [d("200"), d("200"), d("200"), d("200"), d("200"), d("200")];
        assert_eq!(summarize_monthly(&six_months), Some((d("200.00"), 6)));

        // Net withdrawals pull the sum negative -> nothing suggested.
        let net_outflow = [d("-600"), d("200"), d("200"), d("0"), d("0"), d("0")];
        assert_eq!(summarize_monthly(&net_outflow), None);
    }

    #[test]
    fn flags_dividend_interest_and_capital_gain_labels_without_touching_transfers() {
        for label in [
            "Interest",
            "Intérêts créditeurs",
            "Interets mensuels",
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
}
