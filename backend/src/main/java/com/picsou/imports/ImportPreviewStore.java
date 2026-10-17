package com.picsou.imports;

import com.picsou.exception.ImportPreviewCapacityException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded, owner-scoped storage for short-lived import previews.
 *
 * <p>Two caps keep it bounded without letting one owner starve the others: {@code perScope}
 * unconsumed previews per owner (the owner's oldest is evicted first) and {@code capacity}
 * entries overall (the oldest unconsumed preview of any owner is evicted). The global cap exists
 * to bound memory; the per-scope cap keeps a flooding owner from ever reaching it as long as
 * {@code owners <= capacity / perScope}. Consumed entries belong to a running import and are
 * never evicted, so {@link #restore} always works after a rollback; only when the whole store is
 * consumed entries does {@link #put} refuse, with {@link ImportPreviewCapacityException}.
 */
public final class ImportPreviewStore<T> {

    public record Entry<T>(Object scope, T payload, Instant createdAt) {}

    private final Clock clock;
    private final Duration ttl;
    private final int capacity;
    private final int perScope;
    private final Map<String, Entry<T>> entries = new HashMap<>();
    private final Map<String, Entry<T>> consumed = new HashMap<>();

    public ImportPreviewStore(Clock clock, Duration ttl, int capacity) {
        this(clock, ttl, capacity, capacity);
    }

    public ImportPreviewStore(Clock clock, Duration ttl, int capacity, int perScope) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (perScope < 1 || perScope > capacity) {
            throw new IllegalArgumentException("perScope must be between 1 and capacity");
        }
        this.capacity = capacity;
        this.perScope = perScope;
    }

    public synchronized String put(Object scope, T payload) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(payload, "payload");
        purgeExpired();
        if (countUnconsumed(scope) >= perScope) {
            evictOldestUnconsumed(scope);
        }
        if (entries.size() + consumed.size() >= capacity && !evictOldestUnconsumed(null)) {
            throw new ImportPreviewCapacityException();
        }
        String token = UUID.randomUUID().toString();
        entries.put(token, new Entry<>(scope, payload, clock.instant()));
        return token;
    }

    public synchronized Entry<T> get(String token, Object scope) {
        Entry<T> entry = entries.get(token);
        if (entry == null) {
            return null;
        }
        if (isExpired(entry)) {
            entries.remove(token);
            return null;
        }
        return Objects.equals(entry.scope(), scope) ? entry : null;
    }

    /** Atomically removes a preview from the readable set, allowing only one consumer. */
    public synchronized boolean consume(String token, Entry<T> entry) {
        if (entry == null || !Objects.equals(entries.get(token), entry)) {
            return false;
        }
        if (isExpired(entry)) {
            entries.remove(token);
            return false;
        }
        entries.remove(token);
        consumed.put(token, entry);
        return true;
    }

    /** Drops the consumed entry and its payload after the owning operation succeeds. */
    public synchronized void complete(String token, Entry<T> entry) {
        if (entry != null && Objects.equals(consumed.get(token), entry)) {
            consumed.remove(token);
        }
    }

    /** Restores a preview consumed by a failed transaction, if it has not expired. */
    public synchronized void restore(String token, Entry<T> entry) {
        if (entry == null || !Objects.equals(consumed.get(token), entry)) {
            return;
        }
        consumed.remove(token);
        if (!isExpired(entry)) {
            entries.put(token, entry);
        }
    }

    public synchronized void purgeExpired() {
        entries.entrySet().removeIf(entry -> isExpired(entry.getValue()));
        consumed.entrySet().removeIf(entry -> isExpired(entry.getValue()));
    }

    private long countUnconsumed(Object scope) {
        return entries.values().stream().filter(entry -> Objects.equals(entry.scope(), scope)).count();
    }

    /** Evicts the oldest unconsumed entry of {@code scope} (any scope when null); false if none. */
    private boolean evictOldestUnconsumed(Object scope) {
        return entries.entrySet().stream()
            .filter(entry -> scope == null || Objects.equals(entry.getValue().scope(), scope))
            .min(Comparator.comparing(entry -> entry.getValue().createdAt()))
            .map(entry -> entries.remove(entry.getKey()) != null)
            .orElse(false);
    }

    private boolean isExpired(Entry<T> entry) {
        return !entry.createdAt().plus(ttl).isAfter(clock.instant());
    }
}
