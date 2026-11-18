package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * The client id Louve Invest issued to this Picsou instance for one redirect URI (RFC 7591
 * dynamic registration). Instance-wide: every member connecting through the same origin shares
 * it. A public client, so there is no secret to store.
 */
@Entity
@Table(name = "louve_oauth_client")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LouveOAuthClientRegistration extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "redirect_uri", nullable = false, length = 512, unique = true)
    private String redirectUri;

    @Column(name = "client_id", nullable = false, length = 255)
    private String clientId;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;
}
