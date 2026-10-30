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
 * Bridges the stateless cookie session into the OAuth2 authorization-server chain.
 *
 * <p>The authorization endpoint ({@code /oauth2/authorize}) needs a {@code SecurityContext} to
 * know <em>who</em> is authorizing the native client. The rest of the app is stateless and
 * authenticates from the {@code access_token} cookie, so this filter reuses that exact cookie
 * → user path ({@link JwtTokenAuthenticator}) to populate the context for the current request.
 *
 * <p>When no valid cookie is present the request stays anonymous and the chain's
 * {@code AuthenticationEntryPoint} redirects the in-app browser to the existing SPA login
 * (password + TOTP + Remember-Me), which sets the cookie and bounces back to
 * {@code /oauth2/authorize}. No new login UI, no session-based MFA rebuild.
 */
public class CookieBridgeAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenAuthenticator authenticator;

    public CookieBridgeAuthenticationFilter(JwtTokenAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = extractAccessTokenFromCookie(request);
            if (token != null) {
                authenticator.authenticate(token).ifPresent(auth ->
                    SecurityContextHolder.getContext().setAuthentication(auth));
            }
        }

        chain.doFilter(request, response);
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
}
