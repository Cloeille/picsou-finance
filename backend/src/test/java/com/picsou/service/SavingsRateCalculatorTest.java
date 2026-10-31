package com.picsou.service;

import com.picsou.dto.GoalProgressResponse;
import com.picsou.model.Goal;
import com.picsou.model.GoalType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirrors {@code frontend/src/pages/goals/plan-math.test} in what it pins: the active-window
 * rule and the worked example (400 + 200 over 3 000 is 20.0 %) are the two things that must not
 * drift between this and the client's own computation.
 */
class SavingsRateCalculatorTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-08-19");

    private final SavingsRateCalculator calculator = new SavingsRateCalculator();

    private static GoalProgressResponse plan(String monthly, LocalDate start, LocalDate end) {
        Goal goal = Goal.builder()
            .id(1L).name("Plan").type(GoalType.RECURRING_INVESTMENT)
            .monthlyAmount(monthly == null ? null : new BigDecimal(monthly))
            .startDate(start).endDate(end)
            .build();
        return GoalProgressResponse.recurring(goal, List.of(), BigDecimal.ZERO, List.of());
    }

    private static GoalProgressResponse savingsTarget() {
        Goal goal = Goal.builder()
            .id(2L).name("Apport").type(GoalType.SAVINGS_TARGET)
            .targetAmount(new BigDecimal("50000")).deadline(TODAY.plusYears(2))
            .build();
        return GoalProgressResponse.from(goal, List.of(), BigDecimal.ZERO, BigDecimal.ZERO,
            24, BigDecimal.ZERO, null, true, BigDecimal.ZERO);
    }

    @Test
    void sumsTheRunningPlans() {
        BigDecimal total = calculator.monthlyContributions(
            List.of(plan("400", null, null), plan("200", null, null)), TODAY);

        assertThat(total).isEqualByComparingTo("600");
    }

    @Test
    void ignoresAPlanThatHasNotStartedOrHasEnded() {
        // Both stay in the member's list -- they are a record kept -- but neither is money going
        // out this month.
        List<GoalProgressResponse> goals = List.of(
            plan("300", null, null),
            plan("900", LocalDate.parse("2027-01-01"), null),
            plan("900", null, LocalDate.parse("2026-01-01")));

        assertThat(calculator.monthlyContributions(goals, TODAY)).isEqualByComparingTo("300");
    }

    @Test
    void countsAPlanOnTheDayItStartsAndTheDayItEnds() {
        assertThat(calculator.monthlyContributions(List.of(plan("100", TODAY, TODAY)), TODAY))
            .isEqualByComparingTo("100");
    }

    @Test
    void ignoresSavingsTargets() {
        // A goal with a deadline has no monthly amount to contribute.
        assertThat(calculator.monthlyContributions(List.of(savingsTarget()), TODAY))
            .isEqualByComparingTo("0");
    }

    @Test
    void ratesContributionsAgainstNetIncome() {
        assertThat(calculator.savingsRate(new BigDecimal("600"), new BigDecimal("3000")))
            .isEqualByComparingTo("20.0");
    }

    @Test
    void roundsTheRateToOneDecimal() {
        assertThat(calculator.savingsRate(new BigDecimal("400"), new BigDecimal("2549.25")))
            .isEqualByComparingTo("15.7");
    }

    @Test
    void hasNoRateWithoutACredibleDenominator() {
        // Null rather than zero: "we were not told what they earn" is not "saves nothing".
        assertThat(calculator.savingsRate(new BigDecimal("400"), null)).isNull();
        assertThat(calculator.savingsRate(new BigDecimal("400"), BigDecimal.ZERO)).isNull();
        assertThat(calculator.savingsRate(null, new BigDecimal("3000"))).isNull();
    }
}
