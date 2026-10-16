package com.picsou.config;

import com.picsou.model.AppUser;
import com.picsou.model.PersistentSession;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Re-authenticates a request that arrives with no valid {@code access_token}
 * but a valid {@code persistent_token} ("Remember Me" cookie). Runs AFTER
 * {@link JwtAuthenticationFilter} so an active access cookie short-circuits
 * and we don't pay the DB hit on every request.
 *
 * <p>On a successful match the filter:
 * <ol>
 *   <li>rotates the persistent token (defends against replay theft),</li>
 *   <li>sets new {@code access_token} + {@code refresh_token} cookies,</li>
 *   <li>sets the {@link SecurityContextHolder} for the current request so
 *       downstream authorization treats it as authenticated.</li>
 * </ol>
 *
 * <p>On a malformed/expired/replayed token the {@code persistent_token}
 * cookie is cleared so the browser stops sending the bad value.
 */
public class PersistentTokenAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PersistentTokenAuthFilter.class);

    private final PersistentSessionService persistentSessionService;
    private final AppUserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final AuthCookieWriter cookieWriter;
    private final MfaService mfaService;

    public PersistentTokenAuthFilter(
        PersistentSessionService persistentSessionService,
        AppUserRepository userRepository,
        JwtUtil jwtUtil,
        AuthCookieWriter cookieWriter,
        MfaService mfaService
    ) {
        this.persistentSessionService = persistentSessionService;
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.cookieWriter = cookieWriter;
        this.mfaService = mfaService;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {

        // Already authenticated by JwtAuthenticationFilter — bail out, no DB hit. A request that
        // chose a Bearer identity never falls back to this browser cookie, even when that Bearer
        // was rejected (see JwtAuthenticationFilter).
        if (SecurityContextHolder.getContext().getAuthentication() != null
                || JwtAuthenticationFilter.extractBearerToken(request) != null) {
            chain.doFilter(request, response);
            return;
        }

        String cookieValue = extractCookie(request, AuthCookieWriter.PERSISTENT_COOKIE);
        if (cookieValue == null || cookieValue.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        Optional<PersistentSessionService.ValidationResult> validated;
        try {
            validated = persistentSessionService.validateAndRotate(cookieValue);
        } catch (RuntimeException ex) {
            // DB error or unexpected — fail closed (anonymous) and keep serving.
            log.warn("Persistent token validation failed: {}", ex.getMessage());
            chain.doFilter(request, response);
            return;
        }

        if (validated.isEmpty()) {
            // Bad / expired / revoked / theft-replayed — drop the cookie client-side.
            cookieWriter.clearPersistent(response);
            chain.doFilter(request, response);
            return;
        }

        PersistentSession session = validated.get().session();
        AppUser user = userRepository.findByIdWithMember(session.getUser().getId()).orElse(null);
        if (user == null || !user.isActivated()) {
            cookieWriter.clearPersistent(response);
            chain.doFilter(request, response);
            return;
        }

        // Honour the trusted-device promise: if the user has 2FA enabled but this
        // session was issued without trust, the cookie alone must not bypass MFA.
        // Clear it so the browser stops auto-attempting silent re-login.
        if (mfaService.isEnabled(user) && !session.isTrustedFor2fa()) {
            cookieWriter.clearPersistent(response);
            chain.doFilter(request, response);
            return;
        }

        // Mint fresh access/refresh + re-set the rotated persistent cookie.
        // Rotated cookie carries the same series_id; remaining lifetime = expiresAt - now.
        // The refresh token is bound to this series (sid) so /auth/refresh can cut its
        // chain the moment the session is revoked ("log out this device").
        cookieWriter.setAccessAndRefresh(response,
            jwtUtil.generateAccessToken(user),
            jwtUtil.generateRefreshToken(user, session.getSeriesId()),
            true);
        long secondsUntilExpiry = Math.max(
            ChronoUnit.SECONDS.between(java.time.Instant.now(), session.getExpiresAt()),
            0
        );
        cookieWriter.setPersistent(response, validated.get().rotatedCookieValue(), secondsUntilExpiry);

        // Authorise the current request as this user — JwtAuthenticationFilter
        // already ran upstream so it can't pick up the new cookie this round-trip.
        String role = "ROLE_" + user.getRole().name();
        var auth = new UsernamePasswordAuthenticationToken(
            user, null, List.of(new SimpleGrantedAuthority(role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(request, response);
    }

    private String extractCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
