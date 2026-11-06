package com.picsou.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The container-resolved client address for rate-limit keys and request metadata.
 *
 * <p>Tomcat's native forwarding support accepts client addresses only from the proxy named by
 * {@code PICSOU_PROXY_IP} ({@code server.tomcat.remoteip.internal-proxies}); with none
 * configured it trusts nobody. Direct connections retain their socket address. Header parsing here would bypass that
 * shared trust policy; audit readers of {@code getRemoteAddr()} use the same resolved value.
 */
public final class ClientIp {

    private ClientIp() {}

    public static String resolve(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
