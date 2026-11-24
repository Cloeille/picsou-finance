//! CatchUpScheduler: runs the five scheduled jobs of `scheduler` from their last recorded run in
//! `app_setting`, so a slot missed while the machine was off or asleep is made up once at the next
//! tick.
//!
//! Exact port of `com.picsou.service.CatchUpScheduler`:
//! - A job is due when the first cron slot after its last run is `<= now`.
//! - Several missed slots still give one run; a restart right after a run gives none.
//! - A job seen for the first time records `now` and does not run immediately.
//! - A failing job still records `now` and waits for its next slot instead of retrying every minute.
//! - `last_runs` is updated in memory before writing to `app_setting`, so a transient DB write
//!   failure does not cause minute-by-minute retries.
//! - A non-reentrant `running` guard prevents overlapping runs of the same job.

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};

use crate::db::Db;
use async_trait::async_trait;
use chrono::{
    DateTime, Datelike, Duration, LocalResult, NaiveDate, NaiveTime, Offset, TimeZone, Utc, Weekday,
};
use chrono_tz::Tz;

use super::scheduler;
use crate::AppState;
use crate::clock::AppClock;
use crate::error::AppResult;

pub const KEY_PREFIX: &str = "scheduler.last-run.";
pub const DAILY_BANK_SYNC: &str = "daily-bank-sync";
pub const DAILY_SNAPSHOTS: &str = "daily-snapshots";
pub const MONTHLY_PROPERTY_VALUATION: &str = "monthly-property-valuation";
pub const WEEKLY_SECURITY_PROFILES: &str = "weekly-security-profiles";
pub const WEEKLY_DIVIDEND_HISTORY: &str = "weekly-dividend-history";

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("Unknown scheduled job {0}")]
pub struct UnknownJob(pub String);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Schedule {
    Daily {
        hour: u32,
        minute: u32,
    },
    Monthly {
        day: u32,
        hour: u32,
        minute: u32,
    },
    Weekly {
        weekday: Weekday,
        hour: u32,
        minute: u32,
    },
}

impl Schedule {
    /// First wall-clock slot strictly after `last` in `zone`, matching Spring `CronExpression.next`.
    pub fn next_after(self, last: DateTime<Utc>, zone: Tz) -> DateTime<Utc> {
        let last_local = last.with_timezone(&zone);
        match self {
            Self::Daily { hour, minute } => {
                let time = NaiveTime::from_hms_opt(hour, minute, 0).expect("valid schedule time");
                let mut date = last_local.date_naive();
                loop {
                    let candidate = resolve_local(date, time, zone);
                    if candidate > last {
                        return candidate;
                    }
                    date = date.succ_opt().expect("next date");
                }
            }
            Self::Monthly { day, hour, minute } => {
                let time = NaiveTime::from_hms_opt(hour, minute, 0).expect("valid schedule time");
                let mut year = last_local.year();
                let mut month = last_local.month();
                loop {
                    if let Some(date) = NaiveDate::from_ymd_opt(year, month, day) {
                        let candidate = resolve_local(date, time, zone);
                        if candidate > last {
                            return candidate;
                        }
                    }
                    if month == 12 {
                        year += 1;
                        month = 1;
                    } else {
                        month += 1;
                    }
                }
            }
            Self::Weekly {
                weekday,
                hour,
                minute,
            } => {
                let time = NaiveTime::from_hms_opt(hour, minute, 0).expect("valid schedule time");
                let base = last_local.date_naive();
                let days_ahead = (i64::from(weekday.num_days_from_monday())
                    - i64::from(base.weekday().num_days_from_monday()))
                .rem_euclid(7);
                let mut date = base + Duration::days(days_ahead);
                loop {
                    let candidate = resolve_local(date, time, zone);
                    if candidate > last {
                        return candidate;
                    }
                    date += Duration::days(7);
                }
            }
        }
    }
}

/// Resolves a wall-clock `(date, time)` in `zone` the same way `java.time.ZonedDateTime.of` does:
/// ambiguous fall-back times pick the earlier offset; non-existent spring-forward times shift
/// forward by the gap duration.
fn resolve_local(date: NaiveDate, time: NaiveTime, zone: Tz) -> DateTime<Utc> {
    let naive = date.and_time(time);
    match zone.from_local_datetime(&naive) {
        LocalResult::Single(dt) => dt.with_timezone(&Utc),
        LocalResult::Ambiguous(earliest, _) => earliest.with_timezone(&Utc),
        LocalResult::None => {
            let probe = naive - Duration::hours(1);
            let pre_offset = match zone.from_local_datetime(&probe) {
                LocalResult::Single(dt) => dt.offset().fix(),
                LocalResult::Ambiguous(earliest, _) => earliest.offset().fix(),
                LocalResult::None => chrono::FixedOffset::east_opt(0).expect("zero offset"),
            };
            naive.and_utc() - Duration::seconds(i64::from(pre_offset.local_minus_utc()))
        }
    }
}

/// Persistence seam for `scheduler.last-run.*` rows in `app_setting`.
#[async_trait]
pub trait LastRunStore: Send + Sync {
    async fn load(&self, key: &str) -> AppResult<Option<String>>;
    async fn save(&self, key: &str, value: &str) -> AppResult<()>;
}

/// Default `LastRunStore` backed by PostgreSQL `app_setting`.
pub struct SqlLastRunStore(pub Db);

#[async_trait]
impl LastRunStore for SqlLastRunStore {
    async fn load(&self, key: &str) -> AppResult<Option<String>> {
        Ok(
            crate::db::query_scalar("SELECT value FROM app_setting WHERE setting_key = $1")
                .bind(key)
                .fetch_optional(&self.0)
                .await?,
        )
    }

    async fn save(&self, key: &str, value: &str) -> AppResult<()> {
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

/// Execution seam so `CatchUpScheduler` can be tested with a recording runner as well as the real
/// `scheduler` jobs.
#[async_trait]
pub trait JobRunner: Send + Sync {
    async fn run(&self, state: Option<&AppState>, job_name: &'static str) -> anyhow::Result<()>;
}

/// Runs the real `SchedulerService` jobs against `AppState`.
pub struct LiveJobRunner;

#[async_trait]
impl JobRunner for LiveJobRunner {
    async fn run(&self, state: Option<&AppState>, job_name: &'static str) -> anyhow::Result<()> {
        let Some(state) = state else {
            return Ok(());
        };
        match job_name {
            DAILY_BANK_SYNC => {
                scheduler::daily_bank_sync(state).await;
                Ok(())
            }
            DAILY_SNAPSHOTS => scheduler::daily_snapshots(state)
                .await
                .map(|_| ())
                .map_err(|e| anyhow::anyhow!("{e}")),
            MONTHLY_PROPERTY_VALUATION => scheduler::monthly_property_valuation(state)
                .await
                .map(|_| ())
                .map_err(|e| anyhow::anyhow!("{e}")),
            WEEKLY_SECURITY_PROFILES => scheduler::refresh_security_profiles(state)
                .await
                .map(|_| ())
                .map_err(|e| anyhow::anyhow!("{e}")),
            WEEKLY_DIVIDEND_HISTORY => scheduler::refresh_dividend_history(state)
                .await
                .map(|_| ())
                .map_err(|e| anyhow::anyhow!("{e}")),
            other => Err(anyhow::anyhow!("Unknown scheduled job {other}")),
        }
    }
}

struct JobDef {
    name: &'static str,
    schedule: Schedule,
}

/// In order: when both are due at the same tick, daily snapshots run after the daily bank sync.
const JOBS: &[JobDef] = &[
    JobDef {
        name: DAILY_BANK_SYNC,
        schedule: Schedule::Daily { hour: 8, minute: 0 },
    },
    JobDef {
        name: DAILY_SNAPSHOTS,
        schedule: Schedule::Daily { hour: 8, minute: 5 },
    },
    JobDef {
        name: MONTHLY_PROPERTY_VALUATION,
        schedule: Schedule::Monthly {
            day: 1,
            hour: 3,
            minute: 30,
        },
    },
    JobDef {
        name: WEEKLY_SECURITY_PROFILES,
        schedule: Schedule::Weekly {
            weekday: Weekday::Sun,
            hour: 3,
            minute: 45,
        },
    },
    JobDef {
        name: WEEKLY_DIVIDEND_HISTORY,
        schedule: Schedule::Weekly {
            weekday: Weekday::Sun,
            hour: 4,
            minute: 15,
        },
    },
];

pub struct CatchUpScheduler {
    store: Arc<dyn LastRunStore>,
    runner: Arc<dyn JobRunner>,
    clock: AppClock,
    last_runs: Mutex<HashMap<&'static str, DateTime<Utc>>>,
    /// Non-reentrant guard: a nested `run_now` for the same job skips instead of running twice.
    running: Mutex<HashSet<&'static str>>,
}

impl CatchUpScheduler {
    pub fn new(db: Db, clock: AppClock) -> Self {
        Self::with_parts(
            Arc::new(SqlLastRunStore(db)),
            Arc::new(LiveJobRunner),
            clock,
        )
    }

    pub fn with_parts(
        store: Arc<dyn LastRunStore>,
        runner: Arc<dyn JobRunner>,
        clock: AppClock,
    ) -> Self {
        Self {
            store,
            runner,
            clock,
            last_runs: Mutex::new(HashMap::new()),
            running: Mutex::new(HashSet::new()),
        }
    }

    /// Checks all five cron jobs in order and runs any whose first slot after `last_run` is `<= now`.
    pub async fn tick(&self, state: Option<&AppState>) {
        for job in JOBS {
            let now = self.clock.now();
            let mut last = match self.last_run(job.name, now).await {
                Ok(t) => t,
                Err(e) => {
                    tracing::error!(
                        "Could not check {} -- trying again at the next tick: {e}",
                        job.name
                    );
                    continue;
                }
            };
            if last > now {
                tracing::warn!(
                    "Last run of {} is in the future ({last}) -- starting from now",
                    job.name
                );
                last = self.record(job.name, now).await;
            }
            let next = job.schedule.next_after(last, self.clock.zone());
            if next <= now {
                tracing::info!(
                    "Running {} (slot {}, last run {last})",
                    job.name,
                    next.with_timezone(&self.clock.zone())
                );
                self.run_job(state, job.name).await;
            }
        }
    }

    /// Runs a job immediately outside its schedule unless it is already running. Records the run.
    pub async fn run_now(&self, state: Option<&AppState>, name: &str) -> Result<bool, UnknownJob> {
        let Some(job) = JOBS.iter().find(|j| j.name == name) else {
            return Err(UnknownJob(name.to_owned()));
        };
        Ok(self.run_job(state, job.name).await)
    }

    async fn run_job(&self, state: Option<&AppState>, name: &'static str) -> bool {
        {
            let mut running = self.running.lock().unwrap_or_else(|e| e.into_inner());
            if !running.insert(name) {
                tracing::info!("{name} is already running -- skipping");
                return false;
            }
        }
        struct RunningGuard<'a> {
            running: &'a Mutex<HashSet<&'static str>>,
            name: &'static str,
        }
        impl Drop for RunningGuard<'_> {
            fn drop(&mut self) {
                self.running
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .remove(self.name);
            }
        }
        let _guard = RunningGuard {
            running: &self.running,
            name,
        };

        if let Err(e) = self.runner.run(state, name).await {
            tracing::error!("{name} failed -- waiting for its next slot: {e}");
        }
        self.record(name, self.clock.now()).await;
        true
    }

    async fn last_run(&self, name: &'static str, now: DateTime<Utc>) -> AppResult<DateTime<Utc>> {
        if let Some(&cached) = self
            .last_runs
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(name)
        {
            return Ok(cached);
        }
        let key = format!("{KEY_PREFIX}{name}");
        let stored =
            self.store
                .load(&key)
                .await?
                .and_then(|raw| match DateTime::parse_from_rfc3339(&raw) {
                    Ok(dt) => Some(dt.with_timezone(&Utc)),
                    Err(_) => {
                        tracing::warn!("Unreadable last run {raw} for {name} -- starting from now");
                        None
                    }
                });
        match stored {
            Some(at) => {
                self.last_runs
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .insert(name, at);
                Ok(at)
            }
            None => Ok(self.record(name, now).await),
        }
    }

    /// Updates memory first so a failed DB save never causes the job to re-fire every minute.
    async fn record(&self, name: &'static str, at: DateTime<Utc>) -> DateTime<Utc> {
        self.last_runs
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(name, at);
        let key = format!("{KEY_PREFIX}{name}");
        let value = at.to_rfc3339_opts(chrono::SecondsFormat::AutoSi, true);
        if let Err(e) = self.store.save(&key, &value).await {
            tracing::error!(
                "Could not store the last run of {name} -- kept until restart only: {e}"
            );
        }
        at
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::{Clock, FixedClock};
    use chrono::NaiveDateTime;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::time::Duration as StdDuration;

    struct MemStore {
        map: Mutex<HashMap<String, String>>,
        fail_save: AtomicBool,
    }

    impl MemStore {
        fn new() -> Self {
            Self {
                map: Mutex::new(HashMap::new()),
                fail_save: AtomicBool::new(false),
            }
        }

        fn set(&self, key: &str, value: &str) {
            self.map
                .lock()
                .unwrap()
                .insert(key.to_owned(), value.to_owned());
        }

        fn get(&self, key: &str) -> Option<String> {
            self.map.lock().unwrap().get(key).cloned()
        }
    }

    #[async_trait]
    impl LastRunStore for MemStore {
        async fn load(&self, key: &str) -> AppResult<Option<String>> {
            Ok(self.map.lock().unwrap().get(key).cloned())
        }

        async fn save(&self, key: &str, value: &str) -> AppResult<()> {
            if self.fail_save.load(Ordering::Relaxed) {
                return Err(crate::error::AppError::Internal("database down".into()));
            }
            self.map
                .lock()
                .unwrap()
                .insert(key.to_owned(), value.to_owned());
            Ok(())
        }
    }

    struct RecordingRunner {
        calls: Mutex<Vec<&'static str>>,
        fail_job: Mutex<Option<&'static str>>,
    }

    impl RecordingRunner {
        fn new() -> Self {
            Self {
                calls: Mutex::new(Vec::new()),
                fail_job: Mutex::new(None),
            }
        }

        fn calls(&self) -> Vec<&'static str> {
            self.calls.lock().unwrap().clone()
        }

        fn count(&self, name: &str) -> usize {
            self.calls
                .lock()
                .unwrap()
                .iter()
                .filter(|&&n| n == name)
                .count()
        }
    }

    #[async_trait]
    impl JobRunner for RecordingRunner {
        async fn run(
            &self,
            _state: Option<&AppState>,
            job_name: &'static str,
        ) -> anyhow::Result<()> {
            self.calls.lock().unwrap().push(job_name);
            if *self.fail_job.lock().unwrap() == Some(job_name) {
                return Err(anyhow::anyhow!("bank down"));
            }
            Ok(())
        }
    }

    fn paris(local: &str) -> DateTime<Utc> {
        let naive = NaiveDateTime::parse_from_str(local, "%Y-%m-%dT%H:%M:%S").unwrap();
        chrono_tz::Europe::Paris
            .from_local_datetime(&naive)
            .earliest()
            .unwrap()
            .with_timezone(&Utc)
    }

    fn set_last(store: &MemStore, job: &str, local: &str) {
        store.set(
            &format!("{KEY_PREFIX}{job}"),
            &paris(local).to_rfc3339_opts(chrono::SecondsFormat::AutoSi, true),
        );
    }

    fn make(
        local: &str,
        store: Arc<MemStore>,
        runner: Arc<dyn JobRunner>,
    ) -> (Arc<FixedClock>, CatchUpScheduler) {
        let clock = Arc::new(FixedClock::new(paris(local)));
        let app_clock = AppClock::new(clock.clone(), chrono_tz::Europe::Paris);
        (
            clock,
            CatchUpScheduler::with_parts(store, runner, app_clock),
        )
    }

    #[tokio::test]
    async fn missed_slot_runs_once_in_order_then_waits_for_tomorrow() {
        let store = Arc::new(MemStore::new());
        let runner = Arc::new(RecordingRunner::new());
        set_last(&store, DAILY_BANK_SYNC, "2026-11-14T08:00:30");
        set_last(&store, DAILY_SNAPSHOTS, "2026-11-14T08:05:30");
        let (clock, scheduler) = make("2026-11-15T10:12:00", store, runner.clone());

        scheduler.tick(None).await;
        clock.advance(StdDuration::from_secs(60));
        scheduler.tick(None).await;

        assert_eq!(runner.calls(), vec![DAILY_BANK_SYNC, DAILY_SNAPSHOTS]);

        clock.advance(StdDuration::from_secs(21 * 3600 + 48 * 60));
        scheduler.tick(None).await;
        assert_eq!(runner.count(DAILY_BANK_SYNC), 2);
    }

    #[tokio::test]
    async fn several_missed_days_still_give_one_run() {
        let store = Arc::new(MemStore::new());
        let runner = Arc::new(RecordingRunner::new());
        set_last(&store, DAILY_BANK_SYNC, "2026-11-09T08:00:30");
        let (_, scheduler) = make("2026-11-15T19:00:00", store, runner.clone());

        scheduler.tick(None).await;
        scheduler.tick(None).await;

        assert_eq!(runner.count(DAILY_BANK_SYNC), 1);
    }

    #[tokio::test]
    async fn restart_right_after_run_does_not_run_again() {
        let store = Arc::new(MemStore::new());
        let runner = Arc::new(RecordingRunner::new());
        set_last(&store, DAILY_BANK_SYNC, "2026-11-15T08:00:30");
        let (_, scheduler) = make("2026-11-15T08:20:00", store, runner.clone());

        scheduler.tick(None).await;

        assert_eq!(runner.count(DAILY_BANK_SYNC), 0);
    }

    #[tokio::test]
    async fn job_never_seen_before_waits_for_next_slot() {
        let store = Arc::new(MemStore::new());
        let runner = Arc::new(RecordingRunner::new());
        let (clock, scheduler) = make("2026-11-15T10:00:00", store.clone(), runner.clone());

        scheduler.tick(None).await;

        assert_eq!(runner.count(DAILY_BANK_SYNC), 0);
        assert_eq!(runner.count(MONTHLY_PROPERTY_VALUATION), 0);
        assert!(
            store
                .get(&format!("{KEY_PREFIX}{DAILY_BANK_SYNC}"))
                .is_some()
        );

        clock.advance(StdDuration::from_secs(22 * 3600));
        scheduler.tick(None).await;
        assert_eq!(runner.count(DAILY_BANK_SYNC), 1);
    }

    #[tokio::test]
    async fn weekly_jobs_missed_on_sunday_run_on_monday() {
        let store = Arc::new(MemStore::new());
        let runner = Arc::new(RecordingRunner::new());
        set_last(&store, WEEKLY_SECURITY_PROFILES, "2026-11-08T03:45:30");
        set_last(&store, WEEKLY_DIVIDEND_HISTORY, "2026-11-08T04:15:30");
        let (_, scheduler) = make("2026-11-16T09:00:00", store, runner.clone());

        scheduler.tick(None).await;

        assert_eq!(runner.count(WEEKLY_SECURITY_PROFILES), 1);
        assert_eq!(runner.count(WEEKLY_DIVIDEND_HISTORY), 1);
    }

    #[tokio::test]
    async fn failing_job_is_recorded_and_waits_for_next_slot() {
        let store = Arc::new(MemStore::new());
        let runner = Arc::new(RecordingRunner::new());
        *runner.fail_job.lock().unwrap() = Some(DAILY_BANK_SYNC);
        set_last(&store, DAILY_BANK_SYNC, "2026-11-14T08:00:30");
        let (clock, scheduler) = make("2026-11-15T10:00:00", store, runner.clone());

        scheduler.tick(None).await;
        clock.advance(StdDuration::from_secs(60));
        scheduler.tick(None).await;

        assert_eq!(runner.count(DAILY_BANK_SYNC), 1);
    }

    #[tokio::test]
    async fn last_run_survives_restart() {
        let store = Arc::new(MemStore::new());
        let runner = Arc::new(RecordingRunner::new());
        set_last(&store, DAILY_BANK_SYNC, "2026-11-14T08:00:30");
        let (clock, first) = make("2026-11-15T10:00:00", store.clone(), runner.clone());
        first.tick(None).await;

        let second = CatchUpScheduler::with_parts(
            store,
            runner.clone(),
            AppClock::new(clock, chrono_tz::Europe::Paris),
        );
        second.tick(None).await;

        assert_eq!(runner.count(DAILY_BANK_SYNC), 1);
    }

    #[tokio::test]
    async fn run_now_records_run_so_tick_does_not_repeat_it() {
        let store = Arc::new(MemStore::new());
        let runner = Arc::new(RecordingRunner::new());
        set_last(&store, DAILY_BANK_SYNC, "2026-11-14T08:00:30");
        let (_, scheduler) = make("2026-11-15T10:00:00", store, runner.clone());

        assert_eq!(scheduler.run_now(None, DAILY_BANK_SYNC).await, Ok(true));
        scheduler.tick(None).await;

        assert_eq!(runner.count(DAILY_BANK_SYNC), 1);
        assert!(scheduler.run_now(None, "nope").await.is_err());
    }

    #[tokio::test]
    async fn unreadable_last_run_starts_from_now() {
        let store = Arc::new(MemStore::new());
        let runner = Arc::new(RecordingRunner::new());
        store.set(&format!("{KEY_PREFIX}{DAILY_BANK_SYNC}"), "yesterday");
        let (clock, scheduler) = make("2026-11-15T10:00:00", store.clone(), runner.clone());

        scheduler.tick(None).await;

        assert_eq!(runner.count(DAILY_BANK_SYNC), 0);
        let stored = store
            .get(&format!("{KEY_PREFIX}{DAILY_BANK_SYNC}"))
            .unwrap();
        assert_eq!(
            DateTime::parse_from_rfc3339(&stored)
                .unwrap()
                .with_timezone(&Utc),
            clock.now()
        );
    }

    #[tokio::test]
    async fn failed_save_does_not_rerun_every_tick() {
        let store = Arc::new(MemStore::new());
        let runner = Arc::new(RecordingRunner::new());
        set_last(&store, DAILY_BANK_SYNC, "2026-11-14T08:00:30");
        let (clock, scheduler) = make("2026-11-15T10:00:00", store.clone(), runner.clone());
        store.fail_save.store(true, Ordering::Relaxed);

        scheduler.tick(None).await;
        clock.advance(StdDuration::from_secs(60));
        scheduler.tick(None).await;

        assert_eq!(runner.count(DAILY_BANK_SYNC), 1);
        assert_eq!(scheduler.run_now(None, DAILY_BANK_SYNC).await, Ok(true));
    }

    #[tokio::test]
    async fn sunday_job_missed_on_fall_back_dst_night_runs_once() {
        let store = Arc::new(MemStore::new());
        let runner = Arc::new(RecordingRunner::new());
        set_last(&store, WEEKLY_SECURITY_PROFILES, "2026-10-18T03:45:30");
        let (clock, scheduler) = make("2026-10-25T02:30:00", store, runner.clone());

        scheduler.tick(None).await;
        assert_eq!(runner.count(WEEKLY_SECURITY_PROFILES), 0);

        clock.advance(StdDuration::from_secs(3 * 3600));
        scheduler.tick(None).await;
        clock.advance(StdDuration::from_secs(60));
        scheduler.tick(None).await;
        assert_eq!(runner.count(WEEKLY_SECURITY_PROFILES), 1);
    }

    #[tokio::test]
    async fn future_last_run_resets_to_now_instead_of_blocking() {
        let store = Arc::new(MemStore::new());
        let runner = Arc::new(RecordingRunner::new());
        set_last(&store, DAILY_BANK_SYNC, "2026-12-25T08:00:30");
        let (clock, scheduler) = make("2026-11-15T10:00:00", store, runner.clone());

        scheduler.tick(None).await;
        assert_eq!(runner.count(DAILY_BANK_SYNC), 0);

        clock.advance(StdDuration::from_secs(22 * 3600));
        scheduler.tick(None).await;
        assert_eq!(runner.count(DAILY_BANK_SYNC), 1);
    }

    #[tokio::test]
    async fn job_already_running_is_skipped_not_run_twice() {
        struct NestedRunner {
            scheduler: std::sync::OnceLock<Arc<CatchUpScheduler>>,
            nested_result: Mutex<Option<bool>>,
            count: Mutex<usize>,
        }

        #[async_trait]
        impl JobRunner for NestedRunner {
            async fn run(
                &self,
                _state: Option<&AppState>,
                job_name: &'static str,
            ) -> anyhow::Result<()> {
                if job_name == DAILY_BANK_SYNC {
                    *self.count.lock().unwrap() += 1;
                    let s = self.scheduler.get().unwrap();
                    let nested = s.run_now(None, DAILY_BANK_SYNC).await.unwrap();
                    *self.nested_result.lock().unwrap() = Some(nested);
                }
                Ok(())
            }
        }

        let store = Arc::new(MemStore::new());
        set_last(&store, DAILY_BANK_SYNC, "2026-11-14T08:00:30");
        let runner = Arc::new(NestedRunner {
            scheduler: std::sync::OnceLock::new(),
            nested_result: Mutex::new(None),
            count: Mutex::new(0),
        });
        let clock = Arc::new(FixedClock::new(paris("2026-11-15T10:00:00")));
        let scheduler = Arc::new(CatchUpScheduler::with_parts(
            store,
            runner.clone(),
            AppClock::new(clock, chrono_tz::Europe::Paris),
        ));
        let _ = runner.scheduler.set(scheduler.clone());

        scheduler.tick(None).await;

        assert_eq!(*runner.nested_result.lock().unwrap(), Some(false));
        assert_eq!(*runner.count.lock().unwrap(), 1);
    }
}
