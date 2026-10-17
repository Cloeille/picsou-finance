package com.picsou.service;

import com.picsou.dto.BudgetResponse;
import com.picsou.dto.CashflowPeriod;
import com.picsou.model.Account;
import com.picsou.model.Budget;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.CategorizationRule;
import com.picsou.model.FamilyMember;
import com.picsou.model.RuleMatchType;
import com.picsou.model.Transaction;
import com.picsou.port.SidecarTransaction;
import com.picsou.repository.BudgetRepository;
import com.picsou.repository.BudgetSettingsRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.CategorizationRuleRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.BudgetService;
import com.picsou.service.budget.BudgetSettingsService;
import com.picsou.service.budget.CashflowService;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.budget.CategoryService;
import com.picsou.service.budget.MerchantKnowledgeBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SidecarBudgetAcceptanceTest {
    private static final Long MEMBER_ID = 42L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final LocalDate CYCLE_START = LocalDate.of(2026, 9, 15);
    private static final LocalDate CYCLE_END = LocalDate.of(2026, 10, 14);

    @Mock TransactionRepository transactionRepository;
    @Mock BudgetRepository budgetRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock BudgetSettingsService budgetSettingsService;
    @Mock CategorizationRuleRepository ruleRepository;
    @Mock BudgetSettingsRepository settingsRepository;
    @Mock CategoryService categoryService;
    @Mock MerchantKnowledgeBase knowledgeBase;

    @Test
    void boursoSidecarSpendingAppearsInCurrentCycleBudgetAndResyncIsIdempotent() {
        Category groceries = category(11L, "Groceries", CategoryKind.EXPENSE);
        Category transfer = category(12L, "Internal transfer", CategoryKind.TRANSFER);
        List<CategorizationRule> rules = List.of(
            rule("market", groceries),
            rule("internal transfer", transfer));
        Account account = Account.builder()
            .id(7L).member(FamilyMember.builder().id(MEMBER_ID).build())
            .name("Bourso current account").currency("EUR").build();
        List<Transaction> stored = new ArrayList<>();
        when(transactionRepository.findByAccountIdAndIsManualFalse(7L)).thenAnswer(invocation ->
            stored.stream().filter(row -> !row.isManual()).toList());
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction transaction = invocation.getArgument(0);
            stored.add(transaction);
            return transaction;
        });
        when(knowledgeBase.match(any())).thenReturn(Optional.empty());

        CategorizationService categorizationService = new CategorizationService(
            ruleRepository, categoryRepository, transactionRepository, familyMemberRepository,
            knowledgeBase, categoryService, settingsRepository);
        CategorizationService.CategorizationContext context = new CategorizationService.CategorizationContext(
            rules, Map.of("groceries", groceries, "virement-interne", transfer));
        SharedSidecarTransactionImportService importer =
            new SharedSidecarTransactionImportService(transactionRepository, categorizationService);
        List<SidecarTransaction> snapshot = List.of(
            new SidecarTransaction("grocery-1", LocalDate.of(2026, 9, 20), "Market groceries",
                new BigDecimal("-12.50"), "Market", null),
            new SidecarTransaction("grocery-2", LocalDate.of(2026, 10, 3), "Market groceries",
                new BigDecimal("-7.50"), "Market", null),
            new SidecarTransaction("transfer-1", LocalDate.of(2026, 10, 4), "Internal transfer",
                new BigDecimal("-50.00"), "Own account", "TRANSFER"),
            new SidecarTransaction("outside-cycle", LocalDate.of(2026, 9, 14), "Market groceries",
                new BigDecimal("-99.00"), "Market", null));

        assertThat(importer.importFor(account, snapshot, context, "bourso")).isEqualTo(4);
        assertThat(stored).hasSize(4);
        assertThat(stored).extracting(Transaction::getCategoryRef)
            .containsExactly(groceries, groceries, transfer, groceries);

        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(15);
        when(budgetRepository.findAllByMemberIdOrderByIdAsc(MEMBER_ID)).thenReturn(List.of(
            Budget.builder().id(1L).category(groceries).monthlyLimit(new BigDecimal("100.00")).build()));
        when(categoryRepository.findAllByMemberIdAndParentIdOrderBySortOrderAscIdAsc(MEMBER_ID, 11L))
            .thenReturn(List.of());
        when(transactionRepository.sumByCategoryIdAndDateBetween(eq(11L), any(LocalDate.class), any(LocalDate.class)))
            .thenAnswer(invocation -> stored.stream()
                .filter(row -> row.getCategoryRef() != null && row.getCategoryRef().getId().equals(11L))
                .filter(row -> !row.getDate().isBefore(invocation.getArgument(1))
                    && !row.getDate().isAfter(invocation.getArgument(2)))
                .map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER_ID), any(LocalDate.class), any(LocalDate.class)))
            .thenAnswer(invocation -> stored.stream()
                .filter(row -> row.getAccount().getMember().getId().equals(MEMBER_ID))
                .filter(row -> !row.getDate().isBefore(invocation.getArgument(1))
                    && !row.getDate().isAfter(invocation.getArgument(2)))
                .toList());
        CashflowService cashflowService = new CashflowService(transactionRepository, budgetSettingsService);
        BudgetService budgetService = new BudgetService(budgetRepository, categoryRepository,
            transactionRepository, familyMemberRepository, budgetSettingsService);

        try (MockedStatic<LocalDate> dates = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
            dates.when(LocalDate::now).thenReturn(TODAY);
            BudgetResponse budget = budgetService.findAll(MEMBER_ID).getFirst();
            assertThat(budget.cycleStart()).isEqualTo(CYCLE_START);
            assertThat(budget.cycleEnd()).isEqualTo(CYCLE_END);
            assertThat(budget.spent()).isEqualByComparingTo("20.00");

            var cashflow = cashflowService.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY);
            assertThat(cashflow.expense()).isEqualByComparingTo("20.00");

            assertThat(importer.importFor(account, snapshot, context, "bourso")).isZero();
            assertThat(stored).hasSize(4);
            assertThat(budgetService.findAll(MEMBER_ID).getFirst().spent()).isEqualByComparingTo("20.00");
            assertThat(cashflowService.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY).expense())
                .isEqualByComparingTo("20.00");
        }
    }

    private static Category category(Long id, String name, CategoryKind kind) {
        return Category.builder().id(id).name(name).slug(name.toLowerCase().replace(' ', '-')).kind(kind).build();
    }

    private static CategorizationRule rule(String pattern, Category category) {
        return CategorizationRule.builder().matchType(RuleMatchType.KEYWORD).pattern(pattern)
            .category(category).build();
    }
}
