package com.picsou.dto;

import com.picsou.model.GoalType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * One request shape for both kinds of goal.
 *
 * <p>Two DTOs behind two endpoints would validate more cleanly, and would also fork the
 * controller, {@code goalsApi}, four MCP tools and the demo handler table. Validation groups
 * cannot select themselves from the payload's own content without Hibernate-specific machinery
 * that does not work on records. So: one record, {@code @AssertTrue} for the cross-field rules.
 *
 * <p>Note what the 422 body looks like — those rules have no single field to attach to, so they
 * surface under the derived property names ({@code savingsTargetComplete}, …), not under
 * {@code targetAmount}. The client maps its messages off those keys.
 */
public record GoalRequest(
    @NotBlank @Size(max = 200) String name,

    GoalType type,

    @DecimalMin("0.01") BigDecimal targetAmount,
    @Future LocalDate deadline,

    @DecimalMin("0.01") BigDecimal monthlyAmount,
    @DecimalMin("-100.0") @DecimalMax("100.0") BigDecimal expectedReturn,
    LocalDate startDate,
    LocalDate endDate,

    /** How the monthly amount is split across positions the funded account already holds. */
    @Valid List<GoalAllocationRequest> allocations,

    @NotEmpty List<Long> accountIds
) {

    /**
     * Every payload written before this field existed — the frontend's, and four MCP tools' —
     * omits {@code type}. Defaulting here rather than requiring it is what keeps all of them
     * working untouched, and is why the column has a DEFAULT too.
     */
    public GoalRequest {
        if (type == null) type = GoalType.SAVINGS_TARGET;
        // Normalised for the same reason: a caller that has never heard of the split sends no
        // key at all, and every reader downstream should see an empty list rather than a null.
        if (allocations == null) allocations = List.of();
    }

    /** The classic shape, so call sites that only ever build one do not thread five nulls. */
    public static GoalRequest savingsTarget(String name, BigDecimal targetAmount,
                                            LocalDate deadline, List<Long> accountIds) {
        return new GoalRequest(name, GoalType.SAVINGS_TARGET, targetAmount, deadline,
            null, null, null, null, List.of(), accountIds);
    }

    /** A monthly plan funding one account. */
    public static GoalRequest recurringInvestment(String name, BigDecimal monthlyAmount,
                                                  BigDecimal expectedReturn, LocalDate startDate,
                                                  LocalDate endDate, Long accountId) {
        return new GoalRequest(name, GoalType.RECURRING_INVESTMENT, null, null,
            monthlyAmount, expectedReturn, startDate, endDate, List.of(), List.of(accountId));
    }

    @AssertTrue(message = "a savings target needs both a target amount and a deadline")
    public boolean isSavingsTargetComplete() {
        return type != GoalType.SAVINGS_TARGET || (targetAmount != null && deadline != null);
    }

    @AssertTrue(message = "a recurring investment needs a monthly amount")
    public boolean isRecurringComplete() {
        return type != GoalType.RECURRING_INVESTMENT || monthlyAmount != null;
    }

    /**
     * A recurring investment funds exactly one account. Several would make the projection
     * ambiguous about where the money lands, and the M:N table is reused rather than adding a
     * second way to link a goal to an account.
     */
    @AssertTrue(message = "a recurring investment tracks exactly one account")
    public boolean isRecurringSingleAccount() {
        return type != GoalType.RECURRING_INVESTMENT || (accountIds != null && accountIds.size() == 1);
    }

    @AssertTrue(message = "the end date must come after the start date")
    public boolean isDateRangeOrdered() {
        return startDate == null || endDate == null || endDate.isAfter(startDate);
    }

    /** A savings target funds no positions: there is no monthly amount for them to split. */
    @AssertTrue(message = "only a recurring investment can split its monthly amount")
    public boolean isAllocationOnlyOnRecurring() {
        return type == GoalType.RECURRING_INVESTMENT || allocations.isEmpty();
    }

    /**
     * The split may cover part of the monthly amount — the remainder simply reads as unallocated —
     * but never more than it.
     *
     * <p>True when {@code monthlyAmount} is null so "a recurring investment needs a monthly
     * amount" is the message the user sees on a half-filled form, rather than a confusing
     * complaint about a total they have not typed yet. Same shape as
     * {@code AllocationTargetsRequest.isSummingToOneHundred}.
     */
    @AssertTrue(message = "the split cannot exceed the monthly amount")
    public boolean isAllocationWithinMonthlyAmount() {
        if (monthlyAmount == null || allocations.isEmpty()) return true;
        BigDecimal total = allocations.stream()
            .map(GoalAllocationRequest::monthlyAmount)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.compareTo(monthlyAmount) <= 0;
    }

    /**
     * One line per ticker. Without this the duplicate would reach
     * {@code uk_goal_allocation_goal_ticker} and surface as a 500 instead of a 422.
     */
    @AssertTrue(message = "a position can only appear once in the split")
    public boolean isAllocationTickersUnique() {
        // Compared against the non-null tickers rather than against allocations.size(), so a
        // blank ticker fails only its own @NotBlank instead of also reporting a phantom duplicate.
        List<String> tickers = allocations.stream()
            .map(GoalAllocationRequest::ticker)
            .filter(Objects::nonNull)
            .toList();
        return tickers.stream().distinct().count() == tickers.size();
    }
}
