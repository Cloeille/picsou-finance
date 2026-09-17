package com.picsou.finary.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One line on a Finary account (security, crypto, fonds euro, fiat, generic asset).
 * {@code display_*} values are in the user's display currency (EUR). Native
 * {@code current_value} / {@code buying_value} stay in the account currency.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FinaryPositionDto(
    String id,
    String name,
    String type,
    Double quantity,
    @JsonProperty("buying_price") @JsonAlias("buyingPrice") Double buyingPrice,
    @JsonProperty("buying_value") @JsonAlias("buyingValue") Double buyingValue,
    @JsonProperty("current_price") @JsonAlias("currentPrice") Double currentPrice,
    @JsonProperty("current_value") @JsonAlias("currentValue") Double currentValue,
    @JsonProperty("unrealized_pnl") @JsonAlias("unrealizedPnl") Double unrealizedPnl,
    @JsonProperty("display_buying_value") @JsonAlias("displayBuyingValue") Double displayBuyingValue,
    @JsonProperty("display_current_price") @JsonAlias("displayCurrentPrice") Double displayCurrentPrice,
    @JsonProperty("display_current_value") @JsonAlias("displayCurrentValue") Double displayCurrentValue,
    @JsonProperty("display_unrealized_pnl") @JsonAlias("displayUnrealizedPnl") Double displayUnrealizedPnl,
    FinarySecurityRefDto security,
    FinaryAssetRefDto crypto,
    FinaryAssetRefDto fiat
) {}
