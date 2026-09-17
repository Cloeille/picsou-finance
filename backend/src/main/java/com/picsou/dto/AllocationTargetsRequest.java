package com.picsou.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * A replacement of the member's whole allocation profile.
 *
 * <p>{@code monthlyEssentialExpenses} may be null: that is how a member clears a figure they no
 * longer stand behind, and it puts the safety-net tier back into its unscored state.
 *
 * @param monthlyEssentialExpenses compulsory monthly spend, or null to leave it unknown
 */
public record AllocationTargetsRequest(
    @DecimalMin("0") BigDecimal monthlyEssentialExpenses,
    @NotNull @Min(1) @Max(24) Short safetyNetMonths,
    @NotNull @DecimalMin("0") BigDecimal realEstatePct,
    @NotNull @DecimalMin("0") BigDecimal equityPct,
    @NotNull @DecimalMin("0") BigDecimal cryptoPct,
    @NotNull @DecimalMin("0") BigDecimal alternativePct
) {

    /**
     * The four targets must sum to exactly 100.
     *
     * <p>Cross-field validation on a record has no field to attach to, so this surfaces in the
     * 422 {@code errors} map under the derived property name {@code summingToOneHundred} rather
     * than under a component name. The client keys its message off that, not off a field.
     *
     * <p>Returns true when a component is null so the {@code @NotNull} messages are what the user
     * sees first, rather than a confusing "must sum to 100" on a half-filled form.
     */
    @AssertTrue(message = "allocation targets must sum to 100")
    public boolean isSummingToOneHundred() {
        if (realEstatePct == null || equityPct == null || cryptoPct == null || alternativePct == null) {
            return true;
        }
        return realEstatePct.add(equityPct).add(cryptoPct).add(alternativePct)
            .compareTo(new BigDecimal("100")) == 0;
    }
}
