package com.picsou.dto;

import com.picsou.model.HouseholdStatus;
import com.picsou.model.RiskProfile;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The member's profile, plus the two figures every reader would otherwise derive itself.
 *
 * @param age              years completed, from {@code birthDate}; null when no date is stated.
 *                         Derived server-side so it cannot go stale in a cache or drift between
 *                         the two or three places that want it.
 * @param monthlyNetIncome what actually reaches the account:
 *                         {@code monthlyNetBeforeTax × (1 − withholdingTaxRate)}. **Null unless
 *                         both inputs are stated** — a blank withholding rate means "not said",
 *                         not "zero", and a savings rate over a denominator we guessed at would
 *                         look like a measurement while being an artefact. Someone genuinely at
 *                         0 % types a zero.
 */
public record MemberProfileResponse(
    LocalDate birthDate,
    Integer age,
    BigDecimal marginalTaxRate,
    HouseholdStatus householdStatus,
    BigDecimal taxHouseholdParts,
    Short dependents,
    BigDecimal annualGrossIncome,
    BigDecimal monthlyNetBeforeTax,
    BigDecimal withholdingTaxRate,
    BigDecimal monthlyNetIncome,
    BigDecimal monthlySavingsCapacity,
    Short targetRetirementAge,
    RiskProfile riskProfile
) {}
