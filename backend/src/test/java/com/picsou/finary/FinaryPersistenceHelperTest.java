package com.picsou.finary;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.CryptoAssetRegistry;
import com.picsou.adapter.CompositePriceProvider;
import com.picsou.adapter.PreciousMetalPriceProvider;
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
import com.picsou.model.SnapshotSource;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
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
                new CompositePriceProvider(mock(CoinGeckoPriceProvider.class), yahoo,
                    new PreciousMetalPriceProvider(yahoo),
                    CryptoAssetRegistry.of(java.util.Map.of()), java.time.Clock.systemUTC()),
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

    @Test
    void reconstructSnapshotsFromDb_preservesHandEnteredStatements() {
        LocalDate today = LocalDate.now();
        LocalDate january = today.minusDays(40);
        LocalDate february = today.minusDays(10);
        LocalDate march = today.minusDays(3);
        Account account = Account.builder().id(1L).name("Livret").currency("EUR")
            .currentBalance(new BigDecimal("1250")).build();
        // One transaction since the February statement; the walk would otherwise rebuild
        // every row from the ledger alone.
        when(transactionRepository.findByAccountIdOrderByDateDesc(1L)).thenReturn(List.of(
            Transaction.builder().account(account).date(march).amount(new BigDecimal("50")).build()));
        BalanceSnapshot januaryStatement = BalanceSnapshot.builder()
            .account(account).date(january).balance(new BigDecimal("1000"))
            .investedAmount(new BigDecimal("1000")).source(SnapshotSource.MANUAL).build();
        BalanceSnapshot februaryStatement = BalanceSnapshot.builder()
            .account(account).date(february).balance(new BigDecimal("1200"))
            .investedAmount(new BigDecimal("1200")).source(SnapshotSource.MANUAL).build();
        when(balanceSnapshotRepository.findRecentByAccountId(1L, LocalDate.of(2000, 1, 1)))
            .thenReturn(List.of(januaryStatement, februaryStatement));

        helper.reconstructSnapshotsFromDb(account);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BalanceSnapshot>> deleted = ArgumentCaptor.forClass(List.class);
        verify(balanceSnapshotRepository).deleteAllInBatch(deleted.capture());
        assertThat(deleted.getValue()).isEmpty();
        ArgumentCaptor<BalanceSnapshot> saved = ArgumentCaptor.forClass(BalanceSnapshot.class);
        verify(balanceSnapshotRepository, atLeastOnce()).save(saved.capture());
        Map<LocalDate, BigDecimal> byDate = saved.getAllValues().stream()
            .collect(Collectors.toMap(BalanceSnapshot::getDate, BalanceSnapshot::getBalance));
        // Only today's anchor and the transaction date are rebuilt; the statements stand.
        assertThat(byDate.keySet()).containsExactlyInAnyOrder(today, march);
        assertThat(byDate.get(today)).isEqualByComparingTo("1250");
        assertThat(byDate.get(march)).isEqualByComparingTo("1250");
    }

    @Test
    void reconstructSnapshotsFromDb_noDerivedRowBeforeTheOldestManualStatement() {
        LocalDate today = LocalDate.now();
        LocalDate statement = today.minusDays(40);
        LocalDate beforeStatement = today.minusDays(60);
        LocalDate afterStatement = today.minusDays(3);
        Account account = Account.builder().id(1L).name("Livret").currency("EUR")
            .currentBalance(new BigDecimal("1250")).build();
        when(transactionRepository.findByAccountIdOrderByDateDesc(1L)).thenReturn(List.of(
            Transaction.builder().account(account).date(afterStatement).amount(new BigDecimal("50")).build(),
            Transaction.builder().account(account).date(beforeStatement).amount(new BigDecimal("200")).build()));
        when(balanceSnapshotRepository.findRecentByAccountId(1L, LocalDate.of(2000, 1, 1)))
            .thenReturn(List.of(BalanceSnapshot.builder()
                .account(account).date(statement).balance(new BigDecimal("1200"))
                .investedAmount(new BigDecimal("1200")).source(SnapshotSource.MANUAL).build()));

        helper.reconstructSnapshotsFromDb(account);

        ArgumentCaptor<BalanceSnapshot> saved = ArgumentCaptor.forClass(BalanceSnapshot.class);
        verify(balanceSnapshotRepository, atLeastOnce()).save(saved.capture());
        // History before the first statement is unknown: an honest gap, not an invented point.
        assertThat(saved.getAllValues()).extracting(BalanceSnapshot::getDate)
            .contains(today, afterStatement)
            .doesNotContain(statement, beforeStatement);
    }

    @Test
    void reconstructSnapshotsFromDb_manualTodayWinsOverTheAnchor() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        Account account = Account.builder().id(1L).name("Livret").currency("EUR")
            .currentBalance(new BigDecimal("1250")).build();
        when(transactionRepository.findByAccountIdOrderByDateDesc(1L)).thenReturn(List.of(
            Transaction.builder().account(account).date(yesterday).amount(new BigDecimal("50")).build()));
        when(balanceSnapshotRepository.findRecentByAccountId(1L, LocalDate.of(2000, 1, 1)))
            .thenReturn(List.of(BalanceSnapshot.builder()
                .account(account).date(today).balance(new BigDecimal("1300"))
                .investedAmount(new BigDecimal("1300")).source(SnapshotSource.MANUAL).build()));

        helper.reconstructSnapshotsFromDb(account);

        // The only manual row is today: no anchor, and every transaction date falls before
        // the oldest statement, so nothing is written.
        verify(balanceSnapshotRepository, never()).save(any(BalanceSnapshot.class));
    }

    @Test
    void reconstructSnapshotsFromDb_noTransactionLeft_dropsStaleDerivedRowsAndReanchorsToday() {
        LocalDate today = LocalDate.now();
        LocalDate lastWeek = today.minusDays(7);
        Account account = Account.builder().id(1L).name("Livret").currency("EUR")
            .currentBalance(new BigDecimal("1200")).build();
        when(transactionRepository.findByAccountIdOrderByDateDesc(1L)).thenReturn(List.of());
        BalanceSnapshot stale = BalanceSnapshot.builder()
            .account(account).date(lastWeek).balance(new BigDecimal("999"))
            .investedAmount(new BigDecimal("999")).build();
        BalanceSnapshot statement = BalanceSnapshot.builder()
            .account(account).date(today.minusDays(10)).balance(new BigDecimal("1200"))
            .investedAmount(new BigDecimal("1200")).source(SnapshotSource.MANUAL).build();
        when(balanceSnapshotRepository.findRecentByAccountId(1L, LocalDate.of(2000, 1, 1)))
            .thenReturn(List.of(stale, statement));

        helper.reconstructSnapshotsFromDb(account);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BalanceSnapshot>> deleted = ArgumentCaptor.forClass(List.class);
        verify(balanceSnapshotRepository).deleteAllInBatch(deleted.capture());
        assertThat(deleted.getValue()).containsExactly(stale);
        ArgumentCaptor<BalanceSnapshot> saved = ArgumentCaptor.forClass(BalanceSnapshot.class);
        verify(balanceSnapshotRepository).save(saved.capture());
        assertThat(saved.getValue().getDate()).isEqualTo(today);
        assertThat(saved.getValue().getBalance()).isEqualByComparingTo("1200");
    }

    @Test
    void reconstructSnapshots_preservesHandEnteredStatements() {
        LocalDate today = LocalDate.now();
        LocalDate february = today.minusDays(10);
        LocalDate march = today.minusDays(3);
        Account account = Account.builder().id(1L).name("Livret").currency("EUR")
            .currentBalance(new BigDecimal("1250")).build();
        FinaryPersistenceHelper.ParsedFinaryAccount finaryAcc =
            new FinaryPersistenceHelper.ParsedFinaryAccount("Livret", "Finary",
                "Savings", new BigDecimal("1250"), "EUR");
        FinaryPersistenceHelper.ParsedFinaryTransaction marchTx =
            new FinaryPersistenceHelper.ParsedFinaryTransaction("Livret", march, "Virement",
                new BigDecimal("50"), "credit", "income", "EUR");
        BalanceSnapshot statement = BalanceSnapshot.builder()
            .account(account).date(february).balance(new BigDecimal("1200"))
            .investedAmount(new BigDecimal("1200")).source(SnapshotSource.MANUAL).build();
        when(balanceSnapshotRepository.findRecentByAccountId(1L, LocalDate.of(2000, 1, 1)))
            .thenReturn(List.of(statement));

        helper.reconstructSnapshots(account, finaryAcc, List.of(marchTx));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BalanceSnapshot>> deleted = ArgumentCaptor.forClass(List.class);
        verify(balanceSnapshotRepository).deleteAllInBatch(deleted.capture());
        assertThat(deleted.getValue()).doesNotContain(statement);
        ArgumentCaptor<BalanceSnapshot> saved = ArgumentCaptor.forClass(BalanceSnapshot.class);
        verify(balanceSnapshotRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(BalanceSnapshot::getDate)
            .contains(today, march)
            .doesNotContain(february);
    }

    @Test
    void reconstructSnapshotsFromDb_futureStatement_keepsPastDerivedHistory() {
        LocalDate today = LocalDate.now();
        LocalDate lastWeek = today.minusDays(7);
        LocalDate newYear = today.plusDays(60);
        Account account = Account.builder().id(1L).name("Livret").currency("EUR")
            .currentBalance(new BigDecimal("1250")).build();
        when(transactionRepository.findByAccountIdOrderByDateDesc(1L)).thenReturn(List.of(
            Transaction.builder().account(account).date(lastWeek).amount(new BigDecimal("50")).build()));
        BalanceSnapshot future = BalanceSnapshot.builder()
            .account(account).date(newYear).balance(new BigDecimal("2000"))
            .investedAmount(new BigDecimal("2000")).source(SnapshotSource.MANUAL).build();
        when(balanceSnapshotRepository.findRecentByAccountId(1L, LocalDate.of(2000, 1, 1)))
            .thenReturn(List.of(future));

        helper.reconstructSnapshotsFromDb(account);

        // The future statement is kept but must not wipe the reached past: the cutoff only
        // looks at statements on or before today, and there are none, so the walk rebuilds.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BalanceSnapshot>> deleted = ArgumentCaptor.forClass(List.class);
        verify(balanceSnapshotRepository).deleteAllInBatch(deleted.capture());
        assertThat(deleted.getValue()).doesNotContain(future);
        ArgumentCaptor<BalanceSnapshot> saved = ArgumentCaptor.forClass(BalanceSnapshot.class);
        verify(balanceSnapshotRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(BalanceSnapshot::getDate)
            .contains(today, lastWeek)
            .doesNotContain(newYear);
    }

    @Test
    void computeManualLedgerBalance_withoutStatement_sumsEveryTransaction() {
        Account account = Account.builder().id(1L).name("Livret").currency("EUR")
            .currentBalance(BigDecimal.ZERO).build();
        when(balanceSnapshotRepository
            .findFirstByAccountIdAndSourceAndDateLessThanEqualOrderByDateDesc(
                1L, SnapshotSource.MANUAL, LocalDate.now()))
            .thenReturn(Optional.empty());
        when(transactionRepository.sumAmountByAccountId(1L)).thenReturn(new BigDecimal("1000"));

        assertThat(helper.computeManualLedgerBalance(account)).isEqualByComparingTo("1000");
    }

    @Test
    void computeManualLedgerBalance_anchoredOnTheLatestStatement() {
        LocalDate today = LocalDate.now();
        LocalDate statement = today.minusDays(10);
        Account account = Account.builder().id(1L).name("Livret").currency("EUR")
            .currentBalance(BigDecimal.ZERO).build();
        when(balanceSnapshotRepository
            .findFirstByAccountIdAndSourceAndDateLessThanEqualOrderByDateDesc(
                1L, SnapshotSource.MANUAL, today))
            .thenReturn(Optional.of(BalanceSnapshot.builder()
                .account(account).date(statement).balance(new BigDecimal("1200"))
                .investedAmount(new BigDecimal("1200")).source(SnapshotSource.MANUAL).build()));
        when(transactionRepository.sumAmountByAccountIdAfterStatement(eq(1L), eq(statement), any(Instant.class)))
            .thenReturn(new BigDecimal("50"));

        assertThat(helper.computeManualLedgerBalance(account)).isEqualByComparingTo("1250");
    }

    @Test
    void computeManualLedgerBalance_sameDayTransactionCreatedAfterTheStatement_isIncluded() {
        LocalDate today = LocalDate.now();
        Instant morning = Instant.now().minusSeconds(3600);
        // Typed 600 this morning, spent 50 this afternoon: the statement cannot contain a
        // movement that did not exist yet.
        Account account = Account.builder().id(1L).name("Livret").currency("EUR")
            .currentBalance(BigDecimal.ZERO).build();
        when(balanceSnapshotRepository
            .findFirstByAccountIdAndSourceAndDateLessThanEqualOrderByDateDesc(
                1L, SnapshotSource.MANUAL, today))
            .thenReturn(Optional.of(BalanceSnapshot.builder()
                .account(account).date(today).balance(new BigDecimal("600"))
                .investedAmount(new BigDecimal("600")).source(SnapshotSource.MANUAL)
                .nativeBalance(new BigDecimal("600")).createdAt(morning).build()));
        when(transactionRepository.sumAmountByAccountIdAfterStatement(eq(1L), eq(today), eq(morning)))
            .thenReturn(new BigDecimal("-50"));

        assertThat(helper.computeManualLedgerBalance(account)).isEqualByComparingTo("550");
        verify(transactionRepository).sumAmountByAccountIdAfterStatement(eq(1L), eq(today), eq(morning));
    }

    @Test
    void computeManualLedgerBalance_sameDayTransactionCreatedBeforeTheStatement_isExcluded() {
        LocalDate today = LocalDate.now();
        Instant evening = Instant.now();
        Instant morning = evening.minusSeconds(3600);
        // The -50 was already in when the 600 end-of-day statement was typed: counting it
        // again would double it. The query itself enforces the createdAt ordering; here the
        // wiring (statement instant forwarded) is pinned.
        Account account = Account.builder().id(1L).name("Livret").currency("EUR")
            .currentBalance(BigDecimal.ZERO).build();
        when(balanceSnapshotRepository
            .findFirstByAccountIdAndSourceAndDateLessThanEqualOrderByDateDesc(
                1L, SnapshotSource.MANUAL, today))
            .thenReturn(Optional.of(BalanceSnapshot.builder()
                .account(account).date(today).balance(new BigDecimal("600"))
                .investedAmount(new BigDecimal("600")).source(SnapshotSource.MANUAL)
                .nativeBalance(new BigDecimal("600")).createdAt(evening).build()));
        when(transactionRepository.sumAmountByAccountIdAfterStatement(eq(1L), eq(today), eq(evening)))
            .thenReturn(BigDecimal.ZERO);

        assertThat(helper.computeManualLedgerBalance(account)).isEqualByComparingTo("600");
        verify(transactionRepository).sumAmountByAccountIdAfterStatement(eq(1L), eq(today), eq(evening));
    }

    @Test
    void computeManualLedgerBalance_recycledRow_ordersByEntryInstantNotCreation() {
        LocalDate today = LocalDate.now();
        Instant created = Instant.now().minusSeconds(3600 * 9);
        Instant entered = Instant.now();
        // A DERIVED today row from the morning rebuild, flipped to a statement in the
        // evening: same-day movements order against the flip, not the row creation.
        Account account = Account.builder().id(1L).name("Livret").currency("EUR")
            .currentBalance(BigDecimal.ZERO).build();
        when(balanceSnapshotRepository
            .findFirstByAccountIdAndSourceAndDateLessThanEqualOrderByDateDesc(
                1L, SnapshotSource.MANUAL, today))
            .thenReturn(Optional.of(BalanceSnapshot.builder()
                .account(account).date(today).balance(new BigDecimal("570"))
                .investedAmount(new BigDecimal("570")).source(SnapshotSource.MANUAL)
                .nativeBalance(new BigDecimal("570")).createdAt(created).statementEnteredAt(entered)
                .build()));
        when(transactionRepository.sumAmountByAccountIdAfterStatement(eq(1L), eq(today), eq(entered)))
            .thenReturn(BigDecimal.ZERO);

        assertThat(helper.computeManualLedgerBalance(account)).isEqualByComparingTo("570");
        verify(transactionRepository).sumAmountByAccountIdAfterStatement(eq(1L), eq(today), eq(entered));
    }

    @Test
    void computeManualLedgerBalance_foreignCurrency_usesStoredNativeIgnoringLaterFxMoves() {
        LocalDate today = LocalDate.now();
        LocalDate statement = today.minusDays(10);
        // The statement said 1000 USD; whatever the rate does afterwards, the anchor stays
        // 1000 USD — no priceService stub: touching a rate here fails the test.
        Account account = Account.builder().id(1L).name("USD account").currency("USD")
            .currentBalance(BigDecimal.ZERO).build();
        when(balanceSnapshotRepository
            .findFirstByAccountIdAndSourceAndDateLessThanEqualOrderByDateDesc(
                1L, SnapshotSource.MANUAL, today))
            .thenReturn(Optional.of(BalanceSnapshot.builder()
                .account(account).date(statement).balance(new BigDecimal("910"))
                .investedAmount(new BigDecimal("910")).source(SnapshotSource.MANUAL)
                .nativeBalance(new BigDecimal("1000")).build()));
        when(transactionRepository.sumAmountByAccountIdAfterStatement(eq(1L), eq(statement), any(Instant.class)))
            .thenReturn(new BigDecimal("50"));

        assertThat(helper.computeManualLedgerBalance(account)).isEqualByComparingTo("1050");
    }

    @Test
    void computeManualLedgerBalance_legacyRowWithoutNative_convertsTheAnchorBack() {
        LocalDate today = LocalDate.now();
        LocalDate statement = today.minusDays(10);
        // 1000 EUR at 1.1 is about 909.09 USD, plus nothing booked since.
        Account account = Account.builder().id(1L).name("USD account").currency("USD")
            .currentBalance(BigDecimal.ZERO).build();
        when(balanceSnapshotRepository
            .findFirstByAccountIdAndSourceAndDateLessThanEqualOrderByDateDesc(
                1L, SnapshotSource.MANUAL, today))
            .thenReturn(Optional.of(BalanceSnapshot.builder()
                .account(account).date(statement).balance(new BigDecimal("1000"))
                .investedAmount(new BigDecimal("1000")).source(SnapshotSource.MANUAL).build()));
        when(priceService.getFxRateEur("USD")).thenReturn(new BigDecimal("1.1"));
        when(transactionRepository.sumAmountByAccountIdAfterStatement(eq(1L), eq(statement), any(Instant.class)))
            .thenReturn(BigDecimal.ZERO);

        assertThat(helper.computeManualLedgerBalance(account)).isEqualByComparingTo("909.09090909");
    }

    @Test
    void computeManualLedgerBalance_missingRate_throwsInsteadOfEngraving() {
        LocalDate today = LocalDate.now();
        LocalDate statement = today.minusDays(10);
        Account account = Account.builder().id(1L).name("USD account").currency("USD")
            .currentBalance(BigDecimal.ZERO).build();
        when(balanceSnapshotRepository
            .findFirstByAccountIdAndSourceAndDateLessThanEqualOrderByDateDesc(
                1L, SnapshotSource.MANUAL, today))
            .thenReturn(Optional.of(BalanceSnapshot.builder()
                .account(account).date(statement).balance(new BigDecimal("1000"))
                .investedAmount(new BigDecimal("1000")).source(SnapshotSource.MANUAL).build()));
        when(priceService.getFxRateEur("USD")).thenReturn(null);

        assertThatThrownBy(() -> helper.computeManualLedgerBalance(account))
            .isInstanceOf(SyncException.class);
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
