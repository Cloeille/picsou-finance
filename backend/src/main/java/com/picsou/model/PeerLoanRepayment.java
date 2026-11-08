package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One dated event on a peer loan: a capital repayment, an interest payment, or a
 * write-off. These rows are the only source of truth for the remaining balance.
 */
@Entity
@Table(name = "peer_loan_repayment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PeerLoanRepayment extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PeerLoanRepaymentKind kind = PeerLoanRepaymentKind.PRINCIPAL;

    @Column(length = 255)
    private String note;

    /** The cash movement this row created on a manual linked account (null otherwise). */
    @Column(name = "cash_transaction_id")
    private Long cashTransactionId;

    /**
     * True when this service wrote the cash row and may update or delete it. False when
     * the repayment was linked from an existing incoming transaction: that row belongs to
     * the member (or the provider) and must never be duplicated or removed here.
     */
    @Column(name = "cash_leg_owned", nullable = false)
    @Builder.Default
    private boolean cashLegOwned = true;
}
