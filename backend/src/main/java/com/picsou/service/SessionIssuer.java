package com.picsou.service;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.ClientIp;
import com.picsou.config.JwtUtil;
import com.picsou.model.AppUser;
import com.picsou.model.PersistentSession;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Component
public class SessionIssuer {
    private final JwtUtil jwtUtil;
    private final AuthCookieWriter cookieWriter;
    private final PersistentSessionService persistentSessionService;

    public SessionIssuer(JwtUtil jwtUtil, AuthCookieWriter cookieWriter,
                         PersistentSessionService persistentSessionService) {
        this.jwtUtil = jwtUtil;
        this.cookieWriter = cookieWriter;
        this.persistentSessionService = persistentSessionService;
    }

    public void issue(AppUser user, boolean issuePersistent, boolean trustedFor2fa,
                      Instant ssoAuthenticatedAt, HttpServletRequest httpReq, HttpServletResponse httpRes) {
        if (issuePersistent) {
            PersistentSessionService.IssueResult issued = persistentSessionService.issue(
                user,
                trustedFor2fa,
                httpReq.getHeader("User-Agent"),
                getClientIp(httpReq),
                ssoAuthenticatedAt
            );
            PersistentSession session = issued.session();
            // Bind the refresh token to the freshly-issued series (sid) so revoking this
            // device later cuts its refresh chain -- see /auth/refresh.
            cookieWriter.setAccessAndRefresh(httpRes,
                jwtUtil.generateAccessToken(user, ssoAuthenticatedAt),
                jwtUtil.generateRefreshToken(user, session.getSeriesId(), ssoAuthenticatedAt),
                true);
            long secondsUntilExpiry = Math.max(
                ChronoUnit.SECONDS.between(Instant.now(), session.getExpiresAt()),
                0
            );
            cookieWriter.setPersistent(httpRes, issued.cookieValue(), secondsUntilExpiry);
        } else {
            cookieWriter.setAccessAndRefresh(httpRes,
                jwtUtil.generateAccessToken(user, ssoAuthenticatedAt),
                jwtUtil.generateRefreshToken(user, null, ssoAuthenticatedAt),
                false);
            // Preserve this user's trusted device, but drop another identity's cookie
            // so it cannot restore that identity after the access token expires.
            String existingPersistent = extractCookie(httpReq, AuthCookieWriter.PERSISTENT_COOKIE);
            if (existingPersistent != null && !existingPersistent.isBlank()
                && persistentSessionService.ownerUserId(existingPersistent)
                    .filter(ownerId -> ownerId.equals(user.getId()))
                    .isEmpty()) {
                cookieWriter.clearPersistent(httpRes);
            }
        }
    }

    public boolean isPersistentDevice(HttpServletRequest httpReq, AppUser user) {
        String cookie = extractCookie(httpReq, AuthCookieWriter.PERSISTENT_COOKIE);
        if (cookie == null || cookie.isBlank()) return false;
        return persistentSessionService.ownerUserId(cookie).filter(id -> id.equals(user.getId())).isPresent();
    }

    public String rotatedRefreshToken(AppUser user, boolean persistent, UUID series, Instant ssoAt) {
        return jwtUtil.generateRefreshToken(user, persistent ? series : null, ssoAt);
    }

    public Optional<UUID> currentSeriesId(HttpServletRequest httpReq) {
        String cookie = extractCookie(httpReq, AuthCookieWriter.PERSISTENT_COOKIE);
        if (cookie == null || cookie.isBlank()) return Optional.empty();
        return persistentSessionService.seriesFromCookie(cookie);
    }

    public Optional<Claims> requestAccessClaims(HttpServletRequest request) {
        String token = extractCookie(request, AuthCookieWriter.ACCESS_COOKIE);
        if (token == null) {
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) return Optional.empty();
            token = header.substring("Bearer ".length()).trim();
            if (token.startsWith("psk_")) return Optional.empty();
        }
        if (token.isBlank()) return Optional.empty();
        try {
            Claims claims = jwtUtil.validateAndParse(token);
            return jwtUtil.isAccessToken(claims) ? Optional.of(claims) : Optional.empty();
        } catch (JwtException ex) {
            return Optional.empty();
        }
    }

    public boolean isAuthorizationServerToken(Claims claims) {
        return claims.containsKey("aud") || claims.containsKey("iss");
    }

    public Optional<Claims> webAccessClaims(HttpServletRequest request) {
        return requestAccessClaims(request).filter(claims -> !isAuthorizationServerToken(claims));
    }

    public Instant currentSsoAt(HttpServletRequest request) {
        String refresh = extractCookie(request, AuthCookieWriter.REFRESH_COOKIE);
        if (refresh != null && !refresh.isBlank()) {
            try {
                Claims claims = jwtUtil.validateAndParse(refresh);
                if (jwtUtil.isRefreshToken(claims)) {
                    Instant ssoAt = jwtUtil.getSsoAuthenticatedAt(claims);
                    if (ssoAt != null) return ssoAt;
                }
            } catch (JwtException ex) {
                // An unusable refresh token may coexist with a valid restored session.
            }
        }
        return webAccessClaims(request).map(jwtUtil::getSsoAuthenticatedAt)
            .or(() -> currentSeriesId(request).flatMap(persistentSessionService::ssoAuthenticatedAtOf))
            .orElse(null);
    }

    private String extractCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (var cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private String getClientIp(HttpServletRequest request) {
        return ClientIp.resolve(request);
    }
}
