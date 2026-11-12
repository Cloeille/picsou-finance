package com.picsou.model;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.port.SidecarErrorCode;
import com.picsou.support.TestEntities;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaisseEpargneSessionTest {
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    private SidecarSession session() {
        return SidecarSession.create(SidecarBank.CAISSE_EPARGNE, TestEntities.owner(), "encrypted", NOW);
    }

    @Test
    void aCaisseEpargneSessionHasNoRegion() {
        assertThat(session().getRegion()).isNull();
    }

    @Test
    void aNewSessionIsActiveAndIdle() {
        SidecarSession session = session();

        assertThat(session.isActive()).isTrue();
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.IDLE);
        assertThat(session.getSessionState()).isEqualTo("encrypted");
        assertThat(session.getLastValidatedAt()).isEqualTo(NOW);
    }

    @Test
    void happyPathQueuedRunningSuccess() {
        SidecarSession session = session();

        session.markQueued();
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.QUEUED);
        assertThat(session.isSyncInFlight()).isTrue();

        session.markRunning(NOW);
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.RUNNING);
        assertThat(session.getLastSyncStartedAt()).isEqualTo(NOW);

        Instant done = NOW.plusSeconds(5);
        session.markSuccessful(done);
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        assertThat(session.getLastSyncCompletedAt()).isEqualTo(done);
        assertThat(session.getLastValidatedAt()).isEqualTo(done);
        assertThat(session.getLastSyncError()).isNull();
        assertThat(session.isSyncInFlight()).isFalse();
    }

    @Test
    void cannotQueueTwiceOrWhileInactive() {
        SidecarSession session = session();
        session.markQueued();
        assertThatThrownBy(session::markQueued).isInstanceOf(IllegalStateException.class);

        SidecarSession expired = session();
        expired.markQueued();
        expired.markFailed(SidecarErrorCode.SESSION_EXPIRED, NOW);
        assertThatThrownBy(expired::markQueued).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runningRequiresAQueuedActiveSession() {
        assertThatThrownBy(() -> session().markRunning(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void successRequiresARunningSession() {
        SidecarSession session = session();
        session.markQueued();
        assertThatThrownBy(() -> session.markSuccessful(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureRequiresAnInFlightSync() {
        assertThatThrownBy(() -> session().markFailed(SidecarErrorCode.INTERNAL_ERROR, NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTransientFailureKeepsTheSessionUsable() {
        SidecarSession session = session();
        session.markQueued();
        session.markRunning(NOW);

        session.markFailed(SidecarErrorCode.UPSTREAM_UNAVAILABLE, NOW);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(session.isActive()).isTrue();
    }

    @Test
    void anExpiredOrUnusableSessionIsDeactivated() {
        for (SidecarErrorCode code : new SidecarErrorCode[] {
            SidecarErrorCode.SESSION_EXPIRED, SidecarErrorCode.INVALID_SESSION_STATE}) {
            SidecarSession session = session();
            session.markQueued();
            session.markFailed(code, NOW);

            assertThat(session.isActive()).as(code.name()).isFalse();
            assertThat(session.getLastSyncError()).isEqualTo(code);
        }
    }

    @Test
    void queueingClearsThePreviousOutcome() {
        SidecarSession session = session();
        session.markQueued();
        session.markFailed(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED, NOW);

        session.markQueued();

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.QUEUED);
        assertThat(session.getLastSyncError()).isNull();
        assertThat(session.getLastSyncCompletedAt()).isNull();
    }

    @Test
    void updatingTheSessionStateReactivatesIt() {
        SidecarSession session = session();
        session.markQueued();
        session.markFailed(SidecarErrorCode.SESSION_EXPIRED, NOW);

        session.updateSessionState("fresh", NOW.plusSeconds(60));

        assertThat(session.isActive()).isTrue();
        assertThat(session.getSessionState()).isEqualTo("fresh");
    }

    @Test
    void loginErrorCodesNeverDeactivateAnExistingSession() {
        for (SidecarErrorCode code : new SidecarErrorCode[] {
            SidecarErrorCode.INVALID_CREDENTIALS, SidecarErrorCode.KEYPAD_CHANGED,
            SidecarErrorCode.APP_VALIDATION_TIMEOUT, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED}) {
            SidecarSession session = session();
            session.markQueued();
            session.markFailed(code, NOW);
            assertThat(session.isActive()).as(code.name()).isTrue();
            assertThat(session.getLastSyncError()).isEqualTo(code);
        }
    }
}
