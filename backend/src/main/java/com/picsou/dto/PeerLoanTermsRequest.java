package com.picsou.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The terms of a peer loan. {@code plannedMonthlyAmount} and {@code plannedMonths} are a
 * forecast only: both or neither, validated in the service.
 */
public record PeerLoanTermsRequest(
    @NotNull @DecimalMin(value = "0.01", message = "Principal must be greater than zero")
    BigDecimal principal,
    @NotBlank @Size(max = 120) String counterpartyName,
    @NotNull LocalDate lentAt,
    LocalDate expectedBack,
    @DecimalMin("0") @DecimalMax("1") BigDecimal annualRate,
    @DecimalMin("0") BigDecimal plannedMonthlyAmount,
    @Min(1) Integer plannedMonths,
    Long linkedAccountId,
    @Size(max = 500) String note
) {}
