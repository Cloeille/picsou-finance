package com.picsou.dto;

import com.picsou.model.DividendPolicy;
import com.picsou.model.ScpiPosition;
import com.picsou.model.ScpiValuationStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ScpiPositionResponse(
    String isin,
    String managementCompany,
    BigDecimal shareCount,
    BigDecimal subscriptionPriceEur,
    BigDecimal withdrawalPriceEur,
    /** Withdrawal price times share count, or null when the withdrawal price is missing. */
    BigDecimal withdrawalValueEur,
    DividendPolicy dividendPolicy,
    LocalDate jouissanceDate,
    ScpiValuationStatus valuationStatus
) {
    public static ScpiPositionResponse from(ScpiPosition position, BigDecimal withdrawalValueEur) {
        return new ScpiPositionResponse(
            position.getIsin(),
            position.getManagementCompany(),
            position.getShareCount(),
            position.getSubscriptionPriceEur(),
            position.getWithdrawalPriceEur(),
            withdrawalValueEur,
            position.getDividendPolicy(),
            position.getJouissanceDate(),
            position.getValuationStatus()
        );
    }
}
