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
 *   <li>the {@code Authorization: Bearer <jwt>} header — the native iOS app, whose tokens are
 *       minted by the OAuth2 authorization server but HS256-signed with the same secret and
 *       carry the same claims, so they validate through the identical path;</li>
 *   <li>the {@code access_token} HttpOnly cookie — the web client.</li>
 * </ol>
 * A Bearer is an explicit choice of identity, so when one is sent the cookie is never read, even
 * if the Bearer is invalid: a stale or other-user cookie must not win, and a rejected Bearer must
 * surface as a 401 the client can refresh on rather than silently fall back to another session.
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

    /** A non-{@code psk_} Bearer header (native app) if present, else the cookie (web). */
    private String extractToken(HttpServletRequest request) {
        String bearer = extractBearerToken(request);
        if (bearer != null) {
            return bearer;
        }
        return extractAccessTokenFromCookie(request);
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

    static String extractBearerToken(HttpServletRequest request) {
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
