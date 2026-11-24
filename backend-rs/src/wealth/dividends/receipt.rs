//! DividendReceiptService: the dividends received over a window, and classifyRecent, which
//! recognises a recent cash credit as the dividend of a held line (amount within 40%..110% of
//! quantity x per-share around a stored ex-date, and a label that names the instrument).

use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

use crate::db::Db;
use async_trait::async_trait;
use chrono::{DateTime, Days, NaiveDate, Utc};
use regex::Regex;
use rust_decimal::Decimal;
use serde::Serialize;
use unicode_normalization::UnicodeNormalization;

use super::forecast::{self, Origin};
use super::model::{DividendEvent, DividendSource, sources_by_ticker, sources_by_ticker_on};
use crate::AppState;
use crate::error::AppResult;
use crate::ledger::model::{
    Account, AccountHolding, AccountType, CategoryKind, Transaction, TransactionType,
};
use crate::ledger::transfers::DividendClassifier;
use crate::ledger::{access, accounts, categorize, holdings, transactions};

const LOOKBACK_DAYS: u64 = 45;
const EX_TO_PAYMENT_MAX_DAYS: u64 = 60;
const PAYMENT_DATE_TOLERANCE_DAYS: i64 = 7;
const CATEGORY_SLUG: &str = "revenus-capital";
const MIN_DAYS: i32 = 1;
const MAX_DAYS: i32 = 366;
const MIN_ROOT_LENGTH: usize = 3;
const MIN_STRONG_ROOT_LENGTH: usize = 4;
const MIN_NAME_TOKEN_LENGTH: usize = 4;
const KEYWORDS: [&str; 4] = ["divid", "coupon", "dvd", "distribution"];
const NAME_STOP_WORDS: [&str; 22] = [
    "group",
    "groupe",
    "holding",
    "holdings",
    "corp",
    "corporation",
    "company",
    "inc",
    "ishares",
    "amundi",
    "lyxor",
    "xtrackers",
    "vanguard",
    "ucits",
    "core",
    "msci",
    "world",
    "index",
    "class",
    "shares",
    "action",
    "actions",
];

static NON_WORD: LazyLock<Regex> = LazyLock::new(|| Regex::new("[^a-z0-9]+").expect("regex"));

/// ReceivedDividendResponse.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReceivedDividendResponse {
    pub id: String,
    pub transaction_id: i64,
    pub account_id: i64,
    pub account_name: String,
    pub account_type: AccountType,
    pub ticker: Option<String>,
    pub name: Option<String>,
    pub received_date: NaiveDate,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub amount: Decimal,
    pub currency: String,
    pub ex_date: Option<NaiveDate>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub amount_per_share: Option<Decimal>,
    pub counts_in_budget: bool,
}

pub fn clamp_days(days: i32) -> i32 {
    days.clamp(MIN_DAYS, MAX_DAYS)
}

fn has_text(value: Option<&str>) -> bool {
    value.is_some_and(|v| !v.trim().is_empty())
}

/// received(memberId, today, days): DIVIDEND credits of readable accounts, newest first.
pub async fn received(
    state: &AppState,
    member_id: i64,
    today: NaiveDate,
    days: i32,
) -> AppResult<Vec<ReceivedDividendResponse>> {
    let mut conn = state.db.pool_handle();
    let readable = access::readable_accounts(&mut conn, member_id).await?;
    if readable.is_empty() {
        return Ok(Vec::new());
    }
    let by_id: HashMap<i64, &Account> = readable.iter().map(|a| (a.id, a)).collect();
    let ids: Vec<i64> = by_id.keys().copied().collect();
    let from = today - Days::new(clamp_days(days) as u64);
    let rows: Vec<Transaction> =
        transactions::find_by_accounts_between(&mut conn, &ids, from, today)
            .await?
            .into_iter()
            .filter(|t| t.tx_type == Some(TransactionType::Dividend) && t.amount > Decimal::ZERO)
            .collect();
    if rows.is_empty() {
        return Ok(Vec::new());
    }
    let tickers: Vec<String> = rows.iter().filter_map(|t| t.ticker.clone()).collect();
    let by_ticker = sources_by_ticker_on(&mut conn, &tickers).await?;
    let lines: Vec<(&Account, AccountHolding)> =
        holdings::find_positive_by_accounts(&mut conn, &ids)
            .await?
            .into_iter()
            .filter_map(|h| by_id.get(&h.account_id).map(|a| (*a, h)))
            .collect();
    let mut out = Vec::new();
    for tx in rows {
        let Some(credited) = by_id.get(&tx.account_id) else {
            continue;
        };
        let ticker = tx.ticker.clone().filter(|t| !t.trim().is_empty());
        let account = holding_account(credited, ticker.as_deref(), &lines);
        let source = ticker
            .as_ref()
            .and_then(|t| by_ticker.get(&t.to_uppercase()));
        let event = source.and_then(|s| events_for(s, tx.date).into_iter().next());
        let ex_date = event.map(|e| e.ex_date);
        let id = match ex_date {
            Some(ex) => forecast::id(Origin::Holding, account.id, ticker.as_deref(), ex),
            None => format!(
                "HOLDING:{}:{}:tx{}",
                account.id,
                ticker.as_deref().unwrap_or(""),
                tx.id
            ),
        };
        let amount_per_share = match (event, source) {
            (Some(e), Some(s)) => per_share(&tx, e, s),
            _ => None,
        };
        out.push(ReceivedDividendResponse {
            id,
            transaction_id: tx.id,
            account_id: account.id,
            account_name: account.name.clone(),
            account_type: account.account_type,
            ticker,
            name: tx.name.clone(),
            received_date: tx.date,
            amount: tx.amount,
            currency: tx.native_currency.clone(),
            ex_date,
            amount_per_share,
            counts_in_budget: !tx.budget_neutral
                && forecast::counts_in_budget(account.account_type, false),
        });
    }
    out.sort_by(|a, b| {
        b.received_date
            .cmp(&a.received_date)
            .then(b.transaction_id.cmp(&a.transaction_id))
    });
    Ok(out)
}

/// The account a credit is attributed to: the one holding the line, or a same-provider sibling
/// when the bank books the dividend on the cash account.
fn holding_account<'a>(
    credited: &'a Account,
    ticker: Option<&str>,
    lines: &[(&'a Account, AccountHolding)],
) -> &'a Account {
    let Some(ticker) = ticker else {
        return credited;
    };
    let holders: Vec<&Account> = lines
        .iter()
        .filter(|(_, h)| h.ticker.eq_ignore_ascii_case(ticker))
        .map(|(a, _)| *a)
        .collect();
    let Some(provider) = credited.provider.as_deref() else {
        return credited;
    };
    if holders.iter().any(|a| a.id == credited.id) {
        return credited;
    }
    let mut same: Vec<&Account> = holders
        .into_iter()
        .filter(|a| a.provider.as_deref() == Some(provider))
        .collect();
    same.dedup_by_key(|a| a.id);
    if same.len() == 1 { same[0] } else { credited }
}

/// The stored events the credit can be paying: ex-date within 60 days before it, payment date
/// (when stored) within a week of it; newest ex-date first.
fn events_for(source: &DividendSource, date: NaiveDate) -> Vec<&DividendEvent> {
    let earliest = date - Days::new(EX_TO_PAYMENT_MAX_DAYS);
    let mut events: Vec<&DividendEvent> = source
        .events
        .iter()
        .filter(|e| e.ex_date >= earliest && e.ex_date <= date)
        .filter(|e| {
            e.payment_date
                .is_none_or(|p| (date - p).num_days().abs() <= PAYMENT_DATE_TOLERANCE_DAYS)
        })
        .collect();
    events.sort_by_key(|e| std::cmp::Reverse(e.ex_date));
    events
}

fn is_pence(currency: &str) -> bool {
    currency == "GBp" || currency.eq_ignore_ascii_case("GBX")
}

/// The per-share amount in the credit's currency: EUR from amount_eur, the source's own currency
/// (pence and pounds reconciled), anything else unknown.
fn per_share(
    credit: &Transaction,
    event: &DividendEvent,
    source: &DividendSource,
) -> Option<Decimal> {
    let currency = credit.native_currency.as_str();
    if currency.eq_ignore_ascii_case("EUR") {
        return Some(event.amount_eur);
    }
    let source_currency = source.currency.as_deref()?;
    let pounds = |c: &str| {
        if is_pence(c) {
            "GBP".to_owned()
        } else {
            c.to_owned()
        }
    };
    if !pounds(currency).eq_ignore_ascii_case(&pounds(source_currency)) {
        return None;
    }
    let places = |c: &str| if is_pence(c) { 2i32 } else { 0 };
    let shift = places(currency) - places(source_currency);
    let mut amount = event.amount;
    match shift.cmp(&0) {
        std::cmp::Ordering::Greater => amount *= Decimal::from(10i64.pow(shift as u32)),
        std::cmp::Ordering::Less => {
            let scale = amount.scale() + shift.unsigned_abs();
            amount.set_scale(scale).ok()?;
        }
        std::cmp::Ordering::Equal => {}
    }
    Some(amount)
}

fn normalize(text: &str) -> String {
    text.nfd()
        .filter(|c| !unicode_normalization::char::is_combining_mark(*c))
        .collect::<String>()
        .to_lowercase()
}

fn words_of(normalized: &str) -> HashSet<String> {
    NON_WORD
        .split(normalized)
        .filter(|w| !w.is_empty())
        .map(str::to_owned)
        .collect()
}

fn label_of(tx: &Transaction) -> String {
    normalize(&format!(
        "{} {} {}",
        tx.description,
        tx.counterparty.as_deref().unwrap_or(""),
        tx.merchant_label.as_deref().unwrap_or("")
    ))
}

fn names_instrument(
    label_words: &HashSet<String>,
    holding: &AccountHolding,
    evidence: bool,
) -> bool {
    let ticker = holding.ticker.to_lowercase();
    let root = ticker.split('.').next().unwrap_or(&ticker);
    let min_root = if evidence {
        MIN_ROOT_LENGTH
    } else {
        MIN_STRONG_ROOT_LENGTH
    };
    if root.chars().count() >= min_root && label_words.contains(root) {
        return true;
    }
    let Some(name) = holding.name.as_deref() else {
        return false;
    };
    let name_words: Vec<String> = words_of(&normalize(name))
        .into_iter()
        .filter(|w| {
            w.chars().count() >= MIN_NAME_TOKEN_LENGTH
                && !NAME_STOP_WORDS.contains(&w.as_str())
                && w != "etf"
        })
        .collect();
    if name_words.is_empty() {
        return false;
    }
    if evidence {
        name_words.iter().any(|w| label_words.contains(w))
    } else {
        name_words.iter().all(|w| label_words.contains(w))
    }
}

fn fits_amount(
    credit: &Transaction,
    holding: &AccountHolding,
    event: &DividendEvent,
    source: &DividendSource,
) -> bool {
    let Some(per_share) = per_share(credit, event, source) else {
        return false;
    };
    let expected = holding.quantity * per_share;
    if expected <= Decimal::ZERO {
        return false;
    }
    let ratio =
        crate::ledger::decimal::divide(credit.amount, expected, 8, crate::ledger::decimal::HALF_UP);
    ratio >= Decimal::new(40, 2) && ratio <= Decimal::new(110, 2)
}

struct Classifier<'a> {
    lines: Vec<(&'a Account, AccountHolding)>,
    by_ticker: HashMap<String, DividendSource>,
}

impl Classifier<'_> {
    fn candidates(&self, account: &Account) -> Vec<&(&Account, AccountHolding)> {
        let own: Vec<_> = self
            .lines
            .iter()
            .filter(|(a, _)| a.id == account.id)
            .collect();
        match account.provider.as_deref() {
            Some(provider) if own.is_empty() => self
                .lines
                .iter()
                .filter(|(a, _)| a.provider.as_deref() == Some(provider))
                .collect(),
            _ => own,
        }
    }

    fn pick(&self, credit: &Transaction, account: &Account) -> Option<&AccountHolding> {
        let label_words = words_of(&label_of(credit));
        let keyword = label_words
            .iter()
            .any(|w| KEYWORDS.iter().any(|k| w.starts_with(k)));
        let declared = credit.tx_type == Some(TransactionType::Dividend);
        let mut candidates = Vec::new();
        for (_, holding) in self.candidates(account) {
            if let Some(t) = credit.ticker.as_deref().filter(|t| !t.trim().is_empty())
                && !t.eq_ignore_ascii_case(&holding.ticker)
            {
                continue;
            }
            if let Some(source) = self.by_ticker.get(&holding.ticker.to_uppercase())
                && events_for(source, credit.date)
                    .into_iter()
                    .any(|e| fits_amount(credit, holding, e, source))
            {
                candidates.push(holding);
            }
        }
        let named: Vec<&AccountHolding> = candidates
            .iter()
            .copied()
            .filter(|h| names_instrument(&label_words, h, keyword || declared))
            .collect();
        if named.len() == 1 {
            return Some(named[0]);
        }
        if (keyword || declared) && candidates.len() == 1 {
            return Some(candidates[0]);
        }
        None
    }
}

/// classifyRecent: types, names and categorises the member's recent credits that are a held
/// line's dividend. Never overrides a manual or non-income category. Returns how many changed.
pub async fn classify_recent(
    db: &Db,
    member_id: i64,
    today: NaiveDate,
    now: DateTime<Utc>,
) -> AppResult<usize> {
    let owned = accounts::find_all_by_member(db, member_id).await?;
    if owned.is_empty() {
        return Ok(0);
    }
    let ids: Vec<i64> = owned.iter().map(|a| a.id).collect();
    let kinds = category_kinds(db, member_id).await?;
    let credits: Vec<Transaction> =
        transactions::find_by_accounts_between(db, &ids, today - Days::new(LOOKBACK_DAYS), today)
            .await?
            .into_iter()
            .filter(|t| is_classifiable(t, &kinds))
            .collect();
    if credits.is_empty() {
        return Ok(0);
    }
    let by_id: HashMap<i64, &Account> = owned.iter().map(|a| (a.id, a)).collect();
    let lines: Vec<(&Account, AccountHolding)> = holdings::find_positive_by_accounts(db, &ids)
        .await?
        .into_iter()
        .filter(|h| h.quantity > Decimal::ZERO && !h.ticker.trim().is_empty())
        .filter_map(|h| by_id.get(&h.account_id).map(|a| (*a, h)))
        .filter(|(a, _)| a.account_type != AccountType::Crypto)
        .collect();
    if lines.is_empty() {
        return Ok(0);
    }
    let tickers: Vec<String> = lines.iter().map(|(_, h)| h.ticker.clone()).collect();
    let by_ticker = sources_by_ticker(db, &tickers).await?;
    if by_ticker.is_empty() {
        return Ok(0);
    }
    let classifier = Classifier { lines, by_ticker };
    let mut target: Option<Option<i64>> = None;
    let mut changed = Vec::new();
    for mut credit in credits {
        let Some(account) = by_id.get(&credit.account_id) else {
            continue;
        };
        let Some(holding) = classifier.pick(&credit, account) else {
            continue;
        };
        if target.is_none() {
            target = Some(
                categorize::categories_by_slug(db, member_id, now)
                    .await?
                    .get(CATEGORY_SLUG)
                    .map(|c| c.id),
            );
        }
        if apply(&mut credit, holding, target.flatten(), &kinds) {
            changed.push(credit);
        }
    }
    if !changed.is_empty() {
        let mut db_tx = db.begin().await?;
        for tx in &changed {
            transactions::update(&mut db_tx, tx).await?;
        }
        db_tx.commit().await?;
    }
    Ok(changed.len())
}

async fn category_kinds(db: &Db, member_id: i64) -> sqlx::Result<HashMap<i64, CategoryKind>> {
    let rows: Vec<(i64, String)> = crate::db::query_as::<(i64, String)>(
        "SELECT id, kind::text FROM category WHERE member_id = $1",
    )
    .bind(member_id)
    .fetch_all(db)
    .await?;
    Ok(rows
        .into_iter()
        .filter_map(|(id, kind)| kind.parse().ok().map(|k| (id, k)))
        .collect())
}

/// A category the classifier may replace: none, or an income one.
fn replaceable(category_id: Option<i64>, kinds: &HashMap<i64, CategoryKind>) -> bool {
    category_id.is_none_or(|id| kinds.get(&id) == Some(&CategoryKind::Income))
}

fn is_classifiable(tx: &Transaction, kinds: &HashMap<i64, CategoryKind>) -> bool {
    let blocked =
        tx.budget_neutral || tx.category_manual || (tx.is_manual && tx.category_id.is_some());
    tx.amount > Decimal::ZERO
        && !blocked
        && replaceable(tx.category_id, kinds)
        && !tx
            .category
            .as_deref()
            .is_some_and(|c| c.eq_ignore_ascii_case("TRADING"))
        && matches!(
            tx.tx_type,
            None | Some(TransactionType::Deposit) | Some(TransactionType::Dividend)
        )
}

fn apply(
    tx: &mut Transaction,
    holding: &AccountHolding,
    target: Option<i64>,
    kinds: &HashMap<i64, CategoryKind>,
) -> bool {
    let mut changed = false;
    if tx.tx_type != Some(TransactionType::Dividend) {
        tx.tx_type = Some(TransactionType::Dividend);
        changed = true;
    }
    if !has_text(tx.ticker.as_deref()) {
        tx.ticker = Some(holding.ticker.clone());
        changed = true;
    }
    if !has_text(tx.name.as_deref()) && has_text(holding.name.as_deref()) {
        tx.name = holding.name.clone();
        changed = true;
    }
    if let Some(target) = target
        && replaceable(tx.category_id, kinds)
        && tx.category_id != Some(target)
    {
        tx.category_id = Some(target);
        changed = true;
    }
    changed
}

/// The ledger's import pipeline calls this after every bank import.
pub struct ReceiptClassifier;

#[async_trait]
impl DividendClassifier for ReceiptClassifier {
    async fn classify_recent(
        &self,
        db: &Db,
        member_id: i64,
        today: NaiveDate,
        now: DateTime<Utc>,
    ) -> AppResult<usize> {
        classify_recent(db, member_id, today, now).await
    }
}
