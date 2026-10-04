package com.picsou.imports;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportPreviewStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

    @Test
    void get_bindsPreviewToOwner() {
        ImportPreviewStore<String> store = new ImportPreviewStore<>(Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(30), 2);
        String token = store.put(10L, "payload");

        assertThat(store.get(token, 10L)).isNotNull();
        assertThat(store.get(token, 11L)).isNull();
    }

    @Test
    void get_expiresPreviewAtThirtyMinutes() {
        MutableClock clock = new MutableClock(NOW);
        ImportPreviewStore<String> store = new ImportPreviewStore<>(clock, Duration.ofMinutes(30), 2);
        String token = store.put(10L, "payload");

        clock.advance(Duration.ofMinutes(30));

        assertThat(store.get(token, 10L)).isNull();
    }

    @Test
    void consume_isSingleUseAndRestoreMakesItAvailableAgain() {
        ImportPreviewStore<String> store = new ImportPreviewStore<>(Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(30), 2);
        String token = store.put(10L, "payload");
        ImportPreviewStore.Entry<String> entry = store.get(token, 10L);

        assertThat(store.consume(token, entry)).isTrue();
        assertThat(store.consume(token, entry)).isFalse();
        assertThat(store.get(token, 10L)).isNull();

        store.restore(token, entry);

        assertThat(store.get(token, 10L)).isEqualTo(entry);
    }

    @Test
    void consume_rejectsPreviewThatExpiresAfterGet() {
        MutableClock clock = new MutableClock(NOW);
        ImportPreviewStore<String> store = new ImportPreviewStore<>(clock, Duration.ofMinutes(30), 1);
        String token = store.put(10L, "payload");
        ImportPreviewStore.Entry<String> entry = store.get(token, 10L);

        clock.advance(Duration.ofMinutes(30));

        assertThat(store.consume(token, entry)).isFalse();
        assertThat(store.put(10L, "replacement")).isNotBlank();
    }

    @Test
    void put_rejectsWhenCapacityIsFull() {
        ImportPreviewStore<String> store = new ImportPreviewStore<>(Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(30), 1);
        store.put(10L, "payload");

        assertThatThrownBy(() -> store.put(10L, "second"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("capacity");
    }

    @Test
    void consumedPreviewKeepsItsCapacityReservationUntilRestoredOrExpired() {
        ImportPreviewStore<String> store = new ImportPreviewStore<>(Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(30), 1);
        String token = store.put(10L, "payload");
        ImportPreviewStore.Entry<String> entry = store.get(token, 10L);
        store.consume(token, entry);

        assertThatThrownBy(() -> store.put(10L, "second"))
            .isInstanceOf(IllegalStateException.class);

        store.restore(token, entry);

        assertThat(store.get(token, 10L)).isEqualTo(entry);
    }

    @Test
    void complete_releasesReservationWithoutMakingPreviewReadableOrRestorable() {
        ImportPreviewStore<String> store = new ImportPreviewStore<>(Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(30), 1);
        String token = store.put(10L, "payload");
        ImportPreviewStore.Entry<String> entry = store.get(token, 10L);
        store.consume(token, entry);

        store.complete(token, entry);
        store.restore(token, entry);

        assertThat(store.get(token, 10L)).isNull();
        assertThat(store.put(10L, "replacement")).isNotBlank();
    }

    @Test
    void purgeExpired_removesExpiredPreviews() {
        MutableClock clock = new MutableClock(NOW);
        ImportPreviewStore<String> store = new ImportPreviewStore<>(clock, Duration.ofMinutes(30), 2);
        String token = store.put(10L, "payload");
        clock.advance(Duration.ofMinutes(31));

        store.purgeExpired();

        assertThat(store.get(token, 10L)).isNull();
        assertThat(store.put(10L, "replacement")).isNotBlank();
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        private void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
