package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(
    name = "balance_snapshot",
    uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "date"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BalanceSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal balance;

    @Column(name = "invested_amount", nullable = false, precision = 20, scale = 8)
    @Builder.Default
    private BigDecimal investedAmount = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private java.time.Instant createdAt = java.time.Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private SnapshotSource source = SnapshotSource.DERIVED;

    @Column(name = "native_balance", precision = 20, scale = 8)
    private BigDecimal nativeBalance;

    @Column(name = "statement_entered_at")
    private java.time.Instant statementEnteredAt;
}
