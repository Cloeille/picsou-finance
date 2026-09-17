package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

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

    @Column(name = "external_account_id", length = 100)
    private String externalAccountId;

    @Column(name = "is_manual", nullable = false)
    @Builder.Default
    private boolean isManual = true;

    @Column(nullable = false, length = 7)
    @Builder.Default
    private String color = "#6366f1";

    /**
     * IBAN when provided by the bank (e.g. via Open Banking).
     * Used as a stable match key across provider uid changes (e.g. Enable Banking v0.16.4).
     * NULL for accounts without an IBAN (crypto, pocket sub-accounts, etc.).
     */
    @Column(length = 34)
    private String iban;

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

    /**
     * When the wrapper was opened, as the member states it — not when Picsou learned of it.
     *
     * <p>Load-bearing for anything fiscal: a PEA's exemption turns on its fifth anniversary, an
     * assurance-vie's on its eighth. {@code createdAt} cannot stand in — a plan opened in 2014
     * and typed in last month has ten years between the two.
     */
    @Column(name = "opened_at")
    private LocalDate openedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * For Revolut pockets only: points to the parent wallet account.
     * NULL for all normal accounts.
     */
    @Column(name = "parent_account_id")
    private Long parentAccountId;

    @Column(nullable = false)
    @Builder.Default
    private boolean hidden = false;
}
