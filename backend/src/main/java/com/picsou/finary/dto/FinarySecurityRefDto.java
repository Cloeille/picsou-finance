package com.picsou.finary.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Nested security reference on a Finary investment position. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FinarySecurityRefDto(
    Long id,
    String name,
    String isin,
    String symbol,
    String slug,
    String type
) {}
