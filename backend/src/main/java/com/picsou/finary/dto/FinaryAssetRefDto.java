package com.picsou.finary.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Nested crypto / fiat / generic asset reference on a Finary position. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FinaryAssetRefDto(
    Long id,
    String name,
    String code,
    String symbol,
    String type
) {}
