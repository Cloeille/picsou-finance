package com.picsou.service.budget;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure logic for the configurable payday budget cycle. A "month" of budget runs
 * from {@code cycleStartDay} of one month to the day before {@code cycleStartDay}
 * of the next. With {@code cycleStartDay == 1} this is exactly the calendar month.
 *
 * <p>{@code cycleStartDay} is constrained to 1–28 so {@code withDayOfMonth} is always
 * valid regardless of month length. This class is the single source of truth for the
 * "which cycle does this date belong to" question across envelopes, cashflow and
 * allocation — nothing else should re-derive it.
 */
public final class BudgetCycle {

    private BudgetCycle() {}

    /** Inclusive date range [start, end] of a single budget cycle. */
    public record CycleRange(LocalDate start, LocalDate end) {
        public boolean contains(LocalDate date) {
            return !date.isBefore(start) && !date.isAfter(end);
        }
    }

    /** The cycle that contains {@code date}, given the member's payday. */
    public static CycleRange cycleFor(LocalDate date, int cycleStartDay) {
        if (cycleStartDay < 1 || cycleStartDay > 28) {
            throw new IllegalArgumentException("cycleStartDay must be between 1 and 28, was " + cycleStartDay);
        }
        LocalDate start = date.getDayOfMonth() >= cycleStartDay
            ? date.withDayOfMonth(cycleStartDay)
            : date.minusMonths(1).withDayOfMonth(cycleStartDay);
        LocalDate end = start.plusMonths(1).minusDays(1);
        return new CycleRange(start, end);
    }

    /** All cycles overlapping [from, to], ordered ascending (inclusive of both ends). */
    public static List<CycleRange> cyclesBetween(LocalDate from, LocalDate to, int cycleStartDay) {
        List<CycleRange> cycles = new ArrayList<>();
        CycleRange cursor = cycleFor(from, cycleStartDay);
        while (!cursor.start().isAfter(to)) {
            cycles.add(cursor);
            cursor = cycleFor(cursor.end().plusDays(1), cycleStartDay);
        }
        return cycles;
    }
}
