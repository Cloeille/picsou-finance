package com.picsou.config;

import com.picsou.service.sso.SsoIntent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SsoAuthenticationSuccessHandlerTest {
    @ParameterizedTest
    @EnumSource(SsoIntent.Kind.class)
    void everyIntentFailsClosedAndDiscardsHandshake(SsoIntent.Kind kind) throws Exception {
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        request.setSession(session);
        var intent = new SsoIntent(kind, "/settings?tab=security", false, null, "token", Instant.EPOCH);
        var authorization = OAuth2AuthorizationRequest.authorizationCode().authorizationUri("https://idp.test/authorize")
            .clientId("picsou").attributes(a -> a.put(SsoIntent.ATTRIBUTE, intent)).build();
        request.setAttribute(SsoAuthorizationRequestRepository.REMOVED_REQUEST_ATTRIBUTE, authorization);
        var response = new MockHttpServletResponse();
        new SsoAuthenticationSuccessHandler().onAuthenticationSuccess(request, response, null);
        assertThat(response.getRedirectedUrl()).isEqualTo(intent.errorRedirect("IDP_ERROR"));
        assertThat(session.isInvalid()).isTrue();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    void missingIntentExpiresAndInvalidatesSession() throws Exception {
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        request.setSession(session);
        var response = new MockHttpServletResponse();
        new SsoAuthenticationSuccessHandler().onAuthenticationSuccess(request, response, null);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?ssoError=SESSION_EXPIRED");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void missingSessionIsNotCreated() throws Exception {
        var request = new MockHttpServletRequest();
        new SsoAuthenticationSuccessHandler().onAuthenticationSuccess(request, new MockHttpServletResponse(), null);
        assertThat(request.getSession(false)).isNull();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"authorization_request_not_found,SESSION_EXPIRED", "invalid_grant,IDP_ERROR"})
    void failuresUseStableCodesAndInvalidateHandshake(String error, String expected) throws Exception {
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        request.setSession(session);
        var response = new MockHttpServletResponse();
        new SsoAuthenticationFailureHandler().onAuthenticationFailure(request, response,
            new OAuth2AuthenticationException(new OAuth2Error(error, "untrusted\r\ndescription", null)));
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?ssoError=" + expected);
        assertThat(session.isInvalid()).isTrue();
    }
}
