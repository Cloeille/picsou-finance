package com.picsou.config;

import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitConfigAmexReloginTest {
    @Test
    void reloginBucketsStartEmptyAndAreNotSharedBetweenConfigs() {
        Map<Long, Bucket> first = new RateLimitConfig().amexReloginBuckets();
        Map<Long, Bucket> second = new RateLimitConfig().amexReloginBuckets();

        first.computeIfAbsent(7L, key -> RateLimitConfig.createAmexAuthBucket());

        assertThat(first).containsOnlyKeys(7L);
        assertThat(second).isEmpty();
    }

    @Test
    void aMemberHasTheAmexLoginBudgetOfFivePerFifteenMinutes() {
        Bucket bucket = RateLimitConfig.createAmexAuthBucket();

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(bucket.tryConsume(1)).isTrue();
        }
        assertThat(bucket.tryConsume(1)).isFalse();
    }
}
