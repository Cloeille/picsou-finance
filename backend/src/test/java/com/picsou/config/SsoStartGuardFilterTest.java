package com.picsou.config;

import com.picsou.service.SetupService;
import com.picsou.service.sso.SsoIntent;
import io.github.bucket4j.Bucket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SsoStartGuardFilterTest {
    @Mock SsoClientRegistrationRepository repository;
    @Mock SetupService setup;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private SsoStartGuardFilter guard;
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/sso/authorization/oidc");

    @BeforeEach
    void createGuard() {
        guard = new SsoStartGuardFilter(repository, setup, buckets, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        request.setRemoteAddr("192.0.2.1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/oauth2/sso/authorization/oidc", "/oauth2/sso/code/oidc"})
    void getNeverReachesProvider(String path) throws Exception {
        request.setMethod("GET");
        request.setRequestURI(path);
        guard.doFilter(request, response, chain);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
        assertThat(chain.getRequest()).isNull();
        verifyNoInteractions(repository, setup);
    }

    @Test
    void callbackPostPassesWithoutStartingAnIntent() throws Exception {
        request.setRequestURI("/oauth2/sso/code/oidc");
        guard.doFilter(request, response, chain);
        assertThat(chain.getRequest()).isSameAs(request);
        verifyNoInteractions(repository, setup);
    }

    @Test
    void unrelatedPostIsRefused() throws Exception {
        request.setRequestURI("/oauth2/sso/other");
        guard.doFilter(request, response, chain);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
    }

    @Test
    void exhaustedIpBucketRefusesBeforeDiscovery() throws Exception {
        Bucket bucket = RateLimitConfig.createSsoStartBucket();
        assertThat(bucket.tryConsume(30)).isTrue();
        buckets.put("192.0.2.1", bucket);
        request.addHeader("X-Forwarded-For", "192.0.2.2");
        guard.doFilter(request, response, chain);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?ssoError=RATE_LIMITED");
        verifyNoInteractions(repository, setup);
    }

    @Test
    void incompleteSetupRedirectsToWizard() throws Exception {
        when(repository.isAvailable()).thenReturn(true);
        guard.doFilter(request, response, chain);
        assertThat(response.getRedirectedUrl()).isEqualTo("/setup");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void unavailableProviderRefusesLogin() throws Exception {
        guard.doFilter(request, response, chain);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?ssoError=IDP_UNAVAILABLE");
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "link", "reauth", "activate", "setup"})
    void unsupportedIntentFailsClosed(String intent) throws Exception {
        request.addParameter("intent", intent);
        if (!intent.equals("unknown")) {
            when(repository.isAvailable()).thenReturn(true);
        }
        guard.doFilter(request, response, chain);
        assertThat(response.getRedirectedUrl()).contains("ssoError=IDP_ERROR");
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/accounts", "//evil.test"})
    void loginCarriesSanitizedIntentAndInjectedTime(String redirect) throws Exception {
        when(repository.isAvailable()).thenReturn(true);
        when(setup.isComplete()).thenReturn(true);
        request.addParameter("redirect", redirect);
        request.addParameter("rememberMe", "1");
        guard.doFilter(request, response, chain);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(SsoIntent.ATTRIBUTE)).isEqualTo(new SsoIntent(
            SsoIntent.Kind.LOGIN, redirect.equals("/accounts") ? "/accounts" : "/", true, null, null, Instant.EPOCH));
        assertThat(request.getSession(false)).isNull();
    }
}
