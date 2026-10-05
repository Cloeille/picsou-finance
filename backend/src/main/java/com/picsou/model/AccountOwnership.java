package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * One member's share of one account.
 *
 * <p>Absence is meaningful: an account with <em>no</em> rows belongs entirely to
 * {@code account.member}. Rows only appear once a property (or its loan) is actually
 * split, which is why introducing this table needed no backfill.
 *
 * <p>Shares are resolved exclusively through {@code AccountAccessResolver} — never by
 * querying this repository from a service that also does authorization.
 */
@Entity
@Table(
    name = "account_ownership",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_account_ownership_account_member",
        columnNames = {"account_id", "member_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountOwnership extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    /** Percentage in (0, 100]. The per-account sum must stay ≤ 100. */
    @Column(name = "share_percent", nullable = false, precision = 6, scale = 3)
    private BigDecimal sharePercent;
}
