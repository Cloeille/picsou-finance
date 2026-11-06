package com.picsou.config;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The cookie bridge populates the SecurityContext for the OAuth2 authorize endpoint from the
 * existing {@code access_token} cookie, and only when nothing else has authenticated the request.
 */
@ExtendWith(MockitoExtension.class)
class CookieBridgeAuthenticationFilterTest {

    @Mock JwtTokenAuthenticator authenticator;
    @Mock JwtUtil jwtUtil;
    SsoProperties properties;
    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

    CookieBridgeAuthenticationFilter filter;
    MockHttpServletRequest request;
    MockHttpServletResponse response;
    MockFilterChain chain;

    private static final Authentication AUTH =
        new UsernamePasswordAuthenticationToken("alice", null, List.of());

    @BeforeEach
    void setUp() {
        properties = new SsoProperties();
        properties.setIssuerUri("https://idp.example");
        properties.setClientId("picsou");
        properties.setSessionRevalidateAfter("1m");
        filter = new CookieBridgeAuthenticationFilter(authenticator, jwtUtil, properties,
            Clock.fixed(NOW, ZoneOffset.UTC));
        request = new MockHttpServletRequest();
        request.setMethod("GET");
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validCookie_authenticatesTheAuthorizeRequest() throws Exception {
        request.setCookies(new Cookie("access_token", "cookie-jwt"));
        when(jwtUtil.validateAndParse("cookie-jwt")).thenReturn(org.mockito.Mockito.mock(Claims.class));
        when(authenticator.authenticate("cookie-jwt")).thenReturn(Optional.of(AUTH));

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(AUTH);
    }

    @ParameterizedTest
    @CsvSource({"GET,true,61,false", "GET,false,0,false", "GET,true,60,true", "GET,true,30,true", "POST,true,61,true"})
    void ssoWindow_gatesAuthorizationGet_butNotConsentPost(String method, boolean enabled,
                                                         long ageSeconds, boolean authenticated) throws Exception {
        request.setMethod(method);
        request.setCookies(new Cookie("access_token", "sso-jwt"));
        if (!enabled) properties.setIssuerUri(null);
        if (method.equals("GET")) {
            Claims claims = org.mockito.Mockito.mock(Claims.class);
            when(jwtUtil.validateAndParse("sso-jwt")).thenReturn(claims);
            when(jwtUtil.getSsoAuthenticatedAt(claims)).thenReturn(NOW.minusSeconds(ageSeconds));
        }
        org.mockito.Mockito.lenient().when(authenticator.authenticate("sso-jwt")).thenReturn(Optional.of(AUTH));

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isEqualTo(authenticated ? AUTH : null);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void invalidJwt_stillUsesExistingAuthenticator() throws Exception {
        request.setCookies(new Cookie("access_token", "invalid"));
        when(jwtUtil.validateAndParse("invalid")).thenThrow(new JwtException("invalid"));
        when(authenticator.authenticate("invalid")).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(authenticator).authenticate("invalid");
    }

    @Test
    void noCookie_staysAnonymous_soEntryPointCanRedirect() throws Exception {
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(authenticator, never()).authenticate(any());
    }

    @Test
    void alreadyAuthenticated_isNotOverwritten() throws Exception {
        Authentication existing = new UsernamePasswordAuthenticationToken("bob", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(existing);
        request.setCookies(new Cookie("access_token", "cookie-jwt"));

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existing);
        verify(authenticator, never()).authenticate(any());
    }
}
