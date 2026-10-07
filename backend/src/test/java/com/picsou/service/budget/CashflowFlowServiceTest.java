package com.picsou.service.budget;

import com.picsou.dto.CashflowFlowResponse;
import com.picsou.dto.CashflowFlowResponse.FlowLink;
import com.picsou.dto.CashflowFlowResponse.FlowNode;
import com.picsou.dto.CashflowFlowResponse.NodeType;
import com.picsou.dto.CashflowPeriod;
import com.picsou.dto.SpendingByCategoryResponse;
import com.picsou.dto.SpendingDetailResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.Transaction;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashflowFlowServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock BudgetSettingsService budgetSettingsService;
    @Mock CategoryRepository categoryRepository;

    private CashflowFlowService service;

    private static final Long MEMBER = 10L;
    private static final LocalDate TODAY = LocalDate.of(2025, 3, 15);

    @BeforeEach
    void setUp() {
        service = new CashflowFlowService(transactionRepository, budgetSettingsService, categoryRepository);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static Category cat(long id, String name, CategoryKind kind) {
        return Category.builder().id(id).name(name).color("#123456").kind(kind).slug(name.toLowerCase()).build();
    }

    /** A sub-category: inherits its parent's kind, carries the back-reference for rollup tests. */
    private static Category childOf(long id, String name, Category parent) {
        return Category.builder().id(id).name(name).color("#123456").kind(parent.getKind())
            .slug(name.toLowerCase()).parent(parent).build();
    }

    private static Transaction tx(String amount, Category category) {
        // account is @ManyToOne(optional = false); TransactionResponse.from() dereferences it.
        return Transaction.builder().amount(bd(amount)).categoryRef(category)
            .account(Account.builder().id(1L).name("Compte").build())
            .date(LocalDate.of(2025, 3, 10)).build();
    }

    private void givenTransactions(List<Transaction> txns) {
        when(budgetSettingsService.cycleStartDay(MEMBER)).thenReturn(1);
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER), any(), any())).thenReturn(txns);
    }

    private static FlowNode node(CashflowFlowResponse flow, int index) {
        return flow.nodes().get(index);
    }

    // ─── Sankey flow ───────────────────────────────────────────────────────────

    @Test
    void flow_buildsBalancedIncomeHubExpenseGraph_withSavingsSink() {
        Category salaire = cat(1, "Salaire", CategoryKind.INCOME);
        Category courses = cat(2, "Courses", CategoryKind.EXPENSE);
        givenTransactions(List.of(tx("3000", salaire), tx("-1000", courses)));

        CashflowFlowResponse flow = service.flow(MEMBER, CashflowPeriod.CYCLE, TODAY);

        assertThat(flow.income()).isEqualByComparingTo("3000");
        assertThat(flow.expense()).isEqualByComparingTo("1000");
        assertThat(flow.net()).isEqualByComparingTo("2000");

        // One hub, an income source, an expense sink and a savings sink.
        int hubIndex = hubIndex(flow);
        assertThat(flow.nodes()).anyMatch(n -> n.type() == NodeType.SAVINGS);
        assertThat(flow.nodes()).noneMatch(n -> "__drawdown__".equals(n.key()));

        // Conservation: total into the hub equals total out of it.
        assertThat(intoHub(flow, hubIndex)).isEqualByComparingTo(outOfHub(flow, hubIndex));
        assertThat(intoHub(flow, hubIndex)).isEqualByComparingTo("3000");
    }

    @Test
    void flow_addsDrawdownSource_whenOverspending() {
        Category salaire = cat(1, "Salaire", CategoryKind.INCOME);
        Category courses = cat(2, "Courses", CategoryKind.EXPENSE);
        givenTransactions(List.of(tx("1000", salaire), tx("-1500", courses)));

        CashflowFlowResponse flow = service.flow(MEMBER, CashflowPeriod.CYCLE, TODAY);

        assertThat(flow.net()).isEqualByComparingTo("-500");
        assertThat(flow.nodes()).anyMatch(n -> "__drawdown__".equals(n.key()) && n.type() == NodeType.INCOME);
        assertThat(flow.nodes()).noneMatch(n -> n.type() == NodeType.SAVINGS);

        int hubIndex = hubIndex(flow);
        assertThat(intoHub(flow, hubIndex)).isEqualByComparingTo(outOfHub(flow, hubIndex));
        assertThat(intoHub(flow, hubIndex)).isEqualByComparingTo("1500");
    }

    @Test
    void flow_excludesTransfers() {
        Category salaire = cat(1, "Salaire", CategoryKind.INCOME);
        Category courses = cat(2, "Courses", CategoryKind.EXPENSE);
        Category virement = cat(3, "Virement", CategoryKind.TRANSFER);
        givenTransactions(List.of(
            tx("2000", salaire), tx("-500", courses),
            tx("-900", virement), tx("900", virement)
        ));

        CashflowFlowResponse flow = service.flow(MEMBER, CashflowPeriod.CYCLE, TODAY);

        assertThat(flow.income()).isEqualByComparingTo("2000");
        assertThat(flow.expense()).isEqualByComparingTo("500");
        assertThat(flow.nodes()).noneMatch(n -> "cat:3".equals(n.key()));
    }

    @Test
    void flow_isEmpty_whenNoSpendableTransactions() {
        givenTransactions(List.of());

        CashflowFlowResponse flow = service.flow(MEMBER, CashflowPeriod.CYCLE, TODAY);

        assertThat(flow.nodes()).isEmpty();
        assertThat(flow.links()).isEmpty();
        assertThat(flow.income()).isEqualByComparingTo("0");
        assertThat(flow.expense()).isEqualByComparingTo("0");
    }

    @Test
    void flow_capsExpenseNodes_rollingTheTailIntoOther() {
        // 10 distinct expense categories (amounts 100..1000) with no income.
        List<Transaction> txns = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            txns.add(tx("-" + (i * 100), cat(i, "Cat" + i, CategoryKind.EXPENSE)));
        }
        givenTransactions(txns);

        CashflowFlowResponse flow = service.flow(MEMBER, CashflowPeriod.CYCLE, TODAY);

        long expenseNodes = flow.nodes().stream().filter(n -> n.type() == NodeType.EXPENSE).count();
        assertThat(expenseNodes).isEqualTo(8); // 7 largest + 1 rollup
        // Rollup gathers the three smallest (100 + 200 + 300 = 600).
        FlowNode rollup = flow.nodes().stream()
            .filter(n -> "__expense_more__".equals(n.key())).findFirst().orElseThrow();
        int hubIndex = hubIndex(flow);
        BigDecimal rollupValue = flow.links().stream()
            .filter(l -> l.target() == flow.nodes().indexOf(rollup))
            .map(FlowLink::value).findFirst().orElseThrow();
        assertThat(rollupValue).isEqualByComparingTo("600");
        // Still balanced overall (income 0 → a drawdown source funds all spending).
        assertThat(intoHub(flow, hubIndex)).isEqualByComparingTo(outOfHub(flow, hubIndex));
    }

    // ─── Ranked breakdown ──────────────────────────────────────────────────────

    @Test
    void spendingByCategory_ranksDescending_withSharesAndUncategorized() {
        Category courses = cat(2, "Courses", CategoryKind.EXPENSE);
        Category transport = cat(4, "Transport", CategoryKind.EXPENSE);
        givenTransactions(List.of(
            tx("-600", courses), tx("-300", transport), tx("-100", null), tx("2000", null)
        ));

        SpendingByCategoryResponse resp = service.spendingByCategory(MEMBER, CashflowPeriod.CYCLE, TODAY);

        assertThat(resp.totalExpense()).isEqualByComparingTo("1000");
        assertThat(resp.categories()).extracting(SpendingByCategoryResponse.CategorySpend::amount)
            .containsExactly(bd("600"), bd("300"), bd("100")); // sorted desc
        assertThat(resp.categories().get(0).share()).isEqualByComparingTo("0.6000");
        // The null-category bucket is spending with no category yet.
        assertThat(resp.categories().get(2).categoryId()).isNull();
    }

    @Test
    void spendingByCategory_annotatesEachLeafRowWithItsParent() {
        // Aggregation stays leaf-based; each row just carries its parent so the client can group.
        Category maison = cat(7, "Maison", CategoryKind.EXPENSE);
        Category courses = childOf(2, "Courses", maison);
        givenTransactions(List.of(tx("-600", courses)));

        SpendingByCategoryResponse resp = service.spendingByCategory(MEMBER, CashflowPeriod.CYCLE, TODAY);

        SpendingByCategoryResponse.CategorySpend row = resp.categories().get(0);
        assertThat(row.categoryId()).isEqualTo(2L);
        assertThat(row.parentId()).isEqualTo(7L);
        assertThat(row.parentName()).isEqualTo("Maison");
        assertThat(row.parentColor()).isEqualTo("#123456");
    }

    @Test
    void spendingByCategory_leavesParentFieldsNull_forRootAndUncategorized() {
        Category transport = cat(4, "Transport", CategoryKind.EXPENSE); // root: no parent
        givenTransactions(List.of(tx("-300", transport), tx("-100", null)));

        SpendingByCategoryResponse resp = service.spendingByCategory(MEMBER, CashflowPeriod.CYCLE, TODAY);

        assertThat(resp.categories().get(0).parentId()).isNull(); // root category
        assertThat(resp.categories().get(1).parentId()).isNull(); // uncategorized bucket
    }

    // ─── Category drill ──────────────────────────────────────────────────────

    @Test
    void categoryDetail_sumsAndListsCategoryTransactions() {
        Category courses = cat(2, "Courses", CategoryKind.EXPENSE);
        when(categoryRepository.findByIdAndMemberId(2L, MEMBER)).thenReturn(Optional.of(courses));
        when(budgetSettingsService.cycleStartDay(MEMBER)).thenReturn(1);
        // Leaf category: no children, so the drill spans only this one category's transactions.
        when(categoryRepository.findAllByMemberIdAndParentIdOrderBySortOrderAscIdAsc(MEMBER, 2L))
            .thenReturn(List.of());
        when(transactionRepository.findByMemberIdAndCategoryIdInAndDateBetween(eq(MEMBER), any(), any(), any()))
            .thenReturn(List.of(tx("-67.80", courses), tx("-45.30", courses)));

        SpendingDetailResponse resp = service.categoryDetail(MEMBER, 2L, CashflowPeriod.CYCLE, TODAY);

        assertThat(resp.name()).isEqualTo("Courses");
        assertThat(resp.slug()).isEqualTo("courses");
        assertThat(resp.count()).isEqualTo(2);
        assertThat(resp.total()).isEqualByComparingTo("-113.10");
        assertThat(resp.transactions()).hasSize(2);
        assertThat(resp.children()).isEmpty(); // a leaf drill has no per-child rollup
    }

    @Test
    void categoryDetail_rollsUpChildren_whenDrillingParent() {
        Category maison = cat(7, "Maison", CategoryKind.EXPENSE);
        Category courses = childOf(2, "Courses", maison);
        Category loyer = childOf(3, "Loyer", maison);
        when(categoryRepository.findByIdAndMemberId(7L, MEMBER)).thenReturn(Optional.of(maison));
        when(budgetSettingsService.cycleStartDay(MEMBER)).thenReturn(1);
        when(categoryRepository.findAllByMemberIdAndParentIdOrderBySortOrderAscIdAsc(MEMBER, 7L))
            .thenReturn(List.of(courses, loyer));
        // The subtree query returns the parent's own spend plus each child's, newest first.
        when(transactionRepository.findByMemberIdAndCategoryIdInAndDateBetween(eq(MEMBER), any(), any(), any()))
            .thenReturn(List.of(
                tx("-50", maison), tx("-600", courses), tx("-400", courses), tx("-900", loyer)
            ));

        SpendingDetailResponse resp = service.categoryDetail(MEMBER, 7L, CashflowPeriod.CYCLE, TODAY);

        assertThat(resp.name()).isEqualTo("Maison");
        assertThat(resp.count()).isEqualTo(4);
        // Subtree total spans every listed transaction, including the parent's own €50.
        assertThat(resp.total()).isEqualByComparingTo("-1950");
        // Per-child rollup, in the children's sort order; the parent's direct spend is not a child row.
        assertThat(resp.children()).hasSize(2);
        assertThat(resp.children().get(0).categoryId()).isEqualTo(2L);
        assertThat(resp.children().get(0).total()).isEqualByComparingTo("-1000");
        assertThat(resp.children().get(0).count()).isEqualTo(2);
        assertThat(resp.children().get(1).categoryId()).isEqualTo(3L);
        assertThat(resp.children().get(1).total()).isEqualByComparingTo("-900");
        assertThat(resp.children().get(1).count()).isEqualTo(1);
    }

    @Test
    void categoryDetail_listsChildWithZeroSpend_whenNoTransactions() {
        // A child with no transactions this cycle still appears in the rollup, at zero.
        Category maison = cat(7, "Maison", CategoryKind.EXPENSE);
        Category courses = childOf(2, "Courses", maison);
        Category loyer = childOf(3, "Loyer", maison);
        when(categoryRepository.findByIdAndMemberId(7L, MEMBER)).thenReturn(Optional.of(maison));
        when(budgetSettingsService.cycleStartDay(MEMBER)).thenReturn(1);
        when(categoryRepository.findAllByMemberIdAndParentIdOrderBySortOrderAscIdAsc(MEMBER, 7L))
            .thenReturn(List.of(courses, loyer));
        when(transactionRepository.findByMemberIdAndCategoryIdInAndDateBetween(eq(MEMBER), any(), any(), any()))
            .thenReturn(List.of(tx("-600", courses)));

        SpendingDetailResponse resp = service.categoryDetail(MEMBER, 7L, CashflowPeriod.CYCLE, TODAY);

        assertThat(resp.children()).hasSize(2);
        assertThat(resp.children().get(1).categoryId()).isEqualTo(3L);
        assertThat(resp.children().get(1).total()).isEqualByComparingTo("0");
        assertThat(resp.children().get(1).count()).isEqualTo(0);
    }

    @Test
    void categoryDetail_throws_whenCategoryMissing() {
        when(categoryRepository.findByIdAndMemberId(99L, MEMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.categoryDetail(MEMBER, 99L, CashflowPeriod.CYCLE, TODAY))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ─── Anchor date forwarding ──────────────────────────────────────────────

    @Test
    void flow_withPastAnchor_windowsOnPastCycle() {
        LocalDate anchor = LocalDate.of(2024, 3, 10);
        when(budgetSettingsService.cycleStartDay(MEMBER)).thenReturn(1);
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER), any(), any()))
            .thenReturn(List.of());

        CashflowFlowResponse flow = service.flow(MEMBER, CashflowPeriod.CYCLE, anchor);

        assertThat(flow.nodes()).isEmpty();
        assertThat(flow.links()).isEmpty();
        // cycleStartDay=1, anchor=2024-03-10 → March-2024 cycle: 2024-03-01..2024-03-31
        verify(transactionRepository).findByMemberIdAndDateBetween(
            eq(MEMBER), eq(LocalDate.of(2024, 3, 1)), eq(LocalDate.of(2024, 3, 31)));
    }

    @Test
    void spendingByCategory_withPastCycleAnchor_windowsOnPastCycle() {
        LocalDate anchor = LocalDate.of(2024, 3, 10);
        when(budgetSettingsService.cycleStartDay(MEMBER)).thenReturn(1);
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER), any(), any()))
            .thenReturn(List.of());

        service.spendingByCategory(MEMBER, CashflowPeriod.CYCLE, anchor);

        // cycleStartDay=1, anchor=2024-03-10 → 2024-03-01..2024-03-31 (not current cycle)
        verify(transactionRepository).findByMemberIdAndDateBetween(
            eq(MEMBER), eq(LocalDate.of(2024, 3, 1)), eq(LocalDate.of(2024, 3, 31)));
    }

    @Test
    void spendingByCategory_withYtdAnchor_windowsOnCalendarYear() {
        LocalDate anchor = LocalDate.of(2024, 12, 31);
        when(budgetSettingsService.cycleStartDay(MEMBER)).thenReturn(1);
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER), any(), any()))
            .thenReturn(List.of());

        service.spendingByCategory(MEMBER, CashflowPeriod.YTD, anchor);

        // YTD with anchor 2024-12-31 → full calendar year 2024-01-01..2024-12-31
        verify(transactionRepository).findByMemberIdAndDateBetween(
            eq(MEMBER), eq(LocalDate.of(2024, 1, 1)), eq(LocalDate.of(2024, 12, 31)));
    }

    @Test
    void categoryDetail_withPastCycleAnchor_windowsOnPastCycle() {
        LocalDate anchor = LocalDate.of(2024, 3, 10);
        Category courses = cat(2, "Courses", CategoryKind.EXPENSE);
        when(categoryRepository.findByIdAndMemberId(2L, MEMBER)).thenReturn(Optional.of(courses));
        when(budgetSettingsService.cycleStartDay(MEMBER)).thenReturn(1);
        when(categoryRepository.findAllByMemberIdAndParentIdOrderBySortOrderAscIdAsc(MEMBER, 2L))
            .thenReturn(List.of());
        when(transactionRepository.findByMemberIdAndCategoryIdInAndDateBetween(eq(MEMBER), any(), any(), any()))
            .thenReturn(List.of());

        service.categoryDetail(MEMBER, 2L, CashflowPeriod.CYCLE, anchor);

        // cycleStartDay=1, anchor=2024-03-10 → 2024-03-01..2024-03-31 (not current cycle)
        verify(transactionRepository).findByMemberIdAndCategoryIdInAndDateBetween(
            eq(MEMBER), any(), eq(LocalDate.of(2024, 3, 1)), eq(LocalDate.of(2024, 3, 31)));
    }

    @Test
    void categoryDetail_withYtdAnchor_windowsOnCalendarYear() {
        LocalDate anchor = LocalDate.of(2024, 12, 31);
        Category courses = cat(2, "Courses", CategoryKind.EXPENSE);
        when(categoryRepository.findByIdAndMemberId(2L, MEMBER)).thenReturn(Optional.of(courses));
        when(budgetSettingsService.cycleStartDay(MEMBER)).thenReturn(1);
        when(categoryRepository.findAllByMemberIdAndParentIdOrderBySortOrderAscIdAsc(MEMBER, 2L))
            .thenReturn(List.of());
        when(transactionRepository.findByMemberIdAndCategoryIdInAndDateBetween(eq(MEMBER), any(), any(), any()))
            .thenReturn(List.of());

        service.categoryDetail(MEMBER, 2L, CashflowPeriod.YTD, anchor);

        // YTD with anchor 2024-12-31 → full calendar year 2024-01-01..2024-12-31
        verify(transactionRepository).findByMemberIdAndCategoryIdInAndDateBetween(
            eq(MEMBER), any(), eq(LocalDate.of(2024, 1, 1)), eq(LocalDate.of(2024, 12, 31)));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static int hubIndex(CashflowFlowResponse flow) {
        for (int i = 0; i < flow.nodes().size(); i++) {
            if (node(flow, i).type() == NodeType.HUB) {
                return i;
            }
        }
        throw new AssertionError("no hub node");
    }

    private static BigDecimal intoHub(CashflowFlowResponse flow, int hubIndex) {
        return flow.links().stream().filter(l -> l.target() == hubIndex)
            .map(FlowLink::value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal outOfHub(CashflowFlowResponse flow, int hubIndex) {
        return flow.links().stream().filter(l -> l.source() == hubIndex)
            .map(FlowLink::value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
