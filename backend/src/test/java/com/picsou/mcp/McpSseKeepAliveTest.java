package com.picsou.mcp;

import com.picsou.model.AppSetting;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.SetupState;
import com.picsou.model.UserRole;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.support.PostgresContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An idle SSE stream must not stay silent: clients drop a stream that receives no byte for a few
 * minutes (Bun's fetch in Claude Code after ~6 min, the Python MCP client after 300 s), and the next
 * call then times out. With {@code spring.ai.mcp.server.keep-alive-interval} set, the server pings
 * every open session on its own. The interval is shortened to one second so the test sees it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.ai.mcp.server.keep-alive-interval=1s")
@Testcontainers(disabledWithoutDocker = true)
class McpSseKeepAliveTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "test-jwt-secret-test-jwt-secret-0123456789");
        registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("app.sidecar.api-key", () -> "test-sidecar-key");
    }

    @LocalServerPort int port;

    @Autowired AppSettingRepository appSettingRepository;
    @Autowired FamilyMemberRepository familyMemberRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired AccessKeyService accessKeyService;

    private String rawKey;

    @BeforeEach
    void seed() {
        appSettingRepository.save(AppSetting.builder()
            .key("setup.state")
            .value(SetupState.COMPLETE.name())
            .build());
        FamilyMember member = familyMemberRepository.save(FamilyMember.builder().displayName("Alice").build());
        AppUser owner = appUserRepository.save(AppUser.builder()
            .username("alice-" + UUID.randomUUID())
            .passwordHash("$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ")
            .role(UserRole.ADMIN)
            .activated(true)
            .tokenVersion(0L)
            .member(member)
            .build());
        rawKey = accessKeyService.create(owner, "keep-alive", Set.of(Scopes.SYNC_READ), null).rawSecret();
    }

    @Test
    void idleSseStream_receivesServerPings() throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        URI base = URI.create("http://127.0.0.1:" + port);
        HttpRequest open = HttpRequest.newBuilder(base.resolve("/mcp"))
            .timeout(Duration.ofMinutes(2))
            .header("Authorization", "Bearer " + rawKey)
            .header("Accept", "text/event-stream")
            .GET()
            .build();
        HttpResponse<java.io.InputStream> stream = http.send(open, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);

        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    events.add(line);
                }
            } catch (Exception ignored) {
                events.add("");
            }
        }, "mcp-sse");
        reader.setDaemon(true);
        reader.start();

        String endpoint = awaitEndpoint(events);
        URI messageUri = endpoint.startsWith("http") ? URI.create(endpoint) : base.resolve(endpoint);
        post(http, messageUri, rawKey, """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"picsou-test","version":"0"}}}
            """);
        awaitData(events, "\"id\":1");
        post(http, messageUri, rawKey, """
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            """);

        // No further client traffic: anything that arrives now was sent by the server on its own.
        String ping = awaitData(events, "\"method\":\"ping\"");
        assertThat(ping).contains("\"jsonrpc\":\"2.0\"");
    }

    private static void post(HttpClient http, URI uri, String rawKey, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + rawKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
            .as(response.body())
            .isIn(200, 202);
    }

    private static String awaitEndpoint(BlockingQueue<String> events) throws InterruptedException {
        String seen = awaitData(events, "sessionId=");
        for (String line : seen.split("\n")) {
            String value = line.startsWith("data:") ? line.substring(5).trim() : line.trim();
            if (value.contains("sessionId=")) {
                return value;
            }
        }
        throw new AssertionError("No endpoint data in:\n" + seen);
    }

    private static String awaitData(BlockingQueue<String> events, String needle) throws InterruptedException {
        StringBuilder seen = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            String line = events.poll(1, TimeUnit.SECONDS);
            if (line == null) {
                continue;
            }
            seen.append(line).append('\n');
            if (seen.indexOf(needle) >= 0) {
                return seen.toString();
            }
        }
        throw new AssertionError("SSE did not contain '" + needle + "'. Seen:\n" + seen);
    }
}
