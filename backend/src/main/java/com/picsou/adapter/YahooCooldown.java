package com.picsou.adapter;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The pause a Yahoo 429 buys, shared by every Yahoo call site that should yield to it.
 *
 * <p>Asymmetric on purpose. The price path <em>arms</em> it but does not read it: prices are what
 * the user came for, and making them wait on a pause a logo lookup armed would let decoration eat
 * into the price budget (issue #162). The logo path both arms and reads it, so a 429 seen by
 * either one stops quote-page scraping until Yahoo has had its breather.
 *
 * <p>Same clamp as {@code CoinGeckoPriceProvider}: the server's {@code Retry-After} when sane,
 * a minute otherwise, never more than fifteen.
 */
@Component
public class YahooCooldown {

    static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(60);
    static final Duration MAX_COOLDOWN = Duration.ofMinutes(15);

    private final Clock clock;
    private volatile Instant until = Instant.EPOCH;

    public YahooCooldown(Clock clock) {
        this.clock = clock;
    }

    /** Arms the pause from a 429's headers. Never shortens a pause already in force. */
    public Duration arm(HttpHeaders headers) {
        Duration pause = retryAfter(headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER));
        Instant candidate = Instant.now(clock).plus(pause);
        synchronized (this) {
            if (candidate.isAfter(until)) until = candidate;
        }
        return pause;
    }

    public boolean active() {
        return Instant.now(clock).isBefore(until);
    }

    public Instant until() {
        return until;
    }

    /** Only the delta-seconds form, as in {@code CoinGeckoPriceProvider.retryAfter}. */
    static Duration retryAfter(String header) {
        if (header == null || header.isBlank()) return DEFAULT_COOLDOWN;
        try {
            long seconds = Long.parseLong(header.trim());
            if (seconds <= 0) return DEFAULT_COOLDOWN;
            return Duration.ofSeconds(Math.min(seconds, MAX_COOLDOWN.toSeconds()));
        } catch (NumberFormatException ex) {
            return DEFAULT_COOLDOWN;
        }
    }
}
