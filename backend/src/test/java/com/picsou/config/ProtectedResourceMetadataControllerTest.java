package com.picsou.config;

import com.picsou.mcp.Scopes;
import com.picsou.model.AppSetting;
import com.picsou.model.SetupState;
import com.picsou.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 7: RFC 9728 protected-resource metadata at {@code /.well-known/oauth-protected-resource},
 * reachable unauthenticated. Boots the full application context + real Postgres via Testcontainers
 * (mirroring {@code McpAuthenticationEntryPointTest}), since what's under test is real
 * {@code SecurityConfig} wiring (the {@code permitAll} for this path), not just controller logic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ProtectedResourceMetadataControllerTest {

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

    /** Same trap Task 6 hit: SetupFilter 503s every request until setup is COMPLETE. */
    @BeforeEach
    void completeSetup() {
        appSettingRepository.save(AppSetting.builder()
            .key("setup.state")
            .value(SetupState.COMPLETE.name())
            .build());
    }

    @Test
    void metadata_isReachableUnauthenticated_andShapedPerRfc9728() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-protected-resource"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resource").value(org.hamcrest.Matchers.endsWith("/mcp")))
            .andExpect(jsonPath("$.authorization_servers", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.bearer_methods_supported[0]").value("header"))
            .andExpect(jsonPath("$.scopes_supported", org.hamcrest.Matchers.hasSize(Scopes.ALL.size())))
            .andExpect(jsonPath("$.scopes_supported",
                org.hamcrest.Matchers.containsInAnyOrder(Scopes.ALL.toArray())));
    }

    @Test
    void resourceAndAuthorizationServer_shareTheSameBaseUrl() throws Exception {
        String body = mockMvc.perform(get("/.well-known/oauth-protected-resource"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // resource == authorization_servers[0] + "/mcp" — both derived from the same request base.
        com.jayway.jsonpath.DocumentContext doc = com.jayway.jsonpath.JsonPath.parse(body);
        String resource = doc.read("$.resource");
        String authServer = doc.read("$.authorization_servers[0]");
        org.assertj.core.api.Assertions.assertThat(resource).isEqualTo(authServer + "/mcp");
    }
}
