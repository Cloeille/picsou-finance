package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A detected (or manually declared) recurring cash movement — a subscription, a direct
 * debit, a regular salary. {@code RecurringDetectionService} groups a member's transactions
 * by normalised counterparty and creates these as {@link RecurringStatus#SUGGESTED}; the user
 * then confirms or ignores them. Confirmed series drive the upcoming-due-date calendar.
 */
@Entity
@Table(name = "recurring_series")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecurringSeries extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Column(nullable = false, length = 255)
    private String label;

    @Column(length = 255)
    private String counterparty;

    /** Signed like a transaction: negative for an outflow (debit), positive for income. */
    @Column(name = "expected_amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal expectedAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "recurring_cadence")
    private RecurringCadence cadence;

    @Column(name = "next_due_date")
    private LocalDate nextDueDate;

    @Column(name = "last_seen_date")
    private LocalDate lastSeenDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "recurring_status")
    @Builder.Default
    private RecurringStatus status = RecurringStatus.SUGGESTED;

    // ─── Detection v2 (M3) ──────────────────────────────────────────────────────

    /** Detector confidence in [0,1] (regularity + amount stability + occurrence count); null when manually declared. */
    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    /** Smallest / largest observed occurrence amount (signed), describing the series' amount envelope. */
    @Column(name = "amount_min", precision = 20, scale = 2)
    private BigDecimal amountMin;

    @Column(name = "amount_max", precision = 20, scale = 2)
    private BigDecimal amountMax;

    /** True when the amount legitimately drifts each period (e.g. a utility bill) rather than being fixed. */
    @Column(name = "is_variable", nullable = false)
    @Builder.Default
    private boolean isVariable = false;

    /** The expected amount before the last detected price change; null when the price has never moved. */
    @Column(name = "previous_amount", precision = 20, scale = 2)
    private BigDecimal previousAmount;

    /** When {@link #expectedAmount} last changed; drives the "price changed" activity-feed alert. */
    @Column(name = "price_changed_at")
    private LocalDate priceChangedAt;

    /**
     * True when the detector confirmed this series <em>silently</em> (high confidence) rather than the
     * user confirming it. The activity feed surfaces these and {@code undo} reverses them — the safety
     * net for "auto-confirmed ≠ unexplained".
     */
    @Column(name = "auto_confirmed", nullable = false)
    @Builder.Default
    private boolean autoConfirmed = false;
}
