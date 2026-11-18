package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.service.LouveSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Louve Invest connection, laid out like the other API connectors ({@code /api/wise},
 * {@code /api/simplefin}): an OAuth redirect to Louve's consent page and back instead of a pasted
 * token, then {@code /sync}, {@code /status} and {@code DELETE /connection}. All calls are the member's own (cookie session); the callback page of the SPA posts
 * the code here, so no state changes on a GET.
 */
@RestController
@RequestMapping("/api/louve")
public class LouveController {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final LouveSyncService service;
    private final UserContext userContext;
    private final CorsConfigurationSource corsConfigurationSource;
    private final Map<String, Bucket> authBuckets;
    private final Map<String, Bucket> syncBuckets;

    public LouveController(
        LouveSyncService service,
        UserContext userContext,
        // Named: Spring MVC's HandlerMappingIntrospector is a CorsConfigurationSource too.
        @Qualifier("corsConfigurationSource") CorsConfigurationSource corsConfigurationSource,
        @Qualifier("louveAuthBuckets") Map<String, Bucket> authBuckets,
        @Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.service = service;
        this.userContext = userContext;
        this.corsConfigurationSource = corsConfigurationSource;
        this.authBuckets = authBuckets;
        this.syncBuckets = syncBuckets;
    }

    /**
     * Returns Louve's consent URL. The redirect URI is the origin the browser is on plus
     * {@link LouveSyncService#CALLBACK_PATH}; see {@link #redirectUri}.
     */
    @PostMapping("/auth/start")
    public ResponseEntity<?> start(HttpServletRequest request) {
        if (!consume(authBuckets, request, RateLimitConfig::createLouveAuthBucket)) {
            return rateLimited("Too many Louve Invest connection attempts. Please wait before retrying.");
        }
        return ResponseEntity.ok(service.startConnection(userContext.currentMemberId(), redirectUri(request)));
    }

    @PostMapping("/auth/complete")
    public ResponseEntity<?> complete(@Valid @RequestBody CompleteRequest body, HttpServletRequest request) {
        if (!consume(authBuckets, request, RateLimitConfig::createLouveAuthBucket)) {
            return rateLimited("Too many Louve Invest connection attempts. Please wait before retrying.");
        }
        return ResponseEntity.ok(service.completeConnection(
            userContext.currentMemberId(), body.code(), body.state(), body.iss()));
    }

    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        if (!consume(syncBuckets, request, RateLimitConfig::createSyncBucket)) {
            return rateLimited("Too many Louve Invest synchronization requests. Please wait before retrying.");
        }
        return ResponseEntity.ok(service.sync(userContext.currentMemberId()));
    }

    @GetMapping("/status")
    public LouveSyncService.ConnectionStatusResponse status() {
        return service.getStatus(userContext.currentMemberId());
    }

    /** Disconnects and revokes the grant on Louve (best effort). */
    @DeleteMapping("/connection")
    public ResponseEntity<Void> disconnect() {
        service.deleteConnection(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    /**
     * The redirect URI for this request: the browser's {@code Origin} plus the callback path.
     *
     * <p>The origin is trusted only when the browser says the call is same-origin
     * ({@code Sec-Fetch-Site}), when it matches the request's own scheme, host and port (behind the
     * trusted proxy), or when the operator allow-listed it in the CORS settings of the setup wizard.
     * It must be HTTPS, or plain HTTP on a loopback host for development. A non-browser client that
     * forges these headers only misdirects its own consent page.
     */
    String redirectUri(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || origin.isBlank()) {
            origin = request.getScheme() + "://" + request.getServerName()
                + (isDefaultPort(request.getScheme(), request.getServerPort()) ? "" : ":" + request.getServerPort());
        } else if (!"same-origin".equals(request.getHeader("Sec-Fetch-Site"))
                && !sameAsRequest(origin, request) && !allowListed(origin, request)) {
            throw new IllegalArgumentException("This Picsou address is not allowed for a Louve Invest connection");
        }
        URI parsed;
        try {
            parsed = URI.create(origin.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("This Picsou address is not a valid origin");
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.ROOT);
        boolean secure = scheme.equals("https") || (scheme.equals("http") && LOOPBACK_HOSTS.contains(host));
        if (!secure || host.isEmpty() || (parsed.getPath() != null && !parsed.getPath().isEmpty())
                || parsed.getQuery() != null || parsed.getUserInfo() != null) {
            throw new IllegalArgumentException("Louve Invest needs Picsou to be reached over HTTPS");
        }
        String port = parsed.getPort() == -1 || isDefaultPort(scheme, parsed.getPort()) ? "" : ":" + parsed.getPort();
        return scheme + "://" + parsed.getHost() + port + LouveSyncService.CALLBACK_PATH;
    }

    private static boolean sameAsRequest(String origin, HttpServletRequest request) {
        try {
            URI uri = URI.create(origin.trim());
            int port = uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
            return request.getScheme().equalsIgnoreCase(uri.getScheme())
                && request.getServerName().equalsIgnoreCase(uri.getHost())
                && request.getServerPort() == port;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean allowListed(String origin, HttpServletRequest request) {
        CorsConfiguration config = corsConfigurationSource.getCorsConfiguration(request);
        return config != null && config.checkOrigin(origin) != null;
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return ("https".equalsIgnoreCase(scheme) && port == 443) || ("http".equalsIgnoreCase(scheme) && port == 80);
    }

    private static boolean consume(Map<String, Bucket> buckets, HttpServletRequest request,
                                   java.util.function.Supplier<Bucket> factory) {
        return buckets.computeIfAbsent(ClientIp.resolve(request), key -> factory.get()).tryConsume(1);
    }

    private static ResponseEntity<ProblemDetail> rateLimited(String message) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail(message);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }

    /** {@code iss} is required: Louve advertises RFC 9207, so a callback without it is refused. */
    record CompleteRequest(
        @NotBlank @Size(max = 2048) String code,
        @NotBlank @Size(max = 512) String state,
        @NotBlank @Size(max = 200) String iss
    ) {}
}
