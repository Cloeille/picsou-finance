package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "account")
@org.hibernate.annotations.SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "account_type")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.NAMED_ENUM)
    private AccountType type;

    @Column(length = 100)
    private String provider;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String currency = "EUR";

    @Column(name = "current_balance", nullable = false, precision = 20, scale = 8)
    @Builder.Default
    private BigDecimal currentBalance = BigDecimal.ZERO;

    /** Cash held inside an investment envelope (PEA/CTO); null for other providers. */
    @Column(name = "cash_balance", precision = 20, scale = 8)
    private BigDecimal cashBalance;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "external_account_id", length = 255)
    private String externalAccountId;

    @Column(name = "is_manual", nullable = false)
    @Builder.Default
    private boolean isManual = true;

    @Column(nullable = false, length = 7)
    @Builder.Default
    private String color = "#6366f1";

    /** Ticker symbol for live price lookup, e.g. "BTC", "IWDA.AS" */
    @Column(length = 20)
    private String ticker;

    /** Bank logo URL, captured from Enable Banking institution search. Null falls back to {@link #color}. */
    @Column(name = "logo_url", columnDefinition = "TEXT")
    private String logoUrl;

    /**
     * Which bundled frontend asset this account shows, e.g. {@code "ledger"}. Set by
     * {@link com.picsou.service.WalletSyncService} for on-chain wallets (whose {@code provider}
     * is a ticker, so nothing stable to key a logo on) and overridable by the user. The key is
     * opaque here -- the frontend owns the key -> asset mapping and falls back to
     * {@link #logoUrl}, then {@link #color}, when it is null or unknown.
     */
    @Column(name = "logo_key", length = 32)
    private String logoKey;

    /**
     * The Enable Banking connection this account came from, or null for every other origin
     * (manual, on-chain wallet, broker sidecar...) and for rows the V76 backfill could not
     * attribute with certainty. Mapped as a plain id rather than a {@code @ManyToOne}: nothing
     * needs to navigate to the requisition from here, and the association would drag a lazy
     * proxy through every account read for a column only the deletion path consults.
     */
    @Column(name = "requisition_id")
    private Long requisitionId;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
