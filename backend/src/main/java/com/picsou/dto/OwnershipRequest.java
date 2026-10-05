package com.picsou.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Replaces an account's ownership split wholesale.
 *
 * <p>An empty {@code shares} list clears the split, which restores the default: the owning
 * member holds 100%.
 */
public record OwnershipRequest(
    @NotNull @Valid List<Share> shares
) {
    public record Share(
        @NotNull Long memberId,
        /** Exclusive lower bound: a 0% holder is simply not in the list. */
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") BigDecimal sharePercent
    ) {}
}
