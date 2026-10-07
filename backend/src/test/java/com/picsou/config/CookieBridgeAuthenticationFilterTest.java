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
 * The cookie bridge populates the SecurityContext for the OAuth2 authorize endpoint from the
 * existing {@code access_token} cookie, and only when nothing else has authenticated the request.
 */
@ExtendWith(MockitoExtension.class)
class CookieBridgeAuthenticationFilterTest {

    @Mock JwtTokenAuthenticator authenticator;

    CookieBridgeAuthenticationFilter filter;
    MockHttpServletRequest request;
    MockHttpServletResponse response;
    MockFilterChain chain;

    private static final Authentication AUTH =
        new UsernamePasswordAuthenticationToken("alice", null, List.of());

    @BeforeEach
    void setUp() {
        filter = new CookieBridgeAuthenticationFilter(authenticator);
        request = new MockHttpServletRequest();
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
        when(authenticator.authenticate("cookie-jwt")).thenReturn(Optional.of(AUTH));

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(AUTH);
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
