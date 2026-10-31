package com.picsou.finary;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.CompositePriceProvider;
import com.picsou.adapter.YahooFinancePriceProvider;
import com.picsou.adapter.YahooFinanceStubs;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.DividendEvent;
import com.picsou.model.DividendSource;
import com.picsou.model.DividendSourceStatus;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.DividendSourceRepository;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.AccountAccessResolver;
import com.picsou.service.DividendReceiptService;
import com.picsou.service.PriceService;
import com.picsou.service.budget.CategorizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rebuilt history records, under a transaction's date, the balance at the END of that day.
 * Walking backwards from today's balance, that is the running balance before the day's
 * transactions are subtracted; subtracting first stored the balance before the day's movements,
 * so every rebuilt point was one day's transactions off.
 */
@ExtendWith(MockitoExtension.class)
class FinaryPersistenceHelperTest {

    @Mock BalanceSnapshotRepository balanceSnapshotRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock PriceService priceService;

    @InjectMocks FinaryPersistenceHelper helper;

    @Test
    void reconstructSnapshotsFromDb_recordsTheEndOfDayBalanceUnderEachDay() {
        LocalDate today = LocalDate.now();
        LocalDate twoDaysAgo = today.minusDays(2);
        LocalDate yesterday = today.minusDays(1);
        Account account = Account.builder().id(1L).name("Livret").currency("EUR")
            .currentBalance(new BigDecimal("1000")).build();
        // Newest first, as the repository returns them: -50 yesterday, +200 two days ago.
        when(transactionRepository.findByAccountIdOrderByDateDesc(1L)).thenReturn(List.of(
            Transaction.builder().account(account).date(yesterday).amount(new BigDecimal("-50")).build(),
            Transaction.builder().account(account).date(twoDaysAgo).amount(new BigDecimal("200")).build()));
        when(balanceSnapshotRepository.findRecentByAccountId(1L, LocalDate.of(2000, 1, 1))).thenReturn(List.of());

        helper.reconstructSnapshotsFromDb(account);

        ArgumentCaptor<BalanceSnapshot> saved = ArgumentCaptor.forClass(BalanceSnapshot.class);
        verify(balanceSnapshotRepository, atLeastOnce()).save(saved.capture());
        Map<LocalDate, BigDecimal> byDate = saved.getAllValues().stream()
            .collect(Collectors.toMap(BalanceSnapshot::getDate, BalanceSnapshot::getBalance));
        assertThat(byDate.get(today)).isEqualByComparingTo("1000");
        // End of yesterday: after the -50, i.e. today's balance.
        assertThat(byDate.get(yesterday)).isEqualByComparingTo("1000");
        // End of two days ago: before the -50, after the +200.
        assertThat(byDate.get(twoDaysAgo)).isEqualByComparingTo("1050");
    }

    @Test
    void reconstructSnapshots_recordsTheEndOfDayBalanceUnderEachDay() {
        LocalDate today = LocalDate.now();
        LocalDate twoDaysAgo = today.minusDays(2);
        LocalDate yesterday = today.minusDays(1);
        Account account = Account.builder().id(1L).name("Livret").currency("EUR")
            .currentBalance(new BigDecimal("1000")).build();
        FinaryPersistenceHelper.ParsedFinaryAccount parsed = new FinaryPersistenceHelper.ParsedFinaryAccount(
            "Livret", "Finary", "savings", new BigDecimal("1000"), "EUR");
        when(balanceSnapshotRepository.findRecentByAccountId(1L, LocalDate.of(2000, 1, 1))).thenReturn(List.of());

        helper.reconstructSnapshots(account, parsed, List.of(
            new FinaryPersistenceHelper.ParsedFinaryTransaction(
                "Livret", twoDaysAgo, "deposit", new BigDecimal("200"), "credit", "", "EUR"),
            new FinaryPersistenceHelper.ParsedFinaryTransaction(
                "Livret", yesterday, "withdrawal", new BigDecimal("-50"), "debit", "", "EUR")));

        ArgumentCaptor<BalanceSnapshot> saved = ArgumentCaptor.forClass(BalanceSnapshot.class);
        verify(balanceSnapshotRepository, atLeastOnce()).save(saved.capture());
        Map<LocalDate, BigDecimal> byDate = saved.getAllValues().stream()
            .collect(Collectors.toMap(BalanceSnapshot::getDate, BalanceSnapshot::getBalance));
        assertThat(byDate.get(today)).isEqualByComparingTo("1000");
        assertThat(byDate.get(yesterday)).isEqualByComparingTo("1000");
        assertThat(byDate.get(twoDaysAgo)).isEqualByComparingTo("1050");
    }

    @Test
    void reconstructSnapshotsFromDb_writesTheHistoryInEur_convertingTheBalanceAndEveryTransaction() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate twoDaysAgo = today.minusDays(2);
        LocalDate threeDaysAgo = today.minusDays(3);
        Account account = Account.builder().id(1L).name("US checking").currency("USD")
            .currentBalance(new BigDecimal("1000")).build();
        // A synced USD row, then a retained manual one in GBP.
        when(transactionRepository.findByAccountIdOrderByDateDesc(1L)).thenReturn(List.of(
            Transaction.builder().account(account).date(yesterday).amount(new BigDecimal("100"))
                .nativeCurrency("USD").build(),
            Transaction.builder().account(account).date(twoDaysAgo).amount(new BigDecimal("10"))
                .nativeCurrency("GBP").isManual(true).build(),
            Transaction.builder().account(account).date(threeDaysAgo).amount(BigDecimal.ONE)
                .nativeCurrency("EUR").build()));
        when(balanceSnapshotRepository.findRecentByAccountId(1L, LocalDate.of(2000, 1, 1))).thenReturn(List.of());
        when(priceService.getFxRateEur("USD")).thenReturn(new BigDecimal("0.9"));
        when(priceService.getFxRateEur("GBP")).thenReturn(new BigDecimal("1.2"));

        helper.reconstructSnapshotsFromDb(account);

        ArgumentCaptor<BalanceSnapshot> saved = ArgumentCaptor.forClass(BalanceSnapshot.class);
        verify(balanceSnapshotRepository, atLeastOnce()).save(saved.capture());
        Map<LocalDate, BigDecimal> byDate = saved.getAllValues().stream()
            .collect(Collectors.toMap(BalanceSnapshot::getDate, BalanceSnapshot::getBalance));
        assertThat(byDate.get(today)).isEqualByComparingTo("900");
        assertThat(byDate.get(yesterday)).isEqualByComparingTo("900");
        // Before yesterday's $100 (€90).
        assertThat(byDate.get(twoDaysAgo)).isEqualByComparingTo("810");
        // Before the manual £10 (€12).
        assertThat(byDate.get(threeDaysAgo)).isEqualByComparingTo("798");
    }

    @Test
    void reconstructSnapshotsFromDb_convertsWithTheExchangeRate_notTheQuoteOfTheEtfTickeredUsd() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate twoDaysAgo = LocalDate.now().minusDays(2);
        // "USD" is also the ProShares Ultra Semiconductors ETF, quoted at $100.
        YahooFinancePriceProvider yahoo = YahooFinanceStubs.provider(url -> {
            if (url.contains("/USDEUR")) return """
                {"chart":{"result":[{"meta":{"regularMarketPrice":0.9,"currency":"EUR"}}]}}""";
            if (url.contains("/chart/USD")) return """
                {"chart":{"result":[{"meta":{"regularMarketPrice":100.0,"currency":"USD"}}]}}""";
            return null;
        });
        FinaryPersistenceHelper realFx = new FinaryPersistenceHelper(balanceSnapshotRepository,
            transactionRepository, new PriceService(
                new CompositePriceProvider(mock(CoinGeckoPriceProvider.class), yahoo),
                mock(PriceSnapshotRepository.class)));
        Account account = Account.builder().id(1L).name("Checking").currency("EUR")
            .currentBalance(new BigDecimal("1000")).build();
        when(transactionRepository.findByAccountIdOrderByDateDesc(1L)).thenReturn(List.of(
            Transaction.builder().account(account).date(yesterday).amount(new BigDecimal("100"))
                .nativeCurrency("USD").build(),
            Transaction.builder().account(account).date(twoDaysAgo).amount(BigDecimal.ONE)
                .nativeCurrency("EUR").build()));
        when(balanceSnapshotRepository.findRecentByAccountId(1L, LocalDate.of(2000, 1, 1))).thenReturn(List.of());

        realFx.reconstructSnapshotsFromDb(account);

        ArgumentCaptor<BalanceSnapshot> saved = ArgumentCaptor.forClass(BalanceSnapshot.class);
        verify(balanceSnapshotRepository, atLeastOnce()).save(saved.capture());
        Map<LocalDate, BigDecimal> byDate = saved.getAllValues().stream()
            .collect(Collectors.toMap(BalanceSnapshot::getDate, BalanceSnapshot::getBalance));
        // Before yesterday's $100 at 0.9: €910, not 1000 - 100 × (100 × 0.9).
        assertThat(byDate.get(twoDaysAgo)).isEqualByComparingTo("910");
    }

    @Test
    void reconstructSnapshotsFromDb_aborts_ratherThanWritingAnUnconvertedAmountAsEur() {
        Account account = Account.builder().id(1L).name("Checking").currency("EUR")
            .currentBalance(new BigDecimal("1000")).build();
        when(transactionRepository.findByAccountIdOrderByDateDesc(1L)).thenReturn(List.of(
            Transaction.builder().account(account).date(LocalDate.now().minusDays(1))
                .amount(new BigDecimal("100")).nativeCurrency("USD").build()));
        when(priceService.getFxRateEur("USD")).thenReturn(null);

        assertThatThrownBy(() -> helper.reconstructSnapshotsFromDb(account))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("USD");
        verify(balanceSnapshotRepository, never()).deleteAllInBatch(any());
        verify(balanceSnapshotRepository, never()).save(any(BalanceSnapshot.class));
    }

    @Test
    void importTransactions_keepsADividendRecognisedBeforeTheClassificationWindow() {
        // Every Finary sync deletes and recreates the account's rows, while classification only
        // revisits 45 days: a dividend recognised 50 days ago would come back as a plain credit.
        LocalDate paid = LocalDate.now().minusDays(50);
        Account cto = Account.builder()
            .id(1L).name("CTO").type(AccountType.COMPTE_TITRES).provider("Finary").currency("EUR").build();
        Category revenusCapital = Category.builder()
            .id(100L).slug("revenus-capital").name("Revenus du capital").kind(CategoryKind.INCOME).build();
        Transaction stored = Transaction.builder()
            .id(5L).account(cto).date(paid).description("DIVIDENDE TOTALENERGIES")
            .amount(new BigDecimal("59.50")).nativeCurrency("EUR").build();
        AtomicReference<List<Transaction>> ledger = new AtomicReference<>(List.of(stored));
        DividendReceiptService receipts = realReceipts(cto, revenusCapital, ledger, paid.minusDays(9));
        assertThat(receipts.classifyRecent(7L, paid.plusDays(1))).isEqualTo(1);
        when(transactionRepository.findByAccountIdAndIsManualFalse(1L)).thenReturn(List.of(stored));

        helper.importTransactions(cto,
            new FinaryPersistenceHelper.ParsedFinaryAccount("CTO", "Bourse Direct", "investments", BigDecimal.TEN, "EUR"),
            List.of(new FinaryPersistenceHelper.ParsedFinaryTransaction(
                "CTO", paid, "DIVIDENDE TOTALENERGIES", new BigDecimal("59.5"), "credit", "income", "EUR")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> saved = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(row -> {
            assertThat(row.getTxType()).isEqualTo(TransactionType.DIVIDEND);
            assertThat(row.getTicker()).isEqualTo("TTE.PA");
            assertThat(row.getCategoryRef()).isSameAs(revenusCapital);
        });
        ledger.set(saved.getValue());
        assertThat(receipts.received(7L, LocalDate.now(), 90)).singleElement().satisfies(line -> {
            assertThat(line.ticker()).isEqualTo("TTE.PA");
            assertThat(line.receivedDate()).isEqualTo(paid);
        });
    }

    @Test
    void importTransactions_restoresEachRecognitionOnASingleReimportedRow() {
        LocalDate paid = LocalDate.now().minusDays(50);
        Account cto = Account.builder().id(1L).name("CTO").currency("EUR").build();
        when(transactionRepository.findByAccountIdAndIsManualFalse(1L)).thenReturn(List.of(Transaction.builder()
            .account(cto).date(paid).description("DIVIDENDE").amount(new BigDecimal("59.50"))
            .txType(TransactionType.DIVIDEND).ticker("TTE.PA").build()));
        FinaryPersistenceHelper.ParsedFinaryTransaction credit = new FinaryPersistenceHelper.ParsedFinaryTransaction(
            "CTO", paid, "DIVIDENDE", new BigDecimal("59.50"), "credit", "income", "EUR");

        helper.importTransactions(cto,
            new FinaryPersistenceHelper.ParsedFinaryAccount("CTO", "Bourse Direct", "investments", BigDecimal.TEN, "EUR"),
            List.of(credit, credit));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> saved = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(Transaction::getTxType)
            .containsExactlyInAnyOrder(TransactionType.DIVIDEND, null);
    }

    private static DividendReceiptService realReceipts(
        Account account,
        Category revenusCapital,
        AtomicReference<List<Transaction>> ledger,
        LocalDate exDate
    ) {
        AccountRepository accounts = mock(AccountRepository.class);
        AccountAccessResolver access = mock(AccountAccessResolver.class);
        AccountHoldingRepository holdings = mock(AccountHoldingRepository.class);
        DividendSourceRepository sources = mock(DividendSourceRepository.class);
        TransactionRepository transactions = mock(TransactionRepository.class);
        CategorizationService categorization = mock(CategorizationService.class);
        DividendSource source = DividendSource.builder()
            .ticker("TTE.PA").currency("EUR").status(DividendSourceStatus.OK).events(new ArrayList<>()).build();
        source.getEvents().add(DividendEvent.builder()
            .source(source).exDate(exDate).amount(new BigDecimal("0.85")).amountEur(new BigDecimal("0.85")).build());
        lenient().when(accounts.findAllByMemberIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(account));
        lenient().when(access.readableAccounts(7L)).thenReturn(List.of(account));
        lenient().when(holdings.findPositiveByAccountIdIn(anyCollection())).thenReturn(List.of(AccountHolding.builder()
            .account(account).ticker("TTE.PA").name("TotalEnergies").quantity(new BigDecimal("100")).build()));
        lenient().when(sources.findAllWithEventsByTickerIn(anyCollection())).thenReturn(List.of(source));
        lenient().when(categorization.categoriesBySlug(7L)).thenReturn(Map.of("revenus-capital", revenusCapital));
        lenient().when(transactions.findByAccountIdInAndDateBetweenOrderByDateAsc(anyCollection(), any(), any()))
            .thenAnswer(invocation -> ledger.get());
        return new DividendReceiptService(accounts, access, holdings, sources, transactions, categorization);
    }
}
