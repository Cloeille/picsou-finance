package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single property estimate, kept forever.
 *
 * <p>Storing the history rather than one current figure is what lets the UI draw a value
 * curve and explain an old estimate after the heuristics have moved on — {@link #methodDetail}
 * pins the coefficients that were actually applied that day.
 */
@Entity
@Table(
    name = "property_valuation",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_property_valuation_account_date",
        columnNames = {"account_id", "valued_at"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PropertyValuation extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Column(name = "valued_at", nullable = false)
    private LocalDate valuedAt;

    @Column(name = "estimated_value", nullable = false, precision = 20, scale = 8)
    private BigDecimal estimatedValue;

    /** Lower bound, from the source's 25th percentile. */
    @Column(name = "low_value", precision = 20, scale = 8)
    private BigDecimal lowValue;

    /** Upper bound, from the source's 75th percentile. */
    @Column(name = "high_value", precision = 20, scale = 8)
    private BigDecimal highValue;

    @Column(name = "price_per_sqm", precision = 20, scale = 8)
    private BigDecimal pricePerSqm;

    /** {@code CEREMA_DV3F}, {@code LOCAL_DVF} or {@code MANUAL}. */
    @Column(nullable = false, length = 30)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ValuationConfidence confidence;

    /** Number of comparable transactions behind the median. Drives {@link #confidence}. */
    @Column(name = "sample_size")
    private Integer sampleSize;

    /** DVF vintage the median came from — the data lags the current year. */
    @Column(name = "source_year")
    private Short sourceYear;

    /** JSON breakdown of the applied adjustments, surfaced verbatim in the UI. */
    @Column(name = "method_detail", columnDefinition = "text")
    private String methodDetail;
}
