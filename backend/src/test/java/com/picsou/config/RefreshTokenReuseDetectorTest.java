package com.picsou.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenReuseDetectorTest {

    private final NativeAppRefreshTokens tokens = new NativeAppRefreshTokens("test-jwt-secret-test-jwt-secret-0123456789");
    private final OAuth2AuthorizationService authorizationService = mock(OAuth2AuthorizationService.class);
    private final RefreshTokenReuseDetector detector = new RefreshTokenReuseDetector(authorizationService, tokens);

    private final RegisteredClient client = RegisteredClient.withId("client-row")
        .clientId("picsou-ios")
        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
        .redirectUri("picsou://callback")
        .build();

    private String rotatedAway;
    private String current;
    private OAuth2Authorization authorization;

    @BeforeEach
    void setUp() {
        rotatedAway = tokens.issue("auth-1");
        current = tokens.issue("auth-1");
        authorization = OAuth2Authorization.withRegisteredClient(client)
            .id("auth-1")
            .principalName("alice")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .refreshToken(new OAuth2RefreshToken(current, Instant.now(), Instant.now().plusSeconds(3600)))
            .build();
        when(authorizationService.findById("auth-1")).thenReturn(authorization);
    }

    @Test
    void rotatedAwayToken_revokesTheWholeAuthorization_andAnswersInvalidGrant() {
        assertThatThrownBy(() -> detector.authenticate(refreshRequest(rotatedAway)))
            .isInstanceOf(OAuth2AuthenticationException.class)
            .extracting(e -> ((OAuth2AuthenticationException) e).getError().getErrorCode())
            .isEqualTo("invalid_grant");

        verify(authorizationService).remove(authorization);
    }

    @Test
    void currentToken_defersToTheNormalRefresh() {
        assertThat(detector.authenticate(refreshRequest(current))).isNull();

        verify(authorizationService, never()).remove(any());
    }

    @Test
    void forgedTokenNamingARealAuthorization_isIgnored() {
        assertThat(detector.authenticate(refreshRequest("auth-1.random.not-a-valid-mac"))).isNull();

        verify(authorizationService, never()).findById(any());
        verify(authorizationService, never()).remove(any());
    }

    @Test
    void tokenOfAnAuthorizationAlreadyGone_defersToTheFrameworkRejection() {
        assertThat(detector.authenticate(refreshRequest(tokens.issue("auth-deleted")))).isNull();

        verify(authorizationService, never()).remove(any());
    }

    @Test
    void opaqueTokenOfAnotherClient_isIgnored() {
        assertThat(detector.authenticate(refreshRequest("plain-random-refresh-token-of-an-mcp-client"))).isNull();

        verify(authorizationService, never()).findById(any());
    }

    @Test
    void tokenCodec_rejectsAJwtSignedWithTheSameSecret() {
        String jwtShaped = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJl";

        assertThat(tokens.issuedFor(jwtShaped)).isEmpty();
        assertThat(tokens.issuedFor(rotatedAway)).contains("auth-1");
    }

    private OAuth2RefreshTokenAuthenticationToken refreshRequest(String refreshToken) {
        OAuth2ClientAuthenticationToken clientPrincipal =
            new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
        return new OAuth2RefreshTokenAuthenticationToken(refreshToken, clientPrincipal, null, Map.of());
    }
}
