package com.picsou.dto;

import com.picsou.model.Goal;
import com.picsou.model.GoalType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One goal's state, whichever shape it has.
 *
 * <p>The target machinery — {@code targetAmount}, {@code deadline}, {@code percentComplete},
 * {@code monthlyNeeded}, {@code surplus} — is null for a {@code RECURRING_INVESTMENT} and
 * dropped from the JSON by {@code non_null}. Clients discriminate on {@code type}.
 *
 * <p>{@code monthsLeft} and {@code isOnTrack} are primitives and cannot be dropped that way; a
 * recurring plan reports {@code 0} and {@code true}. They are meaningless for it, and the client
 * must not render them — boxing them would have rippled a nullable through every savings-goal
 * call site to express something the {@code type} field already says.
 *
 * <p>{@code allocations} is <em>always a list</em> — empty for a savings target and for an
 * undetailed plan, never null. That is deliberate against the {@code non_null} rule the rest of
 * this record follows: an omitted key reaches TypeScript as {@code undefined}, and the client
 * maps over this one. An empty array costs two bytes and removes the whole class of bug.
 */
public record GoalProgressResponse(
    Long id,
    String name,
    GoalType type,
    BigDecimal targetAmount,
    LocalDate deadline,
    Instant createdAt,
    String historyStartMonth,
    List<AccountResponse> accounts,
    BigDecimal currentTotal,
    BigDecimal percentComplete,
    long monthsLeft,
    BigDecimal monthlyNeeded,
    BigDecimal avgMonthlyContribution,  // null = pas assez de données
    boolean isOnTrack,
    BigDecimal surplus,
    BigDecimal monthlyAmount,
    BigDecimal expectedReturn,
    LocalDate startDate,
    LocalDate endDate,
    List<GoalAllocationResponse> allocations
) {
    public static GoalProgressResponse from(
        Goal goal,
        List<AccountResponse> accounts,
        BigDecimal currentTotal,
        BigDecimal percentComplete,
        long monthsLeft,
        BigDecimal monthlyNeeded,
        BigDecimal avgMonthlyContribution,
        boolean isOnTrack,
        BigDecimal surplus
    ) {
        return new GoalProgressResponse(
            goal.getId(),
            goal.getName(),
            goal.getType(),
            goal.getTargetAmount(),
            goal.getDeadline(),
            goal.getCreatedAt(),
            goal.getHistoryStartMonth(),
            accounts,
            currentTotal,
            percentComplete,
            monthsLeft,
            monthlyNeeded,
            avgMonthlyContribution,
            isOnTrack,
            surplus,
            goal.getMonthlyAmount(),
            goal.getExpectedReturn(),
            goal.getStartDate(),
            goal.getEndDate(),
            // A savings target funds no positions; see the note above on why this is not null.
            List.of()
        );
    }

    /**
     * A recurring plan: no target, no deadline, no "on track" verdict yet — only what goes in
     * every month and what the account it funds is worth today.
     */
    public static GoalProgressResponse recurring(Goal goal, List<AccountResponse> accounts,
                                                 BigDecimal currentTotal,
                                                 List<GoalAllocationResponse> allocations) {
        return new GoalProgressResponse(
            goal.getId(), goal.getName(), goal.getType(),
            null, null, goal.getCreatedAt(), goal.getHistoryStartMonth(), accounts,
            currentTotal, null, 0, null, null, true, null,
            goal.getMonthlyAmount(), goal.getExpectedReturn(),
            goal.getStartDate(), goal.getEndDate(), allocations);
    }
}
