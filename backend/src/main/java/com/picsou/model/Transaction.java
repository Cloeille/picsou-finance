package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "transaction")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal amount;

    @Column(length = 100)
    private String type;

    @Column(length = 100)
    private String category;

    @Column(name = "native_currency", nullable = false, length = 10)
    @Builder.Default
    private String nativeCurrency = "EUR";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "is_manual", nullable = false)
    @Builder.Default
    private boolean isManual = false;

    /**
     * Dedup key for provider-imported rows: the bank's own entry reference when it
     * sends one, otherwise a content fingerprint computed by the importer. Null for
     * manual rows and for importers that replace their rows wholesale (Finary).
     */
    @Column(name = "external_transaction_id", length = 128)
    private String externalTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_type", length = 20)
    private TransactionType txType;

    @Column(name = "ticker", length = 30)
    private String ticker;

    /** Human-readable security name (distinct from the row description). Used to label the derived position. */
    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "quantity", precision = 20, scale = 8)
    private BigDecimal quantity;

    @Column(name = "price_per_unit", precision = 20, scale = 8)
    private BigDecimal pricePerUnit;

    /** Broker/transaction fees. Null (no fee recorded) is treated as zero downstream. */
    @Column(name = "fees", precision = 20, scale = 8)
    private BigDecimal fees;
}
