package com.picsou.config;

import com.picsou.model.AppUser;
import com.picsou.repository.AppUserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Single source of truth for turning a raw <em>access</em> JWT into an authenticated
 * {@link Authentication}. Extracted so the three entry points that accept an access token —
 * the {@code access_token} cookie ({@link JwtAuthenticationFilter}), the
 * {@code Authorization: Bearer} header used by the native app ({@link JwtAuthenticationFilter}),
 * and the OAuth2 authorization-server cookie bridge ({@link CookieBridgeAuthenticationFilter}) —
 * share exactly one validation path. Security-sensitive checks (signature, token type,
 * {@code tv} token-version, activation) must never drift between callers.
 */
@Component
public class JwtTokenAuthenticator {

    private final JwtUtil jwtUtil;
    private final AppUserRepository userRepository;

    public JwtTokenAuthenticator(JwtUtil jwtUtil, AppUserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    /**
     * Validate {@code token} as an access JWT and, if it maps to an active user whose
     * {@code tv} claim still matches the persisted token version, return the corresponding
     * authentication carrying a single {@code ROLE_*} authority. Returns empty for any
     * failure (missing/invalid/expired token, wrong token type, revoked version, unknown or
     * deactivated user) — callers simply stay unauthenticated.
     */
    public Optional<Authentication> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = jwtUtil.validateAndParse(token);
            if (!jwtUtil.isAccessToken(claims)) {
                return Optional.empty();
            }
            Long userId = claims.get("uid", Long.class);
            Long tv = jwtUtil.getTokenVersion(claims);
            if (userId == null) {
                return Optional.empty();
            }
            AppUser user = userRepository.findByIdWithMember(userId).orElse(null);
            if (user != null && user.isActivated() && tv != null && tv == user.getTokenVersion()) {
                String role = "ROLE_" + user.getRole().name();
                return Optional.of(new UsernamePasswordAuthenticationToken(
                    user,
                    null,
                    List.of(new SimpleGrantedAuthority(role))
                ));
            }
        } catch (JwtException ex) {
            // Invalid/expired/forged token — treat as unauthenticated.
        }
        return Optional.empty();
    }

    /**
     * Validate {@code token} as an MCP JWT (minted by the OAuth2 authorization server for a
     * remote-MCP client, e.g. claude.ai) and, if it maps to an active user whose {@code tv} claim
     * still matches the persisted token version, return the owning user id and granted scopes.
     * Returns empty for any failure (missing/invalid/expired/forged token, wrong token type,
     * missing {@code picsou-mcp} audience, revoked version, unknown or deactivated user).
     *
     * <p>Callers: {@link AccessKeyAuthFilter}, on the {@code /mcp} surface only — this method
     * itself does not enforce a path, it only enforces the token's own claim shape. A {@code
     * type=access} token (the web/iOS shape validated by {@link #authenticate}) is rejected here
     * just as an MCP token is rejected by {@link #authenticate} — the two token kinds are
     * mutually exclusive by construction (Property A).
     */
    public Optional<McpPrincipal> authenticateMcpToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = jwtUtil.validateAndParse(token);
            if (!isMcpToken(claims)) {
                return Optional.empty();
            }
            Set<String> audience = claims.getAudience();
            if (audience == null || !audience.contains(AuthorizationServerConfig.MCP_AUDIENCE)) {
                return Optional.empty();
            }
            Long userId = claims.get("uid", Long.class);
            Long tv = jwtUtil.getTokenVersion(claims);
            if (userId == null) {
                return Optional.empty();
            }
            AppUser user = userRepository.findByIdWithMember(userId).orElse(null);
            if (user != null && user.isActivated() && tv != null && tv == user.getTokenVersion()) {
                return Optional.of(new McpPrincipal(userId, parseScopes(claims.get("scope", String.class))));
            }
        } catch (JwtException ex) {
            // Invalid/expired/forged token — treat as unauthenticated.
        }
        return Optional.empty();
    }

    /** {@code type=mcp}, distinct from {@code type=access}/{@code refresh}/{@code mfa_challenge}. */
    private boolean isMcpToken(Claims claims) {
        return "mcp".equals(claims.get("type", String.class));
    }

    private Set<String> parseScopes(String scopeClaim) {
        if (scopeClaim == null || scopeClaim.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(List.of(scopeClaim.trim().split("\\s+")));
    }

    /** The resolved identity of an authenticated MCP request: the token owner and its granted scopes. */
    public record McpPrincipal(long uid, Set<String> scopes) {}
}
