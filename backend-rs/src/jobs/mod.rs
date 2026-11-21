//! Schedulers, catch-up, cleanups and startup runners (track T8).

pub mod catch_up;
pub mod scheduler;
pub mod startup;

use std::sync::Arc;
use std::time::Duration;

use sqlx::PgPool;
use tokio::sync::watch;
use tokio::task::JoinSet;

use crate::AppState;
use crate::clock::AppClock;
use crate::routes::Routes;

pub fn register(_routes: &mut Routes) {}

/// Process-local scheduler state held on `AppState` as `state.jobs`.
pub struct JobsState {
    pub catch_up: Arc<catch_up::CatchUpScheduler>,
}

impl JobsState {
    pub fn new(db: PgPool, clock: AppClock) -> Self {
        Self {
            catch_up: Arc::new(catch_up::CatchUpScheduler::new(db, clock)),
        }
    }
}

/// Runs the synchronous startup runners (interrupted sync recovery, FX cleanup, ISIN repair,
/// price backfill).
pub async fn on_startup(state: &AppState) -> anyhow::Result<()> {
    startup::on_startup(state).await
}

/// Handle returned by `spawn_schedulers` so server shutdown cleanly stops all background jobs.
pub struct SchedulerHandle {
    tasks: JoinSet<()>,
}

impl SchedulerHandle {
    /// Waits up to `timeout` for every background scheduler loop to exit after the stop signal
    /// fires, then aborts any straggler so shutdown never hangs.
    pub async fn shutdown(mut self, timeout: Duration) {
        let drain = async { while self.tasks.join_next().await.is_some() {} };
        if tokio::time::timeout(timeout, drain).await.is_err() {
            self.tasks.abort_all();
            while self.tasks.join_next().await.is_some() {}
        }
    }
}

/// Spawns every scheduled background task (plus `StartupSyncService` when enabled) wired to
/// `stop`: as soon as `*stop.borrow()` becomes `true`, every loop exits cleanly.
///
/// `TestApp` never calls `run()`, so unit/HTTP integration tests never run noisy background loops
/// unless a test explicitly calls `spawn_schedulers` or the individual job functions.
pub fn spawn_schedulers(state: AppState, stop: watch::Receiver<bool>) -> SchedulerHandle {
    let mut tasks = JoinSet::new();

    // 1. StartupSyncService (fire-and-forget cancellable task when enabled).
    if state.config.startup_sync_enabled {
        let s = state.clone();
        let mut rx = stop.clone();
        tasks.spawn(async move {
            tokio::select! {
                _ = startup::run_startup_sync(&s) => {}
                _ = wait_for_stop(&mut rx) => {}
            }
        });
    }

    // 2. CatchUpScheduler.tick: initialDelay = 60 s, fixedDelay = 60 s.
    {
        let s = state.clone();
        let rx = stop.clone();
        tasks.spawn(fixed_delay_loop(
            Duration::from_secs(60),
            Duration::from_secs(60),
            rx,
            move || {
                let s = s.clone();
                async move {
                    s.jobs.catch_up.tick(Some(&s)).await;
                }
            },
        ));
    }

    // 3. SchedulerService.refreshPrices: initialDelay = 0, fixedDelay = 3600 s.
    {
        let s = state.clone();
        let rx = stop.clone();
        tasks.spawn(fixed_delay_loop(
            Duration::ZERO,
            Duration::from_secs(3600),
            rx,
            move || {
                let s = s.clone();
                async move {
                    if let Err(e) = scheduler::refresh_prices(&s).await {
                        tracing::error!("Price refresh failed -- skipping this cycle: {e}");
                    }
                }
            },
        ));
    }

    // 4. 60 s fixed-delay in-memory cleanups (import previews, Finary API previews, pending logins).
    {
        let s = state.clone();
        let rx = stop.clone();
        tasks.spawn(fixed_delay_loop(
            Duration::from_secs(60),
            Duration::from_secs(60),
            rx,
            move || {
                let s = s.clone();
                async move {
                    scheduler::run_60s_cleanups(&s);
                }
            },
        ));
    }

    // 5. 300 s fixed-delay stale Natixis sync recovery.
    {
        let s = state;
        let rx = stop;
        tasks.spawn(fixed_delay_loop(
            Duration::from_secs(300),
            Duration::from_secs(300),
            rx,
            move || {
                let s = s.clone();
                async move {
                    if let Err(e) = scheduler::run_300s_stale_recovery(&s).await {
                        tracing::error!("Natixis stale sync recovery failed: {e}");
                    }
                }
            },
        ));
    }

    SchedulerHandle { tasks }
}

async fn wait_for_stop(rx: &mut watch::Receiver<bool>) {
    if *rx.borrow() {
        return;
    }
    while rx.changed().await.is_ok() {
        if *rx.borrow() {
            return;
        }
    }
}

async fn sleep_or_stop(duration: Duration, rx: &mut watch::Receiver<bool>) -> bool {
    if *rx.borrow() {
        return false;
    }
    if duration.is_zero() {
        return true;
    }
    tokio::select! {
        () = tokio::time::sleep(duration) => !*rx.borrow(),
        () = wait_for_stop(rx) => false,
    }
}

async fn fixed_delay_loop<F, Fut>(
    initial_delay: Duration,
    delay: Duration,
    mut rx: watch::Receiver<bool>,
    mut action: F,
) where
    F: FnMut() -> Fut,
    Fut: Future<Output = ()>,
{
    if !sleep_or_stop(initial_delay, &mut rx).await {
        return;
    }
    loop {
        tokio::select! {
            () = action() => {}
            () = wait_for_stop(&mut rx) => return,
        }
        if !sleep_or_stop(delay, &mut rx).await {
            return;
        }
    }
}
