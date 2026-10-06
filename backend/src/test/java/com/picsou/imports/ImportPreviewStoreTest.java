package com.picsou.imports;

import com.picsou.exception.ImportPreviewCapacityException;
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
    void put_evictsTheScopesOldestPreviewInsteadOfFailingWhenPerScopeCapIsReached() {
        MutableClock clock = new MutableClock(NOW);
        ImportPreviewStore<String> store = new ImportPreviewStore<>(clock, Duration.ofMinutes(30), 8, 3);
        String oldest = store.put(10L, "p1");
        clock.advance(Duration.ofSeconds(1));
        String second = store.put(10L, "p2");
        clock.advance(Duration.ofSeconds(1));
        String third = store.put(10L, "p3");
        clock.advance(Duration.ofSeconds(1));

        String newest = store.put(10L, "p4");

        assertThat(store.get(oldest, 10L)).isNull();
        assertThat(store.get(second, 10L)).isNotNull();
        assertThat(store.get(third, 10L)).isNotNull();
        assertThat(store.get(newest, 10L)).isNotNull();
    }

    @Test
    void put_oneMemberFloodingNeverEvictsAnotherMembersPreview() {
        MutableClock clock = new MutableClock(NOW);
        // 2 members x 3 previews fits the global cap of 6, so the flood must stay inside its scope.
        ImportPreviewStore<String> store = new ImportPreviewStore<>(clock, Duration.ofMinutes(30), 6, 3);
        String victim = store.put(20L, "victim");
        clock.advance(Duration.ofSeconds(1));

        for (int i = 0; i < 50; i++) {
            store.put(10L, "flood-" + i);
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(store.get(victim, 20L)).isNotNull();
    }

    @Test
    void put_evictsTheOldestUnconsumedPreviewOfAnyScopeWhenGlobalCapIsReached() {
        MutableClock clock = new MutableClock(NOW);
        ImportPreviewStore<String> store = new ImportPreviewStore<>(clock, Duration.ofMinutes(30), 2, 2);
        String oldest = store.put(10L, "a");
        clock.advance(Duration.ofSeconds(1));
        String other = store.put(20L, "b");
        clock.advance(Duration.ofSeconds(1));

        String newest = store.put(30L, "c");

        assertThat(store.get(oldest, 10L)).isNull();
        assertThat(store.get(other, 20L)).isNotNull();
        assertThat(store.get(newest, 30L)).isNotNull();
    }

    @Test
    void put_neverEvictsConsumedPreviewsAndRestoreStillWorksAfterwards() {
        MutableClock clock = new MutableClock(NOW);
        ImportPreviewStore<String> store = new ImportPreviewStore<>(clock, Duration.ofMinutes(30), 2, 2);
        String inFlight = store.put(10L, "in-flight");
        ImportPreviewStore.Entry<String> entry = store.get(inFlight, 10L);
        assertThat(store.consume(inFlight, entry)).isTrue();
        clock.advance(Duration.ofSeconds(1));
        String other = store.put(20L, "other");
        clock.advance(Duration.ofSeconds(1));

        // Store is full (1 consumed + 1 unconsumed): the unconsumed one is evicted, the consumed one is not.
        String newest = store.put(30L, "newest");

        assertThat(store.get(other, 20L)).isNull();
        assertThat(store.get(newest, 30L)).isNotNull();
        store.restore(inFlight, entry);
        assertThat(store.get(inFlight, 10L)).isEqualTo(entry);
        assertThat(store.get(newest, 30L)).isNotNull();
    }

    @Test
    void put_throwsDedicatedExceptionWhenEveryEntryIsConsumed() {
        ImportPreviewStore<String> store = new ImportPreviewStore<>(Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(30), 1, 1);
        String token = store.put(10L, "payload");
        ImportPreviewStore.Entry<String> entry = store.get(token, 10L);
        store.consume(token, entry);

        assertThatThrownBy(() -> store.put(20L, "second"))
            .isInstanceOf(ImportPreviewCapacityException.class)
            .hasMessageContaining("try again");

        store.restore(token, entry);

        assertThat(store.get(token, 10L)).isEqualTo(entry);
    }

    @Test
    void restore_isNeverRefusedEvenWhenTheStoreFilledUpMeanwhile() {
        MutableClock clock = new MutableClock(NOW);
        ImportPreviewStore<String> store = new ImportPreviewStore<>(clock, Duration.ofMinutes(30), 2, 2);
        String inFlight = store.put(10L, "in-flight");
        ImportPreviewStore.Entry<String> entry = store.get(inFlight, 10L);
        store.consume(inFlight, entry);
        clock.advance(Duration.ofSeconds(1));
        store.put(10L, "n1");
        clock.advance(Duration.ofSeconds(1));
        store.put(10L, "n2");

        store.restore(inFlight, entry);

        assertThat(store.get(inFlight, 10L)).isEqualTo(entry);
    }

    @Test
    void constructor_rejectsPerScopeCapAboveCapacityOrBelowOne() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThatThrownBy(() -> new ImportPreviewStore<String>(clock, Duration.ofMinutes(1), 2, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImportPreviewStore<String>(clock, Duration.ofMinutes(1), 2, 3))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void consumedPreviewKeepsItsCapacityReservationUntilRestoredOrExpired() {
        ImportPreviewStore<String> store = new ImportPreviewStore<>(Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(30), 1);
        String token = store.put(10L, "payload");
        ImportPreviewStore.Entry<String> entry = store.get(token, 10L);
        store.consume(token, entry);

        assertThatThrownBy(() -> store.put(10L, "second"))
            .isInstanceOf(ImportPreviewCapacityException.class);

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
