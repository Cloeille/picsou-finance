package com.picsou.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Arrays;
import java.util.Set;

/**
 * Matches the requests the API chain must refuse as cross-site request forgery: a state-changing
 * method, authenticated by one of our ambient cookies, sent by a browser from another origin.
 *
 * <p>{@code SameSite=Lax} does not cover this on its own. A page on a sibling subdomain is
 * "same-site", so the browser attaches the cookies to its form POSTs, and many endpoints accept a
 * body-less or multipart POST that needs no CORS preflight.
 *
 * <p>Origin is decided from, in order:
 * <ol>
 *   <li>{@code Sec-Fetch-Site}: only {@code same-origin} and {@code none} (user-typed URL,
 *       bookmark) pass;</li>
 *   <li>{@code Origin}, then {@code Referer}, compared with the request's own scheme, host and
 *       port. Those come from {@code X-Forwarded-*} under {@code forward-headers-strategy:
 *       framework}, the same view {@code CorsUtils} uses for same-origin detection;</li>
 *   <li>neither header: a non-browser client, which cannot be driven by another site. Allowed.</li>
 * </ol>
 *
 * <p>A cross-origin request whose {@code Origin} is on the operator's CORS allow-list is trusted:
 * that list already grants it credentialed access. An {@code Authorization} header exempts
 * nothing: a browser attaches cached {@code Basic}/{@code Digest}/{@code Negotiate} credentials on
 * its own, and {@code JwtAuthenticationFilter} reads the cookie before any Bearer token. Bearer
 * clients (native app, MCP keys) send no auth cookie and are never matched.
 */
public class CrossSiteCookieRequestMatcher implements RequestMatcher {

    private static final Set<String> STATE_CHANGING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> SAME_ORIGIN_FETCH_SITES = Set.of("same-origin", "none");
    private static final Set<String> CREDENTIAL_COOKIES = Set.of(
        AuthCookieWriter.ACCESS_COOKIE,
        AuthCookieWriter.REFRESH_COOKIE,
        AuthCookieWriter.MFA_CHALLENGE_COOKIE,
        AuthCookieWriter.PERSISTENT_COOKIE
    );

    private final CorsConfigurationSource corsConfigurationSource;

    public CrossSiteCookieRequestMatcher(CorsConfigurationSource corsConfigurationSource) {
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        return STATE_CHANGING_METHODS.contains(request.getMethod())
            && carriesCredentialCookie(request)
            && isCrossOrigin(request)
            && !isAllowListedOrigin(request);
    }

    private static boolean carriesCredentialCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        return cookies != null && Arrays.stream(cookies).anyMatch(c -> CREDENTIAL_COOKIES.contains(c.getName()));
    }

    private static boolean isCrossOrigin(HttpServletRequest request) {
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null) {
            return !SAME_ORIGIN_FETCH_SITES.contains(fetchSite);
        }
        String source = request.getHeader(HttpHeaders.ORIGIN);
        if (source == null) {
            source = request.getHeader(HttpHeaders.REFERER);
        }
        return source != null && !isSameOrigin(request, source);
    }

    /** {@code "null"} (opaque origin) and malformed values never match. */
    static boolean isSameOrigin(HttpServletRequest request, String url) {
        UriComponents source;
        try {
            source = UriComponentsBuilder.fromUriString(url).build();
        } catch (IllegalArgumentException ex) {
            return false;
        }
        String scheme = source.getScheme();
        String host = source.getHost();
        return scheme != null && host != null
            && scheme.equalsIgnoreCase(request.getScheme())
            && host.equalsIgnoreCase(request.getServerName())
            && effectivePort(scheme, source.getPort()) == effectivePort(request.getScheme(), request.getServerPort());
    }

    private static int effectivePort(String scheme, int port) {
        if (port != -1) return port;
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private boolean isAllowListedOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null) return false;
        CorsConfiguration config = corsConfigurationSource.getCorsConfiguration(request);
        return config != null && config.checkOrigin(origin) != null;
    }
}
