package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * A known merchant/brand in the bundled, offline knowledge base ({@code V37} seed).
 *
 * <p>Unlike {@link Category} or {@link CategorizationRule}, this table is <b>global</b> —
 * not member-scoped. It is read-only at runtime (seeded by migration, never written by the
 * app) and loaded once into {@link com.picsou.service.budget.MerchantKnowledgeBase}. The
 * link to a member's own categories is indirect, through {@link #defaultCategorySlug}: the
 * categorizer resolves that slug against the member's {@link Category#getSlug() category slugs}.
 *
 * <p>Privacy note (ADR 2026-06-02): categorization is fully offline. {@link #logoDomain} is
 * only used when the member opts into online logo fetching; it never influences matching.
 */
@Entity
@Table(name = "merchant_brand")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantBrand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable unique key for the brand ({@code "carrefour"}, {@code "netflix"}). */
    @Column(nullable = false, length = 60, unique = true)
    private String slug;

    /** Human-readable name shown in the UI ({@code "Carrefour"}, {@code "Netflix"}). */
    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    /** Target {@link Category#getSlug() category slug} this brand maps to ({@code "courses"}). */
    @Column(name = "default_category_slug", nullable = false, length = 60)
    private String defaultCategorySlug;

    /** Brand colour (hex) for the offline monogram avatar; nullable. */
    @Column(length = 7)
    private String color;

    /** 1–2 letter monogram for the offline avatar; nullable (derived from name if absent). */
    @Column(length = 4)
    private String monogram;

    /** Domain used only for opt-in online logo fetching ({@code "carrefour.fr"}); nullable. */
    @Column(name = "logo_domain", length = 120)
    private String logoDomain;
}
