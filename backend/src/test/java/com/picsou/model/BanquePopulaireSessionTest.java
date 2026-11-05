package com.picsou.model;

import com.picsou.port.BanquePopulaireErrorCode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BanquePopulaireSessionTest {
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    private BanquePopulaireSession session() {
        return BanquePopulaireSession.create(
            FamilyMember.builder().id(7L).displayName("Owner").build(), "encrypted", NOW);
    }

    @Test
    void aNewSessionIsActiveAndIdle() {
        BanquePopulaireSession session = session();

        assertThat(session.isActive()).isTrue();
        assertThat(session.getSyncStatus()).isEqualTo(BanquePopulaireSyncStatus.IDLE);
        assertThat(session.getSessionState()).isEqualTo("encrypted");
        assertThat(session.getLastValidatedAt()).isEqualTo(NOW);
    }

    @Test
    void neverStoresCredentials() {
        // Decision D5: cookies only, never the password.
        assertThat(Arrays.stream(BanquePopulaireSession.class.getDeclaredFields()).map(Field::getName))
            .noneMatch(name -> name.toLowerCase().contains("credential") || name.toLowerCase().contains("password"));
    }

    @Test
    void happyPathQueuedRunningSuccess() {
        BanquePopulaireSession session = session();

        session.markQueued();
        assertThat(session.getSyncStatus()).isEqualTo(BanquePopulaireSyncStatus.QUEUED);
        assertThat(session.isSyncInFlight()).isTrue();

        session.markRunning(NOW);
        assertThat(session.getSyncStatus()).isEqualTo(BanquePopulaireSyncStatus.RUNNING);
        assertThat(session.getLastSyncStartedAt()).isEqualTo(NOW);

        Instant done = NOW.plusSeconds(5);
        session.markSuccessful(done);
        assertThat(session.getSyncStatus()).isEqualTo(BanquePopulaireSyncStatus.SUCCESS);
        assertThat(session.getLastSyncCompletedAt()).isEqualTo(done);
        assertThat(session.getLastValidatedAt()).isEqualTo(done);
        assertThat(session.getLastSyncError()).isNull();
        assertThat(session.isSyncInFlight()).isFalse();
    }

    @Test
    void cannotQueueTwiceOrWhileInactive() {
        BanquePopulaireSession session = session();
        session.markQueued();
        assertThatThrownBy(session::markQueued).isInstanceOf(IllegalStateException.class);

        BanquePopulaireSession expired = session();
        expired.markQueued();
        expired.markFailed(BanquePopulaireErrorCode.SESSION_EXPIRED, NOW);
        assertThatThrownBy(expired::markQueued).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runningRequiresAQueuedActiveSession() {
        assertThatThrownBy(() -> session().markRunning(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void successRequiresARunningSession() {
        BanquePopulaireSession session = session();
        session.markQueued();
        assertThatThrownBy(() -> session.markSuccessful(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureRequiresAnInFlightSync() {
        assertThatThrownBy(() -> session().markFailed(BanquePopulaireErrorCode.INTERNAL_ERROR, NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTransientFailureKeepsTheSessionUsable() {
        BanquePopulaireSession session = session();
        session.markQueued();
        session.markRunning(NOW);

        session.markFailed(BanquePopulaireErrorCode.UPSTREAM_UNAVAILABLE, NOW);

        assertThat(session.getSyncStatus()).isEqualTo(BanquePopulaireSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(BanquePopulaireErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(session.isActive()).isTrue();
    }

    @Test
    void anExpiredOrUnusableSessionIsDeactivated() {
        for (BanquePopulaireErrorCode code : new BanquePopulaireErrorCode[] {
            BanquePopulaireErrorCode.SESSION_EXPIRED, BanquePopulaireErrorCode.INVALID_SESSION_STATE}) {
            BanquePopulaireSession session = session();
            session.markQueued();
            session.markFailed(code, NOW);

            assertThat(session.isActive()).as(code.name()).isFalse();
            assertThat(session.getLastSyncError()).isEqualTo(code);
        }
    }

    @Test
    void queueingClearsThePreviousOutcome() {
        BanquePopulaireSession session = session();
        session.markQueued();
        session.markFailed(BanquePopulaireErrorCode.UPSTREAM_FORMAT_CHANGED, NOW);

        session.markQueued();

        assertThat(session.getSyncStatus()).isEqualTo(BanquePopulaireSyncStatus.QUEUED);
        assertThat(session.getLastSyncError()).isNull();
        assertThat(session.getLastSyncCompletedAt()).isNull();
    }

    @Test
    void updatingTheSessionStateReactivatesIt() {
        BanquePopulaireSession session = session();
        session.markQueued();
        session.markFailed(BanquePopulaireErrorCode.SESSION_EXPIRED, NOW);

        session.updateSessionState("fresh", NOW.plusSeconds(60));

        assertThat(session.isActive()).isTrue();
        assertThat(session.getSessionState()).isEqualTo("fresh");
    }

    @Test
    void loginErrorCodesNeverDeactivateAnExistingSession() {
        for (BanquePopulaireErrorCode code : new BanquePopulaireErrorCode[] {
            BanquePopulaireErrorCode.INVALID_CREDENTIALS, BanquePopulaireErrorCode.KEYPAD_CHANGED,
            BanquePopulaireErrorCode.APP_VALIDATION_TIMEOUT, BanquePopulaireErrorCode.AUTH_ATTEMPT_EXPIRED}) {
            BanquePopulaireSession session = session();
            session.markQueued();
            session.markFailed(code, NOW);
            assertThat(session.isActive()).as(code.name()).isTrue();
            assertThat(session.getLastSyncError()).isEqualTo(code);
        }
    }
}
