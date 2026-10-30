package com.picsou.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * The Sunday dividend pass. Spring's scheduler is a single thread; this pool keeps that
 * thread free for the hourly price job while Yahoo and Nasdaq answer. One thread is enough:
 * the pass is already paced and capped, and a second one would only double the chart calls.
 */
@Configuration
public class DividendRefreshExecutorConfig {

    @Bean("dividendRefreshExecutor")
    public Executor dividendRefreshExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("dividend-refresh-");
        executor.initialize();
        return executor;
    }
}
