package com.picsou.service;

import com.picsou.model.DegiroSession;
import com.picsou.model.DegiroSessionStatus;
import com.picsou.model.FinarySession;
import com.picsou.model.IbkrConnection;
import com.picsou.model.Requisition;
import com.picsou.model.RequisitionStatus;
import com.picsou.model.WalletAddress;
import com.picsou.model.Chain;
import com.picsou.port.BoursoErrorCode;
import com.picsou.repository.DegiroSessionRepository;
import com.picsou.repository.FinarySessionRepository;
import com.picsou.repository.IbkrConnectionRepository;
import com.picsou.repository.RequisitionRepository;
import com.picsou.repository.TradeRepublicSessionRepository;
import com.picsou.repository.WalletAddressRepository;
import com.picsou.model.BoursoSyncStatus;
import com.picsou.model.AmundiSyncStatus;
import com.picsou.model.BourseDirectSyncStatus;
import com.picsou.model.FortuneoSyncStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    @Mock IbkrConnectionRepository ibkrConnectionRepository;
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
            ibkrConnectionRepository, cryptoExchangeSyncService, walletAddressRepository,
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
        when(ibkrConnectionRepository.findByMemberId(MID)).thenReturn(Optional.empty());
        when(cryptoExchangeSyncService.getStatus(MID)).thenReturn(List.of());
        when(walletAddressRepository.findAllByMemberId(MID)).thenReturn(List.of());
        when(finarySessionRepository.findByMemberId(MID)).thenReturn(Optional.empty());
        when(degiroSessionRepository.findByMemberId(MID)).thenReturn(Optional.empty());
    }

    @Test
    void missingConnections_areNamedNotHidden() {
        String text = service.describe(MID);

        assertThat(text).contains("revolut: NOT_CONNECTED lastSync=none reauth=false");
        assertThat(text).contains("enable-banking: NOT_CONNECTED lastSync=none reauth=false");
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
    void oneUnreadableSource_doesNotHideTheNext() {
        when(boursoSyncService.getStatus(MID)).thenThrow(new RuntimeException("db down"));

        String text = service.describe(MID);

        assertThat(text).contains("bourso: FAILED lastSync=none reauth=false — status unreadable");
        assertThat(text).contains("amundi: NOT_CONNECTED lastSync=none reauth=false");
    }
}
