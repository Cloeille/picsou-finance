package com.picsou.service;

import com.picsou.config.OAuthClientProperties;
import com.picsou.model.AppUser;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The native iOS app's sign-ins, seen as sessions: one per live {@code oauth2_authorization} row of
 * the {@code picsou-ios} client. An authorization survives refresh-token rotation, so it is the
 * device, not a single token.
 *
 * <p>Rows are matched on the user id of the principal captured at sign-in, never on
 * {@code principal_name}: that column freezes the username used at sign-in, and a later rename
 * keeps the device's tokens valid (it does not bump {@code tv}), so a name match would hide a live
 * device and make it unrevocable. Only rows whose captured token version still matches the
 * caller's are listed: a password change bumps {@code tv}, which already makes the API reject that
 * authorization's tokens, so listing it would show a dead device.
 *
 * <p>Revoking deletes the row. The refresh token dies with it, and so does the current access
 * token, because {@code JwtTokenAuthenticator} looks the token's {@code aid} claim up through
 * {@link #isAccessTokenActive} on every request.
 */
@Service
public class NativeAppSessionService {

    private static final String CANDIDATES_SQL = """
        SELECT id FROM oauth2_authorization
        WHERE registered_client_id = ? AND refresh_token_expires_at > ?
        """;

    private final JdbcOperations jdbc;
    private final OAuth2AuthorizationService authorizationService;
    private final RegisteredClientRepository registeredClientRepository;
    private final OAuthClientProperties clientProperties;

    public NativeAppSessionService(
        JdbcOperations jdbc,
        OAuth2AuthorizationService authorizationService,
        RegisteredClientRepository registeredClientRepository,
        OAuthClientProperties clientProperties
    ) {
        this.jdbc = jdbc;
        this.authorizationService = authorizationService;
        this.registeredClientRepository = registeredClientRepository;
        this.clientProperties = clientProperties;
    }

    /** {@code createdAt} is the authorization-code issue time; {@code lastUsedAt} the latest access token. */
    public record NativeAppSession(String id, Instant createdAt, Instant lastUsedAt, Instant expiresAt) {}

    /** The caller's live app sign-ins, most recently used first. */
    public List<NativeAppSession> listActive(AppUser user) {
        return activeAuthorizations(user).stream()
            .map(NativeAppSessionService::toSession)
            .sorted(Comparator.comparing(NativeAppSession::lastUsedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    /** Returns false when the id is unknown, not an app sign-in, or someone else's. */
    public boolean revoke(String authorizationId, AppUser user) {
        Optional<OAuth2Authorization> authorization = activeAuthorizations(user).stream()
            .filter(a -> a.getId().equals(authorizationId))
            .findFirst();
        authorization.ifPresent(authorizationService::remove);
        return authorization.isPresent();
    }

    /** Revokes every app sign-in of the caller except {@code keepId}, which may be null. */
    public void revokeAllExcept(AppUser user, String keepId) {
        activeAuthorizations(user).stream()
            .filter(a -> !a.getId().equals(keepId))
            .forEach(authorizationService::remove);
    }

    /** Whether the authorization an {@code aid} claim names still exists with a usable access token. */
    public boolean isAccessTokenActive(String authorizationId) {
        OAuth2Authorization authorization = authorizationService.findById(authorizationId);
        if (authorization == null) {
            return false;
        }
        OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
        return accessToken != null && !accessToken.isInvalidated();
    }

    private List<OAuth2Authorization> activeAuthorizations(AppUser user) {
        RegisteredClient client = registeredClientRepository.findByClientId(clientProperties.getClientId());
        if (client == null) {
            return List.of();
        }
        return jdbc.queryForList(CANDIDATES_SQL, String.class,
                client.getId(), Timestamp.from(Instant.now()))
            .stream()
            .map(authorizationService::findById)
            .filter(Objects::nonNull)
            .filter(a -> belongsTo(a, user))
            .filter(a -> a.getRefreshToken() != null && a.getRefreshToken().isActive())
            .toList();
    }

    private static boolean belongsTo(OAuth2Authorization authorization, AppUser user) {
        Authentication principal = authorization.getAttribute(Principal.class.getName());
        return principal != null
            && principal.getPrincipal() instanceof AppUser owner
            && Objects.equals(owner.getId(), user.getId())
            && owner.getTokenVersion() == user.getTokenVersion();
    }

    private static NativeAppSession toSession(OAuth2Authorization authorization) {
        OAuth2Authorization.Token<OAuth2AuthorizationCode> code = authorization.getToken(OAuth2AuthorizationCode.class);
        OAuth2Authorization.Token<OAuth2AccessToken> access = authorization.getAccessToken();
        OAuth2Authorization.Token<OAuth2RefreshToken> refresh = authorization.getRefreshToken();
        return new NativeAppSession(
            authorization.getId(),
            code != null ? code.getToken().getIssuedAt() : null,
            access != null ? access.getToken().getIssuedAt() : null,
            refresh.getToken().getExpiresAt());
    }
}
