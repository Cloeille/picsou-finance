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
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The iOS app in Settings › Sessions, end to end through the real filter chain and a real
 * {@code oauth2_authorization} table: a signed-in device is listed (as {@code current} from its own
 * Bearer token), "log out everywhere else" drops the other device but keeps this one, and revoking
 * a device kills both its access token (immediately, not after the TTL) and its refresh token.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class NativeAppSessionIntegrationTest {

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

    private record Device(String accessToken, String refreshToken, String authorizationId) {}

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
    void signedInDevice_isListedAsCurrent_andRevocationKillsBothTokens() throws Exception {
        Device phone = signIn();

        String listed = mockMvc.perform(get("/api/auth/sessions").header("Authorization", bearer(phone)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<String> appIds = JsonPath.read(listed, "$[?(@.kind == 'IOS_APP')].id");
        List<Boolean> appCurrent = JsonPath.read(listed, "$[?(@.kind == 'IOS_APP')].current");
        assertThat(appIds).containsExactly(phone.authorizationId());
        assertThat(appCurrent).containsExactly(true);

        mockMvc.perform(delete("/api/auth/sessions/" + phone.authorizationId()).header("Authorization", bearer(phone)))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/dashboard").header("Authorization", bearer(phone)))
            .andExpect(status().isUnauthorized());
        refresh(phone)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void logOutEverywhereElse_fromTheApp_dropsTheOtherDevice_andKeepsThisOne() throws Exception {
        Device phone = signIn();
        Device tablet = signIn();

        String before = mockMvc.perform(get("/api/auth/sessions").header("Authorization", bearer(phone)))
            .andReturn().getResponse().getContentAsString();
        List<String> idsBefore = JsonPath.read(before, "$[?(@.kind == 'IOS_APP')].id");
        assertThat(idsBefore).containsExactlyInAnyOrder(phone.authorizationId(), tablet.authorizationId());

        mockMvc.perform(delete("/api/auth/sessions").header("Authorization", bearer(phone)))
            .andExpect(status().isNoContent());

        String after = mockMvc.perform(get("/api/auth/sessions").header("Authorization", bearer(phone)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<String> idsAfter = JsonPath.read(after, "$[?(@.kind == 'IOS_APP')].id");
        assertThat(idsAfter).containsExactly(phone.authorizationId());

        mockMvc.perform(get("/api/dashboard").header("Authorization", bearer(tablet)))
            .andExpect(status().isUnauthorized());
        refresh(tablet).andExpect(status().isBadRequest());
        refresh(phone).andExpect(status().isOk());
    }

    @Test
    void afterARename_theDeviceIsStillListedAndRevocable_andItsDeadBearerNeverFallsBackToTheCookie() throws Exception {
        Device phone = signIn();

        mockMvc.perform(patch("/api/auth/username").header("Authorization", bearer(phone))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newUsername\":\"renamed-" + UUID.randomUUID() + "\"}"))
            .andExpect(status().isOk());

        String listed = mockMvc.perform(get("/api/auth/sessions").header("Authorization", bearer(phone)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<String> appIds = JsonPath.read(listed, "$[?(@.kind == 'IOS_APP')].id");
        List<Boolean> appCurrent = JsonPath.read(listed, "$[?(@.kind == 'IOS_APP')].current");
        assertThat(appIds).containsExactly(phone.authorizationId());
        assertThat(appCurrent).containsExactly(true);

        mockMvc.perform(delete("/api/auth/sessions/" + phone.authorizationId()).header("Authorization", bearer(phone)))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/dashboard").header("Authorization", bearer(phone)).cookie(authCookie))
            .andExpect(status().isUnauthorized());
        refresh(phone).andExpect(status().isBadRequest());
    }

    @Test
    void anotherMembersDevice_cannotBeRevoked() throws Exception {
        Device phone = signIn();
        completeSetupAndSeedOwner();

        mockMvc.perform(delete("/api/auth/sessions/" + phone.authorizationId()).cookie(authCookie))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/dashboard").header("Authorization", bearer(phone)))
            .andExpect(status().isOk());
    }

    private Device signIn() throws Exception {
        String verifier = randomCodeVerifier();
        String clientId = oAuthClientProperties.getClientId();
        String redirectUri = oAuthClientProperties.getRedirectUri();

        URI authorizeUri = UriComponentsBuilder.fromPath("/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", clientId)
            .queryParam("redirect_uri", redirectUri)
            .queryParam("scope", "read")
            .queryParam("code_challenge", s256Challenge(verifier))
            .queryParam("code_challenge_method", "S256")
            .build().encode().toUri();
        String callback = mockMvc.perform(get(authorizeUri).cookie(authCookie))
            .andExpect(status().is3xxRedirection())
            .andReturn().getResponse().getHeader("Location");
        String code = URLDecoder.decode(
            UriComponentsBuilder.fromUriString(callback).build().getQueryParams().getFirst("code"),
            StandardCharsets.UTF_8);

        String tokens = mockMvc.perform(post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", redirectUri)
                .param("client_id", clientId)
                .param("code_verifier", verifier))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(tokens, "$.access_token");
        String authorizationId = jwtUtil.validateAndParse(accessToken)
            .get(AuthorizationServerConfig.AUTHORIZATION_ID_CLAIM, String.class);
        assertThat(authorizationId).as("an app access token names its authorization").isNotBlank();
        return new Device(accessToken, JsonPath.read(tokens, "$.refresh_token"), authorizationId);
    }

    private org.springframework.test.web.servlet.ResultActions refresh(Device device) throws Exception {
        return mockMvc.perform(post("/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("grant_type", "refresh_token")
            .param("refresh_token", device.refreshToken())
            .param("client_id", oAuthClientProperties.getClientId()));
    }

    private static String bearer(Device device) {
        return "Bearer " + device.accessToken();
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
