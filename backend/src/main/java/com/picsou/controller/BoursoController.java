package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.service.BoursoSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/bourso")
public class BoursoController {
    private final BoursoSyncService service;
    private final UserContext userContext;
    private final Map<String, Bucket> authBuckets;
    private final Map<String, Bucket> syncBuckets;

    public BoursoController(
        BoursoSyncService service,
        UserContext userContext,
        @Qualifier("boursoAuthBuckets") Map<String, Bucket> authBuckets,
        @Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.service = service;
        this.userContext = userContext;
        this.authBuckets = authBuckets;
        this.syncBuckets = syncBuckets;
    }

    @PostMapping("/auth/initiate")
    public ResponseEntity<?> initiate(@Valid @RequestBody InitiateRequest req, HttpServletRequest request) {
        if (!consumeAuthToken(request)) return rateLimited();
        return ResponseEntity.ok(
            service.initiateAuth(req.customerId(), req.password(), userContext.currentMemberId())
        );
    }

    /**
     * Blocks while the customer approves the push on their phone — up to two
     * minutes. There is no code to submit: BoursoBank's app validation is the
     * only second factor this connector drives.
     */
    @PostMapping("/auth/complete")
    public ResponseEntity<?> complete(@Valid @RequestBody CompleteRequest req, HttpServletRequest request) {
        if (!consumeAuthToken(request)) return rateLimited();
        return ResponseEntity.ok(service.completeAuth(req.processId(), userContext.currentMemberId()));
    }

    /**
     * Throttled like every other sync entry point: queueing takes a row lock and
     * decrypts the stored session. {@code queueSync} already refuses to stack
     * jobs, but nothing otherwise stops a caller re-queueing the moment each one
     * finishes.
     */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        if (!consumeSyncToken(request)) {
            return rateLimited("Too many BoursoBank synchronization requests. Please wait before retrying.");
        }
        return ResponseEntity.accepted().body(service.queueSync(userContext.currentMemberId()));
    }

    @GetMapping("/status")
    public BoursoSyncService.SessionStatusResponse status() {
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
            key -> RateLimitConfig.createBoursoAuthBucket()
        ).tryConsume(1);
    }

    private boolean consumeSyncToken(HttpServletRequest request) {
        return syncBuckets.computeIfAbsent(
            ClientIp.resolve(request),
            key -> RateLimitConfig.createSyncBucket()
        ).tryConsume(1);
    }

    private ResponseEntity<ProblemDetail> rateLimited() {
        return rateLimited("Too many BoursoBank authentication attempts. Please wait before retrying.");
    }

    private ResponseEntity<ProblemDetail> rateLimited(String message) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail(message);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }

    record InitiateRequest(
        @NotBlank @Size(max = 100) String customerId,
        @NotBlank @Size(max = 100) String password
    ) {}

    record CompleteRequest(@NotBlank @Size(max = 100) String processId) {}
}
