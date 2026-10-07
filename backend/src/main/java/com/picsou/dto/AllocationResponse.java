package com.picsou.dto;

import com.picsou.model.AssetClass;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Savings/investment allocation: a current {@code stock} breakdown by asset class (the donut)
 * and the {@code contributions} flowed into savings/investment accounts over the period (the
 * flux). Real-estate/loan/other balances are excluded so this reflects investable money, not
 * total net worth.
 */
public record AllocationResponse(
    CashflowPeriod period,
    LocalDate from,
    LocalDate to,
    BigDecimal totalStock,
    List<AllocationStock> stock,
    BigDecimal totalContributions,
    List<AllocationContribution> contributions
) {
    /** One asset-class slice of the current stock. */
    public record AllocationStock(
        AssetClass assetClass,
        BigDecimal amount,
        BigDecimal percent
    ) {}

    /** Net amount transferred into one savings/investment account over the period. */
    public record AllocationContribution(
        Long accountId,
        String accountName,
        AssetClass assetClass,
        String color,
        BigDecimal amount
    ) {}
}
