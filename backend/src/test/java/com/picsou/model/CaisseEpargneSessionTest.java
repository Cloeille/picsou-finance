package com.picsou.model;

import com.picsou.port.CaisseEpargneErrorCode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaisseEpargneSessionTest {
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    private CaisseEpargneSession session() {
        return CaisseEpargneSession.create(
            FamilyMember.builder().id(7L).displayName("Owner").build(), "encrypted", NOW);
    }

    @Test
    void aNewSessionIsActiveAndIdle() {
        CaisseEpargneSession session = session();

        assertThat(session.isActive()).isTrue();
        assertThat(session.getSyncStatus()).isEqualTo(CaisseEpargneSyncStatus.IDLE);
        assertThat(session.getSessionState()).isEqualTo("encrypted");
        assertThat(session.getLastValidatedAt()).isEqualTo(NOW);
    }

    @Test
    void neverStoresCredentials() {
        // Decision D5: cookies only, never the password.
        assertThat(Arrays.stream(CaisseEpargneSession.class.getDeclaredFields()).map(Field::getName))
            .noneMatch(name -> name.toLowerCase().contains("credential") || name.toLowerCase().contains("password"));
    }

    @Test
    void happyPathQueuedRunningSuccess() {
        CaisseEpargneSession session = session();

        session.markQueued();
        assertThat(session.getSyncStatus()).isEqualTo(CaisseEpargneSyncStatus.QUEUED);
        assertThat(session.isSyncInFlight()).isTrue();

        session.markRunning(NOW);
        assertThat(session.getSyncStatus()).isEqualTo(CaisseEpargneSyncStatus.RUNNING);
        assertThat(session.getLastSyncStartedAt()).isEqualTo(NOW);

        Instant done = NOW.plusSeconds(5);
        session.markSuccessful(done);
        assertThat(session.getSyncStatus()).isEqualTo(CaisseEpargneSyncStatus.SUCCESS);
        assertThat(session.getLastSyncCompletedAt()).isEqualTo(done);
        assertThat(session.getLastValidatedAt()).isEqualTo(done);
        assertThat(session.getLastSyncError()).isNull();
        assertThat(session.isSyncInFlight()).isFalse();
    }

    @Test
    void cannotQueueTwiceOrWhileInactive() {
        CaisseEpargneSession session = session();
        session.markQueued();
        assertThatThrownBy(session::markQueued).isInstanceOf(IllegalStateException.class);

        CaisseEpargneSession expired = session();
        expired.markQueued();
        expired.markFailed(CaisseEpargneErrorCode.SESSION_EXPIRED, NOW);
        assertThatThrownBy(expired::markQueued).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runningRequiresAQueuedActiveSession() {
        assertThatThrownBy(() -> session().markRunning(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void successRequiresARunningSession() {
        CaisseEpargneSession session = session();
        session.markQueued();
        assertThatThrownBy(() -> session.markSuccessful(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureRequiresAnInFlightSync() {
        assertThatThrownBy(() -> session().markFailed(CaisseEpargneErrorCode.INTERNAL_ERROR, NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTransientFailureKeepsTheSessionUsable() {
        CaisseEpargneSession session = session();
        session.markQueued();
        session.markRunning(NOW);

        session.markFailed(CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE, NOW);

        assertThat(session.getSyncStatus()).isEqualTo(CaisseEpargneSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(session.isActive()).isTrue();
    }

    @Test
    void anExpiredOrUnusableSessionIsDeactivated() {
        for (CaisseEpargneErrorCode code : new CaisseEpargneErrorCode[] {
            CaisseEpargneErrorCode.SESSION_EXPIRED, CaisseEpargneErrorCode.INVALID_SESSION_STATE}) {
            CaisseEpargneSession session = session();
            session.markQueued();
            session.markFailed(code, NOW);

            assertThat(session.isActive()).as(code.name()).isFalse();
            assertThat(session.getLastSyncError()).isEqualTo(code);
        }
    }

    @Test
    void queueingClearsThePreviousOutcome() {
        CaisseEpargneSession session = session();
        session.markQueued();
        session.markFailed(CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED, NOW);

        session.markQueued();

        assertThat(session.getSyncStatus()).isEqualTo(CaisseEpargneSyncStatus.QUEUED);
        assertThat(session.getLastSyncError()).isNull();
        assertThat(session.getLastSyncCompletedAt()).isNull();
    }

    @Test
    void updatingTheSessionStateReactivatesIt() {
        CaisseEpargneSession session = session();
        session.markQueued();
        session.markFailed(CaisseEpargneErrorCode.SESSION_EXPIRED, NOW);

        session.updateSessionState("fresh", NOW.plusSeconds(60));

        assertThat(session.isActive()).isTrue();
        assertThat(session.getSessionState()).isEqualTo("fresh");
    }

    @Test
    void loginErrorCodesNeverDeactivateAnExistingSession() {
        for (CaisseEpargneErrorCode code : new CaisseEpargneErrorCode[] {
            CaisseEpargneErrorCode.INVALID_CREDENTIALS, CaisseEpargneErrorCode.KEYPAD_CHANGED,
            CaisseEpargneErrorCode.APP_VALIDATION_TIMEOUT, CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED}) {
            CaisseEpargneSession session = session();
            session.markQueued();
            session.markFailed(code, NOW);
            assertThat(session.isActive()).as(code.name()).isTrue();
            assertThat(session.getLastSyncError()).isEqualTo(code);
        }
    }
}
