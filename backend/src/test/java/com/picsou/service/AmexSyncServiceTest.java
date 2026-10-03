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
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
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
        FamilyMember member = member();
        AmexSession session = activeSession(member);
        arrangeQueuedSession(session);
        AmexPort.Transaction charge = new AmexPort.Transaction(
            null, LocalDate.now().minusDays(1).toString(), "Coffee shop", new BigDecimal("-4.50")
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(accountData("amex_1", charge)));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(20L);

        AmexSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(AmexSyncStatus.SUCCESS);
        verify(transactionWriter).replaceRecentTransactions(
            eq(20L), any(LocalDate.class), transactionsCaptor.capture());
        List<Transaction> inserted = transactionsCaptor.getValue();
        assertThat(inserted).singleElement().satisfies(tx -> {
            assertThat(tx.getDescription()).isEqualTo("Coffee shop");
            assertThat(tx.getAmount()).isEqualByComparingTo("-4.50");
        });
    }

    @Test
    void resync_replacesTheWindowInsteadOfDuplicatingRows() {
        // The sidecar returns the same transaction on a second sync (no stable id to
        // reconcile on); a real re-sync would just re-report the same charge. The writer
        // must be told to replace the window each time, so the repository-level dedup lives
        // in FortuneoTransactionWriter.replaceRecentTransactions rather than this service
        // appending a second copy.
        FamilyMember member = member();
        AmexSession session = activeSession(member);
        arrangeQueuedSession(session);
        AmexPort.Transaction charge = new AmexPort.Transaction(
            null, LocalDate.now().minusDays(1).toString(), "Coffee shop", new BigDecimal("-4.50")
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(accountData("amex_1", charge)));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);
        service.queueSync(7L);

        verify(transactionWriter, org.mockito.Mockito.times(2)).replaceRecentTransactions(
            eq(20L), any(LocalDate.class), transactionsCaptor.capture());
        for (List<Transaction> inserted : transactionsCaptor.getAllValues()) {
            assertThat(inserted).singleElement().satisfies(tx ->
                assertThat(tx.getDescription()).isEqualTo("Coffee shop"));
        }
    }

    @Test
    void duplicateTransactionsWithSameIdentity_arePersistedOnce() {
        FamilyMember member = member();
        AmexSession session = activeSession(member);
        arrangeQueuedSession(session);
        AmexPort.Transaction posted = new AmexPort.Transaction(
            null, LocalDate.now().minusDays(1).toString(), "Coffee shop", new BigDecimal("-4.50")
        );
        AmexPort.Transaction pendingDuplicate = new AmexPort.Transaction(
            null, LocalDate.now().minusDays(1).toString(), "Coffee shop", new BigDecimal("-4.50")
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(accountData("amex_1", posted, pendingDuplicate)));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionWriter).replaceRecentTransactions(eq(20L), any(LocalDate.class), transactionsCaptor.capture());
        assertThat(transactionsCaptor.getValue()).hasSize(1);
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
        verify(transactionWriter, org.mockito.Mockito.never())
            .replaceRecentTransactions(any(), any(), any());
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

    private AmexPort.AccountData accountData(String externalId, AmexPort.Transaction... transactions) {
        return new AmexPort.AccountData(
            externalId,
            "American Express",
            com.picsou.model.AccountType.CREDIT_CARD,
            new BigDecimal("-100.00"),
            new BigDecimal("75.00"),
            new BigDecimal("42.50"),
            LocalDate.now().plusDays(5).toString(),
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
