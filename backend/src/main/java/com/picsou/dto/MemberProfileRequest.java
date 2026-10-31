package com.picsou.dto;

import com.picsou.model.HouseholdStatus;
import com.picsou.model.RiskProfile;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Past;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A replacement of the member's whole profile.
 *
 * <p>Every field is optional, and null is a value: it is how a member clears a figure they no
 * longer stand behind, putting that part of the profile back into its unstated state. Nothing
 * here is required, because a profile is worth having half-filled.
 *
 * <p>The bounds mirror {@code ck_member_profile_*} in V90 so a bad figure is a 422 with a field
 * name on it rather than a constraint violation surfacing as a 500.
 */
public record MemberProfileRequest(
    @Past LocalDate birthDate,
    @DecimalMin("0") @DecimalMax("100") BigDecimal marginalTaxRate,
    HouseholdStatus householdStatus,
    @DecimalMin("1") @DecimalMax("20") BigDecimal taxHouseholdParts,
    @Min(0) @Max(20) Short dependents,
    @DecimalMin("0") BigDecimal annualGrossIncome,
    @DecimalMin("0") BigDecimal monthlyNetBeforeTax,
    @DecimalMin("0") @DecimalMax("100") BigDecimal withholdingTaxRate,
    @DecimalMin("0") BigDecimal monthlySavingsCapacity,
    @Min(40) @Max(90) Short targetRetirementAge,
    RiskProfile riskProfile
) {}
