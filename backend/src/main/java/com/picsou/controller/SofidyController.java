package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.service.SofidySyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/sofidy")
public class SofidyController {
    private final SofidySyncService service;
    private final UserContext userContext;
    private final Map<String, Bucket> authBuckets;
    private final Map<String, Bucket> syncBuckets;

    public SofidyController(
        SofidySyncService service,
        UserContext userContext,
        @org.springframework.beans.factory.annotation.Qualifier("sofidyAuthBuckets") Map<String, Bucket> authBuckets,
        @org.springframework.beans.factory.annotation.Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.service = service;
        this.userContext = userContext;
        this.authBuckets = authBuckets;
        this.syncBuckets = syncBuckets;
    }

    /**
     * Sofidy always asks for a verification code by e-mail, so this returns a
     * process id and no session: {@code /auth/complete} is what opens one.
     */
    @PostMapping("/auth/initiate")
    public ResponseEntity<?> initiate(@Valid @RequestBody InitiateRequest req, HttpServletRequest request) {
        if (!consumeAuthToken(request)) {
            return rateLimited("Too many Sofidy authentication attempts. Please wait before retrying.");
        }
        return ResponseEntity.ok(
            service.initiateAuth(req.associateCode(), req.password(), userContext.currentMemberId())
        );
    }

    @PostMapping("/auth/complete")
    public ResponseEntity<?> complete(@Valid @RequestBody CompleteRequest req, HttpServletRequest request) {
        if (!consumeAuthToken(request)) {
            return rateLimited("Too many Sofidy authentication attempts. Please wait before retrying.");
        }
        return ResponseEntity.ok(
            service.completeAuth(req.processId(), req.code(), userContext.currentMemberId())
        );
    }

    /**
     * Throttled like every other sync entry point: queueing takes a row lock,
     * decrypts the stored session and can hand a remote-read job to the sidecar.
     * {@code queueSync} already refuses to stack jobs, but nothing otherwise
     * stops a caller re-queueing the moment each one finishes.
     */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        if (!consumeSyncToken(request)) {
            return rateLimited("Too many Sofidy synchronization requests. Please wait before retrying.");
        }
        return ResponseEntity.accepted().body(service.queueSync(userContext.currentMemberId()));
    }

    @GetMapping("/status")
    public SofidySyncService.SessionStatusResponse status() {
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
            key -> RateLimitConfig.createSofidyAuthBucket()
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

    /**
     * {@code associateCode} is the six-digit code printed on Sofidy's paper mail,
     * not an email address, so the pattern is the portal's own: six digits.
     */
    record InitiateRequest(
        @NotBlank @Pattern(regexp = "\\d{6}") String associateCode,
        @NotBlank @Size(max = 100) String password
    ) {}

    record CompleteRequest(
        @NotBlank @Size(max = 100) String processId,
        @NotBlank @Pattern(regexp = "\\d{6}") String code
    ) {}
}
