package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.Transaction;
import com.picsou.port.BankConnectorPort;
import com.picsou.port.BankConnectorPort.TransactionData;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class BankTransactionImportServiceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 20);
    private static final String DESCRIPTION = "Cafe de la Place";
    private static final BigDecimal AMOUNT = new BigDecimal("-4.99");

    @Mock BankConnectorPort bankConnector;
    @Mock TransactionRepository transactionRepository;

    private Account account;
    private BankTransactionImportService service;

    @BeforeEach
    void setUp() {
        account = Account.builder().id(55L).externalAccountId("eb-account-55").currency("EUR").build();
        service = new BankTransactionImportService(bankConnector, transactionRepository, 90);
    }

    @ParameterizedTest
    @MethodSource("fetchedOrders")
    void importFor_reservesStableIdsBeforeMatchingTheStrictMultisetRegardlessOfFetchOrder(
        List<TransactionData> fetched
    ) {
        Category manualCategory = Category.builder().id(88L).name("Chosen category")
            .slug("chosen-category").kind(CategoryKind.EXPENSE).build();
        Transaction ebTransaction = transaction("eb-transaction-1", null, manualCategory, true);
        Transaction sidecarTransaction = transaction("bourso:sidecar-transaction-1", "sidecar-transaction-1",
            manualCategory, true);

        assertThat(importTransactions(List.of(ebTransaction, sidecarTransaction), fetched)).isEqualTo(1);

        ArgumentCaptor<List<Transaction>> saved = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(transaction -> {
            assertThat(transaction.getExternalTransactionId()).isEqualTo("eb-transaction-2");
            assertThat(transaction.getDate()).isEqualTo(DATE);
            assertThat(transaction.getAmount()).isEqualByComparingTo(AMOUNT);
            assertThat(transaction.getDescription()).isEqualTo(DESCRIPTION);
        });
        assertThat(ebTransaction.getExternalTransactionId()).isEqualTo("eb-transaction-1");
        assertThat(ebTransaction.getCategoryRef().getId()).isEqualTo(88L);
        assertThat(ebTransaction.isCategoryManual()).isTrue();
        assertThat(sidecarTransaction.getExternalId()).isEqualTo("sidecar-transaction-1");
        assertThat(sidecarTransaction.getCategoryRef().getId()).isEqualTo(88L);
        assertThat(sidecarTransaction.isCategoryManual()).isTrue();
    }

    @Test
    void importFor_preservesTwoIdenticalTransactionsWithoutProviderIds() {
        List<TransactionData> fetched = List.of(data(null, DESCRIPTION), data(null, DESCRIPTION));

        assertThat(importTransactions(List.of(), fetched)).isEqualTo(2);

        ArgumentCaptor<List<Transaction>> saved = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2);
        assertThat(saved.getValue().get(0).getExternalTransactionId())
            .startsWith("fp:").isEqualTo(saved.getValue().get(1).getExternalTransactionId());
    }

    @Test
    void importFor_preservesSameStableIdAndContentMultiplicityAcrossReplaysAndReordering() {
        TransactionData duplicate = new TransactionData("eb-reused-reference", DATE, DESCRIPTION, AMOUNT, "EUR", null);
        List<TransactionData> two = List.of(duplicate,
            new TransactionData("eb-reused-reference", DATE, DESCRIPTION, AMOUNT, "EUR", null));
        List<TransactionData> replay = List.of(two.get(1), two.get(0));
        List<TransactionData> three = List.of(new TransactionData("eb-reused-reference", DATE, DESCRIPTION, AMOUNT,
            "EUR", null), two.get(1), two.get(0));
        List<Transaction> persisted = new ArrayList<>();

        when(transactionRepository.findLatestSyncedDateByAccountId(55L)).thenReturn(null, null, null);
        when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(eq(55L), any(LocalDate.class)))
            .thenAnswer(invocation -> new ArrayList<>(persisted));
        when(transactionRepository.saveAll(any())).thenAnswer(invocation -> {
            List<Transaction> rows = invocation.getArgument(0);
            persisted.addAll(rows);
            return rows;
        });
        when(bankConnector.fetchTransactions(eq("session"), eq("eb-account-55"), any(LocalDate.class)))
            .thenReturn(two, replay, three);

        assertThat(service.importFor(account, "session")).isEqualTo(2);
        assertThat(persisted).hasSize(2);
        assertThat(service.importFor(account, "session")).isZero();
        assertThat(persisted).hasSize(2);

        Collections.reverse(persisted);
        assertThat(service.importFor(account, "session")).isEqualTo(1);
        assertThat(persisted).hasSize(3);
        assertThat(persisted).extracting(Transaction::getExternalTransactionId)
            .containsOnly("eb-reused-reference");
        verify(transactionRepository, times(2)).saveAll(any());
    }

    @Test
    void importFor_roundsAmountsToPostgresScaleForPersistenceAndCrossSourceMatching() {
        BigDecimal fetchedAmount = new BigDecimal("1.123456789");
        TransactionData fetched = new TransactionData("eb-rounded", DATE, DESCRIPTION, fetchedAmount, "EUR", null);

        assertThat(importTransactions(List.of(), List.of(fetched))).isEqualTo(1);

        ArgumentCaptor<List<Transaction>> saved = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(saved.capture());
        Transaction imported = saved.getValue().getFirst();
        assertThat(imported.getAmount()).isEqualByComparingTo("1.12345679");
        assertThat(imported.getAmount().scale()).isEqualTo(8);
        assertThat(importTransactions(List.of(imported), List.of(fetched))).isZero();

        Transaction sidecar = Transaction.builder().account(account).date(DATE).description(DESCRIPTION)
            .amount(new BigDecimal("1.12345679")).externalId("sidecar-id")
            .externalTransactionId("bourso:sidecar-id").isManual(false).build();
        assertThat(importTransactions(List.of(sidecar), List.of(fetched))).isZero();
        verify(transactionRepository, times(1)).saveAll(any());
    }

    @Test
    void importFor_doesNotInferOverlapFromDateAndAmountWhenDescriptionsDiffer() {
        Transaction sidecarTransaction = transaction("bourso:sidecar-transaction-1", "sidecar-transaction-1",
            null, false);

        assertThat(importTransactions(List.of(sidecarTransaction), List.of(data("eb-transaction-1", "Card payment"))))
            .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"externalId", "externalTransactionId"})
    void importFor_reservesHistoricalIdsBeforeFingerprintMatching(String identifierField) {
        Transaction existing = Transaction.builder().account(account).date(DATE)
            .description(DESCRIPTION).amount(AMOUNT).isManual(false).build();
        if ("externalId".equals(identifierField)) {
            existing.setExternalId("rev-1");
        } else {
            existing.setExternalTransactionId("rev-1");
        }
        List<TransactionData> fetched = List.of(data("rev-2", DESCRIPTION), data("rev-1", DESCRIPTION));

        assertThat(importTransactions(List.of(existing), fetched)).isEqualTo(1);

        ArgumentCaptor<List<Transaction>> saved = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement()
            .extracting(Transaction::getExternalTransactionId).isEqualTo("rev-2");
    }

    @Test
    void importFor_keepsReusedReferenceRowsAndDeduplicatesExactRepeatsAcrossSnapshots() {
        Transaction original = Transaction.builder().id(101L).account(account).date(DATE)
            .description("Original description").amount(new BigDecimal("-3.00"))
            .externalTransactionId("eb-reused-reference").isManual(false).build();
        TransactionData originalData = new TransactionData("eb-reused-reference", DATE,
            "Original description", new BigDecimal("-3.00"), "EUR", null);
        TransactionData distinctData = new TransactionData("eb-reused-reference", DATE,
            "Distinct purchase", new BigDecimal("-3.50"), "EUR", null);

        when(transactionRepository.findLatestSyncedDateByAccountId(55L)).thenReturn(null, null);
        when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(eq(55L), any(LocalDate.class)))
            .thenReturn(List.of(original), List.of(original));
        when(bankConnector.fetchTransactions(eq("session"), eq("eb-account-55"), any(LocalDate.class)))
            .thenReturn(List.of(distinctData, originalData), List.of(originalData, distinctData));

        assertThat(service.importFor(account, "session")).isEqualTo(1);

        ArgumentCaptor<List<Transaction>> saved = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(saved.capture());
        Transaction inserted = saved.getValue().getFirst();
        assertThat(inserted.getExternalTransactionId()).isEqualTo("eb-reused-reference");
        assertThat(inserted.getDescription()).isEqualTo("Distinct purchase");
        assertThat(inserted.getAmount()).isEqualByComparingTo("-3.50");

        when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(eq(55L), any(LocalDate.class)))
            .thenReturn(List.of(original, inserted));
        assertThat(service.importFor(account, "session")).isZero();
        verify(transactionRepository, times(1)).saveAll(any());
    }

    @ParameterizedTest
    @MethodSource("currencyCases")
    void importFor_skipsTransactionsWhoseEffectiveCurrencyIsNotEur(
        String accountCurrency, String transactionCurrency, int expectedInserted
    ) {
        account.setCurrency(accountCurrency);
        TransactionData fetched = new TransactionData("currency-transaction", DATE, DESCRIPTION, AMOUNT,
            transactionCurrency, null);

        when(transactionRepository.findLatestSyncedDateByAccountId(55L)).thenReturn(null);
        when(bankConnector.fetchTransactions(eq("session"), eq("eb-account-55"), any(LocalDate.class)))
            .thenReturn(List.of(fetched));
        if (expectedInserted == 1) {
            when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(
                eq(55L), any(LocalDate.class))).thenReturn(List.of());
        }

        assertThat(service.importFor(account, "session")).isEqualTo(expectedInserted);

        verify(transactionRepository, times(expectedInserted == 0 ? 0 : 1)).saveAll(any());
        if (expectedInserted == 1) {
            ArgumentCaptor<List<Transaction>> saved = ArgumentCaptor.forClass(List.class);
            verify(transactionRepository).saveAll(saved.capture());
            assertThat(saved.getValue()).singleElement()
                .extracting(Transaction::getNativeCurrency).isEqualTo("EUR");
        }
    }

    @Test
    void importFor_boundsDescriptionAndHashesOversizedExternalIdDeterministically() {
        String externalId = "eb-" + "x".repeat(200);
        String description = "🪙".repeat(256);
        TransactionData fetched = new TransactionData(externalId, DATE, description, AMOUNT, "EUR", null);

        assertThat(importTransactions(List.of(), List.of(fetched))).isEqualTo(1);

        ArgumentCaptor<List<Transaction>> saved = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(saved.capture());
        Transaction inserted = saved.getValue().getFirst();
        String storedKey = inserted.getExternalTransactionId();
        String expectedKey = BankTransactionImportService.dedupKey(fetched);
        assertThat(inserted.getDescription()).isEqualTo("🪙".repeat(255));
        assertThat(inserted.getDescription().codePointCount(0, inserted.getDescription().length())).isEqualTo(255);
        assertThat(storedKey.codePointCount(0, storedKey.length())).isLessThanOrEqualTo(128);
        assertThat(storedKey).startsWith("id:").isEqualTo(expectedKey);
        assertThat(BankTransactionImportService.dedupKey(fetched)).isEqualTo(expectedKey);

        assertThat(importTransactions(List.of(inserted), List.of(fetched))).isZero();
        verify(transactionRepository, times(1)).saveAll(any());
    }

    private int importTransactions(List<Transaction> existing, List<TransactionData> fetched) {
        when(transactionRepository.findLatestSyncedDateByAccountId(55L)).thenReturn(null);
        when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(eq(55L), any(LocalDate.class)))
            .thenReturn(existing);
        when(bankConnector.fetchTransactions(eq("session"), eq("eb-account-55"), any(LocalDate.class)))
            .thenReturn(fetched);
        return service.importFor(account, "session");
    }

    private Transaction transaction(String transactionId, String externalId, Category category, boolean manualCategory) {
        return Transaction.builder().id(transactionId.startsWith("eb-") ? 101L : 102L)
            .account(account).date(DATE).description(DESCRIPTION).amount(AMOUNT)
            .externalId(externalId).externalTransactionId(transactionId)
            .categoryRef(category).categoryManual(manualCategory).isManual(false).build();
    }

    private static TransactionData data(String externalId, String description) {
        return new TransactionData(externalId, DATE, description, AMOUNT, "EUR", null);
    }

    private static Stream<Arguments> currencyCases() {
        return Stream.of(
            Arguments.of("EUR", "GBP", 0),
            Arguments.of("GBP", null, 0),
            Arguments.of("GBP", "EUR", 1),
            Arguments.of("EUR", "  ", 1)
        );
    }

    private static Stream<Arguments> fetchedOrders() {
        TransactionData withoutId = data(null, DESCRIPTION);
        TransactionData knownId = data("eb-transaction-1", DESCRIPTION);
        TransactionData newId = data("eb-transaction-2", DESCRIPTION);
        return Stream.of(
            Arguments.of(List.of(withoutId, knownId, newId)),
            Arguments.of(List.of(knownId, withoutId, newId))
        );
    }
}
