package com.picsou.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

@Configuration
public class RateLimitConfig {

    /**
     * Idle buckets refill to full anyway (Bucket4j resets state on the bucket's own refill
     * schedule), so forgetting one after an hour of inactivity changes nothing a client would
     * observe — it only reclaims memory from IPs/users that stopped calling.
     */
    private static final Duration BUCKET_STORE_EXPIRE_AFTER_ACCESS = Duration.ofHours(1);

    /**
     * Ceiling on distinct keys held at once per store. 50k covers a large multiple of any
     * realistic concurrent client population for a self-hosted single-/family app; beyond that,
     * Caffeine evicts least-recently-used entries rather than growing without bound.
     */
    private static final long BUCKET_STORE_MAX_SIZE = 50_000;

    /**
     * Bounded, self-evicting replacement for {@code new ConcurrentHashMap<>()}: an unbounded map
     * of per-key Bucket4j buckets never shrinks, so a key an attacker fully controls (e.g. an
     * unauthenticated caller's IP) is an unbounded memory-growth vector. Caffeine's
     * {@code asMap()} view is a drop-in {@code Map<String, Bucket>} — same {@code computeIfAbsent}
     * call sites, no behavior change for any key still within its idle window.
     */
    private static <K> Map<K, Bucket> boundedBucketStore() {
        return Caffeine.newBuilder()
            .expireAfterAccess(BUCKET_STORE_EXPIRE_AFTER_ACCESS)
            .maximumSize(BUCKET_STORE_MAX_SIZE)
            .<K, Bucket>build()
            .asMap();
    }

    /**
     * Per-IP login rate limiter: 5 attempts per 15 minutes.
     * Bounded Caffeine-backed map of Bucket4j buckets keyed by IP address (see ClientIp).
     */
    @Bean("loginBuckets")
    public Map<String, Bucket> loginBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-IP sync rate limiter: 10 requests per minute.
     */
    @Bean("syncBuckets")
    public Map<String, Bucket> syncBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-IP TR auth rate limiter: 3 attempts per 10 minutes.
     * Strict because each attempt sends an SMS.
     */
    @Bean("trAuthBuckets")
    public Map<String, Bucket> trAuthBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-IP BoursoBank auth rate limiter: 5 attempts per 15 minutes.
     */
    @Bean("boursoAuthBuckets")
    public Map<String, Bucket> boursoAuthBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-IP Revolut enrolment rate limiter: 5 attempts per 15 minutes.
     * The sidecar's own login is already rate-limited by Revolut itself (spec §3.5: repeated
     * WEB logins trigger captchas/throttling within hours), but this endpoint just receives a
     * storageState blob -- still worth bounding against abuse of the encryption/DB write path.
     */
    @Bean("revolutAuthBuckets")
    public Map<String, Bucket> revolutAuthBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-IP Bourse Direct auth rate limiter: 5 attempts per 15 minutes.
     */
    @Bean("bourseDirectAuthBuckets")
    public Map<String, Bucket> bourseDirectAuthBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-IP DEGIRO auth rate limiter: 5 attempts per 15 minutes.
     */
    @Bean("degiroAuthBuckets")
    public Map<String, Bucket> degiroAuthBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-IP Amundi auth rate limiter: 5 attempts per 15 minutes.
     */
    @Bean("amundiAuthBuckets")
    public Map<String, Bucket> amundiAuthBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-member address autocomplete limiter.
     *
     * <p>This endpoint proxies IGN's Géoplateforme, which is free but published at 50 req/s
     * per IP for the whole instance. Because it fires as the user types, a debounce in the
     * browser is not a control — a stuck key or a scripted client would spend the shared
     * budget for every other member. The server-side cap is what actually protects it.
     */
    @Bean("geocodeBuckets")
    public Map<String, Bucket> geocodeBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-IP IBKR Flex sync rate limiter: 6 requests per minute.
     * IBKR itself enforces a separate per-token limit (~1 request/sec) on the Flex Web
     * Service; this application-level per-IP cap is additive and defensive — it keeps one
     * caller from burning the shared token's budget, while leaving room for the
     * SendRequest + several GetStatement polls a single sync fans out into.
     */
    @Bean("ibkrSyncBuckets")
    public Map<String, Bucket> ibkrSyncBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-IP setup wizard rate limiter: 10 mutating requests per minute.
     * Tight because the endpoints are unauthenticated until setup completes
     * — without this, a fresh install is exposed to admin-seeding floods
     * from anyone who can reach port 8080 before the legitimate operator.
     */
    @Bean("setupBuckets")
    public Map<String, Bucket> setupBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-IP MFA verify rate limiter: 5 attempts per 15 minutes.
     * The 6-digit TOTP space is only 1M; without throttling an attacker with
     * a stolen mfa_challenge cookie could brute-force in under a minute.
     */
    @Bean("mfaVerifyBuckets")
    public Map<String, Bucket> mfaVerifyBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-IP MFA enrollment rate limiter: 10 requests per hour.
     * QR generation is CPU-bound (PNG encoding) and enrollment is per-user
     * one-time — anything beyond a handful per hour from the same IP is abuse.
     */
    @Bean("mfaEnrollBuckets")
    public Map<String, Bucket> mfaEnrollBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-user GDPR data export rate limiter: 5 exports per hour.
     * Each export streams the user's full graph (accounts, holdings,
     * transactions, ...) — expensive to build and to ship over the wire,
     * and a successful re-auth shouldn't unlock unbounded dumps. Bounded
     * store here too: same unbounded-ConcurrentHashMap shape as the
     * per-IP stores above, just keyed by user id instead of IP.
     */
    @Bean("exportBuckets")
    public Map<String, Bucket> exportBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-user account-spreadsheet rate limiter: 20 workbooks per hour.
     *
     * <p>Looser than the GDPR bucket above because this export is a subset the user picks and is
     * meant to be used iteratively (adjust the selection, export again) rather than once a year,
     * and it is not gated behind a re-authentication. Still bounded: building a workbook prices
     * every holding and computes every amortization schedule in the selection.
     */
    @Bean("accountExportBuckets")
    public Map<String, Bucket> accountExportBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-IP merchant-logo proxy rate limiter: 60 requests per minute.
     * Generous because a single page can render many avatars, but bounded so the
     * opt-in proxy can't be turned into an open relay against the upstream icon
     * service. Cache hits still count — the bucket protects the network egress, not
     * just the cache.
     */
    @Bean("logoBuckets")
    public Map<String, Bucket> logoBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-key MCP rate limiter: keyed by access-key id (not IP), since one key may serve many
     * tool calls from a single AI client. Lives in the {@code AccessKeyAuthFilter}, which creates
     * a bucket lazily on first use — only after the key has resolved to a valid one, so the key
     * space is server-assigned, not attacker-mintable. Bounded anyway: uniformity is cheaper to
     * audit than a per-store judgment call about whose keys are "safe" to keep forever.
     */
    @Bean("mcpKeyBuckets")
    public Map<Long, Bucket> mcpKeyBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-member access-key creation limiter: keyed by member id (not IP), because the
     * {@code POST /api/access-keys} endpoint is cookie-authenticated and self-service — the member is
     * the correct abuse boundary, so an attacker with a stolen session can't mint keys faster than this
     * regardless of IP rotation. Member ids are server-assigned (bounded by the family's size), but
     * the store is bounded like every other for uniformity.
     */
    @Bean("accessKeyCreateBuckets")
    public Map<Long, Bucket> accessKeyCreateBuckets() {
        return boundedBucketStore();
    }

    /**
     * Per-IP OAuth2 Dynamic Client Registration limiter: 10 registrations per 15 minutes.
     * {@code POST /oauth2/register} is unauthenticated by design (RFC 7591) so a remote-MCP client
     * can self-register before login — that openness makes IP throttling the only guard against a
     * flood of throwaway {@code oauth2_registered_client} rows.
     */
    @Bean("oauthRegisterBuckets")
    public Map<String, Bucket> oauthRegisterBuckets() {
        return boundedBucketStore();
    }

    public static Bucket createLoginBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(5)
                .refillIntervally(5, Duration.ofMinutes(15))
                .build())
            .build();
    }

    public static Bucket createSyncBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(10)
                .refillIntervally(10, Duration.ofMinutes(1))
                .build())
            .build();
    }

    public static Bucket createTrAuthBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(3)
                .refillIntervally(3, Duration.ofMinutes(10))
                .build())
            .build();
    }

    public static Bucket createBoursoAuthBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(5)
                .refillIntervally(5, Duration.ofMinutes(15))
                .build())
            .build();
    }

    public static Bucket createRevolutAuthBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(5)
                .refillIntervally(5, Duration.ofMinutes(15))
                .build())
            .build();
    }

    public static Bucket createBourseDirectAuthBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(5)
                .refillIntervally(5, Duration.ofMinutes(15))
                .build())
            .build();
    }

    public static Bucket createDegiroAuthBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(5)
                .refillIntervally(5, Duration.ofMinutes(15))
                .build())
            .build();
    }

    public static Bucket createAmundiAuthBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(5)
                .refillIntervally(5, Duration.ofMinutes(15))
                .build())
            .build();
    }

    public static Bucket createIbkrSyncBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(6)
                .refillIntervally(6, Duration.ofMinutes(1))
                .build())
            .build();
    }

    public static Bucket createSetupBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(10)
                .refillIntervally(10, Duration.ofMinutes(1))
                .build())
            .build();
    }

    public static Bucket createMfaVerifyBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(5)
                .refillIntervally(5, Duration.ofMinutes(15))
                .build())
            .build();
    }

    public static Bucket createMfaEnrollBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(10)
                .refillIntervally(10, Duration.ofMinutes(60))
                .build())
            .build();
    }

    /**
     * Address autocomplete: 60 lookups/minute, enough for continuous typing on a couple of
     * address fields, far below what would trouble the upstream service.
     */
    public static Bucket createGeocodeBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(60)
                .refillIntervally(60, Duration.ofMinutes(1))
                .build())
            .build();
    }

    public static Bucket createExportBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(5)
                .refillIntervally(5, Duration.ofMinutes(60))
                .build())
            .build();
    }

    public static Bucket createAccountExportBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(20)
                .refillIntervally(20, Duration.ofMinutes(60))
                .build())
            .build();
    }

    public static Bucket createLogoBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(60)
                .refillIntervally(60, Duration.ofMinutes(1))
                .build())
            .build();
    }

    /**
     * Per-key MCP throttle: 120 requests per minute. Generous enough for an interactive AI client
     * (each user turn can fan out into several tool calls) while capping a runaway or hostile key.
     */
    public static Bucket createMcpKeyBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(120)
                .refillIntervally(120, Duration.ofMinutes(1))
                .build())
            .build();
    }

    /**
     * Per-member access-key creation throttle: 10 new keys per hour. Minting a key is a deliberate,
     * infrequent action; anything past a handful an hour from one member is a runaway script or a
     * hijacked session, so cap it without hindering normal use.
     */
    public static Bucket createAccessKeyCreateBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(10)
                .refillIntervally(10, Duration.ofMinutes(60))
                .build())
            .build();
    }

    /**
     * Per-IP DCR throttle: 10 client registrations per 15 minutes. Generous enough that a legitimate
     * operator re-registering a few MCP clients (or retrying after a fixed request) never gets
     * blocked, while bounding an anonymous flood against the open {@code POST /oauth2/register}
     * endpoint.
     */
    public static Bucket createOauthRegisterBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(10)
                .refillIntervally(10, Duration.ofMinutes(15))
                .build())
            .build();
    }
}
