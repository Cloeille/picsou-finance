package com.picsou.controller;

import com.picsou.service.LouveSyncService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** The redirect URI Picsou registers with Louve is built from an origin it can trust. */
class LouveControllerTest {

    private static LouveController controller(String... allowedOrigins) {
        CorsConfigurationSource cors = request -> {
            if (allowedOrigins.length == 0) return null;
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(List.of(allowedOrigins));
            return config;
        };
        return new LouveController(mock(LouveSyncService.class), mock(UserContext.class), cors,
            new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }

    private static MockHttpServletRequest request(String scheme, String host, int port) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/louve/auth/start");
        request.setScheme(scheme);
        request.setServerName(host);
        request.setServerPort(port);
        return request;
    }

    @Test
    void aSameOriginBrowserCallUsesItsOrigin() {
        MockHttpServletRequest request = request("http", "app", 8080);
        request.addHeader("Origin", "https://picsou.example");
        request.addHeader("Sec-Fetch-Site", "same-origin");

        assertThat(controller().redirectUri(request)).isEqualTo("https://picsou.example/sync/louve/callback");
    }

    @Test
    void anOriginMatchingTheRequestOrTheAllowListIsAccepted() {
        MockHttpServletRequest same = request("https", "picsou.example", 8443);
        same.addHeader("Origin", "https://picsou.example:8443");
        assertThat(controller().redirectUri(same)).isEqualTo("https://picsou.example:8443/sync/louve/callback");

        MockHttpServletRequest listed = request("http", "app", 8080);
        listed.addHeader("Origin", "https://money.example");
        assertThat(controller("https://money.example").redirectUri(listed))
            .isEqualTo("https://money.example/sync/louve/callback");
    }

    @Test
    void anUntrustedOriginIsRefused() {
        MockHttpServletRequest request = request("http", "app", 8080);
        request.addHeader("Origin", "https://evil.example");
        request.addHeader("Sec-Fetch-Site", "cross-site");

        assertThatThrownBy(() -> controller("https://money.example").redirectUri(request))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plainHttpIsOnlyAcceptedOnLoopback() {
        MockHttpServletRequest loopback = request("http", "127.0.0.1", 5173);
        loopback.addHeader("Origin", "http://127.0.0.1:5173");
        assertThat(controller().redirectUri(loopback)).isEqualTo("http://127.0.0.1:5173/sync/louve/callback");

        MockHttpServletRequest lan = request("http", "192.168.1.10", 8080);
        lan.addHeader("Origin", "http://192.168.1.10:8080");
        assertThatThrownBy(() -> controller().redirectUri(lan)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withoutAnOriginTheRequestItselfIsUsed() {
        assertThat(controller().redirectUri(request("https", "picsou.example", 443)))
            .isEqualTo("https://picsou.example/sync/louve/callback");
    }
}
