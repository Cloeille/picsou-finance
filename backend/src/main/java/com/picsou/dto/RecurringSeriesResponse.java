package com.picsou.dto;

import com.picsou.model.RecurringCadence;
import com.picsou.model.RecurringSeries;
import com.picsou.model.RecurringStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A recurring series as surfaced to the UI: its expected amount/cadence, lifecycle status,
 * the linked category (if any) and its next projected due date for the calendar.
 *
 * <p>Detection-v2 fields (confidence, amount envelope, variable flag, price-change pointers and the
 * {@code autoConfirmed} marker) let the subscriptions page explain what the detector decided. The
 * {@code runtimeStatus} (LATE / DUE_SOON / SCHEDULED) is computed from {@code today}, never stored.
 */
public record RecurringSeriesResponse(
    Long id,
    String label,
    String counterparty,
    BigDecimal expectedAmount,
    RecurringCadence cadence,
    RecurringStatus status,
    LocalDate nextDueDate,
    LocalDate lastSeenDate,
    Long categoryId,
    String categoryName,
    String categoryColor,
    String categoryIcon,
    // ─── Detection v2 ──────────────────────────────────────────────────────────
    BigDecimal confidence,
    BigDecimal amountMin,
    BigDecimal amountMax,
    boolean variable,
    BigDecimal previousAmount,
    LocalDate priceChangedAt,
    boolean autoConfirmed,
    RecurringRuntimeStatus runtimeStatus
) {
    /** How soon ahead a due date counts as "due soon" rather than merely scheduled. */
    private static final int DUE_SOON_DAYS = 7;

    /**
     * Number of missed cadence periods that makes a series stale. A series is considered inactive
     * when at least this many expected occurrences have passed without the series being updated.
     * Computed cadence-correctly via {@link RecurringCadence#next(LocalDate)} — not approximated
     * with fixed day counts.
     */
    public static final int STALE_MISSED_PERIODS = 2;

    /** Without a reference date the runtime urgency is unknown, so it resolves to SCHEDULED. */
    public static RecurringSeriesResponse from(RecurringSeries s) {
        return from(s, null);
    }

    public static RecurringSeriesResponse from(RecurringSeries s, LocalDate today) {
        var category = s.getCategory();
        return new RecurringSeriesResponse(
            s.getId(),
            s.getLabel(),
            s.getCounterparty(),
            s.getExpectedAmount(),
            s.getCadence(),
            s.getStatus(),
            s.getNextDueDate(),
            s.getLastSeenDate(),
            category != null ? category.getId() : null,
            category != null ? category.getName() : null,
            category != null ? category.getColor() : null,
            category != null ? category.getIcon() : null,
            s.getConfidence(),
            s.getAmountMin(),
            s.getAmountMax(),
            s.isVariable(),
            s.getPreviousAmount(),
            s.getPriceChangedAt(),
            s.isAutoConfirmed(),
            runtimeStatus(s.getNextDueDate(), today, s.getCadence())
        );
    }

    /**
     * True when {@code due} is at least {@link #STALE_MISSED_PERIODS} cadence steps before
     * {@code today}, meaning that many expected occurrences have passed without the series being
     * refreshed. Uses {@link RecurringCadence#next} for cadence-correct computation.
     */
    public static boolean isStale(LocalDate due, LocalDate today, RecurringCadence cadence) {
        if (due == null || today == null || cadence == null || !due.isBefore(today)) {
            return false;
        }
        int missed = 0;
        LocalDate step = due;
        while (missed < STALE_MISSED_PERIODS) {
            step = cadence.next(step);
            if (!step.isBefore(today)) break;
            missed++;
        }
        return missed >= STALE_MISSED_PERIODS;
    }

    private static RecurringRuntimeStatus runtimeStatus(LocalDate due, LocalDate today, RecurringCadence cadence) {
        if (due == null || today == null) {
            return RecurringRuntimeStatus.SCHEDULED;
        }
        if (isStale(due, today, cadence)) {
            return RecurringRuntimeStatus.STALE;
        }
        if (due.isBefore(today)) {
            return RecurringRuntimeStatus.LATE;
        }
        if (!due.isAfter(today.plusDays(DUE_SOON_DAYS))) {
            return RecurringRuntimeStatus.DUE_SOON;
        }
        return RecurringRuntimeStatus.SCHEDULED;
    }
}
