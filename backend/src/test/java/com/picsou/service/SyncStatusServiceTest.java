package com.picsou.service;

import com.picsou.model.DegiroSession;
import com.picsou.model.DegiroSessionStatus;
import com.picsou.model.FinarySession;
import com.picsou.model.IbkrConnection;
import com.picsou.model.Requisition;
import com.picsou.model.SimplefinConnection;
import com.picsou.model.RequisitionStatus;
import com.picsou.model.WalletAddress;
import com.picsou.model.Chain;
import com.picsou.port.BoursoErrorCode;
import com.picsou.repository.DegiroSessionRepository;
import com.picsou.repository.FinarySessionRepository;
import com.picsou.repository.IbkrConnectionRepository;
import com.picsou.repository.RequisitionRepository;
import com.picsou.repository.SimplefinConnectionRepository;
import com.picsou.repository.TradeRepublicSessionRepository;
import com.picsou.repository.WalletAddressRepository;
import com.picsou.model.BoursoSyncStatus;
import com.picsou.model.AmundiSyncStatus;
import com.picsou.model.BourseDirectSyncStatus;
import com.picsou.model.FortuneoSyncStatus;
import com.picsou.model.AmexSyncStatus;
import com.picsou.port.AmexErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncStatusServiceTest {

    private static final long MID = 7L;
    private static final Instant SYNCED_AT = Instant.parse("2026-10-04T06:00:00Z");

    @Mock RevolutSyncService revolutSyncService;
    @Mock RequisitionRepository requisitionRepository;
    @Mock TradeRepublicSyncService tradeRepublicSyncService;
    @Mock TradeRepublicSessionRepository tradeRepublicSessionRepository;
    @Mock BoursoSyncService boursoSyncService;
    @Mock BourseDirectSyncService bourseDirectSyncService;
    @Mock AmundiSyncService amundiSyncService;
    @Mock FortuneoSyncService fortuneoSyncService;
    @Mock AmexSyncService amexSyncService;
    @Mock IbkrConnectionRepository ibkrConnectionRepository;
    @Mock SimplefinConnectionRepository simplefinConnectionRepository;
    @Mock CryptoExchangeSyncService cryptoExchangeSyncService;
    @Mock WalletAddressRepository walletAddressRepository;
    @Mock FinarySessionRepository finarySessionRepository;
    @Mock DegiroSessionRepository degiroSessionRepository;

    private SyncStatusService service;

    @BeforeEach
    void setUp() {
        service = new SyncStatusService(
            revolutSyncService, requisitionRepository, tradeRepublicSyncService, tradeRepublicSessionRepository,
            boursoSyncService, bourseDirectSyncService, amundiSyncService, fortuneoSyncService,
            amexSyncService, ibkrConnectionRepository, simplefinConnectionRepository, cryptoExchangeSyncService,
            walletAddressRepository,
            finarySessionRepository, degiroSessionRepository);
        when(revolutSyncService.getStatus(MID)).thenReturn(new RevolutSyncService.StatusResponse(false, false, null));
        when(requisitionRepository.findAllByMemberId(MID)).thenReturn(List.of());
        when(tradeRepublicSyncService.getSessionStatus(MID))
            .thenReturn(new TradeRepublicSyncService.SessionStatusResponse(false, null));
        when(tradeRepublicSessionRepository.findByMemberId(MID)).thenReturn(Optional.empty());
        when(boursoSyncService.getStatus(MID)).thenReturn(
            new BoursoSyncService.SessionStatusResponse(false, BoursoSyncStatus.IDLE, null, null, null));
        when(bourseDirectSyncService.getStatus(MID)).thenReturn(
            new BourseDirectSyncService.SessionStatusResponse(false, null, BourseDirectSyncStatus.IDLE, null, null, null));
        when(amundiSyncService.getStatus(MID)).thenReturn(
            new AmundiSyncService.SessionStatusResponse(false, AmundiSyncStatus.IDLE, null, null, null));
        when(fortuneoSyncService.getStatus(MID)).thenReturn(
            new FortuneoSyncService.SessionStatusResponse(false, null, FortuneoSyncStatus.IDLE, null, null, null));
        when(amexSyncService.getStatus(MID)).thenReturn(
            new AmexSyncService.SessionStatusResponse(false, AmexSyncStatus.IDLE, null, null, null));
        when(ibkrConnectionRepository.findByMemberId(MID)).thenReturn(Optional.empty());
        when(simplefinConnectionRepository.findByMemberId(MID)).thenReturn(Optional.empty());
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
            true, AmexSyncStatus.QUEUED, null, SYNCED_AT, null));
        String queued = service.describe(MID);
        assertThat(queued).contains("amex: QUEUED lastSync=2026-10-04T06:00:00Z reauth=false");

        when(amexSyncService.getStatus(MID)).thenReturn(new AmexSyncService.SessionStatusResponse(
            false, AmexSyncStatus.FAILED, null, SYNCED_AT, AmexErrorCode.SESSION_EXPIRED));
        String expired = service.describe(MID);
        assertThat(expired).contains("amex: NEEDS_REAUTH lastSync=2026-10-04T06:00:00Z reauth=true — SESSION_EXPIRED");
    }

    @Test
    void missingConnections_areNamedNotHidden() {
        String text = service.describe(MID);

        assertThat(text).contains("revolut: NOT_CONNECTED lastSync=none reauth=false");
        assertThat(text).contains("enable-banking: NOT_CONNECTED lastSync=none reauth=false");
        assertThat(text).contains("amex: NOT_CONNECTED lastSync=none reauth=false");
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
            true, BoursoSyncStatus.FAILED, null, SYNCED_AT, BoursoErrorCode.SESSION_EXPIRED));
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

    @ParameterizedTest
    @EnumSource(AmexErrorCode.class)
    void amexFailedStatusUsesOnlyTheExactSessionExpiryCodeForReauthentication(AmexErrorCode error) {
        when(amexSyncService.getStatus(MID)).thenReturn(new AmexSyncService.SessionStatusResponse(
            false, AmexSyncStatus.FAILED, null, SYNCED_AT, error));
        boolean reauth = error == AmexErrorCode.SESSION_EXPIRED;
        String expectedStatus = reauth ? "NEEDS_REAUTH" : "FAILED";

        String text = service.describe(MID);

        assertThat(text).contains("amex: " + expectedStatus + " lastSync=2026-10-04T06:00:00Z reauth="
            + reauth + " — " + error.name());
    }

    @Test
    void oneUnreadableSource_doesNotHideTheNext() {
        when(boursoSyncService.getStatus(MID)).thenThrow(new RuntimeException("db down"));

        String text = service.describe(MID);

        assertThat(text).contains("bourso: FAILED lastSync=none reauth=false — status unreadable");
        assertThat(text).contains("amundi: NOT_CONNECTED lastSync=none reauth=false");
    }
}
