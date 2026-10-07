package com.picsou.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Thread-pool executor for background bank-sync jobs (Revolut discovery today; Trade Republic
 * progress-only in a later increment). Kept small on purpose: each job may launch a heavy
 * headful Camoufox/Xvfb browser in the sidecar, so we never want more than a handful running
 * concurrently.
 */
@Configuration
public class SyncExecutorConfig {

    @Bean("revolutSyncExecutor")
    public ThreadPoolTaskExecutor revolutSyncExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(2);
        e.setMaxPoolSize(4);
        e.setQueueCapacity(20);
        e.setAllowCoreThreadTimeOut(true);
        e.setThreadNamePrefix("revolut-sync-");
        e.initialize();
        return e;
    }
}
