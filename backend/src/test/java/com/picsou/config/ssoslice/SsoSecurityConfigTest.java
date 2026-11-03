package com.picsou.config.ssoslice;

import com.picsou.config.*;
import com.picsou.mcp.AccessKeyService;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import com.picsou.service.SetupService;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.test.context.ContextConfiguration(classes = SsoSecurityConfigTest.SliceApplication.class)
class SsoSecurityConfigTest {
    @Test
    void realRegistrationComponentCanStartWithoutDiscovery() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withPropertyValues("app.sso.issuer-uri=http://127.0.0.1:9/default", "app.sso.client-id=picsou")
            .withBean(SsoProperties.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(SsoClientRegistrationRepository.class)
            .run(context -> assertThat(context).hasSingleBean(SsoClientRegistrationRepository.class));
    }

    @org.springframework.context.annotation.Configuration
    static class SliceApplication {}
    @RestController
    static class ProbeController {
        @GetMapping("/api/setup/sso-probe")
        String probe() { return "ok"; }
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean Clock clock() { return Clock.systemUTC(); }
    }

    abstract static class ApiFixture {
        @MockitoBean JwtUtil jwtUtil;
        @MockitoBean JwtTokenAuthenticator jwtTokenAuthenticator;
        @MockitoBean AppUserRepository appUserRepository;
        @MockitoBean SetupFilter setupFilter;
        @MockitoBean PersistentSessionService persistentSessionService;
        @MockitoBean AuthCookieWriter authCookieWriter;
        @MockitoBean MfaService mfaService;
        @MockitoBean AccessKeyService accessKeyService;
        @MockitoBean AppSettingRepository appSettingRepository;
        @MockitoBean @Qualifier("mcpKeyBuckets") Map<Long, Bucket> mcpKeyBuckets;
        @Autowired MockMvc mvc;
        @Autowired ApplicationContext context;

        @BeforeEach
        void passThroughSetup() throws Exception {
            doAnswer(inv -> {
                ServletRequest request = inv.getArgument(0);
                ServletResponse response = inv.getArgument(1);
                FilterChain chain = inv.getArgument(2);
                chain.doFilter(request, response);
                return null;
            }).when(setupFilter).doFilter(any(), any(), any());
        }
    }

    abstract static class SsoFixture extends ApiFixture {
        @MockitoBean SsoClientRegistrationRepository repository;
        @MockitoBean SetupService setupService;
        @MockitoBean com.picsou.service.sso.SsoAccountService ssoAccountService;
        @MockitoBean com.picsou.service.sso.SsoRoleSyncService ssoRoleSyncService;
        @MockitoBean com.picsou.service.SessionIssuer sessionIssuer;
        @MockitoBean SsoProperties ssoProperties;
        @MockitoBean com.picsou.service.ReAuthService reAuthService;
        @MockitoBean com.picsou.service.SetupAuditService auditService;

        @BeforeEach
        void provider() {
            when(repository.isAvailable()).thenReturn(true);
            when(setupService.isComplete()).thenReturn(true);
            when(repository.findByRegistrationId("oidc")).thenReturn(ClientRegistration.withRegistrationId("oidc")
                .clientId("picsou").clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).scope("openid", "profile")
                .authorizationUri("https://idp.test/authorize").tokenUri("https://idp.test/token")
                .redirectUri("{baseUrl}/login/sso/callback")
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build()).build());
        }
    }

    @Nested
    @WebMvcTest(controllers = ProbeController.class)
    @Import({SecurityConfig.class, SsoSecurityConfig.class, RateLimitConfig.class, TimeConfig.class,
        SsoAuthenticationSuccessHandler.class, SsoAuthenticationFailureHandler.class})
    @TestPropertySource(properties = {"app.sso.issuer-uri=https://idp.test", "app.sso.client-id=picsou",
        "app.cors.allowed-origins=https://trusted.test", "app.hsts-enabled=false"})
    class Enabled extends SsoFixture {
        @Test
        void sameOriginStartRedirectsToProviderAndKeepsOnlyHandshakeInSession() throws Exception {
            mvc.perform(post("/oauth2/sso/authorization/oidc").header("Origin", "http://localhost"))
                .andExpect(status().isFound())
                .andExpect(result -> {
                    var query = UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl()).build().getQueryParams();
                    assertThat(result.getResponse().getRedirectedUrl()).startsWith("https://idp.test/authorize?");
                    assertThat(org.springframework.web.util.UriUtils.decode(query.getFirst("redirect_uri"), java.nio.charset.StandardCharsets.UTF_8))
                        .isEqualTo("http://localhost/login/sso/callback");
                    var session = result.getRequest().getSession(false);
                    assertThat(session).isNotNull();
                    assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
                    assertThat(session.getAttribute("SPRING_SECURITY_SAVED_REQUEST")).isNull();
                });
        }

        @ParameterizedTest
        @ValueSource(strings = {"/oauth2/sso/authorization/oidc", "/oauth2/sso/code/oidc"})
        void getIsRefusedBeforeOauth(String path) throws Exception {
            mvc.perform(get(path)).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"/oauth2/sso/authorization/oidc", "/oauth2/sso/code/oidc"})
        void crossSitePostWithoutCookiesGetsProblem403(String path) throws Exception {
            mvc.perform(post(path).header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Cross-site request rejected"));
        }

        @Test
        void untrustedOriginFailsCsrf() throws Exception {
            mvc.perform(post("/oauth2/sso/authorization/oidc").header("Origin", "https://evil.test"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Cross-site request rejected"));
        }

        @Test
        void allowListedOriginPassesCsrf() throws Exception {
            mvc.perform(post("/oauth2/sso/authorization/oidc").header("Origin", "https://trusted.test")
                    .header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isFound()).andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("https://idp.test/authorize?")));
        }

        @Test
        void httpsStartOmitsHstsWhenDisabled() throws Exception {
            mvc.perform(post("/oauth2/sso/authorization/oidc").secure(true))
                .andExpect(status().isFound()).andExpect(header().doesNotExist("Strict-Transport-Security"));
        }

        @Test
        void metadataRouteIsPermittedBeforeItsHandlerExists() throws Exception {
            mvc.perform(get("/api/auth/sso")).andExpect(status().isNotFound());
        }

        @Test
        void callbackWithNoHandshakeExpiresWithoutCreatingSession() throws Exception {
            mvc.perform(post("/oauth2/sso/code/oidc").param("code", "code").param("state", "state"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?ssoError=SESSION_EXPIRED"))
                .andExpect(r -> assertThat(r.getRequest().getSession(false)).isNull());
        }
    }

    @Nested
    @WebMvcTest(controllers = ProbeController.class)
    @Import({SecurityConfig.class, SsoSecurityConfig.class, RateLimitConfig.class, TimeConfig.class,
        SsoAuthenticationSuccessHandler.class, SsoAuthenticationFailureHandler.class})
    @TestPropertySource(properties = {"app.sso.issuer-uri=https://idp.test", "app.sso.client-id=picsou", "app.hsts-enabled=true"})
    class HstsEnabled extends SsoFixture {
        @Test
        void httpsStartEmitsHstsWhenEnabled() throws Exception {
            mvc.perform(post("/oauth2/sso/authorization/oidc").secure(true))
                .andExpect(status().isFound()).andExpect(header().string("Strict-Transport-Security",
                    org.hamcrest.Matchers.containsString("max-age=31536000")));
        }
    }

    @Nested
    @WebMvcTest(controllers = ProbeController.class)
    @Import({SecurityConfig.class, SsoSecurityConfig.class, SsoProperties.class, TimeConfig.class})
    @TestPropertySource(properties = "app.sso.issuer-uri=")
    class Disabled extends ApiFixture {
        @Test
        void noSsoChainOrRegistrationAndApi401() throws Exception {
            assertThat(context.getBeansOfType(ClientRegistrationRepository.class)).isEmpty();
            assertThat(context.containsBean("ssoSecurityFilterChain")).isFalse();
            mvc.perform(post("/oauth2/sso/authorization/oidc"))
                .andExpect(status().isUnauthorized()).andExpect(content().contentType("application/problem+json"));
        }
    }
}
