package com.picsou.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Thread-pool executors for the AI categorization background job.
 *
 * <ul>
 *   <li>{@code aiJobExecutor} — drives the outer job loop; one job per member at a time
 *       with a small burst capacity.</li>
 *   <li>{@code aiInferenceExecutor} — fans out concurrent model calls within each chunk;
 *       sized for the heaviest expected concurrency (up to 16 in-flight LLM calls).</li>
 * </ul>
 */
@Configuration
public class AiExecutorConfig {

    @Bean
    public Executor aiJobExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(3);
        e.setMaxPoolSize(3);
        e.setQueueCapacity(50);
        e.setAllowCoreThreadTimeOut(true);
        e.setKeepAliveSeconds(60);
        e.setThreadNamePrefix("ai-job-");
        e.initialize();
        return e;
    }

    @Bean
    public Executor aiInferenceExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        // core=16 so the pool creates up to 16 threads on demand without waiting for the
        // queue to fill first (ThreadPoolExecutor only spawns beyond corePoolSize when the
        // queue is full, which never happens here because each chunk joins immediately).
        // allowCoreThreadTimeOut lets idle threads die after keepAlive so we don't hold
        // 16 threads permanently between jobs.
        e.setCorePoolSize(16);
        e.setMaxPoolSize(16);
        e.setQueueCapacity(256);
        e.setAllowCoreThreadTimeOut(true);
        e.setKeepAliveSeconds(60);
        e.setThreadNamePrefix("ai-infer-");
        e.initialize();
        return e;
    }
}
