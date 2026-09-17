package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.service.FortuneoSyncService;
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

/** Authenticated REST endpoints for the unofficial, read-only Fortuneo connector. */
@RestController
@RequestMapping("/api/fortuneo")
public class FortuneoController {
    private final FortuneoSyncService service;
    private final UserContext userContext;
    private final Map<String, Bucket> authBuckets;

    public FortuneoController(
        FortuneoSyncService service,
        UserContext userContext,
        @org.springframework.beans.factory.annotation.Qualifier("fortuneoAuthBuckets") Map<String, Bucket> authBuckets
    ) {
        this.service = service;
        this.userContext = userContext;
        this.authBuckets = authBuckets;
    }

    /** Starts Fortuneo authentication, subject to the per-IP authentication rate limit. */
    @PostMapping("/auth/initiate")
    public ResponseEntity<?> initiate(@Valid @RequestBody InitiateRequest req, HttpServletRequest request) {
        if (!consumeAuthToken(request)) return rateLimited();
        return ResponseEntity.ok(service.initiateAuth(req.login(), req.password(), userContext.currentMemberId()));
    }

    /** Completes a pending six-digit MFA challenge and queues the first synchronization. */
    @PostMapping("/auth/complete")
    public ResponseEntity<?> complete(@Valid @RequestBody CompleteRequest req, HttpServletRequest request) {
        if (!consumeAuthToken(request)) return rateLimited();
        return ResponseEntity.ok(service.completeAuth(req.processId(), req.code(), userContext.currentMemberId()));
    }

    /** Queues a synchronization and returns its persisted status without waiting for portfolio I/O. */
    @PostMapping("/sync")
    public ResponseEntity<FortuneoSyncService.SessionStatusResponse> sync() {
        return ResponseEntity.accepted().body(service.queueSync(userContext.currentMemberId()));
    }

    /** Returns the current member's connection and synchronization status. */
    @GetMapping("/status")
    public FortuneoSyncService.SessionStatusResponse status() {
        return service.getStatus(userContext.currentMemberId());
    }

    /** Deletes provider session state while retaining previously imported accounts and history. */
    @DeleteMapping("/session")
    public ResponseEntity<Void> clear() {
        service.clearSession(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    private boolean consumeAuthToken(HttpServletRequest request) {
        return authBuckets.computeIfAbsent(
            ClientIp.resolve(request),
            key -> RateLimitConfig.createFortuneoAuthBucket()
        )
            .tryConsume(1);
    }

    private ResponseEntity<ProblemDetail> rateLimited() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail("Too many Fortuneo authentication attempts. Please wait before retrying.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }

    /** Validated credentials accepted only for immediate forwarding to the isolated sidecar. */
    record InitiateRequest(
        @NotBlank @Size(max = 100) String login,
        @NotBlank @Size(max = 100) String password
    ) {}
    /** Validated pending-process identifier and six-digit MFA code. */
    record CompleteRequest(
        @NotBlank @Size(max = 100) String processId,
        @NotBlank @Pattern(regexp = "\\d{6}") String code
    ) {}
}
