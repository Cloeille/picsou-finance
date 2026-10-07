package com.picsou.dto;

import com.picsou.model.RateBasis;
import com.picsou.model.SavingsInterestConfig;
import com.picsou.model.SavingsProduct;

import java.math.BigDecimal;

/**
 * Savings-interest configuration DTO.
 *
 * <p>Serves two roles:</p>
 * <ul>
 *   <li>Embedded sub-object in {@link AccountResponse#savingsConfig()} (read path) — null when no
 *       config has been set for the account.</li>
 *   <li>Request body for {@code PUT /api/accounts/{id}/savings-config} (write path).</li>
 * </ul>
 */
public record SavingsConfigDto(
    SavingsProduct product,
    BigDecimal annualRate,
    RateBasis rateBasis,
    BigDecimal taxRatePct,
    BigDecimal ceiling
) {
    public static SavingsConfigDto from(SavingsInterestConfig c) {
        return new SavingsConfigDto(
            c.getProduct(),
            c.getAnnualRate(),
            c.getRateBasis(),
            c.getTaxRatePct(),
            c.getCeiling()
        );
    }
}
