# Feature: Scheduler catch-up after a missed slot

> Last updated: 2026-11-15

## Context

The daily bank sync runs at 08:00 and the balance snapshot at 08:05. A laptop that is asleep or off
at that time, or a NAS that rebooted, used to skip the day. The desktop app (#235) makes this the
normal case, so the backend now makes up a missed slot once. Docker installs get the same behaviour
through the tick, even though the image keeps the startup sync off (`APP_STARTUP_SYNC_ENABLED=false`).

## How it works

`CatchUpScheduler` owns the cron jobs of `SchedulerService` (daily bank sync, daily snapshots,
monthly property valuation, weekly security profiles, weekly dividend history). `refreshPrices`
keeps its own hourly fixed delay.

Every minute (first tick one minute after startup), for each job in that order:

1. Read its last run from `app_setting` (`scheduler.last-run.<job>`, an ISO instant).
2. Compute the first cron slot after it, in the JVM zone that `TimeZoneConfig` sets.
3. If that slot is already past, run the job and store the time it finished as its last run, even
   when it failed. A last run in the future (clock set back) is reset to now.

### Key files

- `backend/src/main/java/com/picsou/service/CatchUpScheduler.java` — the tick, the job list, `runNow`
- `backend/src/main/java/com/picsou/service/SchedulerService.java` — the jobs themselves
- `backend/src/main/java/com/picsou/config/StartupSyncService.java` — the optional startup sync,
  now through `runNow` so it counts as the day's run

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| One wall-clock tick per minute | Spring's cron trigger waits on a monotonic clock that stops while a laptop sleeps on Linux and macOS, so it fires hours late after a wake | Keep `@Scheduled(cron)` and only add a startup check: misses the wake case |
| Last run in `app_setting` | Survives restarts, no migration, already the place for instance-wide state | A new table, ShedLock |
| One run however many slots were missed | A sync fetches everything since the last one; replaying a week of 08:00 runs adds nothing | Replay each missed slot |

## Gotchas / Pitfalls

- **First start after the upgrade does not run anything.** A job with no stored last run records
  now and waits for its next slot, so an upgrade at 10:00 does not trigger a surprise sync.
- **A failing job is still recorded.** It waits for its next slot instead of retrying every minute.
- **A job already running is skipped, not queued**, so a long startup sync and the first tick
  cannot run the bank sync twice.
- **A wake without network still counts as the day's run.** `dailyBankSync` catches failures per
  member and per source, so a sync at the first tick after a wake, before Wi-Fi is back, records the
  day and the next try is tomorrow's slot. Same as a cron run at 08:00 with the network down.
- **A failed save keeps the run in memory**, so the job is not run again at every tick; it is
  stored again at its next run.
- The ticks share Spring's single scheduler thread with `refreshPrices`, as the cron jobs did.
- **A catch-up can be followed by the regular run the same day.** The stored time is when the job
  finished, so a catch-up that ends before that day's slot (a wake at 07:58, or at 00:10 after
  missing the previous 08:00) is followed by the 08:00 run: two unattended bank logins that day.
- **One instance only.** The last run is cached in memory and never re-read, so two replicas would
  both run the jobs. The Helm chart already refuses `replicaCount > 1`.

## Tests

- `CatchUpSchedulerTest` — missed slot, several missed days, restart after a run, first start,
  weekly catch-up, failing job, failed save, job already running, DST night, last run in the
  future, persistence across restarts, `runNow`, unreadable stored value
- `StartupSyncServiceTest` — the startup sync goes through `runNow`
