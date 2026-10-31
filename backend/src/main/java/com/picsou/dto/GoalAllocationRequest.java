package com.picsou.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * One line of a recurring investment plan's monthly split.
 *
 * <p>The ticker must be one the funded account already holds. That cannot be checked here —
 * it needs the database — so {@code GoalService} answers 400 for an unknown one. The rule is
 * deliberate: a plan describes money going into a position that exists, not a wish list.
 */
public record GoalAllocationRequest(
    @NotBlank @Size(max = 30) String ticker,
    @NotNull @DecimalMin("0.01") BigDecimal monthlyAmount
) {}
