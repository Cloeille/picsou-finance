package com.picsou.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * One of {@code months} or {@code monthlyPayment} exactly; the service rejects both or
 * neither. This is a calculator, not a stored state.
 */
public record PeerLoanSimulationRequest(
    @NotNull @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2)
    BigDecimal principal,
    @DecimalMin("0") @DecimalMax("1000")
    @Digits(integer = 6, fraction = 30, message = "The rate has too many digits")
    BigDecimal annualRate,
    @Min(1) @Max(1200) Integer months,
    @DecimalMin("0.01") @Digits(integer = 12, fraction = 2)
    BigDecimal monthlyPayment
) {}
