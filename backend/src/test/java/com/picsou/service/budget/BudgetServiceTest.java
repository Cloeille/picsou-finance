package com.picsou.service.budget;

import com.picsou.dto.BudgetRequest;
import com.picsou.dto.BudgetResponse;
import com.picsou.model.Budget;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.repository.BudgetRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

    @Mock BudgetRepository budgetRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock BudgetSettingsService budgetSettingsService;

    @InjectMocks BudgetService service;

    private static final Long MEMBER_ID = 10L;

    private Budget budget(Long categoryId, String limit) {
        Category category = Category.builder()
            .id(categoryId).kind(CategoryKind.EXPENSE).name("Courses").color("#22c55e").build();
        return Budget.builder().id(1L).category(category).monthlyLimit(new BigDecimal(limit)).build();
    }

    @Test
    void findAll_computesSpentFromNegativeOutflow_underBudget() {
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(budgetRepository.findAllByMemberIdOrderByIdAsc(MEMBER_ID))
            .thenReturn(List.of(budget(5L, "200.00")));
        // Leaf category (no children) → envelope scores its own category, not a subtree rollup.
        when(categoryRepository.findAllByMemberIdAndParentIdOrderBySortOrderAscIdAsc(MEMBER_ID, 5L))
            .thenReturn(List.of());
        // Expenses are stored negative — €150 spent shows up as -150.
        when(transactionRepository.sumByCategoryIdAndDateBetween(eq(5L), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(new BigDecimal("-150.00"));

        List<BudgetResponse> result = service.findAll(MEMBER_ID);

        assertThat(result).hasSize(1);
        BudgetResponse r = result.get(0);
        assertThat(r.spent()).isEqualByComparingTo("150.00");
        assertThat(r.remaining()).isEqualByComparingTo("50.00");
        assertThat(r.percent()).isEqualByComparingTo("75.00");
        assertThat(r.overBudget()).isFalse();
        assertThat(r.rollup()).isFalse(); // leaf envelope, not a subtree
    }

    @Test
    void findAll_flagsOverBudget_whenSpentExceedsLimit() {
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(budgetRepository.findAllByMemberIdOrderByIdAsc(MEMBER_ID))
            .thenReturn(List.of(budget(5L, "200.00")));
        // Leaf category (no children) → envelope scores its own category, not a subtree rollup.
        when(categoryRepository.findAllByMemberIdAndParentIdOrderBySortOrderAscIdAsc(MEMBER_ID, 5L))
            .thenReturn(List.of());
        when(transactionRepository.sumByCategoryIdAndDateBetween(eq(5L), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(new BigDecimal("-250.00"));

        BudgetResponse r = service.findAll(MEMBER_ID).get(0);

        assertThat(r.spent()).isEqualByComparingTo("250.00");
        assertThat(r.remaining()).isEqualByComparingTo("-50.00");
        assertThat(r.overBudget()).isTrue();
    }

    @Test
    void findAll_refundReducesSpent() {
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(budgetRepository.findAllByMemberIdOrderByIdAsc(MEMBER_ID))
            .thenReturn(List.of(budget(5L, "200.00")));
        // Leaf category (no children) → envelope scores its own category, not a subtree rollup.
        when(categoryRepository.findAllByMemberIdAndParentIdOrderBySortOrderAscIdAsc(MEMBER_ID, 5L))
            .thenReturn(List.of());
        // −120 spent then +20 refund nets to −100 → €100 spent.
        when(transactionRepository.sumByCategoryIdAndDateBetween(eq(5L), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(new BigDecimal("-100.00"));

        BudgetResponse r = service.findAll(MEMBER_ID).get(0);

        assertThat(r.spent()).isEqualByComparingTo("100.00");
        assertThat(r.remaining()).isEqualByComparingTo("100.00");
    }

    @Test
    void findAll_rollsUpSubtreeSpend_forParentEnvelope() {
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(budgetRepository.findAllByMemberIdOrderByIdAsc(MEMBER_ID))
            .thenReturn(List.of(budget(7L, "1000.00")));
        // Category 7 is a parent with two children → envelope rolls up the whole subtree.
        Category child1 = Category.builder().id(5L).kind(CategoryKind.EXPENSE).name("Courses").color("#22c55e").build();
        Category child2 = Category.builder().id(6L).kind(CategoryKind.EXPENSE).name("Loyer").color("#22c55e").build();
        when(categoryRepository.findAllByMemberIdAndParentIdOrderBySortOrderAscIdAsc(MEMBER_ID, 7L))
            .thenReturn(List.of(child1, child2));
        // Subtree sum (parent's own + both children), stored negative.
        when(transactionRepository.sumByCategoryIdInAndDateBetween(any(), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(new BigDecimal("-800.00"));

        BudgetResponse r = service.findAll(MEMBER_ID).get(0);

        assertThat(r.rollup()).isTrue();
        assertThat(r.spent()).isEqualByComparingTo("800.00");
        assertThat(r.remaining()).isEqualByComparingTo("200.00");
        assertThat(r.overBudget()).isFalse();
    }

    @Test
    void create_rejects_whenParentAlreadyBudgeted() {
        Category parent = Category.builder()
            .id(7L).kind(CategoryKind.EXPENSE).name("Maison").color("#22c55e").build();
        Category child = Category.builder()
            .id(5L).kind(CategoryKind.EXPENSE).name("Courses").color("#22c55e").parent(parent).build();
        when(categoryRepository.findByIdAndMemberId(5L, MEMBER_ID)).thenReturn(Optional.of(child));
        when(budgetRepository.existsByMemberIdAndCategoryId(MEMBER_ID, 5L)).thenReturn(false);
        when(budgetRepository.existsByMemberIdAndCategoryId(MEMBER_ID, 7L)).thenReturn(true);

        assertThatThrownBy(() -> service.create(new BudgetRequest(5L, new BigDecimal("100.00")), MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("parent already has a budget");
    }

    @Test
    void create_rejects_whenChildAlreadyBudgeted() {
        Category parent = Category.builder()
            .id(7L).kind(CategoryKind.EXPENSE).name("Maison").color("#22c55e").build();
        Category child = Category.builder()
            .id(5L).kind(CategoryKind.EXPENSE).name("Courses").color("#22c55e").parent(parent).build();
        when(categoryRepository.findByIdAndMemberId(7L, MEMBER_ID)).thenReturn(Optional.of(parent));
        when(budgetRepository.existsByMemberIdAndCategoryId(MEMBER_ID, 7L)).thenReturn(false);
        when(categoryRepository.findAllByMemberIdAndParentIdOrderBySortOrderAscIdAsc(MEMBER_ID, 7L))
            .thenReturn(List.of(child));
        when(budgetRepository.existsByMemberIdAndCategoryId(MEMBER_ID, 5L)).thenReturn(true);

        assertThatThrownBy(() -> service.create(new BudgetRequest(7L, new BigDecimal("500.00")), MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sub-category of this category already has a budget");
    }
}
