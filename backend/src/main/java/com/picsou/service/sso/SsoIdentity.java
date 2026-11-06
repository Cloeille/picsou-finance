package com.picsou.service.sso;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public record SsoIdentity(
    String issuer,
    String subject,
    String email,
    String displayName,
    String preferredUsername,
    Instant authTime,
    Optional<Set<String>> groups
) {

    /** Reads the merged ID-token and userinfo claims; {@code authTime} may be null. */
    public static SsoIdentity from(OidcUser user, String groupsClaim) {
        return new SsoIdentity(
            user.getIdToken().getIssuer().toString(),
            user.getSubject(),
            user.getEmail(),
            user.getFullName(),
            user.getPreferredUsername(),
            user.getIdToken().getAuthenticatedAt(),
            GroupsClaimParser.parse(user.getClaims().get(groupsClaim)));
    }
}
