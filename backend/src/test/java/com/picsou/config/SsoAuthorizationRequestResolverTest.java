package com.picsou.config;

import com.picsou.service.sso.SsoIntent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;

class SsoAuthorizationRequestResolverTest {
    private final ClientRegistration registration = ClientRegistration.withRegistrationId("oidc")
        .clientId("picsou").clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).scope("openid", "profile")
        .authorizationUri("https://idp.test/authorize").tokenUri("https://idp.test/token")
        .redirectUri("{baseUrl}/login/sso/callback")
        .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build()).build();
    private final SsoAuthorizationRequestResolver resolver = new SsoAuthorizationRequestResolver(id -> registration);

    @Test
    void missingIntentNeverResolvesEitherOverload() {
        var request = new MockHttpServletRequest("POST", "/oauth2/sso/authorization/oidc");
        assertThat(resolver.resolve(request)).isNull();
        assertThat(resolver.resolve(request, "oidc")).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = SsoIntent.Kind.class, names = {"LOGIN", "REAUTH"})
    void rebuiltUriPreservesHandshakeParametersInBothOverloads(SsoIntent.Kind kind) throws Exception {
        var request = new MockHttpServletRequest("POST", "/oauth2/sso/authorization/oidc");
        request.setServletPath(request.getRequestURI());
        var intent = new SsoIntent(kind, "/", false, null, null, Instant.EPOCH);
        request.setAttribute(SsoIntent.ATTRIBUTE, intent);
        for (var result : java.util.List.of(resolver.resolve(request), resolver.resolve(request, "oidc"))) {
            var query = UriComponentsBuilder.fromUriString(result.getAuthorizationRequestUri()).build().getQueryParams();
            assertThat(result.<SsoIntent>getAttribute(SsoIntent.ATTRIBUTE)).isSameAs(intent);
            assertThat(result.getState()).isNotBlank();
            assertThat(org.springframework.web.util.UriUtils.decode(query.getFirst("state"), StandardCharsets.UTF_8))
                .isEqualTo(result.getState());
            assertThat(query.getFirst("nonce")).isEqualTo(hash(result.getAttribute("nonce")));
            assertThat(query.getFirst("code_challenge")).isEqualTo(hash(result.getAttribute("code_verifier")));
            assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
            assertThat(result.getRedirectUri()).isEqualTo("http://localhost/login/sso/callback");
            switch (kind) {
                case REAUTH -> {
                    assertThat(query.getFirst("prompt")).isEqualTo("login");
                    assertThat(query.getFirst("max_age")).isEqualTo("0");
                }
                case LOGIN -> assertThat(query).doesNotContainKeys("prompt", "max_age");
                case LINK, ACTIVATE, SETUP -> throw new AssertionError("Not a tested kind");
            }
        }
    }

    private static String hash(String value) throws Exception {
        assertThat(value).isNotBlank();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
    }
}
