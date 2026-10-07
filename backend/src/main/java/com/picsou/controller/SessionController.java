package com.picsou.controller;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.AuthorizationServerConfig;
import com.picsou.config.JwtUtil;
import com.picsou.dto.SessionResponse;
import com.picsou.model.AppUser;
import com.picsou.model.PersistentSession;
import com.picsou.service.NativeAppSessionService;
import com.picsou.service.NativeAppSessionService.NativeAppSession;
import com.picsou.service.PersistentSessionService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Self-service "Active sessions" list for the authenticated user. Two kinds of
 * row: a {@link PersistentSession} (browser Remember Me cookie) and an iOS app
 * sign-in (a live {@code picsou-ios} OAuth2 authorization, see
 * {@link NativeAppSessionService}). Users can revoke any one or "log out
 * everywhere else"; the request's own row — matched by the persistent_token
 * cookie's series_id, or by the Bearer token's {@code aid} claim — is flagged
 * {@code current} and spared by the bulk-revoke variant.
 */
@RestController
@RequestMapping("/api/auth/sessions")
public class SessionController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final PersistentSessionService persistentSessionService;
    private final NativeAppSessionService nativeAppSessionService;
    private final JwtUtil jwtUtil;

    public SessionController(PersistentSessionService persistentSessionService,
                             NativeAppSessionService nativeAppSessionService,
                             JwtUtil jwtUtil) {
        this.persistentSessionService = persistentSessionService;
        this.nativeAppSessionService = nativeAppSessionService;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping
    public List<SessionResponse> list(
        @AuthenticationPrincipal AppUser user,
        HttpServletRequest httpReq
    ) {
        UUID currentSeries = currentSeriesId(httpReq).orElse(null);
        String currentAuthorization = currentAuthorizationId(httpReq).orElse(null);
        return Stream.concat(
            persistentSessionService.listActiveForUser(user).stream()
                .map(s -> toResponse(s, currentSeries)),
            nativeAppSessionService.listActive(user).stream()
                .map(s -> toResponse(s, currentAuthorization))
        ).toList();
    }

    /** A numeric id is a Remember Me row; anything else is an app authorization id (a UUID). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(
        @AuthenticationPrincipal AppUser user,
        @PathVariable String id
    ) {
        // Both services return false when the row doesn't exist OR doesn't belong
        // to this user — both collapse to 404 to avoid leaking other users' ids.
        boolean revoked = isPersistentSessionId(id)
            ? persistentSessionService.revoke(Long.parseLong(id), user)
            : nativeAppSessionService.revoke(id, user);
        if (!revoked) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> revokeAllExceptCurrent(
        @AuthenticationPrincipal AppUser user,
        HttpServletRequest httpReq
    ) {
        UUID currentSeries = currentSeriesId(httpReq).orElse(null);

        // If we can identify the current session, exclude it; otherwise nuke
        // them all (request had no persistent cookie => user is on access_token
        // only or on the iOS app, no Remember Me session to spare).
        Long exceptId = persistentSessionService.listActiveForUser(user).stream()
            .filter(s -> currentSeries != null && currentSeries.equals(s.getSeriesId()))
            .map(PersistentSession::getId)
            .findFirst()
            .orElse(null);

        if (exceptId != null) {
            persistentSessionService.revokeAllForUserExcept(user.getId(), exceptId);
        } else {
            persistentSessionService.revokeAllForUser(user.getId());
        }
        nativeAppSessionService.revokeAllExcept(user, currentAuthorizationId(httpReq).orElse(null));
        return ResponseEntity.noContent().build();
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private Optional<UUID> currentSeriesId(HttpServletRequest httpReq) {
        if (httpReq.getCookies() == null) return Optional.empty();
        for (Cookie c : httpReq.getCookies()) {
            if (AuthCookieWriter.PERSISTENT_COOKIE.equals(c.getName())) {
                return persistentSessionService.seriesFromCookie(c.getValue());
            }
        }
        return Optional.empty();
    }

    /** The {@code aid} claim of the request's Bearer token, present only on an iOS app token. */
    private Optional<String> currentAuthorizationId(HttpServletRequest httpReq) {
        String header = httpReq.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) return Optional.empty();
        try {
            return Optional.ofNullable(jwtUtil.validateAndParse(header.substring(BEARER_PREFIX.length()).trim())
                .get(AuthorizationServerConfig.AUTHORIZATION_ID_CLAIM, String.class));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static boolean isPersistentSessionId(String id) {
        return !id.isEmpty() && id.length() <= 18 && id.chars().allMatch(Character::isDigit);
    }

    private static SessionResponse toResponse(PersistentSession s, UUID currentSeries) {
        return new SessionResponse(
            String.valueOf(s.getId()),
            SessionResponse.Kind.REMEMBER_ME,
            s.getUserAgent(),
            s.getIpPrefix(),
            s.getCreatedAt(),
            s.getLastUsedAt(),
            s.getExpiresAt(),
            s.isTrustedFor2fa(),
            currentSeries != null && currentSeries.equals(s.getSeriesId())
        );
    }

    private static SessionResponse toResponse(NativeAppSession s, String currentAuthorization) {
        return new SessionResponse(
            s.id(),
            SessionResponse.Kind.IOS_APP,
            null,
            null,
            s.createdAt(),
            s.lastUsedAt(),
            s.expiresAt(),
            false,
            s.id().equals(currentAuthorization)
        );
    }
}
