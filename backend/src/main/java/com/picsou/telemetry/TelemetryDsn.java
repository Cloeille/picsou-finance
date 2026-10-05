package com.picsou.telemetry;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A parsed Sentry/GlitchTip DSN: {@code scheme://<publicKey>@host[:port]/[prefix/]<projectId>}.
 * The tunnel only ever forwards to {@link #envelopeUri()}, i.e. the host in the operator's own
 * configuration — never to a host named by a client.
 */
public record TelemetryDsn(String raw, String scheme, String authority, String pathPrefix,
                           String projectId, String publicKey) {

    private static final Pattern PROJECT_ID = Pattern.compile("^[\\w\\-]{1,64}$");
    private static final Pattern PUBLIC_KEY = Pattern.compile("^[\\w\\-]{1,128}$");

    public static Optional<TelemetryDsn> parse(String dsn) {
        if (dsn == null || dsn.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(dsn.trim());
            String scheme = uri.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return Optional.empty();
            }
            if (uri.getHost() == null || uri.getUserInfo() == null) {
                return Optional.empty();
            }
            String key = uri.getUserInfo();
            int colon = key.indexOf(':');
            if (colon >= 0) {
                key = key.substring(0, colon); // legacy "key:secret" DSN — the secret is not used
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            int slash = path.lastIndexOf('/');
            if (slash < 0 || slash == path.length() - 1) {
                return Optional.empty();
            }
            String projectId = path.substring(slash + 1);
            String prefix = path.substring(0, slash);
            if (!PROJECT_ID.matcher(projectId).matches() || !PUBLIC_KEY.matcher(key).matches()) {
                return Optional.empty();
            }
            String authority = uri.getPort() >= 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
            return Optional.of(new TelemetryDsn(dsn.trim(), scheme, authority, prefix, projectId, key));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public URI envelopeUri() {
        return URI.create(scheme + "://" + authority + pathPrefix + "/api/" + projectId + "/envelope/");
    }

    public String authHeader() {
        return "Sentry sentry_version=7, sentry_key=" + publicKey + ", sentry_client=picsou-tunnel";
    }
}
