package com.picsou.config;

import com.github.benmanes.caffeine.cache.Ticker;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-IP/per-user bucket-store beans used to be plain {@code ConcurrentHashMap}s that only
 * ever grew: every distinct key -- fully attacker-controlled for the per-IP ones, see
 * {@link ClientIp} -- added an entry nothing ever removed. This asserts the Caffeine-backed
 * replacement actually bounds memory. See docs/features/security-cors-cookies.md
 * ("client IP trust") and {@link RateLimitConfig#loginBuckets()}.
 *
 * <p>{@code loginBuckets} (String keys) and {@code mcpKeyBuckets} (Long keys) are exercised;
 * every other bucket-store bean is the same one-line delegation to the private
 * {@code boundedBucketStore()} factory, so these two cover all of them — including
 * {@code accessKeyCreateBuckets}, which shares {@code mcpKeyBuckets}'s {@code Map<Long, Bucket>}
 * shape.
 */
class RateLimitConfigTest {

    private static final int MAX_SIZE = 50_000;

    @Test
    void mcpMemberSyncBuckets_survive61MinutesAndRejectFifthConsumptionBefore24Hours() {
        AtomicLong nanos = new AtomicLong();
        Ticker ticker = nanos::get;
        TimeMeter timeMeter = new TimeMeter() {
            @Override
            public long currentTimeNanos() {
                return nanos.get();
            }

            @Override
            public boolean isWallClockBased() {
                return false;
            }
        };
        Map<Long, Bucket> buckets = RateLimitConfig.mcpMemberSyncBucketStore(ticker);
        Bucket bucket = buckets.computeIfAbsent(42L, ignored ->
            RateLimitConfig.createMcpMemberSyncBucket(timeMeter));

        for (int consumption = 0; consumption < 4; consumption++) {
            assertThat(buckets.get(42L)).isSameAs(bucket);
            assertThat(bucket.tryConsume(1)).isTrue();
            nanos.addAndGet(Duration.ofMinutes(61).toNanos());
        }

        assertThat(buckets.get(42L)).isSameAs(bucket);
        assertThat(bucket.tryConsume(1)).isFalse();
    }

    @Test
    void mcpMemberSyncBuckets_expireAfter24HoursAndOrdinaryStoresKeepOneHourAccessTtl() {
        AtomicLong nanos = new AtomicLong();
        Ticker ticker = nanos::get;
        Map<Long, Bucket> mcpBuckets = RateLimitConfig.mcpMemberSyncBucketStore(ticker);
        Bucket bucket = mcpBuckets.computeIfAbsent(42L, ignored ->
            RateLimitConfig.createMcpMemberSyncBucket());
        Map<String, Bucket> ordinaryBuckets = RateLimitConfig.boundedBucketStore(ticker);
        ordinaryBuckets.put("ip", RateLimitConfig.createLoginBucket());

        nanos.addAndGet(Duration.ofMinutes(61).toNanos());
        assertThat(mcpBuckets.get(42L)).isSameAs(bucket);
        assertThat(ordinaryBuckets.get("ip")).isNull();

        nanos.addAndGet(Duration.ofHours(24).minus(Duration.ofMinutes(61)).toNanos());
        assertThat(mcpBuckets.get(42L)).isNull();
    }

    @Test
    void loginBuckets_evictsDownToMaximumSize_whenOverfilled() {
        Map<String, Bucket> buckets = new RateLimitConfig().loginBuckets();

        for (int i = 0; i < MAX_SIZE + 10_000; i++) {
            buckets.computeIfAbsent("key-" + i, k -> RateLimitConfig.createLoginBucket());
        }

        awaitEvictionBelowMax(buckets);
        assertThat(buckets.size()).isLessThanOrEqualTo(MAX_SIZE);
    }

    /** Long-keyed variant: the MCP / access-key stores must be bounded like the per-IP ones. */
    @Test
    void mcpKeyBuckets_evictsDownToMaximumSize_whenOverfilled() {
        Map<Long, Bucket> buckets = new RateLimitConfig().mcpKeyBuckets();

        for (long i = 0; i < MAX_SIZE + 10_000; i++) {
            buckets.computeIfAbsent(i, k -> RateLimitConfig.createMcpKeyBucket());
        }

        awaitEvictionBelowMax(buckets);
        assertThat(buckets.size()).isLessThanOrEqualTo(MAX_SIZE);
    }

    /**
     * Caffeine's eviction maintenance can run on a background executor (ForkJoinPool
     * commonPool by default), so give it a short, bounded window to catch up rather than
     * asserting immediately after the write burst -- avoids both a flaky immediate check
     * and an unbounded hang if something is genuinely wrong.
     */
    private static void awaitEvictionBelowMax(Map<?, Bucket> buckets) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (buckets.size() > MAX_SIZE && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
