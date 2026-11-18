package com.picsou.config;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One token from the caller's per-IP bucket ({@link ClientIp}), and the 429 problem a connector
 * controller answers when there is none. The buckets themselves come from {@link RateLimitConfig}.
 */
public final class PerIpRateLimit {

    private PerIpRateLimit() {}

    /** Takes one token from the caller's bucket, creating it with {@code factory} on first use. */
    public static boolean tryConsume(Map<String, Bucket> buckets, HttpServletRequest request, Supplier<Bucket> factory) {
        return bucket(buckets, request, factory).tryConsume(1);
    }

    /** Like {@link #tryConsume}, keeping the wait until the next token for a {@code Retry-After}. */
    public static ConsumptionProbe consumeProbe(Map<String, Bucket> buckets, HttpServletRequest request, Supplier<Bucket> factory) {
        return bucket(buckets, request, factory).tryConsumeAndReturnRemaining(1);
    }

    /** A 429 problem carrying {@code message}. */
    public static ResponseEntity<ProblemDetail> tooManyRequests(String message) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(problem(message));
    }

    /** A 429 problem carrying {@code message}, with a {@code Retry-After} in whole seconds (at least 1). */
    public static ResponseEntity<ProblemDetail> tooManyRequests(ConsumptionProbe probe, String message) {
        long seconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).plusMillis(999).toSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
            .body(problem(message));
    }

    private static Bucket bucket(Map<String, Bucket> buckets, HttpServletRequest request, Supplier<Bucket> factory) {
        return buckets.computeIfAbsent(ClientIp.resolve(request), key -> factory.get());
    }

    private static ProblemDetail problem(String message) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail(message);
        return detail;
    }
}
