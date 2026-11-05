package com.picsou.dto;

import com.picsou.model.PeerLoanRatePeriod;
import jakarta.validation.constraints.DecimalMin;
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
    BigDecimal principal,
    @NotNull LocalDate lentAt,
    LocalDate expectedBack,
    @DecimalMin("0") BigDecimal annualRate,
    /** How the rate was stated; it defaults to annual. The stored rate is annual either way. */
    PeerLoanRatePeriod ratePeriod,
    @DecimalMin("0") BigDecimal plannedMonthlyAmount,
    @Min(1) Integer plannedMonths,
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
