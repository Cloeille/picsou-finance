package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "degiro_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DegiroSession extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    /**
     * Serialized {sessionId, intAccount}, encrypted at rest via CryptoEncryption.
     * Never a TOTP secret — see docs/decisions/2026-08-05-degiro-session-only-no-stored-totp.md.
     * TEXT, not a guessed VARCHAR: same class of failure as GH issue #115 (TR token outgrew its column).
     */
    @Column(name = "session_blob", nullable = false, columnDefinition = "TEXT")
    private String sessionBlob;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private DegiroSessionStatus status = DegiroSessionStatus.ACTIVE;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    /** Short error code (e.g. SESSION_EXPIRED, INVALID_CREDENTIALS) — set only when status = FAILED. */
    @Column(name = "last_error", length = 40)
    private String lastError;
}
