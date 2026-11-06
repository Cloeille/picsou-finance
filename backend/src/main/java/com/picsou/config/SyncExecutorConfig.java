package com.picsou.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Thread-pool executors for the background bank-sync jobs. Each one is injected by bean name
 * ({@code @Qualifier("<bank>SyncExecutor")}).
 *
 * <p>Revolut keeps a small pool: each job may launch a heavy headful Camoufox/Xvfb browser in
 * the sidecar, so we never want more than a handful running concurrently. Every other bank gets
 * {@link #singleWorker one worker}.
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

    @Bean(name = "amexSyncExecutor")
    public Executor amexSyncExecutor() {
        return singleWorker("amex-sync-");
    }

    @Bean(name = "amundiSyncExecutor")
    public Executor amundiSyncExecutor() {
        return singleWorker("amundi-sync-");
    }

    @Bean(name = "bourseDirectSyncExecutor")
    public Executor bourseDirectSyncExecutor() {
        return singleWorker("bourse-direct-sync-");
    }

    @Bean(name = "boursoSyncExecutor")
    public Executor boursoSyncExecutor() {
        return singleWorker("bourso-sync-");
    }

    /** One worker: syncs are on demand and must never overlap on the bank's session. */
    @Bean(name = "caisseEpargneSyncExecutor")
    public Executor caisseEpargneSyncExecutor() {
        return singleWorker("caisse-epargne-sync-");
    }

    @Bean(name = "cicSyncExecutor")
    public Executor cicSyncExecutor() {
        return singleWorker("cic-sync-");
    }

    @Bean(name = "cmesSyncExecutor")
    public Executor cmesSyncExecutor() {
        return singleWorker("cmes-sync-");
    }

    /**
     * One worker: a sync reads a browser-backed sidecar, and two concurrent
     * reads for the same member would contend on the same row lock anyway.
     */
    @Bean(name = "corumSyncExecutor")
    public Executor corumSyncExecutor() {
        return singleWorker("corum-sync-");
    }

    @Bean(name = "fortuneoSyncExecutor")
    public Executor fortuneoSyncExecutor() {
        return singleWorker("fortuneo-sync-");
    }

    /**
     * One worker: a sync reads a remote portal, and two concurrent reads for
     * the same member would contend on the same row lock anyway.
     */
    @Bean(name = "sofidySyncExecutor")
    public Executor sofidySyncExecutor() {
        return singleWorker("sofidy-sync-");
    }

    private static Executor singleWorker(String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
