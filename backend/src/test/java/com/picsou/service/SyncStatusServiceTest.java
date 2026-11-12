package com.picsou.service;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.model.DegiroSession;
import com.picsou.model.DegiroSessionStatus;
import com.picsou.model.FinarySession;
import com.picsou.model.IbkrConnection;
import com.picsou.model.Requisition;
import com.picsou.model.SimplefinConnection;
import com.picsou.model.WiseConnection;
import com.picsou.model.RequisitionStatus;
import com.picsou.model.WalletAddress;
import com.picsou.model.Chain;
import com.picsou.port.SidecarErrorCode;
import com.picsou.repository.DegiroSessionRepository;
import com.picsou.repository.FinarySessionRepository;
import com.picsou.repository.IbkrConnectionRepository;
import com.picsou.repository.RequisitionRepository;
import com.picsou.repository.SimplefinConnectionRepository;
import com.picsou.repository.WiseConnectionRepository;
import com.picsou.repository.WalletAddressRepository;
import com.picsou.model.LbpSyncStatus;
import com.picsou.model.SidecarSyncStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncStatusServiceTest {

    private static final long MID = 7L;
    private static final Instant SYNCED_AT = Instant.parse("2026-10-04T06:00:00Z");

    @Mock RevolutSyncService revolutSyncService;
    @Mock RequisitionRepository requisitionRepository;
    @Mock TradeRepublicSyncService tradeRepublicSyncService;
    @Mock BoursoSyncService boursoSyncService;
    @Mock BourseDirectSyncService bourseDirectSyncService;
    @Mock AmundiSyncService amundiSyncService;
    @Mock CmesSyncService cmesSyncService;
    @Mock NatixisSyncService natixisSyncService;
    @Mock CicSyncService cicSyncService;
    @Mock CorumSyncService corumSyncService;
    @Mock LbpSyncService lbpSyncService;
    @Mock BnpSyncService bnpSyncService;
    @Mock FortuneoSyncService fortuneoSyncService;
    @Mock AmexSyncService amexSyncService;
    @Mock IbkrConnectionRepository ibkrConnectionRepository;
    @Mock SimplefinConnectionRepository simplefinConnectionRepository;
    @Mock WiseConnectionRepository wiseConnectionRepository;
    @Mock CryptoExchangeSyncService cryptoExchangeSyncService;
    @Mock WalletAddressRepository walletAddressRepository;
    @Mock FinarySessionRepository finarySessionRepository;
    @Mock DegiroSessionRepository degiroSessionRepository;

    private SyncStatusService service;

    @BeforeEach
    void setUp() {
        service = new SyncStatusService(
            revolutSyncService, requisitionRepository, tradeRepublicSyncService,
            boursoSyncService, bourseDirectSyncService, amundiSyncService, cmesSyncService, cicSyncService, corumSyncService, natixisSyncService,
            lbpSyncService,
            bnpSyncService,
            fortuneoSyncService,
            amexSyncService, ibkrConnectionRepository, simplefinConnectionRepository, wiseConnectionRepository,
            cryptoExchangeSyncService,
            walletAddressRepository,
            finarySessionRepository, degiroSessionRepository);
        when(revolutSyncService.getStatus(MID)).thenReturn(new RevolutSyncService.StatusResponse(false, false, null));
        when(requisitionRepository.findAllByMemberId(MID)).thenReturn(List.of());
        when(tradeRepublicSyncService.getStatus(MID)).thenReturn(
            new TradeRepublicSyncService.SessionStatusResponse(false, SidecarSyncStatus.IDLE, null, null, null, null));
        when(boursoSyncService.getStatus(MID)).thenReturn(
            new BoursoSyncService.SessionStatusResponse(false, SidecarSyncStatus.IDLE, null, null, null));
        when(bourseDirectSyncService.getStatus(MID)).thenReturn(
            new BourseDirectSyncService.SessionStatusResponse(false, null, SidecarSyncStatus.IDLE, null, null, null));
        when(amundiSyncService.getStatus(MID)).thenReturn(
            new AmundiSyncService.SessionStatusResponse(false, SidecarSyncStatus.IDLE, null, null, null));
        when(cmesSyncService.getStatus(MID)).thenReturn(CmesSyncService.SessionStatusResponse.inactive());
        when(cicSyncService.getStatus(MID)).thenReturn(CicSyncService.SessionStatusResponse.inactive());
        when(corumSyncService.getStatus(MID)).thenReturn(CorumSyncService.SessionStatusResponse.inactive());
        when(natixisSyncService.getStatus(MID)).thenReturn(NatixisSyncService.SessionStatusResponse.inactive());
        when(lbpSyncService.getStatus(MID)).thenReturn(LbpSyncService.SessionStatusResponse.inactive());
        when(bnpSyncService.getStatus(MID)).thenReturn(BnpSyncService.SessionStatusResponse.inactive());
        when(fortuneoSyncService.getStatus(MID)).thenReturn(
            new FortuneoSyncService.SessionStatusResponse(false, null, SidecarSyncStatus.IDLE, null, null, null));
        when(amexSyncService.getStatus(MID)).thenReturn(
            new AmexSyncService.SessionStatusResponse(false, SidecarSyncStatus.IDLE, null, null, null));
        when(ibkrConnectionRepository.findByMemberId(MID)).thenReturn(Optional.empty());
        when(simplefinConnectionRepository.findByMemberId(MID)).thenReturn(Optional.empty());
        when(wiseConnectionRepository.findByMemberId(MID)).thenReturn(Optional.empty());
        when(cryptoExchangeSyncService.getStatus(MID)).thenReturn(List.of());
        when(walletAddressRepository.findAllByMemberId(MID)).thenReturn(List.of());
        when(finarySessionRepository.findByMemberId(MID)).thenReturn(Optional.empty());
        when(degiroSessionRepository.findByMemberId(MID)).thenReturn(Optional.empty());
    }

    @Test
    void degiroStatusDoesNotExposeStoredExceptionDetails() {
        for (String error : List.of("private SQL password=private-marker", "SESSION_EXPIRED token=private-marker")) {
            when(degiroSessionRepository.findByMemberId(MID)).thenReturn(Optional.of(
                DegiroSession.builder().status(DegiroSessionStatus.FAILED).lastError(error).build()));

            String text = service.describe(MID);

            assertThat(text).doesNotContain("private-marker", "password=", "token=");
            assertThat(text).contains(error.startsWith("SESSION_EXPIRED")
                ? "degiro: NEEDS_REAUTH lastSync=none reauth=true"
                : "degiro: FAILED lastSync=none reauth=false");
        }
    }

    @Test
    void amexReportsQueuedCompletionAndReauthenticationTruthfully() {
        when(amexSyncService.getStatus(MID)).thenReturn(new AmexSyncService.SessionStatusResponse(
            true, SidecarSyncStatus.QUEUED, null, SYNCED_AT, null));
        String queued = service.describe(MID);
        assertThat(queued).contains("amex: QUEUED lastSync=2026-10-04T06:00:00Z reauth=false");

        when(amexSyncService.getStatus(MID)).thenReturn(new AmexSyncService.SessionStatusResponse(
            false, SidecarSyncStatus.FAILED, null, SYNCED_AT, SidecarErrorCode.SESSION_EXPIRED));
        String expired = service.describe(MID);
        assertThat(expired).contains("amex: NEEDS_REAUTH lastSync=2026-10-04T06:00:00Z reauth=true — SESSION_EXPIRED");
    }

    @Test
    void cmesReportsItsConnectionAndAskForAReconnectionOnceItsPasswordIsRejected() {
        assertThat(service.describe(MID)).contains("cmes: NOT_CONNECTED lastSync=none reauth=false");

        when(cmesSyncService.getStatus(MID)).thenReturn(new CmesSyncService.SessionStatusResponse(
            true, SidecarSyncStatus.SUCCESS, null, SYNCED_AT, null));
        assertThat(service.describe(MID)).contains("cmes: CONNECTED lastSync=2026-10-04T06:00:00Z reauth=false");

        // The rejected password was forgotten: only the saver can give it again.
        when(cmesSyncService.getStatus(MID)).thenReturn(new CmesSyncService.SessionStatusResponse(
            false, SidecarSyncStatus.FAILED, null, SYNCED_AT, SidecarErrorCode.INVALID_CREDENTIALS));
        assertThat(service.describe(MID))
            .contains("cmes: NEEDS_REAUTH lastSync=2026-10-04T06:00:00Z reauth=true — INVALID_CREDENTIALS");
    }

    @Test
    void corumReportsItsConnectionAndAsksForAReconnectionOnceItsPasswordIsRejected() {
        assertThat(service.describe(MID)).contains("corum: NOT_CONNECTED lastSync=none reauth=false");

        when(corumSyncService.getStatus(MID)).thenReturn(new CorumSyncService.SessionStatusResponse(
            true, SidecarSyncStatus.SUCCESS, null, SYNCED_AT, null, 0, true));
        assertThat(service.describe(MID)).contains("corum: CONNECTED lastSync=2026-10-04T06:00:00Z reauth=false");

        when(corumSyncService.getStatus(MID)).thenReturn(new CorumSyncService.SessionStatusResponse(
            false, SidecarSyncStatus.FAILED, null, SYNCED_AT, SidecarErrorCode.INVALID_CREDENTIALS, 0, false));
        assertThat(service.describe(MID))
            .contains("corum: NEEDS_REAUTH lastSync=2026-10-04T06:00:00Z reauth=true — INVALID_CREDENTIALS");
    }

    @ParameterizedTest
    @EnumSource(value = SidecarErrorCode.class, names = {"SESSION_EXPIRED", "INVALID_SESSION_STATE"})
    void tradeRepublicSessionEndingErrorsRequireReconnection(SidecarErrorCode error) {
        when(tradeRepublicSyncService.getStatus(MID)).thenReturn(new TradeRepublicSyncService.SessionStatusResponse(
            false, SidecarSyncStatus.FAILED, null, SYNCED_AT, error, null));

        assertThat(service.describe(MID)).contains(
            "trade-republic: NEEDS_REAUTH lastSync=2026-10-04T06:00:00Z reauth=true — " + error.name());
    }

    @Test
    void tradeRepublicTransientFailureIsRetriedWithoutReconnection() {
        when(tradeRepublicSyncService.getStatus(MID)).thenReturn(new TradeRepublicSyncService.SessionStatusResponse(
            true, SidecarSyncStatus.FAILED, null, SYNCED_AT, SidecarErrorCode.UPSTREAM_UNAVAILABLE, null));

        assertThat(service.describe(MID)).contains(
            "trade-republic: FAILED lastSync=2026-10-04T06:00:00Z reauth=false — UPSTREAM_UNAVAILABLE");
    }

    @ParameterizedTest
    @EnumSource(value = SidecarSyncStatus.class, names = {"QUEUED", "RUNNING", "SUCCESS"})
    void cicReportsQueuedRunningAndCompletedSyncs(SidecarSyncStatus status) {
        when(cicSyncService.getStatus(MID)).thenReturn(new CicSyncService.SessionStatusResponse(
            true, status, null, SYNCED_AT, null, true));

        assertThat(service.describe(MID)).contains("cic: " + (status == SidecarSyncStatus.SUCCESS ? "CONNECTED" : status.name())
            + " lastSync=2026-10-04T06:00:00Z reauth=false");
    }

    @ParameterizedTest
    @EnumSource(value = SidecarErrorCode.class, names = {"SESSION_EXPIRED", "INVALID_CREDENTIALS"})
    void cicAuthenticationErrorsRequireReconnection(SidecarErrorCode error) {
        when(cicSyncService.getStatus(MID)).thenReturn(new CicSyncService.SessionStatusResponse(
            false, SidecarSyncStatus.FAILED, null, SYNCED_AT, error, false));

        assertThat(service.describe(MID)).contains(
            "cic: NEEDS_REAUTH lastSync=2026-10-04T06:00:00Z reauth=true — " + error.name());
    }

    @Test
    void cicTransientFailuresAllowRetryWithoutReauthentication() {
        when(cicSyncService.getStatus(MID)).thenReturn(new CicSyncService.SessionStatusResponse(
            true, SidecarSyncStatus.FAILED, null, SYNCED_AT, SidecarErrorCode.UPSTREAM_UNAVAILABLE, true));

        assertThat(service.describe(MID)).contains(
            "cic: FAILED lastSync=2026-10-04T06:00:00Z reauth=false — UPSTREAM_UNAVAILABLE");
    }

    @ParameterizedTest
    @EnumSource(value = LbpSyncStatus.class, names = {"QUEUED", "RUNNING", "SUCCESS"})
    void lbpReportsQueuedRunningAndCompletedSyncs(LbpSyncStatus status) {
        when(lbpSyncService.getStatus(MID)).thenReturn(new LbpSyncService.SessionStatusResponse(
            true, status, null, SYNCED_AT, null, List.of(), null));

        assertThat(service.describe(MID)).contains("lbp: " + (status == LbpSyncStatus.SUCCESS ? "CONNECTED" : status.name())
            + " lastSync=2026-10-04T06:00:00Z reauth=false");
    }

    @ParameterizedTest
    @EnumSource(value = SidecarErrorCode.class, names = {"SESSION_EXPIRED", "INVALID_CREDENTIALS"})
    void lbpAuthenticationErrorsRequireReconnection(SidecarErrorCode error) {
        when(lbpSyncService.getStatus(MID)).thenReturn(new LbpSyncService.SessionStatusResponse(
            false, LbpSyncStatus.FAILED, null, SYNCED_AT, error, List.of(), null));

        assertThat(service.describe(MID)).contains(
            "lbp: NEEDS_REAUTH lastSync=2026-10-04T06:00:00Z reauth=true — " + error.name());
    }

    @Test
    void lbpTransientFailuresAllowRetryWithoutReauthentication() {
        when(lbpSyncService.getStatus(MID)).thenReturn(new LbpSyncService.SessionStatusResponse(
            true, LbpSyncStatus.FAILED, null, SYNCED_AT, SidecarErrorCode.UPSTREAM_UNAVAILABLE, List.of(), null));

        assertThat(service.describe(MID)).contains(
            "lbp: FAILED lastSync=2026-10-04T06:00:00Z reauth=false — UPSTREAM_UNAVAILABLE");
    }

    @Test
    void missingConnections_areNamedNotHidden() {
        String text = service.describe(MID);

        assertThat(text).contains("revolut: NOT_CONNECTED lastSync=none reauth=false");
        assertThat(text).contains("enable-banking: NOT_CONNECTED lastSync=none reauth=false");
        assertThat(text).contains("amex: NOT_CONNECTED lastSync=none reauth=false");
        assertThat(text).contains("cic: NOT_CONNECTED lastSync=none reauth=false");
        assertThat(text).contains("lbp: NOT_CONNECTED lastSync=none reauth=false");
        assertThat(text).contains("degiro: NOT_CONNECTED lastSync=none reauth=false");
        assertThat(text).doesNotContain("password");
        assertThat(text).doesNotContain("token");
    }

    @Test
    void expiredBankAndReauthBroker_areFlagged() {
        when(requisitionRepository.findAllByMemberId(MID)).thenReturn(List.of(
            Requisition.builder()
                .institutionName("BNP")
                .status(RequisitionStatus.EXPIRED)
                .lastSyncedAt(SYNCED_AT)
                .build()));
        when(boursoSyncService.getStatus(MID)).thenReturn(new BoursoSyncService.SessionStatusResponse(
            true, SidecarSyncStatus.FAILED, null, SYNCED_AT, SidecarErrorCode.SESSION_EXPIRED));
        when(degiroSessionRepository.findByMemberId(MID)).thenReturn(Optional.of(
            DegiroSession.builder()
                .status(DegiroSessionStatus.REAUTH_REQUIRED)
                .lastSyncedAt(SYNCED_AT)
                .sessionBlob("secret-blob")
                .build()));
        when(finarySessionRepository.findByMemberId(MID)).thenReturn(Optional.of(
            FinarySession.builder()
                .email("a@b.c")
                .password("super-secret-password")
                .status("CONNECTED")
                .lastSyncedAt(SYNCED_AT)
                .build()));
        when(ibkrConnectionRepository.findByMemberId(MID)).thenReturn(Optional.of(
            IbkrConnection.builder().token("secret-token").queryId("secret-query").status("CONNECTED").lastSyncedAt(SYNCED_AT).build()));
        when(walletAddressRepository.findAllByMemberId(MID)).thenReturn(List.of(
            WalletAddress.builder().chain(Chain.EVM).address("0xabc").lastSyncedAt(SYNCED_AT).build()));

        String text = service.describe(MID);

        assertThat(text).contains("enable-banking/BNP: NEEDS_REAUTH lastSync=2026-10-04T06:00:00Z reauth=true");
        assertThat(text).contains("bourso: NEEDS_REAUTH lastSync=2026-10-04T06:00:00Z reauth=true — SESSION_EXPIRED");
        assertThat(text).contains("degiro: NEEDS_REAUTH lastSync=2026-10-04T06:00:00Z reauth=true");
        assertThat(text).contains("finary: CONNECTED lastSync=2026-10-04T06:00:00Z reauth=false");
        assertThat(text).contains("wallets/EVM: CONNECTED lastSync=2026-10-04T06:00:00Z reauth=false");
        assertThat(text).doesNotContain("super-secret-password");
        assertThat(text).doesNotContain("secret-blob");
        assertThat(text).doesNotContain("secret-token");
        assertThat(text).doesNotContain("0xabc");
    }

    @Test
    void ibkrError_isFailedWithoutRequestingReauthentication() {
        when(ibkrConnectionRepository.findByMemberId(MID)).thenReturn(Optional.of(
            IbkrConnection.builder().status("ERROR").lastSyncedAt(SYNCED_AT).build()));

        String text = service.describe(MID);

        assertThat(text).contains("ibkr: FAILED lastSync=2026-10-04T06:00:00Z reauth=false");
        assertThat(text).doesNotContain("ibkr: CONNECTED");
    }

    @Test
    void simplefin_connectedRow_isConnectedWithItsLastSync() {
        when(simplefinConnectionRepository.findByMemberId(MID)).thenReturn(Optional.of(
            SimplefinConnection.builder().accessUrl("secret-ciphertext").status("CONNECTED").lastSyncedAt(SYNCED_AT).build()));

        String text = service.describe(MID);

        assertThat(text).contains("simplefin: CONNECTED lastSync=2026-10-04T06:00:00Z reauth=false");
        assertThat(text).doesNotContain("secret-ciphertext");
    }

    @Test
    void simplefin_errorRow_isFailedWithoutRequestingReauthentication() {
        when(simplefinConnectionRepository.findByMemberId(MID)).thenReturn(Optional.of(
            SimplefinConnection.builder().status("ERROR").lastSyncedAt(SYNCED_AT).build()));

        String text = service.describe(MID);

        assertThat(text).contains("simplefin: FAILED lastSync=2026-10-04T06:00:00Z reauth=false");
        assertThat(text).doesNotContain("simplefin: CONNECTED");
    }

    @Test
    void simplefin_noRow_isNotConnected() {
        assertThat(service.describe(MID)).contains("simplefin: NOT_CONNECTED lastSync=none reauth=false");
    }

    @Test
    void wise_connectedRow_isConnectedWithoutShowingTheToken() {
        when(wiseConnectionRepository.findByMemberId(MID)).thenReturn(Optional.of(
            WiseConnection.builder().apiToken("secret-ciphertext").status("CONNECTED").lastSyncedAt(SYNCED_AT).build()));

        String text = service.describe(MID);

        assertThat(text).contains("wise: CONNECTED lastSync=2026-10-04T06:00:00Z reauth=false");
        assertThat(text).doesNotContain("secret-ciphertext");
    }

    @Test
    void wise_errorRow_isFailed() {
        when(wiseConnectionRepository.findByMemberId(MID)).thenReturn(Optional.of(
            WiseConnection.builder().status("ERROR").lastSyncedAt(SYNCED_AT).build()));

        assertThat(service.describe(MID)).contains("wise: FAILED lastSync=2026-10-04T06:00:00Z reauth=false");
    }

    @Test
    void wise_refusedToken_needsReauth() {
        when(wiseConnectionRepository.findByMemberId(MID)).thenReturn(Optional.of(
            WiseConnection.builder().status("REAUTH_REQUIRED").lastSyncedAt(SYNCED_AT).build()));

        assertThat(service.describe(MID)).contains("wise: NEEDS_REAUTH lastSync=2026-10-04T06:00:00Z reauth=true");
    }

    @Test
    void wise_noRow_isNotConnected() {
        assertThat(service.describe(MID)).contains("wise: NOT_CONNECTED lastSync=none reauth=false");
    }

    @ParameterizedTest
    @MethodSource("amexCodes")
    void amexFailedStatusUsesOnlyTheExactSessionExpiryCodeForReauthentication(SidecarErrorCode error) {
        when(amexSyncService.getStatus(MID)).thenReturn(new AmexSyncService.SessionStatusResponse(
            false, SidecarSyncStatus.FAILED, null, SYNCED_AT, error));
        boolean reauth = error == SidecarErrorCode.SESSION_EXPIRED;
        String expectedStatus = reauth ? "NEEDS_REAUTH" : "FAILED";

        String text = service.describe(MID);

        assertThat(text).contains("amex: " + expectedStatus + " lastSync=2026-10-04T06:00:00Z reauth="
            + reauth + " — " + error.name());
    }

    static Stream<SidecarErrorCode> amexCodes() {
        return SidecarBank.AMEX.codes().stream();
    }

    @Test
    void oneUnreadableSource_doesNotHideTheNext() {
        when(boursoSyncService.getStatus(MID)).thenThrow(new RuntimeException("db down"));

        String text = service.describe(MID);

        assertThat(text).contains("bourso: FAILED lastSync=none reauth=false — status unreadable");
        assertThat(text).contains("amundi: NOT_CONNECTED lastSync=none reauth=false");
    }
}
