package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.BourseDirectSession;
import com.picsou.model.BourseDirectSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.port.BourseDirectErrorCode;
import com.picsou.port.BourseDirectPort;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BourseDirectSessionRepository;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class BourseDirectSyncServiceTest {
    @Mock BourseDirectPort port;
    @Mock BourseDirectSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountService accountService;
    @Mock OpenFigiIsinConverter isinConverter;
    @Mock SecurityIdentityService identityService;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Captor ArgumentCaptor<Account> accountCaptor;
    @Captor ArgumentCaptor<List<AccountHolding>> holdingsCaptor;

    BourseDirectSyncService service;

    @BeforeEach
    void setUp() {
        executeTransactionsImmediately();
        service = serviceWith(Runnable::run);
    }

    @Test
    void queueSync_commitsCompletePortfolioThenWritesSnapshotFromCurrentPositions() {
        FamilyMember member = member();
        BourseDirectSession session = activeSession(member);
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(completeAccount()));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        Account existingAccount = Account.builder()
            .id(11L)
            .member(member)
            .name("Edited account")
            .type(AccountType.OTHER)
            .provider("Edited provider")
            .currency("USD")
            .currentBalance(BigDecimal.ZERO)
            .isManual(true)
            .build();
        when(accountRepository.findByExternalAccountIdAndMemberId("bd_pea-123", 7L))
            .thenReturn(Optional.of(existingAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            return account;
        });

        BourseDirectSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(BourseDirectSyncStatus.SUCCESS);
        verify(accountRepository).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();
        assertThat(savedAccount.getProvider()).isEqualTo("Bourse Direct");
        assertThat(savedAccount.getCurrency()).isEqualTo("EUR");
        assertThat(savedAccount.isManual()).isFalse();
        assertThat(savedAccount.getType()).isEqualTo(AccountType.PEA);
        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        AccountHolding holding = holdingsCaptor.getValue().getFirst();
        assertThat(holding.getTicker()).isEqualTo("ACME");
        assertThat(holding.getQuoteCurrency()).isEqualTo("EUR");
        assertThat(holding.getAverageBuyIn()).isEqualByComparingTo("80");
        assertThat(holding.getProviderValueEur()).isEqualByComparingTo("1000");
        assertThat(holding.getProviderPnlEur()).isEqualByComparingTo("200");

        InOrder writeOrder = inOrder(holdingRepository, accountService);
        writeOrder.verify(holdingRepository).saveAll(any());
        writeOrder.verify(holdingRepository).flush();
        writeOrder.verify(accountService).upsertSnapshot(
            any(Account.class),
            eq(new BigDecimal("1250")),
            eq(new BigDecimal("1050")),
            eq(java.time.LocalDate.now())
        );
    }

    @Test
    void incompleteSnapshot_failsWithoutDeletingExistingHoldings() {
        BourseDirectSession session = activeSession(member());
        arrangeQueuedSession(session);
        BourseDirectPort.AccountData incomplete = new BourseDirectPort.AccountData(
            "pea-123", "PEA", AccountType.PEA,
            new BigDecimal("1250"), new BigDecimal("250"), List.of(), false
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(incomplete));

        BourseDirectSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(BourseDirectSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(BourseDirectErrorCode.PORTFOLIO_INCOMPLETE);
        verify(holdingRepository, never()).deleteByAccountId(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void nativeQuoteWithoutCurrency_rejectsTheWholeSnapshot() {
        BourseDirectSession session = activeSession(member());
        arrangeQueuedSession(session);
        BourseDirectPort.Position ambiguous = new BourseDirectPort.Position(
            null, "ACME", "Acme", BigDecimal.ONE, new BigDecimal("80"),
            new BigDecimal("100"), null, new BigDecimal("100"), new BigDecimal("20")
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(new BourseDirectPort.AccountData(
            "pea-123", "PEA", AccountType.PEA,
            new BigDecimal("100"), BigDecimal.ZERO, List.of(ambiguous), true
        )));

        BourseDirectSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(BourseDirectSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(BourseDirectErrorCode.INVALID_DATA);
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void expiredSession_isInvalidatedInFollowUpTransactionAndRemainsObservable() {
        BourseDirectSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenThrow(new SyncException(
            "Session expired", null, BourseDirectErrorCode.SESSION_EXPIRED.name()
        ));

        BourseDirectSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.isActive()).isFalse();
        assertThat(result.syncStatus()).isEqualTo(BourseDirectSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(BourseDirectErrorCode.SESSION_EXPIRED);
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void completeAuth_returnsQueuedBeforePortfolioFetchRuns() {
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        BourseDirectSyncService delayedService = serviceWith(queuedTask::set);
        FamilyMember member = member();
        BourseDirectSession stored = BourseDirectSession.builder()
            .id(9L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(BourseDirectSyncStatus.QUEUED)
            .build();
        when(port.completeAuth("process", "123456")).thenReturn("plain-state");
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.empty());
        when(encryption.encrypt("plain-state")).thenReturn("encrypted");
        when(sessionRepository.saveAndFlush(any(BourseDirectSession.class))).thenReturn(stored);
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(stored));

        BourseDirectSyncService.SessionStatusResponse result =
            delayedService.completeAuth("process", "123456", 7L);

        assertThat(result.isActive()).isTrue();
        assertThat(result.syncStatus()).isEqualTo(BourseDirectSyncStatus.QUEUED);
        assertThat(queuedTask.get()).isNotNull();
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void initiateAuth_withoutMfaStoresSessionAndQueuesTheInitialSync() {
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        BourseDirectSyncService delayedService = serviceWith(queuedTask::set);
        FamilyMember member = member();
        BourseDirectSession stored = BourseDirectSession.builder()
            .id(9L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(BourseDirectSyncStatus.QUEUED)
            .build();
        when(port.initiateAuth("login", "password")).thenReturn(
            new BourseDirectPort.InitiateResult(null, false, null, "plain-state")
        );
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.empty());
        when(encryption.encrypt("plain-state")).thenReturn("encrypted");
        when(sessionRepository.saveAndFlush(any(BourseDirectSession.class))).thenReturn(stored);
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(stored));

        BourseDirectSyncService.AuthInitResponse response =
            delayedService.initiateAuth("login", "password", 7L);

        assertThat(response.mfaRequired()).isFalse();
        assertThat(response.processId()).isNull();
        assertThat(queuedTask.get()).isNotNull();
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void queueSync_doesNotScheduleDuplicateWhileJobIsRunning() {
        BourseDirectSession session = activeSession(member());
        session.markQueued();
        session.markRunning(Instant.now());
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));

        BourseDirectSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(BourseDirectSyncStatus.RUNNING);
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void foreignQuote_keepsNativeCurrentPriceButStoresEurValuation() {
        FamilyMember member = member();
        BourseDirectSession session = activeSession(member);
        arrangeQueuedSession(session);
        BourseDirectPort.Position position = new BourseDirectPort.Position(
            null, "US", "US share", new BigDecimal("2"), new BigDecimal("70"),
            new BigDecimal("100"), "USD", new BigDecimal("180"), new BigDecimal("40")
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(new BourseDirectPort.AccountData(
            "cto", "CTO", AccountType.COMPTE_TITRES,
            new BigDecimal("200"), new BigDecimal("20"), List.of(position), true
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(accountRepository.findByExternalAccountIdAndMemberId("bd_cto", 7L)).thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("bd_cto", 7L)).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(12L);
            return account;
        });

        service.queueSync(7L);

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        AccountHolding saved = holdingsCaptor.getValue().getFirst();
        assertThat(saved.getCurrentPrice()).isEqualByComparingTo("100");
        assertThat(saved.getQuoteCurrency()).isEqualTo("USD");
        assertThat(saved.getProviderValueEur()).isEqualByComparingTo("180");
    }

    @Test
    void reconciliationMismatch_rejectsTheWholeSnapshot() {
        BourseDirectSession session = activeSession(member());
        arrangeQueuedSession(session);
        BourseDirectPort.Position position = position(
            null, "ACME", BigDecimal.ONE, new BigDecimal("1000"),
            new BigDecimal("800"), new BigDecimal("1000"), "EUR"
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(account(
            "pea", AccountType.PEA, new BigDecimal("1300"), new BigDecimal("250"), List.of(position)
        )));

        BourseDirectSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(BourseDirectSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(BourseDirectErrorCode.PORTFOLIO_INCOMPLETE);
        verify(accountRepository, never()).save(any());
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void duplicatePositions_areMergedWithWeightedPrices() {
        FamilyMember member = member();
        BourseDirectSession session = activeSession(member);
        arrangeQueuedSession(session);
        BourseDirectPort.Position first = position(
            null, "ACME", new BigDecimal("2"), new BigDecimal("200"),
            new BigDecimal("80"), new BigDecimal("100"), "EUR"
        );
        BourseDirectPort.Position second = position(
            null, "ACME", BigDecimal.ONE, new BigDecimal("130"),
            new BigDecimal("110"), new BigDecimal("130"), "EUR"
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(account(
            "pea", AccountType.PEA, new BigDecimal("330"), BigDecimal.ZERO, List.of(first, second)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(12L);

        service.queueSync(7L);

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue()).singleElement().satisfies(holding -> {
            assertThat(holding.getQuantity()).isEqualByComparingTo("3");
            assertThat(holding.getAverageBuyIn()).isEqualByComparingTo("90");
            assertThat(holding.getCurrentPrice()).isEqualByComparingTo("110");
            assertThat(holding.getProviderValueEur()).isEqualByComparingTo("330");
        });
    }

    @Test
    void duplicatePositions_doNotInventMissingPrices() {
        FamilyMember member = member();
        BourseDirectSession session = activeSession(member);
        arrangeQueuedSession(session);
        BourseDirectPort.Position incompletePrice = position(
            null, "ACME", BigDecimal.ONE, new BigDecimal("90"),
            null, null, "EUR"
        );
        BourseDirectPort.Position completePrice = position(
            null, "ACME", BigDecimal.ONE, new BigDecimal("100"),
            new BigDecimal("80"), new BigDecimal("100"), "EUR"
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(account(
            "pea", AccountType.PEA, new BigDecimal("190"), BigDecimal.ZERO,
            List.of(incompletePrice, completePrice)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(12L);

        service.queueSync(7L);

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        AccountHolding holding = holdingsCaptor.getValue().getFirst();
        assertThat(holding.getQuantity()).isEqualByComparingTo("2");
        assertThat(holding.getAverageBuyIn()).isNull();
        assertThat(holding.getCurrentPrice()).isNull();
        assertThat(holding.getProviderValueEur()).isEqualByComparingTo("190");
    }

    @Test
    void unsupportedAccountType_rejectsTheWholeSnapshot() {
        BourseDirectSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(account(
            "other", AccountType.OTHER, BigDecimal.ZERO, BigDecimal.ZERO, List.of()
        )));

        BourseDirectSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.lastSyncError()).isEqualTo(BourseDirectErrorCode.INVALID_DATA);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void duplicateExternalAccountId_rejectsTheWholeSnapshot() {
        BourseDirectSession session = activeSession(member());
        arrangeQueuedSession(session);
        BourseDirectPort.AccountData account = account(
            "same", AccountType.PEA, BigDecimal.TEN, BigDecimal.TEN, List.of()
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(account, account));

        BourseDirectSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.lastSyncError()).isEqualTo(BourseDirectErrorCode.INVALID_DATA);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void zeroQuantityPosition_isFilteredBeforePersistence() {
        FamilyMember member = member();
        BourseDirectSession session = activeSession(member);
        arrangeQueuedSession(session);
        BourseDirectPort.Position closed = position(
            null, "CLOSED", BigDecimal.ZERO, new BigDecimal("999"),
            new BigDecimal("80"), new BigDecimal("100"), "EUR"
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(account(
            "pea", AccountType.PEA, new BigDecimal("50"), new BigDecimal("50"), List.of(closed)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(12L);

        service.queueSync(7L);

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue()).isEmpty();
    }

    @Test
    void invalidIsin_rejectsTheWholeSnapshot() {
        BourseDirectSession session = activeSession(member());
        arrangeQueuedSession(session);
        BourseDirectPort.Position invalid = position(
            "not-an-isin", "ACME", BigDecimal.ONE, new BigDecimal("100"),
            new BigDecimal("80"), new BigDecimal("100"), "EUR"
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(account(
            "pea", AccountType.PEA, new BigDecimal("100"), BigDecimal.ZERO, List.of(invalid)
        )));

        BourseDirectSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.lastSyncError()).isEqualTo(BourseDirectErrorCode.INVALID_DATA);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void oversizedSymbol_fallsBackToAValidIsin() {
        FamilyMember member = member();
        BourseDirectSession session = activeSession(member);
        arrangeQueuedSession(session);
        BourseDirectPort.Position position = position(
            "FR0000000001", "X".repeat(31), BigDecimal.ONE, new BigDecimal("100"),
            new BigDecimal("80"), new BigDecimal("100"), "EUR"
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(account(
            "pea", AccountType.PEA, new BigDecimal("100"), BigDecimal.ZERO, List.of(position)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(12L);

        service.queueSync(7L);

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue().getFirst().getTicker()).isEqualTo("FR0000000001");
    }

    @Test
    void missingSymbolAndIsin_rejectsTheWholeSnapshot() {
        BourseDirectSession session = activeSession(member());
        arrangeQueuedSession(session);
        BourseDirectPort.Position unidentified = position(
            null, null, BigDecimal.ONE, new BigDecimal("100"),
            new BigDecimal("80"), new BigDecimal("100"), "EUR"
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(account(
            "pea", AccountType.PEA, new BigDecimal("100"), BigDecimal.ZERO, List.of(unidentified)
        )));

        BourseDirectSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.lastSyncError()).isEqualTo(BourseDirectErrorCode.INVALID_DATA);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void replacedSession_preventsAnOldJobFromCommittingItsPortfolio() {
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        BourseDirectSyncService delayedService = serviceWith(queuedTask::set);
        BourseDirectSession session = activeSession(member());
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");

        delayedService.queueSync(7L);

        when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L))
            .thenReturn(Optional.of(session), Optional.empty());
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(completeAccount()));
        queuedTask.get().run();

        verify(accountRepository, never()).save(any());
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void applicationStart_marksInterruptedJobsAsRetryableFailures() {
        when(sessionRepository.markInterruptedSyncsFailed(
            any(),
            eq(BourseDirectSyncStatus.FAILED),
            any(),
            eq(BourseDirectErrorCode.INTERNAL_ERROR)
        )).thenReturn(2);

        service.recoverInterruptedSyncs();

        verify(sessionRepository).markInterruptedSyncsFailed(
            eq(List.of(BourseDirectSyncStatus.QUEUED, BourseDirectSyncStatus.RUNNING)),
            eq(BourseDirectSyncStatus.FAILED),
            any(),
            eq(BourseDirectErrorCode.INTERNAL_ERROR)
        );
    }

    private void arrangeQueuedSession(BourseDirectSession session) {
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByIdAndMemberIdForUpdate(session.getId(), 7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");
    }

    private FamilyMember member() {
        return FamilyMember.builder().id(7L).displayName("Owner").build();
    }

    private BourseDirectSession activeSession(FamilyMember member) {
        return BourseDirectSession.builder()
            .id(3L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(BourseDirectSyncStatus.IDLE)
            .build();
    }

    private BourseDirectPort.AccountData completeAccount() {
        BourseDirectPort.Position position = new BourseDirectPort.Position(
            null, "ACME", "Acme SA", new BigDecimal("10"),
            new BigDecimal("80"), new BigDecimal("100"), "EUR",
            new BigDecimal("1000"), new BigDecimal("200")
        );
        return new BourseDirectPort.AccountData(
            "pea-123", "PEA Bourse Direct", AccountType.PEA,
            new BigDecimal("1250"), new BigDecimal("250"), List.of(position), true
        );
    }

    private BourseDirectPort.Position position(
        String isin,
        String symbol,
        BigDecimal quantity,
        BigDecimal currentValueEur,
        BigDecimal buyingPriceEur,
        BigDecimal currentPrice,
        String quoteCurrency
    ) {
        return new BourseDirectPort.Position(
            isin,
            symbol,
            symbol == null ? "Unknown" : symbol,
            quantity,
            buyingPriceEur,
            currentPrice,
            quoteCurrency,
            currentValueEur,
            null
        );
    }

    private BourseDirectPort.AccountData account(
        String externalId,
        AccountType type,
        BigDecimal balance,
        BigDecimal cash,
        List<BourseDirectPort.Position> positions
    ) {
        return new BourseDirectPort.AccountData(
            externalId,
            type == AccountType.PEA ? "PEA" : "CTO",
            type,
            balance,
            cash,
            positions,
            true
        );
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

    private BourseDirectSyncService serviceWith(Executor executor) {
        return new BourseDirectSyncService(
            port,
            sessionRepository,
            accountRepository,
            holdingRepository,
            memberRepository,
            accountService,
            isinConverter,
            identityService,
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
