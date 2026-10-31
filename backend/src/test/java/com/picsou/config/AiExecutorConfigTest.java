package com.picsou.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard: verifies that the AI executor pool sizes match the intended concurrency.
 *
 * <p>The key invariant is that {@code corePoolSize == maxPoolSize} on the inference executor.
 * A {@link java.util.concurrent.ThreadPoolExecutor} only creates threads beyond
 * {@code corePoolSize} once the queue is full; because each job chunk submits ≤C tasks and
 * then immediately joins, the queue never fills. Setting core=max ensures up to 16 threads
 * are created on demand so {@code ai.max-concurrency} values 5..16 are actually respected.
 */
class AiExecutorConfigTest {

    private final AiExecutorConfig config = new AiExecutorConfig();

    @Test
    void aiInferenceExecutor_corePoolSizeIs16() {
        Executor executor = config.aiInferenceExecutor();
        ThreadPoolTaskExecutor tpte = (ThreadPoolTaskExecutor) executor;
        assertThat(tpte.getCorePoolSize())
            .as("inference executor corePoolSize must equal maxPoolSize so concurrency 5..16 is honoured")
            .isEqualTo(16);
    }

    @Test
    void aiInferenceExecutor_maxPoolSizeIs16() {
        Executor executor = config.aiInferenceExecutor();
        ThreadPoolTaskExecutor tpte = (ThreadPoolTaskExecutor) executor;
        assertThat(tpte.getMaxPoolSize()).isEqualTo(16);
    }

    @Test
    void aiJobExecutor_corePoolSizeIs3() {
        Executor executor = config.aiJobExecutor();
        ThreadPoolTaskExecutor tpte = (ThreadPoolTaskExecutor) executor;
        assertThat(tpte.getCorePoolSize()).isEqualTo(3);
    }

    @Test
    void aiJobExecutor_maxPoolSizeIs3() {
        Executor executor = config.aiJobExecutor();
        ThreadPoolTaskExecutor tpte = (ThreadPoolTaskExecutor) executor;
        assertThat(tpte.getMaxPoolSize()).isEqualTo(3);
    }
}
