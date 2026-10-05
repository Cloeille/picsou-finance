package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "persistent_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PersistentSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "series_id", nullable = false, unique = true)
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID seriesId;

    @Column(name = "token_hash", nullable = false, columnDefinition = "TEXT")
    private String tokenHash;

    // The immediately-previous token hash, kept for a short grace window so that
    // concurrent multi-tab restores (which all present the pre-rotation token)
    // are accepted instead of tripping theft detection. Null until first rotation.
    @Column(name = "previous_token_hash", columnDefinition = "TEXT")
    private String previousTokenHash;

    @Column(name = "previous_token_at")
    private Instant previousTokenAt;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "ip_prefix", length = 45)
    private String ipPrefix;

    @Column(name = "trusted_for_2fa", nullable = false)
    @Builder.Default
    private boolean trustedFor2fa = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (lastUsedAt == null) lastUsedAt = now;
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
