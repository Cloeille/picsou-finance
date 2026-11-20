package com.picsou.config;

import com.picsou.service.CatchUpScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Triggers initial sync of all accounts at application startup.
 * Runs after DataSeeder to ensure user exists before syncing. Goes through
 * {@link CatchUpScheduler} so the run is recorded and the catch-up tick does not repeat it.
 */
@Component
@Order(1) // Run after DataSeeder (which has default order 0)
public class StartupSyncService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSyncService.class);

    private final CatchUpScheduler catchUpScheduler;
    private final boolean enabled;

    public StartupSyncService(
            CatchUpScheduler catchUpScheduler,
            @Value("${app.startup-sync.enabled:true}") boolean enabled) {
        this.catchUpScheduler = catchUpScheduler;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("Initial startup sync disabled");
            return;
        }
        log.info("Starting initial sync of all accounts at startup");
        try {
            if (catchUpScheduler.runNow(CatchUpScheduler.DAILY_BANK_SYNC)) {
                log.info("Initial startup sync finished");
            } else {
                log.info("Initial startup sync skipped: the daily sync is already running");
            }
        } catch (Exception ex) {
            log.error("Initial startup sync failed", ex);
            // Don't throw — allow the application to start even if sync fails
        }
    }
}
