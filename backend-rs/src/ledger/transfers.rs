//! Internal transfer pairing (InternalTransferPairingService) and the passes that run once an
//! import has committed (ReceivedDividendClassifier).
//!
//! Two legs pair when they sit on two different owned accounts, carry the same currency and
//! exactly opposite amounts, and are booked at most [MAX_DAY_GAP] days apart. A pair is taken
//! only when each leg is the other's single candidate; a leg categorised by hand never takes part.

use std::collections::{HashMap, HashSet};

use async_trait::async_trait;
use chrono::{DateTime, Days, NaiveDate, Utc};
use rust_decimal::Decimal;
use sqlx::{PgExecutor, PgPool};

use super::accounts;
use super::categorize::{self, TRANSFER_SLUG};
use super::model::{AccountType, CategoryKind, Transaction, TransactionType};
use super::transactions;
use crate::error::AppResult;

/// Matches the bank sync lookback: older rows were paired by an earlier sync.
pub const LOOKBACK_DAYS: u64 = 90;
/// The largest booking gap, in days, between the two legs of a transfer.
pub const MAX_DAY_GAP: u64 = 3;
/// Raw Trade Republic category of a trade row: its importer already pairs it.
const TRADING: &str = "TRADING";

/// Pairs the recent transfer legs of the member's visible accounts, categorises them
/// virement-interne in one transaction and returns the number of rows it recategorised.
/// A second run changes nothing: a leg already in a TRANSFER category keeps it.
///
/// Rows up to MAX_DAY_GAP days past either end of the lookback window are loaded only as
/// rivals that make a group ambiguous: a pair is taken only when both legs sit inside it.
pub async fn pair_recent(
    db: &PgPool,
    member_id: i64,
    today: NaiveDate,
    now: DateTime<Utc>,
) -> sqlx::Result<usize> {
    let from = today - Days::new(LOOKBACK_DAYS);
    pair_between(db, member_id, from, today, now).await
}

/// Pairs transfer legs across the member's full history up to `today`.
/// Used by manual recategorization so historical cross-bank transfers older than
/// [LOOKBACK_DAYS] are also matched.
pub async fn pair_all(
    db: &PgPool,
    member_id: i64,
    today: NaiveDate,
    now: DateTime<Utc>,
) -> sqlx::Result<usize> {
    let from = NaiveDate::from_ymd_opt(1970, 1, 1).unwrap_or(today);
    pair_between(db, member_id, from, today, now).await
}

async fn pair_between(
    db: &PgPool,
    member_id: i64,
    from: NaiveDate,
    to: NaiveDate,
    now: DateTime<Utc>,
) -> sqlx::Result<usize> {
    let mut tx = db.begin().await?;
    // A loan repayment is budgeted as an expense, and peer loans book their own transfer.
    let account_ids: Vec<i64> = accounts::find_visible_by_member(&mut *tx, member_id)
        .await?
        .into_iter()
        .filter(|a| !matches!(a.account_type, AccountType::Loan | AccountType::PeerLoan))
        .map(|a| a.id)
        .collect();
    if account_ids.len() < 2 {
        return Ok(0);
    }
    let gap = Days::new(MAX_DAY_GAP);
    let mut legs: Vec<Transaction> =
        transactions::find_by_accounts_between(&mut *tx, &account_ids, from - gap, to + gap)
            .await?
            .into_iter()
            .filter(is_candidate)
            .collect();
    let in_window = |leg: &Transaction| leg.date >= from && leg.date <= to;
    let paired: Vec<usize> = unique_pairs(&legs)
        .into_iter()
        .filter(|&(debit, credit)| in_window(&legs[debit]) && in_window(&legs[credit]))
        .flat_map(|(debit, credit)| [debit, credit])
        .collect();
    if paired.is_empty() {
        return Ok(0);
    }
    // Seeds the defaults in a transaction of its own, as Java's REQUIRES_NEW ensureSeeded.
    let Some(target) = categorize::categories_by_slug(db, member_id, now)
        .await?
        .remove(TRANSFER_SLUG)
        .filter(|c| c.kind == CategoryKind::Transfer)
    else {
        return Ok(0);
    };
    let current: Vec<i64> = paired.iter().filter_map(|&i| legs[i].category_id).collect();
    let already_transfer = transfer_category_ids(&mut *tx, &current).await?;
    let mut changed = 0;
    for i in paired {
        let leg = &mut legs[i];
        if leg
            .category_id
            .is_some_and(|id| already_transfer.contains(&id))
        {
            continue;
        }
        leg.category_id = Some(target.id);
        leg.ai_suggested_category_id = None;
        leg.ai_confidence = None;
        transactions::update(&mut *tx, leg).await?;
        changed += 1;
    }
    tx.commit().await?;
    Ok(changed)
}

/// Indices of the (debit, credit) pairs in which each leg is the other's only candidate. Every
/// cash leg counts as a candidate, a manual, typed or same-account one included, so it makes a
/// group ambiguous; the pair is then taken only when both legs are pairable and sit on two
/// different accounts.
fn unique_pairs(legs: &[Transaction]) -> Vec<(usize, usize)> {
    let mut credits_by_key: HashMap<(String, Decimal), Vec<usize>> = HashMap::new();
    for (i, leg) in legs.iter().enumerate() {
        if leg.amount > Decimal::ZERO {
            credits_by_key.entry(key(leg)).or_default().push(i);
        }
    }
    let mut candidates_by_debit: Vec<(usize, Vec<usize>)> = Vec::new();
    let mut debit_count_by_credit: HashMap<usize, usize> = HashMap::new();
    for (debit, leg) in legs.iter().enumerate() {
        if leg.amount >= Decimal::ZERO {
            continue;
        }
        let candidates: Vec<usize> = credits_by_key
            .get(&key(leg))
            .into_iter()
            .flatten()
            .copied()
            .filter(|&credit| {
                (leg.date - legs[credit].date).num_days().unsigned_abs() <= MAX_DAY_GAP
            })
            .collect();
        for &credit in &candidates {
            *debit_count_by_credit.entry(credit).or_default() += 1;
        }
        candidates_by_debit.push((debit, candidates));
    }
    candidates_by_debit
        .into_iter()
        .filter_map(|(debit, candidates)| match candidates[..] {
            [credit] => Some((debit, credit)),
            _ => None,
        })
        .filter(|&(debit, credit)| {
            debit_count_by_credit[&credit] == 1
                && is_pairable(&legs[debit])
                && is_pairable(&legs[credit])
                && legs[debit].account_id != legs[credit].account_id
        })
        .collect()
}

/// Upper-cased currency and absolute amount; Decimal equality and hashing ignore the scale, so
/// -100.00 and 100 share a key as with Java's stripTrailingZeros.
fn key(tx: &Transaction) -> (String, Decimal) {
    (tx.native_currency.to_uppercase(), tx.amount.abs())
}

/// Any leg that could be the other side of a transfer. Budget-neutral rows and Trade Republic
/// TRADING rows are left out: their importer already pairs them.
fn is_candidate(tx: &Transaction) -> bool {
    !tx.amount.is_zero()
        && !tx.budget_neutral
        && !tx
            .category
            .as_deref()
            .is_some_and(|c| java_equals_ignore_case(c, TRADING))
}

/// Uncategorised or auto-categorised cash legs. A hand-entered row saved with a category before
/// category_manual existed carries the user's choice all the same; a trade leg is not a transfer.
fn is_pairable(tx: &Transaction) -> bool {
    !(tx.category_manual || (tx.is_manual && tx.category_id.is_some()))
        && matches!(
            tx.tx_type,
            None | Some(TransactionType::Deposit) | Some(TransactionType::Withdrawal)
        )
}

/// String.equalsIgnoreCase: char by char, equal after upper-casing or after lower-casing the
/// upper case (the Kelvin sign matches "k", the dotless i matches "I").
fn java_equals_ignore_case(a: &str, b: &str) -> bool {
    a.chars().count() == b.chars().count()
        && a.chars().zip(b.chars()).all(|(x, y)| {
            x == y
                || x.to_uppercase().eq(y.to_uppercase())
                || x.to_uppercase()
                    .flat_map(char::to_lowercase)
                    .eq(y.to_uppercase().flat_map(char::to_lowercase))
        })
}

/// Which of the categories are of kind TRANSFER, whoever owns them.
async fn transfer_category_ids<'e>(
    db: impl PgExecutor<'e>,
    ids: &[i64],
) -> sqlx::Result<HashSet<i64>> {
    let found: Vec<i64> = sqlx::query_scalar(
        "SELECT id FROM category WHERE id = ANY($1) AND kind::text = 'TRANSFER'",
    )
    .bind(ids)
    .fetch_all(db)
    .await?;
    Ok(found.into_iter().collect())
}

/// DividendReceiptService.classifyRecent, owned by the dividend track.
#[async_trait]
pub trait DividendClassifier: Send + Sync {
    /// Classifies the member's recent cash credits that match a dividend; returns how many changed.
    async fn classify_recent(
        &self,
        db: &PgPool,
        member_id: i64,
        today: NaiveDate,
        now: DateTime<Utc>,
    ) -> AppResult<usize>;
}

/// The classifier when the dividend feature is absent: classifies nothing.
pub struct NoDividendClassifier;

#[async_trait]
impl DividendClassifier for NoDividendClassifier {
    async fn classify_recent(
        &self,
        _db: &PgPool,
        _member_id: i64,
        _today: NaiveDate,
        _now: DateTime<Utc>,
    ) -> AppResult<usize> {
        Ok(0)
    }
}

/// ReceivedDividendClassifier.afterImport: classifies dividends, then pairs transfers, so a
/// recognised dividend is never paired. Best effort: each pass is isolated, and a failure,
/// its commit included, is logged and never reaches the caller.
pub async fn after_import(
    db: &PgPool,
    classifier: &dyn DividendClassifier,
    member_id: i64,
    today: NaiveDate,
    now: DateTime<Utc>,
) {
    if let Err(e) = classifier.classify_recent(db, member_id, today, now).await {
        tracing::error!("Dividend classification failed for member {member_id}: {e}");
    }
    if let Err(e) = pair_recent(db, member_id, today, now).await {
        tracing::error!("Internal transfer pairing failed for member {member_id}: {e}");
    }
}
