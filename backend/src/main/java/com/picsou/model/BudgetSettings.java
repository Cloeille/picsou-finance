package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Per-member budget configuration. {@code cycleStartDay} (1–28) is the payday the
 * monthly budget cycle resets on — the whole module reasons in these cycles rather
 * than calendar months. See {@code BudgetCycle}.
 */
@Entity
@Table(name = "budget_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BudgetSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private FamilyMember member;

    // Column is SMALLINT (value is 1–28); map the JDBC type explicitly so Hibernate
    // schema-validation on Postgres expects int2 rather than int4 for this int field.
    @Column(name = "cycle_start_day", nullable = false)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Builder.Default
    private int cycleStartDay = 1;

    /**
     * Opt-in toggle (OFF by default) for fetching brand logos online. When false, the
     * {@code GET /api/merchants/{id}/logo} proxy returns 404 and the UI shows offline
     * monograms only. Purely cosmetic — it never influences categorization (ADR 2026-06-02).
     */
    @Column(name = "logo_fetch_enabled", nullable = false)
    @Builder.Default
    private boolean logoFetchEnabled = false;

    /**
     * Opt-in toggle (OFF by default) for the AI categorizer. When false the optional LLM
     * fallback never runs and the deterministic pipeline (rules + brand KB) is the only path.
     */
    @Column(name = "ai_categorization_enabled", nullable = false)
    @Builder.Default
    private boolean aiCategorizationEnabled = false;

    /** How an AI suggestion is applied: suggest-only / auto on high confidence / auto-all. */
    @Enumerated(EnumType.STRING)
    @Column(name = "ai_mode", nullable = false, length = 24)
    @Builder.Default
    private AiCategorizationMode aiMode = AiCategorizationMode.AUTO_HIGH_CONFIDENCE;

    /** Sensitivity gate (0–100) for {@link AiCategorizationMode#AUTO_HIGH_CONFIDENCE}. */
    @Column(name = "ai_confidence_threshold", nullable = false)
    @Builder.Default
    private int aiConfidenceThreshold = 75;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
