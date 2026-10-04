package com.picsou.imports;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded, owner-scoped storage for short-lived import previews. */
public final class ImportPreviewStore<T> {

    public record Entry<T>(Object scope, T payload, Instant createdAt) {}

    private final Clock clock;
    private final Duration ttl;
    private final int capacity;
    private final Map<String, Entry<T>> entries = new HashMap<>();
    private final Map<String, Entry<T>> consumed = new HashMap<>();

    public ImportPreviewStore(Clock clock, Duration ttl, int capacity) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized String put(Object scope, T payload) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(payload, "payload");
        purgeExpired();
        if (entries.size() + consumed.size() >= capacity) {
            throw new IllegalStateException("Import preview store capacity is full");
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

    private boolean isExpired(Entry<T> entry) {
        return !entry.createdAt().plus(ttl).isAfter(clock.instant());
    }
}
