package com.picsou.config;

import com.picsou.model.AppSetting;
import com.picsou.model.SetupState;
import com.picsou.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 6: an unauthenticated {@code /mcp} request gets the RFC 9728 {@code WWW-Authenticate}
 * challenge (via {@link McpAuthenticationEntryPoint}), while every other unauthenticated path
 * keeps the pre-existing {@code application/problem+json} 401 body from {@link SecurityConfig}.
 * Boots the full application context + real Postgres via Testcontainers (mirroring {@code
 * AuthorizationServerConfigTest} / {@code OAuth2SchemaMigrationTest}), since the wiring under test
 * lives in {@code SecurityConfig}'s real {@code SecurityFilterChain} bean, not a slice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class McpAuthenticationEntryPointTest {

    private static final Pattern CHALLENGE_PATTERN =
        Pattern.compile("Bearer resource_metadata=\".*/\\.well-known/oauth-protected-resource\"");

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

    /** SetupFilter 503s every request (including /mcp) until setup is COMPLETE — a fresh
     * Testcontainers DB starts at PENDING_ADMIN, so mark it complete before hitting the chain. */
    @BeforeEach
    void completeSetup() {
        appSettingRepository.save(AppSetting.builder()
            .key("setup.state")
            .value(SetupState.COMPLETE.name())
            .build());
    }

    @Test
    void unauthenticatedMcpRequest_gets401WithOAuthChallengeHeader() throws Exception {
        mockMvc.perform(get("/mcp"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", matchesPattern(CHALLENGE_PATTERN.pattern())));
    }

    @Test
    void unauthenticatedApiRequest_keepsThePreExistingProblemJsonEntryPoint() throws Exception {
        // Other paths must NOT get the OAuth challenge — SecurityConfig's original entry point
        // (JSON problem+json body, no WWW-Authenticate header) is unchanged for everything but /mcp.
        mockMvc.perform(get("/api/accounts"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    // ─── McpAuthenticationEntryPoint in isolation (no Spring context needed) ───

    @Test
    void commence_derivesBaseUrlFromTheRequest_notThreadLocalState() throws Exception {
        // Exercises the entry point directly, the way ExceptionTranslationFilter calls it: no
        // DispatcherServlet has run yet, so RequestContextHolder has nothing thread-bound. Also
        // covers the reverse-proxy case (X-Forwarded-* already applied to the request by
        // ForwardedHeaderFilter, simulated here by setting scheme/server directly).
        McpAuthenticationEntryPoint entryPoint = new McpAuthenticationEntryPoint();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp");
        request.setScheme("https");
        request.setServerName("mcp-picsou.patato.es");
        request.setServerPort(443);
        request.setContextPath("");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new InsufficientAuthenticationException("no auth"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(
            "Bearer resource_metadata=\"https://mcp-picsou.patato.es/.well-known/oauth-protected-resource\"");
    }
}
