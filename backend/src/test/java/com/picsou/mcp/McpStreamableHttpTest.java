package com.picsou.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.AppSetting;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.SetupState;
import com.picsou.model.UserRole;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "MCP_PROTOCOL=STREAMABLE")
@Testcontainers(disabledWithoutDocker = true)
class McpStreamableHttpTest {

    private static final String JWT_SECRET = "test-jwt-secret-test-jwt-secret-0123456789";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> JWT_SECRET);
        registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("app.sidecar.api-key", () -> "test-sidecar-key");
    }

    @LocalServerPort int port;

    @Autowired ObjectMapper mapper;
    @Autowired AppSettingRepository appSettingRepository;
    @Autowired FamilyMemberRepository familyMemberRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired AccessKeyService accessKeyService;

    private AppUser owner;
    private String ownAccountName;
    private String otherAccountName;

    @BeforeEach
    void seed() {
        appSettingRepository.save(AppSetting.builder()
            .key("setup.state").value(SetupState.COMPLETE.name()).build());
        FamilyMember member = familyMemberRepository.save(FamilyMember.builder().displayName("Alice").build());
        FamilyMember other = familyMemberRepository.save(FamilyMember.builder().displayName("Bob").build());
        owner = appUserRepository.save(AppUser.builder()
            .username("streamable-" + UUID.randomUUID())
            .passwordHash("$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ")
            .role(UserRole.ADMIN).activated(true).tokenVersion(0L).member(member).build());
        ownAccountName = "own-" + UUID.randomUUID();
        otherAccountName = "other-" + UUID.randomUUID();
        accountRepository.save(Account.builder().member(member).name(ownAccountName).type(AccountType.CHECKING).build());
        accountRepository.save(Account.builder().member(other).name(otherAccountName).type(AccountType.CHECKING).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"access-key", "oauth2"})
    void modernClient_negotiatesRequestedVersionAndReadsOnlyItsOwnAccounts(String credentialType) throws Exception {
        String bearer = credentialType.equals("access-key")
            ? accessKeyService.create(owner, "streamable", Set.of(Scopes.ACCOUNTS_READ), null).rawSecret()
            : mcpToken();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpResponse<String> unauthenticated = post(http, null, null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"picsou-test","version":"0"}}}
                """);
            assertThat(unauthenticated.statusCode()).isEqualTo(401);
            assertThat(unauthenticated.headers().firstValue("WWW-Authenticate").orElseThrow())
                .contains("resource_metadata=");

            HttpResponse<String> initialized = post(http, bearer, null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"picsou-test","version":"0"}}}
                """);
            JsonNode initialization = rpc(initialized);
            assertThat(initialization.path("result").path("protocolVersion").asText()).isEqualTo("2025-11-25");
            assertThat(initialization.path("result").path("capabilities").has("tools")).isTrue();
            String session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
            try {
                assertThat(post(http, bearer, session, """
                    {"jsonrpc":"2.0","method":"notifications/initialized"}
                    """).statusCode()).isEqualTo(202);

                JsonNode catalogue = rpc(post(http, bearer, session, """
                    {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                    """));
                assertThat(catalogue.path("result").path("tools").findValuesAsText("name"))
                    .contains("list_accounts");

                JsonNode accounts = rpc(post(http, bearer, session, """
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_accounts","arguments":{}}}
                    """));
                assertThat(accounts.path("result").path("isError").asBoolean()).isFalse();
                String text = accounts.path("result").path("content").get(0).path("text").asText();
                assertThat(mapper.readTree(text).findValuesAsText("name")).containsExactly(ownAccountName);
                assertThat(text).doesNotContain(otherAccountName);

                JsonNode denied = rpc(post(http, bearer, session, """
                    {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_goals","arguments":{}}}
                    """));
                assertThat(denied.path("result").path("isError").asBoolean() || denied.hasNonNull("error"))
                    .as("A token without goals:read must not read goals").isTrue();

                HttpResponse<String> api = http.send(HttpRequest.newBuilder(base().resolve("/api/accounts"))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + bearer).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                assertThat(api.statusCode()).isEqualTo(401);
            } finally {
                HttpResponse<String> closed = http.send(HttpRequest.newBuilder(base().resolve("/mcp"))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + bearer)
                    .header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-11-25")
                    .DELETE().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(closed.statusCode()).isEqualTo(200);
            }
        }
    }

    private String mcpToken() {
        return Jwts.builder().subject(owner.getUsername()).claim("uid", owner.getId())
            .claim("type", "mcp").claim("tv", owner.getTokenVersion())
            .claim("scope", Scopes.ACCOUNTS_READ).claim("aud", List.of("picsou-mcp"))
            .issuedAt(Date.from(Instant.now())).expiration(Date.from(Instant.now().plusSeconds(900)))
            .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private HttpResponse<String> post(HttpClient http, String bearer, String session, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base().resolve("/mcp"))
            .timeout(Duration.ofSeconds(30)).header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json");
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (session != null) {
            request.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-11-25");
        }
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode rpc(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String json = response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")
            ? response.body().lines().filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim()).findFirst().orElseThrow()
            : response.body();
        JsonNode message = mapper.readTree(json);
        assertThat(message.path("jsonrpc").asText()).isEqualTo("2.0");
        return message;
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + port);
    }
}
