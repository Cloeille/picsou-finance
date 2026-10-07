package com.picsou.controller;

import com.picsou.config.AuthorizationServerConfig;
import com.picsou.config.JwtUtil;
import com.picsou.model.AppSetting;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.SetupState;
import com.picsou.model.UserRole;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 12: {@code GET /api/connected-apps} / {@code DELETE /api/connected-apps/{id}}, cookie/bearer
 * authenticated and strictly scoped to the caller's own {@code oauth2_authorization} rows (filtered
 * by {@code principal_name}). Boots the full application context + real Postgres via Testcontainers
 * (mirroring {@code DynamicClientRegistrationControllerTest}) since both the listing query and the
 * revoke path exercise the real V54 schema / {@code JdbcOAuth2AuthorizationService}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ConnectedAppsControllerTest {

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
    @Autowired RegisteredClientRepository registeredClientRepository;
    @Autowired OAuth2AuthorizationService authorizationService;
    @Autowired JwtUtil jwtUtil;

    private String callerBearer;
    private String callerUsername;

    @BeforeEach
    void completeSetupAndSeedUsers() {
        appSettingRepository.save(AppSetting.builder()
            .key("setup.state")
            .value(SetupState.COMPLETE.name())
            .build());

        AppUser caller = seedUser("caller");
        callerUsername = caller.getUsername();
        callerBearer = "Bearer " + jwtUtil.generateAccessToken(caller);
    }

    private AppUser seedUser(String label) {
        FamilyMember member = familyMemberRepository.save(
            FamilyMember.builder().displayName(label).build());
        return appUserRepository.save(AppUser.builder()
            .username(label + "-" + UUID.randomUUID())
            .passwordHash("$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ")
            .role(UserRole.ADMIN)
            .activated(true)
            .tokenVersion(0L)
            .member(member)
            .build());
    }

    private RegisteredClient seedMcpClient(String clientId, String clientName) {
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(clientId)
            .clientName(clientName)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .redirectUri("https://claude.ai/api/mcp/auth_callback")
            .scope("accounts:read")
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(true)
                .setting(AuthorizationServerConfig.MCP_CLIENT_SETTING, true)
                .build())
            .build();
        registeredClientRepository.save(client);
        return client;
    }

    /** Mirrors what the token endpoint persists at the end of a real authorization-code exchange. */
    private OAuth2Authorization seedAuthorization(RegisteredClient client, String principalName, Set<String> scopes) {
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "tok-" + UUID.randomUUID(),
            Instant.now(),
            Instant.now().plusSeconds(3600),
            scopes);
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
            .id(UUID.randomUUID().toString())
            .principalName(principalName)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizedScopes(scopes)
            .accessToken(accessToken)
            .build();
        authorizationService.save(authorization);
        return authorization;
    }

    @Test
    void list_returnsOnlyTheCallersOwnAuthorizations() throws Exception {
        RegisteredClient client = seedMcpClient("claude-list-1", "claude.ai");
        seedAuthorization(client, callerUsername, Set.of("accounts:read", "goals:read"));

        AppUser other = seedUser("other");
        seedAuthorization(client, other.getUsername(), Set.of("accounts:read"));

        mockMvc.perform(get("/api/connected-apps").header("Authorization", callerBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$[0].clientName").value("claude.ai"))
            .andExpect(jsonPath("$[0].scopes", org.hamcrest.Matchers.containsInAnyOrder(
                "accounts:read", "goals:read")))
            .andExpect(jsonPath("$[0].issuedAt").exists());
    }

    @Test
    void list_isEmpty_whenCallerHasNoConnectedApps() throws Exception {
        mockMvc.perform(get("/api/connected-apps").header("Authorization", callerBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void delete_removesTheCallersOwnAuthorization() throws Exception {
        RegisteredClient client = seedMcpClient("claude-del-1", "claude.ai");
        OAuth2Authorization authorization = seedAuthorization(client, callerUsername, Set.of("accounts:read"));

        mockMvc.perform(delete("/api/connected-apps/{id}", authorization.getId())
                .header("Authorization", callerBearer))
            .andExpect(status().isNoContent());

        assertThat(authorizationService.findById(authorization.getId())).isNull();
    }

    @Test
    void delete_returns404_whenTheAuthorizationBelongsToAnotherUser() throws Exception {
        RegisteredClient client = seedMcpClient("claude-del-2", "claude.ai");
        AppUser other = seedUser("victim");
        OAuth2Authorization othersAuthorization = seedAuthorization(client, other.getUsername(), Set.of("accounts:read"));

        mockMvc.perform(delete("/api/connected-apps/{id}", othersAuthorization.getId())
                .header("Authorization", callerBearer))
            .andExpect(status().isNotFound());

        // Isolation: the other user's authorization must survive the caller's failed attempt.
        assertThat(authorizationService.findById(othersAuthorization.getId())).isNotNull();
    }

    @Test
    void delete_returns404_forAnUnknownId() throws Exception {
        mockMvc.perform(delete("/api/connected-apps/{id}", "does-not-exist")
                .header("Authorization", callerBearer))
            .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticated_returns401_forListAndDelete() throws Exception {
        mockMvc.perform(get("/api/connected-apps"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/connected-apps/{id}", "whatever"))
            .andExpect(status().isUnauthorized());
    }
}
