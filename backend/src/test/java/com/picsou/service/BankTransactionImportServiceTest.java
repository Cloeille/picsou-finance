package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.Transaction;
import com.picsou.port.BankConnectorPort;
import com.picsou.port.BankConnectorPort.TransactionData;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankTransactionImportServiceTest {

    @Mock BankConnectorPort bankConnector;
    @Mock TransactionRepository transactionRepository;

    @Test
    void importProvidedClipsADescriptionThatExceedsTheColumn() {
        BankTransactionImportService service =
            new BankTransactionImportService(bankConnector, transactionRepository, 90);
        Account account = new Account();
        account.setId(1L);
        account.setCurrency("USD");
        when(transactionRepository.findLatestSyncedDateByAccountId(1L)).thenReturn(null);
        when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(eq(1L), any()))
            .thenReturn(List.of());

        service.importProvided(account, List.of(new TransactionData(
            "tx-1",
            LocalDate.of(2026, 1, 2),
            "x".repeat(400),
            new BigDecimal("-4.50"),
            "USD",
            null)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> saved = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(tx -> {
            assertThat(tx.getDescription()).hasSize(255);
            assertThat(tx.getExternalTransactionId()).isEqualTo("tx-1");
        });
    }

    @Test
    void importProvidedSkipsARowItAlreadyStored() {
        BankTransactionImportService service =
            new BankTransactionImportService(bankConnector, transactionRepository, 90);
        Account account = new Account();
        account.setId(1L);
        account.setCurrency("USD");
        List<Transaction> stored = new java.util.ArrayList<>();
        when(transactionRepository.findLatestSyncedDateByAccountId(1L)).thenReturn(null);
        when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(eq(1L), any()))
            .thenAnswer(invocation -> List.copyOf(stored));
        when(transactionRepository.saveAll(any())).thenAnswer(invocation -> {
            stored.addAll(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        List<TransactionData> batch = List.of(new TransactionData(
            "tx-1", LocalDate.of(2026, 1, 2), "Coffee", new BigDecimal("-4.50"), "USD", null));

        assertThat(service.importProvided(account, batch)).isEqualTo(1);
        assertThat(service.importProvided(account, batch)).isZero();

        verify(transactionRepository, times(1)).saveAll(any());
        assertThat(stored).hasSize(1);
    }

    @Test
    void importProvidedDropsARepeatedIdAndAnAmountTheColumnCannotHold() {
        BankTransactionImportService service =
            new BankTransactionImportService(bankConnector, transactionRepository, 90);
        Account account = new Account();
        account.setId(1L);
        account.setCurrency("USD");
        when(transactionRepository.findLatestSyncedDateByAccountId(1L)).thenReturn(null);
        when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(eq(1L), any()))
            .thenReturn(List.of());
        String longId = "t".repeat(300);

        service.importProvided(account, List.of(
            new TransactionData("tx-1", LocalDate.of(2026, 1, 2), "Coffee", new BigDecimal("-4.50"), "USD", null),
            new TransactionData("tx-1", LocalDate.of(2026, 1, 2), "Coffee again", new BigDecimal("-4.50"), "USD", null),
            new TransactionData("huge", LocalDate.of(2026, 1, 2), "Nope", new BigDecimal("1000000000000"), "USD", null),
            new TransactionData(longId, LocalDate.of(2026, 1, 3), "x".repeat(254) + "\uD83D\uDE00yyyy", new BigDecimal("1.00"), "USD", null)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> saved = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2);
        assertThat(saved.getValue().get(0).getExternalTransactionId()).isEqualTo("tx-1");
        Transaction clipped = saved.getValue().get(1);
        assertThat(clipped.getExternalTransactionId()).startsWith("fp:").hasSizeLessThanOrEqualTo(255);
        assertThat(clipped.getDescription()).isEqualTo("x".repeat(254));
        assertThat(Character.isHighSurrogate(clipped.getDescription().charAt(clipped.getDescription().length() - 1))).isFalse();
    }
}
