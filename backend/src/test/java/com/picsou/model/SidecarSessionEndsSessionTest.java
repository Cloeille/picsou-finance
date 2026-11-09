package com.picsou.model;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.port.SidecarErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SidecarSessionEndsSessionTest {
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private SidecarSession failedWith(SidecarBank bank, SidecarErrorCode code) {
        SidecarSession session = SidecarSession.create(
            bank, FamilyMember.builder().id(7L).displayName("Owner").build(), "encrypted", NOW);
        session.markQueued();
        session.markFailed(code, NOW);
        return session;
    }

    @Test
    void amexSessionEndsWhenTheStoredPasswordIsRejected() {
        SidecarSession session = failedWith(SidecarBank.AMEX, SidecarErrorCode.INVALID_CREDENTIALS);

        assertThat(session.isActive()).isFalse();
        assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void amexSessionEndsWhenItExpires() {
        assertThat(failedWith(SidecarBank.AMEX, SidecarErrorCode.SESSION_EXPIRED).isActive()).isFalse();
    }

    @Test
    void amexSessionSurvivesATransientFailure() {
        assertThat(failedWith(SidecarBank.AMEX, SidecarErrorCode.UPSTREAM_UNAVAILABLE).isActive()).isTrue();
    }

    @Test
    void aBankThatKeepsNoPasswordKeepsItsSessionOnInvalidCredentials() {
        assertThat(failedWith(SidecarBank.AMUNDI, SidecarErrorCode.INVALID_CREDENTIALS).isActive()).isTrue();
    }
}
