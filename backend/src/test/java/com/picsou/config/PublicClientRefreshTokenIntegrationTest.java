package com.picsou.config;

import com.jayway.jsonpath.JsonPath;
import com.picsou.model.AppSetting;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.SetupState;
import com.picsou.model.UserRole;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for a real bug caught by live e2e testing of the iOS app (not by any unit test):
 * the native app's {@code picsou-ios} client — public, PKCE, {@link
 * org.springframework.security.oauth2.core.ClientAuthenticationMethod#NONE} — never received a
 * refresh token, and even once it did, could never redeem one. Two independent framework defaults
 * were responsible (confirmed by decompiling {@code spring-security-oauth2-authorization-server}
 * 1.4.5):
 *
 * <ol>
 *   <li>{@code OAuth2RefreshTokenGenerator.generate()} unconditionally returns {@code null} for a
 *       {@code NONE}-method client, regardless of its registered grant types or
 *       {@link org.springframework.security.oauth2.server.authorization.settings.TokenSettings}.
 *       Fixed by {@link AuthorizationServerConfig#tokenGenerator}.</li>
 *   <li>{@code PublicClientAuthenticationConverter} only recognizes a PKCE-shaped
 *       {@code authorization_code} request, so a {@code NONE}-method client can never authenticate
 *       itself on a {@code refresh_token} grant via any built-in converter — the request falls
 *       through anonymous and gets redirected to the SPA login. Fixed by
 *       {@code AuthorizationServerConfig}'s {@code PublicClientRefreshTokenAuthenticationConverter}
 *       + {@code PublicClientRefreshTokenAuthenticationProvider}.</li>
 * </ol>
 *
 * <p>Every device was, in effect, silently force-logged-out every {@code accessTokenTtlMinutes}
 * (15 min in prod) with no way to refresh — this walks the exact sequence the app performs
 * ({@code login → authorize → token exchange → refresh → refresh again with the rotated-away
 * token}) through the real security filter chain, the same shape as
 * {@link Oauth2ConsentHandshakeIntegrationTest} but for the first-party {@code picsou-ios} client
 * instead of a DCR-registered remote-MCP one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PublicClientRefreshTokenIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "test-jwt-secret-test-jwt-secret-0123456789");
        registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired MockMvc mockMvc;
    @Autowired AppSettingRepository appSettingRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired FamilyMemberRepository familyMemberRepository;
    @Autowired JwtUtil jwtUtil;
    @Autowired OAuthClientProperties oAuthClientProperties;

    private Cookie authCookie;

    @BeforeEach
    void completeSetupAndSeedOwner() {
        appSettingRepository.save(AppSetting.builder()
            .key("setup.state")
            .value(SetupState.COMPLETE.name())
            .build());

        FamilyMember member = familyMemberRepository.save(
            FamilyMember.builder().displayName("Alice").build());
        AppUser owner = appUserRepository.save(AppUser.builder()
            .username("alice-" + UUID.randomUUID())
            .passwordHash("$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ")
            .role(UserRole.ADMIN)
            .activated(true)
            .tokenVersion(0L)
            .member(member)
            .build());

        authCookie = new Cookie("access_token", jwtUtil.generateAccessToken(owner));
    }

    @Test
    void publicClient_isIssuedARefreshToken_andCanRedeemAndRotateIt() throws Exception {
        String verifier = randomCodeVerifier();
        String challenge = s256Challenge(verifier);
        String clientId = oAuthClientProperties.getClientId();
        String redirectUri = oAuthClientProperties.getRedirectUri();

        // 1) GET /oauth2/authorize with the cookie — first-party client, consent skipped — redirects
        // straight to the custom-scheme callback carrying a code (no /consent parking, unlike DCR).
        URI authorizeUri = UriComponentsBuilder.fromPath("/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", clientId)
            .queryParam("redirect_uri", redirectUri)
            .queryParam("scope", "read")
            .queryParam("code_challenge", challenge)
            .queryParam("code_challenge_method", "S256")
            .build().encode().toUri();

        MvcResult authorizeResult = mockMvc.perform(get(authorizeUri).cookie(authCookie))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String callback = authorizeResult.getResponse().getHeader("Location");
        assertThat(callback).as("must carry a code, not bounce to /login").startsWith(redirectUri).contains("code=");
        String code = java.net.URLDecoder.decode(
            UriComponentsBuilder.fromUriString(callback).build().getQueryParams().getFirst("code"),
            StandardCharsets.UTF_8);

        // 2) POST /oauth2/token — authorization_code + PKCE exchange. Bug #1 lived here: this
        // response used to have no refresh_token field at all.
        String firstTokenResponse = mockMvc.perform(post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", redirectUri)
                .param("client_id", clientId)
                .param("code_verifier", verifier))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").exists())
            .andExpect(jsonPath("$.refresh_token").exists())
            .andReturn().getResponse().getContentAsString();
        String refreshToken1 = JsonPath.read(firstTokenResponse, "$.refresh_token");
        assertThat(refreshToken1).isNotBlank();

        // 3) POST /oauth2/token, grant_type=refresh_token, client_id only (no secret, no PKCE
        // verifier — a public client has neither). Bug #2 lived here: this used to 302 to /login,
        // the client never authenticating at all.
        String refreshedResponse = mockMvc.perform(post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken1)
                .param("client_id", clientId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").exists())
            .andExpect(jsonPath("$.refresh_token").exists())
            .andReturn().getResponse().getContentAsString();
        String rotatedAccessToken = JsonPath.read(refreshedResponse, "$.access_token");
        String refreshToken2 = JsonPath.read(refreshedResponse, "$.refresh_token");
        assertThat(refreshToken2).isNotBlank().isNotEqualTo(refreshToken1);

        // 4) The rotated access token is a real, usable Bearer token against a protected endpoint —
        // proves the refreshed token isn't just well-formed JSON but actually authenticates.
        mockMvc.perform(get("/api/dashboard")
                .header("Authorization", "Bearer " + rotatedAccessToken))
            .andExpect(status().isOk());

        // 5) reuseRefreshTokens(false): the first refresh token must be dead after rotation —
        // otherwise a stolen (rotated-away) refresh token would remain silently valid forever.
        mockMvc.perform(post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken1)
                .param("client_id", clientId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_grant"));

        // 6) Reuse detection: that reuse revoked the whole authorization, so whoever holds the
        // current refresh token and access token is locked out too.
        mockMvc.perform(post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken2)
                .param("client_id", clientId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_grant"));
        mockMvc.perform(get("/api/dashboard")
                .header("Authorization", "Bearer " + rotatedAccessToken))
            .andExpect(status().isUnauthorized());
    }

    private String randomCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String s256Challenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }
}
