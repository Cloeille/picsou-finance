package com.picsou.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The forwarded public origin through the real server: embedded Tomcat on a random port, with
 * {@link ForwardedOriginCustomizer}, the production {@code application.yml} and the security
 * filter chains. A plain-HTTP request from the loopback peer, which is not a trusted proxy,
 * carries {@code X-Forwarded-Proto} and {@code X-Forwarded-Host}; the unit tests build the same
 * valves by hand, this proves they are installed and that what reads the origin sees it.
 */
@Testcontainers(disabledWithoutDocker = true)
class ForwardedOriginServerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    abstract static class Fixture {
        @DynamicPropertySource
        static void secrets(DynamicPropertyRegistry registry) {
            registry.add("app.jwt.secret", () -> "test-jwt-secret-test-jwt-secret-0123456789");
            registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        }

        @LocalServerPort int port;

        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        HttpResponse<String> get(String path, String... headers) throws Exception {
            return send("GET", path, headers);
        }

        HttpResponse<String> send(String method, String path, String... headers) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).method(method, HttpRequest.BodyPublishers.noBody());
            for (int i = 0; i < headers.length; i += 2) {
                request.header(headers[i], headers[i + 1]);
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        String issuer(String... headers) throws Exception {
            HttpResponse<String> metadata = get("/.well-known/oauth-authorization-server", headers);
            assertThat(metadata.statusCode()).as(metadata.body()).isEqualTo(200);
            return new ObjectMapper().readTree(metadata.body()).path("issuer").asText();
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestPropertySource(properties = "app.hsts-enabled=true")
    class WhenHstsIsEnabled extends Fixture {

        @Test
        void forwardedSchemeAndHost_areTheOriginTheApplicationSees() throws Exception {
            assertThat(issuer("X-Forwarded-Proto", "https", "X-Forwarded-Host", "picsou.example:8443"))
                .isEqualTo("https://picsou.example:8443");
            assertThat(issuer("X-Forwarded-Proto", "https", "X-Forwarded-Host", "picsou.example"))
                .isEqualTo("https://picsou.example");
        }

        @Test
        void withoutForwardedHeaders_theOriginIsTheDirectOne() throws Exception {
            assertThat(issuer()).isEqualTo("http://127.0.0.1:" + port);
        }

        /** The README's check, {@code curl -sI -H 'X-Forwarded-Proto: https' .../actuator/health}. */
        @Test
        void forwardedHttps_getsStrictTransportSecurityOnce() throws Exception {
            HttpResponse<String> response = send("HEAD", "/actuator/health", "X-Forwarded-Proto", "https");

            assertThat(response.headers().allValues("Strict-Transport-Security"))
                .hasSize(1).first().asString().contains("max-age=31536000");
        }

        @Test
        void plainHttp_getsNoStrictTransportSecurity() throws Exception {
            assertThat(get("/actuator/health").headers().firstValue("Strict-Transport-Security")).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestPropertySource(properties = "app.hsts-enabled=false")
    class WhenHstsIsDisabled extends Fixture {

        @Test
        void forwardedHttps_getsNoStrictTransportSecurity() throws Exception {
            HttpResponse<String> response = send("HEAD", "/actuator/health", "X-Forwarded-Proto", "https");

            assertThat(response.headers().firstValue("Strict-Transport-Security")).isEmpty();
            assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
        }

        /** Loopback is not PICSOU_PROXY_IP here: the rate-limit key stays the socket address. */
        @Test
        void untrustedPeer_cannotChooseItsRateLimitKey(@Autowired @Qualifier("setupBuckets") Map<String, Bucket> buckets)
            throws Exception {
            send("POST", "/api/setup/integrations/enablebanking/keypair", "X-Forwarded-For", "203.0.113.9");

            assertThat(buckets).containsKey("127.0.0.1").doesNotContainKey("203.0.113.9");
        }

        /**
         * The cap nginx used to apply, now on the running server: declared and chunked bodies. Both
         * go to the setup wizard, the one anonymous route open before setup; SetupFilter answers the
         * login 503 then, without reading its body.
         */
        @Test
        void anAnonymousBodyOverOneMebibyte_is413() throws Exception {
            byte[] body = new byte[2 * AnonymousBodyLimitFilter.MAX_BODY_BYTES];
            HttpRequest.Builder declared = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/setup/admin"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            HttpRequest.Builder chunked = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/setup/admin"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(body)));
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

            assertThat(client.send(declared.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
            assertThat(client.send(chunked.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
        }

        /**
         * Spring's FormContentFilter runs before Spring Security and reads a form-encoded
         * PUT/PATCH/DELETE body whole, with no limit, on any path: an anonymous client could make
         * the JVM buffer it before the 401. No endpoint reads such a body, so it is not registered.
         */
        @Test
        void noFilterParsesAFormEncodedBodyBeforeAuthentication(@Autowired org.springframework.context.ApplicationContext context) {
            assertThat(context.getBeansOfType(org.springframework.web.filter.FormContentFilter.class)).isEmpty();
        }
    }

    /** The running server's RemoteIpValve, wired from PICSOU_PROXY_IP, resolves the client address. */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestPropertySource(properties = "PICSOU_PROXY_IP=127.0.0.1")
    class BehindTheTrustedProxy extends Fixture {

        @Test
        void forwardedClientAddress_isTheRateLimitKey(@Autowired @Qualifier("setupBuckets") Map<String, Bucket> buckets)
            throws Exception {
            send("POST", "/api/setup/integrations/enablebanking/keypair", "X-Forwarded-For", "203.0.113.9");

            assertThat(buckets).containsKey("203.0.113.9").doesNotContainKey("127.0.0.1");
        }
    }
}
