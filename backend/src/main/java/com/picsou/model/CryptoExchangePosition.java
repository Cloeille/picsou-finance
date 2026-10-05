package com.picsou.model;

import com.picsou.port.CryptoExchangePort.Product;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One asset held under one product on a crypto exchange — the breakdown behind an
 * {@link AccountHolding}.
 *
 * <p>Deliberately <em>not</em> a product column on {@code account_holding}: that table is the
 * valuation model shared by every connector (banks, brokers, wallets, Finary), unique on
 * {@code (account_id, ticker)}, and splitting it per product would ripple into each of them plus
 * the dashboard aggregations. These rows are display-only and derived: a sync rewrites them
 * wholesale, and nothing computes net worth from them.
 */
@Entity
// Declared for readers and schema-validation tooling only — Flyway owns the schema, and V74
// already creates this constraint. It is what makes the delete-then-insert rewrite in
// CryptoExchangeSyncService.replacePositions a hard requirement rather than a style choice.
@Table(name = "crypto_exchange_position", uniqueConstraints = @UniqueConstraint(
    name = "uk_crypto_exchange_position_account_product_ticker",
    columnNames = {"account_id", "product", "ticker"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CryptoExchangePosition extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Product product;

    @Column(nullable = false, length = 30)
    private String ticker;

    /** What is held, and what the matching {@link AccountHolding} sums across products. */
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal quantity;

    /** The capital part of {@link #quantity}, or null when the exchange doesn't distinguish it. */
    @Column(precision = 20, scale = 8)
    private BigDecimal principal;

    /** Yield already included in {@link #quantity} — a decomposition of it, never an addition. */
    @Column(precision = 20, scale = 8)
    private BigDecimal interest;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;
}
