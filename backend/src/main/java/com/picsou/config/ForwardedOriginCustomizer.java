package com.picsou.config;

import jakarta.servlet.ServletException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.tomcat.util.http.parser.Host;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Applies the public origin (scheme, host, port) a proxy forwarded, from any peer, exactly as the
 * bundled nginx did: it relayed {@code X-Forwarded-Proto} and {@code X-Forwarded-Host} whoever sent
 * them, and derived the port from the host. Only the client address stays restricted to
 * {@code PICSOU_PROXY_IP} ({@code RemoteIpValve}, {@link TrustedProxyBootstrap}), so an existing
 * install behind an undeclared HTTPS proxy keeps its same-origin checks working.
 *
 * <p>Port: the one in the forwarded host (or in {@code Host} when no forwarded host is sent) wins,
 * then a numeric {@code X-Forwarded-Port}, then the scheme default. A request without any of the
 * three headers is left as Tomcat parsed it (direct {@code http://nas:8080}).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ForwardedOriginCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");

    private final ServerProperties properties;

    public ForwardedOriginCustomizer(ServerProperties properties) {
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        // After TomcatWebServerFactoryCustomizer (order 0), which adds RemoteIpValve.
        return 1;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        ServerProperties.Tomcat.Remoteip remoteip = properties.getTomcat().getRemoteip();
        String protocolHeader = remoteip.getProtocolHeader();
        String hostHeader = remoteip.getHostHeader();
        String portHeader = remoteip.getPortHeader();
        // asyncSupported: a valve that is not breaks every async request, /mcp's SSE included.
        factory.addEngineValves(new ValveBase(true) {
            @Override
            public void invoke(Request request, Response response) throws IOException, ServletException {
                String proto = request.getHeader(protocolHeader);
                String forwardedHost = request.getHeader(hostHeader);
                String forwardedPort = request.getHeader(portHeader);
                if (proto != null || forwardedHost != null || forwardedPort != null) {
                    apply(request, proto, forwardedHost != null ? forwardedHost : request.getHeader("Host"),
                        forwardedPort);
                }
                getNext().invoke(request, response);
            }
        });
    }

    private static void apply(Request request, String proto, String host, String forwardedPort) {
        boolean https = proto != null ? isHttps(proto) : request.isSecure();
        Integer port = null;
        if (host != null) {
            try {
                int portIndex = Host.parse(host);
                String name = portIndex < 0 ? host : host.substring(0, portIndex);
                request.getCoyoteRequest().serverName().setString(name);
                if (portIndex >= 0) {
                    port = validPort(host.substring(portIndex + 1));
                }
            } catch (IllegalArgumentException invalidHost) {
                // Keep the server name Tomcat parsed, as RemoteIpValve does for an invalid host.
            }
        }
        if (port == null && forwardedPort != null) {
            port = validPort(forwardedPort);
        }
        if (port == null) {
            port = https ? 443 : 80;
        }
        request.setSecure(https);
        request.getCoyoteRequest().scheme().setString(https ? "https" : "http");
        request.setServerPort(port);
        request.getCoyoteRequest().setServerPort(port);
    }

    /** Tomcat's rule: every value of a comma-separated list must say https. */
    private static boolean isHttps(String proto) {
        return Arrays.stream(proto.split(",")).map(String::trim).allMatch("https"::equalsIgnoreCase);
    }

    private static Integer validPort(String value) {
        if (!PORT.matcher(value).matches()) {
            return null;
        }
        int port = Integer.parseInt(value);
        return port >= 1 && port <= 65535 ? port : null;
    }
}
