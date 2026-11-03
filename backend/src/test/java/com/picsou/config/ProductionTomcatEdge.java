package com.picsou.config;

import org.apache.catalina.Valve;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.embedded.TomcatWebServerFactoryCustomizer;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Tomcat engine valves built from the production properties, as the server would install
 * them: {@code application.yml}, {@link TrustedProxyBootstrap} for {@code PICSOU_PROXY_IP}, then
 * the same customizers in the same order. No server starts and no database is needed.
 */
final class ProductionTomcatEdge {

    /** An address inside the bundled Compose proxy network, configured as the trusted proxy. */
    static final String PROXY_IP = "172.30.253.2";

    /** Differs from every public port the tests use, so a forwarded port is told from the listener's. */
    static final int LISTENER_PORT = 9090;

    private final List<Valve> valves;

    ProductionTomcatEdge(String proxyIp) throws IOException {
        this(proxyIp, null);
    }

    /** {@code internalProxies}, when not null, is an operator-set {@code server.tomcat.remoteip.internal-proxies}. */
    ProductionTomcatEdge(String proxyIp, String internalProxies) throws IOException {
        MockEnvironment environment = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader().load("application",
            new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        if (proxyIp != null) {
            environment.setProperty(TrustedProxyBootstrap.PROXY_IP_ENV, proxyIp);
        }
        if (internalProxies != null) {
            environment.setProperty(TrustedProxyBootstrap.INTERNAL_PROXIES_PROPERTY, internalProxies);
        }
        new TrustedProxyBootstrap().postProcessEnvironment(environment, new SpringApplication());
        ServerProperties properties = Binder.get(environment)
            .bind("server", Bindable.of(ServerProperties.class)).orElseThrow(IllegalStateException::new);
        assertThat(properties.getForwardHeadersStrategy()).isEqualTo(ServerProperties.ForwardHeadersStrategy.NATIVE);
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        new TomcatWebServerFactoryCustomizer(environment, properties).customize(factory);
        new ForwardedOriginCustomizer(properties).customize(factory);
        valves = List.copyOf(factory.getEngineValves());
    }

    /** Sends a plain-HTTP request from {@code peer} through the valves and hands it to {@code downstream}. */
    void handle(String peer, Map<String, String> headers, Consumer<Request> downstream) throws Exception {
        for (int i = 0; i < valves.size(); i++) {
            Valve next = i + 1 < valves.size() ? valves.get(i + 1) : terminal(downstream);
            valves.get(i).setNext(next);
        }
        Request request = new Request(new Connector());
        org.apache.coyote.Request coyote = new org.apache.coyote.Request();
        request.setCoyoteRequest(coyote);
        request.setRemoteAddr(peer);
        request.setRemoteHost(peer);
        coyote.scheme().setString("http");
        coyote.serverName().setString("picsou.internal");
        coyote.setServerPort(LISTENER_PORT);
        coyote.method().setString("POST");
        coyote.requestURI().setString("/api/auth/login");
        headers.forEach((name, value) -> coyote.getMimeHeaders().addValue(name).setString(value));
        valves.get(0).invoke(request, new Response());
    }

    List<Valve> valves() {
        return valves;
    }

    private static Valve terminal(Consumer<Request> downstream) {
        return new ValveBase() {
            @Override
            public void invoke(Request request, Response response) {
                downstream.accept(request);
            }
        };
    }
}
