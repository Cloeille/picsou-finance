package com.picsou.config;

import com.picsou.service.SetupService;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.ReAuthService;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@Conditional(SsoEnabledCondition.class)
public class SsoSecurityConfig {
    @Value("${app.hsts-enabled:false}")
    private String hstsEnabledRaw;

    @Bean
    @Order(0)
    public SecurityFilterChain ssoSecurityFilterChain(HttpSecurity http, SsoClientRegistrationRepository repository,
            SetupService setupService, @Qualifier("ssoStartBuckets") Map<String, Bucket> buckets, Clock clock,
            CorsConfigurationSource corsConfigurationSource, SsoAuthenticationSuccessHandler successHandler,
            SsoAuthenticationFailureHandler failureHandler, JwtTokenAuthenticator authenticator,
            ReAuthService reAuthService, AppUserRepository userRepository) throws Exception {
        var resolver = new SsoAuthorizationRequestResolver(repository);
        var requestRepository = new SsoAuthorizationRequestRepository();
        var guard = new SsoStartGuardFilter(repository, setupService, buckets, clock, authenticator, reAuthService, userRepository);
        var oidcUserService = new OidcUserService();
        oidcUserService.setRetrieveUserInfo(r -> StringUtils.hasText(
            r.getClientRegistration().getProviderDetails().getUserInfoEndpoint().getUri()));
        http.securityMatcher("/oauth2/sso/**")
            .authorizeHttpRequests(a -> a.anyRequest().permitAll())
            .oauth2Login(o -> o.loginPage("/login")
                .clientRegistrationRepository(repository)
                .authorizedClientRepository(new DiscardingOAuth2AuthorizedClientRepository())
                .authorizationEndpoint(a -> a.baseUri("/oauth2/sso/authorization")
                    .authorizationRequestResolver(resolver).authorizationRequestRepository(requestRepository))
                .redirectionEndpoint(r -> r.baseUri("/oauth2/sso/code/*"))
                .userInfoEndpoint(u -> u.oidcUserService(oidcUserService))
                .successHandler(successHandler).failureHandler(failureHandler))
            .securityContext(c -> c.securityContextRepository(new RequestAttributeSecurityContextRepository()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .requestCache(c -> c.requestCache(new NullRequestCache()))
            .csrf(c -> c.requireCsrfProtectionMatcher(new CrossSiteCookieRequestMatcher(corsConfigurationSource, false))
                .csrfTokenRepository(new SecurityConfig.NoStoredCsrfTokenRepository()))
            .exceptionHandling(e -> e.accessDeniedHandler(SecurityConfig.crossSiteAwareAccessDeniedHandler()))
            .headers(h -> h.frameOptions(f -> f.deny()).contentTypeOptions(c -> {})
                .httpStrictTransportSecurity(hsts -> {
                    if (SecurityConfig.parseHstsEnabled(hstsEnabledRaw)) {
                        hsts.maxAgeInSeconds(31536000).includeSubDomains(true);
                    } else {
                        hsts.disable();
                    }
                })
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
            .addFilterBefore(guard, OAuth2AuthorizationRequestRedirectFilter.class);
        return http.build();
    }

    static final class DiscardingOAuth2AuthorizedClientRepository implements OAuth2AuthorizedClientRepository {
        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String registrationId,
                Authentication principal, HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {}

        @Override
        public void removeAuthorizedClient(String registrationId, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {}
    }
}
