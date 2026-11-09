package com.picsou.service;

import com.picsou.adapter.NatixisAdapter;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.dto.RealizedPnlResponse;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.SidecarInitiate;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SidecarSessionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.sync.SourceSyncResult;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.picsou.support.TestEntities.owner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class NatixisSyncServiceTest {
    private static final LocalDate HISTORY_FROM = LocalDate.now().minusYears(5).plusDays(1);

    private static final String PEE_ID = "natixis_10000-1000000000001_PEE-4242";
    private static final String PERCO_ID = "natixis_10000-1000000000001_PERCO-4242";

    @Mock NatixisAdapter port;
    @Mock SidecarSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountService accountService;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Captor ArgumentCaptor<Account> accountCaptor;
    @Captor ArgumentCaptor<List<AccountHolding>> holdingsCaptor;

    NatixisSyncService service;

    @BeforeEach
    void setUp() {
        executeTransactionsImmediately();
        service = new NatixisSyncService(
            port, sessionRepository, accountRepository, holdingRepository, transactionRepository, memberRepository,
            accountService, encryption, txTemplate, Runnable::run
        );
    }

    @Test
    void importsEachPlanAsAnEmployeeSavingsAccountWithProviderValuedFunds() {
        SidecarSession session = activeSession(owner());
        arrangeCommittableSession(session);
        when(port.fetchPlans("plain-state")).thenReturn(positions("plain-state", peePlan(), emptyPerco()));

        NatixisSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        verify(accountRepository, org.mockito.Mockito.times(2)).save(accountCaptor.capture());
        Account pee = accountCaptor.getAllValues().getFirst();
        assertThat(pee.getType()).isEqualTo(AccountType.EMPLOYEE_SAVINGS);
        assertThat(pee.getProvider()).isEqualTo("Natixis Interépargne");
        assertThat(pee.getExternalAccountId()).isEqualTo(PEE_ID);
        assertThat(pee.getName()).isEqualTo("Plan d'Épargne Entreprise — UES ACME");
        assertThat(pee.getCurrentBalance()).isEqualByComparingTo("1500.50");
        assertThat(pee.getCashBalance()).isNull();
        // An empty plan is listed too, at zero.
        Account perco = accountCaptor.getAllValues().get(1);
        assertThat(perco.getExternalAccountId()).isEqualTo(PERCO_ID);
        assertThat(perco.getCurrentBalance()).isEqualByComparingTo("0");

        verify(holdingRepository, org.mockito.Mockito.times(2)).saveAll(holdingsCaptor.capture());
        List<AccountHolding> holdings = holdingsCaptor.getAllValues().getFirst();
        assertThat(holdings).extracting(AccountHolding::getTicker)
            .satisfiesExactly(
                ticker -> assertThat(ticker).isEqualTo("NATIXIS-QS0000001002"),
                // No fund code: the label slug plus a hash of the full label.
                ticker -> assertThat(ticker).matches("FONDS-MONETAIRE-[0-9A-F]{8}"));
        AccountHolding first = holdings.getFirst();
        assertThat(first.getProviderValueEur()).isEqualByComparingTo("1000.00");
        assertThat(first.getProviderPnlEur()).isEqualByComparingTo("90.91");
        assertThat(first.getCurrentPrice()).isEqualByComparingTo("20");
        assertThat(first.getAverageBuyIn()).isEqualByComparingTo("18.1818");
        assertThat(holdings.get(1).getProviderPnlEur()).isNull();
        verify(accountService).upsertSnapshot(any(Account.class), eq(new BigDecimal("1500.50")),
            eq(new BigDecimal("1500.50")), eq(LocalDate.now()));
    }

    @Test
    void theSessionStateReturnedByPositionsReplacesTheStoredOne() {
        SidecarSession session = activeSession(owner());
        arrangeCommittableSession(session);
        when(port.fetchPlans("plain-state")).thenReturn(positions("rotated-state", peePlan()));
        when(encryption.encrypt("rotated-state")).thenReturn("rotated-encrypted");

        service.queueSync(7L);

        assertThat(session.getSessionState()).isEqualTo("rotated-encrypted");
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
    }

    @Test
    void anIncompletePlanKeepsTheExistingHoldingsButStillStoresTheRotatedSession() {
        SidecarSession session = activeSession(owner());
        arrangeQueuedSession(session);
        NatixisAdapter.PlanData broken = new NatixisAdapter.PlanData(PEE_ID, "Plan d'Épargne Entreprise", "PEE",
            "UES ACME", new BigDecimal("9999.99"), peePlan().positions(), true);
        when(port.fetchPlans("plain-state")).thenReturn(positions("rotated-state", broken));
        when(encryption.encrypt("rotated-state")).thenReturn("rotated-encrypted");

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.PORTFOLIO_INCOMPLETE);
        // A partial read leaves the session usable for the next scheduled run.
        assertThat(session.isActive()).isTrue();
        assertThat(session.getSessionState()).isEqualTo("rotated-encrypted");
        verify(accountRepository, never()).save(any());
        verify(holdingRepository, never()).deleteByAccountId(anyLong());
        verify(holdingRepository, never()).saveAll(any());
    }

    @Test
    void anExpiredSessionGoesInactiveAndTheScheduledResyncAsksForReauthentication() {
        SidecarSession session = activeSession(owner());
        arrangeQueuedSession(session);
        when(port.fetchPlans("plain-state"))
            .thenThrow(new SyncException("expired", null, SidecarErrorCode.SESSION_EXPIRED.name()));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.SESSION_EXPIRED);
        assertThat(session.isActive()).isFalse();
        verify(accountRepository, never()).save(any());

        SourceSyncResult result = service.resyncReporting(7L);

        assertThat(result.source()).isEqualTo("natixis");
        assertThat(result.status()).isEqualTo(SourceSyncResult.Status.NEEDS_REAUTH);
        assertThat(result.message()).isEqualTo("SESSION_EXPIRED");
        verify(port).fetchPlans(anyString());
    }

    @Test
    void aPlanWhoseAccountTheMemberDeletedIsNotImportedAgain() {
        SidecarSession session = activeSession(owner());
        arrangeCommittableSession(session);
        when(port.fetchPlans("plain-state")).thenReturn(positions("plain-state", peePlan(), emptyPerco()));
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(PEE_ID, 7L)).thenReturn(true);

        NatixisSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        // Only the PERCO is written: the deleted PEE gets no account, holdings or snapshot.
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getExternalAccountId()).isEqualTo(PERCO_ID);
        verify(holdingRepository).saveAll(any());
        verify(accountService).upsertSnapshot(any(Account.class), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO),
            eq(LocalDate.now()));
    }

    @Test
    void anUnlistedPlanIsClosedAtZero() {
        SidecarSession session = activeSession(owner());
        arrangeCommittableSession(session);
        when(port.fetchPlans("plain-state")).thenReturn(positions("plain-state", peePlan()));
        Account gone = Account.builder().id(20L).externalAccountId(PERCO_ID).name("PERCO")
            .provider(NatixisSyncService.PROVIDER).currentBalance(new BigDecimal("300")).build();
        when(accountRepository.findAllByMemberIdAndProviderForUpdate(7L, NatixisSyncService.PROVIDER))
            .thenReturn(List.of(gone));

        service.queueSync(7L);

        assertThat(gone.getCurrentBalance()).isEqualByComparingTo("0");
        verify(holdingRepository).deleteByAccountId(20L);
        verify(accountService).upsertSnapshot(gone, BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.now());
    }

    @Test
    void aSaverWhoClosedEveryPlanGetsTheirOldAccountsClosedAtZero() {
        SidecarSession session = activeSession(owner());
        arrangeCommittableSession(session);
        when(port.fetchPlans("plain-state")).thenReturn(positions("plain-state"));
        Account gone = Account.builder().id(20L).externalAccountId(PEE_ID).name("PEE")
            .provider(NatixisSyncService.PROVIDER).currentBalance(new BigDecimal("1500.50")).build();
        when(accountRepository.findAllByMemberIdAndProviderForUpdate(7L, NatixisSyncService.PROVIDER))
            .thenReturn(List.of(gone));

        NatixisSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        assertThat(gone.getCurrentBalance()).isEqualByComparingTo("0");
        verify(holdingRepository).deleteByAccountId(20L);
        verify(accountService).upsertSnapshot(gone, BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.now());
    }

    @Test
    void theCommitLocksTheAccountsBeforeTheSessionLikeAnAccountDeletionDoes() {
        SidecarSession session = activeSession(owner());
        arrangeCommittableSession(session);
        when(port.fetchPlans("plain-state")).thenReturn(positions("plain-state", peePlan()));

        service.queueSync(7L);

        InOrder order = inOrder(accountRepository, sessionRepository);
        order.verify(accountRepository).findAllByMemberIdAndProviderForUpdate(7L, NatixisSyncService.PROVIDER);
        order.verify(sessionRepository).findByIdAndMemberIdForUpdate(3L, 7L);
        order.verify(accountRepository).save(any(Account.class));
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
    }

    @Test
    void twoLabelOnlyFundsSharingTheirFirstCharactersKeepDistinctTickers() {
        SidecarSession session = activeSession(owner());
        arrangeCommittableSession(session);
        NatixisAdapter.PlanData plan = new NatixisAdapter.PlanData(PEE_ID, "Plan d'Épargne Entreprise", "PEE",
            "UES ACME", new BigDecimal("300.00"), List.of(
                new NatixisAdapter.Position(null, null, "FONDS SOLIDAIRE DIVERSIFIE EUROPE PART A",
                    new BigDecimal("10"), null, new BigDecimal("100.00"), null),
                new NatixisAdapter.Position(null, null, "FONDS SOLIDAIRE DIVERSIFIE EUROPE PART B",
                    new BigDecimal("10"), null, new BigDecimal("200.00"), null)
            ), true);
        when(port.fetchPlans("plain-state")).thenReturn(positions("plain-state", plan));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        List<String> tickers = holdingsCaptor.getValue().stream().map(AccountHolding::getTicker).toList();
        assertThat(tickers).hasSize(2).doesNotHaveDuplicates()
            .allSatisfy(ticker -> assertThat(ticker).hasSizeLessThanOrEqualTo(30)
                .startsWith("FONDS-SOLIDAIRE-DIVER"));
    }

    @Test
    void aJobThatCannotBeMarkedRunningFailsRetryablyInsteadOfStayingQueued() {
        SidecarSession session = activeSession(owner());
        arrangeQueuedSession(session);
        when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L))
            .thenThrow(new org.springframework.dao.QueryTimeoutException("db hiccup"))
            .thenReturn(Optional.of(session));

        NatixisSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(session.isActive()).isTrue();
        verify(port, never()).fetchPlans(anyString());
        // Retryable: the next sync is accepted again.
        when(port.fetchPlans("plain-state"))
            .thenThrow(new SyncException("transient", null, SidecarErrorCode.UPSTREAM_UNAVAILABLE.name()));
        service.queueSync(7L);
        verify(port).fetchPlans("plain-state");
    }

    @Test
    void bootRecoveryFailsEveryJobFromBeforeTheStartAndSparesOnesQueuedSince() {
        Instant beforeStart = Instant.now();
        NatixisSyncService started = new NatixisSyncService(
            port, sessionRepository, accountRepository, holdingRepository, transactionRepository, memberRepository,
            accountService, encryption, txTemplate, Runnable::run
        );
        Instant afterStart = Instant.now();
        // A job this backend queues after starting is stamped later than the cut-off.
        while (!Instant.now().isAfter(afterStart)) {
            Thread.onSpinWait();
        }

        started.recoverSyncsInterruptedByRestart();

        ArgumentCaptor<Instant> touchedBefore = ArgumentCaptor.forClass(Instant.class);
        verify(sessionRepository).markInterruptedSyncsFailedBefore(
            eq(SidecarBank.NATIXIS),
            eq(List.of(SidecarSyncStatus.QUEUED, SidecarSyncStatus.RUNNING)),
            eq(SidecarSyncStatus.FAILED),
            any(Instant.class),
            eq(SidecarErrorCode.INTERNAL_ERROR),
            touchedBefore.capture());
        // The cut-off is the start itself, not the call time and not the stale window.
        assertThat(touchedBefore.getValue()).isBetween(beforeStart, afterStart);
    }

    @Test
    void periodicRecoveryOnlyFailsJobsThatWentStale() {
        Instant before = Instant.now();

        service.recoverInterruptedSyncs();

        ArgumentCaptor<Instant> staleBefore = ArgumentCaptor.forClass(Instant.class);
        verify(sessionRepository).markInterruptedSyncsFailedBefore(
            eq(SidecarBank.NATIXIS),
            eq(List.of(SidecarSyncStatus.QUEUED, SidecarSyncStatus.RUNNING)),
            eq(SidecarSyncStatus.FAILED),
            any(Instant.class),
            eq(SidecarErrorCode.INTERNAL_ERROR),
            staleBefore.capture());
        // A job another backend touched in the last ten minutes is left alone.
        assertThat(staleBefore.getValue())
            .isBeforeOrEqualTo(Instant.now().minus(NatixisSyncService.STALE_SYNC_AFTER))
            .isAfterOrEqualTo(before.minus(NatixisSyncService.STALE_SYNC_AFTER));
        assertThat(NatixisSyncService.STALE_SYNC_AFTER).isGreaterThanOrEqualTo(java.time.Duration.ofMinutes(10));
    }

    @Test
    void aCompletedLoginStoresTheSessionAndQueuesASync() {
        when(port.initiateAuth("saver@example.com", "secret"))
            .thenReturn(new SidecarInitiate("process", true, "SMS", null));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(owner()));
        when(port.completeAuth("process", "123456")).thenReturn("fresh-state");
        when(encryption.encrypt("fresh-state")).thenReturn("fresh-encrypted");
        when(sessionRepository.saveAndFlush(any(SidecarSession.class))).thenAnswer(invocation -> {
            SidecarSession stored = invocation.getArgument(0);
            lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(stored.getId(), 7L))
                .thenReturn(Optional.of(stored));
            lenient().when(sessionRepository.findByBankAndMemberId(SidecarBank.NATIXIS, 7L)).thenReturn(Optional.of(stored));
            return stored;
        });
        when(port.fetchPlans("fresh-state"))
            .thenThrow(new SyncException("transient", null, SidecarErrorCode.UPSTREAM_UNAVAILABLE.name()));

        NatixisSyncService.AuthInitResponse init = service.initiateAuth("saver@example.com", "secret", 7L);
        service.completeAuth("process", "123456", 7L);

        assertThat(init.mfaRequired()).isTrue();
        assertThat(init.mfaType()).isEqualTo("SMS");
        ArgumentCaptor<SidecarSession> stored = ArgumentCaptor.forClass(SidecarSession.class);
        verify(sessionRepository).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getSessionState()).isEqualTo("fresh-encrypted");
        verify(port).fetchPlans("fresh-state");
    }

    @Test
    void aMistypedCodeKeepsTheAttemptOpenForAnotherTry() {
        when(port.initiateAuth("saver@example.com", "secret"))
            .thenReturn(new SidecarInitiate("process", true, "EMAIL", null));
        when(port.completeAuth("process", "000000"))
            .thenThrow(new SyncException("wrong", null, SidecarErrorCode.INVALID_OTP.name()));
        when(port.completeAuth("process", "999999"))
            .thenThrow(new SyncException("still wrong", null, SidecarErrorCode.INVALID_OTP.name()));

        service.initiateAuth("saver@example.com", "secret", 7L);

        assertThatThrownBy(() -> service.completeAuth("process", "000000", 7L))
            .isInstanceOfSatisfying(SyncException.class,
                error -> assertThat(error.getCode()).isEqualTo("INVALID_OTP"));
        // Still the sidecar's answer, not AUTH_ATTEMPT_EXPIRED: the attempt survived.
        assertThatThrownBy(() -> service.completeAuth("process", "999999", 7L))
            .isInstanceOfSatisfying(SyncException.class,
                error -> assertThat(error.getCode()).isEqualTo("INVALID_OTP"));
    }

    @Test
    void anotherMemberCannotCompleteSomeoneElsesLogin() {
        when(port.initiateAuth("saver@example.com", "secret"))
            .thenReturn(new SidecarInitiate("process", true, "SMS", null));
        service.initiateAuth("saver@example.com", "secret", 7L);

        assertThatThrownBy(() -> service.completeAuth("process", "123456", 8L))
            .isInstanceOfSatisfying(SyncException.class,
                error -> assertThat(error.getCode()).isEqualTo("AUTH_ATTEMPT_EXPIRED"));
        verify(port, never()).completeAuth(anyString(), anyString());
    }

    @Test
    void aLoginCompletedAfterTheConnectionWasRemovedStoresNothing() {
        when(port.initiateAuth("saver@example.com", "secret"))
            .thenReturn(new SidecarInitiate("process", true, "SMS", null));
        service.initiateAuth("saver@example.com", "secret", 7L);

        service.clearSession(7L);

        assertThatThrownBy(() -> service.completeAuth("process", "123456", 7L))
            .isInstanceOfSatisfying(SyncException.class,
                error -> assertThat(error.getCode()).isEqualTo("AUTH_ATTEMPT_EXPIRED"));
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void removingTheConnectionCancelsTheMembersPendingLoginsAtTheSidecar() {
        when(port.initiateAuth("saver@example.com", "secret"))
            .thenReturn(new SidecarInitiate("mine", true, "SMS", null));
        when(port.initiateAuth("other@example.com", "secret"))
            .thenReturn(new SidecarInitiate("theirs", true, "SMS", null));
        service.initiateAuth("saver@example.com", "secret", 7L);
        service.initiateAuth("other@example.com", "secret", 8L);

        service.clearSession(7L);

        verify(port).cancelAuth("mine");
        verify(port, never()).cancelAuth("theirs");
    }

    @Test
    void aLoginOpenedWhileTheConnectionWasRemovedIsCancelledAtTheSidecar() {
        when(port.initiateAuth("saver@example.com", "secret")).thenAnswer(invocation -> {
            service.clearSession(7L);
            return new SidecarInitiate("process", true, "SMS", null);
        });

        assertThatThrownBy(() -> service.initiateAuth("saver@example.com", "secret", 7L))
            .isInstanceOfSatisfying(SyncException.class,
                error -> assertThat(error.getCode()).isEqualTo("AUTH_ATTEMPT_EXPIRED"));
        verify(port).cancelAuth("process");
    }

    @Test
    void aConnectionRemovedWhileTheCodeIsBeingCheckedStoresNothing() {
        when(port.initiateAuth("saver@example.com", "secret"))
            .thenReturn(new SidecarInitiate("process", true, "SMS", null));
        when(port.completeAuth("process", "123456")).thenAnswer(invocation -> {
            service.clearSession(7L);
            return "fresh-state";
        });
        service.initiateAuth("saver@example.com", "secret", 7L);

        assertThatThrownBy(() -> service.completeAuth("process", "123456", 7L))
            .isInstanceOfSatisfying(SyncException.class,
                error -> assertThat(error.getCode()).isEqualTo("AUTH_ATTEMPT_EXPIRED"));
        verify(sessionRepository, never()).saveAndFlush(any());
        verify(memberRepository, never()).findById(anyLong());
    }

    private NatixisAdapter.PositionsData positions(String sessionState, NatixisAdapter.PlanData... plans) {
        return new NatixisAdapter.PositionsData(List.of(plans), sessionState, null, null, null);
    }

    // ─── Operations history ─────────────────────────────────────────────────

    private final List<Transaction> store = new ArrayList<>();

    @Test
    void aCompleteHistoryInsertsBuysAndSalesOnTheirPlanAccountWithTheHoldingsTicker() {
        Account pee = arrangeOperationsSync(true, buy("natixis_1_D1_L1", "2024-01-10"),
            sell("natixis_1_P1_L1", "2025-03-01"),
            new NatixisAdapter.Operation("natixis_1_D9_L1", "natixis_1_UNKNOWN", "2024-02-01", "BUY", "Versement",
                "FONDS ACTIONS EUROPE I", "QS0000001002", BigDecimal.ONE, null, BigDecimal.TEN, null));

        service.queueSync(7L);

        assertThat(store).hasSize(2).allSatisfy(row -> {
            assertThat(row.getAccount()).isSameAs(pee);
            assertThat(row.isManual()).isFalse();
            assertThat(row.getNativeCurrency()).isEqualTo("EUR");
            // Fund trades inside the plan: kept off the budget, cash flow and categorization.
            assertThat(row.isBudgetNeutral()).isTrue();
            assertThat(row.getName()).isEqualTo("FONDS ACTIONS EUROPE I");
        });
        Transaction bought = store.getFirst();
        assertThat(bought.getExternalId()).isEqualTo("natixis_1_D1_L1");
        assertThat(bought.getTxType()).isEqualTo(TransactionType.BUY);
        assertThat(bought.getDate()).isEqualTo(LocalDate.parse("2024-01-10"));
        assertThat(bought.getDescription()).isEqualTo("Investissement de votre intéressement");
        assertThat(bought.getQuantity()).isEqualByComparingTo("10");
        assertThat(bought.getPricePerUnit()).isEqualByComparingTo("20");
        assertThat(bought.getFees()).isEqualByComparingTo("1");
        // A buy is an outflow of the amount plus its fees, a sale an inflow of its proceeds.
        assertThat(bought.getAmount()).isEqualByComparingTo("-201");
        Transaction sold = store.get(1);
        assertThat(sold.getTxType()).isEqualTo(TransactionType.SELL);
        assertThat(sold.getAmount()).isEqualByComparingTo("100");
        // The same ticker as the holding, so realized P&L lines the sale up with its buys.
        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue().getFirst().getTicker()).isEqualTo(bought.getTicker());
        assertThat(sold.getTicker()).isEqualTo("NATIXIS-QS0000001002");
        // History only: the plan's balance and snapshot are the snapshot's alone.
        assertThat(pee.getCurrentBalance()).isEqualByComparingTo("1500.50");
        verify(accountService).upsertSnapshot(pee, new BigDecimal("1500.50"), new BigDecimal("1500.50"), LocalDate.now());
    }

    @Test
    void anIdenticalSecondSyncChangesNothingAndAChangedOperationIsUpdatedInPlace() {
        arrangeOperationsSync(true, buy("natixis_1_D1_L1", "2024-01-10"), sell("natixis_1_P1_L1", "2025-03-01"));
        service.queueSync(7L);
        List<Transaction> first = List.copyOf(store);

        service.queueSync(7L);
        assertThat(store).containsExactlyElementsOf(first);

        NatixisAdapter.Operation corrected = new NatixisAdapter.Operation("natixis_1_P1_L1", PEE_ID,
            "2025-03-02", "SELL", "Déblocage anticipé", "FONDS ACTIONS EUROPE I", "QS0000001002",
            new BigDecimal("4"), new BigDecimal("26"), new BigDecimal("104"), null);
        when(port.fetchPlans("plain-state")).thenReturn(operationsPositions(true, buy("natixis_1_D1_L1", "2024-01-10"),
            corrected));
        service.queueSync(7L);

        assertThat(store).containsExactlyElementsOf(first);
        assertThat(first.get(1).getAmount()).isEqualByComparingTo("104");
        assertThat(first.get(1).getDate()).isEqualTo(LocalDate.parse("2025-03-02"));
        assertThat(first.get(1).getDescription()).isEqualTo("Déblocage anticipé");
    }

    @Test
    void anOperationGoneFromTheHistoryIsDeletedOnlyInsideWhatTheReadCovered() {
        Account pee = arrangeOperationsSync(true, buy("natixis_1_D1_L1", "2024-01-10"));
        Transaction recent = storedRow(pee, "natixis_1_D2_L1", LocalDate.now().minusYears(1), false);
        Transaction ancient = storedRow(pee, "natixis_1_D0_L1", LocalDate.now().minusYears(6), false);
        // Dated just after the first listing date: its demand may have been listed before it,
        // outside the read, so its absence proves nothing.
        Transaction settling = storedRow(pee, "natixis_1_D3_L1", HISTORY_FROM.plusDays(30), false);
        Transaction manual = storedRow(pee, null, LocalDate.now().minusMonths(2), true);
        Transaction foreign = storedRow(pee, "other_123", LocalDate.now().minusMonths(2), false);

        service.queueSync(7L);

        assertThat(store).doesNotContain(recent).contains(ancient, settling, manual, foreign);
        assertThat(store).extracting(Transaction::getExternalId).contains("natixis_1_D1_L1");
    }

    @Test
    void aHistoryThatDoesNotSayWhatItCoveredDeletesNothing() {
        Account pee = arrangeOperationsSync(true, buy("natixis_1_D1_L1", "2024-01-10"));
        when(port.fetchPlans("plain-state")).thenReturn(new NatixisAdapter.PositionsData(List.of(peePlan()),
            "plain-state", true, List.of(buy("natixis_1_D1_L1", "2024-01-10")), null));
        Transaction recent = storedRow(pee, "natixis_1_D2_L1", LocalDate.now().minusYears(1), false);

        service.queueSync(7L);

        assertThat(store).contains(recent);
        verify(transactionRepository, never()).deleteAll(anyIterable());
    }

    @Test
    void anIncompleteHistoryLeavesTheStoredOperationsUntouched() {
        Account pee = arrangeOperationsSync(false);
        Transaction recent = storedRow(pee, "natixis_1_D2_L1", LocalDate.now().minusYears(1), false);
        BigDecimal amount = recent.getAmount();

        service.queueSync(7L);

        assertThat(store).containsExactly(recent);
        assertThat(recent.getAmount()).isEqualByComparingTo(amount);
        verify(transactionRepository, never()).deleteAll(anyIterable());
        verify(transactionRepository, never()).saveAllAndFlush(anyIterable());
    }

    @Test
    void anOlderSidecarWithoutAHistoryLeavesTheStoredOperationsUntouched() {
        Account pee = arrangeOperationsSync(true);
        when(port.fetchPlans("plain-state")).thenReturn(positions("plain-state", peePlan()));
        storedRow(pee, "natixis_1_D2_L1", LocalDate.now().minusYears(1), false);

        service.queueSync(7L);

        assertThat(store).hasSize(1);
        verify(transactionRepository, never()).saveAllAndFlush(anyIterable());
    }

    @Test
    void realizedPnlPairsTheImportedSaleWithItsBuys() {
        Account pee = arrangeOperationsSync(true, sell("natixis_1_P1_L1", "2025-03-01"), buy("natixis_1_D1_L1", "2024-01-10"));
        service.queueSync(7L);
        AccountRepository pnlAccounts = org.mockito.Mockito.mock(AccountRepository.class);
        TransactionRepository pnlTransactions = org.mockito.Mockito.mock(TransactionRepository.class);
        when(pnlAccounts.findByIdAndMemberId(11L, 7L)).thenReturn(Optional.of(pee));
        // Saved oldest first, which is the (date, id) order the repository replays.
        when(pnlTransactions.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(eq(11L), any()))
            .thenReturn(store.stream().sorted(Comparator.comparing(Transaction::getDate)).toList());

        RealizedPnlResponse realized = new RealizedPnlService(pnlAccounts, pnlTransactions).compute(11L, 7L);

        // Cost (10 x 20 + 1 fee) / 10 = 20.1 a unit; 4 sold at 25: 100 - 80.4.
        assertThat(realized.realizedTotal()).isEqualByComparingTo("19.6");
        assertThat(realized.byTicker()).singleElement()
            .satisfies(line -> assertThat(line.warning()).isFalse());
    }

    private Account arrangeOperationsSync(boolean complete, NatixisAdapter.Operation... operations) {
        SidecarSession session = activeSession(owner());
        arrangeCommittableSession(session);
        Account pee = Account.builder().id(11L).externalAccountId(PEE_ID).provider(NatixisSyncService.PROVIDER)
            .member(owner()).name("PEE").currentBalance(new BigDecimal("1500.50")).build();
        when(accountRepository.findByExternalAccountIdAndMemberId(PEE_ID, 7L)).thenReturn(Optional.of(pee));
        lenient().when(accountRepository.findAllByMemberIdAndProvider(7L, NatixisSyncService.PROVIDER))
            .thenReturn(List.of(pee));
        lenient().when(port.fetchPlans("plain-state")).thenReturn(operationsPositions(complete, operations));
        lenient().when(transactionRepository.findByAccountIdAndIsManualFalse(anyLong())).thenAnswer(invocation ->
            store.stream()
                .filter(row -> !row.isManual() && row.getAccount().getId().equals(invocation.getArgument(0)))
                .toList());
        lenient().when(transactionRepository.saveAllAndFlush(anyIterable())).thenAnswer(invocation -> {
            Iterable<Transaction> rows = invocation.getArgument(0);
            rows.forEach(row -> {
                if (!store.contains(row)) store.add(row);
            });
            return rows;
        });
        lenient().doAnswer(invocation -> {
            Iterable<Transaction> rows = invocation.getArgument(0);
            rows.forEach(store::remove);
            return null;
        }).when(transactionRepository).deleteAll(anyIterable());
        return pee;
    }

    private NatixisAdapter.PositionsData operationsPositions(boolean complete, NatixisAdapter.Operation... operations) {
        return new NatixisAdapter.PositionsData(List.of(peePlan()), "plain-state", complete,
            complete ? List.of(operations) : List.of(), complete ? HISTORY_FROM.toString() : null);
    }

    private NatixisAdapter.Operation buy(String externalId, String date) {
        return new NatixisAdapter.Operation(externalId, PEE_ID, date, "BUY",
            "Investissement de votre intéressement", "FONDS ACTIONS EUROPE I", "QS0000001002",
            new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("200"), BigDecimal.ONE);
    }

    private NatixisAdapter.Operation sell(String externalId, String date) {
        return new NatixisAdapter.Operation(externalId, PEE_ID, date, "SELL",
            "Déblocage anticipé", "FONDS ACTIONS EUROPE I", "QS0000001002",
            new BigDecimal("4"), new BigDecimal("25"), new BigDecimal("100"), null);
    }

    private Transaction storedRow(Account account, String externalId, LocalDate date, boolean manual) {
        Transaction row = Transaction.builder().account(account).externalId(externalId).date(date)
            .description("stored").amount(new BigDecimal("-50")).txType(TransactionType.BUY)
            .isManual(manual).build();
        store.add(row);
        return row;
    }

    private void arrangeCommittableSession(SidecarSession session) {
        arrangeQueuedSession(session);
        when(memberRepository.findById(7L)).thenReturn(Optional.of(owner()));
        lenient().when(accountRepository.findByExternalAccountIdAndMemberId(any(), eq(7L))).thenReturn(Optional.empty());
        lenient().when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            if (account.getId() == null) account.setId(11L);
            return account;
        });
        lenient().when(encryption.encrypt("plain-state")).thenReturn("encrypted");
    }

    private void arrangeQueuedSession(SidecarSession session) {
        lenient().when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.NATIXIS, 7L)).thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(session.getId(), 7L))
            .thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByBankAndMemberId(SidecarBank.NATIXIS, 7L)).thenReturn(Optional.of(session));
        lenient().when(encryption.decrypt("encrypted")).thenReturn("plain-state");
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

    private SidecarSession activeSession(FamilyMember member) {
        return SidecarSession.builder()
            .bank(SidecarBank.NATIXIS)
            .id(3L)
            .member(member)
            .sessionState("encrypted")
            .lastValidatedAt(Instant.now())
            .active(true)
            .syncStatus(SidecarSyncStatus.IDLE)
            .build();
    }

    private NatixisAdapter.PlanData peePlan() {
        return new NatixisAdapter.PlanData(
            PEE_ID,
            "Plan d'Épargne Entreprise",
            "PEE",
            "UES ACME",
            new BigDecimal("1500.50"),
            List.of(
                new NatixisAdapter.Position(null, "QS0000001002", "FONDS ACTIONS EUROPE I", new BigDecimal("50.0000"),
                    new BigDecimal("20.00"), new BigDecimal("1000.00"), new BigDecimal("90.91")),
                new NatixisAdapter.Position(null, null, "FONDS MONETAIRE", new BigDecimal("25.0250"),
                    null, new BigDecimal("500.50"), null)
            ),
            true
        );
    }

    private NatixisAdapter.PlanData emptyPerco() {
        return new NatixisAdapter.PlanData(PERCO_ID, "PERCO", "PERCO", "UES ACME",
            BigDecimal.ZERO, List.of(), true);
    }
}
