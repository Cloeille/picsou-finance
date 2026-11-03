package com.picsou.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Makes {@code PICSOU_PROXY_IP} the only peer Tomcat takes the client address from. Tomcat is the
 * edge of the container: without a configured proxy it trusts nobody (an empty expression
 * matches no address), not its private-network default and not loopback.
 *
 * <p>An explicitly configured {@code server.tomcat.remoteip.internal-proxies} (the environment
 * variable {@code SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES}, {@code -D}, the command line,
 * {@code SPRING_APPLICATION_JSON} or a config file) wins and is left alone: the Compose file
 * always sets {@code PICSOU_PROXY_IP}, so letting it shadow the explicit property would make that
 * property unusable there. Otherwise this adds the lowest-precedence source holding the exact
 * {@code PICSOU_PROXY_IP} address, or the empty expression.
 *
 * <p>{@code PICSOU_PROXY_IP} is validated whenever it is set, even when it is not used: it must
 * be one plain IPv4 address, without leading zeros. It is never echoed.
 */
public class TrustedProxyBootstrap implements EnvironmentPostProcessor, Ordered {

    static final String PROXY_IP_ENV = "PICSOU_PROXY_IP";
    static final String INTERNAL_PROXIES_PROPERTY = "server.tomcat.remoteip.internal-proxies";
    static final String SOURCE_NAME = "picsouTrustedProxy";

    private static final Pattern IPV4 =
        Pattern.compile("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}");

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String proxyIp = environment.getProperty(PROXY_IP_ENV);
        String expression = "";
        if (StringUtils.hasLength(proxyIp)) {
            if (!isPlainIpv4(proxyIp)) {
                throw new IllegalStateException(PROXY_IP_ENV + " must be one plain IPv4 address.");
            }
            expression = proxyIp.replace(".", "\\.");
        }
        if (environment.containsProperty(INTERNAL_PROXIES_PROPERTY)) {
            return;
        }
        environment.getPropertySources().addLast(
            new MapPropertySource(SOURCE_NAME, Map.of(INTERNAL_PROXIES_PROPERTY, expression)));
    }

    private static boolean isPlainIpv4(String value) {
        if (!IPV4.matcher(value).matches()) {
            return false;
        }
        for (String octet : value.split("\\.")) {
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }
}
