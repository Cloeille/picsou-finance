package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The immutable terms of a peer loan. Paired 1:1 with a {@code PEER_LOAN} account, the
 * way {@link Debt} is paired with a {@code LOAN} account — but with no amortization
 * schedule: a loan between individuals is repaid irregularly.
 *
 * <p>{@code annual_rate} is null or zero for the common interest-free case. The
 * {@code planned_*} fields are a forecast only: they never generate a repayment, never
 * mark one as paid, and never drive the remaining balance.
 */
@Entity
@Table(name = "peer_loan_terms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PeerLoanTerms extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "counterparty_id", nullable = false)
    private PeerCounterparty counterparty;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal principal;

    @Column(name = "lent_at", nullable = false)
    private LocalDate lentAt;

    @Column(name = "expected_back")
    private LocalDate expectedBack;

    @Column(name = "annual_rate", precision = 5, scale = 4)
    private BigDecimal annualRate;

    @Column(name = "planned_monthly_amount", precision = 20, scale = 8)
    private BigDecimal plannedMonthlyAmount;

    @Column(name = "planned_months")
    private Integer plannedMonths;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_account_id")
    private Account linkedAccount;

    @Column(length = 500)
    private String note;

    @Column(name = "closed_at")
    private LocalDate closedAt;
}
