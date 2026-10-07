package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.util.Locale;

/**
 * A user's own verdict on what a security actually is, overriding whatever the account type and
 * the price providers would infer.
 *
 * <p>It exists because the wrapper does not determine the asset: a gold ETC and a bitcoin ETP
 * both live in an ordinary brokerage account, and counting them as listed equity would misstate
 * two tiers of the pyramid at once.
 *
 * <p>Keyed on {@code (member, ticker)} rather than on the holding row, and kept out of
 * {@code account_holding} entirely: the sync paths delete holding rows a provider stops
 * reporting, so an override living there would disappear with the first transient gap. Here it
 * outlives the prune, the re-sync and the account, and one row covers the same ticker held in
 * several accounts.
 */
@Entity
@Table(
    name = "holding_classification",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_holding_classification_member_ticker",
        columnNames = {"member_id", "ticker"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HoldingClassification extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Column(nullable = false, length = 30)
    private String ticker;

    /**
     * Upper-cases the ticker on the way in, because the uniqueness that matters is
     * case-insensitive and the database constraint is not.
     *
     * <p>{@code UNIQUE (member_id, ticker)} would accept both {@code gold} and {@code GOLD} for
     * one member, while every reader keys its override map by the upper-cased ticker — so the
     * row that won would depend on the order they came back in. Normalising here rather than
     * widening the constraint to {@code UPPER(ticker)} keeps the invariant at the only gate that
     * writes this table, and leaves a migration that is already applied on running instances
     * alone.
     */
    @PrePersist
    @PreUpdate
    private void normaliseTicker() {
        if (ticker != null) ticker = ticker.trim().toUpperCase(Locale.ROOT);
    }

    /** {@code null} leaves the inferred value in place — each field overrides independently. */
    @Enumerated(EnumType.STRING)
    @Column(name = "wealth_tier", length = 20)
    private WealthTier wealthTier;

    /** Morningstar sector key, e.g. {@code technology}. */
    @Column(name = "sector_key", length = 40)
    private String sectorKey;

    /** ISO 3166-1 alpha-2. */
    @Column(name = "country_key", length = 2)
    private String countryKey;
}
