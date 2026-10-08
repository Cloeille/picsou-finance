package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.Transaction;
import com.picsou.port.BankConnectorPort;
import com.picsou.port.BankConnectorPort.TransactionData;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankTransactionImportServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 2);
    private static final String EMOJI = "\uD83D\uDE00";

    @Mock BankConnectorPort bankConnector;
    @Mock TransactionRepository transactionRepository;

    // ---- clipping ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {255, 400})
    void importProvided_longDescription_isClippedToTheColumn(int length) {
        givenNothingStored(1L);

        service().importProvided(account(1L), List.of(row("tx-1", "x".repeat(length))));

        assertThat(savedRows()).singleElement().satisfies(tx -> {
            assertThat(tx.getDescription()).isEqualTo("x".repeat(255));
            assertThat(tx.getExternalTransactionId()).isEqualTo("tx-1");
        });
    }

    @Test
    void importProvided_emojiEndingExactlyOnTheLimit_isKept() {
        givenNothingStored(1L);
        String description = "x".repeat(253) + EMOJI;

        service().importProvided(account(1L), List.of(row("tx-1", description)));

        assertThat(savedRows()).singleElement()
            .satisfies(tx -> assertThat(tx.getDescription()).isEqualTo(description).hasSize(255));
    }

    @Test
    void importProvided_emojiStraddlingTheLimit_isDroppedWhole() {
        givenNothingStored(1L);

        service().importProvided(account(1L), List.of(row("tx-1", "x".repeat(254) + EMOJI + "yyyy")));

        assertThat(savedRows()).singleElement()
            .satisfies(tx -> assertThat(tx.getDescription()).isEqualTo("x".repeat(254)));
    }

    @Test
    void importProvided_nullDescription_isStoredEmpty() {
        givenNothingStored(1L);

        service().importProvided(account(1L), List.of(row("tx-1", null)));

        assertThat(savedRows()).singleElement().satisfies(tx -> assertThat(tx.getDescription()).isEmpty());
    }

    // ---- keys and dedup ---------------------------------------------------------------

    @Test
    void importProvided_externalIdOver255_isHashedWithTheFingerprintPrefix() {
        givenNothingStored(1L);

        service().importProvided(account(1L), List.of(row("t".repeat(300), "Coffee")));

        assertThat(savedRows()).singleElement().satisfies(tx ->
            assertThat(tx.getExternalTransactionId()).startsWith("fp:").hasSizeLessThanOrEqualTo(255));
    }

    @Test
    void importProvided_repeatedIdInOneBatch_isStoredOnce() {
        givenNothingStored(1L);

        service().importProvided(account(1L), List.of(row("tx-1", "Coffee"), row("tx-1", "Coffee again")));

        assertThat(savedRows()).singleElement().satisfies(tx -> assertThat(tx.getDescription()).isEqualTo("Coffee"));
    }

    @Test
    void importProvided_amountTheColumnCannotHold_isSkippedAndTheOthersStored() {
        givenNothingStored(1L);
        TransactionData huge = new TransactionData("huge", DAY, "Nope", new BigDecimal("1000000000000"), "USD", null);

        service().importProvided(account(1L), List.of(huge, row("tx-1", "Coffee")));

        assertThat(savedRows()).extracting(Transaction::getExternalTransactionId).containsExactly("tx-1");
    }

    @Test
    void importProvided_secondImportOfTheSameBatch_insertsNothing() {
        List<Transaction> stored = givenRepositoryBackedByAList();
        List<TransactionData> batch = List.of(row("tx-1", "Coffee"));

        assertThat(service().importProvided(account(1L), batch)).isEqualTo(1);
        assertThat(service().importProvided(account(1L), batch)).isZero();

        verify(transactionRepository, times(1)).saveAll(any());
        assertThat(stored).hasSize(1);
    }

    @Test
    void importProvided_sameIdOnTwoAccounts_isStoredForBoth() {
        List<Transaction> stored = givenRepositoryBackedByAList();

        int first = service().importProvided(account(1L), List.of(row("shared", "Transfer out")));
        int second = service().importProvided(account(2L), List.of(row("shared", "Transfer in")));

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        assertThat(stored).extracting(tx -> tx.getAccount().getId()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void importProvided_idWithSurroundingSpaces_matchesTheStoredRow() {
        givenStored(DAY, storedRow(DAY, "Coffee", "-4.50", "tx-1"));

        int imported = service().importProvided(account(1L), List.of(row(" tx-1 ", "Coffee")));

        assertThat(imported).isZero();
        verify(transactionRepository, never()).saveAll(any());
    }

    @Test
    void importProvided_rowOlderThanTheWindow_isComparedAgainstStoredHistory() {
        LocalDate old = LocalDate.now().minusDays(200);
        when(transactionRepository.findLatestSyncedDateByAccountId(1L)).thenReturn(LocalDate.now());
        when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(1L, old))
            .thenReturn(List.of(storedRow(old, "Old", "-1.00", "tx-old")));

        int imported = service().importProvided(account(1L), List.of(new TransactionData(
            "tx-old", old, "Old", new BigDecimal("-1.00"), "USD", null)));

        assertThat(imported).isZero();
    }

    @Test
    void importProvided_noIdAndAmountOfAnotherScale_matchesTheStoredRow() {
        givenStored(DAY, storedRow(DAY, "Coffee", "12.34000000", null));

        int imported = service().importProvided(account(1L), List.of(new TransactionData(
            null, DAY, " Coffee ", new BigDecimal("1.234e1"), "USD", null)));

        assertThat(imported).isZero();
    }

    @Test
    void dedupKey_amountsEqualAfterRoundingTo8Decimals_shareTheFingerprint() {
        TransactionData exact = new TransactionData(null, DAY, "Coffee", new BigDecimal("12.34"), "USD", null);
        TransactionData roundsToSame = new TransactionData(null, DAY, "Coffee", new BigDecimal("12.340000001"), "USD", null);

        assertThat(BankTransactionImportService.dedupKey(roundsToSame))
            .startsWith("fp:").isEqualTo(BankTransactionImportService.dedupKey(exact));
    }

    @Test
    void dedupKey_amountsDifferingAtThe8thDecimal_haveDifferentFingerprints() {
        TransactionData exact = new TransactionData(null, DAY, "Coffee", new BigDecimal("12.34"), "USD", null);
        TransactionData other = new TransactionData(null, DAY, "Coffee", new BigDecimal("12.34000001"), "USD", null);

        assertThat(BankTransactionImportService.dedupKey(other)).isNotEqualTo(BankTransactionImportService.dedupKey(exact));
    }

    // ---- ledger limit -----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"999999999999.99999999", "-999999999999.99999999", "-0.00", "1e-999999999"})
    void fitsLedgerAmount_insideNumeric20_8_isAccepted(BigDecimal amount) {
        assertThat(BankTransactionImportService.fitsLedgerAmount(amount)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"1000000000000", "-1000000000000", "1e999999999", "-1e999999999"})
    void fitsLedgerAmount_nullOrOutsideNumeric20_8_isRefused(BigDecimal amount) {
        assertThat(BankTransactionImportService.fitsLedgerAmount(amount)).isFalse();
    }

    // ---- helpers ----------------------------------------------------------------------

    private BankTransactionImportService service() {
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

    private static Transaction storedRow(LocalDate date, String description, String amount, String externalId) {
        return Transaction.builder().account(account(1L)).date(date).description(description)
            .amount(new BigDecimal(amount)).externalTransactionId(externalId).isManual(false).build();
    }

    private void givenNothingStored(long accountId) {
        when(transactionRepository.findLatestSyncedDateByAccountId(accountId)).thenReturn(null);
        when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(eq(accountId), any()))
            .thenReturn(List.of());
    }

    private void givenStored(LocalDate latest, Transaction stored) {
        when(transactionRepository.findLatestSyncedDateByAccountId(1L)).thenReturn(latest);
        when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(eq(1L), any()))
            .thenReturn(List.of(stored));
    }

    /** Saved rows become visible to later lookups of the same account, like the real table. */
    private List<Transaction> givenRepositoryBackedByAList() {
        List<Transaction> stored = new ArrayList<>();
        when(transactionRepository.findLatestSyncedDateByAccountId(any())).thenReturn(null);
        when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(any(), any()))
            .thenAnswer(invocation -> stored.stream()
                .filter(tx -> tx.getAccount().getId().equals(invocation.getArgument(0))).toList());
        when(transactionRepository.saveAll(any())).thenAnswer(invocation -> {
            stored.addAll(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        return stored;
    }

    private List<Transaction> savedRows() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> saved = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(saved.capture());
        return saved.getValue();
    }
}
