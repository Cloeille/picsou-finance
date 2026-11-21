//! The AMEX transaction reconciliation of AmexSyncService: derived ids, the routine 90-day upsert,
//! the history merge, legacy id re-keying, relabelled charges and settled pending charges.
//!
//! Rows are worked on in memory, then written back as JPA would flush them: every stored row that
//! changed is updated, deleted rows are removed, and the new rows are inserted.

use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

use chrono::{DateTime, Days, NaiveDate, Utc};
use regex::Regex;
use rust_decimal::Decimal;
use sha2::{Digest, Sha256};
use sqlx::PgConnection;

use crate::ledger::model::{NewTransaction, Transaction};
use crate::ledger::transactions;

const POSTED_PREFIX: &str = "amex_tx_";
/// A pending charge has its own prefix, the only rows a routine sync may delete: AMEX reports it
/// again under a posted identity once it settles, or drops a cancelled hold.
const PENDING_PREFIX: &str = "amex_txp_";
/// Routine syncs only upsert reported rows dated inside this trailing window.
const TRANSACTION_WINDOW_DAYS: u64 = 90;
/// AMEX usually settles a charge within a few business days; a week also covers a weekend and
/// bank holidays. Requiring the exact amount keeps that window from pairing unrelated charges.
const SETTLEMENT_WINDOW_DAYS: i64 = 7;
/// How recent a row of the earlier id format must be to be treated as possibly pending.
const LEGACY_PENDING_WINDOW_DAYS: u64 = 7;

static CURRENT_TX_ID: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^amex_txp?_[0-9a-f]{32}$").expect("regex"));

#[derive(Debug, Clone)]
pub struct PreparedTransaction {
    pub external_id: String,
    pub date: NaiveDate,
    pub label: String,
    pub amount_eur: Decimal,
}

/// The derived id of a charge without a provider id: the prefix of its feed and the first 16
/// bytes of SHA-256 over date, label, amount without trailing zeros and occurrence.
pub fn identity(
    pending: bool,
    date: NaiveDate,
    description: &str,
    amount: Decimal,
    occurrence: usize,
) -> String {
    let key = format!("{date}|{description}|{}|{occurrence}", amount.normalize());
    let digest = Sha256::digest(key.as_bytes());
    let prefix = if pending {
        PENDING_PREFIX
    } else {
        POSTED_PREFIX
    };
    format!("{prefix}{}", hex::encode(&digest[..16]))
}

/// One row as the sync sees it: a stored row (with its loaded state) or a new one.
#[derive(Debug, Clone)]
struct Row {
    stored: Option<Transaction>,
    external_id: Option<String>,
    date: NaiveDate,
    description: String,
    amount: Decimal,
    category_id: Option<i64>,
    category: Option<String>,
    category_manual: bool,
    recurring_series_id: Option<i64>,
}

impl Row {
    fn of(tx: Transaction) -> Self {
        Self {
            external_id: tx.external_id.clone(),
            date: tx.date,
            description: tx.description.clone(),
            amount: tx.amount,
            category_id: tx.category_id,
            category: tx.category.clone(),
            category_manual: tx.category_manual,
            recurring_series_id: tx.recurring_series_id,
            stored: Some(tx),
        }
    }

    fn new(tx: &PreparedTransaction) -> Self {
        Self {
            stored: None,
            external_id: Some(tx.external_id.clone()),
            date: tx.date,
            description: tx.label.clone(),
            amount: tx.amount_eur,
            category_id: None,
            category: None,
            category_manual: false,
            recurring_series_id: None,
        }
    }

    fn id_starts_with(&self, prefix: &str) -> bool {
        self.external_id
            .as_deref()
            .is_some_and(|id| id.starts_with(prefix))
    }

    fn has_user_fields(&self) -> bool {
        self.category_id.is_some() || self.recurring_series_id.is_some()
    }

    fn charge(&self) -> (NaiveDate, Decimal) {
        (self.date, self.amount.normalize())
    }

    /// The loaded row with this sync's changes, when it has any.
    fn changed(&self) -> Option<Transaction> {
        let stored = self.stored.as_ref()?;
        let mut next = stored.clone();
        next.external_id = self.external_id.clone();
        next.date = self.date;
        next.description = self.description.clone();
        next.amount = self.amount;
        next.category_id = self.category_id;
        next.category = self.category.clone();
        next.category_manual = self.category_manual;
        next.recurring_series_id = self.recurring_series_id;
        (next != *stored).then_some(next)
    }
}

struct Book {
    rows: Vec<Row>,
    deleted: HashSet<usize>,
}

impl Book {
    fn push_new(&mut self, tx: &PreparedTransaction) -> usize {
        self.rows.push(Row::new(tx));
        self.rows.len() - 1
    }

    fn id(&self, index: usize) -> Option<&str> {
        self.rows[index].external_id.as_deref()
    }

    /// Copies what is set on `from` and still unset on `to`; never overwrites.
    fn carry_user_fields(&mut self, from: usize, to: usize) {
        let source = self.rows[from].clone();
        let target = &mut self.rows[to];
        if source.category_id.is_some() && target.category_id.is_none() && !target.category_manual {
            target.category_id = source.category_id;
            target.category = source.category;
            target.category_manual = source.category_manual;
        }
        if source.recurring_series_id.is_some() && target.recurring_series_id.is_none() {
            target.recurring_series_id = source.recurring_series_id;
        }
    }

    fn can_receive(&self, pending: usize, posted: usize) -> bool {
        let (pending, posted) = (&self.rows[pending], &self.rows[posted]);
        if pending.category_id.is_some() {
            return posted.category_id.is_none() && !posted.category_manual;
        }
        posted.recurring_series_id.is_none()
    }

    /// Rows synced before ids were SHA-256 based carry `amex_tx_` and a base-36 hash and would
    /// never match a current id. They are re-keyed once, numbering repeats of a tuple in date
    /// then row order as the prepared list does. The old format stored no pending state, so a
    /// recent legacy row the response does not report under its posted id becomes pending; a
    /// legacy row whose new id is already held is merged into the holder, which keeps its edits.
    /// Returns the stored rows still in play.
    fn rekey_legacy(&mut self, returned: &HashSet<&str>, today: NaiveDate) -> Vec<usize> {
        let all: Vec<usize> = (0..self.rows.len()).collect();
        let mut legacy: Vec<usize> = all
            .iter()
            .copied()
            .filter(|&i| {
                self.id(i)
                    .is_some_and(|id| id.starts_with("amex_tx") && !CURRENT_TX_ID.is_match(id))
            })
            .collect();
        if legacy.is_empty() {
            return all;
        }
        let stored_id = |row: &Row| row.stored.as_ref().map(|s| s.id);
        legacy.sort_by(|&a, &b| {
            let (a, b) = (&self.rows[a], &self.rows[b]);
            a.date
                .cmp(&b.date)
                .then_with(|| stored_id(a).cmp(&stored_id(b)))
        });
        let legacy_set: HashSet<usize> = legacy.iter().copied().collect();
        let mut holders: HashMap<String, usize> = HashMap::new();
        for &i in &all {
            if let Some(id) = self.id(i)
                && !legacy_set.contains(&i)
            {
                holders.insert(id.to_owned(), i);
            }
        }
        let pending_cutoff = today - Days::new(LEGACY_PENDING_WINDOW_DAYS);
        let mut occurrences: HashMap<String, usize> = HashMap::new();
        let mut rekeyed = 0;
        let mut merged = HashSet::new();
        for i in legacy {
            let (date, description, amount) = {
                let row = &self.rows[i];
                (row.date, row.description.clone(), row.amount)
            };
            let base = identity(false, date, &description, amount, 0);
            let seen = occurrences.entry(base).or_default();
            *seen += 1;
            let occurrence = *seen - 1;
            let posted = identity(false, date, &description, amount, occurrence);
            let pending = identity(true, date, &description, amount, occurrence);
            let still_pending = returned.contains(pending.as_str())
                || (!returned.contains(posted.as_str()) && date >= pending_cutoff);
            let target = if still_pending { pending } else { posted };
            if let Some(&holder) = holders.get(&target) {
                self.carry_user_fields(i, holder);
                merged.insert(i);
                continue;
            }
            self.rows[i].external_id = Some(target.clone());
            holders.insert(target, i);
            rekeyed += 1;
        }
        tracing::info!(
            "American Express re-keyed {rekeyed} earlier transaction id(s) and merged {} duplicate(s)",
            merged.len()
        );
        self.deleted.extend(merged.iter().copied());
        all.into_iter().filter(|i| !merged.contains(i)).collect()
    }

    /// AMEX re-describes a charge between two syncs, and a derived id hashes the label: the
    /// charge would arrive under an id nothing stores and sit beside the row it replaces. A stored
    /// posted row the response no longer reports, with the same date and amount as a reported
    /// posted charge nobody stores, takes that charge's identity and label, keeping its category
    /// and series. A pair is made only when its date and amount hold exactly one stored and one
    /// reported posted charge, and only for rows dated more than the settlement window after the
    /// oldest reported posted charge: the posted feed is one page cut on the posting date while
    /// rows carry the charge date, so a row near that edge may simply sit past the cut.
    fn adopt_relabelled(
        &mut self,
        stored: &[usize],
        reported: &[PreparedTransaction],
        returned: &HashSet<&str>,
    ) {
        let stored_ids: HashSet<Option<&str>> = stored.iter().map(|&i| self.id(i)).collect();
        let unstored: Vec<&PreparedTransaction> = reported
            .iter()
            .filter(|tx| {
                tx.external_id.starts_with(POSTED_PREFIX)
                    && !stored_ids.contains(&Some(tx.external_id.as_str()))
            })
            .collect();
        let Some(oldest_reported) = reported
            .iter()
            .filter(|tx| tx.external_id.starts_with(POSTED_PREFIX))
            .map(|tx| tx.date)
            .min()
        else {
            return;
        };
        if unstored.is_empty() {
            return;
        }
        let edge = oldest_reported + Days::new(SETTLEMENT_WINDOW_DAYS as u64);
        let mut stored_purchases: HashMap<(NaiveDate, Decimal), usize> = HashMap::new();
        let mut candidates: HashMap<(NaiveDate, Decimal), Vec<usize>> = HashMap::new();
        for &i in stored {
            let row = &self.rows[i];
            if !row.id_starts_with(POSTED_PREFIX) {
                continue;
            }
            *stored_purchases.entry(row.charge()).or_default() += 1;
            if !returned.contains(row.external_id.as_deref().unwrap_or_default()) && row.date > edge
            {
                candidates.entry(row.charge()).or_default().push(i);
            }
        }
        let mut reported_purchases: HashMap<(NaiveDate, Decimal), usize> = HashMap::new();
        for tx in reported
            .iter()
            .filter(|tx| tx.external_id.starts_with(POSTED_PREFIX))
        {
            *reported_purchases
                .entry((tx.date, tx.amount_eur.normalize()))
                .or_default() += 1;
        }
        let mut charges: Vec<((NaiveDate, Decimal), &PreparedTransaction)> = Vec::new();
        for tx in unstored {
            let charge = (tx.date, tx.amount_eur.normalize());
            if !charges.iter().any(|(c, _)| *c == charge) {
                charges.push((charge, tx));
            }
        }
        let mut adopted = 0;
        for (charge, tx) in charges {
            let Some(&row) = candidates.get(&charge).and_then(|rows| rows.first()) else {
                continue;
            };
            if stored_purchases.get(&charge) != Some(&1)
                || reported_purchases.get(&charge) != Some(&1)
            {
                continue;
            }
            self.rows[row].external_id = Some(tx.external_id.clone());
            self.rows[row].description = tx.label.clone();
            adopted += 1;
        }
        if adopted > 0 {
            tracing::info!(
                "American Express matched {adopted} relabelled charge(s) to stored rows"
            );
        }
    }

    /// A settled charge comes back as a posted row while its pending row is deleted, so what the
    /// user set on the pending row (category, recurring series) is copied onto the posted row it
    /// became. The same date, label, amount and occurrence give the same hash suffix under both
    /// prefixes; otherwise the closest posted row with the exact amount within the settlement
    /// window that can still receive the edit is taken, each row paired at most once.
    fn carry_over_settled(&mut self, obsolete_pending: &[usize], posted: &[usize]) {
        let edited: Vec<usize> = obsolete_pending
            .iter()
            .copied()
            .filter(|&i| self.rows[i].has_user_fields())
            .collect();
        if edited.is_empty() || posted.is_empty() {
            return;
        }
        let mut posted_by_suffix: HashMap<String, usize> = HashMap::new();
        for &i in posted {
            if let Some(suffix) = self.id(i).and_then(|id| id.strip_prefix(POSTED_PREFIX)) {
                posted_by_suffix.insert(suffix.to_owned(), i);
            }
        }
        let mut paired: HashSet<usize> = HashSet::new();
        for &pending in &edited {
            let same = self
                .id(pending)
                .and_then(|id| id.strip_prefix(PENDING_PREFIX))
                .and_then(|suffix| posted_by_suffix.get(suffix))
                .copied();
            if let Some(same) = same {
                self.carry_user_fields(pending, same);
                paired.insert(pending);
                paired.insert(same);
            }
        }
        let mut candidates: Vec<(usize, usize, i64)> = Vec::new();
        for &pending in &edited {
            if paired.contains(&pending) {
                continue;
            }
            for &row in posted {
                let days = (self.rows[pending].date - self.rows[row].date)
                    .num_days()
                    .abs();
                if !paired.contains(&row)
                    && self.rows[row].amount == self.rows[pending].amount
                    && days <= SETTLEMENT_WINDOW_DAYS
                    && self.can_receive(pending, row)
                {
                    candidates.push((pending, row, days));
                }
            }
        }
        candidates.sort_by(|a, b| {
            a.2.cmp(&b.2)
                .then_with(|| self.rows[a.0].date.cmp(&self.rows[b.0].date))
                .then_with(|| self.id(a.0).cmp(&self.id(b.0)))
                .then_with(|| self.id(a.1).cmp(&self.id(b.1)))
        });
        for (pending, row, _) in candidates {
            if paired.contains(&pending) || paired.contains(&row) {
                continue;
            }
            self.carry_user_fields(pending, row);
            paired.insert(pending);
            paired.insert(row);
        }
    }

    /// Deletes first (a deleted row frees its id), then updates, then inserts.
    async fn write(
        &self,
        conn: &mut PgConnection,
        account_id: i64,
        now: DateTime<Utc>,
    ) -> sqlx::Result<()> {
        for &i in &self.deleted {
            if let Some(stored) = &self.rows[i].stored {
                transactions::delete(&mut *conn, stored.id).await?;
            }
        }
        for (i, row) in self.rows.iter().enumerate() {
            if self.deleted.contains(&i) {
                continue;
            }
            if let Some(changed) = row.changed() {
                transactions::update(&mut *conn, &changed).await?;
            }
        }
        for row in self.rows.iter().filter(|r| r.stored.is_none()) {
            let mut draft =
                NewTransaction::new(account_id, row.date, row.description.clone(), row.amount);
            draft.external_id = row.external_id.clone();
            draft.category_id = row.category_id;
            draft.category = row.category.clone();
            draft.category_manual = row.category_manual;
            let mut inserted = transactions::insert(&mut *conn, &draft, now).await?;
            if row.recurring_series_id.is_some() {
                inserted.recurring_series_id = row.recurring_series_id;
                transactions::update(&mut *conn, &inserted).await?;
            }
        }
        Ok(())
    }
}

/// Posted rows a settled pending may have become: stored on any earlier sync, or new now.
fn posted_rows(book: &Book, stored_posted: &[usize], inserted: &[usize]) -> Vec<usize> {
    let mut posted = stored_posted.to_vec();
    posted.extend(
        inserted
            .iter()
            .copied()
            .filter(|&i| !book.rows[i].id_starts_with(PENDING_PREFIX)),
    );
    posted
}

/// A routine sync upserts the 90-day window and deletes only the stored pending charges an
/// answered pending feed no longer reports. A history import inserts what nothing stores yet and
/// never updates or deletes a row, except those same pending charges. Both re-key relabelled
/// posted charges.
pub async fn sync_transactions(
    conn: &mut PgConnection,
    account_id: i64,
    transactions: &[PreparedTransaction],
    pending_complete: bool,
    history: bool,
    today: NaiveDate,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    if transactions.is_empty() {
        return Ok(());
    }
    let returned: HashSet<&str> = transactions
        .iter()
        .map(|tx| tx.external_id.as_str())
        .collect();
    let loaded = transactions::find_synced_by_account(&mut *conn, account_id, None).await?;
    let mut book = Book {
        rows: loaded.into_iter().map(Row::of).collect(),
        deleted: HashSet::new(),
    };
    let stored = book.rekey_legacy(&returned, today);
    book.adopt_relabelled(&stored, transactions, &returned);
    // The posted feed is one page, cut on a date that is not the one rows carry, so a posted row
    // missing from it proves nothing: posted rows are never deleted here.
    let mut obsolete = Vec::new();
    let mut stored_posted = Vec::new();
    for &i in &stored {
        let Some(id) = book.id(i) else {
            continue;
        };
        if !id.starts_with(PENDING_PREFIX) {
            stored_posted.push(i);
        } else if pending_complete && !returned.contains(id) {
            obsolete.push(i);
        }
    }

    if history {
        let mut known: HashSet<String> = stored
            .iter()
            .map(|&i| {
                let row = &book.rows[i];
                row.external_id
                    .clone()
                    .unwrap_or_else(|| identity(false, row.date, &row.description, row.amount, 0))
            })
            .collect();
        let mut missing = Vec::new();
        for tx in transactions {
            if known.insert(tx.external_id.clone()) {
                missing.push(book.push_new(tx));
            }
        }
        let posted = posted_rows(&book, &stored_posted, &missing);
        book.carry_over_settled(&obsolete, &posted);
    } else {
        let cutoff = today - Days::new(TRANSACTION_WINDOW_DAYS);
        let mut seen = HashSet::new();
        let reported: Vec<&PreparedTransaction> = transactions
            .iter()
            .filter(|tx| tx.date >= cutoff && seen.insert(tx.external_id.as_str()))
            .collect();
        let mut stored_by_id: HashMap<String, usize> = HashMap::new();
        for &i in &stored {
            if let Some(id) = book.id(i)
                && seen.contains(id)
            {
                stored_by_id.insert(id.to_owned(), i);
            }
        }
        let mut inserted = Vec::new();
        for tx in reported {
            let i = match stored_by_id.get(&tx.external_id) {
                Some(&i) => i,
                None => {
                    let i = book.push_new(tx);
                    inserted.push(i);
                    i
                }
            };
            let row = &mut book.rows[i];
            row.date = tx.date;
            row.description = tx.label.clone();
            row.amount = tx.amount_eur;
        }
        let posted = posted_rows(&book, &stored_posted, &inserted);
        book.carry_over_settled(&obsolete, &posted);
    }
    book.deleted.extend(obsolete);
    book.write(conn, account_id, now).await
}
