package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.Transaction;
import com.picsou.port.SidecarTransaction;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SharedSidecarTransactionImportServiceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 20);

    @Mock TransactionRepository transactionRepository;
    @Mock CategorizationService categorizationService;

    private final List<Transaction> stored = new ArrayList<>();
    private SharedSidecarTransactionImportService service;

    @BeforeEach
    void setUp() {
        stored.clear();
        org.mockito.Mockito.lenient().when(transactionRepository.findByAccountIdAndIsManualFalse(any())).thenReturn(stored);
        org.mockito.Mockito.lenient().when(transactionRepository.save(any())).thenAnswer(invocation -> {
            Transaction transaction = invocation.getArgument(0);
            stored.add(transaction);
            return transaction;
        });
        service = new SharedSidecarTransactionImportService(transactionRepository, categorizationService);
    }

    @Test
    void resyncWithStableProviderId_importsOnlyOnce() {
        Account account = account(1L);
        SidecarTransaction coffee = transaction("rev-1", DATE, "Coffee", "-3.50", CategoryKind.EXPENSE);

        assertThat(service.importFor(account, List.of(coffee), context(), "bourso")).isEqualTo(1);
        assertThat(service.importFor(account, List.of(coffee), context(), "bourso")).isZero();
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().getExternalId()).isEqualTo("rev-1");
        assertThat(stored.getFirst().getExternalTransactionId()).isEqualTo("bourso:rev-1");
    }

    @Test
    void matchesLegacyEnableBankingRowByStrictFingerprintWithoutChangingItsCategory() {
        Account account = account(1L);
        Category manualCategory = Category.builder().name("User choice").kind(CategoryKind.EXPENSE).build();
        stored.add(Transaction.builder()
            .account(account).date(DATE).description("  VIREMENT   INTERNE ").amount(new BigDecimal("-10.00000000"))
            .externalId("eb-reference").externalTransactionId("eb-reference").categoryRef(manualCategory)
            .categoryManual(true).build());

        int imported = service.importFor(account,
            List.of(transaction("bourso-ref", DATE, "Virement Interne", "-10", CategoryKind.TRANSFER)), context(), "bourso");

        assertThat(imported).isZero();
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().getExternalId()).isEqualTo("eb-reference");
        assertThat(stored.getFirst().getExternalTransactionId()).isEqualTo("eb-reference");
        assertThat(stored.getFirst().getCategoryRef()).isSameAs(manualCategory);
    }

    @Test
    void retainsTwoLegitimateIdenticalOperationsWithDifferentProviderIds() {
        Account account = account(1L);
        List<SidecarTransaction> fetched = List.of(
            transaction("id-one", DATE, "Market", "-12.00", CategoryKind.EXPENSE),
            transaction("id-two", DATE, "Market", "-12", CategoryKind.EXPENSE));

        assertThat(service.importFor(account, fetched, context(), "bourso")).isEqualTo(2);
        assertThat(stored).hasSize(2);
        assertThat(stored).extracting(Transaction::getExternalId).containsExactly("id-one", "id-two");
    }

    @Test
    void legacyOverlapIsConsumedOnceAndDoesNotCollapseAnAdditionalIdenticalOperation() {
        Account account = account(1L);
        stored.add(Transaction.builder().account(account).date(DATE).description("Rent")
            .amount(new BigDecimal("-100.00")).externalId("eb-row").externalTransactionId("eb-row")
            .isManual(false).build());
        List<SidecarTransaction> fetched = List.of(
            transaction("new-id-one", DATE, "rent", "-100", CategoryKind.EXPENSE),
            transaction("new-id-two", DATE, " Rent ", "-100.00", CategoryKind.EXPENSE));

        assertThat(service.importFor(account, fetched, context(), "bourso")).isEqualTo(1);
        assertThat(stored).hasSize(2);
        assertThat(service.importFor(account, fetched, context(), "bourso")).isZero();
        assertThat(stored).hasSize(2);
    }

    @Test
    void transferKindWinsOverAnExpenseRule() {
        Account account = account(1L);
        Category transfer = Category.builder().name("Internal transfer").slug("virement-interne")
            .kind(CategoryKind.TRANSFER).build();
        when(categorizationService.autoCategorize(any(), any(CategorizationService.CategorizationContext.class)))
            .thenAnswer(invocation -> {
            Transaction transaction = invocation.getArgument(0);
            transaction.setCategoryRef(Category.builder().name("Rule expense").kind(CategoryKind.EXPENSE).build());
            return true;
        });
        Map<String, Category> categories = new HashMap<>();
        categories.put("virement-interne", transfer);

        assertThat(service.importFor(account,
            List.of(transaction("transfer-leg", DATE, "Transfer", "-30", CategoryKind.TRANSFER)),
            new CategorizationService.CategorizationContext(List.of(), categories), "revolut")).isEqualTo(1);

        assertThat(stored.getFirst().getCategoryRef()).isSameAs(transfer);
        assertThat(stored.getFirst().getCategoryRef().getKind()).isEqualTo(CategoryKind.TRANSFER);
    }

    @Test
    void ordinaryCurrentExpenseIsPersistedWithExpenseCategoryForBudgetReads() {
        Account account = account(1L);
        Category expense = Category.builder().name("Groceries").slug("groceries").kind(CategoryKind.EXPENSE).build();
        when(categorizationService.autoCategorize(any(), any(CategorizationService.CategorizationContext.class)))
            .thenAnswer(invocation -> {
                ((Transaction) invocation.getArgument(0)).setCategoryRef(expense);
                return true;
            });

        assertThat(service.importFor(account,
            List.of(new SidecarTransaction("grocery-1", DATE, "Market", new BigDecimal("-12.50"), null, "TRANSFER")),
            context(), "bourso")).isEqualTo(1);

        assertThat(stored.getFirst().isManual()).isFalse();
        assertThat(stored.getFirst().getCategoryRef().getKind()).isEqualTo(CategoryKind.EXPENSE);
        assertThat(stored.getFirst().getDate()).isEqualTo(DATE);
        assertThat(stored.getFirst().getAmount()).isEqualByComparingTo("-12.50");
    }

    @Test
    void identicalFingerprintOnAnotherAccountIsNotAOverlapMatch() {
        Account first = account(1L);
        Account second = account(2L);
        SidecarTransaction same = transaction("provider-ref", DATE, "Rent", "-100", CategoryKind.EXPENSE);

        assertThat(service.importFor(first, List.of(same), context(), "bourso")).isEqualTo(1);
        when(transactionRepository.findByAccountIdAndIsManualFalse(2L)).thenReturn(List.of());
        assertThat(service.importFor(second, List.of(same), context(), "bourso")).isEqualTo(1);
        assertThat(stored).hasSize(2);
    }

    private static Account account(Long id) {
        return Account.builder().id(id).name("Test account").currency("EUR").build();
    }

    private static SidecarTransaction transaction(String externalId, LocalDate date, String description,
                                                   String amount, CategoryKind kind) {
        return new SidecarTransaction(externalId, date, description, new BigDecimal(amount), "Merchant",
            kind == null ? null : kind.name());
    }

    private static CategorizationService.CategorizationContext context() {
        return new CategorizationService.CategorizationContext(List.of(), Map.of());
    }
}
