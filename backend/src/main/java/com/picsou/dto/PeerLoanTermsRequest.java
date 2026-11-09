package com.picsou.dto;

import com.picsou.model.PeerLoanRatePeriod;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The terms of a peer loan. The borrower is the account's own name: one account is one
 * loan, so there is no second identity to enter here.
 *
 * <p>{@code linkedAccountId} is required: the receivable must balance a real cash
 * movement, or a repayment would silently reduce net worth. {@code plannedMonthlyAmount}
 * and {@code plannedMonths} are a forecast only — both or neither, validated in the
 * service.
 */
public record PeerLoanTermsRequest(
    @NotNull @DecimalMin(value = "0.01", message = "Principal must be greater than zero")
    @Digits(integer = 12, fraction = 2, message = "Principal is too large")
    BigDecimal principal,
    @NotNull LocalDate lentAt,
    LocalDate expectedBack,
    // The cap is deliberate: the rate is exponentiated by the forecast, and an unbounded
    // value is both a CPU sink and a NUMERIC(12,6) overflow. 1 000 is already 100 000 %/yr.
    // The fraction bound is loose on purpose (30, not 6): the dialog sends a double
    // (0.011000000000000001), and the backend rounds it to six decimals instead of
    // rejecting an ordinary rate as a 422 — while a tiny exponent like 1E-100000000 is
    // still rejected fast by validation, before it can pin a thread in setScale.
    @DecimalMin("0") @DecimalMax(value = "1000", message = "The rate is too high")
    @Digits(integer = 6, fraction = 30, message = "The rate has too many digits")
    BigDecimal annualRate,
    /** How the rate was stated; it defaults to annual. The stored rate is annual either way. */
    PeerLoanRatePeriod ratePeriod,
    @DecimalMin("0") @Digits(integer = 12, fraction = 2, message = "The planned payment is too large")
    BigDecimal plannedMonthlyAmount,
    /** Capped so the forecast curve cannot be asked for an unbounded number of points. */
    @Min(1) @Max(1200) Integer plannedMonths,
    @NotNull Long linkedAccountId,
    @Size(max = 500) String note,
    /**
     * Whether Picsou writes the lending outflow on a manual linked account. Null or true
     * keeps the historical behaviour. False is for a loan recorded after the fact, whose
     * cash movement is already reflected in the account's typed balance; only the first
     * configuration acts on it.
     */
    Boolean withCashLeg
) {
    /** Pre-cash-leg shape, kept so existing callers keep the historic behaviour. */
    public PeerLoanTermsRequest(
        BigDecimal principal, LocalDate lentAt, LocalDate expectedBack, BigDecimal annualRate,
        PeerLoanRatePeriod ratePeriod, BigDecimal plannedMonthlyAmount, Integer plannedMonths,
        Long linkedAccountId, String note
    ) {
        this(principal, lentAt, expectedBack, annualRate, ratePeriod, plannedMonthlyAmount,
            plannedMonths, linkedAccountId, note, true);
    }
}
