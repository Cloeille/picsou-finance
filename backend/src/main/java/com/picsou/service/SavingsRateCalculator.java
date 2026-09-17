package com.picsou.service;

import com.picsou.dto.GoalProgressResponse;
import com.picsou.model.GoalType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * What the member puts aside every month, and what share of their income that is.
 *
 * <p>Mirrors {@code frontend/src/pages/goals/plan-math.ts}, which computes the same two figures
 * for the Goals page from data it already holds. Two implementations of one rule is a drift
 * risk, and the rules that must not drift are the two below — both suites pin the same worked
 * example (400 + 200 over 3 000 is 20.0 %) so a change to one side fails the other's neighbour.
 */
@Service
public class SavingsRateCalculator {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    /**
     * The recurring plans paying in on a given date.
     *
     * <p>A plan that starts next year or ended last one stays in the member's list — it is a
     * record they keep — but it is not money going out today.
     *
     * <p>Deliberately <em>not</em> share-weighted, unlike {@code ProjectionService}'s
     * {@code monthlyInflowEur}: this figure is reported beside the plans' own monthly amounts,
     * and two numbers disagreeing in one document is worse than being approximate about a
     * jointly-held account.
     */
    public BigDecimal monthlyContributions(List<GoalProgressResponse> goals, LocalDate on) {
        return goals.stream()
            .filter(g -> g.type() == GoalType.RECURRING_INVESTMENT)
            .filter(g -> activeOn(g, on))
            .map(GoalProgressResponse::monthlyAmount)
            .filter(java.util.Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static boolean activeOn(GoalProgressResponse goal, LocalDate on) {
        if (goal.startDate() != null && goal.startDate().isAfter(on)) return false;
        return goal.endDate() == null || !goal.endDate().isBefore(on);
    }

    /**
     * Contributions as a percentage of net monthly income, or null when there is no credible
     * denominator.
     *
     * <p>Net, not gross: money withheld at source was never available to save. It is also what
     * makes the comparison to INSEE's household rate legitimate — the "brut" in <em>revenu
     * disponible brut</em> means gross of capital consumption, not gross of tax, so that figure
     * is measured after compulsory levies too.
     */
    public BigDecimal savingsRate(BigDecimal monthlyContributions, BigDecimal monthlyNetIncome) {
        if (monthlyContributions == null || monthlyNetIncome == null
            || monthlyNetIncome.signum() <= 0) {
            return null;
        }
        return monthlyContributions
            .multiply(ONE_HUNDRED)
            .divide(monthlyNetIncome, 1, RoundingMode.HALF_UP);
    }
}
