package com.picsou.model;

/**
 * How often a payer distributes, either inferred from ex-dates or declared on a SCPI.
 *
 * <p>The day bands are the whole classifier. A gap that falls outside every band — or a series
 * whose gaps do not all fall in the <em>same</em> band — is not a cadence, and we forecast
 * nothing rather than guess. The bands do not overlap, so a series matches at most one.
 */
public enum DividendCadence {

    MONTHLY(1, 20, 40),
    QUARTERLY(3, 75, 105),
    SEMI_ANNUAL(6, 160, 200),
    ANNUAL(12, 330, 400);

    private final int months;
    private final int minGapDays;
    private final int maxGapDays;

    DividendCadence(int months, int minGapDays, int maxGapDays) {
        this.months = months;
        this.minGapDays = minGapDays;
        this.maxGapDays = maxGapDays;
    }

    /** Step used by {@code LocalDate.plusMonths} when projecting the next ex-date. */
    public int months() {
        return months;
    }

    public int minGapDays() {
        return minGapDays;
    }

    public int maxGapDays() {
        return maxGapDays;
    }

    /**
     * Annual needs two ex-dates (one gap). Anything faster needs three, so a single odd gap
     * cannot pass for a monthly or quarterly payer.
     */
    public int minEvents() {
        return this == ANNUAL ? 2 : 3;
    }

    public boolean acceptsGap(long days) {
        return days >= minGapDays && days <= maxGapDays;
    }
}
