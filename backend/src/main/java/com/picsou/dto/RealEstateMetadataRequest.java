package com.picsou.dto;

import com.picsou.model.PropertyCategory;
import com.picsou.model.ValuationMode;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Full property description.
 *
 * <p>Only {@code purchasePrice} is required — a user should be able to record a property
 * immediately and fill in the details that unlock an automatic valuation (type, living area,
 * address) whenever they get to it.
 */
public record RealEstateMetadataRequest(
    // ─── Acquisition ─────────────────────────────────────────────────────────
    @NotNull @DecimalMin("0") BigDecimal purchasePrice,
    LocalDate purchaseDate,
    @DecimalMin("0") BigDecimal agencyFees,
    @DecimalMin("0") BigDecimal notaryFees,
    @DecimalMin("0") BigDecimal worksCost,

    // ─── Classification ──────────────────────────────────────────────────────
    @Size(max = 50) String propertyType,
    PropertyCategory category,
    @Size(max = 5000) String description,

    // ─── Address ─────────────────────────────────────────────────────────────
    @Size(max = 500) String address,
    @Size(max = 10) String postalCode,
    @Size(max = 120) String city,
    @Size(min = 2, max = 2) String country,

    // ─── Characteristics ─────────────────────────────────────────────────────
    @DecimalMin("0") BigDecimal surfaceArea,
    @DecimalMin("0") BigDecimal landArea,
    @Min(1000) @Max(2200) Short constructionYear,
    @Min(0) @Max(1000) Short rooms,
    @Min(0) @Max(1000) Short bedrooms,
    @Min(0) @Max(100) Short bathrooms,
    @Min(-10) @Max(200) Short floorNumber,
    @Min(0) @Max(200) Short floorsTotal,
    Boolean hasElevator,
    @Min(0) @Max(100) Short garageCount,
    @Min(0) @Max(100) Short parkingCount,
    Boolean hasGarden,
    Boolean hasTerrace,
    Boolean hasBalcony,
    @Pattern(regexp = "[A-G]", message = "must be a DPE class between A and G") String energyClass,

    // ─── Valuation & income ──────────────────────────────────────────────────
    ValuationMode valuationMode,
    @DecimalMin("0") BigDecimal rentalIncome
) {}
