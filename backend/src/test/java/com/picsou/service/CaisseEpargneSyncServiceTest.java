package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.CardNature;
import com.picsou.model.CaisseEpargneSession;
import com.picsou.model.CaisseEpargneSyncStatus;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.Debt;
import com.picsou.model.FamilyMember;
import com.picsou.model.Transaction;
import com.picsou.port.CaisseEpargneErrorCode;
import com.picsou.port.CaisseEpargnePort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.CaisseEpargneSessionRepository;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.PropertyValuationRepository;
import com.picsou.repository.RealEstateMetadataRepository;
import com.picsou.repository.SavingsInterestConfigRepository;
import com.picsou.repository.ScpiPositionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.budget.CategoryService;
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
import java.util.Map;
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
    @Mock DebtRepository debtRepository;
    @Mock AccountService accountService;
    @Mock CategorizationService categorizationService;
    @Mock CategoryService categoryService;
    @Mock CaisseEpargnePerService perService;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Captor ArgumentCaptor<Account> accountCaptor;
    @Captor ArgumentCaptor<Debt> debtCaptor;
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
    void queueSync_mapsLddsPerAndLoanToTheirPicsouAccountTypes() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.ldds(), f.per(), f.loan());

        CaisseEpargneSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(CaisseEpargneSyncStatus.SUCCESS);
        verify(accountRepository, times(3)).save(accountCaptor.capture());
        List<Account> saved = accountCaptor.getAllValues();
        assertThat(saved).extracting(Account::getType).containsExactly(
            AccountType.LDDS, AccountType.PER, AccountType.LOAN);
        assertThat(saved).extracting(Account::getExternalAccountId)
            .containsExactly("ce_1003", "ce_contract:P0001", "ce_contract:L0001");
    }

    @Test
    void queueSync_persistsTheCompletePerPocketAndSchedulesBackfillOnlyAfterTheCommit() {
        Fixture f = new Fixture();
        LocalDate valuationDate = TODAY.minusDays(3);
        CaisseEpargnePort.PerDetails detail = new CaisseEpargnePort.PerDetails(
            new BigDecimal("4200.00"), new BigDecimal("1000.00"), valuationDate,
            List.of(new CaisseEpargnePort.PerPosition("XX0000000001", "Synthetic fund",
                new BigDecimal("10"), new BigDecimal("320"), new BigDecimal("3200.00"), valuationDate, 1)));
        CaisseEpargnePort.AccountData source = f.per();
        CaisseEpargnePort.AccountData withPer = new CaisseEpargnePort.AccountData(
            source.externalId(), source.kind(), source.name(), source.balance(), source.currency(),
            source.iban(), source.ibanAmbiguous(), source.authorizedOverdraft(), source.ceiling(),
            source.remainingDepositCapacity(), source.fillingRatio(), source.cardNature(),
            source.parentExternalId(), source.nextDueDate(), source.transactions(), true, null, detail);
        CaisseEpargnePerService.Prepared prepared = new CaisseEpargnePerService.Prepared(
            new BigDecimal("4200.00"), new BigDecimal("1000.00"), valuationDate, List.of(),
            java.util.Map.of(), java.util.Set.of("SYN.PA"));
        when(perService.prepare(detail, "EUR")).thenReturn(prepared);
        arrangeCommittableSync(withPer);

        service.queueSync(7L);

        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getCashBalance()).isEqualByComparingTo("1000.00");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(perService, accountService);
        order.verify(perService).prepare(detail, "EUR");
        order.verify(perService).apply(eq(accountCaptor.getValue()), eq(prepared), any());
        order.verify(accountService).upsertSnapshot(
            eq(accountCaptor.getValue()), eq(new BigDecimal("4200.00")),
            eq(new BigDecimal("4200.00")), eq(valuationDate));
        order.verify(perService).backfill(prepared);
    }

    @Test
    void queueSync_pairsTheBankNavSnapshotWithItsOwnNeutralBaseline() {
        Fixture f = new Fixture();
        LocalDate navDate = TODAY.minusDays(3);
        CaisseEpargnePort.PerPosition bankPosition = new CaisseEpargnePort.PerPosition(
            "XX0000000001", "Synthetic fund", new BigDecimal("10"), new BigDecimal("100"),
            new BigDecimal("1000"), navDate, 1);
        CaisseEpargnePort.PerDetails detail = new CaisseEpargnePort.PerDetails(
            new BigDecimal("1000"), BigDecimal.ZERO, navDate, List.of(bankPosition));
        CaisseEpargnePort.AccountData source = f.per();
        CaisseEpargnePort.AccountData withPer = new CaisseEpargnePort.AccountData(
            source.externalId(), source.kind(), source.name(), new BigDecimal("1000"), source.currency(),
            source.iban(), source.ibanAmbiguous(), source.authorizedOverdraft(), source.ceiling(),
            source.remainingDepositCapacity(), source.fillingRatio(), source.cardNature(),
            source.parentExternalId(), source.nextDueDate(), source.transactions(), true, null, detail);
        CaisseEpargnePerService.Prepared prepared = new CaisseEpargnePerService.Prepared(
            new BigDecimal("1000"), BigDecimal.ZERO, navDate,
            List.of(new CaisseEpargnePerService.PreparedPosition(
                "TEST.PA", "Synthetic fund", new BigDecimal("10"), new BigDecimal("100"),
                new BigDecimal("1000"), navDate, "XX0000000001")), Map.of(), java.util.Set.of());
        BalanceSnapshotRepository snapshots = mock(BalanceSnapshotRepository.class);
        AccountHoldingRepository holdings = mock(AccountHoldingRepository.class);
        PriceService prices = mock(PriceService.class);
        AccountHolding currentHolding = AccountHolding.builder()
            .ticker("TEST.PA")
            .quantity(new BigDecimal("10"))
            .providerValueEur(new BigDecimal("1000"))
            .providerPriceDate(navDate)
            .build();
        lenient().when(holdings.findByAccount_Id(anyLong())).thenReturn(List.of(currentHolding));
        lenient().when(prices.getQuotes(any())).thenReturn(Map.of("TEST.PA",
            new PriceService.Quote(new BigDecimal("120"), TODAY, true)));
        when(snapshots.findByAccountIdAndDate(anyLong(), eq(navDate))).thenReturn(Optional.empty());
        when(snapshots.save(any(BalanceSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AccountService actualAccountService = new AccountService(
            accountRepository, snapshots, holdings, transactionRepository,
            mock(RealEstateMetadataRepository.class), mock(PropertyValuationRepository.class), debtRepository,
            mock(SavingsInterestConfigRepository.class), prices, mock(LoanAmortizationService.class),
            mock(AccountAccessResolver.class), mock(BankLogoResolver.class), mock(CryptoLogoService.class),
            mock(InstrumentLogoService.class), mock(ScpiPositionRepository.class), mock(ScpiFundService.class));
        when(perService.prepare(detail, "EUR")).thenReturn(prepared);
        CaisseEpargneSyncService sync = serviceWith(txTemplate, Runnable::run, actualAccountService);
        arrangeCommittableSync(withPer);

        sync.queueSync(7L);

        ArgumentCaptor<BalanceSnapshot> snapshot = ArgumentCaptor.forClass(BalanceSnapshot.class);
        verify(snapshots).save(snapshot.capture());
        assertThat(snapshot.getValue().getDate()).isEqualTo(navDate);
        assertThat(snapshot.getValue().getBalance()).isEqualByComparingTo("1000");
        assertThat(snapshot.getValue().getInvestedAmount()).isEqualByComparingTo("1000");
        verify(prices, never()).getQuotes(any());
    }

    @Test
    void queueSync_keepsCommittedImportSuccessfulAndRunsLaterPerBackfillsAfterOneFails() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(transactionStatus);
        CaisseEpargneSyncService transactional = serviceWith(new TransactionTemplate(txManager), Runnable::run);
        Fixture f = new Fixture();
        LocalDate valuationDate = TODAY.minusDays(2);
        CaisseEpargnePerService.Prepared first = new CaisseEpargnePerService.Prepared(
            new BigDecimal("4200.00"), new BigDecimal("4200.00"), valuationDate,
            List.of(), java.util.Map.of(), java.util.Set.of());
        CaisseEpargnePerService.Prepared second = new CaisseEpargnePerService.Prepared(
            new BigDecimal("5100.00"), new BigDecimal("5100.00"), valuationDate,
            List.of(), java.util.Map.of(), java.util.Set.of());
        when(perService.prepare(any(), eq("EUR"))).thenReturn(first, second);
        CaisseEpargnePort.AccountData firstPer = f.perWithDetails("contract:P0001", "4200.00", valuationDate);
        CaisseEpargnePort.AccountData secondPer = f.perWithDetails("contract:P0002", "5100.00", valuationDate);
        CaisseEpargneSession session = arrangeCommittableSync(firstPer, secondPer);
        org.mockito.Mockito.doThrow(new IllegalStateException("post-commit proxy failure"))
            .when(perService).backfill(first);

        CaisseEpargneSyncService.SessionStatusResponse result = transactional.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(CaisseEpargneSyncStatus.SUCCESS);
        assertThat(session.getSyncStatus()).isEqualTo(CaisseEpargneSyncStatus.SUCCESS);
        verify(txManager, times(3)).commit(any());
        verify(txManager, never()).rollback(any());
        verify(perService).backfill(first);
        verify(perService).backfill(second);
    }

    @Test
    void queueSync_withoutPerDetailKeepsTheBalanceOnlySnapshotAndNeverClearsHoldings() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.per());

        service.queueSync(7L);

        verify(perService, never()).apply(any(), any(), any());
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getCashBalance()).isNull();
    }

    @Test
    void queueSync_givesTheLddsTheSameColourAsTheLivretA() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.livret(), f.ldds());

        service.queueSync(7L);

        verify(accountRepository, times(2)).save(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues().get(1).getColor())
            .isEqualTo(accountCaptor.getAllValues().get(0).getColor());
    }

    @Test
    void queueSync_fallsBackToAReadableNameForEachNewKind() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.withName(f.ldds(), null), f.withName(f.per(), " "), f.withName(f.loan(), null));

        service.queueSync(7L);

        verify(accountRepository, times(3)).save(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues()).extracting(Account::getName)
            .containsExactly("LDDS", "PER", "Caisse d'Epargne loan");
    }

    @Test
    void queueSync_storesTheLoanOutstandingAsAPositiveBalance() {
        // Picsou stores a LOAN positive and applies the liability sign when it sums net worth;
        // the bank sends the outstanding capital negative.
        arrangeCommittableSync(new Fixture().loan());

        service.queueSync(7L);

        verify(accountRepository).save(accountCaptor.capture());
        Account loan = accountCaptor.getValue();
        assertThat(loan.getCurrentBalance()).isEqualByComparingTo("9000.00");
        assertThat(loan.getType().isLiability()).isTrue();
        verify(accountService).upsertSnapshot(any(Account.class), eq(new BigDecimal("9000.00")), eq(LocalDate.now()));
    }

    @Test
    void queueSync_refusesTheWholeSnapshotWhenALoanHasAPositiveBalance() {
        Fixture f = new Fixture();
        CaisseEpargneSession session = arrangeCommittableSync(f.current(), f.withBalance(f.loan(), "9000.00"));

        service.queueSync(7L);

        assertFailedWithNothingWritten(session, CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED);
    }

    @Test
    void queueSync_refusesTheWholeSnapshotWhenAPerHasANegativeBalance() {
        Fixture f = new Fixture();
        CaisseEpargneSession session = arrangeCommittableSync(f.current(), f.withBalance(f.per(), "-1.00"));

        service.queueSync(7L);

        assertFailedWithNothingWritten(session, CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED);
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
            List.of(), true, null);
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

    // -- loan debt ----------------------------------------------------------

    @Test
    void queueSync_createsTheDebtOfALoanFromTheBankDetails() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.current(), f.withLoan(f.loan(), f.loanDetails(f.current().iban())));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());

        service.queueSync(7L);

        verify(accountRepository, times(2)).save(accountCaptor.capture());
        Account current = accountCaptor.getAllValues().get(0);
        Account loan = accountCaptor.getAllValues().get(1);
        verify(debtRepository).save(debtCaptor.capture());
        Debt debt = debtCaptor.getValue();
        assertThat(debt.getAccount()).isSameAs(loan);
        assertThat(debt.getMember().getId()).isEqualTo(7L);
        assertThat(debt.getLinkedAccount()).isSameAs(current);
        assertThat(debt.getBorrowedAmount()).isEqualByComparingTo("10000.00");
        assertThat(debt.getInterestRate()).isEqualByComparingTo("0.045");
        assertThat(debt.getMonthlyPayment()).isEqualByComparingTo("230.00");
        assertThat(debt.getInsuranceMonthly()).isEqualByComparingTo("2.00");
        assertThat(debt.getLenderName()).isEqualTo("Caisse d'Epargne");
        // The schedule dates installment i at startDate + i months.
        assertThat(debt.getStartDate()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(debt.getEndDate()).isEqualTo(LocalDate.of(2028, 1, 5));
    }

    @Test
    void queueSync_givesTheLoanDebtOneInstallmentPerMonthFromTheFirstDueDate() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.withLoan(f.loan(), f.loanDetails(null)));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());

        service.queueSync(7L);

        verify(debtRepository).save(debtCaptor.capture());
        LoanAmortizationService.LoanScheduleResponse schedule =
            new LoanAmortizationService().compute(debtCaptor.getValue(), TODAY);
        assertThat(schedule.schedule()).hasSize(24);
        assertThat(schedule.schedule().get(0).date()).isEqualTo(LocalDate.of(2026, 2, 5));
        assertThat(schedule.schedule().get(23).date()).isEqualTo(LocalDate.of(2028, 1, 5));
        assertThat(schedule.schedule().get(23).remainingBalance()).isEqualByComparingTo("0");
    }

    @Test
    void queueSync_keepsAnEndOfMonthLoanOnItsDueDayThroughTheSchedule() {
        // What the sidecar sends for a 4-installment home loan ending 2042-05-31: the first due
        // date it derives (test_the_first_due_date_keeps_to_the_end_of_a_short_month) is clamped
        // to 2042-02-28.
        Fixture f = new Fixture();
        CaisseEpargnePort.LoanDetails endOfMonth = new CaisseEpargnePort.LoanDetails(
            new BigDecimal("1000.00"), new BigDecimal("0.012"), new BigDecimal("251.25"), new BigDecimal("0.00"),
            LocalDate.of(2042, 1, 2), LocalDate.of(2042, 2, 28), LocalDate.of(2042, 5, 31), 4, null);
        arrangeCommittableSync(f.withLoan(f.loan(), endOfMonth));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());

        service.queueSync(7L);

        verify(debtRepository).save(debtCaptor.capture());
        assertThat(debtCaptor.getValue().getStartDate()).isEqualTo(LocalDate.of(2042, 1, 31));
        LoanAmortizationService.LoanScheduleResponse schedule =
            new LoanAmortizationService().compute(debtCaptor.getValue(), TODAY);
        assertThat(schedule.schedule()).extracting(LoanAmortizationService.LoanInstallment::date).containsExactly(
            LocalDate.of(2042, 2, 28), LocalDate.of(2042, 3, 31), LocalDate.of(2042, 4, 30), LocalDate.of(2042, 5, 31));
    }

    @Test
    void queueSync_keepsAnEndOfMonthLoanOnItsDueDayWhenTheMonthBeforeTheFirstIsShorter() {
        // A 3-installment home loan ending 2042-05-31: first due 2042-03-31, so the anchor month
        // is February and the anchor itself is clamped to the 28th.
        Fixture f = new Fixture();
        CaisseEpargnePort.LoanDetails endOfMonth = new CaisseEpargnePort.LoanDetails(
            new BigDecimal("1000.00"), new BigDecimal("0.012"), new BigDecimal("334.33"), new BigDecimal("0.00"),
            LocalDate.of(2042, 1, 2), LocalDate.of(2042, 3, 31), LocalDate.of(2042, 5, 31), 3, null);
        arrangeCommittableSync(f.withLoan(f.loan(), endOfMonth));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());

        service.queueSync(7L);

        verify(debtRepository).save(debtCaptor.capture());
        assertThat(debtCaptor.getValue().getStartDate()).isEqualTo(LocalDate.of(2042, 2, 28));
        LoanAmortizationService.LoanScheduleResponse schedule =
            new LoanAmortizationService().compute(debtCaptor.getValue(), TODAY);
        assertThat(schedule.schedule()).extracting(LoanAmortizationService.LoanInstallment::date).containsExactly(
            LocalDate.of(2042, 3, 31), LocalDate.of(2042, 4, 30), LocalDate.of(2042, 5, 31));
    }

    @Test
    void queueSync_updatesTheExistingDebtOfTheLoanAndKeepsWhatTheBankDoesNotSend() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.withLoan(f.loan(), f.loanDetails(null)));
        Debt existing = Debt.builder().id(99L).borrowedAmount(new BigDecimal("1.00"))
            .lenderName("typed by the user").fileFees(new BigDecimal("50.00")).build();
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.of(existing));

        service.queueSync(7L);

        verify(debtRepository).save(debtCaptor.capture());
        Debt debt = debtCaptor.getValue();
        assertThat(debt).isSameAs(existing);
        assertThat(debt.getBorrowedAmount()).isEqualByComparingTo("10000.00");
        assertThat(debt.getStartDate()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(debt.getFileFees()).isEqualByComparingTo("50.00");
    }

    @Test
    void queueSync_keepsThePropertyTheUserLinkedWhenTheBankSendsNoDebitIban() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.withLoan(f.loan(), f.loanDetails(null)));
        Account property = Account.builder().id(600L).type(AccountType.REAL_ESTATE).build();
        Debt existing = Debt.builder().id(99L).linkedAccount(property).build();
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.of(existing));

        service.queueSync(7L);

        verify(debtRepository).save(debtCaptor.capture());
        assertThat(debtCaptor.getValue().getLinkedAccount()).isSameAs(property);
        assertThat(debtCaptor.getValue().getBorrowedAmount()).isEqualByComparingTo("10000.00");
    }

    @Test
    void queueSync_leavesTheDebtUntouchedWhenTheLoanDetailsAreMissing() {
        arrangeCommittableSync(new Fixture().loan());

        service.queueSync(7L);

        verify(accountRepository).save(any(Account.class));
        verify(debtRepository, never()).save(any());
    }

    @Test
    void queueSync_linksTheDebtToAnotherAccountOfTheMemberWithThatIban() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.withLoan(f.loan(), f.loanDetails("FR7600000000000000000000099")));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());
        Account elsewhere = Account.builder().id(500L).build();
        when(accountRepository.findByIbanAndCurrencyAndMemberId("FR7600000000000000000000099", "EUR", 7L))
            .thenReturn(Optional.of(elsewhere));

        service.queueSync(7L);

        verify(debtRepository).save(debtCaptor.capture());
        assertThat(debtCaptor.getValue().getLinkedAccount()).isSameAs(elsewhere);
    }

    @Test
    void queueSync_leavesTheDebtUnlinkedWhenNoAccountHasThatIban() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.current(), f.withLoan(f.loan(), f.loanDetails("FR7600000000000000000000099")));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());
        when(accountRepository.findByIbanAndCurrencyAndMemberId("FR7600000000000000000000099", "EUR", 7L))
            .thenReturn(Optional.empty());

        service.queueSync(7L);

        verify(debtRepository).save(debtCaptor.capture());
        assertThat(debtCaptor.getValue().getLinkedAccount()).isNull();
    }

    @Test
    void queueSync_doesNotLinkTheDebtThroughAnAmbiguousIban() {
        Fixture f = new Fixture();
        CaisseEpargnePort.AccountData current = f.current();
        arrangeCommittableSync(f.ambiguousIban(current), f.withLoan(f.loan(), f.loanDetails(current.iban())));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());
        lenient().when(accountRepository.findByIbanAndCurrencyAndMemberId(current.iban(), "EUR", 7L))
            .thenReturn(Optional.of(Account.builder().id(500L).build()));

        service.queueSync(7L);

        verify(debtRepository).save(debtCaptor.capture());
        assertThat(debtCaptor.getValue().getLinkedAccount()).isNull();
    }

    @Test
    void queueSync_ignoresIncompleteLoanDetailsButImportsTheLoan() {
        Fixture f = new Fixture();
        CaisseEpargnePort.LoanDetails full = f.loanDetails(null);
        CaisseEpargnePort.LoanDetails noFirstDue = new CaisseEpargnePort.LoanDetails(full.borrowedAmount(),
            full.interestRate(), full.monthlyPayment(), full.insuranceMonthly(), full.signatureDate(),
            null, full.lastDueDate(), full.durationMonths(), null);
        CaisseEpargneSession session = arrangeCommittableSync(f.withLoan(f.loan(), noFirstDue));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CaisseEpargneSyncStatus.SUCCESS);
        verify(accountRepository).save(any(Account.class));
        verify(debtRepository, never()).save(any());
    }

    @Test
    void queueSync_ignoresLoanAmountsTheDebtCannotStoreButImportsEveryAccount() {
        // debt.borrowed_amount is NUMERIC(20, 8): 10^12 has one integer digit too many, and its
        // insert would roll back the current account with it.
        Fixture f = new Fixture();
        CaisseEpargnePort.LoanDetails full = f.loanDetails(null);
        CaisseEpargnePort.LoanDetails oversized = new CaisseEpargnePort.LoanDetails(new BigDecimal("1000000000000"),
            full.interestRate(), full.monthlyPayment(), full.insuranceMonthly(), full.signatureDate(),
            full.firstDueDate(), full.lastDueDate(), full.durationMonths(), null);
        CaisseEpargneSession session = arrangeCommittableSync(f.current(), f.withLoan(f.loan(), oversized));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CaisseEpargneSyncStatus.SUCCESS);
        verify(accountRepository, times(2)).save(any(Account.class));
        verify(debtRepository, never()).save(any());
    }

    @Test
    void queueSync_acceptsTheLargestLoanAmountTheDebtCanStore() {
        Fixture f = new Fixture();
        CaisseEpargnePort.LoanDetails full = f.loanDetails(null);
        CaisseEpargnePort.LoanDetails largest = new CaisseEpargnePort.LoanDetails(
            new BigDecimal("999999999999.999999994"), full.interestRate(), full.monthlyPayment(),
            full.insuranceMonthly(), full.signatureDate(), full.firstDueDate(), full.lastDueDate(),
            full.durationMonths(), null);
        arrangeCommittableSync(f.withLoan(f.loan(), largest));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());

        service.queueSync(7L);

        verify(debtRepository).save(any());
    }

    @Test
    void queueSync_neverCreatesADebtForAnAccountThatIsNotALoan() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.withLoan(f.current(), f.loanDetails(null)));

        service.queueSync(7L);

        verify(debtRepository, never()).save(any());
    }

    // -- loan budget tagging --------------------------------------------------

    /** The synthetic loan of {@link Fixture#loanDetails}, debited from the current account 1001. */
    private CaisseEpargnePort.AccountData linkedLoan(Fixture f) {
        return f.withLoan(f.loan(), f.loanDetails(f.current().iban()));
    }

    private Category creditCategory() {
        Category credit = Category.builder().id(901L).slug("credit").name("Crédit")
            .kind(CategoryKind.EXPENSE).build();
        lenient().when(categoryService.findOrCreateDefault(7L, "credit")).thenReturn(Optional.of(credit));
        return credit;
    }

    /** Rows listed get exactly that category; every other written row stays uncategorized. */
    private void assertCategories(java.util.Map<String, Category> expected) {
        writtenRows().forEach((id, row) -> {
            if (expected.containsKey(id)) {
                assertThat(row.getCategoryRef()).as(id).isSameAs(expected.get(id));
            } else {
                assertThat(row.getCategoryRef()).as(id).isNull();
            }
        });
    }

    @Test
    void queueSync_tagsTheLoanDisbursementOnTheLinkedAccountAsAnInternalTransfer() {
        Fixture f = new Fixture();
        Category transfer = transferCategory();
        arrangeCommittableSync(
            f.currentWith(
                f.tx("d1", "2026-01-12", "10000.00", "VIR SEPA BANQUE financement"),
                f.tx("d2", "2026-01-12", "10000.00", "VIR SEPA SALAIRE"),
                f.tx("d3", "2026-01-13", "9999.00", "VIR SEPA BANQUE FINANCEMENT")),
            linkedLoan(f));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());

        service.queueSync(7L);

        assertCategories(java.util.Map.of("ce_d1", transfer));
    }

    @Test
    void queueSync_acceptsTheDisbursementAtTheEdgesOfItsWindowAndToTheCent() {
        Fixture f = new Fixture();
        Category transfer = transferCategory();
        arrangeCommittableSync(f.currentWith(f.tx("d1", "2026-01-05", "10000.01", "FINANCEMENT")), linkedLoan(f));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());

        service.queueSync(7L);

        assertCategories(java.util.Map.of("ce_d1", transfer));
    }

    @Test
    void queueSync_leavesADisbursementOutsideItsWindowAlone() {
        Fixture f = new Fixture();
        transferCategory();
        arrangeCommittableSync(
            f.currentWith(
                f.tx("d1", "2026-01-04", "10000.00", "FINANCEMENT"),
                f.tx("d2", "2026-02-06", "10000.00", "FINANCEMENT")),
            linkedLoan(f));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());

        service.queueSync(7L);

        assertCategories(java.util.Map.of());
    }

    @Test
    void queueSync_leavesTheDisbursementAloneWhenTwoCreditsMatch() {
        Fixture f = new Fixture();
        transferCategory();
        arrangeCommittableSync(
            f.currentWith(
                f.tx("d1", "2026-01-12", "10000.00", "FINANCEMENT"),
                f.tx("d2", "2026-01-20", "10000.00", "FINANCEMENT")),
            linkedLoan(f));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());

        service.queueSync(7L);

        assertCategories(java.util.Map.of());
    }

    @Test
    void queueSync_tagsEachInstallmentWithinFiveDaysOfItsDueDateAsCredit() {
        Fixture f = new Fixture();
        Category credit = creditCategory();
        arrangeCommittableSync(
            f.currentWith(
                f.tx("i1", "2026-02-05", "-230.00", "PRELEVEMENT ECHEANCE"),
                f.tx("i2", "2026-03-10", "-229.99", "PRELEVEMENT ECHEANCE"),
                f.tx("i3", "2026-03-31", "-230.00", "PRELEVEMENT ECHEANCE"),
                f.tx("i4", "2026-04-11", "-230.00", "PRELEVEMENT ECHEANCE"),
                f.tx("i5", "2026-05-05", "-231.00", "PRELEVEMENT ECHEANCE"),
                f.tx("i6", "2026-06-05", "230.00", "REMBOURSEMENT")),
            linkedLoan(f));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());

        service.queueSync(7L);

        // i3 is 5 days before the April due date, i4 6 days after it: only i3 is the April one.
        assertCategories(java.util.Map.of("ce_i1", credit, "ce_i2", credit, "ce_i3", credit));
    }

    @Test
    void queueSync_neverTagsAnInstallmentBeforeTheFirstOrAfterTheLastDueDate() {
        Fixture f = new Fixture();
        creditCategory();
        arrangeCommittableSync(
            f.currentWith(
                f.tx("i0", "2026-01-05", "-230.00", "PRELEVEMENT"),
                f.tx("i9", "2028-02-05", "-230.00", "PRELEVEMENT")),
            linkedLoan(f));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());

        service.queueSync(7L);

        assertCategories(java.util.Map.of());
    }

    @Test
    void queueSync_leavesAnInstallmentAloneWhenTwoDebitsMatchTheSameDueDate() {
        Fixture f = new Fixture();
        Category credit = creditCategory();
        arrangeCommittableSync(
            f.currentWith(
                f.tx("i1", "2026-02-04", "-230.00", "PRELEVEMENT"),
                f.tx("i2", "2026-02-06", "-230.00", "PRELEVEMENT"),
                f.tx("i3", "2026-03-05", "-230.00", "PRELEVEMENT")),
            linkedLoan(f));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());

        service.queueSync(7L);

        assertCategories(java.util.Map.of("ce_i3", credit));
    }

    @Test
    void queueSync_neverOverwritesACategoryTheUserChoseOnALoanRow() {
        Fixture f = new Fixture();
        transferCategory();
        creditCategory();
        Category mine = Category.builder().id(42L).slug(null).name("Mine").kind(CategoryKind.EXPENSE).build();
        Transaction installment = Transaction.builder().externalId("ce_i1").categoryRef(mine).build();
        Transaction disbursement = Transaction.builder().externalId("ce_d1").categoryRef(mine).build();
        arrangeCommittableSync(
            f.currentWith(
                f.tx("d1", "2026-01-12", "10000.00", "FINANCEMENT"),
                f.tx("i1", "2026-02-05", "-230.00", "PRELEVEMENT")),
            linkedLoan(f));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());
        when(transactionRepository.findByAccountIdAndIsManualFalse(11L)).thenReturn(List.of(installment, disbursement));

        service.queueSync(7L);

        assertCategories(java.util.Map.of("ce_i1", mine, "ce_d1", mine));
    }

    @Test
    void queueSync_tagsTheLoanRowsOnceWhenTheSyncRunsAgain() {
        Fixture f = new Fixture();
        Category transfer = transferCategory();
        Category credit = creditCategory();
        arrangeCommittableSync(
            f.currentWith(
                f.tx("d1", "2026-01-12", "10000.00", "FINANCEMENT"),
                f.tx("i1", "2026-02-05", "-230.00", "PRELEVEMENT")),
            linkedLoan(f));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());
        service.queueSync(7L);
        java.util.Map<String, Transaction> first = writtenRows();
        when(transactionRepository.findByAccountIdAndIsManualFalse(11L))
            .thenReturn(List.of(first.get("ce_d1"), first.get("ce_i1")));

        service.queueSync(7L);

        assertCategories(java.util.Map.of("ce_d1", transfer, "ce_i1", credit));
        assertThat(writtenRows().get("ce_i1")).isSameAs(first.get("ce_i1"));
    }

    @Test
    void queueSync_leavesInstallmentsAloneWhenTheMemberArchivedTheCreditCategory() {
        Fixture f = new Fixture();
        when(categoryService.findOrCreateDefault(7L, "credit")).thenReturn(Optional.empty());
        CaisseEpargneSession session = arrangeCommittableSync(
            f.currentWith(f.tx("i1", "2026-02-05", "-230.00", "PRELEVEMENT")), linkedLoan(f));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CaisseEpargneSyncStatus.SUCCESS);
        assertCategories(java.util.Map.of());
    }

    @Test
    void queueSync_tagsNothingWhenTheLoanIsNotLinkedToAnImportedAccount() {
        Fixture f = new Fixture();
        transferCategory();
        creditCategory();
        arrangeCommittableSync(
            f.currentWith(
                f.tx("d1", "2026-01-12", "10000.00", "FINANCEMENT"),
                f.tx("i1", "2026-02-05", "-230.00", "PRELEVEMENT")),
            f.withLoan(f.loan(), f.loanDetails(null)));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());

        service.queueSync(7L);

        assertCategories(java.util.Map.of());
        verify(categoryService, never()).findOrCreateDefault(any(), any());
    }

    @Test
    void queueSync_createsNoCreditCategoryWhenNoInstallmentMatches() {
        Fixture f = new Fixture();
        arrangeCommittableSync(f.current(), linkedLoan(f));
        when(debtRepository.findByAccountId(anyLong())).thenReturn(Optional.empty());

        service.queueSync(7L);

        verify(categoryService, never()).findOrCreateDefault(any(), any());
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
        return serviceWith(template, executor, accountService);
    }

    private CaisseEpargneSyncService serviceWith(
        TransactionTemplate template, Executor executor, AccountService snapshotService
    ) {
        return new CaisseEpargneSyncService(
            port, sessionRepository, accountRepository, transactionRepository,
            memberRepository, debtRepository, snapshotService, categorizationService, categoryService, encryption,
            template, executor,
            perService, Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
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
                List.of(rows), base.snapshotComplete(), base.loan());
        }

        CaisseEpargnePort.AccountData current() {
            return new CaisseEpargnePort.AccountData("1001", "CURRENT_ACCOUNT", "COMPTE COURANT",
                new BigDecimal("1234.56"), "EUR", "FR0000000000000000000000000", false,
                new BigDecimal("500.00"), null, null, null, null, null, null,
                List.of(tx("t1", "2026-10-01", "-12.30", "SUPERMARCHE")), true, null);
        }

        CaisseEpargnePort.AccountData livret() {
            return new CaisseEpargnePort.AccountData("1002", "LIVRET_A", "LIVRET A",
                new BigDecimal("5000.00"), "EUR", null, false,
                null, new BigDecimal("22950.00"), new BigDecimal("17950.00"), new BigDecimal("0.2179"),
                null, null, null, List.of(), true, null);
        }

        CaisseEpargnePort.AccountData ldds() {
            return new CaisseEpargnePort.AccountData("1003", "LDDS", "LIVRET DEV DURABLE",
                new BigDecimal("3000.00"), "EUR", null, false,
                null, new BigDecimal("12000.00"), new BigDecimal("9000.00"), new BigDecimal("0.25"),
                null, null, null, List.of(), true, null);
        }

        CaisseEpargnePort.AccountData per() {
            return new CaisseEpargnePort.AccountData("contract:P0001", "PER", "PLAN EPARGNE RETRAITE",
                new BigDecimal("4200.00"), "EUR", null, false,
                null, null, null, null, null, null, null, List.of(), true, null);
        }

        CaisseEpargnePort.AccountData perWithDetails(String externalId, String balance, LocalDate valuationDate) {
            CaisseEpargnePort.PerDetails details = new CaisseEpargnePort.PerDetails(
                new BigDecimal(balance), new BigDecimal(balance), valuationDate, List.of());
            return new CaisseEpargnePort.AccountData(externalId, "PER", "PLAN EPARGNE RETRAITE",
                new BigDecimal(balance), "EUR", null, false, null, null, null, null, null, null, null,
                List.of(), true, null, details);
        }

        CaisseEpargnePort.AccountData loan() {
            return new CaisseEpargnePort.AccountData("contract:L0001", "LOAN", "PRET PERSONNEL",
                new BigDecimal("-9000.00"), "EUR", null, false,
                null, null, null, null, null, null, null, List.of(), true, null);
        }

        /** Synthetic 24-month personal loan, first installment 2026-02-05. */
        CaisseEpargnePort.LoanDetails loanDetails(String linkedIban) {
            return new CaisseEpargnePort.LoanDetails(new BigDecimal("10000.00"), new BigDecimal("0.045"),
                new BigDecimal("230.00"), new BigDecimal("2.00"), LocalDate.of(2026, 1, 10),
                LocalDate.of(2026, 2, 5), LocalDate.of(2028, 1, 5), 24, linkedIban);
        }

        CaisseEpargnePort.AccountData withLoan(CaisseEpargnePort.AccountData a, CaisseEpargnePort.LoanDetails loan) {
            return new CaisseEpargnePort.AccountData(a.externalId(), a.kind(), a.name(), a.balance(), a.currency(),
                a.iban(), a.ibanAmbiguous(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(), a.transactions(),
                a.snapshotComplete(), loan);
        }

        CaisseEpargnePort.AccountData ambiguousIban(CaisseEpargnePort.AccountData a) {
            return new CaisseEpargnePort.AccountData(a.externalId(), a.kind(), a.name(), a.balance(), a.currency(),
                a.iban(), true, a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(), a.transactions(),
                a.snapshotComplete(), a.loan());
        }

        CaisseEpargnePort.AccountData card() {
            return new CaisseEpargnePort.AccountData("2001", "CARD", null,
                new BigDecimal("-87.10"), "EUR", null, false,
                null, null, null, null, "DEFERRED_DEBIT", "1001", LocalDate.of(2026, 10, 31),
                List.of(new CaisseEpargnePort.Transaction("c1", LocalDate.of(2026, 10, 2),
                    LocalDate.of(2026, 10, 31), new BigDecimal("-87.10"), "EUR", "RESTAURANT", "04")), true, null);
        }

        CaisseEpargnePort.AccountData with(CaisseEpargnePort.AccountData a, List<CaisseEpargnePort.Transaction> txs) {
            return new CaisseEpargnePort.AccountData(a.externalId(), a.kind(), a.name(), a.balance(), a.currency(),
                a.iban(), a.ibanAmbiguous(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(), txs, a.snapshotComplete(), a.loan());
        }

        CaisseEpargnePort.AccountData withKind(CaisseEpargnePort.AccountData a, String kind) {
            return new CaisseEpargnePort.AccountData(a.externalId(), kind, a.name(), a.balance(), a.currency(),
                a.iban(), a.ibanAmbiguous(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(), a.transactions(), a.snapshotComplete(), a.loan());
        }

        CaisseEpargnePort.AccountData withName(CaisseEpargnePort.AccountData a, String name) {
            return new CaisseEpargnePort.AccountData(a.externalId(), a.kind(), name, a.balance(), a.currency(),
                a.iban(), a.ibanAmbiguous(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(), a.transactions(), a.snapshotComplete(), a.loan());
        }

        CaisseEpargnePort.AccountData withBalance(CaisseEpargnePort.AccountData a, String balance) {
            return new CaisseEpargnePort.AccountData(a.externalId(), a.kind(), a.name(), new BigDecimal(balance),
                a.currency(), a.iban(), a.ibanAmbiguous(), a.authorizedOverdraft(), a.ceiling(),
                a.remainingDepositCapacity(), a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(),
                a.transactions(), a.snapshotComplete(), a.loan());
        }

        CaisseEpargnePort.AccountData withCurrency(CaisseEpargnePort.AccountData a, String currency) {
            return new CaisseEpargnePort.AccountData(a.externalId(), a.kind(), a.name(), a.balance(), currency,
                a.iban(), a.ibanAmbiguous(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(), a.transactions(), a.snapshotComplete(), a.loan());
        }

        CaisseEpargnePort.AccountData withNature(CaisseEpargnePort.AccountData a, String nature) {
            return new CaisseEpargnePort.AccountData(a.externalId(), a.kind(), a.name(), a.balance(), a.currency(),
                a.iban(), a.ibanAmbiguous(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), nature, a.parentExternalId(), a.nextDueDate(), a.transactions(),
                a.snapshotComplete(), a.loan());
        }

        CaisseEpargnePort.AccountData incomplete(CaisseEpargnePort.AccountData a) {
            return new CaisseEpargnePort.AccountData(a.externalId(), a.kind(), a.name(), a.balance(), a.currency(),
                a.iban(), a.ibanAmbiguous(), a.authorizedOverdraft(), a.ceiling(), a.remainingDepositCapacity(),
                a.fillingRatio(), a.cardNature(), a.parentExternalId(), a.nextDueDate(), a.transactions(), false, a.loan());
        }
    }
}
