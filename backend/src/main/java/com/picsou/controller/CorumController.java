package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.service.CorumSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/corum")
public class CorumController {
    private final CorumSyncService service;
    private final UserContext userContext;
    private final Map<String, Bucket> authBuckets;
    private final Map<String, Bucket> syncBuckets;

    public CorumController(
        CorumSyncService service,
        UserContext userContext,
        @org.springframework.beans.factory.annotation.Qualifier("corumAuthBuckets") Map<String, Bucket> authBuckets,
        @org.springframework.beans.factory.annotation.Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.service = service;
        this.userContext = userContext;
        this.authBuckets = authBuckets;
        this.syncBuckets = syncBuckets;
    }

    /**
     * CORUM asks for no second factor, so this is the whole exchange: the
     * response already carries the sync status because the import is queued
     * here rather than run inline.
     */
    @PostMapping("/auth")
    public ResponseEntity<?> authenticate(@Valid @RequestBody AuthenticateRequest req, HttpServletRequest request) {
        if (!consumeAuthToken(request)) {
            return rateLimited("Too many CORUM authentication attempts. Please wait before retrying.");
        }
        return ResponseEntity.ok(
            service.authenticate(req.login(), req.password(), userContext.currentMemberId())
        );
    }

    /**
     * Throttled like every other sync entry point: queueing takes a row lock,
     * decrypts the stored session and can hand a browser-backed job to the
     * sidecar. {@code queueSync} already refuses to stack jobs, but nothing
     * otherwise stops a caller re-queueing the moment each one finishes.
     */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        if (!consumeSyncToken(request)) {
            return rateLimited("Too many CORUM synchronization requests. Please wait before retrying.");
        }
        return ResponseEntity.accepted().body(service.queueSync(userContext.currentMemberId()));
    }

    @GetMapping("/status")
    public CorumSyncService.SessionStatusResponse status() {
        return service.getStatus(userContext.currentMemberId());
    }

    @DeleteMapping("/session")
    public ResponseEntity<Void> clear() {
        service.clearSession(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    private boolean consumeAuthToken(HttpServletRequest request) {
        return authBuckets.computeIfAbsent(
            ClientIp.resolve(request),
            key -> RateLimitConfig.createCorumAuthBucket()
        ).tryConsume(1);
    }

    private boolean consumeSyncToken(HttpServletRequest request) {
        return syncBuckets.computeIfAbsent(
            ClientIp.resolve(request),
            key -> RateLimitConfig.createSyncBucket()
        ).tryConsume(1);
    }

    private ResponseEntity<ProblemDetail> rateLimited(String message) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail(message);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }

    /** {@code login} is a CORUM client id, not an email address. */
    record AuthenticateRequest(
        @NotBlank @Size(max = 100) String login,
        @NotBlank @Size(max = 100) String password
    ) {}
}
