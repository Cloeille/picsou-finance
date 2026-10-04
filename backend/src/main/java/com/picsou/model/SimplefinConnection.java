package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Per-member SimpleFIN connection.
 *
 * <p>Holds the encrypted access URL claimed from a one-time setup token. The URL
 * embeds HTTP Basic credentials, so it is encrypted at rest and never returned
 * to the client. Balances and transactions become {@link Account} rows at sync time.
 */
@Entity
@Table(name = "simplefin_connection")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SimplefinConnection extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    /** Access URL (with Basic Auth userinfo), AES-256-GCM encrypted. */
    @Column(name = "access_url", nullable = false, columnDefinition = "TEXT")
    private String accessUrl;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "CONNECTED";

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;
}
