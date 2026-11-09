package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Per-member Interactive Brokers Flex Web Service connection.
 *
 * <p>Holds the encrypted Flex token and query id used to pull Open Positions once
 * a day. Structurally identical to {@link FinarySession} / {@link BoursoSession}:
 * a single row per {@link FamilyMember}, credentials encrypted at rest by
 * {@code CryptoEncryption}. No account balances live here — those become
 * {@link Account} + {@link AccountHolding} rows at sync time.
 */
@Entity
@Table(name = "ibkr_connection")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IbkrConnection extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    /** Flex Web Service token, AES-256-GCM encrypted. */
    @Column(nullable = false, length = 500)
    private String token;

    /** Flex Query id, AES-256-GCM encrypted. */
    @Column(name = "query_id", nullable = false, length = 500)
    private String queryId;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "CONNECTED";

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;
}
