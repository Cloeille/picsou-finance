package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.FortuneoSession;
import com.picsou.model.FortuneoSyncStatus;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.port.FortuneoErrorCode;
import com.picsou.port.FortuneoPort;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.FortuneoSessionRepository;
import com.picsou.repository.TransactionRepository;
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
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class FortuneoSyncServiceTest {
    @Mock FortuneoPort port;
    @Mock FortuneoSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock FortuneoTransactionWriter transactionWriter;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountService accountService;
    @Mock PriceService priceService;
    @Mock BalanceHistoryReconstructor historyReconstructor;
    @Mock OpenFigiIsinConverter isinConverter;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Captor ArgumentCaptor<Account> accountCaptor;
    @Captor ArgumentCaptor<FortuneoSession> sessionCaptor;
    @Captor ArgumentCaptor<List<AccountHolding>> holdingsCaptor;
    @Captor ArgumentCaptor<List<Transaction>> transactionsCaptor;
    @Captor ArgumentCaptor<List<Transaction>> obsoleteCaptor;

    FortuneoSyncService service;

    @BeforeEach
    void setUp() {
        executeTransactionsImmediately();
        service = serviceWith(Runnable::run);
    }

    @Test
    void queueSync_commitsCompletePortfolioThenWritesSnapshotFromCurrentPositions() {
        FamilyMember member = member();
        FortuneoSession session = activeSession(member);
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
        when(accountRepository.findByExternalAccountIdAndMemberId("ft_pea-123", 7L))
            .thenReturn(Optional.of(existingAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            return account;
        });

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.SUCCESS);
        verify(accountRepository).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();
        assertThat(savedAccount.getProvider()).isEqualTo("Fortuneo");
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
            eq(LocalDate.now())
        );
    }

    @Test
    void backfillsTheHistoricalPricesTheReconstructionNeedsBeforeCommitting() {
        // The reconstruction values a past day from price_snapshot, and the only thing that ever
        // filled that table was an ApplicationRunner over the tickers held at startup -- which by
        // definition cannot know about an account connected afterwards. Without this the PEA's
        // chart stayed a single point until the app was restarted and the account synced again.
        FamilyMember member = member();
        FortuneoSession session = activeSession(member);
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(completeAccount()));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(30L);

        service.queueSync(7L);

        InOrder order = inOrder(priceService, historyReconstructor);
        order.verify(priceService).backfillHistoricalPrices(
            eq(Set.of("ACME")), eq(LocalDate.now().minusMonths(12)));
        order.verify(historyReconstructor).reconstruct(
            any(Account.class), eq(new BigDecimal("1250")), eq(LocalDate.now()));
    }

    @Test
    void anUnreachablePriceApiCostsHistoryNotTheSyncItself() {
        // Prices are what the chart is drawn from, not what the portfolio import is. Refusing a
        // good sync over them would trade a complete chart for no data whatsoever.
        FamilyMember member = member();
        FortuneoSession session = activeSession(member);
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(completeAccount()));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(31L);
        when(priceService.backfillHistoricalPrices(any(), any()))
            .thenThrow(new IllegalStateException("market data unreachable"));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.SUCCESS);
        verify(accountRepository).save(any(Account.class));
    }

    @Test
    void cashAccount_syncsBalanceWithoutPositionsOrReconciliation() {
        FamilyMember member = member();
        FortuneoSession session = activeSession(member);
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of()
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(20L);

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.SUCCESS);
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getType()).isEqualTo(AccountType.CHECKING);
        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue()).isEmpty();
    }

    @Test
    void mixedPeaAndCheckingPortfolioCommitsBothAccounts() {
        FamilyMember member = member();
        FortuneoSession session = activeSession(member);
        arrangeQueuedSession(session);
        FortuneoPort.Position peaPosition = position(
            null, "PEA_ONE", BigDecimal.ONE, new BigDecimal("100"),
            new BigDecimal("80"), new BigDecimal("100"), "EUR"
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(
            account("pea-1", AccountType.PEA, new BigDecimal("150"), new BigDecimal("50"),
                List.of(peaPosition)),
            cashAccount("checking-1", AccountType.CHECKING, new BigDecimal("700"), List.of())
        ));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountsPersistence();

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.SUCCESS);
        verify(accountRepository, times(2)).save(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues())
            .extracting(Account::getType)
            .containsExactly(AccountType.PEA, AccountType.CHECKING);
    }

    @Test
    void mixedPeaCtoAndCheckingPortfolioPreservesEveryPosition() {
        FamilyMember member = member();
        FortuneoSession session = activeSession(member);
        arrangeQueuedSession(session);
        FortuneoPort.Position peaOne = position(
            null, "PEA_ONE", BigDecimal.ONE, new BigDecimal("100"),
            new BigDecimal("80"), new BigDecimal("100"), "EUR"
        );
        FortuneoPort.Position peaTwo = position(
            null, "PEA_TWO", new BigDecimal("2"), new BigDecimal("200"),
            new BigDecimal("90"), new BigDecimal("100"), "EUR"
        );
        FortuneoPort.Position ctoOne = position(
            null, "CTO_ONE", BigDecimal.ONE, new BigDecimal("250"),
            new BigDecimal("200"), new BigDecimal("250"), "EUR"
        );
        FortuneoPort.Position ctoTwo = position(
            null, "CTO_TWO", new BigDecimal("3"), new BigDecimal("150"),
            new BigDecimal("40"), new BigDecimal("50"), "EUR"
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(
            account("pea-1", AccountType.PEA, new BigDecimal("350"), new BigDecimal("50"),
                List.of(peaOne, peaTwo)),
            account("cto-1", AccountType.COMPTE_TITRES, new BigDecimal("500"),
                new BigDecimal("100"), List.of(ctoOne, ctoTwo)),
            cashAccount("checking-1", AccountType.CHECKING, new BigDecimal("700"), List.of())
        ));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountsPersistence();

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.SUCCESS);
        verify(accountRepository, times(3)).save(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues())
            .extracting(Account::getType)
            .containsExactly(AccountType.PEA, AccountType.COMPTE_TITRES, AccountType.CHECKING);
        verify(holdingRepository, times(3)).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getAllValues().stream().flatMap(List::stream))
            .extracting(AccountHolding::getTicker)
            .containsExactly("PEA_ONE", "PEA_TWO", "CTO_ONE", "CTO_TWO");
    }

    @Test
    void partialMixedPortfolioIsRejectedBeforeAnyPreviousDataIsReplaced() {
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        FortuneoPort.AccountData validChecking = cashAccount(
            "checking-1", AccountType.CHECKING, new BigDecimal("700"), List.of()
        );
        FortuneoPort.AccountData incompletePea = new FortuneoPort.AccountData(
            "pea-1", "PEA", AccountType.PEA,
            new BigDecimal("350"), new BigDecimal("50"), List.of(), List.of(), false
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(validChecking, incompletePea));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.PORTFOLIO_INCOMPLETE);
        verify(accountRepository, never()).save(any());
        verify(holdingRepository, never()).deleteByAccountId(any());
        verify(transactionWriter, never()).replaceRecentTransactions(any(), any(), any());
    }

    @Test
    void cashAccountWithPositions_rejectsTheWholeSnapshot() {
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        FortuneoPort.Position stray = position(
            "FR0000000001", "ACME", BigDecimal.ONE, new BigDecimal("100"),
            new BigDecimal("80"), new BigDecimal("100"), "EUR"
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(new FortuneoPort.AccountData(
            "cc-1", "Compte Courant", AccountType.CHECKING,
            new BigDecimal("500"), new BigDecimal("500"), List.of(stray), List.of(), true
        )));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.INVALID_DATA);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void cashAccountBalanceMismatch_rejectsTheWholeSnapshot() {
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(new FortuneoPort.AccountData(
            "cc-1", "Compte Courant", AccountType.CHECKING,
            new BigDecimal("500"), new BigDecimal("400"), List.of(), List.of(), true
        )));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.INVALID_DATA);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void transactionsWithoutIds_replaceTheTrailingNinetyDayWindow() {
        FamilyMember member = member();
        FortuneoSession session = activeSession(member);
        arrangeQueuedSession(session);
        FortuneoPort.Transaction incoming = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(1), "Virement recu", new BigDecimal("100"), "Virement", null, null, null, null, null, null, null
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(incoming)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        // Only the window is deleted, and nothing is re-saved: older rows are never
        // touched, so they keep their ids and cannot be merged onto deleted rows.
        verify(transactionWriter).replaceRecentTransactions(
            eq(20L), any(LocalDate.class), transactionsCaptor.capture());
        List<Transaction> inserted = transactionsCaptor.getValue();
        assertThat(inserted).singleElement().satisfies(tx -> {
            assertThat(tx.getDescription()).isEqualTo("Virement recu");
            assertThat(tx.getAmount()).isEqualByComparingTo("100");
        });
    }

    @Test
    void transactionsWithoutIds_olderThanTheWindowAreNotReinserted() {
        // Regression: the provider returns more history than the 90-day window. Deleting
        // only inside the window while inserting everything appended a fresh copy of each
        // older row on every sync.
        FamilyMember member = member();
        FortuneoSession session = activeSession(member);
        arrangeQueuedSession(session);
        FortuneoPort.Transaction recent = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(1), "Dans la fenetre", new BigDecimal("100"), "Virement", null, null, null, null, null, null, null
        );
        FortuneoPort.Transaction old = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(200), "Hors fenetre", new BigDecimal("42"), "Virement", null, null, null, null, null, null, null
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(recent, old)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionWriter).replaceRecentTransactions(
            eq(20L), any(LocalDate.class), transactionsCaptor.capture());
        assertThat(transactionsCaptor.getValue())
            .singleElement()
            .satisfies(tx -> assertThat(tx.getDescription()).isEqualTo("Dans la fenetre"));
    }

    @Test
    void identifiedTransactions_importTheWholeHistoryWithoutAnyWindow() {
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        FortuneoPort.Transaction recent = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(1), "Recent", new BigDecimal("100"), "Virement", "tx-recent", null, null, null, null, null, null
        );
        FortuneoPort.Transaction ancient = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(900), "Ancien", new BigDecimal("42"), "Virement", "tx-ancient", null, null, null, null, null, null
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(recent, ancient)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of());
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionWriter, never()).replaceRecentTransactions(any(), any(), any());
        verify(transactionWriter).reconcileHistory(obsoleteCaptor.capture(), transactionsCaptor.capture());
        assertThat(obsoleteCaptor.getValue()).isEmpty();
        assertThat(transactionsCaptor.getValue())
            .extracting(Transaction::getExternalId)
            .containsExactly("tx-recent", "tx-ancient");
    }

    @Test
    void structuredOperationType_isPersistedOnTheTransaction() {
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        FortuneoPort.Transaction coupon = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(1), "Coupon", new BigDecimal("12"), "Epargne", "tx-1", "COUPON", null, null, null, null, null
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(coupon)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of());
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionWriter).reconcileHistory(any(), transactionsCaptor.capture());
        assertThat(transactionsCaptor.getValue()).singleElement().satisfies(tx ->
            assertThat(tx.getType()).isEqualTo("COUPON"));
    }

    @Test
    void blankOperationType_staysNullRatherThanBecomingAPlaceholder() {
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        FortuneoPort.Transaction untyped = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(1), "Sans type", new BigDecimal("12"), null, "tx-1", "  ", null, null, null, null, null
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(untyped)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of());
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionWriter).reconcileHistory(any(), transactionsCaptor.capture());
        assertThat(transactionsCaptor.getValue()).singleElement().satisfies(tx ->
            assertThat(tx.getType()).isNull());
    }

    @Test
    void storedRowNewerThanAnythingReported_isDroppedAsAStaleLeftover() {
        // The whole visible feed is authoritative up to now. A stored row more recent than
        // anything it reports is one the provider stopped showing -- a reversal, or a pending
        // entry that settled under a new id -- and keeping it would strand a duplicate forever.
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        FortuneoPort.Transaction reported = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(30), "Reporte", new BigDecimal("100"), null, "tx-1", null, null, null, null, null, null
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(reported)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        Transaction staleLeftover = Transaction.builder()
            .id(5L).date(LocalDate.now().minusDays(1))
            .description("Import sans id, plus recent que le flux").amount(BigDecimal.ONE).build();
        Transaction olderThanTheFeed = Transaction.builder()
            .id(6L).date(LocalDate.now().minusDays(500))
            .description("Anterieur au flux").amount(BigDecimal.ONE).build();
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L))
            .thenReturn(List.of(staleLeftover, olderThanTheFeed));
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionWriter).reconcileHistory(obsoleteCaptor.capture(), any());
        assertThat(obsoleteCaptor.getValue()).extracting(Transaction::getId).containsExactly(5L);
    }

    @Test
    void identifiedTransactions_areUpdatedInPlaceRatherThanDuplicated() {
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        FortuneoPort.Transaction updated = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(2), "Libelle corrige", new BigDecimal("120"), "Virement", "tx-1", null, null, null, null, null, null
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(updated)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        Transaction stored = Transaction.builder()
            .id(99L)
            .externalId("tx-1")
            .date(LocalDate.now().minusDays(2))
            .description("Ancien libelle")
            .amount(new BigDecimal("100"))
            .build();
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of(stored));
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionWriter).reconcileHistory(obsoleteCaptor.capture(), transactionsCaptor.capture());
        assertThat(obsoleteCaptor.getValue()).isEmpty();
        assertThat(transactionsCaptor.getValue()).singleElement().satisfies(tx -> {
            assertThat(tx.getId()).isEqualTo(99L);
            assertThat(tx.getDescription()).isEqualTo("Libelle corrige");
            assertThat(tx.getAmount()).isEqualByComparingTo("120");
        });
    }

    @Test
    void identifiedTransactions_onlyDeleteInsideTheRangeTheResponseCovers() {
        // A partial response -- Fortuneo answering with just the latest entries -- must rewrite
        // only the days it actually covers. Older history it never mentioned stays untouched.
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        FortuneoPort.Transaction reported = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(1), "Recent", new BigDecimal("100"), "Virement", "tx-new", null, null, null, null, null, null
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(reported)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        Transaction olderThanTheFeed = Transaction.builder()
            .id(1L).externalId("tx-old").date(LocalDate.now().minusDays(400))
            .description("Vieille ligne").amount(BigDecimal.ONE).build();
        Transaction insideRangeNoLongerReported = Transaction.builder()
            .id(2L).externalId("tx-gone").date(LocalDate.now().minusDays(1))
            .description("Ligne retiree").amount(BigDecimal.ONE).build();
        Transaction insideRangeFromTheWindowEra = Transaction.builder()
            .id(3L).date(LocalDate.now().minusDays(1))
            .description("Import sans id").amount(BigDecimal.ONE).build();
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(
            List.of(olderThanTheFeed, insideRangeNoLongerReported, insideRangeFromTheWindowEra));
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionWriter).reconcileHistory(obsoleteCaptor.capture(), transactionsCaptor.capture());
        assertThat(obsoleteCaptor.getValue()).extracting(Transaction::getId).containsExactly(2L, 3L);
        assertThat(transactionsCaptor.getValue())
            .extracting(Transaction::getExternalId).containsExactly("tx-new");
    }

    @Test
    void identifiedTransactions_repeatedInOneResponseCollapseToASingleRow() {
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        FortuneoPort.Transaction first = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(1), "Premiere copie", new BigDecimal("100"), null, "tx-1", null, null, null, null, null, null
        );
        FortuneoPort.Transaction duplicate = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(1), "Seconde copie", new BigDecimal("100"), null, "tx-1", null, null, null, null, null, null
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(first, duplicate)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of());
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionWriter).reconcileHistory(any(), transactionsCaptor.capture());
        assertThat(transactionsCaptor.getValue()).singleElement().satisfies(tx ->
            assertThat(tx.getDescription()).isEqualTo("Seconde copie"));
    }

    @Test
    void emptyTransactionResponse_leavesStoredHistoryUntouched() {
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of()
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionWriter, never()).reconcileHistory(any(), any());
        verify(transactionWriter, never()).replaceRecentTransactions(any(), any(), any());
    }

    @Test
    void mixedTransactionIds_fallBackToTheWindowImport() {
        // Half a response carrying ids is not enough to reconcile on: without an id there is
        // no way to tell a re-sent row from a new one, so the safe rolling window is kept.
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        FortuneoPort.Transaction identified = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(1), "Avec id", new BigDecimal("100"), null, "tx-1", null, null, null, null, null, null
        );
        FortuneoPort.Transaction anonymous = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(2), "Sans id", new BigDecimal("50"), null, null, null, null, null, null, null, null
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(identified, anonymous)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionWriter, never()).reconcileHistory(any(), any());
        verify(transactionWriter).replaceRecentTransactions(eq(20L), any(LocalDate.class), any());
    }

    @Test
    void blankTransactionId_isTreatedAsAbsent() {
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        FortuneoPort.Transaction blank = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(1), "Id vide", new BigDecimal("100"), null, "   ", null, null, null, null, null, null
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(blank)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionWriter, never()).reconcileHistory(any(), any());
        verify(transactionWriter).replaceRecentTransactions(eq(20L), any(LocalDate.class), any());
    }

    @Test
    void securitiesLedgerFields_arePersistedOnTheTransaction() {
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        FortuneoPort.Transaction dividend = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(30), "ACME", new BigDecimal("42.10"), null,
            "ft_h_abc", "Encaissement coupons interet/dividende", "DIVIDEND",
            new BigDecimal("100"), new BigDecimal("0.4210"), new BigDecimal("0.00"), null
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(dividend)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of());
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionWriter).reconcileHistory(any(), transactionsCaptor.capture());
        assertThat(transactionsCaptor.getValue()).singleElement().satisfies(tx -> {
            assertThat(tx.getTxType()).isEqualTo(TransactionType.DIVIDEND);
            assertThat(tx.getQuantity()).isEqualByComparingTo("100");
            assertThat(tx.getPricePerUnit()).isEqualByComparingTo("0.4210");
            assertThat(tx.getFees()).isEqualByComparingTo("0.00");
        });
    }

    @Test
    void anUntypedOperation_isImportedWithoutATypeRatherThanRejected() {
        // The sidecar leaves an operation untyped when the provider's label is not one it
        // recognises. The row still belongs in the ledger; it simply joins no typed aggregate.
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        FortuneoPort.Transaction untyped = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(30), "ACME", new BigDecimal("3.00"), null,
            "ft_h_def", "Indemnisation", null, null, null, null, null
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(untyped)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of());
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionWriter).reconcileHistory(any(), transactionsCaptor.capture());
        assertThat(transactionsCaptor.getValue()).singleElement().satisfies(tx -> {
            assertThat(tx.getTxType()).isNull();
            assertThat(tx.getType()).isEqualTo("Indemnisation");
        });
    }

    @Test
    void anUnknownTransactionType_failsTheSnapshotInsteadOfBeingDropped() {
        // A type Picsou does not define means the two sides disagree on the contract.
        // Dropping it silently would hide that while quietly changing what the ledger reports.
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        FortuneoPort.Transaction bogus = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(1), "ACME", BigDecimal.ONE, null,
            "ft_h_ghi", "Something new", "TRANSFER", null, null, null, null
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(bogus)
        )));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.INVALID_DATA);
        verify(transactionWriter, never()).reconcileHistory(any(), any());
    }

    @Test
    void aLedgerRowWithAnIsin_landsOnTheSameTickerAsTheHoldingItBuilt() {
        // Trades and positions go through the same converter on purpose: RealizedPnlService
        // pairs a BUY with a SELL by ticker, and can only do so if both sides agree on it.
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        when(isinConverter.resolve("XX0000000001"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("SYN.PA", "SYNTHETIC FUND"));
        FortuneoPort.Transaction trade = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(30), "SYNTHETIC FUND", new BigDecimal("-402.50"), null,
            "ft_h_abc", "Achat Comptant", "BUY",
            new BigDecimal("10"), new BigDecimal("40"), new BigDecimal("2.50"), "XX0000000001"
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(trade)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of());
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionWriter).reconcileHistory(any(), transactionsCaptor.capture());
        assertThat(transactionsCaptor.getValue()).singleElement().satisfies(tx -> {
            assertThat(tx.getTicker()).isEqualTo("SYN.PA");
            assertThat(tx.getName()).isEqualTo("SYNTHETIC FUND");
            assertThat(tx.getTxType()).isEqualTo(TransactionType.BUY);
        });
    }

    @Test
    void anUnresolvableIsin_fallsBackToTheIsinRatherThanLosingTheInstrument() {
        // An unresolvable instrument is still a stable key. Dropping it would silently
        // remove the row from every per-instrument computation.
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        when(isinConverter.resolve("FR0000000001")).thenReturn(null);
        FortuneoPort.Transaction trade = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(30), "OBSCURE", BigDecimal.ONE, null,
            "ft_h_def", "Achat Comptant", "BUY", BigDecimal.ONE, BigDecimal.ONE, null,
            "FR0000000001"
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(trade)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of());
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionWriter).reconcileHistory(any(), transactionsCaptor.capture());
        assertThat(transactionsCaptor.getValue()).singleElement().satisfies(tx ->
            assertThat(tx.getTicker()).isEqualTo("FR0000000001"));
    }

    @Test
    void aRowTheProviderNamesNoInstrumentFor_carriesNoTicker() {
        // Cash-ledger rows, and any securities row whose label the provider's own
        // referential does not list. No instrument is better than a guessed one.
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        FortuneoPort.Transaction cashRow = new FortuneoPort.Transaction(
            LocalDate.now().minusDays(30), "Virement recu", new BigDecimal("100"), null,
            "tx-1", "CAV", null, null, null, null, null
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(cashAccount(
            "cc-1", AccountType.CHECKING, new BigDecimal("500"), List.of(cashRow)
        )));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of());
        arrangeNewAccountPersistence(20L);

        service.queueSync(7L);

        verify(transactionWriter).reconcileHistory(any(), transactionsCaptor.capture());
        assertThat(transactionsCaptor.getValue()).singleElement().satisfies(tx -> {
            assertThat(tx.getTicker()).isNull();
            assertThat(tx.getName()).isNull();
        });
        verify(isinConverter, never()).resolve(any());
    }

    @Test
    void incompleteSnapshot_failsWithoutDeletingExistingHoldings() {
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        FortuneoPort.AccountData incomplete = new FortuneoPort.AccountData(
            "pea-123", "PEA", AccountType.PEA,
            new BigDecimal("1250"), new BigDecimal("250"), List.of(), List.of(), false
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(incomplete));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.PORTFOLIO_INCOMPLETE);
        verify(holdingRepository, never()).deleteByAccountId(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void nativeQuoteWithoutCurrency_rejectsTheWholeSnapshot() {
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        FortuneoPort.Position ambiguous = new FortuneoPort.Position(
            null, "ACME", "Acme", BigDecimal.ONE, new BigDecimal("80"),
            new BigDecimal("100"), null, new BigDecimal("100"), new BigDecimal("20")
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(new FortuneoPort.AccountData(
            "pea-123", "PEA", AccountType.PEA,
            new BigDecimal("100"), BigDecimal.ZERO, List.of(ambiguous), List.of(), true
        )));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.INVALID_DATA);
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void expiredSession_isInvalidatedInFollowUpTransactionAndRemainsObservable() {
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenThrow(new SyncException(
            "Session expired", null, FortuneoErrorCode.SESSION_EXPIRED.name()
        ));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.isActive()).isFalse();
        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.SESSION_EXPIRED);
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void completeAuth_returnsQueuedBeforePortfolioFetchRuns() {
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        FortuneoSyncService delayedService = serviceWith(queuedTask::set);
        FamilyMember member = member();
        FortuneoSession stored = FortuneoSession.builder()
            .id(9L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(FortuneoSyncStatus.QUEUED)
            .build();
        when(port.completeAuth("process", "123456")).thenReturn("plain-state");
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.empty());
        when(encryption.encrypt("plain-state")).thenReturn("encrypted");
        when(sessionRepository.saveAndFlush(any(FortuneoSession.class))).thenReturn(stored);
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(stored));

        FortuneoSyncService.SessionStatusResponse result =
            delayedService.completeAuth("process", "123456", 7L);

        assertThat(result.isActive()).isTrue();
        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.QUEUED);
        assertThat(queuedTask.get()).isNotNull();
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void queueSync_executorRejectionMarksTheQueuedSessionFailed() {
        FortuneoSession session = activeSession(member());
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByIdAndMemberIdForUpdate(session.getId(), 7L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");
        RejectedExecutionException rejection = new RejectedExecutionException("queue full");
        FortuneoSyncService rejectedService = serviceWith(task -> { throw rejection; });

        assertThatThrownBy(() -> rejectedService.queueSync(7L))
            .isInstanceOfSatisfying(SyncException.class, exception ->
                assertThat(exception.getCode()).isEqualTo(FortuneoErrorCode.INTERNAL_ERROR.name()))
            .hasCause(rejection);

        assertThat(session.getSyncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(FortuneoErrorCode.INTERNAL_ERROR);
        assertThat(session.isSyncInFlight()).isFalse();
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void completeAuth_submissionFailureMarksTheNewSessionFailed() {
        IllegalStateException submissionFailure = new IllegalStateException("executor stopped");
        FortuneoSyncService rejectedService = serviceWith(task -> { throw submissionFailure; });
        FamilyMember member = member();
        FortuneoSession stored = FortuneoSession.builder()
            .id(9L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(FortuneoSyncStatus.QUEUED)
            .build();
        when(port.completeAuth("process", "123456")).thenReturn("plain-state");
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.empty());
        when(encryption.encrypt("plain-state")).thenReturn("encrypted");
        when(sessionRepository.saveAndFlush(any(FortuneoSession.class))).thenReturn(stored);
        when(sessionRepository.findByIdAndMemberIdForUpdate(9L, 7L)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> rejectedService.completeAuth("process", "123456", 7L))
            .isInstanceOfSatisfying(SyncException.class, exception ->
                assertThat(exception.getCode()).isEqualTo(FortuneoErrorCode.INTERNAL_ERROR.name()))
            .hasCause(submissionFailure);

        assertThat(stored.getSyncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(stored.getLastSyncError()).isEqualTo(FortuneoErrorCode.INTERNAL_ERROR);
        assertThat(stored.isSyncInFlight()).isFalse();
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void initiateAuth_withoutMfaStoresSessionAndQueuesTheInitialSync() {
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        FortuneoSyncService delayedService = serviceWith(queuedTask::set);
        FamilyMember member = member();
        FortuneoSession stored = FortuneoSession.builder()
            .id(9L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(FortuneoSyncStatus.QUEUED)
            .build();
        when(port.initiateAuth("login", "password")).thenReturn(
            new FortuneoPort.InitiateResult(null, false, null, "plain-state")
        );
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.empty());
        when(encryption.encrypt("plain-state")).thenReturn("encrypted");
        when(sessionRepository.saveAndFlush(any(FortuneoSession.class))).thenReturn(stored);
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(stored));

        FortuneoSyncService.AuthInitResponse response =
            delayedService.initiateAuth("login", "password", 7L);

        assertThat(response.mfaRequired()).isFalse();
        assertThat(response.processId()).isNull();
        assertThat(queuedTask.get()).isNotNull();
        verify(encryption).encrypt("plain-state");
        verify(sessionRepository).saveAndFlush(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getSessionState()).isEqualTo("encrypted");
        assertThat(sessionCaptor.getValue().getSessionState()).doesNotContain("plain-state");
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void queueSync_doesNotScheduleDuplicateWhileJobIsRunning() {
        FortuneoSession session = activeSession(member());
        session.markQueued();
        session.markRunning(Instant.now());
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.RUNNING);
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void reconciliationMismatch_rejectsTheWholeSnapshot() {
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        FortuneoPort.Position position = position(
            null, "ACME", BigDecimal.ONE, new BigDecimal("1000"),
            new BigDecimal("800"), new BigDecimal("1000"), "EUR"
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(account(
            "pea", AccountType.PEA, new BigDecimal("1300"), new BigDecimal("250"), List.of(position)
        )));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(FortuneoSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.PORTFOLIO_INCOMPLETE);
        verify(accountRepository, never()).save(any());
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void unsupportedAccountType_rejectsTheWholeSnapshot() {
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(account(
            "other", AccountType.OTHER, BigDecimal.ZERO, BigDecimal.ZERO, List.of()
        )));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.INVALID_DATA);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void duplicateExternalAccountId_rejectsTheWholeSnapshot() {
        FortuneoSession session = activeSession(member());
        arrangeQueuedSession(session);
        FortuneoPort.AccountData account = account(
            "same", AccountType.PEA, BigDecimal.TEN, BigDecimal.TEN, List.of()
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(account, account));

        FortuneoSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.lastSyncError()).isEqualTo(FortuneoErrorCode.INVALID_DATA);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void replacedSession_preventsAnOldJobFromCommittingItsPortfolio() {
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        FortuneoSyncService delayedService = serviceWith(queuedTask::set);
        FortuneoSession session = activeSession(member());
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
        FamilyMember secondMember = FamilyMember.builder().id(8L).displayName("Second member").build();
        when(memberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(member(), secondMember));
        when(sessionRepository.markInterruptedSyncsFailed(
            any(),
            any(),
            eq(FortuneoSyncStatus.FAILED),
            any(),
            eq(FortuneoErrorCode.INTERNAL_ERROR)
        )).thenReturn(1);

        service.recoverInterruptedSyncs();

        verify(sessionRepository).markInterruptedSyncsFailed(
            eq(7L),
            eq(List.of(FortuneoSyncStatus.QUEUED, FortuneoSyncStatus.RUNNING)),
            eq(FortuneoSyncStatus.FAILED),
            any(),
            eq(FortuneoErrorCode.INTERNAL_ERROR)
        );
        verify(sessionRepository).markInterruptedSyncsFailed(
            eq(8L),
            eq(List.of(FortuneoSyncStatus.QUEUED, FortuneoSyncStatus.RUNNING)),
            eq(FortuneoSyncStatus.FAILED),
            any(),
            eq(FortuneoErrorCode.INTERNAL_ERROR)
        );
    }

    private void arrangeQueuedSession(FortuneoSession session) {
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByIdAndMemberIdForUpdate(session.getId(), 7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");
    }

    private FamilyMember member() {
        return FamilyMember.builder().id(7L).displayName("Owner").build();
    }

    private FortuneoSession activeSession(FamilyMember member) {
        return FortuneoSession.builder()
            .id(3L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(FortuneoSyncStatus.IDLE)
            .build();
    }

    private FortuneoPort.AccountData completeAccount() {
        FortuneoPort.Position position = new FortuneoPort.Position(
            null, "ACME", "Acme SA", new BigDecimal("10"),
            new BigDecimal("80"), new BigDecimal("100"), "EUR",
            new BigDecimal("1000"), new BigDecimal("200")
        );
        return new FortuneoPort.AccountData(
            "pea-123", "PEA Fortuneo", AccountType.PEA,
            new BigDecimal("1250"), new BigDecimal("250"), List.of(position), List.of(), true
        );
    }

    private FortuneoPort.Position position(
        String isin,
        String symbol,
        BigDecimal quantity,
        BigDecimal currentValueEur,
        BigDecimal buyingPriceEur,
        BigDecimal currentPrice,
        String quoteCurrency
    ) {
        return new FortuneoPort.Position(
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

    private FortuneoPort.AccountData account(
        String externalId,
        AccountType type,
        BigDecimal balance,
        BigDecimal cash,
        List<FortuneoPort.Position> positions
    ) {
        return new FortuneoPort.AccountData(
            externalId,
            type == AccountType.PEA ? "PEA" : "CTO",
            type,
            balance,
            cash,
            positions,
            List.of(),
            true
        );
    }

    private FortuneoPort.AccountData cashAccount(
        String externalId,
        AccountType type,
        BigDecimal balance,
        List<FortuneoPort.Transaction> transactions
    ) {
        return new FortuneoPort.AccountData(
            externalId,
            type == AccountType.CHECKING ? "Compte Courant" : "Livret",
            type,
            balance,
            balance,
            List.of(),
            transactions,
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

    private void arrangeNewAccountsPersistence() {
        AtomicLong nextId = new AtomicLong(20L);
        when(accountRepository.findByExternalAccountIdAndMemberId(anyString(), eq(7L)))
            .thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(anyString(), eq(7L)))
            .thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(nextId.getAndIncrement());
            return account;
        });
    }

    private FortuneoSyncService serviceWith(Executor executor) {
        return new FortuneoSyncService(
            port,
            sessionRepository,
            accountRepository,
            holdingRepository,
            transactionWriter,
            transactionRepository,
            memberRepository,
            accountService,
            priceService,
            historyReconstructor,
            isinConverter,
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
