package com.picsou.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NativeAppRefreshTokenGeneratorTest {

    private static final String SECRET = "test-jwt-secret-test-jwt-secret-0123456789";

    private final AuthorizationServerConfig config = new AuthorizationServerConfig();
    private final NativeAppRefreshTokens tokens = config.nativeAppRefreshTokens(SECRET);
    private final OAuth2TokenGenerator<OAuth2Token> generator =
        config.tokenGenerator(config.jwkSource(SECRET), config.jwtTokenCustomizer(), tokens, new OAuthClientProperties());

    @Test
    void iosRefreshToken_namesItsAuthorization() {
        OAuth2Token token = generate(client("picsou-ios"), Instant.now());

        assertThat(tokens.issuedFor(token.getTokenValue())).contains("auth-1");
    }

    @Test
    void rotationNeverExtendsTheSignIn_pastOneRefreshTtlFromTheAuthorizationCode() {
        Instant signedInAt = Instant.now().minus(Duration.ofDays(20)).truncatedTo(ChronoUnit.SECONDS);

        OAuth2Token token = generate(client("picsou-ios"), signedInAt);

        assertThat(token.getExpiresAt()).isEqualTo(signedInAt.plus(Duration.ofDays(30)));
    }

    @Test
    void otherClients_keepAnOpaqueSlidingRefreshToken() {
        Instant signedInAt = Instant.now().minus(Duration.ofDays(20));

        OAuth2Token token = generate(client("claude-connector"), signedInAt);

        assertThat(tokens.issuedFor(token.getTokenValue())).isEmpty();
        assertThat(token.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(29)));
    }

    private OAuth2Token generate(RegisteredClient client, Instant signedInAt) {
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
            .id("auth-1")
            .principalName("alice")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .token(new OAuth2AuthorizationCode("code", signedInAt, signedInAt.plusSeconds(300)))
            .build();
        return generator.generate(DefaultOAuth2TokenContext.builder()
            .registeredClient(client)
            .principal(new UsernamePasswordAuthenticationToken("alice", null, List.of()))
            .authorization(authorization)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .tokenType(OAuth2TokenType.REFRESH_TOKEN)
            .build());
    }

    private static RegisteredClient client(String clientId) {
        return RegisteredClient.withId(clientId + "-row")
            .clientId(clientId)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .redirectUri("picsou://callback")
            .tokenSettings(TokenSettings.builder().refreshTokenTimeToLive(Duration.ofDays(30)).build())
            .build();
    }
}
