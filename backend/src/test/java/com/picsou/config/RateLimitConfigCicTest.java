package com.picsou.config;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitConfigCicTest {
    @Test
    void passwordChecksHaveFiveAttemptsPerFifteenMinutes() {
        assertBudget(RateLimitConfig.createCicAuthBucket(), 5);
    }

    @Test
    void completionsHaveTenAttemptsPerFifteenMinutes() {
        assertBudget(RateLimitConfig.createCicCompleteBucket(), 10);
    }

    private void assertBudget(Bucket bucket, long capacity) {
        assertThat(bucket.tryConsume(capacity)).isTrue();

        ConsumptionProbe refused = bucket.tryConsumeAndReturnRemaining(1);

        assertThat(refused.isConsumed()).isFalse();
        assertThat(Duration.ofNanos(refused.getNanosToWaitForRefill()))
            .isGreaterThan(Duration.ofMinutes(14))
            .isLessThanOrEqualTo(Duration.ofMinutes(15));
    }
}
