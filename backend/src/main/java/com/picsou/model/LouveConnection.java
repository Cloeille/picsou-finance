package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

/**
 * Per-member Louve Invest connection, shaped like {@link WiseConnection}: the OAuth token set
 * (encrypted, never returned to the client), a status and the last sync time; plus the pending
 * OAuth attempt while the member is on Louve's consent page.
 *
 * <p>Updates write only the changed columns, as for Wise: a status written after a failed sync
 * must not put back a token set a concurrent refresh rotated in between.
 */
@Entity
@DynamicUpdate
@Table(name = "louve_connection")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LouveConnection extends AuditableEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONNECTED = "CONNECTED";
    public static final String STATUS_ERROR = "ERROR";
    /** Louve refused the grant (revoked, expired, unknown client): only a new consent helps. */
    public static final String STATUS_REAUTH_REQUIRED = "REAUTH_REQUIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    /** The OAuth token set (JSON), AES-256-GCM encrypted. Null until the first consent. */
    @JsonIgnore
    @Column(name = "token_state", columnDefinition = "TEXT")
    private String tokenState;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_PENDING;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    /**
     * How many authorizations this row has stored. A new consent increments it; a refresh-token
     * rotation within the same grant does not. A callback or a sync that started under another
     * generation writes nothing.
     */
    @Column(name = "grant_generation", nullable = false)
    @Builder.Default
    private long grantGeneration = 0;

    /** The pending attempt's state nonce; single use, cleared when spent. */
    @JsonIgnore
    @Column(name = "oauth_state", length = 64)
    private String oauthState;

    /** The pending attempt's PKCE verifier, AES-256-GCM encrypted. */
    @JsonIgnore
    @Column(name = "oauth_verifier", columnDefinition = "TEXT")
    private String oauthVerifier;

    @JsonIgnore
    @Column(name = "oauth_redirect_uri", length = 512)
    private String oauthRedirectUri;

    @JsonIgnore
    @Column(name = "oauth_client_id", length = 255)
    private String oauthClientId;

    @JsonIgnore
    @Column(name = "oauth_started_at")
    private Instant oauthStartedAt;

    /** Clears the pending attempt: spent, abandoned or superseded. */
    public void clearAttempt() {
        oauthState = null;
        oauthVerifier = null;
        oauthRedirectUri = null;
        oauthClientId = null;
        oauthStartedAt = null;
    }
}
