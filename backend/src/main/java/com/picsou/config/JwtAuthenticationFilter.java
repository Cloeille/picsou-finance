package com.picsou.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates {@code /api/**} requests from an <em>access</em> JWT. Two transports are
 * accepted, in this order:
 * <ol>
 *   <li>the {@code access_token} HttpOnly cookie — the web client;</li>
 *   <li>the {@code Authorization: Bearer <jwt>} header — the native iOS app, whose tokens are
 *       minted by the OAuth2 authorization server but HS256-signed with the same secret and
 *       carry the same claims, so they validate through the identical path.</li>
 * </ol>
 * A {@code psk_}-prefixed bearer (MCP access key) is ignored here and left to
 * {@link AccessKeyAuthFilter} on the {@code /mcp} surface. All validation is delegated to
 * {@link JwtTokenAuthenticator} so the cookie and bearer paths cannot diverge.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String MCP_KEY_PREFIX = "psk_";

    private final JwtTokenAuthenticator authenticator;

    public JwtAuthenticationFilter(JwtTokenAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {

        String token = extractToken(request);
        if (token != null) {
            authenticator.authenticate(token).ifPresent(auth ->
                SecurityContextHolder.getContext().setAuthentication(auth));
        }

        chain.doFilter(request, response);
    }

    /** Cookie first (web), then a non-{@code psk_} Bearer header (native app). */
    private String extractToken(HttpServletRequest request) {
        String cookie = extractAccessTokenFromCookie(request);
        if (cookie != null) {
            return cookie;
        }
        return extractBearerToken(request);
    }

    private String extractAccessTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if ("access_token".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        // Leave MCP access keys to AccessKeyAuthFilter; they are not JWTs.
        if (token.isEmpty() || token.startsWith(MCP_KEY_PREFIX)) {
            return null;
        }
        return token;
    }
}
