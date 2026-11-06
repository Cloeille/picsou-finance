package com.picsou.service;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.adapter.CorumAdapter;
import com.picsou.port.SidecarErrorCode;
import com.picsou.repository.SidecarSessionRepository;
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
    @Mock CorumAdapter port;
    @Mock SidecarSessionRepository sessionRepository;
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
    /** The real bounded store, as {@code RateLimitConfig} builds it; fresh per test. */
    java.util.Map<Long, io.github.bucket4j.Bucket> reloginBuckets = new com.picsou.config.RateLimitConfig().corumReloginBuckets();
    CorumSyncService service;

    @BeforeEach
    void setUp() {
        lenient().when(memberRepository.findByIdForUpdate(7L))
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
            txTemplate, Runnable::run, reconciler, lifeWriter, reloginBuckets);
    }

    /**
     * A fetch that ends after the session was disconnected or replaced writes nothing, whether it returned a portfolio or failed.
     */
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void queueSync_discardsSnapshotAfterDisconnectOrReplacement(boolean replace, boolean remoteFailure) throws Exception {
        FamilyMember member = FamilyMember.builder().id(7L).build();
        SidecarSession old = SidecarSession.builder().bank(SidecarBank.CORUM).id(3L).member(member)
            .sessionState("encrypted").build();
        AtomicReference<SidecarSession> current = new AtomicReference<>(old);
        when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.CORUM, 7L))
            .thenAnswer(inv -> Optional.ofNullable(current.get()));
        when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L))
            .thenAnswer(inv -> Optional.ofNullable(current.get()).filter(s -> s.getId().equals(3L)));
        when(sessionRepository.findByBankAndMemberId(SidecarBank.CORUM, 7L))
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
            return realEstateOnly(new CorumAdapter.Snapshot("contract", "PP", "EUR", new BigDecimal("600"), null,
                true, List.of(new CorumAdapter.Holding("FUND", "Test fund", new BigDecimal("2"),
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
                current.set(SidecarSession.builder().bank(SidecarBank.CORUM).id(4L).member(member)
                    .sessionState("new-session").build());
            }
            clearInvocations(sessionRepository, memberRepository);
        } finally {
            release.countDown();
            worker.join(5000);
        }
        assertThat(worker.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        var order = inOrder(memberRepository, sessionRepository);
        order.verify(memberRepository).findByIdForUpdate(7L);
        order.verify(sessionRepository).findByIdAndMemberIdForUpdate(3L, 7L);
        verifyNoInteractions(positionRepository, positionService);
        verify(sessionRepository, never()).save(any());
        assertThat(old.getSyncStatus()).isEqualTo(SidecarSyncStatus.RUNNING);
        if (replace) {
            assertThat(current.get().getSyncStatus()).isEqualTo(SidecarSyncStatus.IDLE);
        }
    }

    @org.junit.jupiter.api.Test
    void queueSync_refusesASnapshotWhoseDisplayedValuesMissTheEnvelope() {
        SidecarSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(realEstateOnly(new CorumAdapter.Snapshot(
            "contract", "PP", "EUR", new BigDecimal("600.00"), null, true,
            List.of(holding("US", "100.00", "150"))
        )));

        service.queueSync(7L);

        verifyNoInteractions(positionRepository, positionService);
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.SidecarErrorCode.PORTFOLIO_INCOMPLETE);
    }

    @org.junit.jupiter.api.Test
    void queueSync_acceptsDisplayedTotalEvenWhenWithdrawalValueIsLower() {
        SidecarSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(realEstateOnly(new CorumAdapter.Snapshot(
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

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        verify(positionService).applySyncedPosition(
            any(), eq(new BigDecimal("2")), eq(new BigDecimal("170")),
            eq(new BigDecimal("150")), eq(null)
        );
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"USD", "eur", ""})
    void queueSync_refusesASnapshotNotInEuros(String currency) {
        SidecarSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(realEstateOnly(new CorumAdapter.Snapshot(
            "contract", "PP", currency, new BigDecimal("340.00"), null, true,
            List.of(holding("US", "340.00", "150"))
        )));

        service.queueSync(7L);

        verifyNoInteractions(positionRepository, positionService);
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.SidecarErrorCode.INVALID_DATA);
    }

    /** A portfolio with life contracts only is written without touching the SCPI funds. */
    @org.junit.jupiter.api.Test
    void queueSync_lifeOnlyPortfolioSkipsTheScpiReconciler() {
        SidecarSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(
            new CorumAdapter.Portfolio(null, List.of(lifeContract("1001", "518.61"))));
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        when(accountRepository.save(any(com.picsou.model.Account.class))).thenAnswer(inv -> inv.getArgument(0));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        verifyNoInteractions(positionRepository, positionService, fundRepository);
        verify(accountRepository).save(argThat(account ->
            "corum_life_1001".equals(account.getExternalAccountId())
                && account.getType() == com.picsou.model.AccountType.ASSURANCE_VIE));
    }

    @org.junit.jupiter.api.Test
    void queueSync_refusesAPortfolioWithNeitherPart() {
        SidecarSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(new CorumAdapter.Portfolio(null, List.of()));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.SidecarErrorCode.PORTFOLIO_INCOMPLETE);
        verifyNoInteractions(accountRepository, positionRepository, positionService, fundRepository);
    }

    /** All-or-nothing: a refused life contract must not leave the SCPI funds written. */
    @org.junit.jupiter.api.Test
    void queueSync_aRefusedLifeContractWritesNoScpiFundEither() {
        SidecarSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(new CorumAdapter.Portfolio(
            new CorumAdapter.Snapshot("contract", "PP", "EUR", new BigDecimal("340.00"), null, true,
                List.of(holding("US", "340.00", "150"))),
            // The line misses the contract total by far more than the allowance.
            List.of(lifeContract("1001", "400.00"))));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.SidecarErrorCode.PORTFOLIO_INCOMPLETE);
        verifyNoInteractions(accountRepository, positionRepository, positionService, fundRepository);
    }

    @org.junit.jupiter.api.Test
    void queueSync_aRefusedRealEstateContractWritesNoLifeContractEither() {
        SidecarSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(new CorumAdapter.Portfolio(
            new CorumAdapter.Snapshot("contract", "PP", "EUR", new BigDecimal("600.00"), null, true,
                List.of(holding("US", "100.00", "150"))),
            List.of(lifeContract("1001", "518.61"))));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.SidecarErrorCode.PORTFOLIO_INCOMPLETE);
        verifyNoInteractions(accountRepository, holdingRepository, accountService,
            positionRepository, positionService, fundRepository);
    }

    @org.junit.jupiter.api.Test
    void queueSync_writesBothPartsInOneRun() {
        SidecarSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(new CorumAdapter.Portfolio(
            new CorumAdapter.Snapshot("contract", "PP", "EUR", new BigDecimal("340.00"), null, true,
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

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        verify(positionService).applySyncedPosition(any(), eq(new BigDecimal("2")), eq(new BigDecimal("170")),
            eq(new BigDecimal("150")), eq(null));
        verify(holdingRepository).saveAll(any());
    }

    @org.junit.jupiter.api.Test
    void queueSync_reportsALifeContractLeftUnwrittenNextToAManualAccount() {
        SidecarSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(new CorumAdapter.Portfolio(
            null, List.of(lifeContract("1001", "518.61"))));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(
            com.picsou.model.Account.builder().id(40L).name("CORUM Life")
                .type(com.picsou.model.AccountType.ASSURANCE_VIE).build(),
            com.picsou.model.Account.builder().id(41L).name("CORUM Life")
                .type(com.picsou.model.AccountType.ASSURANCE_VIE).build()));

        service.queueSync(7L);

        // The sync itself succeeded; the status says one contract was written nowhere.
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        assertThat(service.getStatus(7L).lifeAmbiguousCount()).isEqualTo(1);
        verify(accountRepository, never()).save(any(com.picsou.model.Account.class));
    }

    /** An operation list that cannot be keyed is dropped; the valuation still syncs. */
    @org.junit.jupiter.api.Test
    void queueSync_aDuplicateLifeOperationKeepsTheStoredRowsAndSyncsTheValuation() {
        SidecarSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(new CorumAdapter.Portfolio(null,
            // Same id and same sign twice: no suffix can tell them apart.
            List.of(lifeContract("1001", "518.61", List.of(operation("5", "100"), operation("5", "100"))))));
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        when(accountRepository.save(any(com.picsou.model.Account.class))).thenAnswer(inv -> inv.getArgument(0));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        verify(accountRepository).save(argThat(account ->
            "corum_life_1001".equals(account.getExternalAccountId())
                && new BigDecimal("518.61").compareTo(account.getCurrentBalance()) == 0));
        verify(operationWriter).reconcile(eq(7L), any(), isNull(), any());
    }

    /** A failed operation write fails the run; the transaction (real in the Postgres test) undoes the rest. */
    @org.junit.jupiter.api.Test
    void queueSync_aFailedOperationWriteFailsTheRun() {
        SidecarSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(new CorumAdapter.Portfolio(
            null, List.of(lifeContract("1001", "518.61", List.of(operation("5", "-100"), operation("5", "100"))))));
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        when(accountRepository.save(any(com.picsou.model.Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(operationWriter.reconcile(eq(7L), any(), any(), any()))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("refused"));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.SidecarErrorCode.INTERNAL_ERROR);
    }

    @org.junit.jupiter.api.Test
    void queueSync_refusesAPortfolioWithoutItsLifeList() {
        SidecarSession session = activeSession();
        when(port.fetchPortfolio("plain-state")).thenReturn(new CorumAdapter.Portfolio(
            new CorumAdapter.Snapshot("contract", "PP", "EUR", new BigDecimal("340.00"), null, true,
                List.of(holding("US", "340.00", "150"))),
            null));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.SidecarErrorCode.INVALID_DATA);
        verifyNoInteractions(positionRepository, positionService, fundRepository);
    }

    // --- Remembered credentials and the unattended re-login -------------------

    @org.junit.jupiter.api.Test
    void authenticate_withRememberStoresTheEncryptedCredentials() throws Exception {
        stubPassThroughEncryption();
        AtomicReference<SidecarSession> stored = captureStoredSession();
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
        AtomicReference<SidecarSession> stored = captureStoredSession();
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
        SidecarSession session = SidecarSession.builder().bank(SidecarBank.CORUM).id(3L).member(FamilyMember.builder().id(7L).build())
            .sessionState("encrypted").encryptedCredentials(REMEMBERED).build();
        when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.CORUM, 7L)).thenReturn(Optional.of(session));

        assertThat(service.clearSession(7L)).isTrue();

        verify(sessionRepository).delete(session);
    }

    @org.junit.jupiter.api.Test
    void clearSession_reportsThatThereWasNoSession() {
        when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.CORUM, 7L)).thenReturn(Optional.empty());

        assertThat(service.clearSession(7L)).isFalse();

        verify(sessionRepository, never()).delete(any());
    }

    @org.junit.jupiter.api.Test
    void queueSync_expiredSessionLogsInAgainWithTheStoredCredentialsAndSyncs() {
        SidecarSession session = rememberedSession();
        stubPassThroughEncryption();
        when(port.fetchPortfolio("plain-state"))
            .thenThrow(new SyncException("The CORUM session expired", null, "SESSION_EXPIRED"));
        when(port.authenticate("client", PASSWORD)).thenReturn("fresh-state");
        when(port.fetchPortfolio("fresh-state")).thenReturn(lifeOnly());
        stubLifeWrite();

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        assertThat(session.isActive()).isTrue();
        assertThat(session.getSessionState()).isEqualTo("enc:fresh-state");
        assertThat(session.getEncryptedCredentials()).isEqualTo(REMEMBERED);
        verify(port, times(1)).authenticate("client", PASSWORD);
    }

    /** "Remember my credentials" also serves a SCPI-only client: the re-login does not depend on CORUM Life. */
    @org.junit.jupiter.api.Test
    void queueSync_expiredSessionOfAScpiOnlyClientLogsInAgainWithTheStoredCredentials() {
        SidecarSession session = rememberedSession();
        stubPassThroughEncryption();
        when(port.fetchPortfolio("plain-state"))
            .thenThrow(new SyncException("The CORUM session expired", null, "SESSION_EXPIRED"));
        when(port.authenticate("client", PASSWORD)).thenReturn("fresh-state");
        when(port.fetchPortfolio("fresh-state")).thenReturn(realEstateOnly(new CorumAdapter.Snapshot(
            "contract", "PP", "EUR", new BigDecimal("340.00"), null, true,
            List.of(holding("US", "340.00", "150")))));
        when(positionRepository.findByMemberIdAndCorumFundCode(7L, "US"))
            .thenReturn(Optional.of(com.picsou.model.ScpiPosition.builder()
                .account(com.picsou.model.Account.builder().id(11L).build())
                .corumFundCode("US").build()));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        assertThat(session.isActive()).isTrue();
        assertThat(session.getSessionState()).isEqualTo("enc:fresh-state");
        assertThat(session.getEncryptedCredentials()).isEqualTo(REMEMBERED);
        assertThat(session.getLastSyncLifeAmbiguous()).isZero();
        verify(port, times(1)).authenticate("client", PASSWORD);
        verify(positionService).applySyncedPosition(
            any(), eq(new BigDecimal("2")), eq(new BigDecimal("170")),
            eq(new BigDecimal("150")), eq(null));
    }

    @org.junit.jupiter.api.Test
    void queueSync_aRejectedPasswordClearsTheCredentialsAndDeactivatesTheSession() {
        SidecarSession session = rememberedSession();
        when(port.fetchPortfolio("plain-state"))
            .thenThrow(new SyncException("The CORUM session expired", null, "SESSION_EXPIRED"));
        when(port.authenticate("client", PASSWORD))
            .thenThrow(new SyncException("CORUM rejected the credentials", null, "INVALID_CREDENTIALS"));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.SidecarErrorCode.INVALID_CREDENTIALS);
        assertThat(session.isActive()).isFalse();
        assertThat(session.getEncryptedCredentials()).isNull();
        assertThat(service.getStatus(7L).credentialsRemembered()).isFalse();
    }

    @org.junit.jupiter.api.Test
    void queueSync_expiredSessionWithoutCredentialsWaitsForTheMember() {
        SidecarSession session = activeSession();
        when(port.fetchPortfolio("plain-state"))
            .thenThrow(new SyncException("The CORUM session expired", null, "SESSION_EXPIRED"));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.SidecarErrorCode.SESSION_EXPIRED);
        assertThat(session.isActive()).isFalse();
        verify(port, never()).authenticate(any(), any());
    }

    /** One re-login per run: a fresh session that expires too ends the run instead of looping. */
    @org.junit.jupiter.api.Test
    void queueSync_logsInAgainAtMostOncePerRun() {
        SidecarSession session = rememberedSession();
        stubPassThroughEncryption();
        when(port.fetchPortfolio(any()))
            .thenThrow(new SyncException("The CORUM session expired", null, "SESSION_EXPIRED"));
        when(port.authenticate("client", PASSWORD)).thenReturn("fresh-state");

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.SidecarErrorCode.SESSION_EXPIRED);
        verify(port, times(1)).authenticate(any(), any());
        verify(port, times(2)).fetchPortfolio(any());
        // The password just worked: an expired fresh session is no reason to forget it.
        assertThat(session.getEncryptedCredentials()).isEqualTo(REMEMBERED);
        assertThat(service.getStatus(7L).credentialsRemembered()).isTrue();
    }

    /**
     * Re-logins draw on the member's own CORUM login budget (5 per 15 minutes), however the runs were
     * queued: the sixth run calls nothing, keeps the session active and the credentials stored.
     */
    @org.junit.jupiter.api.Test
    void queueSync_reloginsAreThrottledPerMemberWithoutCallingCorum() {
        SidecarSession session = rememberedSession();
        when(port.fetchPortfolio("plain-state"))
            .thenThrow(new SyncException("The CORUM session expired", null, "SESSION_EXPIRED"));
        when(port.authenticate("client", PASSWORD))
            .thenThrow(new SyncException("CORUM is temporarily unavailable", null, "UPSTREAM_UNAVAILABLE"));

        for (int run = 0; run < 6; run++) {
            service.queueSync(7L);
            assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        }

        verify(port, times(5)).authenticate(any(), any());
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.SidecarErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(session.isActive()).isTrue();
        assertThat(session.getEncryptedCredentials()).isEqualTo(REMEMBERED);
        assertThat(service.getStatus(7L).credentialsRemembered()).isTrue();
        assertThat(reloginBuckets).containsOnlyKeys(7L);
        // Another member's budget is untouched.
        assertThat(reloginBuckets.computeIfAbsent(8L, key -> com.picsou.config.RateLimitConfig.createCorumAuthBucket())
            .getAvailableTokens()).isEqualTo(5);
    }

    /** Credentials that cannot be decrypted or read back can never log in: they are cleared, the run ends expired. */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"DECRYPT", "MALFORMED_JSON", "BLANK_PASSWORD"})
    void queueSync_unreadableCredentialsAreClearedAndTheSessionExpires(String failure) {
        SidecarSession session = rememberedSession();
        switch (failure) {
            case "DECRYPT" -> when(encryption.decrypt(REMEMBERED))
                .thenThrow(new IllegalStateException("Tag mismatch"));
            case "MALFORMED_JSON" -> when(encryption.decrypt(REMEMBERED))
                .thenReturn("{\"login\":\"client\",\"password\":\"" + PASSWORD);
            default -> when(encryption.decrypt(REMEMBERED)).thenReturn("{\"login\":\"client\",\"password\":\"\"}");
        }
        when(port.fetchPortfolio("plain-state"))
            .thenThrow(new SyncException("The CORUM session expired", null, "SESSION_EXPIRED"));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.SidecarErrorCode.SESSION_EXPIRED);
        assertThat(session.isActive()).isFalse();
        assertThat(session.getEncryptedCredentials()).isNull();
        assertThat(service.getStatus(7L).credentialsRemembered()).isFalse();
        verify(port, never()).authenticate(any(), any());
        assertThat(reloginBuckets).isEmpty();
    }

    /** Real AES-GCM: what is stored is ciphertext, and the re-login reads back the same login and password. */
    @org.junit.jupiter.api.Test
    void rememberedCredentialsRoundTripThroughTheRealEncryption() {
        CryptoEncryption real = new CryptoEncryption(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        ScpiFundReconciler reconciler = new ScpiFundReconciler(
            positionRepository, positionService, accountRepository, fundRepository, memberRepository);
        CorumSyncService realService = new CorumSyncService(port, sessionRepository, memberRepository, real,
            txTemplate, Runnable::run, reconciler, new CorumLifeWriter(
                accountRepository, holdingRepository, memberRepository, accountService, operationWriter),
            reloginBuckets);
        AtomicReference<SidecarSession> stored = captureStoredSession();
        when(port.authenticate("client", PASSWORD)).thenReturn("first-state", "fresh-state");
        when(port.fetchPortfolio(any())).thenAnswer(inv -> {
            if ("fresh-state".equals(inv.getArgument(0))) return lifeOnly();
            throw new SyncException("The CORUM session expired", null, "SESSION_EXPIRED");
        });
        stubLifeWrite();

        realService.authenticate("client", PASSWORD, true, 7L);
        SidecarSession session = stored.get();
        assertThat(session.getEncryptedCredentials()).doesNotContain(PASSWORD).doesNotContain("client");
        assertThat(real.decrypt(session.getEncryptedCredentials())).contains(PASSWORD);

        // The first read expired; the re-login used the decrypted credentials.
        verify(port, times(2)).authenticate("client", PASSWORD);
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        assertThat(real.decrypt(session.getSessionState())).isEqualTo("fresh-state");
    }

    /**
     * No log event, from any logger, carries the remembered password: not its message, its arguments, nor any
     * exception in its cause or suppressed chain (message or stack frame). The sidecar answers go through the
     * real {@link com.picsou.adapter.CorumAdapter}, whose stubbed sidecar echoes the request body -- password
     * included -- in its 401, 422 and 500 answers. Each case runs through the member's sync and the daily one.
     */
    @ParameterizedTest(name = "{0} via {1}")
    @CsvSource({
        "REJECTED_401,QUEUE", "REJECTED_401,DAILY",
        "VALIDATION_422,QUEUE", "VALIDATION_422,DAILY",
        "ERROR_500_JSON,QUEUE", "ERROR_500_JSON,DAILY",
        "ERROR_500_TEXT,QUEUE", "ERROR_500_TEXT,DAILY",
        "DECRYPT_FAILURE,QUEUE", "DECRYPT_FAILURE,DAILY",
        "MALFORMED_CREDENTIALS,QUEUE", "MALFORMED_CREDENTIALS,DAILY",
        "PORT_RUNTIME_EXCEPTION,QUEUE", "PORT_RUNTIME_EXCEPTION,DAILY"
    })
    void thePasswordNeverReachesTheLogs(String scenario, String entry) {
        SidecarSession session = rememberedSession();
        stubPassThroughEncryption();
        lenient().when(port.fetchPortfolio("plain-state"))
            .thenThrow(new SyncException("The CORUM session expired", null, "SESSION_EXPIRED"));
        AtomicReference<String> echoed = new AtomicReference<>();
        CorumAdapter sidecarPort = switch (scenario) {
            case "REJECTED_401" -> echoingSidecar(echoed, org.springframework.http.HttpStatus.UNAUTHORIZED,
                org.springframework.http.MediaType.APPLICATION_JSON, body -> body);
            case "VALIDATION_422" -> echoingSidecar(echoed, org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                org.springframework.http.MediaType.APPLICATION_JSON,
                body -> "{\"detail\":[{\"type\":\"value_error\",\"loc\":[\"body\"],\"msg\":\"Invalid login\","
                    + "\"input\":" + body + "}]}");
            case "ERROR_500_JSON" -> echoingSidecar(echoed, org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                org.springframework.http.MediaType.APPLICATION_JSON, body -> "{\"error\":\"boom\",\"request\":" + body + "}");
            case "ERROR_500_TEXT" -> echoingSidecar(echoed, org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                org.springframework.http.MediaType.TEXT_PLAIN, body -> "Traceback: login failed for " + body);
            default -> port;
        };
        switch (scenario) {
            case "DECRYPT_FAILURE" -> when(encryption.decrypt(REMEMBERED))
                .thenThrow(new IllegalStateException("Could not decrypt " + REMEMBERED));
            case "MALFORMED_CREDENTIALS" -> when(encryption.decrypt(REMEMBERED))
                .thenReturn("{\"login\":\"client\",\"password\":\"" + PASSWORD + "\",");
            case "PORT_RUNTIME_EXCEPTION" -> when(port.authenticate("client", PASSWORD)).thenThrow(
                new IllegalStateException("Could not write {\"password\":\"" + PASSWORD + "\"}",
                    new RuntimeException(PASSWORD)));
            default -> { }
        }
        CorumSyncService logged = new CorumSyncService(sidecarPort, sessionRepository, memberRepository, encryption,
            txTemplate, Runnable::run, new ScpiFundReconciler(
                positionRepository, positionService, accountRepository, fundRepository, memberRepository),
            new CorumLifeWriter(accountRepository, holdingRepository, memberRepository, accountService, operationWriter),
            reloginBuckets);

        List<ch.qos.logback.classic.spi.ILoggingEvent> events = captureAllLogs(() -> {
            if ("QUEUE".equals(entry)) logged.queueSync(7L);
            else logged.resyncReporting(7L);
        });

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        if (scenario.startsWith("REJECTED") || scenario.startsWith("VALIDATION") || scenario.startsWith("ERROR")) {
            assertThat(echoed.get()).as("the stubbed sidecar received the password").contains(PASSWORD);
        }
        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(event -> assertThat(logText(event)).doesNotContain(PASSWORD));
        if (scenario.equals("REJECTED_401")) {
            assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.SidecarErrorCode.INVALID_CREDENTIALS);
        }
    }

    /**
     * A real {@link com.picsou.adapter.CorumAdapter} on a stubbed sidecar: the portfolio read answers a bare 401
     * (the session expired), and the login answers {@code status} with a body built from the request body.
     */
    private static CorumAdapter echoingSidecar(
        AtomicReference<String> echoed,
        org.springframework.http.HttpStatus status,
        org.springframework.http.MediaType type,
        java.util.function.UnaryOperator<String> answer
    ) {
        org.springframework.web.reactive.function.BodyInserter.Context context =
            new org.springframework.web.reactive.function.BodyInserter.Context() {
                @Override public List<org.springframework.http.codec.HttpMessageWriter<?>> messageWriters() {
                    return org.springframework.web.reactive.function.client.ExchangeStrategies.withDefaults()
                        .messageWriters();
                }
                @Override public Optional<org.springframework.http.server.reactive.ServerHttpRequest> serverRequest() {
                    return Optional.empty();
                }
                @Override public java.util.Map<String, Object> hints() { return java.util.Map.of(); }
            };
        org.springframework.web.reactive.function.client.ExchangeFunction sidecar = request -> {
            if (request.url().getPath().endsWith("/portfolio")) {
                return reactor.core.publisher.Mono.just(org.springframework.web.reactive.function.client.ClientResponse
                    .create(org.springframework.http.HttpStatus.UNAUTHORIZED).build());
            }
            var captured = new org.springframework.mock.http.client.reactive.MockClientHttpRequest(
                request.method(), request.url());
            return request.body().insert(captured, context)
                .then(reactor.core.publisher.Mono.defer(captured::getBodyAsString))
                .map(body -> {
                    echoed.set(body);
                    return org.springframework.web.reactive.function.client.ClientResponse.create(status)
                        .header("Content-Type", type.toString())
                        .body(answer.apply(body))
                        .build();
                });
        };
        org.springframework.web.reactive.function.client.WebClient client =
            org.springframework.web.reactive.function.client.WebClient.builder()
                .baseUrl("http://corum-auth:8001").exchangeFunction(sidecar).build();
        com.picsou.adapter.sidecar.SidecarWebClientFactory clients =
            mock(com.picsou.adapter.sidecar.SidecarWebClientFactory.class);
        when(clients.create(any(), any())).thenReturn(client);
        return new com.picsou.adapter.CorumAdapter(clients, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    /**
     * Every event any logger emits while {@code action} runs, root logger included. The root stays at the
     * shipped INFO and {@code com.picsou} is opened to TRACE, beyond any shipped profile (dev is DEBUG).
     */
    private static List<ch.qos.logback.classic.spi.ILoggingEvent> captureAllLogs(Runnable action) {
        var root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
            org.slf4j.Logger.ROOT_LOGGER_NAME);
        var app = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.picsou");
        ch.qos.logback.classic.Level rootLevel = root.getLevel();
        ch.qos.logback.classic.Level appLevel = app.getLevel();
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        root.addAppender(appender);
        root.setLevel(ch.qos.logback.classic.Level.INFO);
        app.setLevel(ch.qos.logback.classic.Level.TRACE);
        try {
            action.run();
        } finally {
            root.detachAppender(appender);
            appender.stop();
            root.setLevel(rootLevel);
            app.setLevel(appLevel);
        }
        return appender.list;
    }

    /** Everything an appender could print for {@code event}: message, raw arguments, MDC and every throwable. */
    private static String logText(ch.qos.logback.classic.spi.ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage()).append('\n')
            .append(event.getMessage()).append('\n')
            .append(java.util.Arrays.deepToString(event.getArgumentArray())).append('\n')
            .append(event.getMDCPropertyMap());
        java.util.Deque<ch.qos.logback.classic.spi.IThrowableProxy> pending = new java.util.ArrayDeque<>();
        java.util.Set<ch.qos.logback.classic.spi.IThrowableProxy> seen =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (event.getThrowableProxy() != null) pending.add(event.getThrowableProxy());
        while (!pending.isEmpty()) {
            ch.qos.logback.classic.spi.IThrowableProxy proxy = pending.pop();
            if (!seen.add(proxy)) continue;
            text.append('\n').append(proxy.getClassName()).append(": ").append(proxy.getMessage());
            if (proxy.getStackTraceElementProxyArray() != null) {
                for (var frame : proxy.getStackTraceElementProxyArray()) text.append('\n').append(frame.getSTEAsString());
            }
            if (proxy.getCause() != null) pending.add(proxy.getCause());
            if (proxy.getSuppressed() != null) pending.addAll(java.util.List.of(proxy.getSuppressed()));
        }
        if (event.getThrowableProxy() != null) {
            // The full rendering a console appender prints, as a cross-check of the walk above.
            text.append('\n').append(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy()));
        }
        return text.toString();
    }

    @org.junit.jupiter.api.Test
    void resyncReporting_queuesAnActiveSessionAndReportsAnInactiveOne() {
        SidecarSession session = rememberedSession();
        stubPassThroughEncryption();
        when(port.fetchPortfolio("plain-state")).thenReturn(lifeOnly());
        stubLifeWrite();
        assertThat(service.resyncReporting(7L).status())
            .isEqualTo(com.picsou.service.sync.SourceSyncResult.Status.QUEUED);
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);

        session.markQueued();
        session.markFailed(com.picsou.port.SidecarErrorCode.INVALID_CREDENTIALS, java.time.Instant.now());
        var rejected = service.resyncReporting(7L);
        assertThat(rejected.status()).isEqualTo(com.picsou.service.sync.SourceSyncResult.Status.NEEDS_REAUTH);
        assertThat(rejected.message()).isEqualTo("INVALID_CREDENTIALS");

        when(sessionRepository.findByBankAndMemberId(SidecarBank.CORUM, 7L)).thenReturn(Optional.empty());
        assertThat(service.resyncReporting(7L).status())
            .isEqualTo(com.picsou.service.sync.SourceSyncResult.Status.SKIPPED_NOT_CONNECTED);
    }

    private static final String PASSWORD = "s3cret-Pa55";
    private static final String REMEMBERED = "remembered-credentials";

    private SidecarSession rememberedSession() {
        SidecarSession session = activeSession();
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
    private AtomicReference<SidecarSession> captureStoredSession() {
        AtomicReference<SidecarSession> stored = new AtomicReference<>();
        lenient().when(memberRepository.findById(7L)).thenReturn(Optional.of(FamilyMember.builder().id(7L).build()));
        when(sessionRepository.saveAndFlush(any(SidecarSession.class))).thenAnswer(inv -> {
            SidecarSession session = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(session, "id", 3L);
            stored.set(session);
            return session;
        });
        lenient().when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.CORUM, 7L))
            .thenAnswer(inv -> Optional.ofNullable(stored.get()));
        lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L))
            .thenAnswer(inv -> Optional.ofNullable(stored.get()));
        lenient().when(sessionRepository.findByBankAndMemberId(SidecarBank.CORUM, 7L))
            .thenAnswer(inv -> Optional.ofNullable(stored.get()));
        return stored;
    }

    private static CorumAdapter.Portfolio lifeOnly() {
        return new CorumAdapter.Portfolio(null, List.of(lifeContract("1001", "518.61")));
    }

    private void stubLifeWrite() {
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        when(accountRepository.save(any(com.picsou.model.Account.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private SidecarSession activeSession() {
        FamilyMember member = FamilyMember.builder().id(7L).build();
        SidecarSession session = SidecarSession.builder().bank(SidecarBank.CORUM).id(3L).member(member)
            .sessionState("encrypted").build();
        when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.CORUM, 7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByBankAndMemberId(SidecarBank.CORUM, 7L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");
        return session;
    }

    private static CorumAdapter.Portfolio realEstateOnly(CorumAdapter.Snapshot snapshot) {
        return new CorumAdapter.Portfolio(snapshot, List.of());
    }

    /** One CORUM XL line declared at 518.61, valued at {@code lineValue}. */
    private static CorumAdapter.LifeContract lifeContract(String code, String lineValue) {
        return lifeContract(code, lineValue, null);
    }

    private static CorumAdapter.LifeContract lifeContract(
        String code, String lineValue, List<CorumAdapter.LifeOperation> operations
    ) {
        return new CorumAdapter.LifeContract(code, "EUR", new BigDecimal("518.61"), LocalDate.of(2026, 11, 4), 1,
            null, null, null, null, true,
            List.of(new CorumAdapter.LifeHolding("XL", "CORUM XL", new BigDecimal("2.65955"), new BigDecimal("195"),
                new BigDecimal(lineValue), new BigDecimal("480"), LocalDate.of(2026, 11, 4), false, "realEstate")),
            operations);
    }

    private static CorumAdapter.LifeOperation operation(String id, String amount) {
        return new CorumAdapter.LifeOperation(id, "arbitrage", "arbitrage", LocalDate.of(2026, 6, 15),
            new BigDecimal(amount), null);
    }

    private static CorumAdapter.Holding holding(String code, String displayed, String withdrawal) {
        return new CorumAdapter.Holding(
            code, "CORUM " + code, new BigDecimal("2"),
            new BigDecimal(withdrawal), new BigDecimal("170"),
            new BigDecimal(displayed), LocalDate.of(2026, 9, 26)
        );
    }
}
