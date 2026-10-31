package com.picsou.dto;

import com.picsou.model.RecurringCadence;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Manually declare or edit a recurring series. {@code amount} is signed like a transaction
 * (negative for a debit/subscription, positive for income). Used both to add a series the
 * detector missed and to correct a detected one.
 */
public record RecurringSeriesRequest(
    @NotBlank @Size(max = 255) String label,
    @Size(max = 255) String counterparty,
    @NotNull BigDecimal expectedAmount,
    @NotNull RecurringCadence cadence,
    LocalDate nextDueDate,
    Long categoryId
) {}
