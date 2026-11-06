package com.picsou.config;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class CrossSiteCookieRequestMatcherTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void credentialCookieFlagOnlyChangesCookielessMatching(boolean requireCookie) {
        var matcher = new CrossSiteCookieRequestMatcher(r -> null, requireCookie);
        var request = new MockHttpServletRequest("POST", "/oauth2/sso/authorization/oidc");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        assertThat(matcher.matches(request)).isEqualTo(!requireCookie);
        assertThat(new CrossSiteCookieRequestMatcher(r -> null).matches(request)).isFalse();
        request.setCookies(new Cookie("access_token", "jwt"));
        assertThat(matcher.matches(request)).isTrue();
        request.setMethod("GET");
        assertThat(matcher.matches(request)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void trustedOriginRemainsExempt(boolean requireCookie) {
        var cors = new CorsConfiguration();
        cors.addAllowedOrigin("https://trusted.test");
        var matcher = new CrossSiteCookieRequestMatcher(r -> cors, requireCookie);
        var request = new MockHttpServletRequest("POST", "/oauth2/sso/code/oidc");
        request.setCookies(new Cookie("access_token", "jwt"));
        request.addHeader("Sec-Fetch-Site", "cross-site");
        request.addHeader("Origin", "https://trusted.test");
        assertThat(matcher.matches(request)).isFalse();
    }
}
