package com.picsou.finary.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinaryAccountDto(
    String id,
    String name,
    String slug,
    Double balance,
    Double organizationBalance,
    FinaryAccountInstitution institution,
    FinaryAccountCurrency currency,
    boolean isManual,
    @JsonProperty("display_balance") @JsonAlias("displayBalance") Double displayBalance,
    List<FinaryPositionDto> securities,
    List<FinaryPositionDto> cryptos,
    @JsonProperty("fonds_euro") @JsonAlias("fondsEuro") List<FinaryPositionDto> fondsEuro,
    List<FinaryPositionDto> fiats,
    @JsonProperty("generic_assets") @JsonAlias("genericAssets") List<FinaryPositionDto> genericAssets,
    List<FinaryPositionDto> scpis,
    @JsonProperty("precious_metals") @JsonAlias("preciousMetals") List<FinaryPositionDto> preciousMetals
) {
    public static FinaryAccountDto withoutHoldings(
            String id,
            String name,
            String slug,
            Double balance,
            Double organizationBalance,
            FinaryAccountInstitution institution,
            FinaryAccountCurrency currency,
            boolean isManual) {
        return new FinaryAccountDto(
            id, name, slug, balance, organizationBalance, institution, currency, isManual,
            null, null, null, null, null, null, null, null);
    }
}
