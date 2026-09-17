package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.AmundiSession;
import com.picsou.model.AmundiSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.port.AmundiErrorCode;
import com.picsou.port.AmundiPort;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.AmundiSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class AmundiSyncServiceTest {
    @Mock AmundiPort port;
    @Mock AmundiSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountService accountService;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Captor ArgumentCaptor<Account> accountCaptor;
    @Captor ArgumentCaptor<List<AccountHolding>> holdingsCaptor;

    AmundiSyncService service;

    @BeforeEach
    void setUp() {
        executeTransactionsImmediately();
        service = serviceWith(Runnable::run);
    }

    @Test
    void queueSync_writesOnePlanAsOneAccountWithItsFundLines() {
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        when(port.fetchPlans("plain-state")).thenReturn(List.of(completePlan()));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(accountRepository.findByExternalAccountIdAndMemberId("amundi_PEG001", 7L))
            .thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            if (account.getId() == null) account.setId(11L);
            return account;
        });

        AmundiSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(AmundiSyncStatus.SUCCESS);
        verify(accountRepository).save(accountCaptor.capture());
        Account saved = accountCaptor.getValue();
        assertThat(saved.getExternalAccountId()).isEqualTo("amundi_PEG001");
        assertThat(saved.getProvider()).isEqualTo("Amundi Épargne Salariale");
        assertThat(saved.getType()).isEqualTo(AccountType.EMPLOYEE_SAVINGS);
        assertThat(saved.getCurrency()).isEqualTo("EUR");
        assertThat(saved.isManual()).isFalse();
        assertThat(saved.getCurrentBalance()).isEqualByComparingTo("1234.56");
        // Épargne salariale has no cash sleeve; a zero here would be a lie that
        // AccountService would then fold into the invested amount.
        assertThat(saved.getCashBalance()).isNull();

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        AccountHolding holding = holdingsCaptor.getValue().getFirst();
        assertThat(holding.getTicker()).isEqualTo("FR0010405035");
        assertThat(holding.getName()).isEqualTo("Amundi Label Actions Solidaires");
        assertThat(holding.getQuantity()).isEqualByComparingTo("12.3456");
        assertThat(holding.getCurrentPrice()).isEqualByComparingTo("100");
        assertThat(holding.getQuoteCurrency()).isEqualTo("EUR");
        assertThat(holding.getProviderValueEur()).isEqualByComparingTo("1234.56");
        assertThat(holding.getProviderPnlEur()).isEqualByComparingTo("34.56");
    }

    /**
     * FCPE units carry no purchase price: the cost basis has to come out of the
     * valuation minus the reported gain, or the account shows a phantom profit.
     */
    @Test
    void queueSync_derivesTheCostBasisFromTheReportedGain() {
        arrangeCommittableSync(completePlan());

        service.queueSync(7L);

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        // (1234.56 - 34.56) / 12.3456 = 1200 / 12.3456
        assertThat(holdingsCaptor.getValue().getFirst().getAverageBuyIn())
            .isEqualByComparingTo("97.20062208");
        verify(accountService).upsertSnapshot(
            any(Account.class),
            eq(new BigDecimal("1234.56")),
            eq(new BigDecimal("1200.00")),
            eq(LocalDate.now())
        );
    }

    @Test
    void queueSync_leavesTheCostBasisUnknownWhenAmundiReportsNoGain() {
        arrangeCommittableSync(plan(position("FR0010405035", "Fonds", "10", "100", "1000", null)));

        service.queueSync(7L);

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue().getFirst().getAverageBuyIn()).isNull();
        // Nothing to derive an invested amount from, so fall back to the total
        // rather than inventing a gain out of a missing field.
        verify(accountService).upsertSnapshot(
            any(Account.class),
            eq(new BigDecimal("1000")),
            eq(new BigDecimal("1000")),
            eq(LocalDate.now())
        );
    }

    /**
     * A contribution credited before it was converted into units arrives with
     * zero parts and a real amount. The sidecar counts it toward the plan total,
     * so dropping it here would make the plan permanently unreconcilable.
     */
    @Test
    void queueSync_keepsAZeroQuantityLineThatStillCarriesValue() {
        arrangeCommittableSync(plan("1234.56",
            position("FR0010405035", "Amundi Label Actions Solidaires",
                "12.3456", "100", "1200", "34.56"),
            position("FR0000000000", "Versement en cours", "0", null, "34.56", "0")
        ));

        AmundiSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(AmundiSyncStatus.SUCCESS);
        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue()).hasSize(2);
        assertThat(holdingsCaptor.getValue())
            .filteredOn(holding -> holding.getQuantity().signum() == 0)
            .singleElement()
            .satisfies(pending -> {
                assertThat(pending.getProviderValueEur()).isEqualByComparingTo("34.56");
                assertThat(pending.getAverageBuyIn()).isNull();
            });
    }

    @Test
    void queueSync_stillDropsALineWithNeitherUnitsNorValue() {
        arrangeCommittableSync(plan("1234.56",
            position("FR0010405035", "Amundi Label Actions Solidaires",
                "12.3456", "100", "1234.56", "34.56"),
            position("FR0000000000", "Fonds soldé", "0", null, "0", "0")
        ));

        service.queueSync(7L);

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue()).hasSize(1);
    }

    @Test
    void queueSync_replacesHoldingsAtomicallyBeforeSnapshotting() {
        arrangeCommittableSync(completePlan());

        service.queueSync(7L);

        InOrder order = inOrder(holdingRepository, accountService);
        order.verify(holdingRepository).deleteByAccountId(11L);
        order.verify(holdingRepository).flush();
        order.verify(holdingRepository).saveAll(any());
        order.verify(holdingRepository).flush();
        order.verify(accountService).upsertSnapshot(any(), any(), any(), any());
    }

    @Test
    void planTotalThatDisagreesWithItsLines_keepsTheLastGoodSnapshot() {
        arrangeQueuedSession(activeSession(member()));
        when(port.fetchPlans("plain-state")).thenReturn(List.of(
            plan("2000", position("FR0010405035", "Fonds", "10", "100", "900", "0"))
        ));

        AmundiSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(AmundiSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(AmundiErrorCode.PORTFOLIO_INCOMPLETE);
        verify(holdingRepository, never()).deleteByAccountId(any());
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void roundingWithinToleranceStillReconciles() {
        arrangeCommittableSync(plan("1000.03", position("FR0010405035", "Fonds", "10", "100", "1000", "0")));

        assertThat(service.queueSync(7L).syncStatus()).isEqualTo(AmundiSyncStatus.SUCCESS);
    }

    @Test
    void aPlanFlaggedIncompleteBySidecarIsRejectedOutright() {
        arrangeQueuedSession(activeSession(member()));
        when(port.fetchPlans("plain-state")).thenReturn(List.of(new AmundiPort.PlanData(
            "PEG001", "Plan", "PEG", "ACME", new BigDecimal("1000"),
            List.of(position("FR0010405035", "Fonds", "10", "100", "1000", "0")), false
        )));

        AmundiSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.lastSyncError()).isEqualTo(AmundiErrorCode.PORTFOLIO_INCOMPLETE);
    }

    @Test
    void duplicatePlanIdentifiersAreRejectedRatherThanOverwritingEachOther() {
        arrangeQueuedSession(activeSession(member()));
        when(port.fetchPlans("plain-state")).thenReturn(List.of(completePlan(), completePlan()));

        assertThat(service.queueSync(7L).lastSyncError()).isEqualTo(AmundiErrorCode.INVALID_DATA);
    }

    @Test
    void aFundWithoutAnIsinFallsBackToItsLabel() {
        arrangeCommittableSync(plan(position(null, "Actionnariat ACME 2021", "10", "100", "1000", "0")));

        service.queueSync(7L);

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue().getFirst().getTicker())
            .isEqualTo("ACTIONNARIAT-ACME-2021");
    }

    @Test
    void twoDifferentFundsCollidingOnTheFallbackTickerAreRefusedNotFused() {
        arrangeQueuedSession(activeSession(member()));
        String longPrefix = "Fonds Actionnariat Salarie ACME";
        when(port.fetchPlans("plain-state")).thenReturn(List.of(plan("2000",
            position(null, longPrefix + " 2021", "10", "100", "1000", "0"),
            position(null, longPrefix + " 2024", "10", "100", "1000", "0")
        )));

        assertThat(service.queueSync(7L).lastSyncError()).isEqualTo(AmundiErrorCode.INVALID_DATA);
    }

    @Test
    void theFundCodeKeysTheHoldingWhenCodeIsinIsNotAnIsin() {
        // Amundi puts the AMF code in codeIsin on employer funds.
        arrangeCommittableSync(plan(position(
            "990000093539", "4256", "Epargne Solidaire Dynamique ACME - A", "10", "100", "1000", "0"
        )));

        service.queueSync(7L);

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue().getFirst().getTicker()).isEqualTo("4256");
    }

    @Test
    void twoShareClassesSharingATruncatedLabelAreKeptApartByTheirFundCode() {
        // Regression: both labels slug to the same 30 characters, so keying on the
        // label refused this payload outright -- the share class is the last word.
        String sharedPrefix = "Epargne Solidaire Dynamique ACME -";
        arrangeCommittableSync(plan("2000",
            position("990000093539", "4256", sharedPrefix + " A", "10", "100", "1000", "0"),
            position("990000097329", "4286", sharedPrefix + " B", "10", "100", "1000", "0")
        ));

        service.queueSync(7L);

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue())
            .extracting(AccountHolding::getTicker)
            .containsExactlyInAnyOrder("4256", "4286");
    }

    @Test
    void aRealIsinStillWinsOverTheFundCode() {
        arrangeCommittableSync(plan(position(
            "FR0010405035", "4256", "Amundi Label Actions Solidaires", "10", "100", "1000", "0"
        )));

        service.queueSync(7L);

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue().getFirst().getTicker()).isEqualTo("FR0010405035");
    }

    @Test
    void theSameFundListedTwiceIsMerged() {
        arrangeCommittableSync(plan("2000",
            position("FR0010405035", "Fonds", "10", "100", "1000", "100"),
            position("FR0010405035", "Fonds", "10", "100", "1000", "100")
        ));

        service.queueSync(7L);

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue()).singleElement().satisfies(holding -> {
            assertThat(holding.getQuantity()).isEqualByComparingTo("20");
            assertThat(holding.getProviderValueEur()).isEqualByComparingTo("2000");
            assertThat(holding.getProviderPnlEur()).isEqualByComparingTo("200");
        });
    }

    @Test
    void theEmployerIsAppendedSoTwoPlansOfTheSameKindStayTellableApart() {
        arrangeCommittableSync(completePlan());

        service.queueSync(7L);

        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getName()).isEqualTo("Plan d'Épargne Groupe — ACME SA");
    }

    @Test
    void aSoftDeletedAccountIsNotResurrected() {
        arrangeQueuedSession(activeSession(member()));
        when(port.fetchPlans("plain-state")).thenReturn(List.of(completePlan()));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member()));
        when(accountRepository.findByExternalAccountIdAndMemberId("amundi_PEG001", 7L))
            .thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("amundi_PEG001", 7L))
            .thenReturn(true);

        assertThat(service.queueSync(7L).syncStatus()).isEqualTo(AmundiSyncStatus.SUCCESS);
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void anExpiredUpstreamSessionDeactivatesTheStoredSession() {
        AmundiSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchPlans("plain-state")).thenThrow(
            new SyncException("expired", null, AmundiErrorCode.SESSION_EXPIRED.name())
        );

        AmundiSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.lastSyncError()).isEqualTo(AmundiErrorCode.SESSION_EXPIRED);
        assertThat(session.isActive()).isFalse();
    }

    @Test
    void syncingWithoutASessionAsksTheUserToReconnect() {
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.queueSync(7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(AmundiErrorCode.SESSION_EXPIRED.name())
            );
    }

    @Test
    void aSecondSyncIsNotQueuedWhileOneIsAlreadyRunning() {
        AmundiSession session = activeSession(member());
        session.markQueued();
        session.markRunning(java.time.Instant.now());
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));

        AmundiSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(AmundiSyncStatus.RUNNING);
        verify(port, never()).fetchPlans(any());
    }

    /**
     * The job carries the session id it was queued for; if the user disconnects
     * and reconnects meanwhile, the in-flight result belongs to a dead session
     * and must be dropped rather than written over the new one.
     */
    @Test
    void aResultForAReplacedSessionIsDiscarded() {
        AmundiSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchPlans("plain-state")).thenReturn(List.of(completePlan()));
        when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L))
            .thenReturn(Optional.of(session))
            .thenReturn(Optional.empty());

        service.queueSync(7L);

        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void completeAuthWithoutASessionStateIsRefused() {
        when(port.completeAuth("process", "123456")).thenReturn("  ");

        assertThatThrownBy(() -> service.completeAuth("process", "123456", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(AmundiErrorCode.INVALID_DATA.name())
            );
    }

    @Test
    void initiateAuthStoresTheSessionOnlyWhenNoSecondFactorIsNeeded() {
        when(port.initiateAuth("login", "password"))
            .thenReturn(new AmundiPort.InitiateResult("p1", true, "APP_PUSH", null));

        AmundiSyncService.AuthInitResponse result = service.initiateAuth("login", "password", 7L);

        assertThat(result.mfaRequired()).isTrue();
        assertThat(result.mfaType()).isEqualTo("APP_PUSH");
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void aScheduledResyncIsSkippedWhenNoSessionIsActive() {
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());

        service.resyncIfSessionActive(7L);

        verify(port, never()).fetchPlans(any());
    }

    @Test
    void aScheduledResyncSwallowsUpstreamFailuresSoOtherMembersStillSync() {
        AmundiSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchPlans("plain-state")).thenThrow(
            new SyncException("down", null, AmundiErrorCode.UPSTREAM_UNAVAILABLE.name())
        );

        service.resyncIfSessionActive(7L);

        assertThat(session.getLastSyncError()).isEqualTo(AmundiErrorCode.UPSTREAM_UNAVAILABLE);
    }

    // -- helpers ------------------------------------------------------------

    private void arrangeCommittableSync(AmundiPort.PlanData... plans) {
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        when(port.fetchPlans("plain-state")).thenReturn(List.of(plans));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(accountRepository.findByExternalAccountIdAndMemberId(any(), eq(7L)))
            .thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            if (account.getId() == null) account.setId(11L);
            return account;
        });
    }

    private void executeTransactionsImmediately() {
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        }).when(txTemplate).execute(any(TransactionCallback.class));
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(transactionStatus);
            return null;
        }).when(txTemplate).executeWithoutResult(any());
    }

    private AmundiSyncService serviceWith(Executor executor) {
        return new AmundiSyncService(
            port,
            sessionRepository,
            accountRepository,
            holdingRepository,
            memberRepository,
            accountService,
            encryption,
            txTemplate,
            executor
        );
    }

    private void arrangeQueuedSession(AmundiSession session) {
        lenient().when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(session.getId(), 7L))
            .thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        lenient().when(encryption.decrypt("encrypted")).thenReturn("plain-state");
    }

    private FamilyMember member() {
        return FamilyMember.builder().id(7L).displayName("Owner").build();
    }

    private AmundiSession activeSession(FamilyMember member) {
        return AmundiSession.builder()
            .id(3L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(AmundiSyncStatus.IDLE)
            .build();
    }

    private AmundiPort.PlanData completePlan() {
        return plan(position("FR0010405035", "Amundi Label Actions Solidaires",
            "12.3456", "100", "1234.56", "34.56"));
    }

    private AmundiPort.PlanData plan(AmundiPort.Position... positions) {
        BigDecimal total = List.of(positions).stream()
            .map(AmundiPort.Position::valueEur)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return plan(total.toPlainString(), positions);
    }

    private AmundiPort.PlanData plan(String balanceEur, AmundiPort.Position... positions) {
        return new AmundiPort.PlanData(
            "PEG001",
            "Plan d'Épargne Groupe",
            "PEG",
            "ACME SA",
            new BigDecimal(balanceEur),
            List.of(positions),
            true
        );
    }

    private AmundiPort.Position position(
        String isin, String label, String quantity, String unitValue, String valueEur, String pnlEur
    ) {
        return position(isin, null, label, quantity, unitValue, valueEur, pnlEur);
    }

    private AmundiPort.Position position(
        String isin, String fundCode, String label,
        String quantity, String unitValue, String valueEur, String pnlEur
    ) {
        return new AmundiPort.Position(
            isin,
            fundCode,
            label,
            new BigDecimal(quantity),
            unitValue == null ? null : new BigDecimal(unitValue),
            new BigDecimal(valueEur),
            pnlEur == null ? null : new BigDecimal(pnlEur)
        );
    }
}
