package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * A normalized pattern that resolves a transaction's {@code merchant_label} to a
 * {@link MerchantBrand}. Many aliases point at one brand (a brand may appear as several
 * tokens or phrases on bank statements).
 *
 * <p>{@code brandId} is stored as a raw FK id (not a {@code @ManyToOne}) on purpose:
 * {@link com.picsou.service.budget.MerchantKnowledgeBase} loads <em>all</em> brands and
 * aliases once at startup and joins them in memory, so a managed association would only add
 * lazy-loading overhead for no benefit.
 *
 * <p>{@code matchType} is a plain {@code VARCHAR} (see {@link MatchType}) rather than a native
 * PG enum, so the set can grow without an append-only enum migration.
 */
@Entity
@Table(name = "merchant_alias")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantAlias {

    /** How an alias {@link #pattern} is tested against a normalized merchant match-key. */
    public enum MatchType {
        /** {@code pattern} is a single token; matches when it appears as a whole word. */
        WORD,
        /** {@code pattern} is a multi-word substring; matches on a word-boundary containment. */
        PHRASE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "brand_id", nullable = false)
    private Long brandId;

    /** Already lower-cased / accent-free in the seed, to match {@code MerchantNormalizer.matchKey}. */
    @Column(nullable = false, length = 120)
    private String pattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 10)
    private MatchType matchType;
}
