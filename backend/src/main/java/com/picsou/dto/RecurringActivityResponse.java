package com.picsou.dto;

import com.picsou.model.RecurringCadence;
import com.picsou.model.RecurringSeries;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One entry in the recurring "what changed" activity feed. Entries are <em>derived</em> from series
 * state (no dedicated table): a series is surfaced when it was silently auto-confirmed or when its
 * price recently stepped. Each entry is reversible via {@code POST /api/recurring/{id}/undo}.
 *
 * <p>{@code occurredOn} is the date the change is attributed to — {@code priceChangedAt} for a price
 * change, {@code lastSeenDate} for an auto-confirm — and drives the feed's reverse-chronological order.
 */
public record RecurringActivityResponse(
    Long seriesId,
    String label,
    RecurringActivityType type,
    LocalDate occurredOn,
    BigDecimal expectedAmount,
    /** The pre-change amount; populated only for {@link RecurringActivityType#PRICE_CHANGE}. */
    BigDecimal previousAmount,
    RecurringCadence cadence,
    Long categoryId,
    String categoryName,
    String categoryColor,
    String categoryIcon
) {
    public static RecurringActivityResponse autoConfirmed(RecurringSeries s) {
        return build(s, RecurringActivityType.AUTO_CONFIRMED, s.getLastSeenDate(), null);
    }

    public static RecurringActivityResponse priceChange(RecurringSeries s) {
        return build(s, RecurringActivityType.PRICE_CHANGE, s.getPriceChangedAt(), s.getPreviousAmount());
    }

    private static RecurringActivityResponse build(
        RecurringSeries s, RecurringActivityType type, LocalDate occurredOn, BigDecimal previousAmount
    ) {
        var category = s.getCategory();
        return new RecurringActivityResponse(
            s.getId(),
            s.getLabel(),
            type,
            occurredOn,
            s.getExpectedAmount(),
            previousAmount,
            s.getCadence(),
            category != null ? category.getId() : null,
            category != null ? category.getName() : null,
            category != null ? category.getColor() : null,
            category != null ? category.getIcon() : null
        );
    }
}
