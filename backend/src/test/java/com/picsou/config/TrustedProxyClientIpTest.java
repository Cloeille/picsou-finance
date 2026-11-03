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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.cors.CorsUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Exercises the real Tomcat valves built from the production forwarding properties, without a DB.
 * Tomcat is the edge of the container: it trusts forwarded headers only from {@code PICSOU_PROXY_IP}.
 */
class TrustedProxyClientIpTest {

    private static final String PROXY = ProductionTomcatEdge.PROXY_IP;
    private static final int LISTENER_PORT = ProductionTomcatEdge.LISTENER_PORT;

    @Test
    void login_clientsThroughTheTrustedProxy_haveSeparateBuckets() throws Exception {
        Map<String, Bucket> buckets = new HashMap<>();
        AuthController controller = controller(buckets);
        for (int attempt = 0; attempt < 5; attempt++) {
            throughValve(PROXY, Map.of("X-Forwarded-For", "198.51.100.10"), request ->
                assertInvalidLogin(controller, request));
        }
        throughValve(PROXY, Map.of("X-Forwarded-For", "198.51.100.10"), request ->
            assertThat(controller.login(new LoginRequest("synthetic-user", "invalid", false),
                request, new MockHttpServletResponse()).getStatusCode().value()).isEqualTo(429));

        throughValve(PROXY, Map.of("X-Forwarded-For", "198.51.100.11"), request ->
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

    /**
     * Only PICSOU_PROXY_IP may set the client address: not loopback, not the private networks. The
     * public origin follows the forwarded headers from any peer, as the bundled nginx relayed them,
     * so an undeclared HTTPS proxy keeps same-origin requests working.
     */
    @Test
    void untrustedPeers_keepTheirSocketAddressButTheForwardedOriginApplies() throws Exception {
        for (String peer : new String[] {"127.0.0.1", "::1", "10.0.0.5", "172.18.0.2", "192.168.1.5", "2001:db8::20"}) {
            throughValve(peer, Map.of(
                "X-Forwarded-For", "203.0.113.9",
                "X-Real-IP", "203.0.113.10",
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "other.example.test:8443",
                "X-Forwarded-Port", "443"), request -> {
                    assertThat(ClientIp.resolve(request)).isEqualTo(peer);
                    assertThat(request.getScheme()).isEqualTo("https");
                    assertThat(request.isSecure()).isTrue();
                    assertThat(request.getServerName()).isEqualTo("other.example.test");
                    assertThat(request.getServerPort()).isEqualTo(8443);
                });
        }
    }

    /** Unset and empty behave the same: no peer may set the client address, even the would-be proxy. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "")
    void withoutAConfiguredProxy_noPeerIsTrusted(String proxyIp) throws Exception {
        ProductionTomcatEdge edge = new ProductionTomcatEdge(proxyIp);
        for (String peer : new String[] {PROXY, "127.0.0.1", "::1", "10.1.2.3", "172.18.0.2", "192.168.1.5"}) {
            edge.handle(peer, Map.of(
                "X-Forwarded-For", "203.0.113.9",
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "other.example.test:8443"), request -> {
                    assertThat(request.getRequest().getRemoteAddr()).as(peer).isEqualTo(peer);
                    assertThat(request.getScheme()).as(peer).isEqualTo("https");
                    assertThat(request.getServerPort()).as(peer).isEqualTo(8443);
                });
        }
    }

    @Test
    void audit_usesTheSameResolvedAddressAsRateLimiters() throws Exception {
        SetupAuditRepository repository = mock(SetupAuditRepository.class);
        SetupAuditService audit = new SetupAuditService(repository);
        throughValve(PROXY, Map.of(
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

    /**
     * What the bundled nginx derived: the port of the public host (bracketed IPv6 included), else
     * a numeric port header, else the scheme default.
     */
    @Test
    void trustedProxy_derivesThePublicPortFromTheForwardedHost() throws Exception {
        assertPublicPort(Map.of("X-Forwarded-Host", "nas.test:8080"), 8080);
        assertPublicPort(Map.of("X-Forwarded-Host", "[2001:db8::1]:8443"), 8443);
        assertPublicPort(Map.of("X-Forwarded-Host", "picsou.example", "X-Forwarded-Port", "9443"), 9443);
        // The port in the host wins over the port header, as it did in nginx.
        assertPublicPort(Map.of("X-Forwarded-Host", "picsou.example:8443", "X-Forwarded-Port", "443"), 8443);
        // No port anywhere: the scheme default.
        assertPublicPort(Map.of("X-Forwarded-Host", "picsou.example", "X-Forwarded-Proto", "https"), 443);
        assertPublicPort(Map.of("X-Forwarded-Host", "picsou.example", "X-Forwarded-Proto", "http"), 80);
        assertPublicPort(Map.of("X-Forwarded-Host", "[2001:db8::1]", "X-Forwarded-Proto", "https"), 443);
    }

    /** A valve that is not async-capable fails every async request (the MCP SSE stream) with a 500. */
    @Test
    void everyEngineValve_supportsAsyncRequests() throws Exception {
        assertThat(new ProductionTomcatEdge(PROXY).valves()).isNotEmpty().allSatisfy(valve ->
            assertThat(valve.isAsyncSupported()).as(valve.getClass().getName()).isTrue());
    }

    /** Existing installs behind an HTTPS proxy that is not PICSOU_PROXY_IP keep working (nginx parity). */
    @Test
    void undeclaredHttpsProxy_keepsThePublicOriginButNotTheClientAddress() throws Exception {
        String peer = "172.18.0.1";
        new ProductionTomcatEdge(null).handle(peer, Map.of(
            "Host", "winterfell.example.ts.net:8445",
            "X-Forwarded-For", "100.64.0.7",
            "X-Forwarded-Proto", "https",
            "X-Forwarded-Host", "winterfell.example.ts.net:8445",
            "Origin", "https://winterfell.example.ts.net:8445"), request -> {
                assertThat(request.getRequest().getRemoteAddr()).isEqualTo(peer);
                assertThat(request.getScheme()).isEqualTo("https");
                assertThat(request.getServerName()).isEqualTo("winterfell.example.ts.net");
                assertThat(request.getServerPort()).isEqualTo(8445);
                assertThat(CorsUtils.isCorsRequest(request.getRequest())).isFalse();
            });
    }

    /** With only X-Forwarded-Proto, the host and its port come from Host, as nginx's $http_host fallback. */
    @Test
    void forwardedProtoAlone_keepsThePortOfTheHostHeader() throws Exception {
        new ProductionTomcatEdge(null).handle("172.18.0.1", Map.of(
            "Host", "nas.test:8443", "X-Forwarded-Proto", "https"), request -> {
                assertThat(request.getScheme()).isEqualTo("https");
                assertThat(request.getServerName()).isEqualTo("nas.test");
                assertThat(request.getServerPort()).isEqualTo(8443);
            });
    }

    @Test
    void invalidForwardedPorts_fallBackToTheSchemeDefault() throws Exception {
        assertPublicPort(Map.of("X-Forwarded-Host", "picsou.example", "X-Forwarded-Proto", "https",
            "X-Forwarded-Port", "99999"), 443);
        assertPublicPort(Map.of("X-Forwarded-Host", "picsou.example:0", "X-Forwarded-Proto", "http"), 80);
    }

    @Test
    void requestWithoutForwardedHeaders_keepsTheNativeServerPort() throws Exception {
        // Direct access (http://nas:8080): Tomcat reads Host itself and nothing is rewritten.
        throughValve(PROXY, Map.of(), request -> assertThat(request.getServerPort()).isEqualTo(LISTENER_PORT));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "proxy",
        "172.30.253.0/29",
        "172.30.253.256",
        "172.30.253.02",
        "172.30.253.2.",
        " 172.30.253.2",
        "172.30.253.2 ",
        "172.30.253.2\nset_real_ip_from 0.0.0.0/0;",
        "172.30.253.2; include /tmp/injected.conf",
        "2001:db8::2",
        "172.30.253",
        "172.30.253.2|.*"})
    void invalidProxyAddress_refusesToStartWithoutEchoingIt(String value) {
        MockEnvironment environment = new MockEnvironment().withProperty(TrustedProxyBootstrap.PROXY_IP_ENV, value);

        assertThatThrownBy(() -> new TrustedProxyBootstrap().postProcessEnvironment(environment, new SpringApplication()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("PICSOU_PROXY_IP must be one plain IPv4 address.");
    }

    @Test
    void exactProxyAddress_isTheOnlyTrustedPeerExpression() {
        MockEnvironment environment = new MockEnvironment().withProperty(TrustedProxyBootstrap.PROXY_IP_ENV, PROXY);

        new TrustedProxyBootstrap().postProcessEnvironment(environment, new SpringApplication());

        String expression = environment.getProperty(TrustedProxyBootstrap.INTERNAL_PROXIES_PROPERTY);
        assertThat(expression).isEqualTo("172\\.30\\.253\\.2");
        assertThat(Pattern.matches(expression, PROXY)).isTrue();
        assertThat(Pattern.matches(expression, "172x30x253x2")).isFalse();
    }

    private void assertSameOrigin(String scheme, String host, String port) throws Exception {
        throughValve(PROXY, Map.of(
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

    private void assertPublicPort(Map<String, String> headers, int expected) throws Exception {
        throughValve(PROXY, headers, request ->
            assertThat(request.getServerPort()).as(headers.toString()).isEqualTo(expected));
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
        new ProductionTomcatEdge(PROXY).handle(peer, headers, request -> downstream.accept(request.getRequest()));
    }
}
