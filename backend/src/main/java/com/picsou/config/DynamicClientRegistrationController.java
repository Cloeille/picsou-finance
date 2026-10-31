package com.picsou.config;

import com.picsou.dto.ClientRegistrationRequest;
import com.picsou.dto.ClientRegistrationResponse;
import com.picsou.mcp.Scopes;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * RFC 7591 Dynamic Client Registration: {@code POST /oauth2/register}.
 *
 * <p>Unauthenticated by design — this is how a remote-MCP client (claude.ai) self-registers before
 * the OAuth handshake starts, exactly like {@link ProtectedResourceMetadataController}. Spring
 * Authorization Server 1.4.5 has no built-in (non-OIDC) client-registration endpoint, so this is a
 * plain controller writing directly to the shared {@link RegisteredClientRepository} (the same Jdbc
 * repository {@link AuthorizationServerConfig} seeds {@code picsou-ios} into) rather than a
 * configured {@code clientRegistrationEndpoint()} — which would fold registration into the AS's own
 * securityMatcher chain and require its own authentication story.
 *
 * <p>Every client created here is deliberately narrow:
 * <ul>
 *   <li><b>Public, PKCE-only.</b> {@code client_authentication_method} is always coerced to
 *       {@code none} — no client secret is ever generated, regardless of what the request asks for.</li>
 *   <li><b>Fixed grant types.</b> Always {@code authorization_code} + {@code refresh_token}; the
 *       request's {@code grant_types} (if any) is accepted for RFC shape but ignored.</li>
 *   <li><b>Consent required.</b> Unlike {@code picsou-ios} (first-party, no consent), every
 *       DCR-registered client requires the interactive consent screen (Task 10/11).</li>
 *   <li><b>Flagged MCP.</b> Carries the {@link AuthorizationServerConfig#MCP_CLIENT_SETTING} setting
 *       so {@link AuthorizationServerConfig#jwtTokenCustomizer()} mints the MCP claim shape
 *       ({@code type=mcp}, {@code aud=picsou-mcp}) instead of the first-party one.</li>
 *   <li><b>Scope-limited.</b> Requested scopes must be a subset of {@link Scopes#ALL}; an unknown
 *       scope is rejected outright rather than silently dropped.</li>
 *   <li><b>Rotating refresh tokens, same TTLs as {@code picsou-ios}.</b> {@link OAuthClientProperties}
 *       is the single source of truth for access/refresh-token lifetimes — without an explicit
 *       {@code tokenSettings(...)}, Spring AS's own defaults (non-rotating refresh tokens, 5m/60m
 *       TTLs) would silently apply instead.</li>
 *   <li><b>{@code redirect_uris} scheme-restricted (RFC 8252).</b> Only {@code https://} or a
 *       loopback {@code http://127.0.0.1} / {@code http://localhost} URI is accepted — never a
 *       plain {@code http://} to a non-loopback host, and never an arbitrary custom scheme (the
 *       {@code picsou://callback} convention is reserved for the first-party iOS client, seeded
 *       separately, never through this endpoint).</li>
 *   <li><b>Rate-limited per IP.</b> Being unauthenticated-by-design (previous bullet point aside)
 *       makes this endpoint the one place on the AS surface anyone can hit with no credentials at
 *       all; {@link #registerBuckets} bounds it the same way {@code RateLimitConfig}'s other
 *       unauthenticated endpoints (login, setup) are bounded.</li>
 * </ul>
 */
@RestController
public class DynamicClientRegistrationController {

    private static final List<String> GRANT_TYPES = List.of("authorization_code", "refresh_token");

    /** V54 {@code oauth2_registered_client.client_name varchar(200)}. */
    private static final int CLIENT_NAME_MAX_LENGTH = 200;

    /**
     * V54 {@code oauth2_registered_client.redirect_uris} / {@code .scopes varchar(1000)}. Both
     * columns are comma-joined by {@code JdbcRegisteredClientRepository}'s own parameter mapper
     * ({@code StringUtils.collectionToCommaDelimitedString}), so the length to guard is the joined
     * string's, not any individual entry's.
     */
    private static final int DELIMITED_COLUMN_MAX_LENGTH = 1000;

    private final RegisteredClientRepository registeredClientRepository;
    private final OAuthClientProperties oAuthClientProperties;
    private final Map<String, Bucket> registerBuckets;

    public DynamicClientRegistrationController(
        RegisteredClientRepository registeredClientRepository,
        OAuthClientProperties oAuthClientProperties,
        @Qualifier("oauthRegisterBuckets") Map<String, Bucket> registerBuckets
    ) {
        this.registeredClientRepository = registeredClientRepository;
        this.oAuthClientProperties = oAuthClientProperties;
        this.registerBuckets = registerBuckets;
    }

    @PostMapping("/oauth2/register")
    public ResponseEntity<?> register(@RequestBody ClientRegistrationRequest request, HttpServletRequest httpRequest) {
        String ip = getClientIp(httpRequest);
        Bucket bucket = registerBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createOauthRegisterBucket());
        if (!bucket.tryConsume(1)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many client registrations. Try again later.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }

        List<String> redirectUris = validateRedirectUris(request.redirectUris());
        Set<String> scopes = resolveScopes(request.scope());

        String clientId = UUID.randomUUID().toString();
        Instant issuedAt = Instant.now();
        String clientName = (request.clientName() == null || request.clientName().isBlank())
            ? "Remote MCP client" : request.clientName();
        validateFieldLengths(clientName, redirectUris, scopes);

        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(clientId)
            .clientIdIssuedAt(issuedAt)
            .clientName(clientName)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .scopes(s -> s.addAll(scopes))
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)                // PKCE (S256) required — no client secret exists
                .requireAuthorizationConsent(true)     // third-party client → interactive consent
                .setting(AuthorizationServerConfig.MCP_CLIENT_SETTING, true)
                .build())
            // Same rotation/TTL policy as picsou-ios (buildIosClient): without this, Spring AS's own
            // TokenSettings defaults apply instead — reuseRefreshTokens=true, 5m/60m TTLs — silently
            // contradicting the "refresh tokens rotate" design for every DCR-registered client.
            .tokenSettings(TokenSettings.builder()
                .accessTokenTimeToLive(Duration.ofMinutes(oAuthClientProperties.getAccessTokenTtlMinutes()))
                .refreshTokenTimeToLive(Duration.ofDays(oAuthClientProperties.getRefreshTokenTtlDays()))
                .reuseRefreshTokens(false)
                .build());
        redirectUris.forEach(builder::redirectUri);

        registeredClientRepository.save(builder.build());

        ClientRegistrationResponse body = new ClientRegistrationResponse(
            clientId,
            issuedAt.getEpochSecond(),
            redirectUris,
            "none",
            GRANT_TYPES,
            String.join(" ", scopes)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Mirrors {@code AuthController#getClientIp}: never trust {@code X-Forwarded-For} from the
     * client — it is user-controllable and would allow rate-limit bypass by spoofing IPs. Only the
     * TCP-level remote address is used, which is the nginx container's internal IP in production.
     */
    private String getClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * RFC 7591 requires at least one redirect URI for the {@code authorization_code} grant.
     * {@link RegisteredClient.Builder}'s own validation silently accepts an empty/absent set (only
     * per-element checks run, and an empty collection short-circuits before the loop), so emptiness
     * must be rejected here. Each URI must be absolute, fragment-free (mirroring the framework's own
     * {@code RegisteredClient.Builder} redirect-URI rule), and pass the RFC 8252 scheme allowlist
     * ({@link #isAllowedScheme}).
     */
    private List<String> validateRedirectUris(List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new IllegalArgumentException("redirect_uris must contain at least one URI");
        }
        for (String uri : redirectUris) {
            URI parsed = parseAbsoluteFragmentFreeUri(uri);
            if (parsed == null) {
                throw new IllegalArgumentException("Malformed redirect_uris entry: " + uri);
            }
            if (!isAllowedScheme(parsed)) {
                throw new IllegalArgumentException(
                    "redirect_uris entry must be https:// or a loopback http://127.0.0.1 / "
                        + "http://localhost URI: " + uri);
            }
        }
        return redirectUris;
    }

    private URI parseAbsoluteFragmentFreeUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            URI parsed = new URI(uri);
            return (parsed.isAbsolute() && parsed.getFragment() == null) ? parsed : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * RFC 8252 §8.3/§8.4 (OAuth 2.0 for Native Apps): a public client's redirect URI must be either
     * an HTTPS URL, or a loopback-interface HTTP URL ({@code 127.0.0.1} / {@code localhost}) used
     * for apps that run a local redirect listener. Any other scheme — including arbitrary custom
     * schemes and plain {@code http://} to a non-loopback host — is rejected; DCR clients never get
     * the {@code picsou://callback} custom-scheme carve-out reserved for the first-party iOS client.
     */
    private boolean isAllowedScheme(URI uri) {
        String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return true;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            String host = uri.getHost();
            return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        }
        return false;
    }

    /**
     * Reject an oversized {@code client_name}, {@code redirect_uris}, or {@code scope} with a clean
     * 400 before it ever reaches {@link RegisteredClientRepository#save}, rather than letting
     * {@code JdbcRegisteredClientRepository} attempt an INSERT that Postgres rejects with a
     * {@code varchar(n)} overflow (a 500, and a wasted round trip). {@code redirect_uris} and
     * {@code scopes} are stored as a single comma-joined column each (see
     * {@link #DELIMITED_COLUMN_MAX_LENGTH}), so the joined length — not any individual entry's — is
     * what must fit.
     */
    private void validateFieldLengths(String clientName, List<String> redirectUris, Set<String> scopes) {
        if (clientName.length() > CLIENT_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                "client_name exceeds the maximum length of " + CLIENT_NAME_MAX_LENGTH);
        }
        if (String.join(",", redirectUris).length() > DELIMITED_COLUMN_MAX_LENGTH) {
            throw new IllegalArgumentException(
                "redirect_uris exceeds the maximum combined length of " + DELIMITED_COLUMN_MAX_LENGTH);
        }
        if (String.join(",", scopes).length() > DELIMITED_COLUMN_MAX_LENGTH) {
            throw new IllegalArgumentException(
                "scope exceeds the maximum combined length of " + DELIMITED_COLUMN_MAX_LENGTH);
        }
    }

    /**
     * No {@code scope} in the request → default to every read-only scope in {@link Scopes#ALL}
     * (the {@code *:read} / {@code *-read} entries only — deliberately excludes the two
     * {@code oauth2:*} meta-scopes, which are about the session itself, not app data). A requested
     * scope outside {@link Scopes#ALL} is rejected rather than silently dropped, matching
     * {@code AccessKeyService#validateScopes}'s convention for the same allowlist.
     */
    private Set<String> resolveScopes(String requestedScope) {
        if (requestedScope == null || requestedScope.isBlank()) {
            return Scopes.ALL.stream()
                .filter(s -> s.endsWith(":read") || s.endsWith("-read"))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        }
        Set<String> requested = new LinkedHashSet<>(List.of(requestedScope.trim().split("\\s+")));
        for (String scope : requested) {
            if (!Scopes.ALL.contains(scope)) {
                throw new IllegalArgumentException("Unknown scope: " + scope);
            }
        }
        return requested;
    }
}
