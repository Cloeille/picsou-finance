package com.picsou.config;

import com.picsou.service.SchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Triggers initial sync of all accounts at application startup.
 * Runs after DataSeeder to ensure user exists before syncing.
 */
@Component
@Order(1) // Run after DataSeeder (which has default order 0)
public class StartupSyncService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSyncService.class);

    private final SchedulerService schedulerService;
    private final boolean enabled;

    public StartupSyncService(
            SchedulerService schedulerService,
            @Value("${app.startup-sync.enabled:true}") boolean enabled) {
        this.schedulerService = schedulerService;
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
            schedulerService.dailyBankSync();
            log.info("Initial startup sync completed");
        } catch (Exception ex) {
            log.error("Initial startup sync failed", ex);
            // Don't throw — allow the application to start even if sync fails
        }
    }
}
