package com.picsou.service.sync;

import com.picsou.exception.SyncException;
import com.picsou.port.AmundiErrorCode;
import com.picsou.port.BoursoErrorCode;
import com.picsou.port.BourseDirectErrorCode;
import com.picsou.port.FortuneoErrorCode;
import com.picsou.port.AmexErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceSyncResultTest {

    @Test
    void syncExceptionStatus_recognizesOnlyExactConnectorAuthenticationCodes() {
        assertStatus("trade-republic", new SyncException("expired session", null, "SESSION_EXPIRED"), SourceSyncResult.Status.NEEDS_REAUTH);
        assertStatus("trade-republic", new SyncException("AUTHENTICATION_ERROR"), SourceSyncResult.Status.NEEDS_REAUTH);
        assertStatus("revolut", new SyncException("friendly expired-session message", null, "SESSION_EXPIRED"), SourceSyncResult.Status.NEEDS_REAUTH);
        assertStatus("bourso", coded(BoursoErrorCode.SESSION_EXPIRED.name()), SourceSyncResult.Status.NEEDS_REAUTH);
        assertStatus("bourso", coded(BoursoErrorCode.INVALID_CREDENTIALS.name()), SourceSyncResult.Status.NEEDS_REAUTH);
        assertStatus("bourse-direct", coded(BourseDirectErrorCode.SESSION_EXPIRED.name()), SourceSyncResult.Status.NEEDS_REAUTH);
        assertStatus("amundi", coded(AmundiErrorCode.SESSION_EXPIRED.name()), SourceSyncResult.Status.NEEDS_REAUTH);
        assertStatus("fortuneo", coded(FortuneoErrorCode.SESSION_EXPIRED.name()), SourceSyncResult.Status.NEEDS_REAUTH);
        assertStatus("amex", coded(AmexErrorCode.SESSION_EXPIRED.name()), SourceSyncResult.Status.NEEDS_REAUTH);
        assertStatus("ibkr", new SyncException("Interactive Brokers: Token has expired. (code 1012)"), SourceSyncResult.Status.NEEDS_REAUTH);
        assertStatus("ibkr", new SyncException("Interactive Brokers: Token is invalid. (code 1015)"), SourceSyncResult.Status.NEEDS_REAUTH);
    }

    @Test
    void syncExceptionStatus_keepsTransientUnknownAndNonAuthErrorsFailed() {
        for (String source : new String[] {"trade-republic", "ibkr", "revolut", "bourso", "bourse-direct", "amundi", "fortuneo", "amex"}) {
            assertStatus(source, new SyncException("temporary provider outage"), SourceSyncResult.Status.FAILED);
            assertStatus(source, new SyncException("INTERNAL_ERROR", null, "INTERNAL_ERROR"), SourceSyncResult.Status.FAILED);
            assertStatus(source, new SyncException("failure with no code", null, null), SourceSyncResult.Status.FAILED);
        }
        assertStatus("ibkr", new SyncException("Interactive Brokers: Query is invalid. (code 1014)"), SourceSyncResult.Status.FAILED);
        assertStatus("ibkr", new SyncException("Interactive Brokers: expired token, invalid query id"), SourceSyncResult.Status.FAILED);
        assertStatus("bourso", coded(BoursoErrorCode.UPSTREAM_UNAVAILABLE.name()), SourceSyncResult.Status.FAILED);
        assertStatus("bourse-direct", coded(BourseDirectErrorCode.UPSTREAM_UNAVAILABLE.name()), SourceSyncResult.Status.FAILED);
        assertStatus("amundi", coded(AmundiErrorCode.UPSTREAM_UNAVAILABLE.name()), SourceSyncResult.Status.FAILED);
        assertStatus("fortuneo", coded(FortuneoErrorCode.UPSTREAM_UNAVAILABLE.name()), SourceSyncResult.Status.FAILED);
        assertStatus("amex", coded(AmexErrorCode.UPSTREAM_UNAVAILABLE.name()), SourceSyncResult.Status.FAILED);
        assertStatus("amex", coded(AmexErrorCode.AUTH_ATTEMPT_EXPIRED.name()), SourceSyncResult.Status.FAILED);
    }

    @Test
    void syncExceptionResult_neverExposesProviderMessagesOrUnknownCodes() {
        String secret = "provider-secret-token-123";

        SourceSyncResult reauth = SourceSyncResult.fromSyncException("revolut",
            new SyncException(secret, null, "SESSION_EXPIRED"));
        SourceSyncResult failed = SourceSyncResult.fromSyncException("ibkr",
            new SyncException(secret + " (code 9999)"));
        SourceSyncResult arbitraryCode = SourceSyncResult.fromSyncException("bourso",
            new SyncException(secret, null, secret));

        assertThat(reauth.status()).isEqualTo(SourceSyncResult.Status.NEEDS_REAUTH);
        assertThat(reauth.message()).isEqualTo("Reauthentication required").doesNotContain(secret);
        assertThat(failed.status()).isEqualTo(SourceSyncResult.Status.FAILED);
        assertThat(failed.message()).isEqualTo("Sync failed").doesNotContain(secret);
        assertThat(arbitraryCode.status()).isEqualTo(SourceSyncResult.Status.FAILED);
        assertThat(arbitraryCode.message()).isEqualTo("Sync failed").doesNotContain(secret);
    }

    @Test
    void fromSyncException_simplefinSessionExpired_needsReauth() {
        assertStatus("simplefin", coded("SESSION_EXPIRED"), SourceSyncResult.Status.NEEDS_REAUTH);
    }

    @Test
    void fromSyncException_simplefinWithoutCode_isFailed() {
        assertStatus("simplefin", new SyncException("Bridge unavailable"), SourceSyncResult.Status.FAILED);
    }

    private static SyncException coded(String code) {
        return new SyncException("provider failure", null, code);
    }

    private static void assertStatus(String source, SyncException exception, SourceSyncResult.Status expected) {
        assertThat(SourceSyncResult.fromSyncException(source, exception).status()).isEqualTo(expected);
    }
}
