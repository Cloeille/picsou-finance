package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One member's personal and fiscal context.
 *
 * <p>Picsou knows what someone owns and nothing about the person holding it. The same portfolio
 * reads differently at 25 in the 11% bracket than at 58 in the 41% one, and this is where that
 * difference is recorded.
 *
 * <p>Absence is meaningful, like {@link MemberAllocationProfile} and {@link AccountOwnership}: a
 * member with no row has stated nothing, so introducing the table needed no backfill and reading
 * a profile never writes one. Every field is nullable for the same reason — "not stated" has to
 * stay distinguishable from a value, because a guessed figure promoted to a stored one is
 * indistinguishable from one the member vouched for.
 *
 * <p>The birth date is stored, never the age. An age is wrong the morning after a birthday and
 * nothing here would ever correct it; {@code MemberProfileService} derives it on read.
 */
@Entity
@Table(
    name = "member_profile",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_member_profile_member",
        columnNames = "member_id"
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemberProfile extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    /** The marginal bracket in percent (0, 11, 30, 41, 45 in France), not as a ratio. */
    @Column(name = "marginal_tax_rate", precision = 5, scale = 2)
    private BigDecimal marginalTaxRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "household_status", length = 20)
    private HouseholdStatus householdStatus;

    /** Half-parts are real, hence the scale: a single parent with one child is 2.0, not 2. */
    @Column(name = "tax_household_parts", precision = 4, scale = 2)
    private BigDecimal taxHouseholdParts;

    /** {@code Short} to match SMALLINT. */
    @Column(name = "dependents")
    private Short dependents;

    /** Gross: the figure a payslip states. Fiscal context; nothing is computed from it. */
    @Column(name = "annual_gross_income", precision = 20, scale = 2)
    private BigDecimal annualGrossIncome;

    /**
     * The payslip's "net à payer avant impôt sur le revenu" — after social contributions,
     * before withholding. Half of what the savings rate is measured against.
     */
    @Column(name = "monthly_net_before_tax", precision = 20, scale = 2)
    private BigDecimal monthlyNetBeforeTax;

    /** Taux de prélèvement à la source, in percent. The other half. */
    @Column(name = "withholding_tax_rate", precision = 5, scale = 2)
    private BigDecimal withholdingTaxRate;

    @Column(name = "monthly_savings_capacity", precision = 20, scale = 2)
    private BigDecimal monthlySavingsCapacity;

    @Column(name = "target_retirement_age")
    private Short targetRetirementAge;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_profile", length = 20)
    private RiskProfile riskProfile;
}
