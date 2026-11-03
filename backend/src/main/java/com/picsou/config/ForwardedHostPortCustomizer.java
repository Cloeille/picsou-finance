package com.picsou.config;

import jakarta.servlet.ServletException;
import org.apache.catalina.Globals;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the public port a trusted proxy sent, as the bundled nginx did. Tomcat's
 * {@code RemoteIpValve} strips the port from {@code X-Forwarded-Host}, and applies
 * {@code X-Forwarded-Port} only when the proxy also sent {@code X-Forwarded-Proto}. A request for
 * {@code nas:8080} would otherwise lose its port and fail the same-origin check. The port in the
 * host wins, then a numeric port header; with neither, Tomcat selects the scheme default.
 *
 * <p>The valve runs after {@code RemoteIpValve} and acts only on requests it marked as forwarded,
 * which it does for the trusted proxy alone: an untrusted client cannot influence the port.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ForwardedHostPortCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

    // A bracketed IPv6 literal or a name without colon, then a numeric port.
    private static final Pattern HOST_WITH_PORT =
        Pattern.compile("(?:\\[[0-9A-Fa-f:.]+\\]|[^:]+):([0-9]{1,5})");

    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");

    private final ServerProperties properties;

    public ForwardedHostPortCustomizer(ServerProperties properties) {
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
        String hostHeader = remoteip.getHostHeader();
        String portHeader = remoteip.getPortHeader();
        String protocolHeader = remoteip.getProtocolHeader();
        if (StringUtils.hasLength(hostHeader) && StringUtils.hasLength(portHeader)
                && StringUtils.hasLength(protocolHeader)) {
            // asyncSupported: a valve that is not breaks every async request, /mcp's SSE included.
            factory.addEngineValves(new ValveBase(true) {
                @Override
                public void invoke(Request request, Response response) throws IOException, ServletException {
                    if (Boolean.TRUE.equals(request.getAttribute(Globals.REQUEST_FORWARDED_ATTRIBUTE))) {
                        String port = publicPort(request.getHeader(hostHeader), request.getHeader(portHeader),
                            request.getHeader(protocolHeader) != null);
                        if (port != null) {
                            request.setServerPort(Integer.parseInt(port));
                            request.getCoyoteRequest().setServerPort(Integer.parseInt(port));
                        }
                    }
                    getNext().invoke(request, response);
                }
            });
        }
    }

    /** The port of the public host, else a numeric port header RemoteIpValve ignores; null leaves the request as is. */
    private static String publicPort(String host, String portHeader, boolean protocolForwarded) {
        Matcher match = host == null ? null : HOST_WITH_PORT.matcher(host);
        if (match != null && match.matches()) {
            return match.group(1);
        }
        if (!protocolForwarded && portHeader != null && PORT.matcher(portHeader).matches()) {
            return portHeader;
        }
        return null;
    }
}
