package com.picsou.service;

import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the cron jobs of {@link SchedulerService} from their last recorded run, so a slot missed
 * while the machine was off or asleep is made up once, at the next tick.
 *
 * <p>A job is due when the first cron slot after its last run is already past. Several missed
 * slots still give one run, and a restart right after a run gives none. Spring's own cron trigger
 * waits on a monotonic clock that stops while a laptop sleeps (Linux, macOS), so it fires hours
 * late after a wake; one wall-clock tick per minute replaces it for these jobs.
 *
 * <p>A job seen for the first time records the current time and does not run: an upgrade or a
 * fresh install waits for the next slot, as before. The run is recorded even when the job throws,
 * so a failing job waits for its next slot instead of retrying every minute.
 */
@Component
public class CatchUpScheduler {

    private static final Logger log = LoggerFactory.getLogger(CatchUpScheduler.class);
    static final String KEY_PREFIX = "scheduler.last-run.";

    public static final String DAILY_BANK_SYNC = "daily-bank-sync";

    private record Job(String name, CronExpression cron, Runnable run) {}

    private final AppSettingRepository settings;
    private final Clock clock;
    /** In order: when both are due at the same tick, the snapshot runs after the bank sync. */
    private final List<Job> jobs;
    private final Map<String, Instant> lastRuns = new ConcurrentHashMap<>();
    /** Not a ReentrantLock: a job that starts itself again from the same thread must be skipped too. */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    @Autowired
    public CatchUpScheduler(SchedulerService scheduler, AppSettingRepository settings) {
        this(scheduler, settings, Clock.systemDefaultZone());
    }

    CatchUpScheduler(SchedulerService scheduler, AppSettingRepository settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        this.jobs = List.of(
            job(DAILY_BANK_SYNC, "0 0 8 * * *", scheduler::dailyBankSync),
            job("daily-snapshots", "0 5 8 * * *", scheduler::dailySnapshots),
            job("monthly-property-valuation", "0 30 3 1 * *", scheduler::monthlyPropertyValuation),
            job("weekly-security-profiles", "0 45 3 * * SUN", scheduler::refreshSecurityProfiles),
            job("weekly-dividend-history", "0 15 4 * * SUN", scheduler::refreshDividendHistory));
    }

    private static Job job(String name, String cron, Runnable run) {
        return new Job(name, CronExpression.parse(cron), run);
    }

    /** First tick a minute after startup, so the startup sync, when enabled, records its run first. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void tick() {
        for (Job job : jobs) {
            try {
                Instant now = clock.instant();
                Instant last = lastRun(job.name(), now);
                if (last.isAfter(now)) {
                    log.warn("Last run of {} is in the future ({}) -- starting from now", job.name(), last);
                    last = record(job.name(), now);
                }
                ZonedDateTime next = job.cron().next(ZonedDateTime.ofInstant(last, clock.getZone()));
                if (next != null && !next.toInstant().isAfter(now)) {
                    log.info("Running {} (slot {}, last run {})", job.name(), next, last);
                    run(job);
                }
            } catch (Exception ex) {
                log.error("Could not check {} -- trying again at the next tick", job.name(), ex);
            }
        }
    }

    /** Runs a job now, outside its schedule, unless it is already running. Records the run. */
    public boolean runNow(String name) {
        return jobs.stream().filter(job -> job.name().equals(name)).findFirst()
            .map(this::run)
            .orElseThrow(() -> new IllegalArgumentException("Unknown scheduled job " + name));
    }

    private boolean run(Job job) {
        if (!running.add(job.name())) {
            log.info("{} is already running -- skipping", job.name());
            return false;
        }
        try {
            try {
                job.run().run();
            } catch (Exception ex) {
                log.error("{} failed -- waiting for its next slot", job.name(), ex);
            }
            record(job.name(), clock.instant());
        } finally {
            running.remove(job.name());
        }
        return true;
    }

    private Instant lastRun(String name, Instant now) {
        Instant cached = lastRuns.get(name);
        if (cached != null) {
            return cached;
        }
        Instant stored = settings.findByKey(KEY_PREFIX + name)
            .map(setting -> parse(name, setting.getValue()))
            .orElse(null);
        if (stored == null) {
            return record(name, now);
        }
        lastRuns.put(name, stored);
        return stored;
    }

    private static Instant parse(String name, String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            log.warn("Unreadable last run {} for {} -- starting from now", value, name);
            return null;
        }
    }

    /** Kept in memory first: a failed save must not make the job due again at every tick. */
    private Instant record(String name, Instant at) {
        lastRuns.put(name, at);
        try {
            AppSetting setting = settings.findByKey(KEY_PREFIX + name)
                .orElseGet(() -> AppSetting.builder().key(KEY_PREFIX + name).build());
            setting.setValue(at.toString());
            settings.save(setting);
        } catch (Exception ex) {
            log.error("Could not store the last run of {} -- kept until restart only", name, ex);
        }
        return at;
    }
}
