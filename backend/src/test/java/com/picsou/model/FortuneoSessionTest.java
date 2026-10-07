package com.picsou.model;

import com.picsou.port.FortuneoErrorCode;
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
        FortuneoSession session = newSession();

        session.markQueued();
        session.markRunning(STARTED_AT);
        session.markSuccessful(COMPLETED_AT);

        assertThat(session.getSyncStatus()).isEqualTo(FortuneoSyncStatus.SUCCESS);
        assertThat(session.getLastSyncStartedAt()).isEqualTo(STARTED_AT);
        assertThat(session.getLastSyncCompletedAt()).isEqualTo(COMPLETED_AT);
        assertThat(session.getLastValidatedAt()).isEqualTo(COMPLETED_AT);
        assertThat(session.getLastSyncError()).isNull();
        assertThat(session.isActive()).isTrue();
    }

    @Test
    void invalidTransitionIsRejected() {
        FortuneoSession session = newSession();

        assertThatThrownBy(() -> session.markRunning(STARTED_AT))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("queued");
    }

    @Test
    void failedTransitionStoresTypedErrorAndExpiresTheSession() {
        FortuneoSession session = newSession();
        session.markQueued();
        session.markRunning(STARTED_AT);

        session.markFailed(FortuneoErrorCode.SESSION_EXPIRED, COMPLETED_AT);

        assertThat(session.getSyncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(FortuneoErrorCode.SESSION_EXPIRED);
        assertThat(session.getLastSyncCompletedAt()).isEqualTo(COMPLETED_AT);
        assertThat(session.isActive()).isFalse();
    }

    @Test
    void queuedJobCanFailWhenExecutorRejectsIt() {
        FortuneoSession session = newSession();
        session.markQueued();

        session.markFailed(FortuneoErrorCode.INTERNAL_ERROR, COMPLETED_AT);

        assertThat(session.getSyncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(session.getLastSyncStartedAt()).isNull();
        assertThat(session.isActive()).isTrue();
    }

    private FortuneoSession newSession() {
        FamilyMember member = FamilyMember.builder().id(7L).displayName("Owner").build();
        return FortuneoSession.create(member, "encrypted", VALIDATED_AT);
    }
}
