package com.picsou.config;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The Banque Populaire login is stricter than Bourso's on purpose: the bank can lock the account. */
class RateLimitConfigBanquePopulaireTest {

    @Test
    void theAuthBucketAllowsThreeAttemptsThenRefuses() {
        Bucket bucket = RateLimitConfig.createBanquePopulaireAuthBucket();

        assertThat(bucket.tryConsume(1)).isTrue();
        assertThat(bucket.tryConsume(1)).isTrue();
        assertThat(bucket.tryConsume(1)).isTrue();
        assertThat(bucket.tryConsume(1)).isFalse();
    }

    @Test
    void theAuthBucketRefillsOverFifteenMinutesNotOneMinute() {
        Bucket bucket = RateLimitConfig.createBanquePopulaireAuthBucket();
        bucket.tryConsume(3);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        assertThat(probe.isConsumed()).isFalse();
        Duration wait = Duration.ofNanos(probe.getNanosToWaitForRefill());
        assertThat(wait).isGreaterThan(Duration.ofMinutes(14)).isLessThanOrEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void theAuthBucketStoreIsABoundedBeanOfItsOwn() {
        Map<String, Bucket> store = new RateLimitConfig().banquePopulaireAuthBuckets();

        assertThat(store).isNotNull().isNotSameAs(new RateLimitConfig().boursoAuthBuckets());
    }

    @Test
    void theKeypadBucketIsShortLivedAndSmall() {
        Bucket bucket = RateLimitConfig.createBanquePopulaireKeypadBucket();
        int allowed = 0;
        while (bucket.tryConsume(1) && allowed < 100) {
            allowed++;
        }

        assertThat(allowed).isBetween(3, 10);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        assertThat(probe.isConsumed()).isFalse();
        assertThat(Duration.ofNanos(probe.getNanosToWaitForRefill())).isLessThanOrEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void theKeypadBucketStoreIsABoundedBeanOfItsOwn() {
        RateLimitConfig config = new RateLimitConfig();

        assertThat(config.banquePopulaireKeypadBuckets()).isNotNull()
            .isNotSameAs(config.banquePopulaireAuthBuckets())
            .isNotSameAs(config.boursoAuthBuckets());
    }
}
