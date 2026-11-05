package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.CorumSession;
import com.picsou.model.CorumSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.port.CorumPort;
import com.picsou.repository.CorumSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.ScpiPositionRepository;
import com.picsou.repository.ScpiProviderFundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class CorumSyncServiceTest {
    @Mock CorumPort port;
    @Mock CorumSessionRepository sessionRepository;
    @Mock ScpiPositionRepository positionRepository;
    @Mock ScpiPositionService positionService;
    @Mock FamilyMemberRepository memberRepository;
    @Mock CryptoEncryption encryption;
    @Mock AccountRepository accountRepository;
    @Mock ScpiProviderFundRepository fundRepository;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock AccountService accountService;
    @Mock CorumLifeOperationWriter operationWriter;
    CorumSyncService service;

    @BeforeEach
    void setUp() {
        lenient().when(sessionRepository.findMemberByIdForUpdate(7L))
            .thenReturn(Optional.of(FamilyMember.builder().id(7L).build()));
        lenient().doAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0))
            .doInTransaction(transactionStatus)).when(txTemplate).execute(any(TransactionCallback.class));
        lenient().doAnswer(inv -> {
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(transactionStatus);
            return null;
        }).when(txTemplate).executeWithoutResult(any());
        ScpiFundReconciler reconciler = new ScpiFundReconciler(
            positionRepository, positionService, accountRepository, fundRepository, memberRepository);
        lenient().when(operationWriter.reconcile(any(), any(), any(), any()))
            .thenReturn(CorumLifeOperationWriter.Result.KEPT);
        CorumLifeWriter lifeWriter = new CorumLifeWriter(
            accountRepository, holdingRepository, memberRepository, accountService, operationWriter);
        service = new CorumSyncService(port, sessionRepository, memberRepository, encryption,
            txTemplate, Runnable::run, reconciler, lifeWriter);
    }

    /**
     * A fetch that ends after the session was disconnected or replaced writes nothing, whether it returned a portfolio or failed.
     */
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void queueSync_discardsSnapshotAfterDisconnectOrReplacement(boolean replace, boolean remoteFailure) throws Exception {
        FamilyMember member = FamilyMember.builder().id(7L).build();
        CorumSession old = CorumSession.builder().id(3L).member(member)
            .sessionState("encrypted").build();
        AtomicReference<CorumSession> current = new AtomicReference<>(old);
        when(sessionRepository.findByMemberIdForUpdate(7L))
            .thenAnswer(inv -> Optional.ofNullable(current.get()));
        when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L))
            .thenAnswer(inv -> Optional.ofNullable(current.get()).filter(s -> s.getId().equals(3L)));
        when(sessionRepository.findByMemberId(7L))
            .thenAnswer(inv -> Optional.ofNullable(current.get()));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");
        doAnswer(inv -> { current.set(null); return null; }).when(sessionRepository).delete(old);
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(port.fetchPortfolio("plain-state")).thenAnswer(inv -> {
            fetching.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            if (remoteFailure) {
                throw new SyncException("Expired while fetching", null, "SESSION_EXPIRED");
            }
            return realEstateOnly(new CorumPort.Snapshot("contract", "PP", "EUR", new BigDecimal("600"), null,
                true, List.of(new CorumPort.Holding("FUND", "Test fund", new BigDecimal("2"),
                    new BigDecimal("300"), null, null, null))));
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { service.queueSync(7L); } catch (Throwable ex) { failure.set(ex); }
        });
        worker.start();
        try {
            assertThat(fetching.await(5, TimeUnit.SECONDS)).isTrue();
            service.clearSession(7L);
            if (replace) {
                current.set(CorumSession.builder().id(4L).member(member)
                    .sessionState("new-session").build());
            }
            clearInvocations(sessionRepository);
        } finally {
            release.countDown();
            worker.join(5000);
        }
        assertThat(worker.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        var order = inOrder(sessionRepository);
        order.verify(sessionRepository).findMemberByIdForUpdate(7L);
        order.verify(sessionRepository).findByIdAndMemberIdForUpdate(3L, 7L);
        verifyNoInteractions(positionRepository, positionService);
        verify(sessionRepository, never()).save(any());
        assertThat(old.getSyncStatus()).isEqualTo(CorumSyncStatus.RUNNING);
        if (replace) {
            assertThat(current.get().getSyncStatus()).isEqualTo(CorumSyncStatus.IDLE);
        }
    }

    @org.junit.jupiter.api.Test
    void queueSync_refusesASnapshotWhoseDisplayedValuesMissTheEnvelope() {
        CorumSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(realEstateOnly(new CorumPort.Snapshot(
            "contract", "PP", "EUR", new BigDecimal("600.00"), null, true,
            List.of(holding("US", "100.00", "150"))
        )));

        service.queueSync(7L);

        verifyNoInteractions(positionRepository, positionService);
        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.CorumErrorCode.PORTFOLIO_INCOMPLETE);
    }

    @org.junit.jupiter.api.Test
    void queueSync_acceptsDisplayedTotalEvenWhenWithdrawalValueIsLower() {
        CorumSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(realEstateOnly(new CorumPort.Snapshot(
            "contract", "PP", "EUR", new BigDecimal("340.00"), null, true,
            List.of(holding("US", "340.00", "150"))
        )));
        // A linked position always has its account (account_id is NOT NULL).
        com.picsou.model.ScpiPosition linked = com.picsou.model.ScpiPosition.builder()
            .account(com.picsou.model.Account.builder().id(11L).build())
            .corumFundCode("US")
            .build();
        when(positionRepository.findByMemberIdAndCorumFundCode(7L, "US"))
            .thenReturn(Optional.of(linked));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.SUCCESS);
        verify(positionService).applySyncedPosition(
            any(), eq(new BigDecimal("2")), eq(new BigDecimal("170")),
            eq(new BigDecimal("150")), eq(null)
        );
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"USD", "eur", ""})
    void queueSync_refusesASnapshotNotInEuros(String currency) {
        CorumSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(realEstateOnly(new CorumPort.Snapshot(
            "contract", "PP", currency, new BigDecimal("340.00"), null, true,
            List.of(holding("US", "340.00", "150"))
        )));

        service.queueSync(7L);

        verifyNoInteractions(positionRepository, positionService);
        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.CorumErrorCode.INVALID_DATA);
    }

    /** A portfolio with life contracts only is written without touching the SCPI funds. */
    @org.junit.jupiter.api.Test
    void queueSync_lifeOnlyPortfolioSkipsTheScpiReconciler() {
        CorumSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(
            new CorumPort.Portfolio(null, List.of(lifeContract("1001", "518.61"))));
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        when(accountRepository.save(any(com.picsou.model.Account.class))).thenAnswer(inv -> inv.getArgument(0));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.SUCCESS);
        verifyNoInteractions(positionRepository, positionService, fundRepository);
        verify(accountRepository).save(argThat(account ->
            "corum_life_1001".equals(account.getExternalAccountId())
                && account.getType() == com.picsou.model.AccountType.ASSURANCE_VIE));
    }

    @org.junit.jupiter.api.Test
    void queueSync_refusesAPortfolioWithNeitherPart() {
        CorumSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(new CorumPort.Portfolio(null, List.of()));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.CorumErrorCode.PORTFOLIO_INCOMPLETE);
        verifyNoInteractions(accountRepository, positionRepository, positionService, fundRepository);
    }

    /** All-or-nothing: a refused life contract must not leave the SCPI funds written. */
    @org.junit.jupiter.api.Test
    void queueSync_aRefusedLifeContractWritesNoScpiFundEither() {
        CorumSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(new CorumPort.Portfolio(
            new CorumPort.Snapshot("contract", "PP", "EUR", new BigDecimal("340.00"), null, true,
                List.of(holding("US", "340.00", "150"))),
            // The line misses the contract total by far more than the allowance.
            List.of(lifeContract("1001", "400.00"))));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.CorumErrorCode.PORTFOLIO_INCOMPLETE);
        verifyNoInteractions(accountRepository, positionRepository, positionService, fundRepository);
    }

    @org.junit.jupiter.api.Test
    void queueSync_aRefusedRealEstateContractWritesNoLifeContractEither() {
        CorumSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(new CorumPort.Portfolio(
            new CorumPort.Snapshot("contract", "PP", "EUR", new BigDecimal("600.00"), null, true,
                List.of(holding("US", "100.00", "150"))),
            List.of(lifeContract("1001", "518.61"))));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.CorumErrorCode.PORTFOLIO_INCOMPLETE);
        verifyNoInteractions(accountRepository, holdingRepository, accountService,
            positionRepository, positionService, fundRepository);
    }

    @org.junit.jupiter.api.Test
    void queueSync_writesBothPartsInOneRun() {
        CorumSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(new CorumPort.Portfolio(
            new CorumPort.Snapshot("contract", "PP", "EUR", new BigDecimal("340.00"), null, true,
                List.of(holding("US", "340.00", "150"))),
            List.of(lifeContract("1001", "518.61"))));
        com.picsou.model.ScpiPosition linked = com.picsou.model.ScpiPosition.builder()
            .account(com.picsou.model.Account.builder().id(11L).build())
            .corumFundCode("US")
            .build();
        when(positionRepository.findByMemberIdAndCorumFundCode(7L, "US")).thenReturn(Optional.of(linked));
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        when(accountRepository.save(any(com.picsou.model.Account.class))).thenAnswer(inv -> inv.getArgument(0));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.SUCCESS);
        verify(positionService).applySyncedPosition(any(), eq(new BigDecimal("2")), eq(new BigDecimal("170")),
            eq(new BigDecimal("150")), eq(null));
        verify(holdingRepository).saveAll(any());
    }

    @org.junit.jupiter.api.Test
    void queueSync_reportsALifeContractLeftUnwrittenNextToAManualAccount() {
        CorumSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(new CorumPort.Portfolio(
            null, List.of(lifeContract("1001", "518.61"))));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(
            com.picsou.model.Account.builder().id(40L).name("CORUM Life")
                .type(com.picsou.model.AccountType.ASSURANCE_VIE).build(),
            com.picsou.model.Account.builder().id(41L).name("CORUM Life")
                .type(com.picsou.model.AccountType.ASSURANCE_VIE).build()));

        service.queueSync(7L);

        // The sync itself succeeded; the status says one contract was written nowhere.
        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.SUCCESS);
        assertThat(service.getStatus(7L).lifeAmbiguousCount()).isEqualTo(1);
        verify(accountRepository, never()).save(any(com.picsou.model.Account.class));
    }

    /** An operation list that cannot be keyed is dropped; the valuation still syncs. */
    @org.junit.jupiter.api.Test
    void queueSync_aDuplicateLifeOperationKeepsTheStoredRowsAndSyncsTheValuation() {
        CorumSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(new CorumPort.Portfolio(null,
            // Same id and same sign twice: no suffix can tell them apart.
            List.of(lifeContract("1001", "518.61", List.of(operation("5", "100"), operation("5", "100"))))));
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        when(accountRepository.save(any(com.picsou.model.Account.class))).thenAnswer(inv -> inv.getArgument(0));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.SUCCESS);
        verify(accountRepository).save(argThat(account ->
            "corum_life_1001".equals(account.getExternalAccountId())
                && new BigDecimal("518.61").compareTo(account.getCurrentBalance()) == 0));
        verify(operationWriter).reconcile(eq(7L), any(), isNull(), any());
    }

    /** A failed operation write fails the run; the transaction (real in the Postgres test) undoes the rest. */
    @org.junit.jupiter.api.Test
    void queueSync_aFailedOperationWriteFailsTheRun() {
        CorumSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(new CorumPort.Portfolio(
            null, List.of(lifeContract("1001", "518.61", List.of(operation("5", "-100"), operation("5", "100"))))));
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        when(accountRepository.save(any(com.picsou.model.Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(operationWriter.reconcile(eq(7L), any(), any(), any()))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("refused"));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.CorumErrorCode.INTERNAL_ERROR);
    }

    @org.junit.jupiter.api.Test
    void queueSync_refusesAPortfolioWithoutItsLifeList() {
        CorumSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(new CorumPort.Portfolio(
            new CorumPort.Snapshot("contract", "PP", "EUR", new BigDecimal("340.00"), null, true,
                List.of(holding("US", "340.00", "150"))),
            null));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.CorumErrorCode.INVALID_DATA);
        verifyNoInteractions(positionRepository, positionService, fundRepository);
    }

    // --- Remembered credentials and the unattended re-login -------------------

    @org.junit.jupiter.api.Test
    void authenticate_withRememberStoresTheEncryptedCredentials() throws Exception {
        stubPassThroughEncryption();
        AtomicReference<CorumSession> stored = captureStoredSession();
        when(port.authenticate("client", PASSWORD)).thenReturn("plain-state");
        when(port.fetchPortfolio("plain-state")).thenReturn(lifeOnly());
        stubLifeWrite();

        CorumSyncService.SessionStatusResponse status = service.authenticate("client", PASSWORD, true, 7L);

        String encrypted = stored.get().getEncryptedCredentials();
        assertThat(encrypted).startsWith("enc:");
        com.fasterxml.jackson.databind.JsonNode credentials =
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(encrypted.substring(4));
        assertThat(credentials.path("login").asText()).isEqualTo("client");
        assertThat(credentials.path("password").asText()).isEqualTo(PASSWORD);
        assertThat(status.credentialsRemembered()).isTrue();
        assertThat(status.toString()).doesNotContain(PASSWORD);
    }

    @org.junit.jupiter.api.Test
    void authenticate_withoutRememberStoresNoCredentials() {
        stubPassThroughEncryption();
        AtomicReference<CorumSession> stored = captureStoredSession();
        when(port.authenticate("client", PASSWORD)).thenReturn("plain-state");
        when(port.fetchPortfolio("plain-state")).thenReturn(lifeOnly());
        stubLifeWrite();

        CorumSyncService.SessionStatusResponse status = service.authenticate("client", PASSWORD, false, 7L);

        assertThat(stored.get().getEncryptedCredentials()).isNull();
        assertThat(status.credentialsRemembered()).isFalse();
        verify(encryption, never()).encrypt(contains(PASSWORD));
    }

    /** A login that lands after a disconnect must not bring the session or its password back. */
    @org.junit.jupiter.api.Test
    void authenticate_landingAfterADisconnectStoresNothing() {
        when(port.authenticate("client", PASSWORD)).thenAnswer(inv -> {
            service.clearSession(7L);
            return "plain-state";
        });

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.authenticate("client", PASSWORD, true, 7L))
            .isInstanceOf(SyncException.class)
            .extracting(ex -> ((SyncException) ex).getCode()).isEqualTo("SESSION_EXPIRED");
        verify(sessionRepository, never()).saveAndFlush(any());
        verify(encryption, never()).encrypt(any());
    }

    @org.junit.jupiter.api.Test
    void clearSession_deletesTheSessionAndItsCredentials() {
        CorumSession session = CorumSession.builder().id(3L).member(FamilyMember.builder().id(7L).build())
            .sessionState("encrypted").encryptedCredentials(REMEMBERED).build();
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));

        service.clearSession(7L);

        verify(sessionRepository).delete(session);
    }

    @org.junit.jupiter.api.Test
    void queueSync_expiredSessionLogsInAgainWithTheStoredCredentialsAndSyncs() {
        CorumSession session = rememberedSession();
        stubPassThroughEncryption();
        when(port.fetchPortfolio("plain-state"))
            .thenThrow(new SyncException("The CORUM session expired", null, "SESSION_EXPIRED"));
        when(port.authenticate("client", PASSWORD)).thenReturn("fresh-state");
        when(port.fetchPortfolio("fresh-state")).thenReturn(lifeOnly());
        stubLifeWrite();

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.SUCCESS);
        assertThat(session.isActive()).isTrue();
        assertThat(session.getSessionState()).isEqualTo("enc:fresh-state");
        assertThat(session.getEncryptedCredentials()).isEqualTo(REMEMBERED);
        verify(port, times(1)).authenticate("client", PASSWORD);
    }

    @org.junit.jupiter.api.Test
    void queueSync_aRejectedPasswordClearsTheCredentialsAndDeactivatesTheSession() {
        CorumSession session = rememberedSession();
        when(port.fetchPortfolio("plain-state"))
            .thenThrow(new SyncException("The CORUM session expired", null, "SESSION_EXPIRED"));
        when(port.authenticate("client", PASSWORD))
            .thenThrow(new SyncException("CORUM rejected the credentials", null, "INVALID_CREDENTIALS"));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.CorumErrorCode.INVALID_CREDENTIALS);
        assertThat(session.isActive()).isFalse();
        assertThat(session.getEncryptedCredentials()).isNull();
        assertThat(service.getStatus(7L).credentialsRemembered()).isFalse();
    }

    @org.junit.jupiter.api.Test
    void queueSync_expiredSessionWithoutCredentialsWaitsForTheMember() {
        CorumSession session = activeSession();
        when(port.fetchPortfolio("plain-state"))
            .thenThrow(new SyncException("The CORUM session expired", null, "SESSION_EXPIRED"));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.CorumErrorCode.SESSION_EXPIRED);
        assertThat(session.isActive()).isFalse();
        verify(port, never()).authenticate(any(), any());
    }

    /** One re-login per run: a fresh session that expires too ends the run instead of looping. */
    @org.junit.jupiter.api.Test
    void queueSync_logsInAgainAtMostOncePerRun() {
        CorumSession session = rememberedSession();
        stubPassThroughEncryption();
        when(port.fetchPortfolio(any()))
            .thenThrow(new SyncException("The CORUM session expired", null, "SESSION_EXPIRED"));
        when(port.authenticate("client", PASSWORD)).thenReturn("fresh-state");

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.CorumErrorCode.SESSION_EXPIRED);
        verify(port, times(1)).authenticate(any(), any());
        verify(port, times(2)).fetchPortfolio(any());
    }

    /** Real AES-GCM: what is stored is ciphertext, and the re-login reads back the same login and password. */
    @org.junit.jupiter.api.Test
    void rememberedCredentialsRoundTripThroughTheRealEncryption() {
        CryptoEncryption real = new CryptoEncryption(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        ScpiFundReconciler reconciler = new ScpiFundReconciler(
            positionRepository, positionService, accountRepository, fundRepository, memberRepository);
        CorumSyncService realService = new CorumSyncService(port, sessionRepository, memberRepository, real,
            txTemplate, Runnable::run, reconciler, new CorumLifeWriter(
                accountRepository, holdingRepository, memberRepository, accountService, operationWriter));
        AtomicReference<CorumSession> stored = captureStoredSession();
        when(port.authenticate("client", PASSWORD)).thenReturn("first-state", "fresh-state");
        when(port.fetchPortfolio(any())).thenAnswer(inv -> {
            if ("fresh-state".equals(inv.getArgument(0))) return lifeOnly();
            throw new SyncException("The CORUM session expired", null, "SESSION_EXPIRED");
        });
        stubLifeWrite();

        realService.authenticate("client", PASSWORD, true, 7L);
        CorumSession session = stored.get();
        assertThat(session.getEncryptedCredentials()).doesNotContain(PASSWORD).doesNotContain("client");
        assertThat(real.decrypt(session.getEncryptedCredentials())).contains(PASSWORD);

        // The first read expired; the re-login used the decrypted credentials.
        verify(port, times(2)).authenticate("client", PASSWORD);
        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.SUCCESS);
        assertThat(real.decrypt(session.getSessionState())).isEqualTo("fresh-state");
    }

    /** Neither a log message nor a logged exception from a failed re-login contains the remembered password. */
    @org.junit.jupiter.api.Test
    void thePasswordNeverReachesTheLogs() {
        rememberedSession();
        stubPassThroughEncryption();
        when(port.fetchPortfolio("plain-state"))
            .thenThrow(new SyncException("The CORUM session expired", null, "SESSION_EXPIRED"));
        when(port.authenticate("client", PASSWORD))
            .thenThrow(new SyncException("CORUM rejected the credentials", null, "INVALID_CREDENTIALS"));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CorumSyncService.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            service.queueSync(7L);
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(PASSWORD);
            if (event.getThrowableProxy() != null) {
                assertThat(event.getThrowableProxy().getMessage()).doesNotContain(PASSWORD);
            }
        });
    }

    @org.junit.jupiter.api.Test
    void resyncReporting_queuesAnActiveSessionAndReportsAnInactiveOne() {
        CorumSession session = rememberedSession();
        stubPassThroughEncryption();
        when(port.fetchPortfolio("plain-state")).thenReturn(lifeOnly());
        stubLifeWrite();
        assertThat(service.resyncReporting(7L).status())
            .isEqualTo(com.picsou.service.sync.SourceSyncResult.Status.QUEUED);
        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.SUCCESS);

        session.markQueued();
        session.markFailed(com.picsou.port.CorumErrorCode.INVALID_CREDENTIALS, java.time.Instant.now());
        var rejected = service.resyncReporting(7L);
        assertThat(rejected.status()).isEqualTo(com.picsou.service.sync.SourceSyncResult.Status.NEEDS_REAUTH);
        assertThat(rejected.message()).isEqualTo("INVALID_CREDENTIALS");

        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        assertThat(service.resyncReporting(7L).status())
            .isEqualTo(com.picsou.service.sync.SourceSyncResult.Status.SKIPPED_NOT_CONNECTED);
    }

    private static final String PASSWORD = "s3cret-Pa55";
    private static final String REMEMBERED = "remembered-credentials";

    private CorumSession rememberedSession() {
        CorumSession session = activeSession();
        session.setEncryptedCredentials(REMEMBERED);
        lenient().when(encryption.decrypt(REMEMBERED))
            .thenReturn("{\"login\":\"client\",\"password\":\"" + PASSWORD + "\"}");
        return session;
    }

    private void stubPassThroughEncryption() {
        lenient().when(encryption.encrypt(any())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        lenient().when(encryption.decrypt(startsWith("enc:")))
            .thenAnswer(inv -> ((String) inv.getArgument(0)).substring(4));
    }

    /** The session {@code authenticate} inserts, answered back by every later lookup. */
    private AtomicReference<CorumSession> captureStoredSession() {
        AtomicReference<CorumSession> stored = new AtomicReference<>();
        when(memberRepository.findById(7L)).thenReturn(Optional.of(FamilyMember.builder().id(7L).build()));
        when(sessionRepository.saveAndFlush(any(CorumSession.class))).thenAnswer(inv -> {
            CorumSession session = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(session, "id", 3L);
            stored.set(session);
            return session;
        });
        lenient().when(sessionRepository.findByMemberIdForUpdate(7L))
            .thenAnswer(inv -> Optional.ofNullable(stored.get()));
        lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L))
            .thenAnswer(inv -> Optional.ofNullable(stored.get()));
        lenient().when(sessionRepository.findByMemberId(7L))
            .thenAnswer(inv -> Optional.ofNullable(stored.get()));
        return stored;
    }

    private static CorumPort.Portfolio lifeOnly() {
        return new CorumPort.Portfolio(null, List.of(lifeContract("1001", "518.61")));
    }

    private void stubLifeWrite() {
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        when(accountRepository.save(any(com.picsou.model.Account.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private CorumSession activeSession() {
        FamilyMember member = FamilyMember.builder().id(7L).build();
        CorumSession session = CorumSession.builder().id(3L).member(member)
            .sessionState("encrypted").build();
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");
        return session;
    }

    private static CorumPort.Portfolio realEstateOnly(CorumPort.Snapshot snapshot) {
        return new CorumPort.Portfolio(snapshot, List.of());
    }

    /** One CORUM XL line declared at 518.61, valued at {@code lineValue}. */
    private static CorumPort.LifeContract lifeContract(String code, String lineValue) {
        return lifeContract(code, lineValue, null);
    }

    private static CorumPort.LifeContract lifeContract(
        String code, String lineValue, List<CorumPort.LifeOperation> operations
    ) {
        return new CorumPort.LifeContract(code, "EUR", new BigDecimal("518.61"), LocalDate.of(2026, 11, 4), 1,
            null, null, null, null, true,
            List.of(new CorumPort.LifeHolding("XL", "CORUM XL", new BigDecimal("2.65955"), new BigDecimal("195"),
                new BigDecimal(lineValue), new BigDecimal("480"), LocalDate.of(2026, 11, 4), false, "realEstate")),
            operations);
    }

    private static CorumPort.LifeOperation operation(String id, String amount) {
        return new CorumPort.LifeOperation(id, "arbitrage", "arbitrage", LocalDate.of(2026, 6, 15),
            new BigDecimal(amount), null);
    }

    private static CorumPort.Holding holding(String code, String displayed, String withdrawal) {
        return new CorumPort.Holding(
            code, "CORUM " + code, new BigDecimal("2"),
            new BigDecimal(withdrawal), new BigDecimal("170"),
            new BigDecimal(displayed), LocalDate.of(2026, 9, 26)
        );
    }
}
