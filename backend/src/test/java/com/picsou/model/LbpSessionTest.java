package com.picsou.model;

import com.picsou.port.SidecarErrorCode;
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
        // The password and the keypad positions never reach Picsou.
        assertThat(Arrays.stream(LbpSession.class.getDeclaredFields()).map(Field::getName))
            .noneMatch(name -> name.toLowerCase().contains("credential")
                || name.toLowerCase().contains("password")
                || name.toLowerCase().contains("keypad"));
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
        expired.markFailed(SidecarErrorCode.SESSION_EXPIRED, NOW);
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
        assertThatThrownBy(() -> session().markFailed(SidecarErrorCode.INTERNAL_ERROR, NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUndeclaredErrorCodeIsRejectedAndLeavesTheSessionUntouched() {
        LbpSession session = session();
        session.markQueued();
        session.markRunning(NOW);

        // LBP has no SMS code: INVALID_OTP is not one of its declared codes.
        assertThatThrownBy(() -> session.markFailed(SidecarErrorCode.INVALID_OTP, NOW))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(session.getSyncStatus()).isEqualTo(LbpSyncStatus.RUNNING);
        assertThat(session.getLastSyncError()).isNull();
        assertThat(session.isActive()).isTrue();
    }

    @Test
    void aTransientFailureKeepsTheSessionUsable() {
        LbpSession session = session();
        session.markQueued();
        session.markRunning(NOW);

        session.markFailed(SidecarErrorCode.UPSTREAM_UNAVAILABLE, NOW);

        assertThat(session.getSyncStatus()).isEqualTo(LbpSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(session.isActive()).isTrue();
    }

    @Test
    void anExpiredOrUnusableSessionIsDeactivated() {
        for (SidecarErrorCode code : new SidecarErrorCode[] {
            SidecarErrorCode.SESSION_EXPIRED, SidecarErrorCode.INVALID_SESSION_STATE}) {
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
        session.markFailed(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED, NOW);

        session.markQueued();

        assertThat(session.getSyncStatus()).isEqualTo(LbpSyncStatus.QUEUED);
        assertThat(session.getLastSyncError()).isNull();
        assertThat(session.getLastSyncCompletedAt()).isNull();
    }

    @Test
    void loginErrorCodesNeverDeactivateAnExistingSession() {
        for (SidecarErrorCode code : new SidecarErrorCode[] {
            SidecarErrorCode.INVALID_CREDENTIALS, SidecarErrorCode.KEYPAD_CHANGED,
            SidecarErrorCode.KEYPAD_EXPIRED, SidecarErrorCode.INVALID_POSITIONS,
            SidecarErrorCode.APP_VALIDATION_TIMEOUT, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED}) {
            LbpSession session = session();
            session.markQueued();
            session.markFailed(code, NOW);
            assertThat(session.isActive()).as(code.name()).isTrue();
            assertThat(session.getLastSyncError()).isEqualTo(code);
        }
    }
}
