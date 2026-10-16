package com.picsou.controller;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.AuthorizationServerConfig;
import com.picsou.config.JwtUtil;
import com.picsou.dto.SessionResponse;
import com.picsou.model.AppUser;
import com.picsou.model.PersistentSession;
import com.picsou.model.UserRole;
import com.picsou.service.NativeAppSessionService;
import com.picsou.service.NativeAppSessionService.NativeAppSession;
import com.picsou.service.PersistentSessionService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-test";

    @Mock PersistentSessionService persistentSessionService;
    @Mock NativeAppSessionService nativeAppSessionService;
    SessionController controller;

    AppUser user;
    MockHttpServletRequest httpReq;

    @BeforeEach
    void setUp() {
        controller = new SessionController(persistentSessionService, nativeAppSessionService,
            new JwtUtil(SECRET, 15, 7, 5));
        user = AppUser.builder()
            .id(7L).username("alice").role(UserRole.MEMBER).activated(true)
            .build();
        httpReq = new MockHttpServletRequest();
    }

    // ─── GET /sessions ───────────────────────────────────────────────────

    @Test
    void list_returnsActiveSessions_andMarksCurrent() {
        UUID seriesA = UUID.randomUUID();
        UUID seriesB = UUID.randomUUID();
        PersistentSession a = sessionWith(1L, seriesA, "Mac/Chrome", "10.0.0.", false);
        PersistentSession b = sessionWith(2L, seriesB, "iPhone/Safari", "192.168.1.", true);
        when(persistentSessionService.listActiveForUser(user)).thenReturn(List.of(a, b));

        // Cookie matches session B → it's flagged "current".
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "value-for-b"));
        when(persistentSessionService.seriesFromCookie("value-for-b"))
            .thenReturn(Optional.of(seriesB));

        List<SessionResponse> res = controller.list(user, httpReq);

        assertThat(res).hasSize(2);
        assertThat(res.get(0).id()).isEqualTo("1");
        assertThat(res.get(0).kind()).isEqualTo(SessionResponse.Kind.REMEMBER_ME);
        assertThat(res.get(0).current()).isFalse();
        assertThat(res.get(0).userAgent()).isEqualTo("Mac/Chrome");
        assertThat(res.get(0).ipPrefix()).isEqualTo("10.0.0.");
        assertThat(res.get(0).trustedFor2fa()).isFalse();

        assertThat(res.get(1).id()).isEqualTo("2");
        assertThat(res.get(1).current()).isTrue();
        assertThat(res.get(1).trustedFor2fa()).isTrue();
    }

    @Test
    void list_returnsEmpty_andSkipsCookieLookup_whenNoCookie() {
        when(persistentSessionService.listActiveForUser(user)).thenReturn(List.of());

        List<SessionResponse> res = controller.list(user, httpReq);

        assertThat(res).isEmpty();
        verify(persistentSessionService, never()).seriesFromCookie(any());
    }

    @Test
    void list_doesNotMarkCurrent_whenCookieIsUnparseable() {
        UUID series = UUID.randomUUID();
        PersistentSession s = sessionWith(1L, series, "ua", "10.", false);
        when(persistentSessionService.listActiveForUser(user)).thenReturn(List.of(s));

        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "garbage"));
        when(persistentSessionService.seriesFromCookie("garbage")).thenReturn(Optional.empty());

        List<SessionResponse> res = controller.list(user, httpReq);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).current()).isFalse();
    }

    // ─── DELETE /sessions/{id} ───────────────────────────────────────────

    @Test
    void revoke_returns204_onSuccess() {
        when(persistentSessionService.revoke(42L, user)).thenReturn(true);

        ResponseEntity<Void> res = controller.revoke(user, "42");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void revoke_returns404_whenServiceReturnsFalse() {
        when(persistentSessionService.revoke(99L, user)).thenReturn(false);

        ResponseEntity<Void> res = controller.revoke(user, "99");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── DELETE /sessions (all-except-current) ───────────────────────────

    @Test
    void revokeAll_excludesCurrentSession_whenCookieMatches() {
        UUID seriesA = UUID.randomUUID();
        UUID seriesB = UUID.randomUUID();
        PersistentSession a = sessionWith(1L, seriesA, "ua", "10.", false);
        PersistentSession b = sessionWith(2L, seriesB, "ua", "10.", false);
        when(persistentSessionService.listActiveForUser(user)).thenReturn(List.of(a, b));

        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "v"));
        when(persistentSessionService.seriesFromCookie("v")).thenReturn(Optional.of(seriesB));

        ResponseEntity<Void> res = controller.revokeAllExceptCurrent(user, httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(persistentSessionService).revokeAllForUserExcept(7L, 2L);
        verify(persistentSessionService, never()).revokeAllForUser(anyLong());
    }

    @Test
    void revokeAll_revokesAll_whenNoCookieOrNoMatch() {
        // No cookie at all.
        ResponseEntity<Void> res = controller.revokeAllExceptCurrent(user, httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(persistentSessionService).revokeAllForUser(7L);
        verify(persistentSessionService, never()).revokeAllForUserExcept(anyLong(), anyLong());
    }

    @Test
    void revokeAll_revokesAll_whenCookieDoesntMatchAnyActiveSession() {
        UUID seriesA = UUID.randomUUID();
        UUID seriesUnknown = UUID.randomUUID();
        PersistentSession a = sessionWith(1L, seriesA, "ua", "10.", false);
        when(persistentSessionService.listActiveForUser(user)).thenReturn(List.of(a));

        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "v"));
        when(persistentSessionService.seriesFromCookie("v")).thenReturn(Optional.of(seriesUnknown));

        controller.revokeAllExceptCurrent(user, httpReq);

        verify(persistentSessionService).revokeAllForUser(7L);
        verify(persistentSessionService, never()).revokeAllForUserExcept(anyLong(), anyLong());
    }

    // ─── iOS app rows ────────────────────────────────────────────────────

    @Test
    void list_appendsAppSignIns_andMarksTheOneTheBearerTokenBelongsTo() {
        Instant now = Instant.now();
        when(nativeAppSessionService.listActive(user)).thenReturn(List.of(
            new NativeAppSession("auth-a", now.minusSeconds(86400), now, now.plusSeconds(86400)),
            new NativeAppSession("auth-b", now.minusSeconds(7200), now.minusSeconds(3600), now.plusSeconds(86400))));
        httpReq.addHeader("Authorization", "Bearer " + appToken("auth-b"));

        List<SessionResponse> res = controller.list(user, httpReq);

        assertThat(res).extracting(SessionResponse::id).containsExactly("auth-a", "auth-b");
        assertThat(res).extracting(SessionResponse::kind)
            .containsOnly(SessionResponse.Kind.IOS_APP);
        assertThat(res).extracting(SessionResponse::current).containsExactly(false, true);
        assertThat(res.get(0).userAgent()).isNull();
        assertThat(res.get(0).lastUsedAt()).isEqualTo(now);
    }

    @Test
    void revoke_nonNumericId_targetsTheAppAuthorization() {
        when(nativeAppSessionService.revoke("auth-a", user)).thenReturn(true);

        ResponseEntity<Void> res = controller.revoke(user, "auth-a");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(persistentSessionService, never()).revoke(anyLong(), any());
    }

    @Test
    void revoke_unknownAppAuthorization_returns404() {
        when(nativeAppSessionService.revoke("someone-elses", user)).thenReturn(false);

        assertThat(controller.revoke(user, "someone-elses").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void revokeAll_fromTheApp_keepsItsOwnAuthorization_andDropsEveryRememberMeSession() {
        httpReq.addHeader("Authorization", "Bearer " + appToken("auth-current"));

        controller.revokeAllExceptCurrent(user, httpReq);

        verify(nativeAppSessionService).revokeAllExcept(user, "auth-current");
        verify(persistentSessionService).revokeAllForUser(7L);
    }

    @Test
    void revokeAll_fromTheBrowser_revokesEveryAppSignIn() {
        controller.revokeAllExceptCurrent(user, httpReq);

        verify(nativeAppSessionService).revokeAllExcept(user, null);
    }

    @Test
    void revokeAll_withANonAppBearer_sparesNoAppSignIn() {
        httpReq.addHeader("Authorization", "Bearer not-a-jwt");

        controller.revokeAllExceptCurrent(user, httpReq);

        verify(nativeAppSessionService).revokeAllExcept(user, null);
        verify(nativeAppSessionService, never()).revoke(anyString(), any());
    }

    private String appToken(String authorizationId) {
        return Jwts.builder()
            .subject("alice")
            .claim("uid", 7L)
            .claim("type", "access")
            .claim("tv", 0L)
            .claim(AuthorizationServerConfig.AUTHORIZATION_ID_CLAIM, authorizationId)
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plusSeconds(900)))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
            .compact();
    }

    // ─── helper ──────────────────────────────────────────────────────────

    private PersistentSession sessionWith(Long id, UUID series, String ua, String ipPrefix, boolean trusted) {
        Instant now = Instant.now();
        return PersistentSession.builder()
            .id(id)
            .seriesId(series)
            .user(user)
            .tokenHash("h")
            .userAgent(ua)
            .ipPrefix(ipPrefix)
            .trustedFor2fa(trusted)
            .createdAt(now.minus(2, ChronoUnit.DAYS))
            .lastUsedAt(now)
            .expiresAt(now.plus(80, ChronoUnit.DAYS))
            .build();
    }
}
