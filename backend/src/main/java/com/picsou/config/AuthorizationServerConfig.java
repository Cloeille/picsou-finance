package com.picsou.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadata;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * OAuth2 Authorization Server for the native iOS app (Authorization Code + PKCE).
 *
 * <p>This is a second, higher-priority {@link SecurityFilterChain} scoped to the authorization
 * server endpoints ({@code /oauth2/**}); the existing stateless API chain in
 * {@link SecurityConfig} is unchanged (now {@code @Order(2)}). The web cookie flow keeps working
 * exactly as before.
 *
 * <p>Two deliberate design choices tie this into the existing stack rather than bolting on a
 * parallel identity system:
 * <ul>
 *   <li><b>Same HS256 secret.</b> Tokens are signed with a symmetric {@link OctetSequenceKey}
 *       derived from {@code JWT_SECRET}, and {@link #jwtTokenCustomizer()} reproduces the exact
 *       claims the existing {@link JwtTokenAuthenticator} expects ({@code type=access}, {@code uid},
 *       {@code tv}, {@code sub}, {@code role}). The resource-server validation logic is unchanged.</li>
 *   <li><b>Reuse the web login.</b> {@link CookieBridgeAuthenticationFilter} authenticates the
 *       authorize request from the existing {@code access_token} cookie; when it is absent the
 *       {@link #spaLoginRedirectEntryPoint()} bounces the in-app browser to the SPA login
 *       ({@code /login?redirect=…}), which runs the untouched password + TOTP + Remember-Me flow.</li>
 * </ul>
 *
 * @see <a href="file:../../../../../../docs/decisions/2026-07-03-oauth2-authorization-server-for-native-app.md">ADR</a>
 */
@Configuration
public class AuthorizationServerConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationServerConfig.class);

    /**
     * {@link ClientSettings} custom-setting key marking a registered client as a remote-MCP
     * consumer (e.g. a claude.ai connector registered via Dynamic Client Registration). Read by
     * {@link #jwtTokenCustomizer()} to pick the MCP claim shape instead of the iOS one; set at
     * DCR time by the client-registration endpoint (not part of this file).
     */
    public static final String MCP_CLIENT_SETTING = "settings.client.picsou-mcp";

    /** The {@code aud} claim stamped on every MCP access token; also the RFC 9728 {@code resource}. */
    public static final String MCP_AUDIENCE = "picsou-mcp";

    /**
     * Claim carrying the {@code oauth2_authorization} row id on a native-app access token. The
     * resource server checks the row still exists, so revoking the app from Settings › Sessions
     * locks the device out immediately instead of after the access-token TTL.
     */
    public static final String AUTHORIZATION_ID_CLAIM = "aid";

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(
        HttpSecurity http,
        JwtTokenAuthenticator jwtTokenAuthenticator,
        RegisteredClientRepository registeredClientRepository,
        OAuth2AuthorizationService authorizationService,
        NativeAppRefreshTokens nativeAppRefreshTokens
    ) throws Exception {

        OAuth2AuthorizationServerConfigurer authorizationServer =
            OAuth2AuthorizationServerConfigurer.authorizationServer();

        http
            .securityMatcher(authorizationServer.getEndpointsMatcher())
            .with(authorizationServer, configurer -> configurer
                .authorizationServerMetadataEndpoint(metadata -> metadata
                    .authorizationServerMetadataCustomizer(this::advertiseRegistrationEndpoint))
                // Task 10: send the browser to the SPA's consent screen instead of Spring AS's
                // built-in generated page. MUST be "/consent" (not under /oauth2, /api, /mcp,
                // /actuator) — nginx routes those prefixes to the backend, so only a path outside
                // all of them falls through to the SPA's index.html (client-side routing). Spring
                // AS appends "?scope=<space-delimited>&client_id=&state=" itself
                // (OAuth2AuthorizationEndpointFilter#sendAuthorizationConsent); the SPA calls
                // OAuthConsentController with those same params to resolve what to render.
                .authorizationEndpoint(authorizationEndpoint -> authorizationEndpoint
                    .consentPage("/consent"))
                // Public (secret-less) clients can now be issued a refresh token (see
                // tokenGenerator() below) but Spring AS's built-in client-authentication converters
                // still can't redeem one: PublicClientAuthenticationConverter only activates for a
                // PKCE-shaped request (matchesPkceTokenRequest — grant_type=authorization_code with
                // a code_verifier), which a refresh_token grant never has, so a NONE-method client
                // falls through to anonymous and gets bounced to /login. Confirmed by decompiling
                // spring-security-oauth2-authorization-server 1.4.5. These two prepend onto the
                // framework's default converter/provider list (OAuth2ClientAuthenticationConfigurer
                // .init() does authenticationProviders.addAll(0, custom) — custom entries are tried
                // FIRST), and both return null/defer for anything that isn't a refresh_token grant
                // for a NONE-method client, so the authorization_code+PKCE path is untouched.
                .clientAuthentication(clientAuthentication -> clientAuthentication
                    .authenticationConverter(new PublicClientRefreshTokenAuthenticationConverter())
                    .authenticationProvider(new PublicClientRefreshTokenAuthenticationProvider(registeredClientRepository)))
                // Index 0 explicitly: the reuse check must see a rotated-away token before the
                // framework's refresh provider rejects it as merely unknown.
                .tokenEndpoint(tokenEndpoint -> tokenEndpoint
                    .authenticationProviders(providers -> providers.add(0,
                        new RefreshTokenReuseDetector(authorizationService, nativeAppRefreshTokens)))))
            .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
            // The token endpoint is called by the native app with PKCE (no browser session);
            // the authorize endpoint is a GET. CSRF protection is not applicable to this chain.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            // Populate the SecurityContext from the existing access_token cookie so the authorize
            // endpoint knows who is authorizing. MUST run BEFORE OAuth2AuthorizationEndpointFilter:
            // that filter reads the current principal to decide login-vs-consent, and on a stateless
            // request (no session carrying a SecurityContext — the SPA login is cookie/JWT-based, not
            // session-based) the cookie is the ONLY source of the principal. Anchored just after
            // SecurityContextHolderFilter (which loads any session context first) so the bridge runs
            // ahead of every AS endpoint filter. The previous UsernamePasswordAuthenticationFilter
            // anchor was NOT in this chain, so Spring placed the bridge AFTER the authorize filter —
            // the request then fell through authenticated-but-unhandled to a 404 (see
            // Oauth2ConsentHandshakeIntegrationTest).
            .addFilterAfter(new CookieBridgeAuthenticationFilter(jwtTokenAuthenticator),
                SecurityContextHolderFilter.class)
            .exceptionHandling(ex -> ex.authenticationEntryPoint(spaLoginRedirectEntryPoint()));

        return http.build();
    }

    /**
     * Task 9: advertise {@code registration_endpoint} on the RFC 8414 authorization-server metadata
     * document ({@code /.well-known/oauth-authorization-server}) so a standards-compliant remote-MCP
     * client discovers the custom DCR endpoint ({@link DynamicClientRegistrationController}) without
     * being told about it out-of-band — Spring AS 1.4.5 has no built-in (non-OIDC) client-registration
     * endpoint, so nothing advertises this automatically; we add exactly the one claim.
     *
     * <p>{@code code_challenge_methods_supported} already contains {@code S256} unconditionally —
     * that is {@code OAuth2AuthorizationServerMetadataEndpointFilter}'s own hard-coded default,
     * applied to the builder before this customizer runs (verified in
     * {@code AuthorizationServerMetadataTest} rather than re-set here).
     *
     * <p>The issuer is read from {@link AuthorizationServerContextHolder}, not built from a
     * bean-level base URL: by the time this customizer runs, the framework has already resolved the
     * issuer relative to the current request (honouring {@code X-Forwarded-*} — see
     * {@code forward-headers-strategy: framework}) and populated the holder with it, exactly as it
     * does for every other endpoint URL on this document ({@code token_endpoint},
     * {@code authorization_endpoint}, …).
     */
    private void advertiseRegistrationEndpoint(OAuth2AuthorizationServerMetadata.Builder builder) {
        String issuer = AuthorizationServerContextHolder.getContext().getIssuer();
        builder.clientRegistrationEndpoint(issuer + "/oauth2/register");
    }

    /**
     * JDBC-persistent client registry (V54 migration: {@code oauth2_registered_client}), so both
     * the first-party {@code picsou-ios} client and any client dynamically registered by a
     * remote-MCP consumer (claude.ai) survive redeploys. The {@code picsou-ios} row itself is
     * seeded and kept in sync with the configuration at startup by {@link #seedIosClientRunner},
     * not built here.
     */
    @Bean
    public RegisteredClientRepository registeredClientRepository(JdbcOperations jdbcOperations) {
        return new JdbcRegisteredClientRepository(jdbcOperations);
    }

    /**
     * JDBC-persistent authorization/token store (V54 migration: {@code oauth2_authorization}).
     * The {@code text}-typed blob columns work with the plain {@code (JdbcOperations,
     * RegisteredClientRepository)} constructor's default column-type detection — no
     * {@code LobHandler} needed (see the migration's header comment for why).
     */
    @Bean
    public OAuth2AuthorizationService authorizationService(
        JdbcOperations jdbcOperations,
        RegisteredClientRepository registeredClientRepository
    ) {
        JdbcOAuth2AuthorizationService service =
            new JdbcOAuth2AuthorizationService(jdbcOperations, registeredClientRepository);

        // The persisted authorization carries the authenticating principal (an AppUser). Spring
        // Security's hardened SecurityJackson2Modules ObjectMapper rejects any class not on its
        // allow-list, so AppUser needs an explicit mix-in (see AppUserMixin) — otherwise persisting
        // a consent-required authorization (remote-MCP flow) or reading one back (iOS refresh) fails
        // with "not in the allowlist". Apply the same mapper to BOTH the write (parameters) and read
        // (row) mappers so the round trip is symmetric.
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModules(
            SecurityJackson2Modules.getModules(JdbcOAuth2AuthorizationService.class.getClassLoader()));
        // Mirror the mapper JdbcOAuth2AuthorizationService builds by default: the AS module carries the
        // mix-ins for the framework types persisted in oauth2_authorization (OAuth2AuthorizationRequest,
        // token types, …). Building our own mapper means we must register it explicitly — omitting it
        // fails deserialization on OAuth2AuthorizationRequest.
        objectMapper.registerModule(new OAuth2AuthorizationServerJackson2Module());
        objectMapper.addMixIn(AppUser.class, AppUserMixin.class);
        // The principal snapshot and our own claims introduce scalar JDK/enum types the allow-list
        // does not trust by default: Long (AppUser.id/tokenVersion, uid/tv claims) and UserRole
        // (AppUser.role). Register a no-op mix-in to trust them — values written only by this server.
        objectMapper.addMixIn(Long.class, TrustedClassMixin.class);
        objectMapper.addMixIn(UserRole.class, TrustedClassMixin.class);

        JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper rowMapper =
            new JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper(registeredClientRepository);
        rowMapper.setObjectMapper(objectMapper);
        service.setAuthorizationRowMapper(rowMapper);

        JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper parametersMapper =
            new JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper();
        parametersMapper.setObjectMapper(objectMapper);
        service.setAuthorizationParametersMapper(parametersMapper);

        return service;
    }

    /**
     * JDBC-persistent consent store (V54 migration: {@code oauth2_authorization_consent}). Unused
     * by {@code picsou-ios} today ({@code requireAuthorizationConsent(false)}), but required so a
     * future consenting client (e.g. the remote-MCP client) has somewhere to persist grants.
     */
    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(
        JdbcOperations jdbcOperations,
        RegisteredClientRepository registeredClientRepository
    ) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcOperations, registeredClientRepository);
    }

    /**
     * Seeds the single first-party public client on first boot, then on every later boot
     * reconciles the existing row with the configured redirect URI and token lifetimes, so
     * {@code OAUTH_IOS_REDIRECT_URI}, {@code OAUTH_ACCESS_TTL_MINUTES} and
     * {@code OAUTH_REFRESH_TTL_DAYS} take effect on restart. The row keeps its id (authorizations
     * reference it) and is only written when a value differs, so restarts are idempotent.
     * PKCE is mandatory; there is no client secret. Consent is skipped (the app and server are
     * operated by the same person).
     */
    @Bean
    public ApplicationRunner seedIosClientRunner(
        RegisteredClientRepository registeredClientRepository,
        OAuthClientProperties props
    ) {
        return (ApplicationArguments args) -> {
            RegisteredClient existing = registeredClientRepository.findByClientId(props.getClientId());
            if (existing == null) {
                registeredClientRepository.save(buildIosClient(props));
                return;
            }
            Duration accessTtl = Duration.ofMinutes(props.getAccessTokenTtlMinutes());
            Duration refreshTtl = Duration.ofDays(props.getRefreshTokenTtlDays());
            TokenSettings tokens = existing.getTokenSettings();
            if (existing.getRedirectUris().equals(Set.of(props.getRedirectUri()))
                && tokens.getAccessTokenTimeToLive().equals(accessTtl)
                && tokens.getRefreshTokenTimeToLive().equals(refreshTtl)) {
                return;
            }
            registeredClientRepository.save(RegisteredClient.from(existing)
                .redirectUris(uris -> {
                    uris.clear();
                    uris.add(props.getRedirectUri());
                })
                .tokenSettings(TokenSettings.withSettings(tokens.getSettings())
                    .accessTokenTimeToLive(accessTtl)
                    .refreshTokenTimeToLive(refreshTtl)
                    .build())
                .build());
            log.info("OAuth client '{}' updated from configuration: redirectUri={}, accessTtl={}, refreshTtl={}",
                props.getClientId(), props.getRedirectUri(), accessTtl, refreshTtl);
        };
    }

    private RegisteredClient buildIosClient(OAuthClientProperties props) {
        return RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(props.getClientId())
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .redirectUri(props.getRedirectUri())
            .scope("read")
            .scope("write")
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)               // PKCE (S256) required
                .requireAuthorizationConsent(false)  // first-party → no consent screen
                .build())
            .tokenSettings(TokenSettings.builder()
                .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)   // JWT
                .accessTokenTimeToLive(Duration.ofMinutes(props.getAccessTokenTtlMinutes()))
                .refreshTokenTimeToLive(Duration.ofDays(props.getRefreshTokenTtlDays()))
                .reuseRefreshTokens(false)            // rotate refresh tokens on each use
                .build())
            .build();
    }

    /**
     * Symmetric HS256 signing key built from the same {@code JWT_SECRET} used by {@link JwtUtil},
     * so tokens minted here validate through the existing resource-server path. The key has no
     * public half, so the {@code /oauth2/jwks} endpoint exposes nothing.
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource(@Value("${app.jwt.secret}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        OctetSequenceKey key = new OctetSequenceKey.Builder(keyBytes)
            .keyID("picsou-hs256")
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(com.nimbusds.jose.JWSAlgorithm.HS256)
            .build();
        return new ImmutableJWKSet<>(new JWKSet(key));
    }

    /**
     * Force HMAC signing and stamp the claims the existing filter requires. The principal is the
     * {@link AppUser} set by the cookie bridge (or, on refresh, the snapshot captured at
     * authorization time). Reading {@code tv} from that snapshot — not from a fresh DB load — is
     * intentional: a later password change bumps {@code AppUser.tokenVersion}, so refreshed tokens
     * still carry the old {@code tv} and are rejected by the API, logging the device out.
     *
     * <p>Two claim shapes branch on the {@link #MCP_CLIENT_SETTING} flag on the registered client:
     * <ul>
     *   <li><b>iOS / first-party</b> (flag absent) — {@code type=access}, {@code uid}, {@code tv},
     *       {@code role}, default audience, plus {@link #AUTHORIZATION_ID_CLAIM} naming the
     *       authorization the token belongs to (see {@code NativeAppSessionService}).</li>
     *   <li><b>Remote-MCP client</b> (flag {@code true}) — {@code type=mcp}, {@code aud=picsou-mcp},
     *       {@code uid}, {@code tv}, {@code scope} (space-delimited granted scopes), and
     *       deliberately <em>no</em> {@code role} — an MCP token authorizes {@code /mcp} purely by
     *       scope (Property C), never by role.</li>
     * </ul>
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getJwsHeader().algorithm(MacAlgorithm.HS256);
                if (context.getPrincipal().getPrincipal() instanceof AppUser user) {
                    boolean isMcpClient = Boolean.TRUE.equals(
                        context.getRegisteredClient().getClientSettings().getSetting(MCP_CLIENT_SETTING));
                    if (isMcpClient) {
                        context.getClaims()
                            .subject(user.getUsername())
                            .claim("type", "mcp")
                            // Mutable ArrayList, not List.of(...): the token's claims are persisted in
                            // oauth2_authorization and read back by SecurityJackson2Modules, whose
                            // allow-list rejects java.util.ImmutableCollections (List.of) but accepts
                            // ArrayList — a List.of aud breaks revoke/refresh deserialization.
                            .audience(new ArrayList<>(List.of(MCP_AUDIENCE)))
                            .claim("uid", user.getId())
                            .claim("tv", user.getTokenVersion())
                            .claim("scope", String.join(" ", context.getAuthorizedScopes()));
                    } else {
                        context.getClaims()
                            .subject(user.getUsername())
                            .claim("type", "access")
                            .claim("uid", user.getId())
                            .claim("tv", user.getTokenVersion())
                            .claim("role", user.getRole().name());
                        if (context.getAuthorization() != null) {
                            context.getClaims().claim(AUTHORIZATION_ID_CLAIM, context.getAuthorization().getId());
                        }
                    }
                }
            }
        };
    }

    /**
     * Overrides Spring Authorization Server's default token-generation chain to actually issue a
     * refresh token to {@code picsou-ios}.
     *
     * <p><b>Root cause (confirmed by decompiling {@code spring-security-oauth2-authorization-server}
     * 1.4.5):</b> the framework's built-in {@code OAuth2RefreshTokenGenerator} unconditionally
     * returns {@code null} — silently, no log, no error — whenever
     * {@code isPublicClientForAuthorizationCodeGrant(context)} is true, i.e. whenever the requesting
     * client's {@link ClientAuthenticationMethod} is {@link ClientAuthenticationMethod#NONE}. That is
     * exactly {@code picsou-ios} ({@link #buildIosClient}): a public, secret-less PKCE client by
     * design (RFC 8252 §8.4 — a native app cannot hold a confidential secret). This held true
     * regardless of the client's own {@code authorization_grant_types} row (which correctly listed
     * {@code refresh_token}) and regardless of {@link TokenSettings#getRefreshTokenTimeToLive()} —
     * the JWT access token (15 min TTL) was the only token ever returned, so every device was force
     * logged out roughly every 15 minutes with the app's own {@code TokenRefresher} never getting a
     * refresh token to use. Verified end-to-end against a live instance (login → authorize → token
     * exchange) before this fix: the token response had no {@code refresh_token} field.
     *
     * <p>Spring's default is a defensible security posture for browser-based public clients (a
     * refresh token sitting in JS-accessible storage is a bigger prize than a short-lived access
     * token), but it is overly broad for a native app storing the token in the Keychain, and this
     * client carries the mitigations OAuth 2.1 asks for on public-client refresh tokens: rotation on
     * every use ({@code reuseRefreshTokens(false)}) with reuse detection
     * ({@link RefreshTokenReuseDetector}), and a sign-in lifetime that rotation cannot extend (the
     * refresh TTL counts from the authorization code, not from each rotation). The JDBC authorization store and
     * its Jackson mix-ins were already built anticipating a working refresh flow (see the comments
     * on {@link #authorizationService} and {@link #jwtTokenCustomizer()}) — this bean is what makes
     * that flow actually run.
     *
     * <p>Reuses the framework's own {@link JwtGenerator} (with {@link #jwtTokenCustomizer()}) and
     * {@link OAuth2AccessTokenGenerator} for the other two token types; only the refresh-token leg is
     * replaced, with {@link NativeAppRefreshTokenGenerator} below — a copy of
     * {@code OAuth2RefreshTokenGenerator}'s real generation logic (96-byte URL-safe base64 key, TTL
     * from the registered client) minus the public-client bypass. {@code picsou-ios} tokens differ
     * in two ways: their value comes from {@link NativeAppRefreshTokens} so a reuse can be traced to
     * its authorization, and they expire one refresh TTL after the sign-in, however often rotated.
     */
    @Bean
    public OAuth2TokenGenerator<OAuth2Token> tokenGenerator(
        JWKSource<SecurityContext> jwkSource,
        OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer,
        NativeAppRefreshTokens nativeAppRefreshTokens,
        OAuthClientProperties props
    ) {
        JwtEncoder jwtEncoder = new NimbusJwtEncoder(jwkSource);
        JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
        jwtGenerator.setJwtCustomizer(jwtTokenCustomizer);
        return new DelegatingOAuth2TokenGenerator(jwtGenerator, new OAuth2AccessTokenGenerator(),
            new NativeAppRefreshTokenGenerator(nativeAppRefreshTokens, props.getClientId()));
    }

    @Bean
    NativeAppRefreshTokens nativeAppRefreshTokens(@Value("${app.jwt.secret}") String secret) {
        return new NativeAppRefreshTokens(secret);
    }

    /** See {@link #tokenGenerator} for why this exists instead of the framework default. */
    private static final class NativeAppRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {
        private final StringKeyGenerator refreshTokenGenerator =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);
        private final NativeAppRefreshTokens nativeAppRefreshTokens;
        private final String nativeAppClientId;

        NativeAppRefreshTokenGenerator(NativeAppRefreshTokens nativeAppRefreshTokens, String nativeAppClientId) {
            this.nativeAppRefreshTokens = nativeAppRefreshTokens;
            this.nativeAppClientId = nativeAppClientId;
        }

        @Override
        public OAuth2RefreshToken generate(OAuth2TokenContext context) {
            if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
                return null;
            }
            Instant issuedAt = Instant.now();
            Duration ttl = context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive();
            OAuth2Authorization authorization = context.getAuthorization();
            if (authorization == null || !nativeAppClientId.equals(context.getRegisteredClient().getClientId())) {
                return new OAuth2RefreshToken(refreshTokenGenerator.generateKey(), issuedAt, issuedAt.plus(ttl));
            }
            OAuth2Authorization.Token<OAuth2AuthorizationCode> code = authorization.getToken(OAuth2AuthorizationCode.class);
            Instant signedInAt = code != null ? code.getToken().getIssuedAt() : issuedAt;
            Instant expiresAt = signedInAt.plus(ttl);
            if (!expiresAt.isAfter(issuedAt)) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
            }
            return new OAuth2RefreshToken(nativeAppRefreshTokens.issue(authorization.getId()), issuedAt, expiresAt);
        }
    }

    /**
     * Recognizes a {@code grant_type=refresh_token} request carrying a bare {@code client_id} — the
     * only shape a {@link ClientAuthenticationMethod#NONE} client can ever send, since it has no
     * secret and PKCE's {@code code_verifier} does not apply outside the authorization_code grant.
     * Defers ({@code null}) to the framework's own converters for every other request shape,
     * including a client that supplies an {@code Authorization} header (Basic or otherwise) — this
     * app has no confidential clients today, but that check keeps the converter honest if one is
     * ever added. See {@link #authorizationServerSecurityFilterChain} for how this is wired in and
     * why it's needed.
     */
    private static final class PublicClientRefreshTokenAuthenticationConverter implements AuthenticationConverter {
        @Override
        public Authentication convert(HttpServletRequest request) {
            if (!"POST".equals(request.getMethod())
                || StringUtils.hasText(request.getHeader(HttpHeaders.AUTHORIZATION))) {
                return null;
            }
            String grantType = request.getParameter(OAuth2ParameterNames.GRANT_TYPE);
            if (!AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(grantType)) {
                return null;
            }
            String clientId = request.getParameter(OAuth2ParameterNames.CLIENT_ID);
            if (!StringUtils.hasText(clientId)) {
                return null;
            }
            return new OAuth2ClientAuthenticationToken(clientId, ClientAuthenticationMethod.NONE, null, Map.of());
        }
    }

    /**
     * Authenticates the token from {@link PublicClientRefreshTokenAuthenticationConverter} by
     * {@code client_id} alone — the standard RFC 6749 public-client model, where the refresh token
     * itself (high-entropy, rotated on every use per {@code reuseRefreshTokens(false)} in
     * {@link #buildIosClient}) is the real credential. Returns {@code null} (defer) for any token
     * whose method isn't {@code NONE}, so it never shadows the framework's confidential-client
     * providers. Rejects a client that isn't registered, doesn't allow {@code NONE} auth, or doesn't
     * carry the {@code refresh_token} grant type — the same three checks
     * {@code PublicClientAuthenticationProvider} makes, minus the PKCE verification that only makes
     * sense for an authorization_code exchange.
     */
    private static final class PublicClientRefreshTokenAuthenticationProvider implements AuthenticationProvider {
        private final RegisteredClientRepository registeredClientRepository;

        PublicClientRefreshTokenAuthenticationProvider(RegisteredClientRepository registeredClientRepository) {
            this.registeredClientRepository = registeredClientRepository;
        }

        @Override
        public Authentication authenticate(Authentication authentication) {
            OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;
            if (!ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod())) {
                return null;
            }
            String clientId = String.valueOf(token.getPrincipal());
            RegisteredClient registeredClient = registeredClientRepository.findByClientId(clientId);
            if (registeredClient == null
                || !registeredClient.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
                || !registeredClient.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
            }
            return new OAuth2ClientAuthenticationToken(registeredClient, ClientAuthenticationMethod.NONE, null);
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
        }
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().build();
    }

    /**
     * Redirect unauthenticated authorize requests to the existing SPA login, carrying the original
     * authorize URL in the SPA's established {@code redirect} query param so it can bounce back after
     * login. Relative path → resolves against the user's own instance host (works behind nginx). The
     * SPA only performs a full-page navigation back to a {@code /oauth2/} target (open-redirect guard).
     */
    private AuthenticationEntryPoint spaLoginRedirectEntryPoint() {
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) -> {
            String target = request.getRequestURI();
            if (request.getQueryString() != null) {
                target = target + "?" + request.getQueryString();
            }
            String redirect = URLEncoder.encode(target, StandardCharsets.UTF_8);
            response.sendRedirect("/login?redirect=" + redirect);
        };
    }
}
