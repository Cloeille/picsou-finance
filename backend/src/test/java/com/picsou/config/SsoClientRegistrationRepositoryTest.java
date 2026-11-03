package com.picsou.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SsoClientRegistrationRepositoryTest {

    private static final String ISSUER = "https://idp.example.com/realm";
    private static final String END_SESSION = "https://idp.example.com/realm/logout";

    /** A clock the test moves by hand: no sleeps. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-03T10:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Mock private Function<String, ClientRegistration.Builder> discovery;

    private final MutableClock clock = new MutableClock();
    private final Logger logger = (Logger) LoggerFactory.getLogger(SsoClientRegistrationRepository.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private List<ILoggingEvent> snapshot() {
        synchronized (appender) {
            return new ArrayList<>(appender.list);
        }
    }

    private static SsoProperties props(String secret, String scopes) {
        SsoProperties p = new SsoProperties();
        p.setIssuerUri(ISSUER);
        p.setClientId("picsou");
        p.setClientSecret(secret);
        p.setProviderName("Authentik");
        if (scopes != null) {
            p.setScopes(scopes);
        }
        return p;
    }

    private static ClientRegistration.Builder discovered() {
        return ClientRegistration.withRegistrationId("oidc")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationUri(ISSUER + "/authorize")
            .tokenUri(ISSUER + "/token")
            .jwkSetUri(ISSUER + "/jwks")
            .userInfoUri(ISSUER + "/userinfo")
            .providerConfigurationMetadata(Map.of("end_session_endpoint", END_SESSION));
    }

    private SsoClientRegistrationRepository repository(SsoProperties props) {
        return new SsoClientRegistrationRepository(props, discovery, clock);
    }

    @Test
    void construction_doesNotCallDiscovery() {
        repository(props("", null));

        verifyNoInteractions(discovery);
    }

    @Test
    void success_isCachedAcrossCalls() {
        when(discovery.apply(ISSUER)).thenReturn(discovered());
        SsoClientRegistrationRepository repository = repository(props("", null));

        ClientRegistration first = repository.findByRegistrationId("oidc");
        ClientRegistration second = repository.findByRegistrationId("oidc");

        assertThat(first).isNotNull().isSameAs(second);
        assertThat(repository.isAvailable()).isTrue();
        verify(discovery, times(1)).apply(ISSUER);
    }

    @Test
    void otherRegistrationId_isNullWithoutDiscovery() {
        SsoClientRegistrationRepository repository = repository(props("", null));

        assertThat(repository.findByRegistrationId("github")).isNull();
        assertThat(repository.findByRegistrationId(null)).isNull();
        verifyNoInteractions(discovery);
    }

    @Test
    void failure_backsOffForSixtySecondsThenRetries() {
        when(discovery.apply(anyString()))
            .thenThrow(new IllegalStateException("idp down"))
            .thenReturn(discovered());
        SsoClientRegistrationRepository repository = repository(props("", null));

        assertThat(repository.findByRegistrationId("oidc")).isNull();
        clock.advance(Duration.ofSeconds(59));
        assertThat(repository.findByRegistrationId("oidc")).isNull();
        assertThat(repository.isAvailable()).isFalse();
        verify(discovery, times(1)).apply(ISSUER);

        clock.advance(Duration.ofSeconds(1));
        assertThat(repository.findByRegistrationId("oidc")).isNotNull();
        verify(discovery, times(2)).apply(ISSUER);
    }

    @Test
    void failure_logsOneSanitizedWarning() {
        when(discovery.apply(anyString())).thenThrow(new IllegalStateException("bad\nline"));
        SsoClientRegistrationRepository repository = repository(props("", null));

        repository.findByRegistrationId("oidc");
        repository.findByRegistrationId("oidc");

        List<ILoggingEvent> events = snapshot();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(events.get(0).getFormattedMessage())
            .isEqualTo("SSO discovery failed for issuer " + ISSUER + ": bad?line");
    }

    @Test
    void publicClient_usesNoneAuthenticationAndNoSecret() {
        when(discovery.apply(ISSUER)).thenReturn(discovered());

        ClientRegistration registration = repository(props("", null)).findByRegistrationId("oidc");

        assertThat(registration.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
        assertThat(registration.getClientSecret()).isEmpty();
    }

    @Test
    void confidentialClient_usesClientSecretBasic() {
        when(discovery.apply(ISSUER)).thenReturn(discovered());

        ClientRegistration registration = repository(props("s3cret", null)).findByRegistrationId("oidc");

        assertThat(registration.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(registration.getClientSecret()).isEqualTo("s3cret");
    }

    @Test
    void registration_requiresPkceAndUsesScopesRedirectAndName() {
        when(discovery.apply(ISSUER)).thenReturn(discovered());

        ClientRegistration registration = repository(props("", "profile, email")).findByRegistrationId("oidc");

        assertThat(registration.getRegistrationId()).isEqualTo(SsoClientRegistrationRepository.REGISTRATION_ID);
        assertThat(registration.getClientId()).isEqualTo("picsou");
        assertThat(registration.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
        assertThat(registration.getRedirectUri()).isEqualTo("{baseUrl}/login/sso/callback");
        assertThat(registration.getClientName()).isEqualTo("Authentik");
        assertThat(registration.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
    }

    @Test
    void endSessionEndpoint_onFreshInstance_discoversExactlyOnce() {
        when(discovery.apply(ISSUER)).thenReturn(discovered());
        SsoClientRegistrationRepository repository = repository(props("", null));

        assertThat(repository.endSessionEndpoint()).contains(END_SESSION);
        assertThat(repository.endSessionEndpoint()).contains(END_SESSION);
        verify(discovery, times(1)).apply(ISSUER);
    }

    @Test
    void endSessionEndpoint_isEmptyWhenProviderAdvertisesNone() {
        when(discovery.apply(ISSUER)).thenReturn(discovered().providerConfigurationMetadata(Map.of()));

        assertThat(repository(props("", null)).endSessionEndpoint()).isEqualTo(Optional.empty());
    }

    @Test
    void endSessionEndpoint_isEmptyWhileProviderIsDown() {
        when(discovery.apply(anyString())).thenThrow(new IllegalStateException("idp down"));
        SsoClientRegistrationRepository repository = repository(props("", null));

        assertThat(repository.endSessionEndpoint()).isEmpty();
        assertThat(repository.endSessionEndpoint()).isEmpty();
        verify(discovery, times(1)).apply(ISSUER);
    }

    @Test
    void isNotIterable() {
        // Spring Security's OAuth2LoginConfigurer.getLoginLinks iterates an Iterable repository while
        // the filter chain initializes, which would run OIDC discovery at startup. Discovery must
        // stay lazy, so this repository must never become an Iterable.
        assertThat(repository(props("", null))).isNotInstanceOf(Iterable.class);
        verify(discovery, never()).apply(anyString());
    }
}
