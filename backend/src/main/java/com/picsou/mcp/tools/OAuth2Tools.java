package com.picsou.mcp.tools;

import com.picsou.config.AccessKeyAuthentication;
import com.picsou.config.OAuthClientProperties;
import com.picsou.mcp.AccessKeyService;
import com.picsou.mcp.RequiresScope;
import com.picsou.mcp.Scopes;
import com.picsou.model.AccessKey;
import com.picsou.model.AppUser;
import com.picsou.service.MfaService;
import com.picsou.service.UserContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP tools over the OAuth2 authorization server ({@code /oauth2/**}), which today serves a single
 * first-party public client (the native iOS app, Authorization Code + PKCE, no client secret — see
 * {@code AuthorizationServerConfig}). Both tools here are read-only reflections of state that
 * already exists: the static server metadata, and the calling access-key's own status. Neither
 * touches the authorization-server filter chain, its {@code RegisteredClientRepository}, or issues
 * any token — see {@code STATUS.md} for why {@code request_oauth2_token} was not built.
 */
@Component
public class OAuth2Tools {

    private final AuthorizationServerSettings authorizationServerSettings;
    private final OAuthClientProperties oAuthClientProperties;
    private final AccessKeyService accessKeyService;
    private final MfaService mfaService;
    private final UserContext userContext;

    public OAuth2Tools(AuthorizationServerSettings authorizationServerSettings,
                       OAuthClientProperties oAuthClientProperties,
                       AccessKeyService accessKeyService,
                       MfaService mfaService,
                       UserContext userContext) {
        this.authorizationServerSettings = authorizationServerSettings;
        this.oAuthClientProperties = oAuthClientProperties;
        this.accessKeyService = accessKeyService;
        this.mfaService = mfaService;
        this.userContext = userContext;
    }

    /** Static discovery response: issuer/endpoint paths + this server's single client. No secrets. */
    public record OAuth2Configuration(
        String issuer,
        String authorizationEndpoint,
        String tokenEndpoint,
        String jwkSetEndpoint,
        String clientId,
        boolean pkceRequired,
        List<String> supportedScopes
    ) {}

    @Tool(name = "get_oauth2_configuration",
        description = "Get the Picsou OAuth2 authorization server's discovery metadata: issuer, "
            + "authorize/token/JWKS endpoint paths, the registered client id, and whether PKCE is "
            + "required. Read-only, no secrets. Every key can call this regardless of its other scopes.")
    @RequiresScope(Scopes.OAUTH2_DISCOVER)
    public OAuth2Configuration getOAuth2Configuration() {
        return new OAuth2Configuration(
            authorizationServerSettings.getIssuer(),
            authorizationServerSettings.getAuthorizationEndpoint(),
            authorizationServerSettings.getTokenEndpoint(),
            authorizationServerSettings.getJwkSetEndpoint(),
            oAuthClientProperties.getClientId(),
            true,
            List.of("read", "write")
        );
    }

    /**
     * The calling session's own status, plus its owner's MFA posture. Never another session's.
     *
     * <p>{@code source} distinguishes the two kinds of session that can authenticate {@code /mcp}
     * (see {@code AccessKeyAuthFilter}): {@code "access_key"} for a persisted {@code psk_…} key
     * (which has a {@code keyId} and row-backed metadata), or {@code "oauth2"} for a short-lived MCP
     * JWT minted by the embedded authorization server for a consenting remote-MCP client (e.g.
     * claude.ai) — which has neither an {@code AccessKey} row nor a {@code keyId}, so
     * {@code keyId}/{@code keyName}/{@code createdAt}/{@code lastUsedAt}/{@code expiresAt} are
     * {@code null} for that source; only the granted scopes and MFA posture are meaningful.
     */
    public record OAuth2SessionStatus(
        Long keyId,
        String keyName,
        Set<String> scopes,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        boolean mfaEnabled,
        String source
    ) {}

    private static final String SOURCE_ACCESS_KEY = "access_key";
    private static final String SOURCE_OAUTH2 = "oauth2";

    @Tool(name = "get_oauth2_session_status",
        description = "Get the status of the session this MCP call is authenticated with (an access-key "
            + "or an OAuth2 remote-MCP token): its name (access-keys only), granted scopes, "
            + "creation/last-used/expiry timestamps (access-keys only), whether the owning member has "
            + "MFA enabled, and a source marker distinguishing the two. A caller can only ever see its "
            + "own status.")
    @RequiresScope(Scopes.OAUTH2_SESSION_STATUS)
    public OAuth2SessionStatus getOAuth2SessionStatus() {
        AccessKeyAuthentication auth = currentAccessKeyAuthentication();
        AppUser owner = userContext.currentUser();
        boolean mfaEnabled = mfaService.isEnabled(owner);

        Long keyId = auth.getKeyId();
        if (keyId == null) {
            // OAuth2-issued MCP JWT session (Task 5/8): no AccessKey row exists, so build the status
            // straight from the authentication itself — its authorities ARE the granted scopes.
            Set<String> scopes = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            return new OAuth2SessionStatus(null, null, scopes, null, null, null, mfaEnabled, SOURCE_OAUTH2);
        }

        AccessKey key = accessKeyService.list(userContext.currentMemberId()).stream()
            .filter(k -> k.getId().equals(keyId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Access key not found for the current session"));
        return new OAuth2SessionStatus(
            key.getId(),
            key.getName(),
            key.getScopes(),
            key.getCreatedAt(),
            key.getLastUsedAt(),
            key.getExpiresAt(),
            mfaEnabled,
            SOURCE_ACCESS_KEY
        );
    }

    /**
     * Only an {@link AccessKeyAuthentication} ever calls MCP tools (Property A) — whether backed by
     * a persisted access-key or an OAuth2 MCP JWT (its {@code keyId} is {@code null} in the latter
     * case; see {@link #getOAuth2SessionStatus()}).
     */
    private AccessKeyAuthentication currentAccessKeyAuthentication() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof AccessKeyAuthentication keyAuth) {
            return keyAuth;
        }
        throw new IllegalStateException("MCP tools must run under an access-key session");
    }
}
