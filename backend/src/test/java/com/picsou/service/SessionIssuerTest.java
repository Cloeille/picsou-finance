package com.picsou.service;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.JwtUtil;
import com.picsou.config.SecureCookieProvider;
import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.repository.PersistentSessionRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionIssuerTest {
    private static final Instant SSO_AT = Instant.parse("2026-10-31T12:00:00Z");
    @Mock PersistentSessionRepository repository;
    @Mock PersistentSessionService sessions;
    JwtUtil jwt = new JwtUtil("0123456789abcdef0123456789abcdef-test", 15, 7, 5);
    AppUser user = AppUser.builder().id(7L).username("alice").role(UserRole.ADMIN).tokenVersion(3L).build();
    MockHttpServletRequest request;
    MockHttpServletResponse response;
    SessionIssuer issuer;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        issuer = new SessionIssuer(jwt, new AuthCookieWriter(mock(SecureCookieProvider.class), jwt), sessions,
            Clock.systemUTC());
    }

    @Test
    void issue_rememberMe_storesSsoAt_andBindsRefreshToNewSeries() {
        PersistentSessionService realSessions = new PersistentSessionService(repository,
            Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), ZoneOffset.UTC), 90, 30);
        issuer = new SessionIssuer(jwt, new AuthCookieWriter(mock(SecureCookieProvider.class), jwt), realSessions,
            Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), ZoneOffset.UTC));

        issuer.issue(user, true, true, SSO_AT, request, response);

        var saved = org.mockito.ArgumentCaptor.forClass(com.picsou.model.PersistentSession.class);
        verify(repository).save(saved.capture());
        Claims refresh = jwt.validateAndParse(response.getCookie("refresh_token").getValue());
        assertThat(saved.getValue().getSsoAuthenticatedAt()).isEqualTo(SSO_AT);
        assertThat(saved.getValue().isTrustedFor2fa()).isTrue();
        assertThat(jwt.getSeriesId(refresh)).isEqualTo(saved.getValue().getSeriesId());
        assertThat(jwt.getSsoAuthenticatedAt(refresh)).isEqualTo(SSO_AT);
        assertThat(jwt.getSsoAuthenticatedAt(jwt.validateAndParse(response.getCookie("access_token").getValue())))
            .isEqualTo(SSO_AT);
        assertThat(response.getCookie("persistent_token").getValue()).startsWith(saved.getValue().getSeriesId() + ":");
        assertThat(response.getCookie("persistent_token").getMaxAge()).isEqualTo(90 * 24 * 60 * 60);
    }

    @ParameterizedTest
    @ValueSource(longs = {7L, 99L})
    void issue_withoutRememberMe_preservesOnlyOwnPersistentCookie(long owner) {
        request.setCookies(new Cookie("persistent_token", "existing"));
        when(sessions.ownerUserId("existing")).thenReturn(Optional.of(owner));

        issuer.issue(user, false, false, SSO_AT, request, response);

        assertThat(response.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("access_token=")
            || h.startsWith("refresh_token="))).allSatisfy(h -> assertThat(h).doesNotContain("Max-Age"));
        assertThat(jwt.getSsoAuthenticatedAt(jwt.validateAndParse(response.getCookie("refresh_token").getValue())))
            .isEqualTo(SSO_AT);
        if (owner == 7L) assertThat(response.getCookie("persistent_token")).isNull();
        else assertThat(response.getCookie("persistent_token").getMaxAge()).isZero();
    }

    @Test
    void currentSsoAt_refreshWinsOverAccessAndSeries() {
        request.setCookies(new Cookie("refresh_token", jwt.generateRefreshToken(user, null, SSO_AT)),
            new Cookie("access_token", jwt.generateAccessToken(user, SSO_AT.minusSeconds(10))),
            new Cookie("persistent_token", "series:token"));

        assertThat(issuer.currentSsoAt(request)).isEqualTo(SSO_AT);
        verifyNoInteractions(sessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid", "wrong-type", "password"})
    void currentSsoAt_fallsBackToWebAccess_whenRefreshHasNoMarker(String refreshKind) {
        String refresh = switch (refreshKind) {
            case "missing" -> null;
            case "invalid" -> "invalid";
            case "wrong-type" -> jwt.generateAccessToken(user, SSO_AT.minusSeconds(10));
            case "password" -> jwt.generateRefreshToken(user);
            default -> throw new IllegalArgumentException(refreshKind);
        };
        request.setCookies(refresh == null ? new Cookie[]{new Cookie("access_token", jwt.generateAccessToken(user, SSO_AT)),
                new Cookie("persistent_token", "series:token")}
            : new Cookie[]{new Cookie("access_token", jwt.generateAccessToken(user, SSO_AT)), new Cookie("refresh_token", refresh),
                new Cookie("persistent_token", "series:token")});

        assertThat(issuer.currentSsoAt(request)).isEqualTo(SSO_AT);
        verifyNoInteractions(sessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aud", "iss"})
    void currentSsoAt_excludesAuthorizationServerAccessMarker(String claim) {
        String token = Jwts.builder().claim("type", "access").claim(claim, "authorization-server")
            .claim("sso_at", SSO_AT.getEpochSecond())
            .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                "0123456789abcdef0123456789abcdef-test".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .compact();
        request.setCookies(new Cookie("access_token", token));

        assertThat(issuer.currentSsoAt(request)).isNull();
    }

    @Test
    void currentSsoAt_fallsBackToPersistentSeries() {
        UUID series = UUID.randomUUID();
        request.setCookies(new Cookie("persistent_token", "persistent"));
        when(sessions.seriesFromCookie("persistent")).thenReturn(Optional.of(series));
        when(sessions.ssoAuthenticatedAtOf(series)).thenReturn(Optional.of(SSO_AT));

        assertThat(issuer.currentSsoAt(request)).isEqualTo(SSO_AT);
    }

    @Test
    void currentSsoAt_withoutSession_isNull() {
        assertThat(issuer.currentSsoAt(request)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"aud", "iss", "neither"})
    void isAuthorizationServerToken_detectsEitherReservedClaim(String claim) {
        Claims claims = claim.equals("neither") ? jwt.validateAndParse(jwt.generateAccessToken(user))
            : Jwts.claims().add(claim, "issuer-or-audience").build();

        assertThat(issuer.isAuthorizationServerToken(claims)).isEqualTo(!claim.equals("neither"));
    }

    @Test
    void requestAccessClaims_cookieTakesPrecedenceOverBearer() {
        request.setCookies(new Cookie("access_token", jwt.generateAccessToken(user, SSO_AT)));
        request.addHeader("Authorization", "Bearer " + jwt.generateAccessToken(user, SSO_AT.minusSeconds(10)));

        assertThat(issuer.requestAccessClaims(request).map(jwt::getSsoAuthenticatedAt)).contains(SSO_AT);
    }

    @Test
    void requestAccessClaims_usesBearerWithoutCookie() {
        request.addHeader("Authorization", "Bearer " + jwt.generateAccessToken(user, SSO_AT));

        assertThat(issuer.currentSsoAt(request)).isEqualTo(SSO_AT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "", "refresh"})
    void requestAccessClaims_invalidCookieDoesNotFallThroughToBearer(String kind) {
        request.setCookies(new Cookie("access_token", kind.equals("refresh") ? jwt.generateRefreshToken(user) : kind));
        request.addHeader("Authorization", "Bearer " + jwt.generateAccessToken(user, SSO_AT));

        assertThat(issuer.requestAccessClaims(request)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer psk_secret", "Basic credentials", "Bearer ", "Bearer invalid"})
    void requestAccessClaims_rejectsNonJwtBearer(String header) {
        request.addHeader("Authorization", header);

        assertThat(issuer.requestAccessClaims(request)).isEmpty();
    }
}
