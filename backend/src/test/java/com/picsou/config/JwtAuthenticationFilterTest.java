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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The filter's job is transport selection: cookie first, then a non-{@code psk_} Bearer header.
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
    void cookieTakesPrecedenceOverBearer() throws Exception {
        request.setCookies(new Cookie("access_token", "cookie-jwt"));
        request.addHeader("Authorization", "Bearer app-jwt");
        when(authenticator.authenticate("cookie-jwt")).thenReturn(Optional.of(AUTH));

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(AUTH);
        verify(authenticator, never()).authenticate("app-jwt");
    }

    @Test
    void noToken_leavesUnauthenticated() throws Exception {
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(authenticator, never()).authenticate(any());
    }
}
