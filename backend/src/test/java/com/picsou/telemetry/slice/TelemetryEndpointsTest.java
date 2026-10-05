package com.picsou.telemetry.slice;

import com.picsou.config.AiConfigProvider;
import com.picsou.config.AuthCookieWriter;
import com.picsou.config.EnableBankingConfigProvider;
import com.picsou.config.JwtTokenAuthenticator;
import com.picsou.config.JwtUtil;
import com.picsou.config.SecurityConfig;
import com.picsou.config.SetupFilter;
import com.picsou.controller.AdminController;
import com.picsou.controller.TelemetryController;
import com.picsou.dto.TelemetryConfigResponse;
import com.picsou.mcp.AccessKeyService;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.AiCallLogService;
import com.picsou.service.EnableBankingCallLogger;
import com.picsou.service.EnableBankingKeyPairService;
import com.picsou.service.IntegrationsService;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import com.picsou.service.SetupService;
import com.picsou.telemetry.TelemetryService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Telemetry endpoints through the real {@link SecurityConfig}: authentication, ADMIN-only, tunnel. */
@WebMvcTest(controllers = {TelemetryController.class, AdminController.class})
@Import({SecurityConfig.class, TelemetryController.class, AdminController.class})
class TelemetryEndpointsTest {

    @MockitoBean TelemetryService telemetry;

    // AdminController collaborators
    @MockitoBean SetupService setupService;
    @MockitoBean IntegrationsService integrationsService;
    @MockitoBean EnableBankingConfigProvider ebConfigProvider;
    @MockitoBean EnableBankingKeyPairService keyPairService;
    @MockitoBean AiConfigProvider aiConfigProvider;
    @MockitoBean AiCallLogService aiCallLogService;
    @MockitoBean EnableBankingCallLogger ebCallLogger;

    // SecurityConfig collaborators (same set as the CSRF slice)
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

    private static MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder b) {
        return b.cookie(new Cookie("access_token", "jwt")).with(user("member").roles("USER"));
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder b) {
        return b.cookie(new Cookie("access_token", "jwt")).with(user("admin").roles("ADMIN"));
    }

    // ---- GET /api/telemetry/config -----------------------------------------------------------

    @Test
    void config_requiresAuthentication() throws Exception {
        mvc.perform(get("/api/telemetry/config")).andExpect(status().isUnauthorized());
    }

    @Test
    void config_disabled_returnsNullDsn() throws Exception {
        when(telemetry.config()).thenReturn(new TelemetryConfigResponse(false, null, "production", "1.2.3"));

        mvc.perform(asMember(get("/api/telemetry/config")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.dsn").value((Object) null))
            .andExpect(jsonPath("$.environment").value("production"))
            .andExpect(jsonPath("$.release").value("1.2.3"));
    }

    @Test
    void config_enabled_returnsDsn() throws Exception {
        when(telemetry.config()).thenReturn(
            new TelemetryConfigResponse(true, "https://k@glitch.example.org/1", "production", "1.2.3"));

        mvc.perform(asMember(get("/api/telemetry/config")))
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.dsn").value("https://k@glitch.example.org/1"));
    }

    // ---- POST /api/telemetry/tunnel ----------------------------------------------------------

    @Test
    void tunnel_requiresAuthentication() throws Exception {
        mvc.perform(post("/api/telemetry/tunnel").contentType(MediaType.TEXT_PLAIN).content("{}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(telemetry);
    }

    @Test
    void tunnel_notEnabled_answers204_andForwardsNothing() throws Exception {
        when(telemetry.isEnabled()).thenReturn(false);

        mvc.perform(asMember(post("/api/telemetry/tunnel")
                .header("Sec-Fetch-Site", "same-origin")
                .contentType("text/plain;charset=UTF-8").content("{}\n{\"type\":\"event\"}\n{}")))
            .andExpect(status().isNoContent());

        verify(telemetry, never()).tunnel(any());
    }

    @Test
    void tunnel_enabled_sameOrigin_passesBodyToService() throws Exception {
        when(telemetry.isEnabled()).thenReturn(true);

        mvc.perform(asMember(post("/api/telemetry/tunnel")
                .header("Sec-Fetch-Site", "same-origin")
                .contentType("text/plain;charset=UTF-8").content("envelope-bytes")))
            .andExpect(status().isNoContent());

        verify(telemetry).tunnel("envelope-bytes".getBytes());
    }

    @Test
    void tunnel_acceptsSentryEnvelopeContentType() throws Exception {
        when(telemetry.isEnabled()).thenReturn(true);

        mvc.perform(asMember(post("/api/telemetry/tunnel")
                .contentType("application/x-sentry-envelope").content("x")))
            .andExpect(status().isNoContent());
    }

    @Test
    void tunnel_crossSite_isRejectedByCsrfCheck() throws Exception {
        mvc.perform(asMember(post("/api/telemetry/tunnel")
                .header("Sec-Fetch-Site", "cross-site")
                .contentType(MediaType.TEXT_PLAIN).content("x")))
            .andExpect(status().isForbidden());
        verify(telemetry, never()).tunnel(any());
    }

    @Test
    void tunnel_over200KB_is413() throws Exception {
        when(telemetry.isEnabled()).thenReturn(true);

        mvc.perform(asMember(post("/api/telemetry/tunnel")
                .contentType(MediaType.TEXT_PLAIN).content("x".repeat(200 * 1024 + 1))))
            .andExpect(status().isPayloadTooLarge());

        verify(telemetry, never()).tunnel(any());
    }

    @Test
    void tunnel_exactly200KB_isAccepted() throws Exception {
        when(telemetry.isEnabled()).thenReturn(true);

        mvc.perform(asMember(post("/api/telemetry/tunnel")
                .contentType(MediaType.TEXT_PLAIN).content("x".repeat(200 * 1024))))
            .andExpect(status().isNoContent());
    }

    // ---- /api/admin/settings(/telemetry) ------------------------------------------------------

    @Test
    void adminSettings_includesTelemetryBlock() throws Exception {
        when(telemetry.isAvailable()).thenReturn(true);
        when(telemetry.consent()).thenReturn(TelemetryService.Consent.UNSET);

        mvc.perform(asAdmin(get("/api/admin/settings")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.telemetry.available").value(true))
            .andExpect(jsonPath("$.telemetry.consent").value("UNSET"));
    }

    @Test
    void putTelemetry_admin_togglesAndReturns204() throws Exception {
        mvc.perform(asAdmin(put("/api/admin/settings/telemetry")
                .header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")))
            .andExpect(status().isNoContent());

        verify(telemetry).setEnabled(true);
    }

    @Test
    void putTelemetry_member_isForbidden() throws Exception {
        mvc.perform(asMember(put("/api/admin/settings/telemetry")
                .header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")))
            .andExpect(status().isForbidden());

        verify(telemetry, never()).setEnabled(org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void putTelemetry_anonymous_isUnauthorized() throws Exception {
        mvc.perform(put("/api/admin/settings/telemetry")
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void putTelemetry_missingEnabled_isRejected() throws Exception {
        mvc.perform(asAdmin(put("/api/admin/settings/telemetry")
                .header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON).content("{}")))
            .andExpect(status().is4xxClientError());

        verify(telemetry, never()).setEnabled(org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void putTelemetry_noDsnConfigured_isConflict() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("Telemetry is not configured on this instance"))
            .when(telemetry).setEnabled(true);

        mvc.perform(asAdmin(put("/api/admin/settings/telemetry")
                .header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")))
            .andExpect(status().isConflict());
    }
}
