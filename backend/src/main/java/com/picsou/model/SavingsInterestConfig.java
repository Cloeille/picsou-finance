package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * 1:1 companion table for accounts that are savings books (livrets).
 * Stores product type, annual rate, and tax parameters needed to compute
 * a projected interest figure.
 *
 * <p><strong>Guardrail:</strong> this config is read-only from the interest engine's
 * perspective — interest projections are NEVER written back to
 * {@code account.current_balance} or {@code balance_snapshot}.</p>
 */
@Entity
@Table(name = "savings_interest_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SavingsInterestConfig extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    /** Savings product category — drives rate validation rules. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "savings_product")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.NAMED_ENUM)
    private SavingsProduct product;

    /**
     * Annual interest rate expressed as a <em>percentage</em> (e.g. {@code 2.40} for 2.40 %).
     * User-overridable; regulated defaults come from {@link com.picsou.service.RegulatedRates}.
     */
    @Column(name = "annual_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal annualRate;

    /**
     * Whether the {@link #annualRate} is gross (before tax) or net (after tax).
     * Regulated products always use NET; only meaningful for {@code COMMERCIAL}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rate_basis", nullable = false, columnDefinition = "rate_basis")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.NAMED_ENUM)
    @Builder.Default
    private RateBasis rateBasis = RateBasis.NET;

    /**
     * Tax rate applied to gross interest for {@code COMMERCIAL + GROSS} configurations,
     * expressed as a percentage (e.g. {@code 30.00} for the flat-rate PFU).
     * Null for regulated products and {@code COMMERCIAL + NET}.
     */
    @Column(name = "tax_rate_pct", precision = 5, scale = 2)
    private BigDecimal taxRatePct;

    /**
     * Regulatory deposit ceiling in EUR (e.g. 22 950 for Livret A).
     * Informational — not enforced by this service.
     */
    @Column(precision = 20, scale = 2)
    private BigDecimal ceiling;
}
