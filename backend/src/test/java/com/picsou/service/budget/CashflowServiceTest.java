package com.picsou.service.budget;

import com.picsou.dto.CashflowPeriod;
import com.picsou.dto.CashflowResponse;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.Transaction;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashflowServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock BudgetSettingsService budgetSettingsService;

    @InjectMocks CashflowService service;

    private static final Long MEMBER_ID = 3L;
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    private static Transaction tx(LocalDate date, String amount, CategoryKind kind) {
        Category cat = kind == null ? null
            : Category.builder().id(1L).kind(kind).name(kind.name()).build();
        return Transaction.builder()
            .id(date.toEpochDay())
            .date(date)
            .amount(new BigDecimal(amount))
            .categoryRef(cat)
            .description("x")
            .build();
    }

    @Test
    void compute_cycle_sumsIncomeAndExpenseBySign() {
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER_ID), any(), any()))
            .thenReturn(List.of(
                tx(LocalDate.of(2026, 6, 2), "2500.00", CategoryKind.INCOME),
                tx(LocalDate.of(2026, 6, 5), "-80.00", CategoryKind.EXPENSE),
                tx(LocalDate.of(2026, 6, 9), "-120.00", null) // uncategorized still counts
            ));

        CashflowResponse r = service.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY);

        assertThat(r.income()).isEqualByComparingTo("2500.00");
        assertThat(r.expense()).isEqualByComparingTo("200.00");
        assertThat(r.net()).isEqualByComparingTo("2300.00");
        assertThat(r.from()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(r.to()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(r.series()).hasSize(1);
    }

    @Test
    void compute_excludesTransfers() {
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER_ID), any(), any()))
            .thenReturn(List.of(
                tx(LocalDate.of(2026, 6, 2), "2500.00", CategoryKind.INCOME),
                // Move to savings: an outflow here + inflow elsewhere, both TRANSFER → ignored.
                tx(LocalDate.of(2026, 6, 3), "-500.00", CategoryKind.TRANSFER),
                tx(LocalDate.of(2026, 6, 4), "500.00", CategoryKind.TRANSFER)
            ));

        CashflowResponse r = service.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY);

        assertThat(r.income()).isEqualByComparingTo("2500.00");
        assertThat(r.expense()).isEqualByComparingTo("0.00");
        assertThat(r.net()).isEqualByComparingTo("2500.00");
    }

    @Test
    void compute_cycle_withPastAnchor_returnsPastCycleRange() {
        LocalDate anchor = LocalDate.of(2024, 3, 10);
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER_ID), any(), any()))
            .thenReturn(List.of());

        CashflowResponse r = service.compute(MEMBER_ID, CashflowPeriod.CYCLE, anchor);

        assertThat(r.from()).isEqualTo(LocalDate.of(2024, 3, 1));
        assertThat(r.to()).isEqualTo(LocalDate.of(2024, 3, 31));
    }

    @Test
    void compute_ytd_withPastYearEnd_returnsFullPastYear() {
        LocalDate anchor = LocalDate.of(2024, 12, 31);
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER_ID), any(), any()))
            .thenReturn(List.of());

        CashflowResponse r = service.compute(MEMBER_ID, CashflowPeriod.YTD, anchor);

        assertThat(r.from()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(r.to()).isEqualTo(LocalDate.of(2024, 12, 31));
    }

    @Test
    void compute_ytd_withCurrentYearAnchor_returnsFromJan1ToAnchor() {
        LocalDate anchor = LocalDate.of(2026, 6, 15);
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER_ID), any(), any()))
            .thenReturn(List.of());

        CashflowResponse r = service.compute(MEMBER_ID, CashflowPeriod.YTD, anchor);

        assertThat(r.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(r.to()).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    @Test
    void compute_excludesReclassifiedPocketWalletDebit() {
        // A "To EUR MB:<uuid>" row reclassified to TRANSFER must not appear in spending.
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER_ID), any(), any()))
            .thenReturn(List.of(
                tx(LocalDate.of(2026, 6, 2), "2500.00", CategoryKind.INCOME),
                tx(LocalDate.of(2026, 6, 3), "-666.00", CategoryKind.TRANSFER), // pocket debit — excluded
                tx(LocalDate.of(2026, 6, 3), "666.00", CategoryKind.TRANSFER)   // mirror credit — excluded
            ));

        CashflowResponse r = service.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY);

        assertThat(r.income()).isEqualByComparingTo("2500.00");
        assertThat(r.expense()).isEqualByComparingTo("0.00");
    }

    @Test
    void compute_mirrorLegNotCountedAsExpenseOrIncome() {
        // The pocket mirror credit (+amount, TRANSFER) must not inflate income.
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER_ID), any(), any()))
            .thenReturn(List.of(
                tx(LocalDate.of(2026, 6, 2), "2500.00", CategoryKind.INCOME),
                tx(LocalDate.of(2026, 6, 3), "666.00", CategoryKind.TRANSFER) // pocket mirror credit
            ));

        CashflowResponse r = service.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY);

        // Only real income counted; mirror leg excluded by TRANSFER kind
        assertThat(r.income()).isEqualByComparingTo("2500.00");
        assertThat(r.expense()).isEqualByComparingTo("0.00");
    }

    @Test
    void compute_ytd_bucketsPerCycleAndTotalsMatchSum() {
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER_ID), any(), any()))
            .thenReturn(List.of(
                tx(LocalDate.of(2026, 1, 10), "1000.00", CategoryKind.INCOME),
                tx(LocalDate.of(2026, 1, 20), "-300.00", CategoryKind.EXPENSE),
                tx(LocalDate.of(2026, 3, 5), "1000.00", CategoryKind.INCOME),
                tx(LocalDate.of(2026, 6, 9), "-200.00", CategoryKind.EXPENSE)
            ));

        CashflowResponse r = service.compute(MEMBER_ID, CashflowPeriod.YTD, TODAY);

        assertThat(r.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(r.to()).isEqualTo(TODAY);
        // Jan..Jun inclusive with cycleStartDay=1 → 6 calendar-month buckets.
        assertThat(r.series()).hasSize(6);
        assertThat(r.income()).isEqualByComparingTo("2000.00");
        assertThat(r.expense()).isEqualByComparingTo("500.00");
        assertThat(r.net()).isEqualByComparingTo("1500.00");

        // Totals must equal the sum of the buckets.
        BigDecimal bucketNet = r.series().stream()
            .map(CashflowResponse.CashflowBucket::net)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(bucketNet).isEqualByComparingTo(r.net());

        CashflowResponse.CashflowBucket january = r.series().get(0);
        assertThat(january.label()).isEqualTo("2026-01");
        assertThat(january.net()).isEqualByComparingTo("700.00");
    }
}
