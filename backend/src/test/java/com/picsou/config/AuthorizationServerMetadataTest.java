package com.picsou.config;

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

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 9: the RFC 8414 authorization-server metadata document ({@code
 * /.well-known/oauth-authorization-server}) must advertise the custom DCR endpoint (Task 8) via
 * {@code registration_endpoint}, and support {@code S256} PKCE.
 *
 * <p>This endpoint is served by the AS's own {@code @Order(1)} filter chain
 * ({@link AuthorizationServerConfig#authorizationServerSecurityFilterChain}), matched by {@code
 * OAuth2AuthorizationServerConfigurer.getEndpointsMatcher()} — NOT {@link SecurityConfig}'s
 * {@code @Order(2)} chain — so unlike Tasks 7/8 it is unaffected by {@code SetupFilter} (which is
 * only wired into the {@code @Order(2)} chain) and needs no {@code completeSetup()} step. Boots the
 * full application context + real Postgres via Testcontainers (mirroring
 * {@code AuthorizationServerConfigTest}) since real AS filter-chain wiring is the point.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AuthorizationServerMetadataTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "test-jwt-secret-test-jwt-secret-0123456789");
        registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired MockMvc mockMvc;

    @Test
    void metadata_advertisesTheRegistrationEndpoint_andS256Pkce() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-authorization-server"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.registration_endpoint", endsWith("/oauth2/register")))
            .andExpect(jsonPath("$.code_challenge_methods_supported", hasItem("S256")));
    }

    @Test
    void metadata_isReachableUnauthenticated_withNoSetupGate() throws Exception {
        // No completeSetup() call anywhere in this test class: this path is on the AS's own
        // securityMatcher chain, which SetupFilter never touches (it's only added to SecurityConfig's
        // @Order(2) chain) — so a fresh, not-yet-setup instance still serves this document.
        mockMvc.perform(get("/.well-known/oauth-authorization-server"))
            .andExpect(status().isOk());
    }
}
