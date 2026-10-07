package com.picsou.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The crux of the design: a token minted by the OAuth2 authorization server (HS256 + custom claims)
 * must validate through the <em>existing, unchanged</em> resource-server path ({@link JwtUtil} /
 * {@link JwtTokenAuthenticator}). This test runs the real {@link AuthorizationServerConfig#jwkSource}
 * and {@link AuthorizationServerConfig#jwtTokenCustomizer()} against a {@link JwtEncodingContext},
 * signs the JWT with the same {@link NimbusJwtEncoder} the server uses at runtime, and feeds the
 * result to the resource server.
 *
 * <p>Also boots the full application context against a real Postgres 16 via Testcontainers
 * (mirroring {@code OAuth2SchemaMigrationTest} / {@code BudgetSeedWriteOnReadPostgresTest}) to
 * cover the JDBC-backed {@code RegisteredClientRepository} wiring and the {@code picsou-ios}
 * seeding-on-startup behaviour, since {@code JdbcRegisteredClientRepository} needs the real V54
 * schema (the {@code text}-typed columns) rather than an in-memory stand-in.
 * {@code disabledWithoutDocker = true} self-skips on machines/CI without a Docker daemon.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AuthorizationServerConfigTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-test";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "test-jwt-secret-test-jwt-secret-0123456789");
        registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired RegisteredClientRepository registeredClientRepository;
    @Autowired OAuthClientProperties oAuthClientProperties;

    AuthorizationServerConfig config;
    AppUser user;

    @BeforeEach
    void setUp() {
        config = new AuthorizationServerConfig();
        user = AppUser.builder()
            .id(42L)
            .username("alice")
            .passwordHash("h")
            .role(UserRole.ADMIN)
            .activated(true)
            .tokenVersion(3L)
            .build();
    }

    @Test
    void registeredClientRepositoryBeanIsJdbcBacked() {
        assertThat(registeredClientRepository).isInstanceOf(JdbcRegisteredClientRepository.class);
    }

    @Test
    void iosClientIsSeededOnStartupWithPreservedSettings() {
        RegisteredClient client = registeredClientRepository.findByClientId(oAuthClientProperties.getClientId());

        assertThat(client).isNotNull();
        assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(client.getAuthorizationGrantTypes()).containsExactlyInAnyOrder(
            AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(client.getRedirectUris()).containsExactly("picsou://callback");
        assertThat(client.getScopes()).containsExactlyInAnyOrder("read", "write");
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isFalse();
        assertThat(client.getTokenSettings().isReuseRefreshTokens()).isFalse();
        assertThat(client.getTokenSettings().getAccessTokenTimeToLive())
            .isEqualTo(java.time.Duration.ofMinutes(oAuthClientProperties.getAccessTokenTtlMinutes()));
        assertThat(client.getTokenSettings().getRefreshTokenTimeToLive())
            .isEqualTo(java.time.Duration.ofDays(oAuthClientProperties.getRefreshTokenTtlDays()));
    }

    @Test
    void customizerStampsResourceServerClaimsAndForcesHs256() {
        JwtEncodingContext context = accessTokenContext(user);

        config.jwtTokenCustomizer().customize(context);

        JwsHeader header = context.getJwsHeader().build();
        assertThat(header.getAlgorithm()).isEqualTo(MacAlgorithm.HS256);

        JwtClaimsSet claims = context.getClaims().build();
        String type = claims.getClaim("type");
        Long uid = claims.getClaim("uid");
        Long tv = claims.getClaim("tv");
        String role = claims.getClaim("role");
        assertThat(type).isEqualTo("access");
        assertThat(uid).isEqualTo(42L);
        assertThat(tv).isEqualTo(3L);
        assertThat(role).isEqualTo("ADMIN");
        assertThat(claims.getSubject()).isEqualTo("alice");
    }

    @Test
    void mintedToken_isAcceptedByTheExistingResourceServer() {
        JwtEncodingContext context = accessTokenContext(user);
        config.jwtTokenCustomizer().customize(context);

        String tokenValue = sign(context);

        // 1) The raw jjwt validation the API filter uses.
        JwtUtil jwtUtil = new JwtUtil(SECRET, 15, 7, 5);
        Claims parsed = jwtUtil.validateAndParse(tokenValue);
        assertThat(jwtUtil.isAccessToken(parsed)).isTrue();
        assertThat(parsed.get("uid", Long.class)).isEqualTo(42L);
        assertThat(jwtUtil.getTokenVersion(parsed)).isEqualTo(3L);
        assertThat(parsed.get("role", String.class)).isEqualTo("ADMIN");
        assertThat(parsed.getSubject()).isEqualTo("alice");

        // 2) End-to-end through the shared authenticator (with the user present + tv matching).
        AppUserRepository repo = mock(AppUserRepository.class);
        when(repo.findByIdWithMember(42L)).thenReturn(java.util.Optional.of(user));
        JwtTokenAuthenticator authenticator = new JwtTokenAuthenticator(jwtUtil, repo);

        assertThat(authenticator.authenticate(tokenValue)).isPresent();
    }

    // ─── Task 3: MCP token claim shape ─────────────────────────────────────

    @Test
    void customizerStampsMcpClaimShape_forMcpFlaggedClient() {
        Set<String> scopes = new LinkedHashSet<>(List.of("accounts:read", "goals:read"));
        JwtEncodingContext context = mcpClientAccessTokenContext(user, scopes);

        config.jwtTokenCustomizer().customize(context);

        JwsHeader header = context.getJwsHeader().build();
        assertThat(header.getAlgorithm()).isEqualTo(MacAlgorithm.HS256);

        JwtClaimsSet claims = context.getClaims().build();
        String type = claims.getClaim("type");
        Long uid = claims.getClaim("uid");
        Long tv = claims.getClaim("tv");
        String scope = claims.getClaim("scope");
        assertThat(type).isEqualTo("mcp");
        assertThat(uid).isEqualTo(42L);
        assertThat(tv).isEqualTo(3L);
        assertThat(claims.getAudience()).containsExactly("picsou-mcp");
        assertThat(scope).isEqualTo("accounts:read goals:read");
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat((Object) claims.getClaim("role")).isNull();
    }

    @Test
    void mintedMcpToken_isAcceptedOnlyByMcpValidation_notTheAccessPath() {
        Set<String> scopes = Set.of("accounts:read");
        JwtEncodingContext context = mcpClientAccessTokenContext(user, scopes);
        config.jwtTokenCustomizer().customize(context);

        String tokenValue = sign(context);

        JwtUtil jwtUtil = new JwtUtil(SECRET, 15, 7, 5);
        Claims parsed = jwtUtil.validateAndParse(tokenValue);
        assertThat(parsed.get("type", String.class)).isEqualTo("mcp");
        assertThat(parsed.getAudience()).containsExactly("picsou-mcp");
        assertThat(parsed.get("scope", String.class)).isEqualTo("accounts:read");

        // The existing web/API access-token path must reject it (only type=access authenticates there).
        AppUserRepository repo = mock(AppUserRepository.class);
        JwtTokenAuthenticator authenticator = new JwtTokenAuthenticator(jwtUtil, repo);
        assertThat(authenticator.authenticate(tokenValue)).isEmpty();
    }

    @Test
    void picsouIosClient_isUnaffectedByTheMcpBranch() {
        // Regression guard: a client without the MCP setting keeps the exact pre-existing shape.
        JwtEncodingContext context = accessTokenContext(user);

        config.jwtTokenCustomizer().customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat((String) claims.getClaim("type")).isEqualTo("access");
        assertThat((String) claims.getClaim("role")).isEqualTo("ADMIN");
        assertThat(claims.getAudience()).isNullOrEmpty();
        assertThat((Object) claims.getClaim("scope")).isNull();
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private JwtEncodingContext mcpClientAccessTokenContext(AppUser principalUser, Set<String> authorizedScopes) {
        RegisteredClient client = RegisteredClient.withId("test-mcp")
            .clientId("mcp-claude-1")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://claude.ai/api/mcp/auth_callback")
            .scopes(s -> s.addAll(authorizedScopes))
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(true)
                .setting(AuthorizationServerConfig.MCP_CLIENT_SETTING, true)
                .build())
            .build();

        Authentication principal = new UsernamePasswordAuthenticationToken(
            principalUser, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        JwsHeader.Builder headers = JwsHeader.with(SignatureAlgorithm.RS256); // default; customizer overrides
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuer("https://picsou.local")
            .subject("placeholder")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(900));

        return JwtEncodingContext.with(headers, claims)
            .registeredClient(client)
            .principal(principal)
            .tokenType(OAuth2TokenType.ACCESS_TOKEN)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizedScopes(authorizedScopes)
            .build();
    }

    private JwtEncodingContext accessTokenContext(AppUser principalUser) {
        RegisteredClient client = RegisteredClient.withId("test")
            .clientId("picsou-ios")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("picsou://callback")
            .scope("read")
            .build();

        Authentication principal = new UsernamePasswordAuthenticationToken(
            principalUser, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        JwsHeader.Builder headers = JwsHeader.with(SignatureAlgorithm.RS256); // default; customizer overrides
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuer("https://picsou.local")
            .subject("placeholder")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(900));

        return JwtEncodingContext.with(headers, claims)
            .registeredClient(client)
            .principal(principal)
            .tokenType(OAuth2TokenType.ACCESS_TOKEN)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .build();
    }

    private String sign(JwtEncodingContext context) {
        JWKSource<SecurityContext> jwkSource = config.jwkSource(SECRET);
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(jwkSource);
        Jwt jwt = encoder.encode(JwtEncoderParameters.from(context.getJwsHeader().build(), context.getClaims().build()));
        return jwt.getTokenValue();
    }
}
