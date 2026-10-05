package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "real_estate_metadata")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RealEstateMetadata extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    // ─── Acquisition ─────────────────────────────────────────────────────────

    /** Price excluding fees, matching how Finary asks for it. See {@link #costBasis()}. */
    @Column(name = "purchase_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal purchasePrice;

    @Column(name = "purchase_date")
    private LocalDate purchaseDate;

    @Column(name = "agency_fees", precision = 20, scale = 8)
    private BigDecimal agencyFees;

    @Column(name = "notary_fees", precision = 20, scale = 8)
    private BigDecimal notaryFees;

    @Column(name = "works_cost", precision = 20, scale = 8)
    private BigDecimal worksCost;

    // ─── Classification ──────────────────────────────────────────────────────

    /**
     * Free text for historical reasons (V19 predates {@link PropertyKind}); read it through
     * {@link #kind()} rather than comparing strings.
     */
    @Column(name = "property_type", length = 50)
    private String propertyType;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private PropertyCategory category;

    @Column(columnDefinition = "text")
    private String description;

    // ─── Address & geocoding ─────────────────────────────────────────────────

    @Column(length = 500)
    private String address;

    @Column(name = "postal_code", length = 10)
    private String postalCode;

    @Column(length = 120)
    private String city;

    @Column(length = 2)
    @Builder.Default
    private String country = "FR";

    @Column(name = "insee_code", length = 5)
    private String inseeCode;

    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "ban_id", length = 60)
    private String banId;

    @Column(name = "parcel_id", length = 20)
    private String parcelId;

    @Column(name = "geocode_score", precision = 4, scale = 3)
    private BigDecimal geocodeScore;

    @Column(name = "geocoded_at")
    private Instant geocodedAt;

    // ─── Physical characteristics ────────────────────────────────────────────

    /** Living area in m². Without it there is nothing to multiply a €/m² by. */
    @Column(name = "surface_area", precision = 10, scale = 2)
    private BigDecimal surfaceArea;

    @Column(name = "land_area", precision = 10, scale = 2)
    private BigDecimal landArea;

    @Column(name = "construction_year")
    private Short constructionYear;

    private Short rooms;

    private Short bedrooms;

    private Short bathrooms;

    @Column(name = "floor_number")
    private Short floorNumber;

    @Column(name = "floors_total")
    private Short floorsTotal;

    @Column(name = "has_elevator")
    private Boolean hasElevator;

    @Column(name = "garage_count", nullable = false)
    @Builder.Default
    private Short garageCount = 0;

    @Column(name = "parking_count", nullable = false)
    @Builder.Default
    private Short parkingCount = 0;

    @Column(name = "has_garden", nullable = false)
    @Builder.Default
    private Boolean hasGarden = false;

    @Column(name = "has_terrace", nullable = false)
    @Builder.Default
    private Boolean hasTerrace = false;

    @Column(name = "has_balcony", nullable = false)
    @Builder.Default
    private Boolean hasBalcony = false;

    @Column(name = "energy_class", length = 1)
    private String energyClass;

    // ─── Valuation & income ──────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "valuation_mode", nullable = false, length = 20)
    @Builder.Default
    private ValuationMode valuationMode = ValuationMode.ESTIMATED;

    @Column(name = "rental_income", precision = 20, scale = 8)
    @Builder.Default
    private BigDecimal rentalIncome = BigDecimal.ZERO;

    // ─── Derived ─────────────────────────────────────────────────────────────

    /** Resolved property kind, or {@code null} when the stored label is unrecognised. */
    @Transient
    public PropertyKind kind() {
        return PropertyKind.parse(propertyType);
    }

    /**
     * What the property actually cost: price plus every acquisition fee.
     *
     * <p>Gain/loss is measured against this, not against {@code purchasePrice} alone —
     * notary fees on a French purchase are routinely 7-8% and ignoring them overstates
     * the gain by that much.
     */
    @Transient
    public BigDecimal costBasis() {
        BigDecimal total = purchasePrice != null ? purchasePrice : BigDecimal.ZERO;
        if (agencyFees != null) total = total.add(agencyFees);
        if (notaryFees != null) total = total.add(notaryFees);
        if (worksCost != null) total = total.add(worksCost);
        return total;
    }
}
