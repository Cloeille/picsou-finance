package com.picsou.config;

import com.picsou.service.SetupService;
import com.picsou.service.ReAuthService;
import com.picsou.model.AppUser;
import com.picsou.model.SetupState;
import com.picsou.repository.AppUserRepository;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
    @Mock JwtTokenAuthenticator authenticator;
    @Mock ReAuthService reAuthService;
    @Mock AppUserRepository userRepository;
    private final SsoProperties ssoProperties = new SsoProperties();
    private final Map<String, Bucket> buckets = new HashMap<>();
    private SsoStartGuardFilter guard;
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/sso/authorization/oidc");

    @BeforeEach
    void createGuard() {
        guard = new SsoStartGuardFilter(repository, setup, buckets, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            authenticator, reAuthService, ssoProperties, userRepository);
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
    @ValueSource(strings = {"unknown"})
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
    @org.junit.jupiter.params.provider.CsvSource({
        "PENDING_ADMIN,true,true", "IN_PROGRESS,true,false", "COMPLETE,true,false", "PENDING_ADMIN,false,false"})
    void setupRequiresPendingAdminAndManagedAdminGroup(SetupState state, boolean managed, boolean accepted) throws Exception {
        when(repository.isAvailable()).thenReturn(true);
        when(setup.currentState()).thenReturn(state);
        ssoProperties.setIssuerUri("https://idp.test");
        ssoProperties.setClientId("picsou");
        if (managed) ssoProperties.setAdminGroup("picsou-admin");
        request.addParameter("intent", "setup");

        guard.doFilter(request, response, chain);

        if (accepted) {
            assertThat(chain.getRequest()).isSameAs(request);
            assertThat(request.getAttribute(SsoIntent.ATTRIBUTE)).isEqualTo(new SsoIntent(
                SsoIntent.Kind.SETUP, "/setup/admin", false, null, null, Instant.EPOCH));
        } else {
            assertThat(response.getRedirectedUrl()).isEqualTo("/setup/admin?ssoError=SETUP_ALREADY_CLAIMED");
            assertThat(chain.getRequest()).isNull();
        }
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

    @ParameterizedTest
    @ValueSource(strings = {"reauth", "link"})
    void signedInIntentRequiresAccessCookie(String kind) throws Exception {
        when(repository.isAvailable()).thenReturn(true);
        request.addParameter("intent", kind);
        guard.doFilter(request, response, chain);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"reauth", "link"})
    void signedInIntentBindsUserAndDefaultsToSettings(String kind) throws Exception {
        var user = AppUser.builder().id(7L).build();
        when(repository.isAvailable()).thenReturn(true);
        when(authenticator.authenticate("access")).thenReturn(Optional.of(
            new UsernamePasswordAuthenticationToken(user, null)));
        if (kind.equals("link")) when(reAuthService.hasValidProof(user, request)).thenReturn(true);
        request.setCookies(new Cookie("access_token", "access"));
        request.addParameter("intent", kind);
        guard.doFilter(request, response, chain);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(SsoIntent.ATTRIBUTE)).isEqualTo(new SsoIntent(
            kind.equals("link") ? SsoIntent.Kind.LINK : SsoIntent.Kind.REAUTH,
            "/settings", false, 7L, null, Instant.EPOCH));
    }

    @Test
    void linkWithoutProofIsRefusedBeforeProvider() throws Exception {
        var user = AppUser.builder().id(7L).build();
        when(repository.isAvailable()).thenReturn(true);
        when(authenticator.authenticate("access")).thenReturn(Optional.of(
            new UsernamePasswordAuthenticationToken(user, null)));
        request.setCookies(new Cookie("access_token", "access"));
        request.addParameter("intent", "link");
        request.addParameter("redirect", "/settings?resume=link");
        guard.doFilter(request, response, chain);
        assertThat(response.getRedirectedUrl()).isEqualTo("/settings?resume=link&ssoError=REAUTH_REQUIRED");
        assertThat(chain.getRequest()).isNull();
    }

    private static AppUser invited(Instant expires) {
        return AppUser.builder().id(9L).activationToken("tok").activationTokenExpires(expires).build();
    }

    @Test
    void activateStoresTheTokenWithoutASignedInUser() throws Exception {
        when(repository.isAvailable()).thenReturn(true);
        when(userRepository.findByActivationToken("tok")).thenReturn(Optional.of(invited(Instant.EPOCH.plusSeconds(60))));
        request.addParameter("intent", "activate");
        request.addParameter("token", "tok");
        request.addParameter("ack", "1");
        request.addParameter("rememberMe", "1");
        guard.doFilter(request, response, chain);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(SsoIntent.ATTRIBUTE)).isEqualTo(new SsoIntent(
            SsoIntent.Kind.ACTIVATE, "/", true, null, "tok", Instant.EPOCH));
        verifyNoInteractions(authenticator);
    }

    @Test
    void activateWithNoExpiryIsAccepted() throws Exception {
        when(repository.isAvailable()).thenReturn(true);
        when(userRepository.findByActivationToken("tok")).thenReturn(Optional.of(invited(null)));
        request.addParameter("intent", "activate");
        request.addParameter("token", "tok");
        request.addParameter("ack", "1");
        guard.doFilter(request, response, chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void activateRefusesAnUnknownToken() throws Exception {
        when(repository.isAvailable()).thenReturn(true);
        when(userRepository.findByActivationToken("tok")).thenReturn(Optional.empty());
        request.addParameter("intent", "activate");
        request.addParameter("token", "tok");
        request.addParameter("ack", "1");
        guard.doFilter(request, response, chain);
        assertThat(response.getRedirectedUrl()).isEqualTo("/activate/tok?ssoError=ACTIVATION_INVALID");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void activateRefusesAnExpiredToken() throws Exception {
        when(repository.isAvailable()).thenReturn(true);
        when(userRepository.findByActivationToken("tok")).thenReturn(Optional.of(invited(Instant.EPOCH.minusSeconds(1))));
        request.addParameter("intent", "activate");
        request.addParameter("token", "tok");
        request.addParameter("ack", "1");
        guard.doFilter(request, response, chain);
        assertThat(response.getRedirectedUrl()).isEqualTo("/activate/tok?ssoError=ACTIVATION_INVALID");
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "true"})
    void activateRefusesWhenTheWarningIsNotAcknowledged(String ack) throws Exception {
        when(repository.isAvailable()).thenReturn(true);
        request.addParameter("intent", "activate");
        request.addParameter("token", "tok");
        if (!ack.isEmpty()) request.addParameter("ack", ack);
        guard.doFilter(request, response, chain);
        assertThat(response.getRedirectedUrl()).isEqualTo("/activate/tok?ssoError=ACTIVATION_INVALID");
        assertThat(chain.getRequest()).isNull();
    }
}
