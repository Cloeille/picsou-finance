package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HoldingComputeServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock AccountHoldingRepository accountHoldingRepository;
    @InjectMocks HoldingComputeService holdingComputeService;

    private Account account(long id) {
        return Account.builder().id(id).name("Test").currency("EUR").build();
    }

    private Transaction buyTx(String ticker, String qty, String price) {
        return Transaction.builder()
                .account(account(1L))
                .date(LocalDate.of(2024, 1, 1))
                .description("BUY " + ticker)
                .amount(BigDecimal.ZERO)
                .txType(TransactionType.BUY)
                .ticker(ticker)
                .quantity(new BigDecimal(qty))
                .pricePerUnit(price != null ? new BigDecimal(price) : null)
                .build();
    }

    private Transaction sellTx(String ticker, String qty) {
        return Transaction.builder()
                .account(account(1L))
                .date(LocalDate.of(2024, 6, 1))
                .description("SELL " + ticker)
                .amount(BigDecimal.ZERO)
                .txType(TransactionType.SELL)
                .ticker(ticker)
                .quantity(new BigDecimal(qty))
                .build();
    }

    private Transaction buyTxWithFees(String ticker, String qty, String price, String fees) {
        return Transaction.builder()
                .account(account(1L))
                .date(LocalDate.of(2024, 1, 1))
                .description("BUY " + ticker)
                .amount(BigDecimal.ZERO)
                .txType(TransactionType.BUY)
                .ticker(ticker)
                .quantity(new BigDecimal(qty))
                .pricePerUnit(price != null ? new BigDecimal(price) : null)
                .fees(fees != null ? new BigDecimal(fees) : null)
                .build();
    }

    private Transaction buyTxWithName(String ticker, String qty, String price, String name, LocalDate date) {
        return Transaction.builder()
                .account(account(1L))
                .date(date)
                .description("BUY " + ticker)
                .name(name)
                .amount(BigDecimal.ZERO)
                .txType(TransactionType.BUY)
                .ticker(ticker)
                .quantity(new BigDecimal(qty))
                .pricePerUnit(price != null ? new BigDecimal(price) : null)
                .build();
    }

    @Test
    void buyOnly_setsQuantityAndAverageBuyIn() {
        Account account = account(1L);
        Transaction buy = buyTx("AAPL", "10", "150.00");

        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(eq(1L), anyList()))
                .thenReturn(List.of(buy));
        when(accountHoldingRepository.findByAccount_Id(1L))
                .thenReturn(List.of());

        holdingComputeService.recomputeHoldings(account);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountHolding>> captor = ArgumentCaptor.forClass(List.class);
        verify(accountHoldingRepository).saveAll(captor.capture());

        List<AccountHolding> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        AccountHolding h = saved.get(0);
        assertThat(h.getTicker()).isEqualTo("AAPL");
        assertThat(h.getQuantity()).isEqualByComparingTo("10");
        assertThat(h.getAverageBuyIn()).isEqualByComparingTo("150.00000000");
    }

    @Test
    void multipleBuys_computesVwap() {
        Account account = account(1L);
        // Buy 10 @ 100 and 20 @ 200 → VWAP = (10*100 + 20*200) / (10+20) = 5000/30 = 166.66666667
        Transaction buy1 = buyTx("ETH", "10", "100.00");
        Transaction buy2 = buyTx("ETH", "20", "200.00");

        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(eq(1L), anyList()))
                .thenReturn(List.of(buy1, buy2));
        when(accountHoldingRepository.findByAccount_Id(1L))
                .thenReturn(List.of());

        holdingComputeService.recomputeHoldings(account);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountHolding>> captor = ArgumentCaptor.forClass(List.class);
        verify(accountHoldingRepository).saveAll(captor.capture());

        AccountHolding h = captor.getValue().get(0);
        assertThat(h.getQuantity()).isEqualByComparingTo("30");
        // VWAP = 5000 / 30 = 166.66666667
        assertThat(h.getAverageBuyIn()).isEqualByComparingTo("166.66666667");
    }

    @Test
    void buyWithFees_foldsFeesIntoAverageBuyIn() {
        Account account = account(1L);
        // Buy 10 @ 100 with 5 fees → averageBuyIn = (10*100 + 5) / 10 = 100.5 (French PEA PMP convention)
        Transaction buy = buyTxWithFees("AAPL", "10", "100.00", "5.00");

        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(eq(1L), anyList()))
                .thenReturn(List.of(buy));
        when(accountHoldingRepository.findByAccount_Id(1L))
                .thenReturn(List.of());

        holdingComputeService.recomputeHoldings(account);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountHolding>> captor = ArgumentCaptor.forClass(List.class);
        verify(accountHoldingRepository).saveAll(captor.capture());

        AccountHolding h = captor.getValue().get(0);
        assertThat(h.getQuantity()).isEqualByComparingTo("10");
        assertThat(h.getAverageBuyIn()).isEqualByComparingTo("100.50000000");
    }

    @Test
    void multipleBuysWithFees_foldsAllFeesIntoVwap() {
        Account account = account(1L);
        // (10*100 + 5) + (10*200 + 5) = 1005 + 2005 = 3010 over 20 → 150.5
        Transaction buy1 = buyTxWithFees("ETH", "10", "100.00", "5.00");
        Transaction buy2 = buyTxWithFees("ETH", "10", "200.00", "5.00");

        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(eq(1L), anyList()))
                .thenReturn(List.of(buy1, buy2));
        when(accountHoldingRepository.findByAccount_Id(1L))
                .thenReturn(List.of());

        holdingComputeService.recomputeHoldings(account);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountHolding>> captor = ArgumentCaptor.forClass(List.class);
        verify(accountHoldingRepository).saveAll(captor.capture());

        AccountHolding h = captor.getValue().get(0);
        assertThat(h.getQuantity()).isEqualByComparingTo("20");
        assertThat(h.getAverageBuyIn()).isEqualByComparingTo("150.50000000");
    }

    @Test
    void nullFees_treatedAsZeroInVwap() {
        Account account = account(1L);
        // No fees recorded → averageBuyIn = plain VWAP = 100
        Transaction buy = buyTxWithFees("BTC", "10", "100.00", null);

        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(eq(1L), anyList()))
                .thenReturn(List.of(buy));
        when(accountHoldingRepository.findByAccount_Id(1L))
                .thenReturn(List.of());

        holdingComputeService.recomputeHoldings(account);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountHolding>> captor = ArgumentCaptor.forClass(List.class);
        verify(accountHoldingRepository).saveAll(captor.capture());

        AccountHolding h = captor.getValue().get(0);
        assertThat(h.getAverageBuyIn()).isEqualByComparingTo("100.00000000");
    }

    @Test
    void buyThenSell_reducesQuantity() {
        Account account = account(1L);
        Transaction buy = buyTx("BTC", "5", "30000.00");
        Transaction sell = sellTx("BTC", "2");

        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(eq(1L), anyList()))
                .thenReturn(List.of(buy, sell));
        when(accountHoldingRepository.findByAccount_Id(1L))
                .thenReturn(List.of());

        holdingComputeService.recomputeHoldings(account);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountHolding>> captor = ArgumentCaptor.forClass(List.class);
        verify(accountHoldingRepository).saveAll(captor.capture());

        AccountHolding h = captor.getValue().get(0);
        assertThat(h.getTicker()).isEqualTo("BTC");
        assertThat(h.getQuantity()).isEqualByComparingTo("3");
        // averageBuyIn is based on BUY transactions only (5 @ 30000)
        assertThat(h.getAverageBuyIn()).isEqualByComparingTo("30000.00000000");
    }

    @Test
    void fullySold_deletesHolding() {
        Account account = account(1L);
        Transaction buy = buyTx("SOL", "10", "50.00");
        Transaction sell = sellTx("SOL", "10");

        AccountHolding existing = AccountHolding.builder()
                .id(99L)
                .account(account)
                .ticker("SOL")
                .quantity(new BigDecimal("10"))
                .build();

        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(eq(1L), anyList()))
                .thenReturn(List.of(buy, sell));
        when(accountHoldingRepository.findByAccount_Id(1L))
                .thenReturn(List.of(existing));

        holdingComputeService.recomputeHoldings(account);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountHolding>> deleteCaptor = ArgumentCaptor.forClass(List.class);
        verify(accountHoldingRepository).deleteAll(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue()).containsExactly(existing);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountHolding>> saveCaptor = ArgumentCaptor.forClass(List.class);
        verify(accountHoldingRepository).saveAll(saveCaptor.capture());
        assertThat(saveCaptor.getValue()).isEmpty();
    }

    @Test
    void nullTicker_skipped() {
        Account account = account(1L);
        Transaction buy = Transaction.builder()
                .account(account)
                .date(LocalDate.of(2024, 1, 1))
                .description("BUY unknown")
                .amount(BigDecimal.ZERO)
                .txType(TransactionType.BUY)
                .ticker(null)   // null ticker — must be skipped
                .quantity(new BigDecimal("5"))
                .pricePerUnit(new BigDecimal("100"))
                .build();

        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(eq(1L), anyList()))
                .thenReturn(List.of(buy));
        when(accountHoldingRepository.findByAccount_Id(1L))
                .thenReturn(List.of());

        holdingComputeService.recomputeHoldings(account);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountHolding>> saveCaptor = ArgumentCaptor.forClass(List.class);
        verify(accountHoldingRepository).saveAll(saveCaptor.capture());
        assertThat(saveCaptor.getValue()).isEmpty();

        verify(accountHoldingRepository, never()).findByAccountIdAndTicker(anyLong(), anyString());
    }

    @Test
    void multipleTickers_handledIndependently() {
        Account account = account(1L);
        Transaction buyAapl = buyTx("AAPL", "10", "150.00");
        Transaction buyMsft = buyTx("MSFT", "5", "300.00");

        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(eq(1L), anyList()))
                .thenReturn(List.of(buyAapl, buyMsft));
        when(accountHoldingRepository.findByAccount_Id(1L))
                .thenReturn(List.of());

        holdingComputeService.recomputeHoldings(account);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountHolding>> captor = ArgumentCaptor.forClass(List.class);
        verify(accountHoldingRepository).saveAll(captor.capture());

        List<AccountHolding> saved = captor.getValue();
        assertThat(saved).hasSize(2);

        AccountHolding aapl = saved.stream().filter(h -> "AAPL".equals(h.getTicker())).findFirst().orElseThrow();
        AccountHolding msft = saved.stream().filter(h -> "MSFT".equals(h.getTicker())).findFirst().orElseThrow();

        assertThat(aapl.getQuantity()).isEqualByComparingTo("10");
        assertThat(aapl.getAverageBuyIn()).isEqualByComparingTo("150.00000000");

        assertThat(msft.getQuantity()).isEqualByComparingTo("5");
        assertThat(msft.getAverageBuyIn()).isEqualByComparingTo("300.00000000");
    }

    @Test
    void nullQuantity_skipped() {
        Account account = Account.builder().id(1L).name("Test").type(AccountType.CRYPTO).currency("EUR").color("#fff").build();
        Transaction tx = Transaction.builder()
            .account(account)
            .date(LocalDate.of(2024, 1, 1))
            .description("BUY")
            .amount(BigDecimal.ZERO)
            .txType(TransactionType.BUY)
            .ticker("BTC")
            .quantity(null)  // null quantity — should be skipped
            .pricePerUnit(new BigDecimal("50000"))
            .build();

        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(eq(1L), anyList()))
            .thenReturn(List.of(tx));
        when(accountHoldingRepository.findByAccount_Id(1L))
            .thenReturn(List.of());

        holdingComputeService.recomputeHoldings(account);

        verify(accountHoldingRepository, never()).findByAccountIdAndTicker(any(), any());
        verify(accountHoldingRepository).saveAll(List.of());
    }

    @Test
    void nullPricePerUnit_treatedAsZeroForVwap() {
        Account account = account(1L);
        // Buy 10 with no price — should use 0 for VWAP computation
        Transaction buy = buyTx("XRP", "10", null);

        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(eq(1L), anyList()))
                .thenReturn(List.of(buy));
        when(accountHoldingRepository.findByAccount_Id(1L))
                .thenReturn(List.of());

        holdingComputeService.recomputeHoldings(account);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountHolding>> captor = ArgumentCaptor.forClass(List.class);
        verify(accountHoldingRepository).saveAll(captor.capture());

        AccountHolding h = captor.getValue().get(0);
        assertThat(h.getQuantity()).isEqualByComparingTo("10");
        // VWAP = (10 * 0) / 10 = 0
        assertThat(h.getAverageBuyIn()).isEqualByComparingTo("0.00000000");
    }

    @Test
    void existingHolding_updatedInPlace() {
        Account account = account(1L);
        Transaction buy = buyTx("NVDA", "3", "400.00");

        AccountHolding existing = AccountHolding.builder()
                .id(42L)
                .account(account)
                .ticker("NVDA")
                .quantity(new BigDecimal("1"))
                .averageBuyIn(new BigDecimal("300.00"))
                .build();

        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(eq(1L), anyList()))
                .thenReturn(List.of(buy));
        when(accountHoldingRepository.findByAccount_Id(1L))
                .thenReturn(List.of(existing));

        holdingComputeService.recomputeHoldings(account);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountHolding>> captor = ArgumentCaptor.forClass(List.class);
        verify(accountHoldingRepository).saveAll(captor.capture());

        List<AccountHolding> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        // Must be the same object (updated, not a new one)
        assertThat(saved.get(0).getId()).isEqualTo(42L);
        assertThat(saved.get(0).getQuantity()).isEqualByComparingTo("3");
        assertThat(saved.get(0).getAverageBuyIn()).isEqualByComparingTo("400.00000000");
    }

    @Test
    void positionName_usesNameFromNewestTransaction() {
        Account account = account(1L);
        // Two BUYs for the same security, returned in date-ASC order (oldest first).
        // The older transaction carries a stale label; the newer one the canonical name.
        Transaction older = buyTxWithName("IWDA.AS", "10", "80.00",
                "iShares MSCI World (old label)", LocalDate.of(2024, 1, 1));
        Transaction newer = buyTxWithName("IWDA.AS", "5", "90.00",
                "iShares Core MSCI World UCITS ETF", LocalDate.of(2024, 3, 1));

        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(eq(1L), anyList()))
                .thenReturn(List.of(older, newer));
        when(accountHoldingRepository.findByAccount_Id(1L))
                .thenReturn(List.of());

        holdingComputeService.recomputeHoldings(account);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountHolding>> captor = ArgumentCaptor.forClass(List.class);
        verify(accountHoldingRepository).saveAll(captor.capture());

        AccountHolding h = captor.getValue().get(0);
        assertThat(h.getName()).isEqualTo("iShares Core MSCI World UCITS ETF");
    }

    @Test
    void positionName_preservesExistingNameWhenTransactionsHaveNone() {
        Account account = account(1L);
        // A manual BUY with no "Nom" — the transaction carries no name.
        Transaction buy = buyTx("VWCE.DE", "4", "100.00");

        // The position already has a name set by a previous bank sync (OpenFIGI).
        AccountHolding existing = AccountHolding.builder()
                .id(7L)
                .account(account)
                .ticker("VWCE.DE")
                .name("Vanguard FTSE All-World UCITS ETF")
                .quantity(new BigDecimal("1"))
                .build();

        when(transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(eq(1L), anyList()))
                .thenReturn(List.of(buy));
        when(accountHoldingRepository.findByAccount_Id(1L))
                .thenReturn(List.of(existing));

        holdingComputeService.recomputeHoldings(account);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountHolding>> captor = ArgumentCaptor.forClass(List.class);
        verify(accountHoldingRepository).saveAll(captor.capture());

        AccountHolding h = captor.getValue().get(0);
        // The sync-sourced name must survive a nameless manual transaction.
        assertThat(h.getName()).isEqualTo("Vanguard FTSE All-World UCITS ETF");
    }
}
