package com.picsou.config;

import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import com.picsou.service.SetupService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * CORS regression through native Tomcat forwarding, including HTTPS behind a trusted proxy,
 * with the valves built from the production properties. Untrusted-peer behavior is covered by
 * {@link TrustedProxyClientIpTest}.
 */
@ExtendWith(MockitoExtension.class)
class ForwardedHeadersCorsTest {

    private static final String PUBLIC_HOST = "picsou.example.com";
    private static final String HTTPS_ORIGIN = "https://" + PUBLIC_HOST;

    @Mock AppSettingRepository settingRepository;

    /** Allow-list holds only the HTTP origin — what the wizard stores during an HTTP setup. */
    private void allowListHasOnlyHttpOrigin() {
        AppSetting stored = AppSetting.builder()
            .key(SetupService.KEY_CORS_ALLOWED_ORIGINS)
            .value("http://" + PUBLIC_HOST)
            .build();
        when(settingRepository.findByKey(SetupService.KEY_CORS_ALLOWED_ORIGINS))
            .thenReturn(Optional.of(stored));
    }

    @Test
    void withForwardedProtoHttps_sameOriginRequestIsNotRejected() throws Exception {
        allowListHasOnlyHttpOrigin();

        // The proxy forwarded the real scheme — the fix relies on the backend honoring it.
        MockHttpServletResponse response = process(postBehindProxy("https"));

        // Recognized as same-origin -> CORS skipped -> reaches the controller (here: not 403).
        assertThat(response.getStatus()).isNotEqualTo(403);
    }

    @Test
    void withoutForwardedProto_sameOriginRequestIsWronglyRejected() throws Exception {
        allowListHasOnlyHttpOrigin();

        // Reproduces the bug: no X-Forwarded-Proto -> backend sees http -> scheme mismatch.
        MockHttpServletResponse response = process(postBehindProxy(null));

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void genuineCrossOriginRequestIsStillRejected() throws Exception {
        allowListHasOnlyHttpOrigin();

        MockHttpServletRequest req = postBehindProxy("https");
        req.removeHeader("Origin");
        req.addHeader("Origin", "https://evil.example.com"); // different host -> truly cross-origin

        assertThat(process(req).getStatus()).isEqualTo(403);
    }

    /**
     * Build the request as it reaches Tomcat from the trusted proxy: a plain-HTTP connection, the public Host header, the browser's HTTPS Origin, and (optionally) the
     * forwarded scheme. No X-Forwarded-Port is sent — the backend derives 443 from the scheme.
     */
    private MockHttpServletRequest postBehindProxy(String forwardedProto) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod("POST");
        req.setRequestURI("/api/setup/security");
        req.setScheme("http");
        req.setServerName("127.0.0.1");
        req.setServerPort(9090);
        req.addHeader("Host", PUBLIC_HOST);
        req.addHeader("X-Forwarded-Host", PUBLIC_HOST);
        req.addHeader("Origin", HTTPS_ORIGIN);
        if (forwardedProto != null) {
            req.addHeader("X-Forwarded-Proto", forwardedProto);
        }
        return req;
    }

    /** Native container processing precedes the application's CORS processing. */
    private MockHttpServletResponse process(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, String> headers = new LinkedHashMap<>();
        req.getHeaderNames().asIterator().forEachRemaining(name -> headers.put(name, req.getHeader(name)));
        new ProductionTomcatEdge(ProductionTomcatEdge.PROXY_IP).handle(ProductionTomcatEdge.PROXY_IP, headers, request -> {
            HttpServletRequest effective = request.getRequest();
            DynamicCorsConfigurationSource source =
                new DynamicCorsConfigurationSource(settingRepository, "");
            CorsConfiguration config = source.getCorsConfiguration(effective);
            try {
                new LoggingCorsProcessor().processRequest(config, effective, response);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        return response;
    }
}
