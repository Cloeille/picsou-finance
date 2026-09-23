package com.picsou.dto;

import com.picsou.model.DividendPolicy;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A manually entered SCPI position.
 *
 * <p>{@code withdrawalPriceEur} may be omitted. The service then keeps the previous balance
 * and reports {@code PRICE_INCOMPLETE}, rather than treating the subscription price as what
 * the shares could be sold for.
 */
public record ScpiPositionRequest(
    @Size(max = 12) String isin,
    @Size(max = 100) String managementCompany,
    @NotNull @DecimalMin("0") BigDecimal shareCount,
    @DecimalMin("0") BigDecimal subscriptionPriceEur,
    @DecimalMin("0") BigDecimal withdrawalPriceEur,
    DividendPolicy dividendPolicy,
    LocalDate jouissanceDate
) {}
