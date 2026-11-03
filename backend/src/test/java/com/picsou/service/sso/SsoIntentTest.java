package com.picsou.service.sso;

import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class SsoIntentTest {
    static Stream<Arguments> errorTargets() {
        return Stream.of(
            Arguments.of(SsoIntent.Kind.LOGIN, "/", null, "/login?ssoError=IDP_ERROR"),
            Arguments.of(SsoIntent.Kind.LOGIN, "/accounts?x=1", null, "/login?ssoError=IDP_ERROR&redirect=%2Faccounts%3Fx%3D1"),
            Arguments.of(SsoIntent.Kind.LINK, "/settings", null, "/settings?ssoError=IDP_ERROR"),
            Arguments.of(SsoIntent.Kind.LINK, "/settings?tab=security", null, "/settings?tab=security&ssoError=IDP_ERROR"),
            Arguments.of(SsoIntent.Kind.REAUTH, "/settings", null, "/settings?ssoError=IDP_ERROR"),
            Arguments.of(SsoIntent.Kind.REAUTH, "/settings?resume=export", null, "/settings?resume=export&ssoError=IDP_ERROR"),
            Arguments.of(SsoIntent.Kind.ACTIVATE, "/", "a/b?c", "/activate/a%2Fb%3Fc?ssoError=IDP_ERROR"),
            Arguments.of(SsoIntent.Kind.SETUP, "/", null, "/setup/admin?ssoError=IDP_ERROR"));
    }

    @ParameterizedTest
    @MethodSource("errorTargets")
    void routesErrorsToTheIntentPage(SsoIntent.Kind kind, String redirect, String token, String expected) {
        var intent = new SsoIntent(kind, redirect, false, null, token, Instant.EPOCH);
        assertThat(intent.errorRedirect("IDP_ERROR")).isEqualTo(expected);
        assertThat(SsoIntent.loginError("SESSION_EXPIRED")).isEqualTo("/login?ssoError=SESSION_EXPIRED");
    }
}
