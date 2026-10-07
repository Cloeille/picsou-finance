package com.picsou.config;

import com.picsou.mcp.Scopes;
import com.picsou.model.AppSetting;
import com.picsou.model.SetupState;
import com.picsou.repository.AppSettingRepository;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 8: RFC 7591 Dynamic Client Registration at {@code POST /oauth2/register}, unauthenticated.
 * Boots the full application context + real Postgres via Testcontainers (mirroring
 * {@code AuthorizationServerConfigTest}) since a real {@code JdbcRegisteredClientRepository} against
 * the V54 schema is the point — an in-memory stand-in would not exercise the same write path a
 * dynamically-registered claude.ai client relies on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class DynamicClientRegistrationControllerTest {

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
    @Autowired RegisteredClientRepository registeredClientRepository;
    @Autowired OAuthClientProperties oAuthClientProperties;
    @Autowired @Qualifier("oauthRegisterBuckets") Map<String, Bucket> registerBuckets;

    /**
     * Same trap Task 6 hit: SetupFilter 503s every request until setup is COMPLETE. Also clears the
     * (Spring-context-scoped, shared across every test method) I3 rate-limit bucket so one test's
     * registrations never spuriously 429 a later one — every MockMvc request in this class shares
     * the same {@code 127.0.0.1} remote address, and therefore the same bucket key.
     */
    @BeforeEach
    void completeSetup() {
        appSettingRepository.save(AppSetting.builder()
            .key("setup.state")
            .value(SetupState.COMPLETE.name())
            .build());
        registerBuckets.clear();
    }

    @Test
    void happyPath_persistsAPublicPkceMcpFlaggedClient_andReturns201() throws Exception {
        String body = """
            {"client_name":"claude.ai","redirect_uris":["https://claude.ai/api/mcp/auth_callback"],
             "scope":"accounts:read goals:read"}
            """;

        Instant before = Instant.now();
        String response = mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.client_id").exists())
            .andExpect(jsonPath("$.token_endpoint_auth_method").value("none"))
            .andExpect(jsonPath("$.redirect_uris[0]").value("https://claude.ai/api/mcp/auth_callback"))
            .andExpect(jsonPath("$.grant_types", org.hamcrest.Matchers.containsInAnyOrder(
                "authorization_code", "refresh_token")))
            // RFC 7591 §3.2.1: client_id_issued_at is a JSON number of epoch seconds, not an
            // ISO-8601 string — assert both the JSON type and that it's a sane recent timestamp.
            .andExpect(jsonPath("$.client_id_issued_at").isNumber())
            .andReturn().getResponse().getContentAsString();
        Instant after = Instant.now();

        Number issuedAtEpochSecond = com.jayway.jsonpath.JsonPath.read(response, "$.client_id_issued_at");
        assertThat(issuedAtEpochSecond.longValue())
            .isGreaterThanOrEqualTo(before.getEpochSecond())
            .isLessThanOrEqualTo(after.getEpochSecond());

        String clientId = com.jayway.jsonpath.JsonPath.read(response, "$.client_id");
        RegisteredClient persisted = registeredClientRepository.findByClientId(clientId);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(persisted.getScopes()).containsExactlyInAnyOrder("accounts:read", "goals:read");
        assertThat(persisted.getRedirectUris()).containsExactly("https://claude.ai/api/mcp/auth_callback");
        assertThat(persisted.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(persisted.getClientSettings().isRequireAuthorizationConsent()).isTrue();
        assertThat(persisted.getClientSettings().<Boolean>getSetting(AuthorizationServerConfig.MCP_CLIENT_SETTING))
            .isTrue();
        // I2: DCR clients must rotate refresh tokens and use the same configured TTLs as picsou-ios,
        // not Spring AS's own defaults (reuseRefreshTokens=true, 5m/60m).
        assertThat(persisted.getTokenSettings().isReuseRefreshTokens()).isFalse();
        assertThat(persisted.getTokenSettings().getAccessTokenTimeToLive())
            .isEqualTo(Duration.ofMinutes(oAuthClientProperties.getAccessTokenTtlMinutes()));
        assertThat(persisted.getTokenSettings().getRefreshTokenTimeToLive())
            .isEqualTo(Duration.ofDays(oAuthClientProperties.getRefreshTokenTtlDays()));
    }

    @Test
    void noScopeRequested_defaultsToEveryReadScope() throws Exception {
        String body = """
            {"redirect_uris":["https://claude.ai/api/mcp/auth_callback"]}
            """;

        String response = mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String clientId = com.jayway.jsonpath.JsonPath.read(response, "$.client_id");
        RegisteredClient persisted = registeredClientRepository.findByClientId(clientId);
        java.util.Set<String> expectedDefaults = Scopes.ALL.stream()
            .filter(s -> s.endsWith(":read") || s.endsWith("-read"))
            .collect(java.util.stream.Collectors.toSet());
        assertThat(persisted.getScopes()).containsExactlyInAnyOrderElementsOf(expectedDefaults);
    }

    @Test
    void emptyRedirectUris_returns400() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"redirect_uris":[]}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void malformedRedirectUri_returns400() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"redirect_uris":["not a valid uri with spaces and no scheme"]}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void scopeOutsideAllowlist_returns400() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"redirect_uris":["https://claude.ai/callback"],"scope":"admin:god-mode"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void anyRequestedAuthMethod_isCoercedToNone() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"redirect_uris":["https://claude.ai/callback"],
                     "token_endpoint_auth_method":"client_secret_basic"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token_endpoint_auth_method").value("none"));
    }

    // ─── m4: redirect_uris scheme allowlist (RFC 8252) ─────────────────────

    @Test
    void redirectUri_withNonLoopbackHttp_returns400() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"redirect_uris":["http://evil.example.com/callback"]}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void redirectUri_withCustomScheme_returns400() throws Exception {
        // The picsou://callback carve-out is reserved for the first-party iOS client (seeded
        // separately by AuthorizationServerConfig), never granted through open DCR.
        mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"redirect_uris":["myapp://callback"]}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void redirectUri_loopbackHttp_isAccepted() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"redirect_uris":["http://127.0.0.1:51820/callback"]}
                    """))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"redirect_uris":["http://localhost:51820/callback"]}
                    """))
            .andExpect(status().isCreated());
    }

    // ─── m5: field length validation against the V54 varchar limits ────────

    @Test
    void oversizedClientName_returns400_insteadOf500() throws Exception {
        String tooLong = "x".repeat(201); // client_name varchar(200)
        mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"client_name":"%s","redirect_uris":["https://claude.ai/callback"]}
                    """.formatted(tooLong)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void oversizedRedirectUris_returns400_insteadOf500() throws Exception {
        // redirect_uris is persisted as one comma-joined varchar(1000) column: 60 distinct,
        // individually-valid https:// entries comfortably exceeds that combined budget.
        StringBuilder uris = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            if (i > 0) uris.append(",");
            uris.append("\"https://claude.ai/callback/").append(i).append("-").append("x".repeat(10)).append("\"");
        }
        mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"redirect_uris\":[" + uris + "]}"))
            .andExpect(status().isBadRequest());
    }

    // ─── I3: per-IP rate limit on the open registration endpoint ───────────

    @Test
    void rateLimitExceeded_returns429() throws Exception {
        Bucket drained = RateLimitConfig.createOauthRegisterBucket();
        while (drained.tryConsume(1)) { /* drain */ }
        // MockMvc requests default to remote address 127.0.0.1, so this is the same bucket key
        // DynamicClientRegistrationController.getClientIp(...) resolves for every request below.
        registerBuckets.put("127.0.0.1", drained);

        mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"redirect_uris":["https://claude.ai/callback"]}
                    """))
            .andExpect(status().isTooManyRequests());
    }
}
