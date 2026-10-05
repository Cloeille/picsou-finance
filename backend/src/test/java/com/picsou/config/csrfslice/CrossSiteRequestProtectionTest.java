package com.picsou.config.csrfslice;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.JwtTokenAuthenticator;
import com.picsou.config.JwtUtil;
import com.picsou.config.RateLimitConfig;
import com.picsou.config.SecurityConfig;
import com.picsou.config.SetupFilter;
import com.picsou.mcp.AccessKeyService;
import com.picsou.mcp.AccessKeyService.ResolvedKey;
import com.picsou.model.AppSetting;
import com.picsou.model.AppUser;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import com.picsou.service.SetupService;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-site request protection on the API chain, through the real {@link SecurityConfig}.
 * MockMvc requests default to {@code http://localhost:80}, which is the "own origin" below.
 */
@WebMvcTest(controllers = CrossSiteRequestProtectionTest.ProbeController.class)
@Import({SecurityConfig.class, CrossSiteRequestProtectionTest.ProbeController.class})
class CrossSiteRequestProtectionTest {

    @RestController
    static class ProbeController {
        @PostMapping("/api/probe")
        String post() {
            return "done";
        }

        @GetMapping("/api/probe")
        String get() {
            return "read";
        }

        @PostMapping("/api/auth/refresh")
        String refresh() {
            return "refreshed";
        }

        @PostMapping("/api/auth/login")
        String login() {
            return "logged-in";
        }

        @PostMapping("/mcp")
        String mcp() {
            return "mcp";
        }
    }

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

    @BeforeEach
    void passThroughSetupFilter() throws Exception {
        doAnswer(inv -> {
            ServletRequest req = inv.getArgument(0);
            ServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(setupFilter).doFilter(any(), any(), any());
    }

    private static MockHttpServletRequestBuilder cookiePost(String path) {
        return post(path).cookie(new Cookie("access_token", "jwt")).with(user("member"));
    }

    @Test
    void secFetchSiteCrossSite_isRejectedWithProblemJson() throws Exception {
        mvc.perform(cookiePost("/api/probe").header("Sec-Fetch-Site", "cross-site"))
            .andExpect(status().isForbidden())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.detail").value("Cross-site request rejected"));
    }

    @Test
    void secFetchSiteSameSite_isRejected() throws Exception {
        mvc.perform(cookiePost("/api/probe").header("Sec-Fetch-Site", "same-site"))
            .andExpect(status().isForbidden());
    }

    @Test
    void secFetchSiteSameOrigin_passes() throws Exception {
        mvc.perform(cookiePost("/api/probe").header("Sec-Fetch-Site", "same-origin"))
            .andExpect(status().isOk())
            .andExpect(content().string("done"));
    }

    @Test
    void secFetchSiteNone_passes() throws Exception {
        mvc.perform(cookiePost("/api/probe").header("Sec-Fetch-Site", "none"))
            .andExpect(status().isOk());
    }

    @Test
    void secFetchSiteWins_overAMatchingOrigin() throws Exception {
        mvc.perform(cookiePost("/api/probe")
                .header("Sec-Fetch-Site", "same-site")
                .header("Origin", "http://localhost"))
            .andExpect(status().isForbidden());
    }

    @Test
    void noSecFetchSite_foreignOrigin_isRejected() throws Exception {
        mvc.perform(cookiePost("/api/probe").header("Origin", "http://evil.localhost"))
            .andExpect(status().isForbidden());
    }

    @Test
    void noSecFetchSite_opaqueNullOrigin_isRejected() throws Exception {
        mvc.perform(cookiePost("/api/probe").header("Origin", "null"))
            .andExpect(status().isForbidden());
    }

    @Test
    void noSecFetchSite_originWithAnotherPort_isRejected() throws Exception {
        mvc.perform(cookiePost("/api/probe").header("Origin", "http://localhost:8081"))
            .andExpect(status().isForbidden());
    }

    @Test
    void noSecFetchSite_matchingOrigin_passes() throws Exception {
        mvc.perform(cookiePost("/api/probe").header("Origin", "http://localhost"))
            .andExpect(status().isOk());
    }

    @Test
    void noSecFetchSite_matchingOrigin_behindTlsProxy_passes() throws Exception {
        // What ForwardedHeaderFilter leaves on the request for X-Forwarded-Proto: https.
        mvc.perform(cookiePost("/api/probe")
                .secure(true)
                .with(r -> {
                    r.setScheme("https");
                    r.setServerName("picsou.example.com");
                    r.setServerPort(443);
                    return r;
                })
                .header("Origin", "https://picsou.example.com"))
            .andExpect(status().isOk());
    }

    @Test
    void noOrigin_foreignReferer_isRejected() throws Exception {
        mvc.perform(cookiePost("/api/probe").header("Referer", "http://evil.localhost/page"))
            .andExpect(status().isForbidden());
    }

    @Test
    void noOrigin_sameOriginReferer_passes() throws Exception {
        mvc.perform(cookiePost("/api/probe").header("Referer", "http://localhost/sync/callback?code=x"))
            .andExpect(status().isOk());
    }

    @Test
    void noFetchMetadataNorOriginNorReferer_nonBrowserClient_passes() throws Exception {
        mvc.perform(cookiePost("/api/probe"))
            .andExpect(status().isOk());
    }

    @Test
    void getIsNeverChecked() throws Exception {
        mvc.perform(get("/api/probe")
                .cookie(new Cookie("access_token", "jwt"))
                .with(user("member"))
                .header("Sec-Fetch-Site", "cross-site"))
            .andExpect(status().isOk())
            .andExpect(content().string("read"));
    }

    @Test
    void crossSitePostWithoutAuthCookie_isNotACsrfTarget() throws Exception {
        mvc.perform(post("/api/probe").with(user("member")).header("Sec-Fetch-Site", "cross-site"))
            .andExpect(status().isOk());
    }

    @Test
    void bearerAuthenticatedRequest_isNotChecked() throws Exception {
        mvc.perform(post("/api/probe")
                .with(user("member"))
                .header("Authorization", "Bearer native-app-jwt")
                .header("Sec-Fetch-Site", "cross-site")
                .header("Origin", "http://evil.localhost"))
            .andExpect(status().isOk());
    }

    @Test
    void mcpAccessKeyRequest_isNotChecked() throws Exception {
        when(accessKeyService.validate("psk_test"))
            .thenReturn(Optional.of(new ResolvedKey(mock(AppUser.class), Set.of("read:accounts"), 7L)));
        when(mcpKeyBuckets.computeIfAbsent(anyLong(), any())).thenReturn(RateLimitConfig.createMcpKeyBucket());

        mvc.perform(post("/mcp")
                .cookie(new Cookie("access_token", "jwt"))
                .header("Authorization", "Bearer psk_test")
                .header("Sec-Fetch-Site", "cross-site"))
            .andExpect(status().isOk())
            .andExpect(content().string("mcp"));
    }

    @Test
    void refreshFromTheSpa_passes() throws Exception {
        mvc.perform(post("/api/auth/refresh")
                .cookie(new Cookie("refresh_token", "r"))
                .header("Sec-Fetch-Site", "same-origin")
                .header("Origin", "http://localhost"))
            .andExpect(status().isOk())
            .andExpect(content().string("refreshed"));
    }

    @Test
    void refreshDrivenFromAnotherSite_isRejected() throws Exception {
        mvc.perform(post("/api/auth/refresh")
                .cookie(new Cookie("refresh_token", "r"))
                .header("Sec-Fetch-Site", "same-site"))
            .andExpect(status().isForbidden());
    }

    @Test
    void loginFromTheSpa_withoutCookies_passes() throws Exception {
        mvc.perform(post("/api/auth/login")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Origin", "http://localhost"))
            .andExpect(status().isOk())
            .andExpect(content().string("logged-in"));
    }

    @Test
    void allowListedCorsOrigin_isTrusted() throws Exception {
        when(appSettingRepository.findByKey(eq(SetupService.KEY_CORS_ALLOWED_ORIGINS)))
            .thenReturn(Optional.of(AppSetting.builder()
                .key(SetupService.KEY_CORS_ALLOWED_ORIGINS)
                .value("http://app.localhost")
                .build()));

        mvc.perform(cookiePost("/api/probe")
                .header("Sec-Fetch-Site", "same-site")
                .header("Origin", "http://app.localhost"))
            .andExpect(status().isOk());
    }

    @Test
    void rejectedRequest_createsNoSessionAndSetsNoCookie() throws Exception {
        mvc.perform(post("/api/probe").cookie(new Cookie("access_token", "jwt")).header("Sec-Fetch-Site", "cross-site"))
            .andExpect(status().isForbidden())
            .andExpect(r -> org.assertj.core.api.Assertions.assertThat(r.getRequest().getSession(false)).isNull())
            .andExpect(r -> org.assertj.core.api.Assertions.assertThat(r.getResponse().getHeaders("Set-Cookie")).isEmpty());
    }
}
