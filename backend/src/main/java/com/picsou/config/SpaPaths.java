package com.picsou.config;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits the URL space between the backend and the single-page app. Every path outside the
 * backend prefixes belongs to the SPA: the resource handler falls back to {@code index.html},
 * Spring Security lets anonymous reads through, and {@link SetupFilter} serves it before setup.
 *
 * <p>The prefixes are the ones the bundled nginx used to route, matched the same way (plain
 * {@code startsWith}, so {@code /mcpx} is a backend path too).
 */
public final class SpaPaths {

    private static final List<String> BACKEND_PREFIXES =
        List.of("/api", "/oauth2", "/.well-known/oauth-", "/actuator", "/mcp");

    /**
     * Asset extensions. A missing file with one of them is a 404, never {@code index.html}. Only
     * Vite's build output under {@code /assets/} carries a content hash in its name, so only those
     * files are cached for a year ({@link #isHashedAsset}); files copied from {@code public/}
     * (favicon, {@code theme-init.js}, provider logos) keep their URL across releases.
     */
    private static final Pattern CACHED_EXTENSION =
        Pattern.compile("\\.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$", Pattern.CASE_INSENSITIVE);

    private SpaPaths() {
    }

    public static boolean isBackend(String path) {
        for (String prefix : BACKEND_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasCachedExtension(String path) {
        return CACHED_EXTENSION.matcher(path).find();
    }

    /** A content-hashed build file: safe to cache for a year as immutable. */
    public static boolean isHashedAsset(String path) {
        return path.startsWith("/assets/") && hasCachedExtension(path);
    }

    /**
     * The decoded path Spring MVC dispatches on. {@code getRequestURI()} is not usable for a
     * routing decision: {@code /%61pi/x} is not a backend path as written, yet MVC routes it to
     * {@code /api/x}. Falls back to the raw URI when the container reports no servlet path.
     */
    public static String requestPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        String path = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
        return path.isEmpty() ? request.getRequestURI() : path;
    }
}
