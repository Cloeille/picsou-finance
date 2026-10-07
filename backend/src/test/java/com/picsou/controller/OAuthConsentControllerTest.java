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
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 10: {@code GET /api/oauth2/consent-info}, the cookie/bearer-authenticated endpoint the SPA
 * consent screen (Task 11) calls after Spring AS redirects to {@code /consent?scope=&client_id=&state=}.
 * Boots the full application context + real Postgres via Testcontainers (mirroring
 * {@code DynamicClientRegistrationControllerTest}) since resolving the {@code RegisteredClient} goes
 * through the real Jdbc-backed {@code RegisteredClientRepository} against the V54 schema.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OAuthConsentControllerTest {

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
    @Autowired JwtUtil jwtUtil;

    private String bearer;

    /** Same trap Task 6/8 hit: SetupFilter 503s every /api/** request until setup is COMPLETE. */
    @BeforeEach
    void completeSetupAndSeedUser() {
        appSettingRepository.save(AppSetting.builder()
            .key("setup.state")
            .value(SetupState.COMPLETE.name())
            .build());

        FamilyMember member = familyMemberRepository.save(
            FamilyMember.builder().displayName("Alice").build());
        AppUser user = appUserRepository.save(AppUser.builder()
            .username("alice-" + UUID.randomUUID())
            .passwordHash("$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ")
            .role(UserRole.ADMIN)
            .activated(true)
            .tokenVersion(0L)
            .member(member)
            .build());
        bearer = "Bearer " + jwtUtil.generateAccessToken(user);
    }

    private RegisteredClient seedMcpClient(String clientId, String... scopes) {
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(clientId)
            .clientName("claude.ai")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .redirectUri("https://claude.ai/api/mcp/auth_callback")
            .scopes(s -> {
                for (String scope : scopes) s.add(scope);
            })
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(true)
                .setting(AuthorizationServerConfig.MCP_CLIENT_SETTING, true)
                .build())
            .build();
        registeredClientRepository.save(client);
        return client;
    }

    @Test
    void returnsRequestedScopesAndClientName_forAKnownClient() throws Exception {
        RegisteredClient client = seedMcpClient("claude-1", "accounts:read", "goals:read", "dashboard:read");

        mockMvc.perform(get("/api/oauth2/consent-info")
                .header("Authorization", bearer)
                .param("client_id", client.getClientId())
                .param("scope", "accounts:read goals:read")
                .param("state", "xyz-state"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.client_name").value("claude.ai"))
            .andExpect(jsonPath("$.requested_scopes",
                org.hamcrest.Matchers.containsInAnyOrder("accounts:read", "goals:read")))
            .andExpect(jsonPath("$.state").value("xyz-state"));
    }

    @Test
    void requestedScopes_areFilteredToTheClientsOwnRegisteredScopes() throws Exception {
        // The client is registered with only accounts:read; a scope param outside that set
        // (however it got there) must never be echoed back as "requested".
        RegisteredClient client = seedMcpClient("claude-2", "accounts:read");

        mockMvc.perform(get("/api/oauth2/consent-info")
                .header("Authorization", bearer)
                .param("client_id", client.getClientId())
                .param("scope", "accounts:read admin:god-mode")
                .param("state", "s1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requested_scopes", org.hamcrest.Matchers.contains("accounts:read")));
    }

    @Test
    void unknownClient_returns404() throws Exception {
        mockMvc.perform(get("/api/oauth2/consent-info")
                .header("Authorization", bearer)
                .param("client_id", "does-not-exist")
                .param("scope", "accounts:read")
                .param("state", "s1"))
            .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        RegisteredClient client = seedMcpClient("claude-3", "accounts:read");

        mockMvc.perform(get("/api/oauth2/consent-info")
                .param("client_id", client.getClientId())
                .param("scope", "accounts:read")
                .param("state", "s1"))
            .andExpect(status().isUnauthorized());
    }
}
