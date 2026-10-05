package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * One member's target allocation, and the monthly figure the safety-net target is derived from.
 *
 * <p>Absence is meaningful, like {@link AccountOwnership}: a member with no row uses the shipped
 * defaults below, so introducing this table needed no backfill. Persisting only happens when the
 * user actually edits their targets.
 *
 * <p>{@code monthlyEssentialExpenses} is nullable and stays that way until the user says so. The
 * estimate derived from their transactions is offered by the API but never written on their
 * behalf — a guessed number silently promoted to a stored one is indistinguishable from a number
 * the user vouched for, and the safety-net score depends entirely on it.
 */
@Entity
@Table(
    name = "member_allocation_profile",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_member_allocation_profile_member",
        columnNames = "member_id"
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemberAllocationProfile extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    /** Compulsory monthly spend; {@code null} until the user states it. */
    @Column(name = "monthly_essential_expenses", precision = 20, scale = 2)
    private BigDecimal monthlyEssentialExpenses;

    /** Months of expenses the safety net should cover. {@code Short} to match {@code SMALLINT}. */
    @Column(name = "safety_net_months", nullable = false)
    @Builder.Default
    private Short safetyNetMonths = 6;

    @Column(name = "real_estate_pct", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal realEstatePct = new BigDecimal("30.00");

    @Column(name = "equity_pct", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal equityPct = new BigDecimal("50.00");

    @Column(name = "crypto_pct", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal cryptoPct = new BigDecimal("10.00");

    @Column(name = "alternative_pct", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal alternativePct = new BigDecimal("10.00");

    /** The target for one tier, or {@code null} for {@link WealthTier#SAFETY_NET} — whose target
     *  is an absolute amount, not a share. */
    public BigDecimal targetFor(WealthTier tier) {
        return switch (tier) {
            case SAFETY_NET  -> null;
            case REAL_ESTATE -> realEstatePct;
            case EQUITY      -> equityPct;
            case CRYPTO      -> cryptoPct;
            case ALTERNATIVE -> alternativePct;
        };
    }
}
