package com.picsou.finary;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.YahooFinancePriceProvider;
import com.picsou.adapter.YahooFinanceStubs;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.Transaction;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.PriceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
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
            transactionRepository, new PriceService(mock(CoinGeckoPriceProvider.class), yahoo,
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
}
