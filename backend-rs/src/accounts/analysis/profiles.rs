//! SecurityProfileService.load (the durable cache of what each security is, read only) and
//! SecurityProfileRefreshRunner (the on-demand refresh, off the request).

use std::collections::{BTreeSet, HashMap};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use rust_decimal::Decimal;
use sqlx::{PgConnection, PgPool};

use super::seams::SecurityProfileRefresher;
use crate::ledger::holdings;

/// SecurityProfileStatus.NEVER_FETCHED: a row a sync seeded from an ISIN, never looked up.
const NEVER_FETCHED: &str = "NEVER_FETCHED";

/// One weighted line of a look-through (SecurityCompositionSlice).
#[derive(Debug, Clone, PartialEq)]
pub struct ProfileSlice {
    pub kind: String,
    pub label: String,
    pub percent: Decimal,
}

/// The fields of a security_profile row the analysis reads.
#[derive(Debug, Clone, PartialEq)]
pub struct SecurityProfile {
    pub status: String,
    pub sector_key: Option<String>,
    pub country_key: Option<String>,
    pub slices: Vec<ProfileSlice>,
}

impl SecurityProfile {
    /// Whether a provider was asked at all: a seeded row is not a lookup.
    pub fn was_looked_up(&self) -> bool {
        self.status != NEVER_FETCHED
    }
}

/// load: the persisted profiles of the tickers, keyed upper-case. Never fetches.
pub async fn load(
    conn: &mut PgConnection,
    tickers: impl IntoIterator<Item = &str>,
) -> sqlx::Result<HashMap<String, SecurityProfile>> {
    let upper: BTreeSet<String> = tickers.into_iter().map(str::to_uppercase).collect();
    if upper.is_empty() {
        return Ok(HashMap::new());
    }
    let keys: Vec<&str> = upper.iter().map(String::as_str).collect();
    type ProfileRow = (i64, String, String, Option<String>, Option<String>);
    let rows: Vec<ProfileRow> = sqlx::query_as(
        "SELECT id, ticker, status, sector_key, country_key FROM security_profile WHERE ticker = ANY($1)",
    )
    .bind(&keys)
    .fetch_all(&mut *conn)
    .await?;
    let ids: Vec<i64> = rows.iter().map(|r| r.0).collect();
    let mut slices: HashMap<i64, Vec<ProfileSlice>> = HashMap::new();
    let slice_rows: Vec<(i64, String, String, Decimal)> = sqlx::query_as(
        "SELECT profile_id, kind, label, percent FROM security_composition_slice \
         WHERE profile_id = ANY($1) ORDER BY id",
    )
    .bind(&ids)
    .fetch_all(&mut *conn)
    .await?;
    for (profile_id, kind, label, percent) in slice_rows {
        slices.entry(profile_id).or_default().push(ProfileSlice {
            kind,
            label,
            percent,
        });
    }
    Ok(rows
        .into_iter()
        .map(|(id, ticker, status, sector_key, country_key)| {
            (
                ticker.to_uppercase(),
                SecurityProfile {
                    status,
                    sector_key,
                    country_key,
                    slices: slices.remove(&id).unwrap_or_default(),
                },
            )
        })
        .collect())
}

/// Same cap as the scheduled pass: the sources are rate-limited.
const BATCH: usize = 40;

/// What trigger answers: the tickers the pass will consider, or that one was already running.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Trigger {
    Queued(usize),
    AlreadyRunning,
}

/// One pass at a time: a second call while one runs is reported, never queued.
pub struct RefreshRunner {
    running: Arc<AtomicBool>,
    refresher: Arc<dyn SecurityProfileRefresher>,
}

impl RefreshRunner {
    pub fn new(refresher: Arc<dyn SecurityProfileRefresher>) -> Self {
        Self {
            running: Arc::new(AtomicBool::new(false)),
            refresher,
        }
    }

    /// Queues a pass over every distinct held ticker of the instance (profiles are global).
    pub async fn trigger(&self, db: &PgPool) -> sqlx::Result<Trigger> {
        if self
            .running
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
        {
            return Ok(Trigger::AlreadyRunning);
        }
        let tickers: BTreeSet<String> = match holdings::distinct_tickers(db, None).await {
            Ok(tickers) => tickers.into_iter().collect(),
            Err(e) => {
                self.running.store(false, Ordering::Release);
                return Err(e);
            }
        };
        if tickers.is_empty() {
            self.running.store(false, Ordering::Release);
            return Ok(Trigger::Queued(0));
        }
        let count = tickers.len();
        let running = self.running.clone();
        let refresher = self.refresher.clone();
        let db = db.clone();
        tokio::spawn(async move {
            let refreshed = refresher.refresh_stale(&db, &tickers, BATCH).await;
            tracing::info!(
                "Security profiles: on-demand refresh updated {refreshed} of {} tickers",
                tickers.len()
            );
            running.store(false, Ordering::Release);
        });
        Ok(Trigger::Queued(count))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::accounts::analysis::seams::NoProfileProviders;

    #[tokio::test]
    async fn a_call_while_a_pass_runs_is_reported_rather_than_queued() {
        let runner = RefreshRunner::new(Arc::new(NoProfileProviders));
        runner.running.store(true, Ordering::Release);
        // The guard answers before any query: the pool is never connected.
        let db = PgPool::connect_lazy("postgres://picsou@127.0.0.1:1/none").expect("lazy pool");
        assert_eq!(runner.trigger(&db).await.unwrap(), Trigger::AlreadyRunning);
        assert!(
            runner.running.load(Ordering::Acquire),
            "the running pass keeps its guard"
        );
    }
}
