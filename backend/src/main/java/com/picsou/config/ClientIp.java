package com.picsou.config;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * Trusted client address for rate-limit keys.
 *
 * <p>{@code server.forward-headers-strategy: framework} (see {@code application.yml}) installs
 * Spring's {@code ForwardedHeaderFilter} so {@code request.getScheme()/getServerName()/
 * getServerPort()} reflect the public-facing values behind nginx — required for CORS same-origin
 * detection (see {@code docs/features/security-cors-cookies.md}). The same filter also rewrites
 * {@link HttpServletRequest#getRemoteAddr()} from the <strong>leftmost</strong> entry of
 * {@code X-Forwarded-For} — and nginx's {@code proxy_add_x_forwarded_for} only ever appends to
 * whatever the client sent, so that leftmost entry is client-controlled. A raw HTTP client can
 * rotate it on every request and get a fresh rate-limit bucket each time. That makes
 * {@code getRemoteAddr()} unsafe as a rate-limit key while this strategy is active.
 *
 * <p>{@code X-Real-IP} is the trustworthy signal instead: both nginx configs
 * ({@code docker/nginx.conf}, {@code frontend/nginx.conf}) unconditionally set it to
 * {@code $remote_addr} — the TCP peer nginx itself observed — on every proxied request, so a
 * client cannot inject or override it through our proxy.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code X-Real-IP}, if present and syntactically a plain IPv4/IPv6 literal. The literal
 *       check is defense in depth against a directly-exposed backend (no nginx in front) where
 *       the header would otherwise be attacker-supplied.</li>
 *   <li>Otherwise {@code request.getRemoteAddr()} — correct when there is genuinely no proxy in
 *       front (local dev, unit tests): without any {@code X-Forwarded-*} headers on the request,
 *       {@code ForwardedHeaderFilter} is a no-op and this is the real socket address.</li>
 * </ol>
 */
public final class ClientIp {

    private ClientIp() {}

    private static final String HEADER = "X-Real-IP";

    /**
     * Longest legal textual IP literal is 45 chars (IPv4-mapped IPv6, e.g.
     * {@code ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255}). Reject anything longer before it
     * ever reaches the regexes below — cheap protection against feeding a pathological string to
     * a fairly elaborate pattern from an attacker-controlled header.
     */
    private static final int MAX_LITERAL_LENGTH = 45;

    // Strict dotted-quad: each octet 0-255, no leading zeros (e.g. "010" is rejected).
    private static final Pattern IPV4 = Pattern.compile(
        "^(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])"
        + "(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}$"
    );

    // Standard textual IPv6 forms, including "::" compression and an embedded IPv4 tail
    // (e.g. "::ffff:192.0.2.1"). Zone/scope ids ("%eth0") are deliberately not accepted: nginx's
    // $remote_addr never carries one on the networks this app runs on, so rejecting them just
    // falls back to getRemoteAddr() rather than risking a malformed match.
    private static final Pattern IPV6 = Pattern.compile("""
        ^(
          ([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}
        | ([0-9a-fA-F]{1,4}:){1,7}:
        | ([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}
        | ([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}
        | ([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}
        | ([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}
        | ([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}
        | [0-9a-fA-F]{1,4}:(:[0-9a-fA-F]{1,4}){1,6}
        | :((:[0-9a-fA-F]{1,4}){1,7}|:)
        | ::(ffff(:0{1,4})?:)?((25[0-5]|(2[0-4]|1?[0-9])?[0-9])\\.){3}(25[0-5]|(2[0-4]|1?[0-9])?[0-9])
        | ([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1?[0-9])?[0-9])\\.){3}(25[0-5]|(2[0-4]|1?[0-9])?[0-9])
        )$
        """, Pattern.COMMENTS);

    /** Trusted client address for rate-limit keys — see class javadoc for the resolution order. */
    public static String resolve(HttpServletRequest request) {
        String realIp = request.getHeader(HEADER);
        if (isIpLiteral(realIp)) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    /**
     * Deliberately does NOT use {@link java.net.InetAddress#getByName}: for a string that is
     * not already a literal, that method falls through to the platform name service (DNS/hosts
     * lookup) — network I/O driven by an untrusted header value. Pure regex matching never
     * leaves the process.
     */
    private static boolean isIpLiteral(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LITERAL_LENGTH) {
            return false;
        }
        return IPV4.matcher(trimmed).matches() || IPV6.matcher(trimmed).matches();
    }
}
