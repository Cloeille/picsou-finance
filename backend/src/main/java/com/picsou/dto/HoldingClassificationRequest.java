package com.picsou.dto;

import com.picsou.model.WealthTier;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The member's own verdict on a holding, overriding whatever was inferred.
 *
 * <p>Every field is optional and null means "stop overriding this one" — the three are
 * independent, so correcting a sector must not silently drop a tier the user set earlier.
 */
public record HoldingClassificationRequest(
    WealthTier wealthTier,
    @Size(max = 40) String sectorKey,
    @Pattern(regexp = "^[A-Z]{2}$", message = "must be an ISO 3166-1 alpha-2 country code")
    String countryKey
) {}
