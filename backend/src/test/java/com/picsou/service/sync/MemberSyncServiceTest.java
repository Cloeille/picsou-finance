package com.picsou.service.sync;

import com.picsou.dto.FinaryAutoSyncResponse;
import com.picsou.finary.FinaryApiSyncService;
import com.picsou.service.*;
import com.picsou.service.WalletSyncService.ResyncSummary;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.UnexpectedRollbackException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MemberSyncServiceTest {

    @Mock private RevolutSyncService revolutSyncService;
    @Mock private SyncService syncService;
    @Mock private TradeRepublicSyncService trSyncService;
    @Mock private BoursoSyncService boursoSyncService;
    @Mock private BourseDirectSyncService bourseDirectSyncService;
    @Mock private AmundiSyncService amundiSyncService;
    @Mock private FortuneoSyncService fortuneoSyncService;
    @Mock private IbkrSyncService ibkrSyncService;
    @Mock private CryptoExchangeSyncService cryptoExchangeSyncService;
    @Mock private WalletSyncService walletSyncService;
    @Mock private FinaryApiSyncService finaryApiSyncService;
    @Mock private DegiroSyncService degiroSyncService;

    private MemberSyncService memberSyncService;

    @BeforeEach
    void setUp() {
        memberSyncService = new MemberSyncService(
            revolutSyncService, syncService, trSyncService, boursoSyncService,
            bourseDirectSyncService, amundiSyncService, fortuneoSyncService,
            ibkrSyncService, cryptoExchangeSyncService, walletSyncService,
            finaryApiSyncService, degiroSyncService
        );
    }

    @Test
    void resyncScheduled_executesInCanonicalOrder_revolutBeforeEnable() {
        Long memberId = 42L;
        when(revolutSyncService.resyncReporting(memberId)).thenReturn(success("revolut"));
        when(syncService.resyncAllReporting(memberId)).thenReturn(success("enable-banking"));
        when(syncService.retryFailedReporting(memberId)).thenReturn(success("enable-banking-retry"));
        when(trSyncService.resyncReporting(memberId)).thenReturn(success("trade-republic"));
        when(boursoSyncService.resyncReporting(memberId)).thenReturn(success("bourso"));
        when(bourseDirectSyncService.resyncReporting(memberId)).thenReturn(success("bourse-direct"));
        when(amundiSyncService.resyncReporting(memberId)).thenReturn(success("amundi"));
        when(fortuneoSyncService.resyncReporting(memberId)).thenReturn(success("fortuneo"));
        when(ibkrSyncService.resyncReporting(memberId)).thenReturn(success("ibkr"));
        when(cryptoExchangeSyncService.resyncAllReporting(memberId)).thenReturn(success("crypto-exchanges"));
        when(walletSyncService.resyncAll(memberId)).thenReturn(new ResyncSummary(1, 1, List.of()));
        when(finaryApiSyncService.autoSync(memberId)).thenReturn(new FinaryAutoSyncResponse("OK", 1, 0));
        // degiro not called

        List<SourceSyncResult> results = memberSyncService.resyncScheduled(memberId);

        assertEquals(13, results.size());
        assertEquals("revolut", results.get(0).source());
        assertEquals("enable-banking", results.get(1).source());
        assertEquals("enable-banking-retry", results.get(2).source());
        assertEquals("degiro", results.get(12).source());
        assertEquals(SourceSyncResult.Status.SKIPPED, results.get(12).status());

        InOrder inOrder = inOrder(revolutSyncService, syncService, trSyncService, boursoSyncService,
            bourseDirectSyncService, amundiSyncService, fortuneoSyncService, ibkrSyncService,
            cryptoExchangeSyncService, walletSyncService, finaryApiSyncService, degiroSyncService);
        inOrder.verify(revolutSyncService).resyncReporting(memberId);
        inOrder.verify(syncService).resyncAllReporting(memberId);
        inOrder.verify(syncService).retryFailedReporting(memberId);
        inOrder.verify(trSyncService).resyncReporting(memberId);
        verify(degiroSyncService, never()).userSyncReporting(anyLong());
        verify(degiroSyncService, never()).sync(anyLong());
    }

    @Test
    void failureOfOneSource_doesNotSkipNext_boursoFailAmundiCalled() {
        Long memberId = 1L;
        when(revolutSyncService.resyncReporting(memberId)).thenReturn(success("revolut"));
        when(syncService.resyncAllReporting(memberId)).thenReturn(success("enable-banking"));
        when(syncService.retryFailedReporting(memberId)).thenReturn(success("enable-banking-retry"));
        when(trSyncService.resyncReporting(memberId)).thenReturn(success("trade-republic"));
        when(boursoSyncService.resyncReporting(memberId)).thenThrow(new RuntimeException("bourso boom"));
        when(bourseDirectSyncService.resyncReporting(memberId)).thenReturn(success("bourse-direct"));
        when(amundiSyncService.resyncReporting(memberId)).thenReturn(success("amundi"));
        // stub rest minimally
        when(fortuneoSyncService.resyncReporting(memberId)).thenReturn(success("fortuneo"));
        when(ibkrSyncService.resyncReporting(memberId)).thenReturn(success("ibkr"));
        when(cryptoExchangeSyncService.resyncAllReporting(memberId)).thenReturn(success("crypto-exchanges"));
        when(walletSyncService.resyncAll(memberId)).thenReturn(new ResyncSummary(0,0,List.of()));
        when(finaryApiSyncService.autoSync(memberId)).thenReturn(new FinaryAutoSyncResponse("OK",0,0));

        List<SourceSyncResult> results = memberSyncService.resyncScheduled(memberId);

        assertTrue(results.stream().anyMatch(r -> "bourso".equals(r.source()) && r.status() == SourceSyncResult.Status.FAILED));
        assertTrue(results.stream().anyMatch(r -> "amundi".equals(r.source()) && r.status() == SourceSyncResult.Status.SYNCED));
        verify(amundiSyncService).resyncReporting(memberId);
        verify(bourseDirectSyncService).resyncReporting(memberId); // before bourso? wait order
    }

    @Test
    void ibkrWrapper_mapsUnexpectedRollbackToFailed_andContinues() {
        Long memberId = 7L;
        when(revolutSyncService.resyncReporting(memberId)).thenReturn(success("revolut"));
        when(syncService.resyncAllReporting(memberId)).thenReturn(success("enable-banking"));
        when(syncService.retryFailedReporting(memberId)).thenReturn(success("enable-banking-retry"));
        when(trSyncService.resyncReporting(memberId)).thenReturn(success("trade-republic"));
        when(boursoSyncService.resyncReporting(memberId)).thenReturn(success("bourso"));
        when(bourseDirectSyncService.resyncReporting(memberId)).thenReturn(success("bourse-direct"));
        when(amundiSyncService.resyncReporting(memberId)).thenReturn(success("amundi"));
        when(fortuneoSyncService.resyncReporting(memberId)).thenReturn(success("fortuneo"));
        when(ibkrSyncService.resyncReporting(memberId)).thenThrow(new UnexpectedRollbackException("proxy exit"));
        when(cryptoExchangeSyncService.resyncAllReporting(memberId)).thenReturn(success("crypto-exchanges"));
        when(walletSyncService.resyncAll(memberId)).thenReturn(new ResyncSummary(1,1,List.of()));
        when(finaryApiSyncService.autoSync(memberId)).thenReturn(new FinaryAutoSyncResponse("OK",1,0));

        List<SourceSyncResult> results = memberSyncService.resyncScheduled(memberId);

        SourceSyncResult ibkrRes = results.stream().filter(r -> "ibkr".equals(r.source())).findFirst().orElseThrow();
        assertEquals(SourceSyncResult.Status.FAILED, ibkrRes.status());
        // next still called
        verify(cryptoExchangeSyncService).resyncAllReporting(memberId);
    }

    @Test
    void degiro_absentFromScheduledPath_neverCallsSync() {
        Long memberId = 9L;
        // stub others to avoid NPE in execute
        when(revolutSyncService.resyncReporting(memberId)).thenReturn(success("revolut"));
        when(syncService.resyncAllReporting(memberId)).thenReturn(success("enable-banking"));
        when(syncService.retryFailedReporting(memberId)).thenReturn(success("enable-banking-retry"));
        when(trSyncService.resyncReporting(memberId)).thenReturn(success("trade-republic"));
        when(boursoSyncService.resyncReporting(memberId)).thenReturn(success("bourso"));
        when(bourseDirectSyncService.resyncReporting(memberId)).thenReturn(success("bourse-direct"));
        when(amundiSyncService.resyncReporting(memberId)).thenReturn(success("amundi"));
        when(fortuneoSyncService.resyncReporting(memberId)).thenReturn(success("fortuneo"));
        when(ibkrSyncService.resyncReporting(memberId)).thenReturn(success("ibkr"));
        when(cryptoExchangeSyncService.resyncAllReporting(memberId)).thenReturn(success("crypto-exchanges"));
        when(walletSyncService.resyncAll(memberId)).thenReturn(new ResyncSummary(0,0,List.of()));
        when(finaryApiSyncService.autoSync(memberId)).thenReturn(new FinaryAutoSyncResponse("OK",0,0));

        memberSyncService.resyncScheduled(memberId);

        verify(degiroSyncService, never()).userSyncReporting(anyLong());
        verify(degiroSyncService, never()).sync(anyLong());
    }

    @Test
    void degiro_userSync_active_reauth_absent() {
        Long memberId = 10L;
        // stub all to prevent null returns from unstubbed mocks
        when(revolutSyncService.resyncReporting(memberId)).thenReturn(success("revolut"));
        when(syncService.resyncAllReporting(memberId)).thenReturn(success("enable-banking"));
        when(syncService.retryFailedReporting(memberId)).thenReturn(success("enable-banking-retry"));
        when(trSyncService.resyncReporting(memberId)).thenReturn(success("trade-republic"));
        when(boursoSyncService.resyncReporting(memberId)).thenReturn(success("bourso"));
        when(bourseDirectSyncService.resyncReporting(memberId)).thenReturn(success("bourse-direct"));
        when(amundiSyncService.resyncReporting(memberId)).thenReturn(success("amundi"));
        when(fortuneoSyncService.resyncReporting(memberId)).thenReturn(success("fortuneo"));
        when(ibkrSyncService.resyncReporting(memberId)).thenReturn(success("ibkr"));
        when(cryptoExchangeSyncService.resyncAllReporting(memberId)).thenReturn(success("crypto-exchanges"));
        when(walletSyncService.resyncAll(memberId)).thenReturn(new ResyncSummary(0,0,List.of()));
        when(finaryApiSyncService.autoSync(memberId)).thenReturn(new FinaryAutoSyncResponse("OK",0,0));
        when(degiroSyncService.userSyncReporting(memberId))
            .thenReturn(new SourceSyncResult("degiro", SourceSyncResult.Status.SYNCED, ""));

        List<SourceSyncResult> forActive = memberSyncService.resyncForUser(memberId);
        assertTrue(forActive.stream().anyMatch(r -> r != null && "degiro".equals(r.source()) && r.status() == SourceSyncResult.Status.SYNCED));

        // simulate reauth case via mock
        when(degiroSyncService.userSyncReporting(memberId))
            .thenReturn(new SourceSyncResult("degiro", SourceSyncResult.Status.NEEDS_REAUTH, "Reauth required"));
        List<SourceSyncResult> forReauth = memberSyncService.resyncForUser(memberId);
        assertTrue(forReauth.stream().anyMatch(r -> r != null && r.status() == SourceSyncResult.Status.NEEDS_REAUTH));

        // absent
        when(degiroSyncService.userSyncReporting(memberId))
            .thenReturn(new SourceSyncResult("degiro", SourceSyncResult.Status.SKIPPED_NOT_CONNECTED, "No session"));
        List<SourceSyncResult> forAbsent = memberSyncService.resyncForUser(memberId);
        assertTrue(forAbsent.stream().anyMatch(r -> r != null && r.status() == SourceSyncResult.Status.SKIPPED_NOT_CONNECTED));
    }

    @Test
    void resyncForUser_withSourceFilter_executesOnlySubset_inOrder() {
        Long memberId = 11L;
        Set<String> filter = Set.of("revolut", "amundi");
        when(revolutSyncService.resyncReporting(memberId)).thenReturn(success("revolut"));
        when(amundiSyncService.resyncReporting(memberId)).thenReturn(success("amundi"));

        List<SourceSyncResult> results = memberSyncService.resyncForUser(memberId, filter);

        assertEquals(2, results.size());
        assertEquals("revolut", results.get(0).source());
        assertEquals("amundi", results.get(1).source());
        verify(revolutSyncService).resyncReporting(memberId);
        verify(amundiSyncService).resyncReporting(memberId);
        verifyNoInteractions(boursoSyncService, trSyncService); // etc
    }

    @Test
    void finaryNotConnected_isSkipped() {
        when(finaryApiSyncService.autoSync(42L))
            .thenReturn(new FinaryAutoSyncResponse("NOT_CONNECTED", 0, 0));

        List<SourceSyncResult> results = memberSyncService.resyncForUser(42L, Set.of("finary"));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.source()).isEqualTo("finary");
            assertThat(result.status()).isEqualTo(SourceSyncResult.Status.SKIPPED_NOT_CONNECTED);
        });
    }

    @Test
    void connectorFailure_logsThrowableAndContinuesToNextSource() {
        Long memberId = 42L;
        RuntimeException failure = new RuntimeException("connector failed");
        when(boursoSyncService.resyncReporting(memberId)).thenThrow(failure);
        when(bourseDirectSyncService.resyncReporting(memberId)).thenReturn(success("bourse-direct"));
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MemberSyncService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        try {
            List<SourceSyncResult> results = memberSyncService.resyncForUser(memberId, Set.of("bourso", "bourse-direct"));

            assertThat(results).anySatisfy(result -> {
                assertThat(result.source()).isEqualTo("bourso");
                assertThat(result.status()).isEqualTo(SourceSyncResult.Status.FAILED);
            });
            verify(bourseDirectSyncService).resyncReporting(memberId);
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getThrowableProxy().getMessage())
                .contains("connector failed"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void ibkrProxyExit_logsThrowableAndPreservesFailureResult() {
        Long memberId = 43L;
        UnexpectedRollbackException failure = new UnexpectedRollbackException("proxy exit");
        when(ibkrSyncService.resyncReporting(memberId)).thenThrow(failure);
        when(cryptoExchangeSyncService.resyncAllReporting(memberId)).thenReturn(success("crypto-exchanges"));
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MemberSyncService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        try {
            List<SourceSyncResult> results = memberSyncService.resyncForUser(memberId, Set.of("ibkr", "crypto-exchanges"));

            assertThat(results).anySatisfy(result -> {
                assertThat(result.source()).isEqualTo("ibkr");
                assertThat(result.status()).isEqualTo(SourceSyncResult.Status.FAILED);
            });
            verify(cryptoExchangeSyncService).resyncAllReporting(memberId);
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getThrowableProxy().getMessage())
                .contains("proxy exit"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void finaryFailure_logsThrowableAndPreservesFailureResult() {
        Long memberId = 44L;
        RuntimeException failure = new RuntimeException("Finary unavailable");
        when(finaryApiSyncService.autoSync(memberId)).thenThrow(failure);
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MemberSyncService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        try {
            List<SourceSyncResult> results = memberSyncService.resyncForUser(memberId, Set.of("finary"));

            assertThat(results).singleElement().satisfies(result -> {
                assertThat(result.source()).isEqualTo("finary");
                assertThat(result.status()).isEqualTo(SourceSyncResult.Status.FAILED);
            });
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getThrowableProxy().getMessage())
                .contains("Finary unavailable"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    private SourceSyncResult success(String source) {
        return new SourceSyncResult(source, SourceSyncResult.Status.SYNCED, "");
    }
}
