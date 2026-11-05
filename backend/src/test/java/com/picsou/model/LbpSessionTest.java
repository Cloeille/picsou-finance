package com.picsou.model;

import com.picsou.port.LbpErrorCode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LbpSessionTest {
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private LbpSession session() {
        return LbpSession.create(
            FamilyMember.builder().id(7L).displayName("Owner").build(), "encrypted", NOW);
    }

    @Test
    void aNewSessionIsActiveAndIdle() {
        LbpSession session = session();

        assertThat(session.isActive()).isTrue();
        assertThat(session.getSyncStatus()).isEqualTo(LbpSyncStatus.IDLE);
        assertThat(session.getSessionState()).isEqualTo("encrypted");
        assertThat(session.getLastValidatedAt()).isEqualTo(NOW);
    }

    @Test
    void neverStoresCredentials() {
        // The password, the keypad positions and the Certicode code never reach Picsou.
        assertThat(Arrays.stream(LbpSession.class.getDeclaredFields()).map(Field::getName))
            .noneMatch(name -> name.toLowerCase().contains("credential")
                || name.toLowerCase().contains("password")
                || name.toLowerCase().contains("otp"));
    }

    @Test
    void happyPathQueuedRunningSuccess() {
        LbpSession session = session();

        session.markQueued();
        assertThat(session.getSyncStatus()).isEqualTo(LbpSyncStatus.QUEUED);
        assertThat(session.isSyncInFlight()).isTrue();

        session.markRunning(NOW);
        assertThat(session.getSyncStatus()).isEqualTo(LbpSyncStatus.RUNNING);
        assertThat(session.getLastSyncStartedAt()).isEqualTo(NOW);

        Instant done = NOW.plusSeconds(5);
        session.markSuccessful(done);
        assertThat(session.getSyncStatus()).isEqualTo(LbpSyncStatus.SUCCESS);
        assertThat(session.getLastSyncCompletedAt()).isEqualTo(done);
        assertThat(session.getLastValidatedAt()).isEqualTo(done);
        assertThat(session.getLastSyncError()).isNull();
        assertThat(session.isSyncInFlight()).isFalse();
    }

    @Test
    void cannotQueueTwiceOrWhileInactive() {
        LbpSession session = session();
        session.markQueued();
        assertThatThrownBy(session::markQueued).isInstanceOf(IllegalStateException.class);

        LbpSession expired = session();
        expired.markQueued();
        expired.markFailed(LbpErrorCode.SESSION_EXPIRED, NOW);
        assertThatThrownBy(expired::markQueued).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runningRequiresAQueuedActiveSession() {
        assertThatThrownBy(() -> session().markRunning(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void successRequiresARunningSession() {
        LbpSession session = session();
        session.markQueued();
        assertThatThrownBy(() -> session.markSuccessful(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureRequiresAnInFlightSync() {
        assertThatThrownBy(() -> session().markFailed(LbpErrorCode.INTERNAL_ERROR, NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTransientFailureKeepsTheSessionUsable() {
        LbpSession session = session();
        session.markQueued();
        session.markRunning(NOW);

        session.markFailed(LbpErrorCode.UPSTREAM_UNAVAILABLE, NOW);

        assertThat(session.getSyncStatus()).isEqualTo(LbpSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(LbpErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(session.isActive()).isTrue();
    }

    @Test
    void anExpiredOrUnusableSessionIsDeactivated() {
        for (LbpErrorCode code : new LbpErrorCode[] {
            LbpErrorCode.SESSION_EXPIRED, LbpErrorCode.INVALID_SESSION_STATE}) {
            LbpSession session = session();
            session.markQueued();
            session.markFailed(code, NOW);

            assertThat(session.isActive()).as(code.name()).isFalse();
            assertThat(session.getLastSyncError()).isEqualTo(code);
        }
    }

    @Test
    void queueingClearsThePreviousOutcome() {
        LbpSession session = session();
        session.markQueued();
        session.markFailed(LbpErrorCode.UPSTREAM_FORMAT_CHANGED, NOW);

        session.markQueued();

        assertThat(session.getSyncStatus()).isEqualTo(LbpSyncStatus.QUEUED);
        assertThat(session.getLastSyncError()).isNull();
        assertThat(session.getLastSyncCompletedAt()).isNull();
    }

    @Test
    void updatingTheSessionStateReactivatesIt() {
        LbpSession session = session();
        session.markQueued();
        session.markFailed(LbpErrorCode.SESSION_EXPIRED, NOW);

        session.updateSessionState("fresh", NOW.plusSeconds(60));

        assertThat(session.isActive()).isTrue();
        assertThat(session.getSessionState()).isEqualTo("fresh");
    }

    @Test
    void loginErrorCodesNeverDeactivateAnExistingSession() {
        for (LbpErrorCode code : new LbpErrorCode[] {
            LbpErrorCode.INVALID_CREDENTIALS, LbpErrorCode.KEYPAD_CHANGED,
            LbpErrorCode.KEYPAD_EXPIRED, LbpErrorCode.INVALID_POSITIONS,
            LbpErrorCode.OTP_INVALID, LbpErrorCode.OTP_EXPIRED,
            LbpErrorCode.APP_VALIDATION_TIMEOUT, LbpErrorCode.AUTH_ATTEMPT_EXPIRED}) {
            LbpSession session = session();
            session.markQueued();
            session.markFailed(code, NOW);
            assertThat(session.isActive()).as(code.name()).isTrue();
            assertThat(session.getLastSyncError()).isEqualTo(code);
        }
    }
}
