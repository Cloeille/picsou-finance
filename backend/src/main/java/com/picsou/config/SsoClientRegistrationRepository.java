package com.picsou.config;

import com.picsou.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * The single OIDC registration ({@value #REGISTRATION_ID}), built from {@link SsoProperties} on the
 * first lookup instead of at startup, so an unreachable provider never blocks boot. A failed
 * discovery is remembered for {@link #RETRY_BACKOFF}; a success is cached for good.
 *
 * <p>Deliberately NOT an {@link Iterable}: Spring Security's {@code OAuth2LoginConfigurer} iterates
 * an Iterable repository while initializing the filter chain, which would run discovery at startup.
 */
@Component
@Conditional(SsoEnabledCondition.class)
public class SsoClientRegistrationRepository implements ClientRegistrationRepository {

    public static final String REGISTRATION_ID = "oidc";

    private static final Logger log = LoggerFactory.getLogger(SsoClientRegistrationRepository.class);
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(60);
    private static final String END_SESSION_ENDPOINT = "end_session_endpoint";

    private final SsoProperties properties;
    private final Function<String, ClientRegistration.Builder> discovery;
    private final Clock clock;
    private final AtomicBoolean discovering = new AtomicBoolean();

    private volatile ClientRegistration registration;
    private volatile Instant lastFailure;

    @org.springframework.beans.factory.annotation.Autowired
    public SsoClientRegistrationRepository(SsoProperties properties, Clock clock) {
        this(properties, ClientRegistrations::fromIssuerLocation, clock);
    }

    SsoClientRegistrationRepository(SsoProperties properties,
                                    Function<String, ClientRegistration.Builder> discovery,
                                    Clock clock) {
        this.properties = properties;
        this.discovery = discovery;
        this.clock = clock;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (!REGISTRATION_ID.equals(registrationId)) {
            return null;
        }
        ClientRegistration cached = registration;
        if (cached != null) {
            return cached;
        }
        // One discovery at a time and outside any lock: a lookup arriving while it runs answers
        // "unavailable" at once instead of holding a servlet worker for the provider's timeout.
        if (!discovering.compareAndSet(false, true)) {
            return null;
        }
        try {
            return discoverUnlessBackingOff();
        } finally {
            discovering.set(false);
        }
    }

    private ClientRegistration discoverUnlessBackingOff() {
        if (registration != null) {
            return registration;
        }
        Instant now = clock.instant();
        if (lastFailure != null && now.isBefore(lastFailure.plus(RETRY_BACKOFF))) {
            return null;
        }
        try {
            registration = build(discovery.apply(properties.issuerUri()));
            lastFailure = null;
            return registration;
        } catch (RuntimeException e) {
            log.warn("SSO discovery failed for issuer {}: {}",
                LogSanitizer.safe(properties.issuerUri()), LogSanitizer.safe(e.getMessage()));
            lastFailure = now;
            return null;
        }
    }

    public boolean isAvailable() {
        return findByRegistrationId(REGISTRATION_ID) != null;
    }

    /** Resolved lazily (same backoff), so a logout right after a restart still finds it. */
    public Optional<String> endSessionEndpoint() {
        ClientRegistration current = findByRegistrationId(REGISTRATION_ID);
        if (current == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(current.getProviderDetails().getConfigurationMetadata().get(END_SESSION_ENDPOINT))
            .map(Object::toString);
    }

    private ClientRegistration build(ClientRegistration.Builder discovered) {
        ClientRegistration.Builder builder = discovered
            .registrationId(REGISTRATION_ID)
            .clientId(properties.clientId())
            .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
            .scope(properties.effectiveScopes())
            .redirectUri("{baseUrl}/login/sso/callback")
            .clientName(properties.providerName());
        if (properties.publicClient()) {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        } else {
            builder.clientSecret(properties.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        }
        return builder.build();
    }
}
