package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.service.CicSyncService;
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
@RequestMapping("/api/cic")
public class CicController {
    private final CicSyncService service;
    private final UserContext userContext;
    private final Map<String, Bucket> authBuckets;
    private final Map<String, Bucket> completeBuckets;
    private final Map<String, Bucket> syncBuckets;

    public CicController(
        CicSyncService service,
        UserContext userContext,
        @org.springframework.beans.factory.annotation.Qualifier("cicAuthBuckets") Map<String, Bucket> authBuckets,
        @org.springframework.beans.factory.annotation.Qualifier("cicCompleteBuckets") Map<String, Bucket> completeBuckets,
        @org.springframework.beans.factory.annotation.Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.service = service;
        this.userContext = userContext;
        this.authBuckets = authBuckets;
        this.completeBuckets = completeBuckets;
        this.syncBuckets = syncBuckets;
    }

    @PostMapping("/auth/initiate")
    public ResponseEntity<?> initiate(@Valid @RequestBody InitiateRequest req, HttpServletRequest request) {
        if (!consumeAuthToken(request)) return rateLimited("Too many CIC authentication attempts. Please wait before retrying.");
        return ResponseEntity.ok(service.initiateAuth(req.login(), req.password(), userContext.currentMemberId()));
    }

    /** Logs in again from the stored credentials; the saver then approves in the app as after a typed login. */
    @PostMapping("/auth/reconnect")
    public ResponseEntity<?> reconnect(HttpServletRequest request) {
        if (!consumeAuthToken(request)) return rateLimited("Too many CIC authentication attempts. Please wait before retrying.");
        return ResponseEntity.ok(service.reconnect(userContext.currentMemberId()));
    }

    @PostMapping("/auth/complete")
    public ResponseEntity<?> complete(@Valid @RequestBody CompleteRequest req, HttpServletRequest request) {
        if (!consumeCompleteToken(request)) return rateLimited("Too many CIC authentication attempts. Please wait before retrying.");
        return ResponseEntity.ok(service.completeAuth(req.processId(), req.code(), userContext.currentMemberId()));
    }

    /**
     * Throttled like every other sync entry point: queueing takes a row lock,
     * decrypts the stored session and hands a job to the sidecar, which may log
     * in again with the stored credentials. {@code queueSync} already refuses
     * to stack jobs, but nothing otherwise stops a caller re-queueing the
     * moment each one finishes.
     */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        if (!consumeSyncToken(request)) return rateLimited("Too many CIC synchronization requests. Please wait before retrying.");
        return ResponseEntity.accepted().body(service.queueSync(userContext.currentMemberId()));
    }

    @GetMapping("/status")
    public CicSyncService.SessionStatusResponse status() { return service.getStatus(userContext.currentMemberId()); }

    @DeleteMapping("/session")
    public ResponseEntity<Void> clear() {
        service.clearSession(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    private boolean consumeAuthToken(HttpServletRequest request) {
        return authBuckets.computeIfAbsent(
            ClientIp.resolve(request),
            key -> RateLimitConfig.createCicAuthBucket()
        )
            .tryConsume(1);
    }

    private boolean consumeCompleteToken(HttpServletRequest request) {
        return completeBuckets.computeIfAbsent(
            ClientIp.resolve(request),
            key -> RateLimitConfig.createCicCompleteBucket()
        )
            .tryConsume(1);
    }

    private boolean consumeSyncToken(HttpServletRequest request) {
        return syncBuckets.computeIfAbsent(
            ClientIp.resolve(request),
            key -> RateLimitConfig.createSyncBucket()
        )
            .tryConsume(1);
    }

    private ResponseEntity<ProblemDetail> rateLimited(String message) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail(message);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }

    record InitiateRequest(
        @NotBlank @Size(max = 100) String login,
        @NotBlank @Size(max = 100) String password
    ) {}

    /**
     * {@code code} is the SMS code; absent for a mobile confirmation, which is
     * approved in the bank's app, so it is only validated when present.
     */
    record CompleteRequest(
        @NotBlank @Size(max = 100) String processId,
        @Pattern(regexp = "\\d{4,8}") String code
    ) {}
}
