package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One fund a CORUM or Sofidy sync has seen for a member.
 *
 * <p>The account link lives on {@link ScpiPosition}; this row only remembers
 * the fund. {@code ignored} is the tombstone that keeps a deleted or unlinked
 * account from being recreated, and {@code lastOutcome} is what the latest sync
 * did with the fund, so the UI can report it.
 */
@Entity
@Table(name = "scpi_provider_fund")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScpiProviderFund extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScpiProvider provider;

    @Column(name = "fund_code", nullable = false, length = 40)
    private String fundCode;

    @Column(length = 200)
    private String label;

    @Column(nullable = false)
    @Builder.Default
    private boolean ignored = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_outcome", length = 24)
    private ScpiFundOutcome lastOutcome;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;
}
