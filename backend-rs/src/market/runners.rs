//! The startup runners of the price domain as functions track T8 schedules: the twelve-month
//! price backfill (PriceBackfillRunner), the one-off FX purge (PriceFxCleanupRunner), and the
//! raw-ISIN manual-transaction repair pass (IsinTickerRepairRunner), which also keeps the ISIN as
//! the identity of the ticker it resolves to, and the link of manual lines to known ISINs (#703).

use std::collections::{BTreeMap, BTreeSet};

use crate::db::AsDbHandle;
use crate::db::Db;
use async_trait::async_trait;
use chrono::{Days, Months, NaiveDate};

use super::openfigi::is_isin;
use crate::AppState;
use crate::error::AppResult;
use crate::ledger::instrument::TickerResult;
use crate::ledger::model::AccountType;
use crate::ledger::{accounts, holding_compute, holdings, prices, snapshots, valuation};

/// The app_setting reads and writes the runners need (AppSettingRepository, track T1's).
#[async_trait]
pub trait AppSettings: Send + Sync {
    async fn value(&self, key: &str) -> AppResult<Option<String>>;
    /// Sets the value only if it is still expected; returns the rows changed.
    async fn compare_and_set(&self, key: &str, expected: &str, value: &str) -> AppResult<u64>;
    /// Inserts or replaces the setting's value.
    async fn upsert(&self, key: &str, value: &str) -> AppResult<()>;
}

/// AppSettingRepository's own queries on app_setting.
pub struct SqlAppSettings(pub Db);

#[async_trait]
impl AppSettings for SqlAppSettings {
    async fn value(&self, key: &str) -> AppResult<Option<String>> {
        Ok(crate::db::query_scalar::<String>(
            "SELECT value FROM app_setting WHERE setting_key = $1",
        )
        .bind(key)
        .fetch_optional(&self.0)
        .await?)
    }

    async fn compare_and_set(&self, key: &str, expected: &str, value: &str) -> AppResult<u64> {
        Ok(crate::db::query(
            "UPDATE app_setting SET value = $3 WHERE setting_key = $1 AND value = $2",
        )
        .bind(key)
        .bind(expected)
        .bind(value)
        .execute(&self.0)
        .await?
        .rows_affected())
    }

    async fn upsert(&self, key: &str, value: &str) -> AppResult<()> {
        crate::db::query(
            "INSERT INTO app_setting (setting_key, value) VALUES ($1, $2) \
             ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value",
        )
        .bind(key)
        .bind(value)
        .execute(&self.0)
        .await?;
        Ok(())
    }
}

const FX_CLEANUP_FLAG: &str = "price.fx_fix_cleanup_done";
const PROFILE_RECHECK_FLAG: &str = "security_profile.composition_fix_v1_done";
const PROFILE_RECHECK_BATCH_SIZE: usize = 10;
const PROFILE_RECHECK_STAGGER_DAYS: i64 = 7;
const PROFILE_RECHECK_INITIAL_AGE_DAYS: i64 = 31;
const ISIN_CURSOR_KEY: &str = "isin_repair.cursor";
const ISIN_HISTORY_REWRITE_PREFIX: &str = "isin_history.rewrite_done.";
/// How far back `price_backfill` records daily prices, and so how far back `rewrite_isin_history`
/// can correct a day.
const BACKFILL_MONTHS: u32 = 12;
const MAX_ISINS_PER_BOOT: usize = 25;
const MAX_REPAIR_DURATION: chrono::Duration = chrono::Duration::seconds(60);

fn upper_cased(tickers: Vec<String>) -> BTreeSet<String> {
    tickers
        .into_iter()
        .filter(|t| !t.trim().is_empty())
        .map(|t| t.to_uppercase())
        .collect()
}

/// PriceBackfillRunner: twelve months of daily prices for every held ticker, crypto ones on the
/// crypto route, and for every held ISIN under the ISIN (#702); returns the snapshots written.
pub async fn price_backfill(state: &AppState) -> AppResult<usize> {
    let crypto = upper_cased(
        holdings::distinct_tickers(&state.db, Some((AccountType::Crypto, false))).await?,
    );
    let other: BTreeSet<String> =
        upper_cased(holdings::distinct_market_priced_tickers(&state.db).await?)
            .difference(&crypto)
            .cloned()
            .collect();
    let isins: BTreeSet<String> = holdings::distinct_isins(&state.db)
        .await?
        .into_iter()
        .collect();
    if crypto.is_empty() && other.is_empty() && isins.is_empty() {
        tracing::debug!("No holding tickers found — skipping price backfill");
        return Ok(0);
    }
    let today = state.clock.today();
    let from = today
        .checked_sub_months(Months::new(BACKFILL_MONTHS))
        .unwrap_or(today);
    let prices = state.market.prices();
    let mut saved = 0;
    if !crypto.is_empty() {
        saved += prices.backfill_historical_prices(&crypto, from, true).await;
    }
    if !other.is_empty() {
        saved += prices.backfill_historical_prices(&other, from, false).await;
    }
    if !isins.is_empty() {
        saved += prices.backfill_isin_history(&isins, from).await;
    }
    tracing::info!(
        "Price backfill complete: {saved} snapshots saved for {} tickers ({} crypto) and {} ISINs",
        crypto.len() + other.len(),
        crypto.len(),
        isins.len()
    );
    Ok(saved)
}

/// PriceFxCleanupRunner: once, purges the price history stored before prices were converted to
/// EUR, gated by the app_setting flag; returns the rows purged.
pub async fn price_fx_cleanup(state: &AppState, settings: &dyn AppSettings) -> AppResult<u64> {
    match settings.value(FX_CLEANUP_FLAG).await? {
        None => {
            tracing::debug!("Cleanup gate flag '{FX_CLEANUP_FLAG}' missing, skipping");
            return Ok(0);
        }
        Some(done) if done.eq_ignore_ascii_case("true") => return Ok(0),
        Some(_) => {}
    }
    tracing::warn!(
        "Purging price_snapshot for FX fix — prices stored before the FX fix are in native currency, not EUR"
    );
    let purged = crate::db::query("DELETE FROM price_snapshot")
        .execute(&state.db)
        .await?
        .rows_affected();
    state.market.prices().clear_cache();
    if settings
        .compare_and_set(FX_CLEANUP_FLAG, "false", "true")
        .await?
        == 0
    {
        tracing::info!(
            "Cleanup flag was already flipped by another instance; purged {purged} rows anyway"
        );
    } else {
        tracing::info!(
            "Purged {purged} price_snapshot rows; the backfill rebuilds history with FX-corrected values"
        );
    }
    Ok(purged)
}

/// One-shot startup pass behind `app_setting`: staggers `refreshed_at` across batches (capping
/// `last_attempt_at` to the new `refreshed_at` when more recent so batch 0 is due immediately
/// rather than held back by the 3-day failure backoff, while keeping `status` and existing slices
/// intact so read-time diversification never regresses to empty) on persisted profiles affected by
/// pre-fix bugs — ETFs with slice totals `< 99 %` or synthetic replication (which could hold a
/// substitution basket), stocks missing `country_key`, and `NO_DATA` profiles carrying an ISIN —
/// so upcoming passes repair them gradually without triggering a burst or a recurring re-fetch
/// loop.
///
/// The flag is claimed atomically first (`INSERT ... ON CONFLICT DO NOTHING`) inside the same
/// transaction as the updates so concurrent startups cannot run the pass twice and a failure rolls
/// back both the flag and the updates.
pub async fn schedule_security_profile_recheck(state: &AppState) -> AppResult<u64> {
    let mut tx = state.db.begin().await?;
    let claimed = crate::db::query(
        "INSERT INTO app_setting (setting_key, value) VALUES ($1, 'true') \
         ON CONFLICT (setting_key) DO NOTHING",
    )
    .bind(PROFILE_RECHECK_FLAG)
    .execute(&mut tx)
    .await?
    .rows_affected();
    if claimed == 0 {
        tx.rollback().await?;
        return Ok(0);
    }

    let sum_pred = if state.db.is_sqlite() {
        "dec_cmp(dec_sum(s.percent), '99') < 0"
    } else {
        "SUM(s.percent) < 99"
    };
    let select_sql = format!(
        "SELECT id, refreshed_at \
           FROM security_profile \
          WHERE refreshed_at IS NOT NULL \
            AND ( \
              (status = 'OK' AND asset_type = 'ETF' AND ( \
                replication = 'SYNTHETIC' \
                OR EXISTS ( \
                  SELECT 1 \
                    FROM security_composition_slice s \
                   WHERE s.profile_id = security_profile.id \
                     AND s.kind IN ('SECTOR', 'COUNTRY') \
                   GROUP BY s.kind \
                  HAVING {sum_pred} \
                ) \
              )) \
              OR (status = 'OK' AND asset_type = 'STOCK' AND country_key IS NULL) \
              OR (status = 'NO_DATA' AND isin IS NOT NULL) \
            ) \
          ORDER BY id"
    );
    let affected: Vec<(i64, Option<chrono::DateTime<chrono::Utc>>)> =
        crate::db::query_as(&select_sql).fetch_all(&mut tx).await?;

    let now = state.clock.now();
    for (idx, (id, current_refreshed_at)) in affected.iter().enumerate() {
        let batch = i64::try_from(idx / PROFILE_RECHECK_BATCH_SIZE).unwrap_or(i64::MAX);
        // Batch 0 becomes due immediately (31 days old > 30-day TTL); each subsequent batch steps
        // forward by one weekly cycle (7 days), clamped to at least 1 day old so refreshed_at is
        // never in the future and never newer than what was already persisted.
        let age_days = (PROFILE_RECHECK_INITIAL_AGE_DAYS
            .saturating_sub(batch.saturating_mul(PROFILE_RECHECK_STAGGER_DAYS)))
        .max(1);
        let target = now - chrono::Duration::days(age_days);
        let next_refreshed_at = current_refreshed_at.map_or(target, |cur| cur.min(target));
        crate::db::query(
            "UPDATE security_profile \
                SET refreshed_at = $2, \
                    last_attempt_at = CASE \
                        WHEN last_attempt_at IS NOT NULL AND last_attempt_at > $2 THEN $2 \
                        ELSE last_attempt_at \
                    END \
              WHERE id = $1",
        )
        .bind(*id)
        .bind(next_refreshed_at)
        .execute(&mut tx)
        .await?;
    }

    tx.commit().await?;
    let marked = u64::try_from(affected.len()).unwrap_or(0);
    if marked > 0 {
        tracing::info!(
            "Scheduled staggered one-time refresh for {marked} security profile(s) with incomplete composition or missing equity country"
        );
    }
    Ok(marked)
}

/// Links the lines of manual accounts that have no ISIN to their own ISIN ticker or to the one
/// recorded for their ticker (#703): trades entered before ISINs were kept. Idempotent; returns
/// the lines linked.
pub async fn link_manual_holding_isins(state: &AppState) -> AppResult<u64> {
    let linked = holdings::link_known_isins(&state.db, None).await?;
    if linked > 0 {
        tracing::info!("Linked {linked} manual holding(s) to their ISIN");
    }
    Ok(linked)
}

/// IsinTickerRepairRunner: re-resolves manual transactions still carrying a raw ISIN as their
/// ticker, bounded to 25 ISINs and 60s per pass with a persistent cursor in app_setting, applying
/// each ISIN in its own short transaction and recomputing derived holdings on investment accounts.
/// Returns the number of transactions repaired.
pub async fn isin_ticker_repair(state: &AppState, settings: &dyn AppSettings) -> AppResult<usize> {
    let rows: Vec<(i64, String)> = crate::db::query_as::<(i64, String)>(
        "SELECT id, ticker FROM \"transaction\" \
         WHERE is_manual = true AND ticker IS NOT NULL AND length(ticker) = 12",
    )
    .fetch_all(&state.db)
    .await?;
    if rows.is_empty() {
        return Ok(0);
    }
    let mut by_isin: BTreeMap<String, Vec<i64>> = BTreeMap::new();
    for (id, ticker) in rows {
        if is_isin(&ticker) {
            by_isin.entry(ticker).or_default().push(id);
        }
    }
    if by_isin.is_empty() {
        return Ok(0);
    }
    tracing::info!(
        "Found {} unresolved ISIN ticker(s) on manual transactions, re-resolving",
        by_isin.len()
    );

    let candidates: Vec<String> = by_isin.keys().cloned().collect();
    let start = resume_position(&candidates, settings).await;
    let deadline = state.clock.now() + MAX_REPAIR_DURATION;
    let mut repaired = 0usize;
    let mut last_attempted: Option<String> = None;

    for (attempted, i) in (0..candidates.len()).enumerate() {
        if attempted == MAX_ISINS_PER_BOOT || state.clock.now() > deadline {
            tracing::info!(
                "Stopping after {attempted} ISIN(s) ({}); the next start resumes after {:?}",
                if attempted == MAX_ISINS_PER_BOOT {
                    "per-boot limit"
                } else {
                    "time budget spent"
                },
                last_attempted
            );
            break;
        }
        let isin = &candidates[(start + i) % candidates.len()];
        last_attempted = Some(isin.clone());

        let resolved = state.market.openfigi().resolve(isin).await;
        if resolved.ticker.eq_ignore_ascii_case(isin) {
            tracing::debug!(
                "ISIN {isin} still does not resolve to a ticker, leaving it for a later boot"
            );
            continue;
        }
        let ids = &by_isin[isin];
        match apply_repair(state, isin, ids, &resolved).await {
            Ok(count) => repaired += count,
            Err(e) => {
                tracing::error!(
                    "Repair of ISIN {isin} failed and was rolled back; the rest of the pass continues: {e}"
                );
            }
        }
    }

    if let Some(cursor) = last_attempted
        && let Err(e) = settings.upsert(ISIN_CURSOR_KEY, &cursor).await
    {
        tracing::warn!("Could not persist the ISIN repair cursor at {cursor}: {e}");
    }
    Ok(repaired)
}

async fn resume_position(candidates: &[String], settings: &dyn AppSettings) -> usize {
    let cursor = match settings.value(ISIN_CURSOR_KEY).await {
        Ok(Some(c)) => c,
        _ => return 0,
    };
    let mut position = 0;
    while position < candidates.len() && candidates[position] <= cursor {
        position += 1;
    }
    if position == candidates.len() {
        0
    } else {
        position
    }
}

async fn apply_repair(
    state: &AppState,
    isin: &str,
    tx_ids: &[i64],
    resolved: &TickerResult,
) -> AppResult<usize> {
    let mut tx = state.db.begin().await?;
    let account_ids: Vec<i64> = crate::db::query_scalar::<i64>(
        "SELECT DISTINCT account_id FROM \"transaction\" WHERE is_manual = true AND ticker = $1",
    )
    .bind(isin)
    .fetch_all(&mut tx)
    .await?;

    let updated = crate::db::query(
        "UPDATE \"transaction\" SET ticker = $2, name = COALESCE(name, $3) WHERE id = ANY($1)",
    )
    .bind(tx_ids)
    .bind(&resolved.ticker)
    .bind(&resolved.name)
    .execute(&mut tx)
    .await?
    .rows_affected();

    tracing::info!(
        "Repaired {updated} transaction(s): ISIN {isin} -> {}",
        resolved.ticker
    );

    let affected_accounts = accounts::find_by_ids(&mut tx, &account_ids).await?;
    let investment_ids: Vec<i64> = affected_accounts
        .iter()
        .filter(|a| a.account_type.is_investment())
        .map(|a| a.id)
        .collect();

    // The ISIN stays the instrument's identity once its rows carry the resolved ticker (#703),
    // with the same guards as a manual entry: a real ISIN, held on a non-crypto investment
    // account, and resolved to a ticker other than itself.
    let held_as_security = affected_accounts
        .iter()
        .any(|a| a.account_type.is_investment() && a.account_type != AccountType::Crypto);
    if let Some(isin) = crate::ledger::instrument::normalize_isin(isin)
        .filter(|i| !i.starts_with("XF000") && *i != resolved.ticker.to_uppercase())
        && held_as_security
    {
        holdings::record_ticker_isin(&mut tx, &resolved.ticker, &isin, state.clock.now()).await?;
    }
    if !investment_ids.is_empty() {
        crate::db::query("DELETE FROM account_holding WHERE account_id = ANY($1) AND ticker = $2")
            .bind(&investment_ids)
            .bind(isin)
            .execute(&mut tx)
            .await?;
        let now = state.clock.now();
        for account_id in &investment_ids {
            holding_compute::recompute_holdings(&mut tx.as_handle(), *account_id, now).await?;
        }
        tracing::debug!(
            "Recomputed holdings for {} account(s) after repairing {isin}",
            investment_ids.len()
        );
    }

    tx.commit().await?;
    Ok(usize::try_from(updated).unwrap_or(0))
}

/// One line whose past values may have gone through a wrong ticker (#705).
#[derive(Debug, crate::db::DbFromRow)]
struct IsinLine {
    account_id: i64,
    ticker: String,
    isin: String,
    quantity: rust_decimal::Decimal,
}

/// Rewrites the past DERIVED balance snapshots of accounts with ISIN lines that were valued
/// through a wrong ticker (#683, #705).
///
/// Each line is priced on a past day by its last close on or before that day, within
/// `MAX_FALLBACK_AGE_DAYS`, as the snapshot was built, so weekends and holidays are covered. Where
/// the ticker price contradicts the ISIN price (the #690 factor of two), swapping the line's
/// contribution, `quantity x (isin - ticker)`, gives a candidate balance. The day is rewritten
/// only if its recorded balance is at least twice off its own invested amount, either way, and
/// the candidate is back within twice of it. A day with an ordinary gain or loss is never moved,
/// and a corrected day is no longer suspect, so a rerun changes nothing.
///
/// Left alone: MANUAL snapshots, today's snapshot (written by ISIN since #701), crypto accounts,
/// never-market-priced providers (#809), days without both prices and days with no positive
/// invested amount. An account is flagged once done (app_setting `isin_history.rewrite_done.<id>`)
/// only when every ISIN has a price for each past day of the backfill window; otherwise it is
/// retried at the next boot. One failing account does not stop the others. Current lines and
/// quantities are used for every past day: a snapshot keeps no per-line quantities. Each rewritten
/// snapshot is logged; returns how many.
pub async fn rewrite_isin_history(
    state: &AppState,
    settings: &dyn AppSettings,
) -> AppResult<usize> {
    let positive = if state.db.is_sqlite() {
        "dec_cmp(h.quantity, '0') > 0"
    } else {
        "h.quantity > 0"
    };
    let lines: Vec<IsinLine> = crate::db::query_as(&format!(
        "SELECT h.account_id, h.ticker, h.isin, h.quantity FROM account_holding h \
         JOIN account a ON a.id = h.account_id \
         WHERE h.isin IS NOT NULL AND a.deleted_at IS NULL AND a.type <> $1::account_type \
         AND {positive} AND UPPER(TRIM(h.ticker)) <> h.isin ORDER BY h.account_id, h.id"
    ))
    .bind(AccountType::Crypto)
    .fetch_all(&state.db)
    .await?;
    let mut by_account: BTreeMap<i64, Vec<IsinLine>> = BTreeMap::new();
    for line in lines {
        by_account.entry(line.account_id).or_default().push(line);
    }
    let ids: Vec<i64> = by_account.keys().copied().collect();
    let mut rewritten = 0;
    for account in accounts::find_by_ids(&state.db, &ids).await? {
        if crate::ledger::providers::is_never_market_priced(&account) {
            continue;
        }
        let flag = format!("{ISIN_HISTORY_REWRITE_PREFIX}{}", account.id);
        let lines = &by_account[&account.id];
        let done = async {
            if settings.value(&flag).await?.is_some() {
                return AppResult::Ok(0);
            }
            let (changed, complete) = rewrite_account(state, &account, lines).await?;
            if complete {
                settings.upsert(&flag, &changed.to_string()).await?;
            }
            Ok(changed)
        }
        .await;
        match done {
            Ok(changed) => rewritten += changed,
            Err(e) => tracing::error!(
                "Rewriting the history of account {} failed: {e}",
                account.id
            ),
        }
    }
    if rewritten > 0 {
        tracing::info!("Rewrote {rewritten} balance snapshot(s) valued through a wrong ticker");
    }
    Ok(rewritten)
}

/// Daily closes by ticker or ISIN, then by date.
type Prices = BTreeMap<String, BTreeMap<NaiveDate, rust_decimal::Decimal>>;

/// The last close of `key` on or before `day`, within `MAX_FALLBACK_AGE_DAYS`: the price the
/// snapshot of a weekend or holiday was built with.
fn close_on(by_key: &Prices, key: &str, day: NaiveDate) -> Option<rust_decimal::Decimal> {
    let from = day.checked_sub_days(Days::new(prices::MAX_FALLBACK_AGE_DAYS))?;
    by_key
        .get(key)?
        .range(from..=day)
        .next_back()
        .map(|(_, price)| *price)
}

/// How far `value` is from `invested`, as a ratio of at least one; `None` if either is not
/// positive.
fn spread(
    value: rust_decimal::Decimal,
    invested: rust_decimal::Decimal,
) -> Option<rust_decimal::Decimal> {
    if value <= rust_decimal::Decimal::ZERO || invested <= rust_decimal::Decimal::ZERO {
        return None;
    }
    let ratio = value.checked_div(invested)?;
    if ratio >= rust_decimal::Decimal::ONE {
        Some(ratio)
    } else {
        rust_decimal::Decimal::ONE.checked_div(ratio)
    }
}

async fn rewrite_account(
    state: &AppState,
    account: &crate::ledger::model::Account,
    lines: &[IsinLine],
) -> AppResult<(usize, bool)> {
    use crate::ledger::model::SnapshotSource;
    use rust_decimal::Decimal;
    let today = state.clock.today();
    let fallback = Days::new(prices::MAX_FALLBACK_AGE_DAYS);
    let past: Vec<_> = snapshots::find_by_account(&state.db, account.id)
        .await?
        .into_iter()
        .filter(|s| s.source == SnapshotSource::Derived && s.date < today)
        .collect();
    let Some(first) = past.first() else {
        return Ok((0, false));
    };
    let keys: Vec<String> = lines
        .iter()
        .flat_map(|l| [l.ticker.trim().to_uppercase(), l.isin.clone()])
        .collect();
    let from = first.date.checked_sub_days(fallback).unwrap_or(first.date);
    let mut by_key: Prices = BTreeMap::new();
    for p in prices::price_history(&state.db, &keys, from, today).await? {
        by_key
            .entry(p.ticker)
            .or_default()
            .insert(p.date, p.price_eur);
    }
    // Every past day the backfill reaches, less its first week that has nothing to fall back on,
    // needs an ISIN price: one recent hourly point is not a history.
    let covered_from = today
        .checked_sub_months(Months::new(BACKFILL_MONTHS))
        .and_then(|d| d.checked_add_days(fallback))
        .unwrap_or(today);
    let complete = past.iter().filter(|s| s.date >= covered_from).all(|s| {
        lines
            .iter()
            .all(|l| close_on(&by_key, &l.isin, s.date).is_some())
    });

    let mut updates = Vec::new();
    for snap in &past {
        let mut candidate = snap.balance;
        for line in lines {
            let (Some(wrong), Some(right)) = (
                close_on(&by_key, &line.ticker.trim().to_uppercase(), snap.date),
                close_on(&by_key, &line.isin, snap.date),
            ) else {
                continue;
            };
            let (Some(wrong_value), Some(right_value)) = (
                line.quantity.checked_mul(wrong),
                line.quantity.checked_mul(right),
            ) else {
                continue;
            };
            if valuation::quote_contradicts_provider_value(line.quantity, wrong, Some(right_value))
            {
                let Some(next) = candidate
                    .checked_sub(wrong_value)
                    .and_then(|c| c.checked_add(right_value))
                else {
                    continue;
                };
                candidate = next;
            }
        }
        if candidate == snap.balance {
            continue;
        }
        let (Some(before), Some(after)) = (
            spread(snap.balance, snap.invested_amount),
            spread(candidate, snap.invested_amount),
        ) else {
            continue;
        };
        // Only a day off its cost by the #690 factor is suspect, and only a candidate back within
        // it is taken: a day with an ordinary gain or loss is never moved, and a corrected day is
        // no longer suspect, so a rerun leaves it alone.
        if before < Decimal::TWO || after >= Decimal::TWO {
            continue;
        }
        updates.push((snap, candidate));
    }
    if updates.is_empty() {
        return Ok((0, complete));
    }
    let mut tx = state.db.begin().await?;
    for (snap, candidate) in &updates {
        snapshots::update_amounts(&mut tx, snap.id, *candidate, snap.invested_amount).await?;
    }
    tx.commit().await?;
    for (snap, candidate) in &updates {
        tracing::info!(
            "Rewrote the snapshot of account {} on {}: balance {} -> {} (#705)",
            account.id,
            snap.date,
            snap.balance,
            candidate
        );
    }
    Ok((updates.len(), complete))
}
