package com.picsou.config;

import com.picsou.controller.AuthController;
import com.picsou.dto.LoginRequest;
import com.picsou.model.SetupAudit;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.SetupAuditRepository;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import com.picsou.service.SetupAuditService;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.catalina.valves.ValveBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.embedded.TomcatWebServerFactoryCustomizer;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.cors.CorsUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Exercises the real Tomcat valve built from the production forwarding properties, without a DB. */
class TrustedProxyClientIpTest {

    @Test
    void login_clientsThroughTrustedNginx_haveSeparateBuckets() throws Exception {
        Map<String, Bucket> buckets = new HashMap<>();
        AuthController controller = controller(buckets);
        for (int attempt = 0; attempt < 5; attempt++) {
            throughValve("127.0.0.1", Map.of("X-Forwarded-For", "198.51.100.10"), request ->
                assertInvalidLogin(controller, request));
        }
        throughValve("127.0.0.1", Map.of("X-Forwarded-For", "198.51.100.10"), request ->
            assertThat(controller.login(new LoginRequest("synthetic-user", "invalid", false),
                request, new MockHttpServletResponse()).getStatusCode().value()).isEqualTo(429));

        throughValve("127.0.0.1", Map.of("X-Forwarded-For", "198.51.100.11"), request ->
            assertInvalidLogin(controller, request));

        assertThat(buckets).containsOnlyKeys("198.51.100.10", "198.51.100.11");
        assertThat(buckets.get("198.51.100.10").getAvailableTokens()).isZero();
        assertThat(buckets.get("198.51.100.11").getAvailableTokens()).isEqualTo(4);
    }

    @Test
    void login_untrustedPeer_cannotChangeItsBucketWithForwardedHeaders() throws Exception {
        Map<String, Bucket> buckets = new HashMap<>();
        AuthController controller = controller(buckets);
        for (int attempt = 1; attempt <= 6; attempt++) {
            int currentAttempt = attempt;
            throughValve("198.51.100.20", Map.of(
                "X-Forwarded-For", "203.0.113." + attempt,
                "X-Real-IP", "203.0.113." + (attempt + 10),
                "Forwarded", "for=203.0.113." + (attempt + 20)), request -> {
                    assertThat(request.getRemoteAddr()).isEqualTo("198.51.100.20");
                    if (currentAttempt <= 5) {
                        assertInvalidLogin(controller, request);
                    } else {
                        assertThat(controller.login(new LoginRequest("synthetic-user", "invalid", false),
                            request, new MockHttpServletResponse()).getStatusCode().value()).isEqualTo(429);
                    }
                });
        }
        assertThat(buckets).containsOnlyKeys("198.51.100.20");
    }

    @Test
    void untrustedPrivateAndIpv6Peers_keepTheirSocketAddressAndOrigin() throws Exception {
        for (String peer : new String[] {"10.0.0.5", "172.18.0.2", "192.168.1.5", "2001:db8::20"}) {
            throughValve(peer, Map.of(
                "X-Forwarded-For", "203.0.113.9",
                "X-Real-IP", "203.0.113.10",
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "other.example.test",
                "X-Forwarded-Port", "443"), request -> {
                    assertThat(ClientIp.resolve(request)).isEqualTo(peer);
                    assertThat(request.getScheme()).isEqualTo("http");
                    assertThat(request.getServerName()).isEqualTo("picsou.internal");
                    assertThat(request.getServerPort()).isEqualTo(9090);
                });
        }
    }

    @Test
    void audit_usesTheSameResolvedAddressAsRateLimiters() throws Exception {
        SetupAuditRepository repository = mock(SetupAuditRepository.class);
        SetupAuditService audit = new SetupAuditService(repository);
        throughValve("127.0.0.1", Map.of(
            "X-Forwarded-For", "2001:db8::10",
            "X-Real-IP", "203.0.113.99"), request -> {
                assertThat(ClientIp.resolve(request)).isEqualTo("2001:db8::10");
                audit.record("synthetic.event", "synthetic-user", request, null);
            });
        ArgumentCaptor<SetupAudit> saved = ArgumentCaptor.forClass(SetupAudit.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getIp()).isEqualTo("2001:db8::10");
    }

    @Test
    void trustedProxy_preservesPublicOriginsIncludingExplicitPorts() throws Exception {
        assertSameOrigin("http", "picsou.example.test:8080", "8080");
        assertSameOrigin("https", "picsou.example.test", "443");
        assertSameOrigin("https", "picsou.example.test:8443", "8443");
        assertSameOrigin("http", "[2001:db8::1]:8080", "8080");
    }

    private void assertSameOrigin(String scheme, String host, String port) throws Exception {
        throughValve("127.0.0.1", Map.of(
            "X-Forwarded-For", "198.51.100.10",
            "X-Forwarded-Proto", scheme,
            "X-Forwarded-Host", host,
            "X-Forwarded-Port", port,
            "Origin", scheme + "://" + host), request -> {
                assertThat(request.getScheme()).isEqualTo(scheme);
                assertThat(request.getServerPort()).isEqualTo(Integer.parseInt(port));
                assertThat(CorsUtils.isCorsRequest(request)).isFalse();
            });
    }

    private AuthController controller(Map<String, Bucket> buckets) {
        return new AuthController(mock(AppUserRepository.class), mock(PasswordEncoder.class),
            mock(JwtUtil.class), buckets, new HashMap<>(), mock(AuthCookieWriter.class),
            mock(MfaService.class), mock(PersistentSessionService.class), mock(SetupAuditService.class),
            false, true);
    }

    private void assertInvalidLogin(AuthController controller, HttpServletRequest request) {
        assertThatThrownBy(() -> controller.login(new LoginRequest("synthetic-user", "invalid", false),
            request, new MockHttpServletResponse())).isInstanceOf(BadCredentialsException.class);
    }

    private void throughValve(String peer, Map<String, String> headers,
                              Consumer<HttpServletRequest> downstream) throws Exception {
        RemoteIpValve valve = productionValve();
        valve.setNext(new ValveBase() {
            @Override
            public void invoke(Request request, Response response) {
                downstream.accept(request.getRequest());
            }
        });
        Request request = new Request(new Connector());
        org.apache.coyote.Request coyote = new org.apache.coyote.Request();
        request.setCoyoteRequest(coyote);
        request.setRemoteAddr(peer);
        request.setRemoteHost(peer);
        coyote.scheme().setString("http");
        coyote.serverName().setString("picsou.internal");
        coyote.setServerPort(9090);
        coyote.method().setString("POST");
        coyote.requestURI().setString("/api/auth/login");
        headers.forEach((name, value) -> coyote.getMimeHeaders().addValue(name).setString(value));
        valve.invoke(request, new Response());
    }

    private RemoteIpValve productionValve() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader().load("application",
            new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        ServerProperties properties = Binder.get(environment)
            .bind("server", Bindable.of(ServerProperties.class)).orElseThrow(IllegalStateException::new);
        assertThat(properties.getForwardHeadersStrategy()).isEqualTo(ServerProperties.ForwardHeadersStrategy.NATIVE);
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        new TomcatWebServerFactoryCustomizer(environment, properties).customize(factory);
        return factory.getEngineValves().stream().filter(RemoteIpValve.class::isInstance)
            .map(RemoteIpValve.class::cast).findFirst().orElseThrow();
    }
}
