package com.picsou.controller;

import com.picsou.config.DynamicCorsConfigurationSource;
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

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.picsou.config.PerIpRateLimit.tooManyRequests;
import static com.picsou.config.PerIpRateLimit.tryConsume;

/**
 * Louve Invest connection, laid out like the other API connectors ({@code /api/wise},
 * {@code /api/simplefin}): an OAuth redirect to Louve's consent page and back instead of a pasted
 * token, then {@code /sync}, {@code /status} and {@code DELETE /connection}. All calls are the member's own (cookie session); the callback page of the SPA posts
 * the code here, so no state changes on a GET.
 */
@RestController
@RequestMapping("/api/louve")
public class LouveController {

    /** The browser's origin is not one of the instance's configured origins. */
    static final String ORIGIN_NOT_ALLOWED = "ORIGIN_NOT_ALLOWED";
    /** The configured origin is plain HTTP on a non-loopback host. */
    static final String ORIGIN_NOT_SECURE = "ORIGIN_NOT_SECURE";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final LouveSyncService service;
    private final UserContext userContext;
    private final DynamicCorsConfigurationSource origins;
    private final Map<String, Bucket> authBuckets;
    private final Map<String, Bucket> syncBuckets;

    public LouveController(
        LouveSyncService service,
        UserContext userContext,
        DynamicCorsConfigurationSource origins,
        @Qualifier("louveAuthBuckets") Map<String, Bucket> authBuckets,
        @Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.service = service;
        this.userContext = userContext;
        this.origins = origins;
        this.authBuckets = authBuckets;
        this.syncBuckets = syncBuckets;
    }

    /**
     * Returns Louve's consent URL. The redirect URI is the configured origin the browser is on
     * plus {@link LouveSyncService#CALLBACK_PATH}; see {@link #allowedOrigin} and {@link #redirectUri}.
     */
    @PostMapping("/auth/start")
    public ResponseEntity<?> start(HttpServletRequest request) {
        if (!tryConsume(authBuckets, request, RateLimitConfig::createLouveAuthBucket)) {
            return tooManyRequests("Too many Louve Invest connection attempts. Please wait before retrying.");
        }
        String origin = allowedOrigin(request.getHeader(HttpHeaders.ORIGIN));
        if (origin == null) {
            return badRequest(ORIGIN_NOT_ALLOWED, "Add this Picsou address to the allowed origins in "
                + "Administration > Security, then connect Louve Invest again.");
        }
        String redirectUri = redirectUri(origin);
        if (redirectUri == null) {
            return badRequest(ORIGIN_NOT_SECURE, "Louve Invest needs Picsou to be reached over HTTPS.");
        }
        return ResponseEntity.ok(service.startConnection(userContext.currentMemberId(), redirectUri));
    }

    @PostMapping("/auth/complete")
    public ResponseEntity<?> complete(@Valid @RequestBody CompleteRequest body, HttpServletRequest request) {
        if (!tryConsume(authBuckets, request, RateLimitConfig::createLouveAuthBucket)) {
            return tooManyRequests("Too many Louve Invest connection attempts. Please wait before retrying.");
        }
        return ResponseEntity.ok(service.completeConnection(
            userContext.currentMemberId(), body.code(), body.state(), body.iss()));
    }

    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        if (!tryConsume(syncBuckets, request, RateLimitConfig::createSyncBucket)) {
            return tooManyRequests("Too many Louve Invest synchronization requests. Please wait before retrying.");
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
     * The browser's {@code Origin} when it is one of the instance's public origins: the list the
     * operator keeps in Administration > Security, or {@code ALLOWED_ORIGINS} before one is saved
     * (the same policy as CORS). Null otherwise, or when the request carries no origin.
     *
     * <p>Both sides are compared in their {@linkplain #normalize normal form}: a browser never
     * writes a default port, so {@code https://picsou.example:443} in the list must still match
     * the {@code https://picsou.example} it sends.
     */
    String allowedOrigin(String origin) {
        String wanted = normalize(origin);
        CorsConfiguration policy = origins.originPolicy();
        if (wanted == null || policy == null || policy.getAllowedOrigins() == null) {
            return null;
        }
        for (String allowed : policy.getAllowedOrigins()) {
            if (wanted.equals(normalize(allowed))) {
                return wanted;
            }
        }
        return null;
    }

    /**
     * An origin with its scheme and host lower-cased, its default port (443 for HTTPS, 80 for
     * HTTP) and any trailing slash dropped. Null for a blank value, a wildcard or anything that
     * is not a {@code scheme://host[:port]} origin.
     */
    static String normalize(String origin) {
        if (origin == null || origin.isBlank() || origin.contains("*")) {
            return null;
        }
        URI parsed;
        try {
            parsed = URI.create(origin.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (parsed.getScheme() == null || parsed.getHost() == null) {
            return null;
        }
        String scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
        int port = parsed.getPort();
        boolean defaultPort = port == -1
            || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
        String path = parsed.getRawPath() == null || parsed.getRawPath().equals("/") ? "" : parsed.getRawPath();
        String rest = path
            + (parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery())
            + (parsed.getRawFragment() == null ? "" : "#" + parsed.getRawFragment());
        String userInfo = parsed.getRawUserInfo() == null ? "" : parsed.getRawUserInfo() + "@";
        return scheme + "://" + userInfo + parsed.getHost().toLowerCase(Locale.ROOT)
            + (defaultPort ? "" : ":" + port) + rest;
    }

    /**
     * {@code origin} plus the callback path, when the origin is HTTPS, or plain HTTP on a loopback
     * host for development. Null otherwise. The origin comes from {@link #allowedOrigin}, already
     * in normal form, so the redirect URI never carries a default port.
     */
    static String redirectUri(String origin) {
        URI parsed;
        try {
            parsed = URI.create(origin);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.ROOT);
        boolean secure = scheme.equals("https") || (scheme.equals("http") && LOOPBACK_HOSTS.contains(host));
        boolean bareOrigin = (parsed.getPath() == null || parsed.getPath().isEmpty() || parsed.getPath().equals("/"))
            && parsed.getQuery() == null && parsed.getUserInfo() == null && parsed.getFragment() == null;
        if (!secure || host.isEmpty() || !bareOrigin) {
            return null;
        }
        String port = parsed.getPort() == -1 ? "" : ":" + parsed.getPort();
        return scheme + "://" + host + port + LouveSyncService.CALLBACK_PATH;
    }

    private static ResponseEntity<ProblemDetail> badRequest(String code, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
        detail.setProperty("code", code);
        return ResponseEntity.badRequest().body(detail);
    }

    /** {@code iss} is required: Louve advertises RFC 9207, so a callback without it is refused. */
    record CompleteRequest(
        @NotBlank @Size(max = 2048) String code,
        @NotBlank @Size(max = 512) String state,
        @NotBlank @Size(max = 200) String iss
    ) {}
}
