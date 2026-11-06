package com.picsou.service;

import com.picsou.adapter.NatixisAdapter;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.NatixisSession;
import com.picsou.model.NatixisSyncStatus;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.SidecarInitiate;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.NatixisSessionRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.picsou.support.TestEntities.owner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
    private static final String PEE_ID = "natixis_10000-1000000000001_PEE-4242";
    private static final String PERCO_ID = "natixis_10000-1000000000001_PERCO-4242";

    @Mock NatixisAdapter port;
    @Mock NatixisSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
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
            port, sessionRepository, accountRepository, holdingRepository, memberRepository,
            accountService, encryption, txTemplate, Runnable::run
        );
    }

    @Test
    void importsEachPlanAsAnEmployeeSavingsAccountWithProviderValuedFunds() {
        NatixisSession session = activeSession(owner());
        arrangeCommittableSession(session);
        when(port.fetchPlans("plain-state")).thenReturn(positions("plain-state", peePlan(), emptyPerco()));

        NatixisSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(NatixisSyncStatus.SUCCESS);
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
                ticker -> assertThat(ticker).isEqualTo("NATIXIS-QS0004086763"),
                // No fund code: the label slug plus a hash of the full label.
                ticker -> assertThat(ticker).matches("AVENIR-MONETAIRE-[0-9A-F]{8}"));
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
        NatixisSession session = activeSession(owner());
        arrangeCommittableSession(session);
        when(port.fetchPlans("plain-state")).thenReturn(positions("rotated-state", peePlan()));
        when(encryption.encrypt("rotated-state")).thenReturn("rotated-encrypted");

        service.queueSync(7L);

        assertThat(session.getSessionState()).isEqualTo("rotated-encrypted");
        assertThat(session.getSyncStatus()).isEqualTo(NatixisSyncStatus.SUCCESS);
    }

    @Test
    void anIncompletePlanKeepsTheExistingHoldingsButStillStoresTheRotatedSession() {
        NatixisSession session = activeSession(owner());
        arrangeQueuedSession(session);
        NatixisAdapter.PlanData broken = new NatixisAdapter.PlanData(PEE_ID, "Plan d'Épargne Entreprise", "PEE",
            "UES ACME", new BigDecimal("9999.99"), peePlan().positions(), true);
        when(port.fetchPlans("plain-state")).thenReturn(positions("rotated-state", broken));
        when(encryption.encrypt("rotated-state")).thenReturn("rotated-encrypted");

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(NatixisSyncStatus.FAILED);
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
        NatixisSession session = activeSession(owner());
        arrangeQueuedSession(session);
        when(port.fetchPlans("plain-state"))
            .thenThrow(new SyncException("expired", null, SidecarErrorCode.SESSION_EXPIRED.name()));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(NatixisSyncStatus.FAILED);
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
    void anUnlistedPlanIsClosedAtZero() {
        NatixisSession session = activeSession(owner());
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
        NatixisSession session = activeSession(owner());
        arrangeCommittableSession(session);
        when(port.fetchPlans("plain-state")).thenReturn(positions("plain-state"));
        Account gone = Account.builder().id(20L).externalAccountId(PEE_ID).name("PEE")
            .provider(NatixisSyncService.PROVIDER).currentBalance(new BigDecimal("1500.50")).build();
        when(accountRepository.findAllByMemberIdAndProviderForUpdate(7L, NatixisSyncService.PROVIDER))
            .thenReturn(List.of(gone));

        NatixisSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(NatixisSyncStatus.SUCCESS);
        assertThat(gone.getCurrentBalance()).isEqualByComparingTo("0");
        verify(holdingRepository).deleteByAccountId(20L);
        verify(accountService).upsertSnapshot(gone, BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.now());
    }

    @Test
    void theCommitLocksTheAccountsBeforeTheSessionLikeAnAccountDeletionDoes() {
        NatixisSession session = activeSession(owner());
        arrangeCommittableSession(session);
        when(port.fetchPlans("plain-state")).thenReturn(positions("plain-state", peePlan()));

        service.queueSync(7L);

        InOrder order = inOrder(accountRepository, sessionRepository);
        order.verify(accountRepository).findAllByMemberIdAndProviderForUpdate(7L, NatixisSyncService.PROVIDER);
        order.verify(sessionRepository).findByIdAndMemberIdForUpdate(3L, 7L);
        order.verify(accountRepository).save(any(Account.class));
        assertThat(session.getSyncStatus()).isEqualTo(NatixisSyncStatus.SUCCESS);
    }

    @Test
    void twoLabelOnlyFundsSharingTheirFirstCharactersKeepDistinctTickers() {
        NatixisSession session = activeSession(owner());
        arrangeCommittableSession(session);
        NatixisAdapter.PlanData plan = new NatixisAdapter.PlanData(PEE_ID, "Plan d'Épargne Entreprise", "PEE",
            "UES ACME", new BigDecimal("300.00"), List.of(
                new NatixisAdapter.Position(null, null, "IMPACT ISR PERFORMANCE SOLIDAIRE PART A",
                    new BigDecimal("10"), null, new BigDecimal("100.00"), null),
                new NatixisAdapter.Position(null, null, "IMPACT ISR PERFORMANCE SOLIDAIRE PART B",
                    new BigDecimal("10"), null, new BigDecimal("200.00"), null)
            ), true);
        when(port.fetchPlans("plain-state")).thenReturn(positions("plain-state", plan));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(NatixisSyncStatus.SUCCESS);
        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        List<String> tickers = holdingsCaptor.getValue().stream().map(AccountHolding::getTicker).toList();
        assertThat(tickers).hasSize(2).doesNotHaveDuplicates()
            .allSatisfy(ticker -> assertThat(ticker).hasSizeLessThanOrEqualTo(30)
                .startsWith("IMPACT-ISR-PERFORMANC"));
    }

    @Test
    void aJobThatCannotBeMarkedRunningFailsRetryablyInsteadOfStayingQueued() {
        NatixisSession session = activeSession(owner());
        arrangeQueuedSession(session);
        when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L))
            .thenThrow(new org.springframework.dao.QueryTimeoutException("db hiccup"))
            .thenReturn(Optional.of(session));

        NatixisSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(NatixisSyncStatus.FAILED);
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
    void bootRecoveryOnlyFailsJobsThatWentStale() {
        Instant before = Instant.now();

        service.recoverInterruptedSyncs();

        ArgumentCaptor<Instant> staleBefore = ArgumentCaptor.forClass(Instant.class);
        verify(sessionRepository).markInterruptedSyncsFailed(
            eq(List.of(NatixisSyncStatus.QUEUED, NatixisSyncStatus.RUNNING)),
            eq(NatixisSyncStatus.FAILED),
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
        when(sessionRepository.saveAndFlush(any(NatixisSession.class))).thenAnswer(invocation -> {
            NatixisSession stored = invocation.getArgument(0);
            lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(stored.getId(), 7L))
                .thenReturn(Optional.of(stored));
            lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(stored));
            return stored;
        });
        when(port.fetchPlans("fresh-state"))
            .thenThrow(new SyncException("transient", null, SidecarErrorCode.UPSTREAM_UNAVAILABLE.name()));

        NatixisSyncService.AuthInitResponse init = service.initiateAuth("saver@example.com", "secret", 7L);
        service.completeAuth("process", "123456", 7L);

        assertThat(init.mfaRequired()).isTrue();
        assertThat(init.mfaType()).isEqualTo("SMS");
        ArgumentCaptor<NatixisSession> stored = ArgumentCaptor.forClass(NatixisSession.class);
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
        return new NatixisAdapter.PositionsData(List.of(plans), sessionState);
    }

    private void arrangeCommittableSession(NatixisSession session) {
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

    private void arrangeQueuedSession(NatixisSession session) {
        lenient().when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(session.getId(), 7L))
            .thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
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

    private NatixisSession activeSession(FamilyMember member) {
        return NatixisSession.builder()
            .id(3L)
            .member(member)
            .sessionState("encrypted")
            .lastValidatedAt(Instant.now())
            .active(true)
            .syncStatus(NatixisSyncStatus.IDLE)
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
                new NatixisAdapter.Position(null, "QS0004086763", "AVENIR ACTIONS EUROPE I", new BigDecimal("50.0000"),
                    new BigDecimal("20.00"), new BigDecimal("1000.00"), new BigDecimal("90.91")),
                new NatixisAdapter.Position(null, null, "AVENIR MONETAIRE", new BigDecimal("25.0250"),
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
