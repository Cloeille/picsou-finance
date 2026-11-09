package com.picsou.model;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.port.SidecarErrorCode;
import com.picsou.support.TestEntities;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FortuneoSessionTest {

    private static final Instant VALIDATED_AT = Instant.parse("2026-07-26T08:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-07-26T08:01:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-07-26T08:02:00Z");

    @Test
    void guardedTransitionsKeepSyncFieldsConsistent() {
        SidecarSession session = newSession();

        session.markQueued();
        session.markRunning(STARTED_AT);
        session.markSuccessful(COMPLETED_AT);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        assertThat(session.getLastSyncStartedAt()).isEqualTo(STARTED_AT);
        assertThat(session.getLastSyncCompletedAt()).isEqualTo(COMPLETED_AT);
        assertThat(session.getLastValidatedAt()).isEqualTo(COMPLETED_AT);
        assertThat(session.getLastSyncError()).isNull();
        assertThat(session.isActive()).isTrue();
    }

    @Test
    void invalidTransitionIsRejected() {
        SidecarSession session = newSession();

        assertThatThrownBy(() -> session.markRunning(STARTED_AT))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("queued");
    }

    @Test
    void failedTransitionStoresTypedErrorAndExpiresTheSession() {
        SidecarSession session = newSession();
        session.markQueued();
        session.markRunning(STARTED_AT);

        session.markFailed(SidecarErrorCode.SESSION_EXPIRED, COMPLETED_AT);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.SESSION_EXPIRED);
        assertThat(session.getLastSyncCompletedAt()).isEqualTo(COMPLETED_AT);
        assertThat(session.isActive()).isFalse();
    }

    @Test
    void queuedJobCanFailWhenExecutorRejectsIt() {
        SidecarSession session = newSession();
        session.markQueued();

        session.markFailed(SidecarErrorCode.INTERNAL_ERROR, COMPLETED_AT);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncStartedAt()).isNull();
        assertThat(session.isActive()).isTrue();
    }

    @Test
    void aCodeFortuneoNeverDeclaredIsRejectedBeforeItReachesTheCheckConstraint() {
        SidecarSession session = newSession();
        session.markQueued();
        session.markRunning(STARTED_AT);

        assertThatThrownBy(() -> session.markFailed(SidecarErrorCode.CAPTCHA_BLOCKED, COMPLETED_AT))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CAPTCHA_BLOCKED");
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.RUNNING);
        assertThat(session.getLastSyncError()).isNull();
    }

    private SidecarSession newSession() {
        return SidecarSession.create(SidecarBank.FORTUNEO, TestEntities.owner(), "encrypted", VALIDATED_AT);
    }
}
