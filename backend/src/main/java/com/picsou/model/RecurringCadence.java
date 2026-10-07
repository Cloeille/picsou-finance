package com.picsou.model;

import java.time.LocalDate;

/** How often a recurring series repeats, with helpers to project and classify by interval. */
public enum RecurringCadence {
    WEEKLY(7),
    BIWEEKLY(14),
    MONTHLY(30),
    QUARTERLY(91),
    YEARLY(365);

    private final int approxDays;

    RecurringCadence(int approxDays) {
        this.approxDays = approxDays;
    }

    public int approxDays() {
        return approxDays;
    }

    /** Advance a date by one period of this cadence (calendar-aware for month/year). */
    public LocalDate next(LocalDate from) {
        return switch (this) {
            case WEEKLY -> from.plusWeeks(1);
            case BIWEEKLY -> from.plusWeeks(2);
            case MONTHLY -> from.plusMonths(1);
            case QUARTERLY -> from.plusMonths(3);
            case YEARLY -> from.plusYears(1);
        };
    }

    /**
     * Classify a median interval (in days) into a cadence, or null if it doesn't fall close
     * enough to any known period. Tolerance widens with the period so a few days' drift on a
     * monthly debit (or a couple of weeks on a yearly one) still matches.
     */
    public static RecurringCadence fromMedianDays(double medianDays) {
        RecurringCadence best = null;
        double bestRatio = Double.MAX_VALUE;
        for (RecurringCadence c : values()) {
            double ratio = Math.abs(medianDays - c.approxDays) / c.approxDays;
            if (ratio < bestRatio) {
                bestRatio = ratio;
                best = c;
            }
        }
        // Accept only when within 25% of the closest period.
        return bestRatio <= 0.25 ? best : null;
    }
}
