package com.picsou.mcp.tools;

import com.picsou.dto.BudgetRequest;
import com.picsou.dto.BudgetResponse;
import com.picsou.dto.CashflowFlowResponse;
import com.picsou.dto.CashflowPeriod;
import com.picsou.dto.CashflowResponse;
import com.picsou.dto.CategorizationRuleRequest;
import com.picsou.dto.CategorizationRuleResponse;
import com.picsou.dto.CategoryRequest;
import com.picsou.dto.CategoryResponse;
import com.picsou.dto.RecurringOccurrenceResponse;
import com.picsou.dto.RecurringSeriesResponse;
import com.picsou.dto.SpendingByCategoryResponse;
import com.picsou.dto.SpendingDetailResponse;
import com.picsou.dto.TransactionResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.CategoryKind;
import com.picsou.model.RecurringStatus;
import com.picsou.model.RuleMatchType;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.UserContext;
import com.picsou.service.budget.BudgetService;
import com.picsou.service.budget.CashflowFlowService;
import com.picsou.service.budget.CashflowService;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.budget.CategoryService;
import com.picsou.service.budget.RecurringDetectionService;
import com.picsou.service.budget.RecurringSeriesService;
import jakarta.validation.Validator;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every budget tool must resolve {@link UserContext#currentMemberId()} and delegate to the
 * already member-scoped budget services; member isolation is owned and tested by those services.
 */
@ExtendWith(MockitoExtension.class)
class BudgetToolsTest {

    private static final long MID = 7L;

    @Mock CategoryService categoryService;
    @Mock CategorizationService categorizationService;
    @Mock BudgetService budgetService;
    @Mock RecurringSeriesService recurringSeriesService;
    @Mock RecurringDetectionService recurringDetectionService;
    @Mock CashflowService cashflowService;
    @Mock CashflowFlowService cashflowFlowService;
    @Mock TransactionRepository transactionRepository;
    @Mock UserContext userContext;
    @Mock Validator validator;
    @InjectMocks BudgetTools tools;

    // ─── Categories ─────────────────────────────────────────────────────────

    @Test
    void listBudgetCategories_delegatesScopedToCurrentMember() {
        CategoryResponse c = mock(CategoryResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(categoryService.findAll(MID)).thenReturn(List.of(c));

        assertThat(tools.listBudgetCategories()).containsExactly(c);
    }

    @Test
    void getBudgetCategory_returnsMatchingCategoryFromTheMembersList() {
        CategoryResponse c1 = mock(CategoryResponse.class);
        CategoryResponse c2 = mock(CategoryResponse.class);
        when(c1.id()).thenReturn(1L);
        when(c2.id()).thenReturn(2L);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(categoryService.findAll(MID)).thenReturn(List.of(c1, c2));

        assertThat(tools.getBudgetCategory(2L)).isSameAs(c2);
    }

    @Test
    void getBudgetCategory_throwsWhenNotFoundAmongTheMembersCategories() {
        when(userContext.currentMemberId()).thenReturn(MID);
        when(categoryService.findAll(MID)).thenReturn(List.of());

        assertThatThrownBy(() -> tools.getBudgetCategory(404L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createBudgetCategory_buildsRequestAndDelegatesWithCurrentMember() {
        CategoryResponse created = mock(CategoryResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(categoryService.create(any(CategoryRequest.class), eq(MID))).thenReturn(created);

        CategoryResponse out = tools.createBudgetCategory("Loisirs", CategoryKind.EXPENSE, "#fff", "gamepad", null);

        assertThat(out).isSameAs(created);
        ArgumentCaptor<CategoryRequest> captor = ArgumentCaptor.forClass(CategoryRequest.class);
        verify(categoryService).create(captor.capture(), eq(MID));
        assertThat(captor.getValue().name()).isEqualTo("Loisirs");
        assertThat(captor.getValue().kind()).isEqualTo(CategoryKind.EXPENSE);
    }

    @Test
    void updateBudgetCategory_delegatesScopedToCurrentMember() {
        CategoryResponse updated = mock(CategoryResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(categoryService.update(eq(5L), any(CategoryRequest.class), eq(MID))).thenReturn(updated);

        assertThat(tools.updateBudgetCategory(5L, "Restaurants", CategoryKind.EXPENSE, null, null, null))
            .isSameAs(updated);
    }

    @Test
    void deleteBudgetCategory_archivesScopedToCurrentMember() {
        when(userContext.currentMemberId()).thenReturn(MID);

        tools.deleteBudgetCategory(5L);

        verify(categoryService).archive(5L, MID);
    }

    // ─── Rules ──────────────────────────────────────────────────────────────

    @Test
    void listBudgetRules_delegatesScopedToCurrentMember() {
        CategorizationRuleResponse r = mock(CategorizationRuleResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(categorizationService.findAllRules(MID)).thenReturn(List.of(r));

        assertThat(tools.listBudgetRules()).containsExactly(r);
    }

    @Test
    void getBudgetRule_returnsMatchingRule() {
        CategorizationRuleResponse r1 = mock(CategorizationRuleResponse.class);
        when(r1.id()).thenReturn(9L);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(categorizationService.findAllRules(MID)).thenReturn(List.of(r1));

        assertThat(tools.getBudgetRule(9L)).isSameAs(r1);
    }

    @Test
    void getBudgetRule_throwsWhenNotFound() {
        when(userContext.currentMemberId()).thenReturn(MID);
        when(categorizationService.findAllRules(MID)).thenReturn(List.of());

        assertThatThrownBy(() -> tools.getBudgetRule(404L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createBudgetRule_buildsRequestAndDelegates() {
        CategorizationRuleResponse created = mock(CategorizationRuleResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(categorizationService.createRule(any(CategorizationRuleRequest.class), eq(MID))).thenReturn(created);

        CategorizationRuleResponse out = tools.createBudgetRule(RuleMatchType.KEYWORD, "carrefour", 3L, 5);

        assertThat(out).isSameAs(created);
        ArgumentCaptor<CategorizationRuleRequest> captor = ArgumentCaptor.forClass(CategorizationRuleRequest.class);
        verify(categorizationService).createRule(captor.capture(), eq(MID));
        assertThat(captor.getValue().pattern()).isEqualTo("carrefour");
        assertThat(captor.getValue().categoryId()).isEqualTo(3L);
    }

    @Test
    void updateBudgetRule_delegatesScopedToCurrentMember() {
        CategorizationRuleResponse updated = mock(CategorizationRuleResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(categorizationService.updateRule(eq(9L), any(CategorizationRuleRequest.class), eq(MID)))
            .thenReturn(updated);

        assertThat(tools.updateBudgetRule(9L, RuleMatchType.COUNTERPARTY, "sncf", 4L, 1)).isSameAs(updated);
    }

    @Test
    void deleteBudgetRule_delegatesScopedToCurrentMember() {
        when(userContext.currentMemberId()).thenReturn(MID);

        tools.deleteBudgetRule(9L);

        verify(categorizationService).deleteRule(9L, MID);
    }

    @Test
    void applyRuleToTransactions_reportsHowManyWereCategorized() {
        when(userContext.currentMemberId()).thenReturn(MID);
        when(categorizationService.recategorizeUncategorized(MID)).thenReturn(4);

        assertThat(tools.applyRuleToTransactions()).contains("4");
    }

    // ─── Transactions ───────────────────────────────────────────────────────

    @Test
    void listBudgetTransactions_defaultsToSearchByMemberWithCategoryFilter() {
        when(userContext.currentMemberId()).thenReturn(MID);
        com.picsou.model.Transaction tx = mock(com.picsou.model.Transaction.class);
        com.picsou.model.Account account = mock(com.picsou.model.Account.class);
        when(tx.getAccount()).thenReturn(account);
        when(transactionRepository.searchByMember(MID, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null, 3L))
            .thenReturn(List.of(tx));

        List<TransactionResponse> out = tools.listBudgetTransactions(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 3L, false);

        assertThat(out).hasSize(1);
        verify(categorizationService, never()).findUncategorized(anyLong());
    }

    @Test
    void listBudgetTransactions_uncategorizedOnly_filtersByDateAndSkipsCategorySearch() {
        when(userContext.currentMemberId()).thenReturn(MID);
        TransactionResponse inRange = transactionResponseOn(LocalDate.of(2026, 1, 15));
        TransactionResponse outOfRange = transactionResponseOn(LocalDate.of(2026, 3, 1));
        when(categorizationService.findUncategorized(MID)).thenReturn(List.of(inRange, outOfRange));

        List<TransactionResponse> out = tools.listBudgetTransactions(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null, true);

        assertThat(out).containsExactly(inRange);
        verify(transactionRepository, never()).searchByMember(anyLong(), any(), any(), any(), any());
    }

    @Test
    void updateBudgetTransaction_delegatesToCategorize() {
        when(userContext.currentMemberId()).thenReturn(MID);

        String out = tools.updateBudgetTransaction(11L, 3L, true);

        assertThat(out).contains("11");
        verify(categorizationService).categorize(11L, 3L, true, null, null, List.of(), MID);
    }

    // ─── Recurring ──────────────────────────────────────────────────────────

    @Test
    void listRecurringSeries_delegatesWithStatusFilter() {
        RecurringSeriesResponse s = mock(RecurringSeriesResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(recurringSeriesService.findAll(eq(MID), eq(RecurringStatus.CONFIRMED), any(LocalDate.class)))
            .thenReturn(List.of(s));

        assertThat(tools.listRecurringSeries(RecurringStatus.CONFIRMED)).containsExactly(s);
    }

    @Test
    void getRecurringSeries_returnsMatchingSeries() {
        RecurringSeriesResponse s1 = mock(RecurringSeriesResponse.class);
        when(s1.id()).thenReturn(21L);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(recurringSeriesService.findAll(eq(MID), isNull(), any(LocalDate.class))).thenReturn(List.of(s1));

        assertThat(tools.getRecurringSeries(21L)).isSameAs(s1);
    }

    @Test
    void getRecurringSeries_throwsWhenNotFound() {
        when(userContext.currentMemberId()).thenReturn(MID);
        when(recurringSeriesService.findAll(eq(MID), isNull(), any(LocalDate.class))).thenReturn(List.of());

        assertThatThrownBy(() -> tools.getRecurringSeries(404L)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ─── Envelopes ──────────────────────────────────────────────────────────

    @Test
    void listBudgetEnvelopes_delegatesScopedToCurrentMember() {
        BudgetResponse b = mock(BudgetResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(budgetService.findAll(MID)).thenReturn(List.of(b));

        assertThat(tools.listBudgetEnvelopes()).containsExactly(b);
    }

    @Test
    void getBudgetEnvelope_returnsMatchingEnvelope() {
        BudgetResponse b1 = mock(BudgetResponse.class);
        when(b1.id()).thenReturn(31L);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(budgetService.findAll(MID)).thenReturn(List.of(b1));

        assertThat(tools.getBudgetEnvelope(31L)).isSameAs(b1);
    }

    @Test
    void createBudgetEnvelope_delegatesWithMonthlyLimit() {
        BudgetResponse created = mock(BudgetResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(budgetService.create(eq(new BudgetRequest(3L, new BigDecimal("200"))), eq(MID))).thenReturn(created);

        assertThat(tools.createBudgetEnvelope(3L, new BigDecimal("200"))).isSameAs(created);
    }

    @Test
    void updateBudgetEnvelope_delegatesScopedToCurrentMember() {
        BudgetResponse updated = mock(BudgetResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(budgetService.update(eq(31L), eq(new BudgetRequest(3L, new BigDecimal("250"))), eq(MID)))
            .thenReturn(updated);

        assertThat(tools.updateBudgetEnvelope(31L, 3L, new BigDecimal("250"))).isSameAs(updated);
    }

    @Test
    void deleteBudgetEnvelope_delegatesScopedToCurrentMember() {
        when(userContext.currentMemberId()).thenReturn(MID);

        tools.deleteBudgetEnvelope(31L);

        verify(budgetService).delete(31L, MID);
    }

    @Test
    void setEnvelopeAllocation_keepsCurrentCategoryAndChangesOnlyTheLimit() {
        BudgetResponse current = mock(BudgetResponse.class);
        when(current.id()).thenReturn(31L);
        when(current.categoryId()).thenReturn(3L);
        BudgetResponse updated = mock(BudgetResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(budgetService.findAll(MID)).thenReturn(List.of(current));
        when(budgetService.update(eq(31L), eq(new BudgetRequest(3L, new BigDecimal("300"))), eq(MID)))
            .thenReturn(updated);

        assertThat(tools.setEnvelopeAllocation(31L, new BigDecimal("300"))).isSameAs(updated);
    }

    // ─── Dashboard ──────────────────────────────────────────────────────────

    @Test
    void getBudgetDashboard_composesCashflowTopCategoriesAndUpcomingSubscriptions() {
        when(userContext.currentMemberId()).thenReturn(MID);
        CashflowResponse cashflow = mock(CashflowResponse.class);
        when(cashflowService.compute(eq(MID), eq(CashflowPeriod.CYCLE), any(LocalDate.class))).thenReturn(cashflow);

        SpendingByCategoryResponse.CategorySpend small = categorySpend(1L, new BigDecimal("10"));
        SpendingByCategoryResponse.CategorySpend big = categorySpend(2L, new BigDecimal("500"));
        SpendingByCategoryResponse spending = mock(SpendingByCategoryResponse.class);
        when(spending.categories()).thenReturn(List.of(small, big));
        when(cashflowFlowService.spendingByCategory(eq(MID), eq(CashflowPeriod.CYCLE), any(LocalDate.class)))
            .thenReturn(spending);

        RecurringOccurrenceResponse occurrence = mock(RecurringOccurrenceResponse.class);
        when(recurringSeriesService.upcoming(eq(MID), any(LocalDate.class), eq(30))).thenReturn(List.of(occurrence));

        BudgetTools.BudgetDashboard dashboard = tools.getBudgetDashboard();

        assertThat(dashboard.cashflow()).isSameAs(cashflow);
        assertThat(dashboard.topCategories()).containsExactly(big, small); // ranked highest amount first
        assertThat(dashboard.upcomingSubscriptions()).containsExactly(occurrence);
    }

    // ─── Spending / cashflow ────────────────────────────────────────────────

    @Test
    void getSpendingByCategory_defaultsToTheCycleContainingToday() {
        SpendingByCategoryResponse spending = mock(SpendingByCategoryResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(cashflowFlowService.spendingByCategory(MID, CashflowPeriod.CYCLE, LocalDate.now())).thenReturn(spending);

        assertThat(tools.getSpendingByCategory(null, null)).isSameAs(spending);
    }

    @Test
    void getSpendingCategoryDetail_passesCategoryPeriodAndAnchor() {
        SpendingDetailResponse detail = mock(SpendingDetailResponse.class);
        LocalDate anchor = LocalDate.of(2026, 3, 15);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(cashflowFlowService.categoryDetail(MID, 3L, CashflowPeriod.YTD, anchor)).thenReturn(detail);

        assertThat(tools.getSpendingCategoryDetail(3L, CashflowPeriod.YTD, anchor)).isSameAs(detail);
    }

    @Test
    void getCashflow_passesPeriodAndAnchor() {
        CashflowResponse cashflow = mock(CashflowResponse.class);
        LocalDate anchor = LocalDate.of(2026, 3, 15);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(cashflowService.compute(MID, CashflowPeriod.YTD, anchor)).thenReturn(cashflow);

        assertThat(tools.getCashflow(CashflowPeriod.YTD, anchor)).isSameAs(cashflow);
    }

    @Test
    void getCashflowFlow_defaultsToTheCycleContainingToday() {
        CashflowFlowResponse flow = mock(CashflowFlowResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(cashflowFlowService.flow(MID, CashflowPeriod.CYCLE, LocalDate.now())).thenReturn(flow);

        assertThat(tools.getCashflowFlow(null, null)).isSameAs(flow);
    }

    private static SpendingByCategoryResponse.CategorySpend categorySpend(long categoryId, BigDecimal amount) {
        return new SpendingByCategoryResponse.CategorySpend(
            categoryId, "slug" + categoryId, "Cat " + categoryId, "#fff", "icon", amount, 1,
            BigDecimal.ONE, null, null, null);
    }

    private static TransactionResponse transactionResponseOn(LocalDate date) {
        return new TransactionResponse(
            1L, date, "desc", BigDecimal.TEN, "EXPENSE", null, "EUR",
            java.time.Instant.now(), false, null, null, null, null, null,
            null, null, null, null, null, null, null, 1L, "Account", null);
    }
}
