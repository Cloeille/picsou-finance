package com.picsou.service;

import com.picsou.dto.RealizedPnlResponse;
import com.picsou.model.Account;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealizedPnlServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @InjectMocks RealizedPnlService service;

    private Account account(String currency) {
        return Account.builder().id(1L).name("PEA").currency(currency).build();
    }

    private Transaction buy(String ticker, String qty, String price, String fees) {
        return Transaction.builder()
            .account(account("EUR"))
            .date(LocalDate.of(2024, 1, 1))
            .txType(TransactionType.BUY)
            .ticker(ticker).name(ticker)
            .quantity(new BigDecimal(qty))
            .pricePerUnit(new BigDecimal(price))
            .fees(fees == null ? null : new BigDecimal(fees))
            .amount(BigDecimal.ZERO)
            .build();
    }

    private Transaction sell(String ticker, String qty, String price, String fees) {
        return Transaction.builder()
            .account(account("EUR"))
            .date(LocalDate.of(2024, 6, 1))
            .txType(TransactionType.SELL)
            .ticker(ticker).name(ticker)
            .quantity(new BigDecimal(qty))
            .pricePerUnit(new BigDecimal(price))
            .fees(fees == null ? null : new BigDecimal(fees))
            .amount(BigDecimal.ZERO)
            .build();
    }

    private RealizedPnlResponse compute(Account account, Transaction... txs) {
        when(accountRepository.findByIdAndMemberId(1L, 10L)).thenReturn(Optional.of(account));
        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(eq(1L), anyList()))
            .thenReturn(List.of(txs));
        return service.compute(1L, 10L);
    }

    @Test
    void buyThenPartialSell_realizesGainAgainstAverageCost() {
        // BUY 10 @ 100 → avg 100. SELL 4 @ 150 → proceeds 600, cost 400, realized 200.
        RealizedPnlResponse r = compute(account("EUR"), buy("AAPL", "10", "100", null), sell("AAPL", "4", "150", null));

        assertThat(r.realizedTotal()).isEqualByComparingTo("200");
        assertThat(r.lots()).hasSize(1);
        assertThat(r.byTicker()).hasSize(1);
        assertThat(r.byTicker().get(0).ticker()).isEqualTo("AAPL");
        assertThat(r.byTicker().get(0).quantitySold()).isEqualByComparingTo("4");
        assertThat(r.byTicker().get(0).realized()).isEqualByComparingTo("200");
        assertThat(r.byTicker().get(0).warning()).isFalse();
    }

    @Test
    void multipleBuys_averageCostThenFullClose() {
        // BUY 10 @ 100 + BUY 10 @ 200 → avg 150. SELL 20 @ 180 → proceeds 3600, cost 3000, realized 600.
        RealizedPnlResponse r = compute(account("EUR"),
            buy("ETF", "10", "100", null), buy("ETF", "10", "200", null), sell("ETF", "20", "180", null));

        assertThat(r.realizedTotal()).isEqualByComparingTo("600");
    }

    @Test
    void buyFeesRaiseCost_sellFeesReduceProceeds() {
        // BUY 10 @ 100 fees 5 → avg 100.5. SELL 10 @ 120 fees 5 → proceeds 1195, cost 1005, realized 190.
        RealizedPnlResponse r = compute(account("EUR"),
            buy("VWCE", "10", "100", "5"), sell("VWCE", "10", "120", "5"));

        assertThat(r.realizedTotal()).isEqualByComparingTo("190");
        assertThat(r.lots().get(0).avgCost()).isEqualByComparingTo("100.5");
        assertThat(r.lots().get(0).proceeds()).isEqualByComparingTo("1195");
    }

    @Test
    void overSell_clampsCostAndFlagsWarning() {
        // BUY 5 @ 100 → avg 100. SELL 8 @ 120 → proceeds 960, cost only for 5 units = 500, realized 460.
        RealizedPnlResponse r = compute(account("EUR"), buy("BTC", "5", "100", null), sell("BTC", "8", "120", null));

        assertThat(r.realizedTotal()).isEqualByComparingTo("460");
        assertThat(r.byTicker().get(0).warning()).isTrue();
    }

    @Test
    void sellWithNoPriorBuy_realizesWholeProceedsWithWarning() {
        RealizedPnlResponse r = compute(account("EUR"), sell("SOL", "3", "50", null));

        assertThat(r.realizedTotal()).isEqualByComparingTo("150");
        assertThat(r.byTicker().get(0).warning()).isTrue();
    }

    @Test
    void onlyBuys_noRealizedPnl() {
        RealizedPnlResponse r = compute(account("EUR"), buy("AAPL", "10", "100", null));

        assertThat(r.realizedTotal()).isEqualByComparingTo("0");
        assertThat(r.byTicker()).isEmpty();
        assertThat(r.lots()).isEmpty();
    }

    @Test
    void responseUsesAccountCurrency() {
        RealizedPnlResponse r = compute(account("USD"), buy("AAPL", "1", "100", null), sell("AAPL", "1", "110", null));

        assertThat(r.currency()).isEqualTo("USD");
        assertThat(r.realizedTotal()).isEqualByComparingTo("10");
    }

    /**
     * Pins the behavioral contract that the repository's {@code (date, id)} ordering exists to
     * protect. A same-day BUY-then-SELL stream, returned by the (mocked) repository in insertion
     * order: BUY 10 @ 100 -> avg cost 100; SELL 10 @ 110 fees 0 -> proceeds 1100, cost 1000,
     * realized 100. No warning, because quantity sold (10) never exceeds quantity held (10).
     */
    @Test
    void sameDayBuyThenSell_inInsertionOrder_realizesGainNoWarning() {
        LocalDate sameDay = LocalDate.of(2026, 7, 1);
        Transaction buy = Transaction.builder()
            .account(account("EUR"))
            .date(sameDay)
            .txType(TransactionType.BUY)
            .ticker("AAPL").name("AAPL")
            .quantity(new BigDecimal("10"))
            .pricePerUnit(new BigDecimal("100"))
            .amount(BigDecimal.ZERO)
            .build();
        Transaction sell = Transaction.builder()
            .account(account("EUR"))
            .date(sameDay)
            .txType(TransactionType.SELL)
            .ticker("AAPL").name("AAPL")
            .quantity(new BigDecimal("10"))
            .pricePerUnit(new BigDecimal("110"))
            .fees(BigDecimal.ZERO)
            .amount(BigDecimal.ZERO)
            .build();

        RealizedPnlResponse r = compute(account("EUR"), buy, sell);

        assertThat(r.realizedTotal()).isEqualByComparingTo("100");
        assertThat(r.byTicker().get(0).warning()).isFalse();
    }

    /**
     * Same two same-day transactions, repository order reversed (SELL before BUY) -- exactly what
     * an undefined date-only DB order could hand back. RealizedPnlService never reorders same-day
     * rows itself (that is the repository's job), so the SELL is walked against a still-empty
     * pool: held=0 -> avgCost=0, costed quantity clamped to 0, realized = full proceeds (1100),
     * and the over-sell warning fires. This is the silent-wrong-money failure mode that the
     * repository's (date, id) ordering guarantee must prevent from happening on real data.
     */
    @Test
    void sameDaySellThenBuy_reversedOrder_documentsOverSellWarningContract() {
        LocalDate sameDay = LocalDate.of(2026, 7, 1);
        Transaction buy = Transaction.builder()
            .account(account("EUR"))
            .date(sameDay)
            .txType(TransactionType.BUY)
            .ticker("AAPL").name("AAPL")
            .quantity(new BigDecimal("10"))
            .pricePerUnit(new BigDecimal("100"))
            .amount(BigDecimal.ZERO)
            .build();
        Transaction sell = Transaction.builder()
            .account(account("EUR"))
            .date(sameDay)
            .txType(TransactionType.SELL)
            .ticker("AAPL").name("AAPL")
            .quantity(new BigDecimal("10"))
            .pricePerUnit(new BigDecimal("110"))
            .fees(BigDecimal.ZERO)
            .amount(BigDecimal.ZERO)
            .build();

        RealizedPnlResponse r = compute(account("EUR"), sell, buy);

        assertThat(r.realizedTotal()).isEqualByComparingTo("1100");
        assertThat(r.byTicker().get(0).warning()).isTrue();
    }
}
