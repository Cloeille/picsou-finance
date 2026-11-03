package com.picsou.controller;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.JwtUtil;
import com.picsou.config.RateLimitConfig;
import com.picsou.config.SecureCookieProvider;
import com.picsou.dto.ReAuthDto;
import com.picsou.dto.SetPasswordRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import com.picsou.dto.SignInMethodsResponse;
import com.picsou.dto.SsoInfoResponse;
import com.picsou.model.AppUser;
import com.picsou.service.ReAuthService;
import com.picsou.service.SignInMethodsService;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignInMethodsControllerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-test";

    @Mock private SignInMethodsService signInMethodsService;
    @Mock private ReAuthService reAuthService;
    @Mock private SecureCookieProvider secureCookieProvider;

    private JwtUtil jwtUtil;
    private Map<String, Bucket> buckets;
    private SignInMethodsController controller;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 15, 7, 5);
        buckets = new HashMap<>();
        controller = new SignInMethodsController(
            signInMethodsService, reAuthService, jwtUtil,
            new AuthCookieWriter(secureCookieProvider, jwtUtil), buckets);
    }

    @Test
    void sso_returnsTheServiceInfo() {
        SsoInfoResponse info = new SsoInfoResponse(false, "SSO");
        when(signInMethodsService.ssoInfo()).thenReturn(info);

        assertThat(controller.sso()).isSameAs(info);
    }

    @Test
    void signInMethods_isBuiltForThePrincipal() {
        AppUser user = AppUser.builder().id(7L).username("chloe").build();
        SignInMethodsResponse response =
            new SignInMethodsResponse(true, false, List.of(), false, "SSO", false);
        when(signInMethodsService.signInMethods(user)).thenReturn(response);

        assertThat(controller.signInMethods(user)).isSameAs(response);
    }

    @Test
    void reauth_onSuccess_setsTheProofCookieAndReturns204() {
        AppUser user = AppUser.builder().id(7L).username("chloe").build();
        MockHttpServletResponse httpRes = new MockHttpServletResponse();

        ResponseEntity<?> response =
            controller.reauth(user, new ReAuthDto("qa-password", null), httpRes);

        verify(reAuthService).verifyPasswordAndTotp(user, new ReAuthDto("qa-password", null));
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(httpRes.getHeaders("Set-Cookie")).singleElement()
            .satisfies(c -> assertThat(c).startsWith("reauth_proof=").contains("Max-Age=300"));
    }

    @Test
    void reauth_onRefusal_setsNoProofCookie() {
        AppUser user = AppUser.builder().id(7L).username("chloe").build();
        MockHttpServletResponse httpRes = new MockHttpServletResponse();
        doThrow(new ReAuthService.ReAuthFailedException("invalid password"))
            .when(reAuthService).verifyPasswordAndTotp(any(), any());

        assertThatThrownBy(() -> controller.reauth(
            user, new ReAuthDto("wrong", null), httpRes))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
        assertThat(httpRes.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    void reauth_afterFiveAttempts_theSixthIsRateLimitedWithCodeRateLimited() {
        AppUser user = AppUser.builder().id(7L).username("chloe").build();
        buckets.put("7", RateLimitConfig.createReauthBucket());
        doThrow(new ReAuthService.ReAuthFailedException("invalid password"))
            .when(reAuthService).verifyPasswordAndTotp(any(), any());

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThatThrownBy(() -> controller.reauth(
                user, new ReAuthDto("wrong", null), new MockHttpServletResponse()))
                .isInstanceOf(ReAuthService.ReAuthFailedException.class);
        }

        ResponseEntity<?> sixth =
            controller.reauth(user, new ReAuthDto("wrong", null), new MockHttpServletResponse());

        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(sixth.getBody()).isInstanceOf(ProblemDetail.class);
        assertThat(((ProblemDetail) sixth.getBody()).getProperties()).containsEntry("code", "RATE_LIMITED");
    }

    @Test
    void removePassword_delegatesAndReturns204() {
        AppUser user = AppUser.builder().id(7L).username("chloe").build();
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        ResponseEntity<Void> response = controller.removePassword(user, req, res);

        verify(signInMethodsService).removePassword(user, req, res);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void setPassword_delegatesAndReturns204() {
        AppUser user = AppUser.builder().id(7L).username("chloe").build();
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        ResponseEntity<Void> response = controller.setPassword(user, new SetPasswordRequest("new-password-1"), req, res);

        verify(signInMethodsService).setPassword(user, "new-password-1", req, res);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void unlinkIdentity_delegatesAndReturns204() {
        AppUser user = AppUser.builder().id(7L).username("chloe").build();
        MockHttpServletRequest req = new MockHttpServletRequest();

        ResponseEntity<Void> response = controller.unlinkIdentity(user, 5L, req);

        verify(signInMethodsService).unlinkIdentity(user, 5L, req);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
