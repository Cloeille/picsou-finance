package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.CardNature;
import com.picsou.model.CaisseEpargneSession;
import com.picsou.model.CaisseEpargneSyncStatus;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.FamilyMember;
import com.picsou.model.Transaction;
import com.picsou.port.CaisseEpargneErrorCode;
import com.picsou.port.CaisseEpargnePort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CaisseEpargneSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class CaisseEpargneSyncServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);

    @Mock CaisseEpargnePort port;
    @Mock CaisseEpargneSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountService accountService;
    @Mock CategorizationService categorizationService;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Captor ArgumentCaptor<Account> accountCaptor;
    @Captor ArgumentCaptor<Iterable<Transaction>> transactionsCaptor;

    CaisseEpargneSyncService service;

    @BeforeEach
    void setUp() {
        executeTransactionsImmediately();
        service = serviceWith(txTemplate, Runnable::run);
    }

    // -- mapping ------------------------------------------------------------

    @Test
    void queueSync_mapsEachKindToItsPicsouAccountType() {
        arrangeCommittableSync(new Fixture().current(), new Fixture().livret(), new Fixture().card());

        CaisseEpargneSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(CaisseEpargneSyncStatus.SUCCESS);
        verify(accountRepository, times(3)).save(accountCaptor.capture());
        List<Account> saved = accountCaptor.getAllValues();
        assertThat(saved).extracting(Account::getType).containsExactly(
            AccountType.CHECKING, AccountType.LIVRET_A, AccountType.CREDIT_CARD);
        assertThat(saved).extracting(Account::getExternalAccountId)
            .containsExactly("ce_1001", "ce_1002", "ce_2001");
        assertThat(saved).allSatisfy(account -> {
            assertThat(account.getProvider()).isEqualTo("Caisse d'Epargne");
            assertThat(account.getCurrency()).isEqualTo("EUR");
            assertThat(account.isManual()).isFalse();
            assertThat(account.getLastSyncedAt()).isNotNull();
        });
    }

    @Test
    void queueSync_storesTheCardAsALiabilityWithItsOutstandingAsBalance() {
        arrangeCommittableSync(new Fixture().current(), new Fixture().card());

        service.queueSync(7L);

        verify(accountRepository, times(2)).save(accountCaptor.capture());
        Account card = accountCaptor.getAllValues().get(1);
        assertThat(card.getType().isLiability()).isTrue();
        assertThat(card.getCurrentBalance()).isEqualByComparingTo("-87.10");
        assertThat(card.getCurrentBalance().signum()).isLessThanOrEqualTo(0);
    }

    @Test
    void queueSync_writesTheDailySnapshotOfEveryAccount() {
        arrangeCommittableSync(new Fixture().current(), new Fixture().card());

        service.queueSync(7L);

        verify(accountService).upsertSnapshot(any(Account.class), eq(new BigDecimal("1234.56")), eq(LocalDate.now()));
        verify(accountService).upsertSnapshot(any(Account.class), eq(new BigDecimal("-87.10")), eq(LocalDate.now()));
    }

    @Test
    void queueSync_doesNotStoreTheIbanNorTheCardLink() {
        // EB matching by IBAN is not in this slice: storing the IBAN would let the EB sync
        // adopt this account through findByIbanAndMemberId. A parent link would make the dashboard
        // skip the card (pocket model), so the card liability would vanish from net worth.
        arrangeCommittableSync(new Fixture().current(), new Fixture().card());

        service.queueSync(7L);

        verify(accountRepository, times(2)).save(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues()).allSatisfy(account -> {
            assertThat(account.getIban()).isNull();
            assertThat(account.getParentAccountId()).isNull();
        });
    }

    @Test
    void queueSync_storesTheCardNatureAndTheAmountAndDateStillToBeDebited() {
        arrangeCommittableSync(new Fixture().current(), new Fixture().card());

        service.queueSync(7L);

        verify(accountRepository, times(2)).save(accountCaptor.capture());
        Account current = accountCaptor.getAllValues().get(0);
        Account card = accountCaptor.getAllValues().get(1);
        assertThat(card.getCardNature()).isEqualTo(CardNature.DEFERRED_DEBIT);
        assertThat(card.getPaymentDueAmount()).isEqualByComparingTo("87.10");
        assertThat(card.getPaymentDueDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(card.getParentAccountId()).isNull();
        assertThat(current.getCardNature()).isNull();
        assertThat(current.getPaymentDueAmount()).isNull();
        assertThat(current.getPaymentDueDate()).isNull();
    }

    @Test
    void queueSync_clearsTheDueDateWhenNothingIsLeftToDebit() {
        Fixture f = new Fixture();
        CaisseEpargnePort.AccountData settled = new CaisseEpargnePort.AccountData("2001", "CARD", null,
            BigDecimal.ZERO, "EUR", null, false, null, null, null, null, "DEFERRED_DEBIT", "1001", null,
            List.of(), true);
        Account stored = Account.builder().id(40L).externalAccountId("ce_2001")
            .paymentDueAmount(new BigDecimal("87.10")).paymentDueDate(LocalDate.of(2026, 9, 30)).build();
        arrangeCommittableSync(f.current(), settled);
        when(accountRepository.findByExternalAccountIdAndMemberId("ce_2001", 7L)).thenReturn(Optional.of(stored));

        service.queueSync(7L);

        assertThat(stored.getPaymentDueDate()).isNull();
        assertThat(stored.getPaymentDueAmount()).isEqualByComparingTo("0");
    }

    @Test
    void queueSync_acceptsEachKnownCardNature() {
        for (String nature : List.of("IMMEDIATE_DEBIT", "DEFERRED_DEBIT", "CREDIT")) {
            org.mockito.Mockito.clearInvocations(accountRepository);
            Fixture f = new Fixture();
            arrangeCommittableSync(f.current(), f.withNature(f.card(), nature));

            service.queueSync(7L);

            verify(accountRepository, times(2)).save(accountCaptor.capture());
            assertThat(accountCaptor.getValue().getCardNature()).isEqualTo(CardNature.valueOf(nature));
        }
    }

    @Test
    void queueSync_refusesTheWholeSnapshotWhenACardHasAnUnknownNature() {
        Fixture f = new Fixture();
        CaisseEpargneSession session = arrangeCommittableSync(f.current(), f.withNature(f.card(), "PREPAID"));

        service.queueSync(7L);

        assertFailedWithNothingWritten(session, CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void queueSync_fallsBackToAReadableNameWhenTheBankSendsNone() {
        arrangeCommittableSync(new Fixture().card());

        service.queueSync(7L);

        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getName()).isNotBlank().hasSizeLessThanOrEqualTo(100);
    }

    // -- transactions -------------------------------------------------------

    @Test
    void queueSync_insertsNewTransactionsWithPrefixedExternalIds() {
        arrangeCommittableSync(new Fixture().current());

        service.queueSync(7L);

        verify(transactionRepository).saveAllAndFlush(transactionsCaptor.capture());
        List<Transaction> rows = toList(transactionsCaptor.getValue());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getExternalId()).isEqualTo("ce_t1");
            assertThat(row.getDate()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(row.getAmount()).isEqualByComparingTo("-12.30");
            assertThat(row.getDescription()).isEqualTo("SUPERMARCHE");
            assertThat(row.getNativeCurrency()).isEqualTo("EUR");
            assertThat(row.isManual()).isFalse();
            assertThat(row.getAccount().getExternalAccountId()).isEqualTo("ce_1001");
        });
    }

    @Test
    void queueSync_updatesAStoredTransactionInPlaceInsteadOfDuplicatingIt() {
        arrangeCommittableSync(new Fixture().current());
        Transaction stored = Transaction.builder().id(55L).externalId("ce_t1")
            .date(LocalDate.of(2026, 9, 1)).description("old").amount(new BigDecimal("-1.00")).build();
        when(transactionRepository.findByAccountIdAndIsManualFalse(anyLong())).thenReturn(List.of(stored));

        service.queueSync(7L);

        verify(transactionRepository).saveAllAndFlush(transactionsCaptor.capture());
        List<Transaction> rows = toList(transactionsCaptor.getValue());
        assertThat(rows).singleElement().isSameAs(stored);
        assertThat(stored.getDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(stored.getAmount()).isEqualByComparingTo("-12.30");
        assertThat(stored.getDescription()).isEqualTo("SUPERMARCHE");
    }

    @Test
    void queueSync_keepsLastOccurrenceWhenAnIdRepeatsInOneAccount() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.with(f.current(), List.of(
            f.tx("t1", "2026-10-01", "-1.00", "FIRST"),
            f.tx("t1", "2026-10-01", "-2.00", "SECOND"))));

        service.queueSync(7L);

        verify(transactionRepository).saveAllAndFlush(transactionsCaptor.capture());
        assertThat(toList(transactionsCaptor.getValue())).singleElement()
            .satisfies(row -> assertThat(row.getDescription()).isEqualTo("SECOND"));
    }

    @Test
    void queueSync_neverDeletesTransactions() {
        arrangeCommittableSync(new Fixture().current());

        service.queueSync(7L);

        verify(transactionRepository, never()).deleteAll(any());
        verify(transactionRepository, never()).deleteAll();
    }

    // -- unsupported --------------------------------------------------------

    @Test
    void queueSync_countsUnsupportedContractsInTheStatusWithoutCreatingAccounts() {
        arrangeCommittableSync(new CaisseEpargnePort.AccountsSnapshot(
            List.of(new Fixture().current()),
            List.of(new CaisseEpargnePort.Unsupported("9001", "7"),
                new CaisseEpargnePort.Unsupported("9002", "17"))));

        CaisseEpargneSyncService.SessionStatusResponse result = service.queueSync(7L);

        verify(accountRepository, times(1)).save(any(Account.class));
        assertThat(result.unsupportedCount()).isEqualTo(2);
        assertThat(result.unsupported()).extracting(CaisseEpargneSyncService.UnsupportedContract::externalId)
            .containsExactly("9001", "9002");
        assertThat(result.unsupported()).extracting(CaisseEpargneSyncService.UnsupportedContract::familyCode)
            .containsExactly("7", "17");
        assertThat(service.getStatus(7L).unsupportedCount()).isEqualTo(2);
    }

    // -- all-or-nothing -----------------------------------------------------

    @Test
    void queueSync_refusesTheWholeSnapshotWhenOneAccountHasAnUnknownKind() {
        CaisseEpargneSession session = arrangeCommittableSync(
            new Fixture().current(), new Fixture().withKind(new Fixture().livret(), "PEL"));

        service.queueSync(7L);

        assertFailedWithNothingWritten(session, CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void queueSync_refusesASnapshotTheSidecarFlaggedAsIncomplete() {
        Fixture f = new Fixture();
        CaisseEpargneSession session = arrangeCommittableSync(f.current(), f.incomplete(f.livret()));

        service.queueSync(7L);

        assertFailedWithNothingWritten(session, CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void queueSync_refusesACardWithAPositiveOutstanding() {
        Fixture f = new Fixture();
        CaisseEpargneSession session = arrangeCommittableSync(
            f.current(), f.withBalance(f.card(), "12.00"));

        service.queueSync(7L);

        assertFailedWithNothingWritten(session, CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void queueSync_refusesANonEuroAccount() {
        Fixture f = new Fixture();
        CaisseEpargneSession session = arrangeCommittableSync(f.current(), f.withCurrency(f.livret(), "USD"));

        service.queueSync(7L);

        assertFailedWithNothingWritten(session, CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void queueSync_refusesAnEmptyAccountList() {
        CaisseEpargneSession session = arrangeCommittableSync(
            new CaisseEpargnePort.AccountsSnapshot(List.of(), List.of()));

        service.queueSync(7L);

        assertFailedWithNothingWritten(session, CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void queueSync_refusesDuplicateAccounts() {
        CaisseEpargneSession session = arrangeCommittableSync(new Fixture().current(), new Fixture().current());

        service.queueSync(7L);

        assertFailedWithNothingWritten(session, CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void queueSync_refusesATransactionWithAnIdTooLongForTheColumn() {
        Fixture f = new Fixture();
        CaisseEpargneSession session = arrangeCommittableSync(f.with(f.current(), List.of(
            f.tx("x".repeat(120), "2026-10-01", "-1.00", "LONG"))));

        service.queueSync(7L);

        assertFailedWithNothingWritten(session, CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    /**
     * Atomic per sync: the whole write runs in ONE transaction, so a failure on the second
     * account rolls the first account back. Verified with a real TransactionTemplate over a
     * recording transaction manager rather than the pass-through mock the other tests use.
     */
    @Test
    void aFailureWhileWritingTheSecondAccountRollsTheWholeSyncBack() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(transactionStatus);
        CaisseEpargneSyncService transactional = serviceWith(new TransactionTemplate(txManager), Runnable::run);
        Fixture f = new Fixture();
        CaisseEpargneSession session = arrangeCommittableSync(f.current(), f.card());
        when(transactionRepository.saveAllAndFlush(any()))
            .thenReturn(List.of())
            .thenThrow(new IllegalStateException("db down"));

        transactional.queueSync(7L);

        // exactly one transaction (the data write) rolled back; every other one committed
        verify(txManager, times(1)).rollback(any());
        assertThat(session.getSyncStatus()).isEqualTo(CaisseEpargneSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(CaisseEpargneErrorCode.INTERNAL_ERROR);
        assertThat(session.getLastSyncCompletedAt()).isNotNull();
    }

    @Test
    void queueSync_doesNotResurrectAnAccountTheUserDeleted() {
        CaisseEpargneSession session = arrangeCommittableSync(new Fixture().current());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("ce_1001", 7L)).thenReturn(true);

        CaisseEpargneSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(CaisseEpargneSyncStatus.SUCCESS);
        assertThat(session.getSyncStatus()).isEqualTo(CaisseEpargneSyncStatus.SUCCESS);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).saveAllAndFlush(any());
    }

    // -- session lifecycle ----------------------------------------------------

    @Test
    void queueSync_refusesToRunWithoutASession() {
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.queueSync(7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.SESSION_EXPIRED.name()));
    }

    @Test
    void queueSync_refusesToRunWithAnInactiveSession() {
        CaisseEpargneSession session = activeSession(member());
        session.markQueued();
        session.markFailed(CaisseEpargneErrorCode.SESSION_EXPIRED, Instant.now());
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.queueSync(7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.SESSION_EXPIRED.name()));
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void queueSync_doesNotStackASecondJobOnAnInFlightSession() {
        CaisseEpargneSession session = activeSession(member());
        session.markQueued();
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));

        CaisseEpargneSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(CaisseEpargneSyncStatus.QUEUED);
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void anExpiredSessionIsDeactivatedSoTheUserIsToldToReconnect() {
        CaisseEpargneSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenThrow(
            new SyncException("gone", null, CaisseEpargneErrorCode.SESSION_EXPIRED.name()));

        service.queueSync(7L);

        assertThat(session.isActive()).isFalse();
        assertThat(session.getSyncStatus()).isEqualTo(CaisseEpargneSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(CaisseEpargneErrorCode.SESSION_EXPIRED);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void queueSync_turnsAnUnreadableEncryptedSessionIntoInvalidSessionStateAndDeactivatesIt() {
        CaisseEpargneSession session = activeSession(member());
        lenient().when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted")).thenThrow(new IllegalStateException("bad key"));

        assertThatThrownBy(() -> service.queueSync(7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.INVALID_SESSION_STATE.name()));

        assertThat(session.isActive()).isFalse();
        assertThat(session.getSyncStatus()).isEqualTo(CaisseEpargneSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(CaisseEpargneErrorCode.INVALID_SESSION_STATE);
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void aTransientFailureKeepsTheSessionUsable() {
        CaisseEpargneSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenThrow(
            new SyncException("down", null, CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE.name()));

        service.queueSync(7L);

        assertThat(session.isActive()).isTrue();
        assertThat(session.getLastSyncError()).isEqualTo(CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    void anUnexpectedExceptionIsRecordedAsAnInternalErrorWithoutLeakingItsMessage() {
        CaisseEpargneSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenThrow(new IllegalStateException("cookie=secret"));

        service.queueSync(7L);

        assertThat(session.getLastSyncError()).isEqualTo(CaisseEpargneErrorCode.INTERNAL_ERROR);
        assertThat(service.getStatus(7L).toString()).doesNotContain("secret");
    }

    @Test
    void queueSync_discardsAResultWhoseSessionDisappearedMidFlight() {
        CaisseEpargneSession session = activeSession(member());
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");
        when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L))
            .thenReturn(Optional.of(session))   // markRunning
            .thenReturn(Optional.empty());      // commit: gone
        when(port.fetchAccounts("plain-state")).thenReturn(
            new CaisseEpargnePort.AccountsSnapshot(List.of(new Fixture().current()), List.of()));

        service.queueSync(7L);

        verify(accountRepository, never()).save(any());
    }

    @Test
    void storeSession_encryptsTheStateReplacesTheOldRowAndDoesNotSyncByItself() {
        FamilyMember member = member();
        CaisseEpargneSession old = activeSession(member);
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(old));
        when(encryption.encrypt("cookies")).thenReturn("encrypted-cookies");
        when(sessionRepository.saveAndFlush(any(CaisseEpargneSession.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());

        service.storeSession("cookies", 7L);

        verify(sessionRepository).delete(old);
        ArgumentCaptor<CaisseEpargneSession> stored = ArgumentCaptor.forClass(CaisseEpargneSession.class);
        verify(sessionRepository).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getSessionState()).isEqualTo("encrypted-cookies");
        assertThat(stored.getValue().getSyncStatus()).isEqualTo(CaisseEpargneSyncStatus.IDLE);
        // on demand only: connecting stores the session and nothing else
        verify(port, never()).fetchAccounts(any());
        verify(port, never()).checkSession(any());
    }

    @Test
    void storeSession_refusesABlankState() {
        assertThatThrownBy(() -> service.storeSession("  ", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.INVALID_SESSION_STATE.name()));
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void clearSessionReportsTheSessionItDeleted() {
        CaisseEpargneSession session = activeSession(member());
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));

        assertThat(service.clearSession(7L)).isTrue();
        verify(sessionRepository).delete(session);
        // best-effort bank logout is NOT attempted: the port has no such call
        verify(port, never()).checkSession(any());
    }

    @Test
    void clearSessionReportsNothingToDeleteWhenThereIsNoSession() {
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.empty());

        assertThat(service.clearSession(7L)).isFalse();
        verify(sessionRepository, never()).delete(any());
    }

    @Test
    void getStatusIsInactiveWithoutASession() {
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());

        CaisseEpargneSyncService.SessionStatusResponse status = service.getStatus(7L);

        assertThat(status.isActive()).isFalse();
        assertThat(status.syncStatus()).isEqualTo(CaisseEpargneSyncStatus.IDLE);
        assertThat(status.unsupportedCount()).isZero();
        assertThat(status.unsupported()).isEmpty();
    }

    @Test
    void recoverInterruptedSyncs_failsJobsARestartLeftInFlight() {
        service.recoverInterruptedSyncs();

        verify(sessionRepository).markInterruptedSyncsFailed(
            eq(List.of(CaisseEpargneSyncStatus.QUEUED, CaisseEpargneSyncStatus.RUNNING)),
            eq(CaisseEpargneSyncStatus.FAILED),
            any(),
            eq(CaisseEpargneErrorCode.INTERNAL_ERROR));
    }

    @Test
    void theServiceExposesNoSchedulerOrAutoResyncHook() {
        // Sync is on demand only: nothing besides queueSync may start one.
        assertThat(java.util.Arrays.stream(CaisseEpargneSyncService.class.getDeclaredMethods())
            .map(java.lang.reflect.Method::getName))
            .noneMatch(name -> name.contains("resync") || name.contains("scheduled") || name.contains("Scheduled"));
        assertThat(java.util.Arrays.stream(CaisseEpargneSyncService.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class)))
            .isEmpty();
    }

    // -- settlement = internal transfer ---------------------------------------

    private static final String TRANSFER_SLUG = "virement-interne";

    /** Card group due 09-30 sums to -50.00 (two operations); a third one is not due yet. */
    private CaisseEpargnePort.AccountData settlementCard(Fixture f, String nature, String parent) {
        return f.cardWith(nature, parent,
            f.settlementTx("c1", "2026-09-02", "2026-09-30", "-30.00", null),
            f.settlementTx("c2", "2026-09-10", "2026-09-30", "-20.00", null),
            f.settlementTx("c3", "2026-10-02", "2026-10-31", "-87.10", null));
    }

    private Category transferCategory() {
        Category transfer = Category.builder().id(900L).slug(TRANSFER_SLUG).name("Virement interne")
            .kind(CategoryKind.TRANSFER).build();
        lenient().when(categorizationService.loadContext(7L)).thenReturn(
            new CategorizationService.CategorizationContext(List.of(), java.util.Map.of(TRANSFER_SLUG, transfer)));
        return transfer;
    }

    /** Every row written, by external id (the last write wins, as in the database). */
    private java.util.Map<String, Transaction> writtenRows() {
        verify(transactionRepository, org.mockito.Mockito.atLeastOnce()).saveAllAndFlush(transactionsCaptor.capture());
        java.util.Map<String, Transaction> rows = new java.util.LinkedHashMap<>();
        transactionsCaptor.getAllValues().forEach(batch -> toList(batch).forEach(r -> rows.put(r.getExternalId(), r)));
        return rows;
    }

    private void assertOnlyTagged(Category transfer, String... taggedIds) {
        java.util.Set<String> expected = java.util.Set.of(taggedIds);
        writtenRows().forEach((id, row) -> {
            if (expected.contains(id)) {
                assertThat(row.getCategoryRef()).as(id).isSameAs(transfer);
            } else {
                assertThat(row.getCategoryRef()).as(id).isNull();
            }
        });
    }

    @Test
    void queueSync_tagsTheCurrentAccountDebitThatSettlesTheCardGroupAsAnInternalTransfer() {
        Fixture f = new Fixture();
        Category transfer = transferCategory();
        arrangeCommittableSync(
            f.currentWith(
                f.settlementTx("p1", "2026-09-30", "2026-09-30", "-50.00", "04"),
                f.settlementTx("p2", "2026-09-30", "2026-09-30", "-7.00", "1")),
            settlementCard(f, "DEFERRED_DEBIT", "1001"));

        service.queueSync(7L);

        assertOnlyTagged(transfer, "ce_p1");
    }

    @Test
    void queueSync_comparesTheSettlementAmountExactlyIgnoringScale() {
        Fixture f = new Fixture();
        Category transfer = transferCategory();
        arrangeCommittableSync(
            f.currentWith(f.settlementTx("p1", "2026-09-30", "2026-09-30", "-50", "04")),
            settlementCard(f, "DEFERRED_DEBIT", "1001"));

        service.queueSync(7L);

        assertOnlyTagged(transfer, "ce_p1");
    }

    @Test
    void queueSync_acceptsASettlementRowWithoutTypeCode() {
        Fixture f = new Fixture();
        Category transfer = transferCategory();
        arrangeCommittableSync(
            f.currentWith(f.settlementTx("p1", "2026-09-30", "2026-09-30", "-50.00", null)),
            settlementCard(f, "DEFERRED_DEBIT", "1001"));

        service.queueSync(7L);

        assertOnlyTagged(transfer, "ce_p1");
    }

    @Test
    void queueSync_leavesARowAloneWhenItsTypeCodeIsNotTheCardSettlementOne() {
        Fixture f = new Fixture();
        transferCategory();
        arrangeCommittableSync(
            f.currentWith(f.settlementTx("p1", "2026-09-30", "2026-09-30", "-50.00", "1")),
            settlementCard(f, "DEFERRED_DEBIT", "1001"));

        service.queueSync(7L);

        assertOnlyTagged(null);
    }

    @Test
    void queueSync_leavesTheRowAloneWhenTheAmountIsOffByOneCent() {
        Fixture f = new Fixture();
        transferCategory();
        arrangeCommittableSync(
            f.currentWith(f.settlementTx("p1", "2026-09-30", "2026-09-30", "-50.01", "04")),
            settlementCard(f, "DEFERRED_DEBIT", "1001"));

        service.queueSync(7L);

        assertOnlyTagged(null);
    }

    @Test
    void queueSync_leavesTheRowAloneWhenTheDateIsNotTheDueDate() {
        Fixture f = new Fixture();
        transferCategory();
        arrangeCommittableSync(
            f.currentWith(f.settlementTx("p1", "2026-10-01", "2026-10-01", "-50.00", "04")),
            settlementCard(f, "DEFERRED_DEBIT", "1001"));

        service.queueSync(7L);

        assertOnlyTagged(null);
    }

    @Test
    void queueSync_leavesEveryRowAloneWhenSeveralRowsMatch() {
        Fixture f = new Fixture();
        transferCategory();
        arrangeCommittableSync(
            f.currentWith(
                f.settlementTx("p1", "2026-09-30", "2026-09-30", "-50.00", "04"),
                f.settlementTx("p2", "2026-09-30", "2026-09-30", "-50.00", null)),
            settlementCard(f, "DEFERRED_DEBIT", "1001"));

        service.queueSync(7L);

        assertOnlyTagged(null);
    }

    @Test
    void queueSync_neverOverwritesACategoryAlreadySet() {
        Fixture f = new Fixture();
        transferCategory();
        Category chosen = Category.builder().id(901L).slug("courses").name("Courses")
            .kind(CategoryKind.EXPENSE).build();
        Transaction stored = Transaction.builder().id(55L).externalId("ce_p1")
            .date(LocalDate.of(2026, 9, 30)).description("old").amount(new BigDecimal("-50.00"))
            .categoryRef(chosen).categoryManual(true).build();
        arrangeCommittableSync(
            f.currentWith(f.settlementTx("p1", "2026-09-30", "2026-09-30", "-50.00", "04")),
            settlementCard(f, "DEFERRED_DEBIT", "1001"));
        when(transactionRepository.findByAccountIdAndIsManualFalse(11L)).thenReturn(List.of(stored));

        service.queueSync(7L);

        assertThat(stored.getCategoryRef()).isSameAs(chosen);
        assertThat(stored.isCategoryManual()).isTrue();
    }

    @Test
    void queueSync_doesNotTagAnythingForAnImmediateDebitCard() {
        Fixture f = new Fixture();
        transferCategory();
        arrangeCommittableSync(
            f.currentWith(f.settlementTx("p1", "2026-09-30", "2026-09-30", "-50.00", "04")),
            settlementCard(f, "IMMEDIATE_DEBIT", "1001"));

        service.queueSync(7L);

        assertOnlyTagged(null);
    }

    @Test
    void queueSync_doesNotTagAnythingForACreditCard() {
        Fixture f = new Fixture();
        transferCategory();
        arrangeCommittableSync(
            f.currentWith(f.settlementTx("p1", "2026-09-30", "2026-09-30", "-50.00", "04")),
            settlementCard(f, "CREDIT", "1001"));

        service.queueSync(7L);

        assertOnlyTagged(null);
    }

    @Test
    void queueSync_doesNotTagAnythingWhenTheParentIsNotInTheSnapshot() {
        Fixture f = new Fixture();
        transferCategory();
        arrangeCommittableSync(
            f.currentWith(f.settlementTx("p1", "2026-09-30", "2026-09-30", "-50.00", "04")),
            settlementCard(f, "DEFERRED_DEBIT", "7777"));

        service.queueSync(7L);

        assertOnlyTagged(null);
    }

    @Test
    void queueSync_doesNotTagAnythingWhenTheCardHasNoParent() {
        Fixture f = new Fixture();
        transferCategory();
        arrangeCommittableSync(
            f.currentWith(f.settlementTx("p1", "2026-09-30", "2026-09-30", "-50.00", "04")),
            settlementCard(f, "DEFERRED_DEBIT", null));

        service.queueSync(7L);

        assertOnlyTagged(null);
    }

    @Test
    void queueSync_ignoresCardGroupsThatAreNotDueYetButSettlesTheOneDueToday() {
        Fixture f = new Fixture();
        Category transfer = transferCategory();
        CaisseEpargnePort.AccountData card = f.cardWith("DEFERRED_DEBIT", "1001",
            f.settlementTx("c1", "2026-09-20", "2026-10-07", "-12.00", null),   // due today
            f.settlementTx("c2", "2026-10-02", "2026-10-31", "-87.10", null));  // future
        arrangeCommittableSync(
            f.currentWith(
                f.settlementTx("p1", "2026-10-07", "2026-10-07", "-12.00", "04"),
                f.settlementTx("p2", "2026-10-31", "2026-10-31", "-87.10", "04")),
            card);

        service.queueSync(7L);

        assertOnlyTagged(transfer, "ce_p1");
    }

    @Test
    void queueSync_leavesAGroupWhoseOperationsAreOutsideTheFetchedWindowUncategorized() {
        // The parent debit covers a third operation the card page no longer returns: no fuzzy match.
        Fixture f = new Fixture();
        transferCategory();
        arrangeCommittableSync(
            f.currentWith(f.settlementTx("p1", "2026-09-30", "2026-09-30", "-80.00", "04")),
            settlementCard(f, "DEFERRED_DEBIT", "1001"));

        service.queueSync(7L);

        assertOnlyTagged(null);
    }

    @Test
    void queueSync_skipsTaggingWhenTheMemberHasNoTransferCategory() {
        Fixture f = new Fixture();
        lenient().when(categorizationService.loadContext(7L)).thenReturn(
            new CategorizationService.CategorizationContext(List.of(), java.util.Map.of()));
        arrangeCommittableSync(
            f.currentWith(f.settlementTx("p1", "2026-09-30", "2026-09-30", "-50.00", "04")),
            settlementCard(f, "DEFERRED_DEBIT", "1001"));

        service.queueSync(7L);

        assertOnlyTagged(null);
    }

    @Test
    void queueSync_keepsTheCardOperationsAsExpensesOnTheCardAccount() {
        Fixture f = new Fixture();
        Category transfer = transferCategory();
        arrangeCommittableSync(
            f.currentWith(f.settlementTx("p1", "2026-09-30", "2026-09-30", "-50.00", "04")),
            settlementCard(f, "DEFERRED_DEBIT", "1001"));

        service.queueSync(7L);

        java.util.Map<String, Transaction> rows = writtenRows();
        assertThat(rows.keySet()).contains("ce_c1", "ce_c2", "ce_c3");
        assertThat(rows.get("ce_c1").getCategoryRef()).isNull();
        assertThat(rows.get("ce_c2").getCategoryRef()).isNull();
        assertThat(rows.get("ce_p1").getCategoryRef()).isSameAs(transfer);
    }

    @Test
    void queueSync_isIdempotentWhenTheSyncRunsAgain() {
        Fixture f = new Fixture();
        Category transfer = transferCategory();
        arrangeCommittableSync(
            f.currentWith(f.settlementTx("p1", "2026-09-30", "2026-09-30", "-50.00", "04")),
            settlementCard(f, "DEFERRED_DEBIT", "1001"));
        service.queueSync(7L);
        Transaction tagged = writtenRows().get("ce_p1");
        assertThat(tagged.getCategoryRef()).isSameAs(transfer);
        // second pass: the database now returns the row with its category
        when(transactionRepository.findByAccountIdAndIsManualFalse(11L)).thenReturn(List.of(tagged));

        service.queueSync(7L);

        assertThat(tagged.getCategoryRef()).isSameAs(transfer);
        assertThat(writtenRows().get("ce_p1")).isSameAs(tagged);
        assertThat(writtenRows().values().stream().filter(r -> r.getCategoryRef() != null)).hasSize(1);
    }

    // -- helpers ------------------------------------------------------------

    private void assertFailedWithNothingWritten(CaisseEpargneSession session, CaisseEpargneErrorCode code) {
        assertThat(session.getSyncStatus()).isEqualTo(CaisseEpargneSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(code);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).saveAllAndFlush(any());
        verify(accountService, never()).upsertSnapshot(any(), any(), any());
    }

    private CaisseEpargneSession arrangeCommittableSync(CaisseEpargnePort.AccountData... accounts) {
        return arrangeCommittableSync(new CaisseEpargnePort.AccountsSnapshot(List.of(accounts), List.of()));
    }

    private CaisseEpargneSession arrangeCommittableSync(CaisseEpargnePort.AccountsSnapshot snapshot) {
        FamilyMember member = member();
        CaisseEpargneSession session = activeSession(member);
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(snapshot);
        lenient().when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        lenient().when(accountRepository.findByExternalAccountIdAndMemberId(any(), eq(7L)))
            .thenReturn(Optional.empty());
        AtomicLong ids = new AtomicLong(10);
        lenient().when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            if (account.getId() == null) account.setId(ids.incrementAndGet());
            return account;
        });
        return session;
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

    private CaisseEpargneSyncService serviceWith(TransactionTemplate template, Executor executor) {
        return new CaisseEpargneSyncService(
            port, sessionRepository, accountRepository, transactionRepository,
            memberRepository, accountService, categorizationService, encryption, template, executor,
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
    }

    private void arrangeQueuedSession(CaisseEpargneSession session) {
        lenient().when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(session.getId(), 7L))
            .thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        lenient().when(encryption.decrypt("encrypted")).thenReturn("plain-state");
    }

    private FamilyMember member() {
        return FamilyMember.builder().id(7L).displayName("Owner").build();
    }

    private CaisseEpargneSession activeSession(FamilyMember member) {
        return CaisseEpargneSession.builder()
            .id(3L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(CaisseEpargneSyncStatus.IDLE)
            .build();
    }

    private static <T> List<T> toList(Iterable<T> iterable) {
        List<T> list = new ArrayList<>();
        iterable.forEach(list::add);
        return list;
    }

    /** Synthetic payloads only: invented ids, labels and amounts. */
    private static final class Fixture {
        CaisseEpargnePort.Transaction tx(String id, String date, String amount, String label) {
            return new CaisseEpargnePort.Transaction(id, LocalDate.parse(date), LocalDate.parse(date),
                new BigDecimal(amount), "EUR", label, null);
        }

        CaisseEpargnePort.Transaction settlementTx(String id, String date, String due, String amount, String typeCode) {
            return new CaisseEpargnePort.Transaction(id, LocalDate.parse(date), LocalDate.parse(due),
                new BigDecimal(amount), "EUR", "OP " + id, typeCode);
        }

        /** Current account 1001 whose debit of the 09-30 card group is 50.00, plus a card with that group. */
        CaisseEpargnePort.AccountData currentWith(CaisseEpargnePort.Transaction... rows) {
            return with(current(), List.of(rows));
        }

        CaisseEpargnePort.AccountData cardWith(String nature, String parent, CaisseEpargnePort.Transaction... rows) {
            CaisseEpargnePort.AccountData base = withNature(card(), nature);
            return new CaisseEpargnePort.AccountData(base.externalId(), base.kind(), base.name(), base.balance(),
                base.currency(), base.iban(), base.ibanAmbiguous(), base.authorizedOverdraft(), base.ceiling(),
                base.remainingDepositCapacity(), base.fillingRatio(), nature, parent, base.nextDueDate(),
                List.of(rows), base.snapshotComplete());
        }

        CaisseEpargnePort.AccountData current() {
            return new CaisseEpargnePort.AccountData("1001", "CURRENT_ACCOUNT", "COMPTE COURANT",
                new BigDecimal("1234.56"), "EUR", "FR0000000000000000000000000", false,
                new BigDecimal("500.00"), null, null, null, null, null, null,
                List.of(tx("t1", "2026-10-01", "-12.30", "SUPERMARCHE")), true);
        }

        CaisseEpargnePort.AccountData livret() {
            return new CaisseEpargnePort.AccountData("1002", "LIVRET_A", "LIVRET A",
                new BigDecimal("5000.00"), "EUR", null, false,
                null, new BigDecimal("22950.00"), new BigDecimal("17950.00"), new BigDecimal("0.2179"),
                null, null, null, List.of(), true);
        }

        CaisseEpargnePort.AccountData card() {
            return new CaisseEpargnePort.AccountData("2001", "CARD", null,
                new BigDecimal("-87.10"), "EUR", null, false,
                null, null, null, null, "DEFERRED_DEBIT", "1001", LocalDate.of(2026, 10, 31),
                List.of(new CaisseEpargnePort.Transaction("c1", LocalDate.of(2026, 10, 2),
                    LocalDate.of(2026, 10, 31), new BigDecimal("-87.10"), "EUR", "RESTAURANT", "04")), true);
        }

        CaisseEpargnePort.AccountData with(CaisseEpargnePort.AccountData a, List<CaisseEpargnePort.Transaction> txs) {
            return new CaisseEpargnePort.AccountData(a.externalId(), a.kind(), a.name(), a.balance(), a.currency(),
                a.iban(), a.ibanAmbiguous(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(), txs, a.snapshotComplete());
        }

        CaisseEpargnePort.AccountData withKind(CaisseEpargnePort.AccountData a, String kind) {
            return new CaisseEpargnePort.AccountData(a.externalId(), kind, a.name(), a.balance(), a.currency(),
                a.iban(), a.ibanAmbiguous(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(), a.transactions(), a.snapshotComplete());
        }

        CaisseEpargnePort.AccountData withBalance(CaisseEpargnePort.AccountData a, String balance) {
            return new CaisseEpargnePort.AccountData(a.externalId(), a.kind(), a.name(), new BigDecimal(balance),
                a.currency(), a.iban(), a.ibanAmbiguous(), a.authorizedOverdraft(), a.ceiling(),
                a.remainingDepositCapacity(), a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(),
                a.transactions(), a.snapshotComplete());
        }

        CaisseEpargnePort.AccountData withCurrency(CaisseEpargnePort.AccountData a, String currency) {
            return new CaisseEpargnePort.AccountData(a.externalId(), a.kind(), a.name(), a.balance(), currency,
                a.iban(), a.ibanAmbiguous(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(), a.transactions(), a.snapshotComplete());
        }

        CaisseEpargnePort.AccountData withNature(CaisseEpargnePort.AccountData a, String nature) {
            return new CaisseEpargnePort.AccountData(a.externalId(), a.kind(), a.name(), a.balance(), a.currency(),
                a.iban(), a.ibanAmbiguous(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), nature, a.parentExternalId(), a.nextDueDate(), a.transactions(),
                a.snapshotComplete());
        }

        CaisseEpargnePort.AccountData incomplete(CaisseEpargnePort.AccountData a) {
            return new CaisseEpargnePort.AccountData(a.externalId(), a.kind(), a.name(), a.balance(), a.currency(),
                a.iban(), a.ibanAmbiguous(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(), a.transactions(), false);
        }
    }
}
