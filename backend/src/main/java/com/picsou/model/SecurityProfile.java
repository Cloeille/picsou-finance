package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * What a security is, cached durably so a portfolio-wide breakdown never has to ask the network.
 *
 * <p>Global rather than member-scoped: a sector is not private data. The read path reads only
 * this table; {@code SchedulerService} refreshes it weekly, and a ticker nobody has warmed yet
 * simply reports as unclassified rather than blocking a page render on a scrape.
 */
@Entity
@Table(
    name = "security_profile",
    uniqueConstraints = @UniqueConstraint(name = "uk_security_profile_ticker", columnNames = "ticker")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SecurityProfile extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String ticker;

    /** ETF / STOCK / CRYPTO / UNKNOWN, as {@code SecurityInsightService} classifies it. */
    @Column(name = "asset_type", nullable = false, length = 20)
    private String assetType;

    /** A single share's sector; an ETF carries its breakdown in {@link #slices} instead. */
    @Column(name = "sector_key", length = 40)
    private String sectorKey;

    @Column(name = "country_key", length = 2)
    private String countryKey;

    /**
     * The security's ISIN, when a sync supplied one or a lookup recovered it.
     *
     * <p>Kept because it is the identifier the sources actually resolve: Boursorama's search
     * maps an ISIN to the same symbol as the ticker, and it is the only key a fund-facts lookup
     * has. Holdings store a Yahoo ticker converted from it and drop the original.
     */
    @Column(length = 12)
    private String isin;

    @Column(length = 60)
    private String source;

    /** The provider's own "portfolio as of" date, when it publishes one. */
    @Column(name = "as_of")
    private LocalDate asOf;

    /**
     * When we last asked. Drives the weekly refresh, and is not the same as {@link #asOf}.
     *
     * <p>Null on a row a sync seeded from an ISIN alone — never resolved, and therefore due.
     */
    @Column(name = "refreshed_at")
    private Instant refreshedAt;

    /** Total expense ratio in percent per year; 0.380 means 0.38 %/yr. */
    @Column(name = "ter_percent", precision = 6, scale = 3)
    private java.math.BigDecimal terPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "distribution_policy", length = 16)
    private DistributionPolicy distributionPolicy;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Replication replication;

    /**
     * Where the fund is domiciled, ISO 3166-1 alpha-2.
     *
     * <p>Not part of the geographic breakdown and not to be folded into it: an Irish-domiciled
     * MSCI World is a legal fact about the wrapper, not an exposure to Ireland.
     */
    @Column(name = "domicile_country", length = 2)
    private String domicileCountry;

    /** What the last lookup did. Separates "nothing to find" from "the lookup broke". */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private SecurityProfileStatus status = SecurityProfileStatus.NEVER_FETCHED;

    /** Why the last attempt failed, truncated. Diagnostic only — never shown to a member. */
    @Column(name = "last_error", length = 200)
    private String lastError;

    /**
     * When we last <em>tried</em>, successful or not.
     *
     * <p>Distinct from {@link #refreshedAt}, which only moves on success. Keeping the two apart
     * is what lets a failure be retried in days without a success being re-scraped weekly.
     */
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<SecurityCompositionSlice> slices = new ArrayList<>();

    /** Replaces the whole breakdown; orphanRemoval deletes whatever the provider dropped. */
    public void replaceSlices(List<SecurityCompositionSlice> replacements) {
        slices.clear();
        replacements.forEach(s -> s.setProfile(this));
        slices.addAll(replacements);
    }
}
