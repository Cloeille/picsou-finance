package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.AuthenticatedPrincipal;

@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser extends AuditableEntity implements AuthenticatedPrincipal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private FamilyMember member;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private UserRole role = UserRole.MEMBER;

    @Column(name = "is_activated", nullable = false)
    @Builder.Default
    private boolean activated = true;

    @Column(name = "activation_token", length = 64)
    private String activationToken;

    @Column(name = "activation_token_expires")
    private java.time.Instant activationTokenExpires;

    @Column(name = "acknowledged_warning", nullable = false)
    @Builder.Default
    private boolean acknowledgedWarning = false;

    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private long tokenVersion = 0L;

    /**
     * Stable, value-based principal name for Spring Security.
     *
     * <p>Without this, {@code AbstractAuthenticationToken.getName()} falls back to
     * {@code Object#toString()} — an identity-hash string that differs on every freshly-loaded
     * JPA instance of the same user. That breaks two things that compare a principal name across
     * requests: the OAuth2 authorization-consent handshake (the {@code GET}/{@code POST
     * /oauth2/authorize} legs each load a fresh {@code AppUser} via the cookie bridge, so Spring
     * Authorization Server's parked-authorization principal-name check never matches) and
     * {@code /api/connected-apps}, which filters {@code oauth2_authorization.principal_name}
     * against {@code AppUser.getUsername()}. Implementing {@link AuthenticatedPrincipal} makes
     * {@code getName()} return the stable username instead.
     */
    @Override
    public String getName() {
        return username;
    }
}
