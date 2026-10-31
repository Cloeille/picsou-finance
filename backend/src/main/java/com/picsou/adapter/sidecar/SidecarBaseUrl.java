package com.picsou.adapter.sidecar;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Validates the base URL an adapter was configured to reach its sidecar on.
 *
 * <p>Every sidecar carries the user's bank login, password and one-time code in
 * its request bodies, so the transport is part of the threat model rather than a
 * deployment detail. The rule generalizes the one the Fortuneo hop applied on
 * its own: plaintext HTTP is accepted only where the sidecar is demonstrably
 * co-located with the backend -- loopback, or a single-label hostname, which on
 * this stack can only be a Compose service name resolved on the project's own
 * bridge network. Anything else is a hop across a real network and must be HTTPS.
 *
 * <p>Rejection is an {@link IllegalStateException} thrown while the adapter bean
 * is being constructed, so a misconfigured instance fails to start instead of
 * shipping credentials in the clear on the first sync -- the same fail-fast
 * posture as the mandatory encryption key.
 */
public final class SidecarBaseUrl {

    private SidecarBaseUrl() {
    }

    /**
     * @param sidecarName the sidecar this URL belongs to, used only in error messages
     * @param url         the configured base URL
     * @return {@code url}, trimmed, once validated
     * @throws IllegalStateException if the URL is unusable or would carry credentials in cleartext
     */
    public static String validate(String sidecarName, String url) {
        if (url == null || url.isBlank()) {
            throw invalid(sidecarName, url, "it is not set");
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException ex) {
            throw invalid(sidecarName, url, "it is not a valid URL");
        }

        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw invalid(sidecarName, url, "it has no scheme (expected http:// or https://)");
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw invalid(sidecarName, url, "the scheme '" + scheme + "' is not http or https");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw invalid(sidecarName, url, "it has no host");
        }

        // A base URL is not a place to put credentials: they would be echoed by
        // every client and proxy that logs the request URI.
        if (uri.getUserInfo() != null) {
            throw invalid(sidecarName, url, "it embeds credentials in the URL");
        }

        // The adapters append their own paths ("/initiate", "/complete", ...);
        // a query or fragment here would be silently dropped.
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw invalid(sidecarName, url, "it must not carry a query string or fragment");
        }

        if (scheme.equals("http") && !isCoLocated(host)) {
            throw invalid(
                sidecarName,
                url,
                "plain HTTP is only allowed for a sidecar co-located with the backend "
                    + "(loopback, or a Docker Compose service name). Host '" + host
                    + "' is reachable over a network, so it must use https://"
            );
        }

        return url.trim();
    }

    /**
     * True when the host can only resolve to something running beside the backend:
     * loopback, or a single-label name -- which has no public DNS meaning and on
     * this stack is a Compose service resolved on the project's bridge network.
     */
    private static boolean isCoLocated(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        // URI#getHost keeps the brackets around an IPv6 literal.
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }

        if (normalized.equals("localhost") || normalized.equals("::1") || normalized.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        // The whole 127.0.0.0/8 loopback block, not just 127.0.0.1.
        if (normalized.startsWith("127.")) {
            return true;
        }
        // A dotted name is a real DNS name (or an off-loopback IPv4 literal) and a
        // colon means a non-loopback IPv6 literal; a bare single label is a Compose
        // service name.
        return !normalized.contains(".") && !normalized.contains(":");
    }

    private static IllegalStateException invalid(String sidecarName, String url, String reason) {
        return new IllegalStateException(
            "The " + sidecarName + " sidecar URL is unusable because " + reason + " (configured: "
                + (url == null || url.isBlank() ? "<empty>" : url) + ")."
        );
    }
}
