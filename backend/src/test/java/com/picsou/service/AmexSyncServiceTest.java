package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.model.Account;
import com.picsou.model.AmexSession;
import com.picsou.model.AmexSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.model.Transaction;
import com.picsou.port.AmexPort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.AmexSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class AmexSyncServiceTest {
    @Mock AmexPort port;
    @Mock AmexSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock com.picsou.repository.TransactionRepository transactionRepository;
    @Mock AccountService accountService;
    @Mock FortuneoTransactionWriter transactionWriter;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Captor ArgumentCaptor<List<Transaction>> transactionsCaptor;

    AmexSyncService service;

    @BeforeEach
    void setUp() {
        executeTransactionsImmediately();
        service = serviceWith(Runnable::run);
    }

    @Test
    void syncPersistsConventionalAmericanExpressProviderName() {
        FamilyMember member = member();
        AmexSession session = activeSession(member);
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(accountData("amex_1")));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(accountRepository).save(org.mockito.ArgumentMatchers.argThat(account ->
            "American Express".equals(account.getProvider())));
    }

    @Test
    void firstSync_savesTransactionsAlongsideTheAccount() {
        AmexPort.Transaction charge = charge(1, "Coffee shop", "-4.50");

        Reconciliation result = routineSync(List.of(), charge);

        assertThat(result.obsolete()).isEmpty();
        assertThat(result.upserts()).singleElement().satisfies(tx -> {
            assertThat(tx.getDescription()).isEqualTo("Coffee shop");
            assertThat(tx.getAmount()).isEqualByComparingTo("-4.50");
            assertThat(tx.getExternalId()).startsWith("amex_tx_");
        });
    }

    @Test
    void resync_updatesTheStoredRowInsteadOfDuplicatingIt() {
        AmexPort.Transaction charge = charge(1, "Coffee shop", "-4.50");
        List<Transaction> stored = routineSync(List.of(), charge).upserts();

        Reconciliation second = routineSync(stored, charge);

        assertThat(second.obsolete()).isEmpty();
        assertThat(second.upserts()).singleElement().isSameAs(stored.getFirst());
    }

    @Test
    void routineSync_keepsOlderTransactionsTheLatestPageNoLongerReturns() {
        // 150 charges over the 90-day window, newest first like the sidecar. A routine sync
        // only gets the latest 100; the 50 older ones -- some sharing the page's oldest day --
        // must survive it.
        List<AmexPort.Transaction> all = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            all.add(charge(i * 89 / 149, "Shop " + i, "-" + (i + 1)));
        }
        List<Transaction> stored = historySync(all);
        assertThat(stored).hasSize(150);

        Reconciliation routine = routineSync(stored, all.subList(0, 100).toArray(AmexPort.Transaction[]::new));

        assertThat(routine.obsolete()).isEmpty();
        assertThat(routine.upserts()).hasSize(100).allSatisfy(tx -> assertThat(stored).contains(tx));
    }

    @Test
    void routineSync_dropsAPendingChargeThatSettledOrVanished() {
        AmexPort.Transaction older = charge(10, "Bookshop", "-12.00");
        AmexPort.Transaction pending = charge(2, "PENDING Grocer", "-30.00");
        AmexPort.Transaction cancelled = charge(1, "PENDING Hotel hold", "-150.00");
        List<Transaction> stored = routineSync(List.of(), older, pending, cancelled).upserts();

        AmexPort.Transaction posted = charge(1, "Grocer", "-30.00");
        Reconciliation result = routineSync(stored, older, posted);

        assertThat(result.obsolete())
            .extracting(Transaction::getDescription)
            .containsExactlyInAnyOrder("PENDING Grocer", "PENDING Hotel hold");
        assertThat(result.upserts())
            .extracting(Transaction::getDescription)
            .containsExactly("Bookshop", "Grocer");
    }

    @Test
    void identicalPurchasesOnTheSameDay_stayTwoRowsAcrossSyncs() {
        AmexPort.Transaction ticket = charge(1, "Metro ticket", "-2.15");

        List<Transaction> first = routineSync(List.of(), ticket, ticket).upserts();
        assertThat(first).hasSize(2).extracting(Transaction::getExternalId).doesNotHaveDuplicates();

        Reconciliation second = routineSync(first, ticket, ticket);
        assertThat(second.obsolete()).isEmpty();
        assertThat(second.upserts()).containsExactlyElementsOf(first);
    }

    @Test
    void identicalPurchasesOnTheSameDay_historyImportsBothOnceOnly() {
        AmexPort.Transaction ticket = charge(1, "Metro ticket", "-2.15");

        List<Transaction> first = historySync(List.of(ticket, ticket));
        assertThat(first).hasSize(2).extracting(Transaction::getExternalId).doesNotHaveDuplicates();

        assertThat(historySync(List.of(ticket, ticket), first)).isEmpty();
    }

    @Test
    void historyIdsMatchRoutineIds_soARoutineSyncAfterRecoveryDropsNothing() {
        AmexPort.Transaction ticket = charge(1, "Metro ticket", "-2.15");
        List<Transaction> stored = historySync(List.of(ticket, ticket));

        Reconciliation routine = routineSync(stored, ticket, ticket);

        assertThat(routine.obsolete()).isEmpty();
        assertThat(routine.upserts()).containsExactlyInAnyOrderElementsOf(stored);
    }

    @Test
    void emptyTransactionList_leavesExistingRowsAlone() {
        FamilyMember member = member();
        AmexSession session = activeSession(member);
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(accountData("amex_1")));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(20L);

        AmexSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(AmexSyncStatus.SUCCESS);
        verifyNoInteractions(transactionWriter);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"04/03/26", "2026-13-01"})
    void unparseableDueDate_isDroppedWithoutFailingTheSync(String dueDate) {
        FamilyMember member = member();
        AmexSession session = activeSession(member);
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(accountData("amex_1", dueDate)));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(20L);

        AmexSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(AmexSyncStatus.SUCCESS);
        verify(accountRepository).save(org.mockito.ArgumentMatchers.argThat(account ->
            account.getPaymentDueDate() == null && account.getPaymentDueAmount() != null));
    }

    @Test
    void completionQueuesFullHistoryImport() {
        // Connecting (or reconnecting) must backfill the provider's whole history
        // automatically -- not just the 90-day window -- so the user never has to
        // reach for the separate recovery action after a fresh login.
        FamilyMember member = member();
        when(port.completeAuth("process-123", "123456")).thenReturn("plain-state");
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.empty());
        AmexSession stored = AmexSession.builder()
            .id(3L).member(member).sessionState("encrypted")
            .active(true).syncStatus(AmexSyncStatus.QUEUED)
            .lastValidatedAt(java.time.Instant.now())
            .build();
        when(sessionRepository.saveAndFlush(any(AmexSession.class))).thenReturn(stored);
        when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L)).thenReturn(Optional.of(stored));
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(stored));
        lenient().when(encryption.encrypt("plain-state")).thenReturn("encrypted");

        AmexPort.Transaction oldCharge = new AmexPort.Transaction(null, "2020-01-02", "Old store", new BigDecimal("-7.00"));
        when(port.fetchTransactionHistory("plain-state")).thenReturn(List.of(accountData("amex_1", oldCharge)));
        arrangeNewAccountPersistence(20L);
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of());

        var result = service.completeAuth("process-123", "123456", 7L);

        // Status polling would read the in-memory session object; only assert
        // that the completion accepted the job and resolved the history import.
        assertThat(result).isNotNull();
        verify(port).fetchTransactionHistory("plain-state");
        verify(port, org.mockito.Mockito.never()).fetchAccounts(anyString());
        verify(transactionRepository).saveAllAndFlush(transactionsCaptor.capture());
        assertThat(transactionsCaptor.getValue()).singleElement().satisfies(tx ->
            assertThat(tx.getDate()).isEqualTo(LocalDate.of(2020, 1, 2)));
    }

    @Test
    void historyRecoveryMergesWithoutDeletingExistingTransactions() {
        FamilyMember member = member();
        AmexSession session = activeSession(member);
        arrangeQueuedSession(session);
        AmexPort.Transaction oldCharge = new AmexPort.Transaction(null, "2020-01-02", "Old store", new BigDecimal("-7.00"));
        when(port.fetchTransactionHistory("plain-state")).thenReturn(List.of(accountData("amex_1", oldCharge)));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(20L);
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of());

        var result = service.queueHistoryRecovery(7L);

        assertThat(result.syncStatus()).isEqualTo(AmexSyncStatus.SUCCESS);
        verify(transactionRepository).saveAllAndFlush(transactionsCaptor.capture());
        assertThat(transactionsCaptor.getValue()).singleElement().satisfies(tx -> {
            assertThat(tx.getDate()).isEqualTo(LocalDate.of(2020, 1, 2));
            assertThat(tx.getExternalId()).startsWith("amex_tx_");
        });
        verify(transactionWriter, org.mockito.Mockito.never()).replaceRecentTransactions(any(), any(), any());
    }

    private record Reconciliation(List<Transaction> obsolete, List<Transaction> upserts) {}

    private Reconciliation routineSync(List<Transaction> stored, AmexPort.Transaction... response) {
        arrangeSync(stored);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(accountData("amex_1", response)));

        service.queueSync(7L);

        ArgumentCaptor<List<Transaction>> obsolete = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Transaction>> upserts = ArgumentCaptor.forClass(List.class);
        verify(transactionWriter).reconcileHistory(obsolete.capture(), upserts.capture());
        return new Reconciliation(obsolete.getValue(), upserts.getValue());
    }

    private List<Transaction> historySync(List<AmexPort.Transaction> response) {
        return historySync(response, List.of());
    }

    private List<Transaction> historySync(List<AmexPort.Transaction> response, List<Transaction> stored) {
        arrangeSync(stored);
        when(port.fetchTransactionHistory("plain-state"))
            .thenReturn(List.of(accountData("amex_1", response.toArray(AmexPort.Transaction[]::new))));

        service.queueHistoryRecovery(7L);

        verify(transactionRepository).saveAllAndFlush(transactionsCaptor.capture());
        return transactionsCaptor.getValue();
    }

    private void arrangeSync(List<Transaction> stored) {
        clearInvocations(transactionWriter, transactionRepository);
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(20L);
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(stored);
    }

    private AmexPort.Transaction charge(int daysAgo, String label, String amount) {
        return new AmexPort.Transaction(null, LocalDate.now().minusDays(daysAgo).toString(), label, new BigDecimal(amount));
    }

    private AmexPort.AccountData accountData(String externalId, AmexPort.Transaction... transactions) {
        return accountData(externalId, LocalDate.now().plusDays(5).toString(), transactions);
    }

    private AmexPort.AccountData accountData(String externalId, String dueDate, AmexPort.Transaction... transactions) {
        return new AmexPort.AccountData(
            externalId,
            "American Express",
            com.picsou.model.AccountType.CREDIT_CARD,
            new BigDecimal("-100.00"),
            new BigDecimal("75.00"),
            new BigDecimal("42.50"),
            dueDate,
            null,
            500L,
            List.of(transactions),
            true
        );
    }

    private void arrangeQueuedSession(AmexSession session) {
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByIdAndMemberIdForUpdate(session.getId(), 7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        lenient().when(encryption.decrypt("encrypted")).thenReturn("plain-state");
    }

    private FamilyMember member() {
        return FamilyMember.builder().id(7L).displayName("Owner").build();
    }

    private AmexSession activeSession(FamilyMember member) {
        return AmexSession.builder()
            .id(3L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(AmexSyncStatus.IDLE)
            .build();
    }

    private void arrangeNewAccountPersistence(Long id) {
        when(accountRepository.findByExternalAccountIdAndMemberId(anyString(), eq(7L)))
            .thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(anyString(), eq(7L)))
            .thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(id);
            return account;
        });
    }

    private AmexSyncService serviceWith(Executor executor) {
        return new AmexSyncService(
            port,
            sessionRepository,
            accountRepository,
            memberRepository,
            transactionRepository,
            accountService,
            transactionWriter,
            encryption,
            txTemplate,
            executor
        );
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
}
