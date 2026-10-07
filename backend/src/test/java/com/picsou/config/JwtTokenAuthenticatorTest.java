package com.picsou.config;

import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Exercises the single shared access-token validation path used by the cookie, the Bearer header
 * and the OAuth2 cookie bridge. Uses a real {@link JwtUtil} to mint tokens (so signature and claim
 * shape are genuine) and a mocked repository for the user lookup.
 */
@ExtendWith(MockitoExtension.class)
class JwtTokenAuthenticatorTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-test";

    @Mock AppUserRepository userRepository;

    JwtUtil jwtUtil;
    JwtTokenAuthenticator authenticator;
    AppUser user;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 15, 7, 5);
        authenticator = new JwtTokenAuthenticator(jwtUtil, userRepository);
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
    void validAccessToken_forActiveUserWithMatchingTokenVersion_authenticates() {
        when(userRepository.findByIdWithMember(42L)).thenReturn(Optional.of(user));
        String token = jwtUtil.generateAccessToken(user);

        Optional<Authentication> result = authenticator.authenticate(token);

        assertThat(result).isPresent();
        assertThat(result.get().getPrincipal()).isSameAs(user);
        assertThat(result.get().getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    }

    @Test
    void refreshToken_isRejected() {
        // A refresh token is validly signed but must never authenticate a request.
        lenient().when(userRepository.findByIdWithMember(42L)).thenReturn(Optional.of(user));
        String refresh = jwtUtil.generateRefreshToken(user);

        assertThat(authenticator.authenticate(refresh)).isEmpty();
    }

    @Test
    void tokenVersionMismatch_isRejected() {
        // Token minted at tv=3, then the user's tokenVersion is bumped (e.g. password change).
        String token = jwtUtil.generateAccessToken(user);
        user.setTokenVersion(4L);
        when(userRepository.findByIdWithMember(42L)).thenReturn(Optional.of(user));

        assertThat(authenticator.authenticate(token)).isEmpty();
    }

    @Test
    void deactivatedUser_isRejected() {
        String token = jwtUtil.generateAccessToken(user);
        user.setActivated(false);
        when(userRepository.findByIdWithMember(42L)).thenReturn(Optional.of(user));

        assertThat(authenticator.authenticate(token)).isEmpty();
    }

    @Test
    void unknownUser_isRejected() {
        String token = jwtUtil.generateAccessToken(user);
        when(userRepository.findByIdWithMember(42L)).thenReturn(Optional.empty());

        assertThat(authenticator.authenticate(token)).isEmpty();
    }

    @Test
    void forgedToken_isRejected() {
        // Signed with a different secret → signature verification fails.
        JwtUtil attacker = new JwtUtil("ffffffffffffffffffffffffffffffff-evil", 15, 7, 5);
        String forged = attacker.generateAccessToken(user);

        assertThat(authenticator.authenticate(forged)).isEmpty();
    }

    @Test
    void nullOrBlankToken_isRejected() {
        assertThat(authenticator.authenticate(null)).isEmpty();
        assertThat(authenticator.authenticate("   ")).isEmpty();
    }

    // ─── Task 4: authenticateMcpToken — path-scoped MCP validation ─────────

    @Test
    void validMcpToken_authenticateMcpToken_returnsUidAndScopes() {
        when(userRepository.findByIdWithMember(42L)).thenReturn(Optional.of(user));
        String token = mcpToken(user, "accounts:read goals:read", Instant.now().plusSeconds(900));

        Optional<JwtTokenAuthenticator.McpPrincipal> result = authenticator.authenticateMcpToken(token);

        assertThat(result).isPresent();
        assertThat(result.get().uid()).isEqualTo(42L);
        assertThat(result.get().scopes()).containsExactlyInAnyOrder("accounts:read", "goals:read");
    }

    @Test
    void accessToken_isRejectedByAuthenticateMcpToken() {
        // A regular web/iOS access token (type=access) must never validate as an MCP token.
        String token = jwtUtil.generateAccessToken(user);

        assertThat(authenticator.authenticateMcpToken(token)).isEmpty();
    }

    @Test
    void mcpToken_isRejectedByTheExistingApiWebAuthenticate() {
        // The existing /api Bearer/cookie path must reject type=mcp (only type=access authenticates there).
        String token = mcpToken(user, "accounts:read", Instant.now().plusSeconds(900));

        assertThat(authenticator.authenticate(token)).isEmpty();
    }

    @Test
    void expiredMcpToken_isRejected() {
        String token = mcpToken(user, "accounts:read", Instant.now().minusSeconds(5));

        assertThat(authenticator.authenticateMcpToken(token)).isEmpty();
    }

    @Test
    void forgedMcpToken_isRejected() {
        // Signed with a different secret — signature verification must fail.
        SecretKey attackerKey = Keys.hmacShaKeyFor("ffffffffffffffffffffffffffffffff-evil".getBytes(StandardCharsets.UTF_8));
        String forged = mcpToken(attackerKey, user, "accounts:read", Instant.now().plusSeconds(900));

        assertThat(authenticator.authenticateMcpToken(forged)).isEmpty();
    }

    @Test
    void mcpToken_wrongAudience_isRejected() {
        String token = Jwts.builder()
            .subject(user.getUsername())
            .claim("uid", user.getId())
            .claim("type", "mcp")
            .claim("tv", user.getTokenVersion())
            .claim("scope", "accounts:read")
            .claim("aud", List.of("some-other-audience"))
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plusSeconds(900)))
            .signWith(SIGNING_KEY)
            .compact();

        assertThat(authenticator.authenticateMcpToken(token)).isEmpty();
    }

    @Test
    void mcpToken_tokenVersionMismatch_isRejected() {
        String token = mcpToken(user, "accounts:read", Instant.now().plusSeconds(900));
        user.setTokenVersion(4L);
        when(userRepository.findByIdWithMember(42L)).thenReturn(Optional.of(user));

        assertThat(authenticator.authenticateMcpToken(token)).isEmpty();
    }

    @Test
    void mcpToken_deactivatedOwner_isRejected() {
        String token = mcpToken(user, "accounts:read", Instant.now().plusSeconds(900));
        user.setActivated(false);
        when(userRepository.findByIdWithMember(42L)).thenReturn(Optional.of(user));

        assertThat(authenticator.authenticateMcpToken(token)).isEmpty();
    }

    @Test
    void mcpToken_unknownOwner_isRejected() {
        String token = mcpToken(user, "accounts:read", Instant.now().plusSeconds(900));
        when(userRepository.findByIdWithMember(42L)).thenReturn(Optional.empty());

        assertThat(authenticator.authenticateMcpToken(token)).isEmpty();
    }

    @Test
    void mcpToken_blankScope_yieldsEmptyScopeSet() {
        when(userRepository.findByIdWithMember(42L)).thenReturn(Optional.of(user));
        String token = mcpToken(user, "", Instant.now().plusSeconds(900));

        Optional<JwtTokenAuthenticator.McpPrincipal> result = authenticator.authenticateMcpToken(token);

        assertThat(result).isPresent();
        assertThat(result.get().scopes()).isEmpty();
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static final SecretKey SIGNING_KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    /** Mints an MCP-shaped JWT ({@code type=mcp}, {@code aud=picsou-mcp}) the way the OAuth2
     * authorization server's {@code jwtTokenCustomizer} would, signed with the shared test secret. */
    private String mcpToken(AppUser u, String scope, Instant expiry) {
        return mcpToken(SIGNING_KEY, u, scope, expiry);
    }

    private String mcpToken(SecretKey key, AppUser u, String scope, Instant expiry) {
        return Jwts.builder()
            .subject(u.getUsername())
            .claim("uid", u.getId())
            .claim("type", "mcp")
            .claim("tv", u.getTokenVersion())
            .claim("scope", scope)
            .claim("aud", List.of("picsou-mcp"))
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(expiry))
            .signWith(key)
            .compact();
    }
}
