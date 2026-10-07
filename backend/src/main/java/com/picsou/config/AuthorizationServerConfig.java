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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadata;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * {@link ClientSettings} custom-setting key marking a registered client as a remote-MCP
     * consumer (e.g. a claude.ai connector registered via Dynamic Client Registration). Read by
     * {@link #jwtTokenCustomizer()} to pick the MCP claim shape instead of the iOS one; set at
     * DCR time by the client-registration endpoint (not part of this file).
     */
    public static final String MCP_CLIENT_SETTING = "settings.client.picsou-mcp";

    /** The {@code aud} claim stamped on every MCP access token; also the RFC 9728 {@code resource}. */
    public static final String MCP_AUDIENCE = "picsou-mcp";

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(
        HttpSecurity http,
        JwtTokenAuthenticator jwtTokenAuthenticator
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
                    .consentPage("/consent")))
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
     * seeded once at startup by {@link #seedIosClientRunner}, not built here.
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
     * Seeds the single first-party public client on first boot only. PKCE is mandatory; there is
     * no client secret. Consent is skipped (the app and server are operated by the same person).
     * Runs after the {@link RegisteredClientRepository} bean exists; idempotent across restarts —
     * {@code findByClientId} returns non-null on every boot after the first, so the row is never
     * re-inserted (and therefore never duplicated or reset).
     */
    @Bean
    public ApplicationRunner seedIosClientRunner(
        RegisteredClientRepository registeredClientRepository,
        OAuthClientProperties props
    ) {
        return (ApplicationArguments args) -> {
            if (registeredClientRepository.findByClientId(props.getClientId()) == null) {
                registeredClientRepository.save(buildIosClient(props));
            }
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
     *       {@code role}, default audience. Unchanged from before this MCP branch existed.</li>
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
                    }
                }
            }
        };
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
