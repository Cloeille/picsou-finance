package com.picsou.config;

import com.picsou.model.AppUser;
import com.picsou.model.PersistentSession;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersistentTokenAuthFilterTest {

    @Mock PersistentSessionService persistentSessionService;
    @Mock AppUserRepository userRepository;
    @Mock JwtUtil jwtUtil;
    @Mock AuthCookieWriter cookieWriter;
    @Mock MfaService mfaService;
    @Mock FilterChain chain;

    PersistentTokenAuthFilter filter;
    MockHttpServletRequest request;
    MockHttpServletResponse response;
    AppUser user;
    SsoProperties properties;
    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

    @BeforeEach
    void setUp() {
        properties = new SsoProperties();
        properties.setIssuerUri("https://idp.example");
        properties.setClientId("picsou");
        properties.setSessionRevalidateAfter("1m");
        filter = new PersistentTokenAuthFilter(persistentSessionService, userRepository, jwtUtil, cookieWriter, mfaService,
            properties, Clock.fixed(NOW, ZoneOffset.UTC));
        request = new MockHttpServletRequest("GET", "/api/accounts");
        request.setServletPath("/api/accounts");
        response = new MockHttpServletResponse();
        user = AppUser.builder().id(7L).username("alice").role(UserRole.MEMBER).activated(true).build();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ─── short-circuits ──────────────────────────────────────────────────

    @Test
    void noOps_whenSecurityContextAlreadySet() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("someone", null));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(persistentSessionService, userRepository, jwtUtil, cookieWriter);
    }

    @Test
    void spaPath_neverRotatesThePersistentToken() throws Exception {
        request.setRequestURI("/assets/chunk-abc.js");
        request.setServletPath("/assets/chunk-abc.js");
        request.setCookies(new Cookie("persistent_token", "series:token"));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(persistentSessionService, userRepository, jwtUtil, cookieWriter);
        assertThat(response.getHeader("Set-Cookie")).isNull();
    }

    @Test
    void noOps_whenNoPersistentCookie() throws Exception {
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(persistentSessionService, userRepository, jwtUtil, cookieWriter);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void noOps_whenPersistentCookieIsBlank() throws Exception {
        request.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, ""));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(persistentSessionService, userRepository, jwtUtil, cookieWriter);
    }

    // ─── failure paths clear the cookie ──────────────────────────────────

    @Test
    void clearsCookie_whenValidationFails() throws Exception {
        request.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "bad-cookie"));
        when(persistentSessionService.validateAndRotate("bad-cookie")).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        verify(cookieWriter).clearPersistent(response);
        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void clearsCookie_whenUserMissing() throws Exception {
        setupValidCookieFor(7L, false);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        verify(cookieWriter).clearPersistent(response);
        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void clearsCookie_whenUserNotActivated() throws Exception {
        user.setActivated(false);
        setupValidCookieFor(7L, false);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(user));

        filter.doFilter(request, response, chain);

        verify(cookieWriter).clearPersistent(response);
        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void noOps_whenServiceThrows() throws Exception {
        request.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "boom"));
        when(persistentSessionService.validateAndRotate("boom"))
            .thenThrow(new RuntimeException("db down"));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(cookieWriter, never()).clearPersistent(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ─── happy paths ─────────────────────────────────────────────────────

    @Test
    void authenticatesAndRotates_onValidCookie_whenMfaDisabled() throws Exception {
        setupValidCookieFor(7L, false);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(user));
        when(mfaService.isEnabled(user)).thenReturn(false);
        when(jwtUtil.generateAccessToken(user)).thenReturn("new-access");
        // Refresh is now bound to the rotated session's series (sid), so it is minted via
        // the (AppUser, UUID) overload rather than the bare one.
        when(jwtUtil.generateRefreshToken(eq(user), any())).thenReturn("new-refresh");

        filter.doFilter(request, response, chain);

        verify(cookieWriter).setAccessAndRefresh(response, "new-access", "new-refresh", true);
        verify(cookieWriter).setPersistent(eq(response), eq("rotated-cookie-value"), anyLong());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(user);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
            .extracting(Object::toString).contains("ROLE_MEMBER");
        verify(chain).doFilter(request, response);
    }

    @Test
    void authenticates_whenMfaEnabledAndSessionTrusted() throws Exception {
        setupValidCookieFor(7L, true);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(user));
        when(mfaService.isEnabled(user)).thenReturn(true);
        when(jwtUtil.generateAccessToken(user)).thenReturn("a");
        when(jwtUtil.generateRefreshToken(eq(user), any())).thenReturn("r");

        filter.doFilter(request, response, chain);

        verify(cookieWriter).setAccessAndRefresh(response, "a", "r", true);
        verify(cookieWriter).setPersistent(eq(response), eq("rotated-cookie-value"), anyLong());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void clearsCookie_whenMfaEnabledButSessionNotTrusted() throws Exception {
        setupValidCookieFor(7L, false);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(user));
        when(mfaService.isEnabled(user)).thenReturn(true);

        filter.doFilter(request, response, chain);

        verify(cookieWriter).clearPersistent(response);
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    // ─── helper ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {30, 60})
    void freshSsoSeries_restoresWithoutLocalMfa_andCarriesMarker(long ageSeconds) throws Exception {
        PersistentSession session = setupValidCookieFor(7L, false);
        Instant ssoAt = NOW.minusSeconds(ageSeconds);
        session.setSsoAuthenticatedAt(ssoAt);
        org.mockito.Mockito.lenient().when(mfaService.isEnabled(user)).thenReturn(true);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(user));
        JwtUtil realJwt = new JwtUtil("0123456789abcdef0123456789abcdef-test", 15, 7, 5);
        AuthCookieWriter writer = new AuthCookieWriter(org.mockito.Mockito.mock(SecureCookieProvider.class), realJwt);
        filter = new PersistentTokenAuthFilter(persistentSessionService, userRepository, realJwt, writer, mfaService,
            properties, Clock.fixed(NOW, ZoneOffset.UTC));

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(user);
        assertThat(realJwt.getSsoAuthenticatedAt(realJwt.validateAndParse(response.getCookie("access_token").getValue())))
            .isEqualTo(ssoAt);
        assertThat(realJwt.getSsoAuthenticatedAt(realJwt.validateAndParse(response.getCookie("refresh_token").getValue())))
            .isEqualTo(ssoAt);
        verifyNoInteractions(mfaService);
    }

    @ParameterizedTest
    @CsvSource({"true,61", "false,0"})
    void ssoSeries_requiresRevalidation_whenExpiredOrDisabled(boolean enabled, long ageSeconds) throws Exception {
        PersistentSession session = setupValidCookieFor(7L, false);
        session.setSsoAuthenticatedAt(NOW.minusSeconds(ageSeconds));
        if (!enabled) properties.setIssuerUri(null);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(user));

        filter.doFilter(request, response, chain);

        verify(persistentSessionService).revokeBySeriesId(session.getSeriesId());
        verify(cookieWriter).clearPersistent(response);
        assertThat(request.getAttribute("picsou.sso.revalidate")).isEqualTo(Boolean.TRUE);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verifyNoInteractions(mfaService, jwtUtil);
    }

    /**
     * Plumbs a request that carries a "valid" persistent cookie and stubs
     * the session service to return a freshly-rotated session for the given
     * user id with the given trusted-for-2fa flag.
     */
    private PersistentSession setupValidCookieFor(long userId, boolean trustedFor2fa) {
        String rawCookie = "abc:def";
        request.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, rawCookie));

        AppUser sessionUser = AppUser.builder().id(userId).username("alice").build();
        Instant now = NOW;
        PersistentSession session = PersistentSession.builder()
            .id(1L)
            .seriesId(UUID.randomUUID())
            .user(sessionUser)
            .tokenHash("h")
            .trustedFor2fa(trustedFor2fa)
            .createdAt(now.minus(1, ChronoUnit.DAYS))
            .lastUsedAt(now)
            .expiresAt(now.plus(80, ChronoUnit.DAYS))
            .build();

        when(persistentSessionService.validateAndRotate(rawCookie))
            .thenReturn(Optional.of(new PersistentSessionService.ValidationResult(
                "rotated-cookie-value", session
             )));
        return session;
    }
}
