package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "trade_republic_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeRepublicSession extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Column(name = "session_token", nullable = false, columnDefinition = "TEXT")
    private String sessionToken;

    /** Refresh token — valid ~2h, used to obtain a new session token without 2FA. */
    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken;

    /** Set to now + 2h based on observed refresh token expiry. */
    @Column(name = "expires_at")
    private Instant expiresAt;
}
