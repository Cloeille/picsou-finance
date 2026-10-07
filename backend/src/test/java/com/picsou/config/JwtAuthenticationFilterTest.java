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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The filter's job is transport selection: a non-{@code psk_} Bearer header, else the cookie.
 * Actual token validation is delegated to {@link JwtTokenAuthenticator} (mocked here).
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock JwtTokenAuthenticator authenticator;

    JwtAuthenticationFilter filter;
    MockHttpServletRequest request;
    MockHttpServletResponse response;
    MockFilterChain chain;

    private static final Authentication AUTH =
        new UsernamePasswordAuthenticationToken("alice", null, List.of());

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(authenticator);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void cookieToken_authenticates() throws Exception {
        request.setCookies(new Cookie("access_token", "cookie-jwt"));
        when(authenticator.authenticate("cookie-jwt")).thenReturn(Optional.of(AUTH));

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(AUTH);
    }

    @Test
    void bearerToken_authenticates_whenNoCookie() throws Exception {
        request.addHeader("Authorization", "Bearer app-jwt");
        when(authenticator.authenticate("app-jwt")).thenReturn(Optional.of(AUTH));

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(AUTH);
    }

    @Test
    void mcpAccessKeyBearer_isIgnored() throws Exception {
        // psk_ bearers belong to AccessKeyAuthFilter on /mcp — never validated here.
        request.addHeader("Authorization", "Bearer psk_deadbeef");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(authenticator, never()).authenticate(any());
    }

    @Test
    void bearerTakesPrecedenceOverAnotherUsersCookie() throws Exception {
        Authentication userB = new UsernamePasswordAuthenticationToken("bob", null, List.of());
        request.setCookies(new Cookie("access_token", "bob-cookie-jwt"));
        request.addHeader("Authorization", "Bearer alice-app-jwt");
        when(authenticator.authenticate("alice-app-jwt")).thenReturn(Optional.of(AUTH));
        lenient().when(authenticator.authenticate("bob-cookie-jwt")).thenReturn(Optional.of(userB));

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("alice");
    }

    @Test
    void rejectedBearer_doesNotFallBackToAValidCookie() throws Exception {
        request.setCookies(new Cookie("access_token", "valid-cookie-jwt"));
        request.addHeader("Authorization", "Bearer expired-app-jwt");
        when(authenticator.authenticate("expired-app-jwt")).thenReturn(Optional.empty());
        lenient().when(authenticator.authenticate("valid-cookie-jwt")).thenReturn(Optional.of(AUTH));

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(authenticator, never()).authenticate("valid-cookie-jwt");
    }

    @Test
    void mcpAccessKeyBearer_stillLetsTheCookieAuthenticate() throws Exception {
        request.setCookies(new Cookie("access_token", "cookie-jwt"));
        request.addHeader("Authorization", "Bearer psk_deadbeef");
        when(authenticator.authenticate("cookie-jwt")).thenReturn(Optional.of(AUTH));

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(AUTH);
    }

    @Test
    void noToken_leavesUnauthenticated() throws Exception {
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(authenticator, never()).authenticate(any());
    }
}
