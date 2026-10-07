package com.picsou.config;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The Caisse d'Epargne login is stricter than Bourso's on purpose: the bank can lock the account. */
class RateLimitConfigCaisseEpargneTest {

    @Test
    void theAuthBucketAllowsThreeAttemptsThenRefuses() {
        Bucket bucket = RateLimitConfig.createCaisseEpargneAuthBucket();

        assertThat(bucket.tryConsume(1)).isTrue();
        assertThat(bucket.tryConsume(1)).isTrue();
        assertThat(bucket.tryConsume(1)).isTrue();
        assertThat(bucket.tryConsume(1)).isFalse();
    }

    @Test
    void theAuthBucketRefillsOverFifteenMinutesNotOneMinute() {
        Bucket bucket = RateLimitConfig.createCaisseEpargneAuthBucket();
        bucket.tryConsume(3);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        assertThat(probe.isConsumed()).isFalse();
        Duration wait = Duration.ofNanos(probe.getNanosToWaitForRefill());
        assertThat(wait).isGreaterThan(Duration.ofMinutes(4)).isLessThanOrEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void theAuthBucketStoreIsABoundedBeanOfItsOwn() {
        Map<String, Bucket> store = new RateLimitConfig().caisseEpargneAuthBuckets();

        assertThat(store).isNotNull().isNotSameAs(new RateLimitConfig().boursoAuthBuckets());
    }
}
