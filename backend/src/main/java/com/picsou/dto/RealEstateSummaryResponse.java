package com.picsou.dto;

import com.picsou.model.ValuationConfidence;
import com.picsou.model.ValuationMode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The member's property wealth, gross and net of the loans financing it.
 *
 * <p>Every figure is already weighted by the member's share, so a house owned 50/50 shows
 * half its value and half its mortgage — the two must be weighted together or the equity
 * would be nonsense.
 *
 * @param grossValue      current value of the properties
 * @param outstandingDebt capital still owed on the loans linked to them
 * @param netValue        {@code grossValue - outstandingDebt}; negative when underwater
 * @param costBasis       purchase prices plus agency, notary and works fees
 * @param unrealizedGain  {@code grossValue - costBasis}
 * @param loanToValue     debt as a percentage of gross value; null when there is nothing to own
 */
public record RealEstateSummaryResponse(
    BigDecimal grossValue,
    BigDecimal outstandingDebt,
    BigDecimal netValue,
    BigDecimal costBasis,
    BigDecimal unrealizedGain,
    BigDecimal unrealizedGainPercent,
    BigDecimal loanToValue,
    BigDecimal monthlyRentalIncome,
    List<PropertyLine> properties,
    /**
     * SCPI shares, valued at the withdrawal price. Kept out of {@code grossValue}: that figure
     * is what the open-data estimator can say about a house, and a share has no floor area.
     */
    BigDecimal paperGross,
    BigDecimal paperDebt,
    BigDecimal paperNet,
    List<PaperLine> paper
) {
    /**
     * @param sharePercent the member's stake, so the UI can show "50% of 400 000 €"
     * @param grossValue   already weighted by {@code sharePercent}
     */
    public record PropertyLine(
        Long accountId,
        String name,
        String color,
        String propertyType,
        String category,
        String city,
        BigDecimal sharePercent,
        BigDecimal grossValue,
        BigDecimal outstandingDebt,
        BigDecimal netValue,
        BigDecimal costBasis,
        BigDecimal unrealizedGain,
        BigDecimal surfaceArea,
        BigDecimal rentalIncome,
        ValuationMode valuationMode,
        LocalDate lastValuedAt,
        ValuationConfidence lastConfidence,
        List<LinkedLoan> loans
    ) {}

    /**
     * One SCPI vehicle. {@code grossValue} is the withdrawal price times the share count,
     * already weighted by {@code sharePercent}. The subscription price is shown beside it
     * and is never this figure.
     */
    public record PaperLine(
        Long accountId,
        String name,
        String color,
        String managementCompany,
        BigDecimal shareCount,
        BigDecimal sharePercent,
        BigDecimal withdrawalPriceEur,
        BigDecimal subscriptionPriceEur,
        BigDecimal grossValue,
        BigDecimal outstandingDebt,
        BigDecimal netValue,
        String valuationStatus,
        List<LinkedLoan> loans
    ) {}

    /** A loan financing this property, weighted by the member's share of the loan itself. */
    public record LinkedLoan(
        Long accountId,
        String name,
        String lenderName,
        BigDecimal outstandingBalance,
        BigDecimal sharePercent,
        BigDecimal monthlyPayment,
        LocalDate endDate
    ) {}
}
