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

    // ------------------------------------------------------------------------------------
    // Data edge cases for provider-supplied rows (SimpleFIN): clipping, dedup, ledger limits.
    // ------------------------------------------------------------------------------------

    private static final LocalDate DAY = LocalDate.of(2026, 1, 2);

    private BankTransactionImportService newService() {
        return new BankTransactionImportService(bankConnector, transactionRepository, 90);
    }

    private static Account account(long id) {
        Account account = new Account();
        account.setId(id);
        account.setCurrency("USD");
        return account;
    }

    private static TransactionData row(String id, String description) {
        return new TransactionData(id, DAY, description, new BigDecimal("-4.50"), "USD", null);
    }

    private void givenNothingStored(long accountId) {
        when(transactionRepository.findLatestSyncedDateByAccountId(accountId)).thenReturn(null);
        when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(eq(accountId), any()))
            .thenReturn(List.of());
    }

    private List<Transaction> savedRows() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> saved = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(saved.capture());
        return saved.getValue();
    }

    @Test
    void importProvided_anEmojiThatEndsExactlyOnTheLimitIsKept() {
        givenNothingStored(1L);
        String description = "x".repeat(253) + "\uD83D\uDE00";

        newService().importProvided(account(1L), List.of(row("tx-1", description)));

        assertThat(savedRows()).singleElement()
            .satisfies(tx -> assertThat(tx.getDescription()).isEqualTo(description).hasSize(255));
    }

    @Test
    void importProvided_aCombiningMarkPastTheLimitIsCutLeavingValidUtf16() {
        givenNothingStored(1L);

        newService().importProvided(account(1L), List.of(row("tx-1", "x".repeat(254) + "e\u0301")));

        assertThat(savedRows()).singleElement().satisfies(tx -> {
            assertThat(tx.getDescription()).isEqualTo("x".repeat(254) + "e");
            assertThat(Character.isSurrogate(tx.getDescription().charAt(254))).isFalse();
        });
    }

    @Test
    void importProvided_aLongRunOfEmojiIsCutOnAWholeEmoji() {
        givenNothingStored(1L);

        newService().importProvided(account(1L), List.of(row("tx-1", "\uD83D\uDE00".repeat(200))));

        assertThat(savedRows()).singleElement().satisfies(tx -> {
            assertThat(tx.getDescription()).hasSize(254);
            assertThat(tx.getDescription().codePoints().allMatch(cp -> cp == 0x1F600)).isTrue();
        });
    }

    @Test
    void importProvided_aDescriptionOfExactly255IsNotTouched() {
        givenNothingStored(1L);

        newService().importProvided(account(1L), List.of(row("tx-1", "y".repeat(255))));

        assertThat(savedRows()).singleElement()
            .satisfies(tx -> assertThat(tx.getDescription()).isEqualTo("y".repeat(255)));
    }

    @Test
    void importProvided_aNullDescriptionIsStoredEmptyRatherThanFailing() {
        givenNothingStored(1L);

        newService().importProvided(account(1L), List.of(row("tx-1", null)));

        assertThat(savedRows()).singleElement().satisfies(tx -> assertThat(tx.getDescription()).isEmpty());
    }

    @Test
    void importProvided_anIdWithSurroundingSpacesMatchesTheStoredRow() {
        Transaction stored = Transaction.builder().account(account(1L)).date(DAY).description("Coffee")
            .amount(new BigDecimal("-4.50")).externalTransactionId("tx-1").isManual(false).build();
        when(transactionRepository.findLatestSyncedDateByAccountId(1L)).thenReturn(DAY);
        when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(eq(1L), any()))
            .thenReturn(List.of(stored));

        int imported = newService().importProvided(account(1L), List.of(row(" tx-1 ", "Coffee")));

        assertThat(imported).isZero();
        verify(transactionRepository, org.mockito.Mockito.never()).saveAll(any());
    }

    @Test
    void importProvided_theSameTransactionIdOnTwoAccountsIsStoredForBoth() {
        Account checking = account(1L);
        Account savings = account(2L);
        List<Transaction> stored = new java.util.ArrayList<>();
        when(transactionRepository.findLatestSyncedDateByAccountId(any())).thenReturn(null);
        when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(any(), any()))
            .thenAnswer(invocation -> stored.stream()
                .filter(tx -> tx.getAccount().getId().equals(invocation.getArgument(0))).toList());
        when(transactionRepository.saveAll(any())).thenAnswer(invocation -> {
            stored.addAll(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        BankTransactionImportService service = newService();

        int first = service.importProvided(checking, List.of(row("shared", "Transfer out")));
        int second = service.importProvided(savings, List.of(row("shared", "Transfer in")));

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        assertThat(stored).extracting(tx -> tx.getAccount().getId()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void importProvided_aRowOlderThanTheWindowIsComparedAgainstStoredHistory() {
        LocalDate old = LocalDate.now().minusDays(200);
        Transaction stored = Transaction.builder().account(account(1L)).date(old).description("Old")
            .amount(new BigDecimal("-1.00")).externalTransactionId("tx-old").isManual(false).build();
        when(transactionRepository.findLatestSyncedDateByAccountId(1L)).thenReturn(LocalDate.now());
        when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(1L, old))
            .thenReturn(List.of(stored));

        int imported = newService().importProvided(account(1L), List.of(new TransactionData(
            "tx-old", old, "Old", new BigDecimal("-1.00"), "USD", null)));

        assertThat(imported).isZero();
    }

    @Test
    void importProvided_anAmountThatDiffersOnlyInScaleMatchesTheStoredRowWhenThereIsNoId() {
        Transaction stored = Transaction.builder().account(account(1L)).date(DAY).description("Coffee")
            .amount(new BigDecimal("12.34000000")).isManual(false).build();
        when(transactionRepository.findLatestSyncedDateByAccountId(1L)).thenReturn(DAY);
        when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(eq(1L), any()))
            .thenReturn(List.of(stored));

        int imported = newService().importProvided(account(1L), List.of(new TransactionData(
            null, DAY, " Coffee ", new BigDecimal("1.234e1"), "USD", null)));

        assertThat(imported).isZero();
    }

    @Test
    void fitsLedgerAmount_sitsExactlyOnTheNumeric20_8Boundary() {
        assertThat(BankTransactionImportService.fitsLedgerAmount(new BigDecimal("999999999999.99999999"))).isTrue();
        assertThat(BankTransactionImportService.fitsLedgerAmount(new BigDecimal("-999999999999.99999999"))).isTrue();
        assertThat(BankTransactionImportService.fitsLedgerAmount(new BigDecimal("1000000000000"))).isFalse();
        assertThat(BankTransactionImportService.fitsLedgerAmount(new BigDecimal("-1000000000000"))).isFalse();
        assertThat(BankTransactionImportService.fitsLedgerAmount(new BigDecimal("-0.00"))).isTrue();
        assertThat(BankTransactionImportService.fitsLedgerAmount(null)).isFalse();
    }

    @Test
    void fitsLedgerAmount_anAbsurdExponentIsRefusedWithoutAllocatingTheDigits() {
        assertThat(BankTransactionImportService.fitsLedgerAmount(new BigDecimal("1e999999999"))).isFalse();
        assertThat(BankTransactionImportService.fitsLedgerAmount(new BigDecimal("-1e999999999"))).isFalse();
        assertThat(BankTransactionImportService.fitsLedgerAmount(new BigDecimal("1e-999999999"))).isTrue();
    }
}
