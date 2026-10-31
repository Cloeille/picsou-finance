package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One projected charge on the upcoming-payments calendar: a confirmed series rolled forward
 * by its cadence to a concrete future date. A single series yields several occurrences across
 * the requested window.
 */
public record RecurringOccurrenceResponse(
    Long seriesId,
    String label,
    String counterparty,
    BigDecimal expectedAmount,
    LocalDate dueDate,
    Long categoryId,
    String categoryName,
    String categoryColor,
    String categoryIcon,
    Long rewardPoints,
    /** True for a one-off card payment rather than a recurring subscription. */
    boolean creditCardPayment
) {}
