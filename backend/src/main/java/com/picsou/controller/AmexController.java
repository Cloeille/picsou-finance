package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.service.AmexSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Authenticated REST endpoints for the American Express connector. */
@RestController
@RequestMapping("/api/amex")
public class AmexController {
    private final AmexSyncService service;
    private final UserContext userContext;
    private final Map<String, Bucket> authBuckets;
    private final Map<String, Bucket> syncBuckets;

    public AmexController(
        AmexSyncService service,
        UserContext userContext,
        @Qualifier("amexAuthBuckets") Map<String, Bucket> authBuckets,
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
            service.initiateAuth(req.login(), req.password(), req.method(), userContext.currentMemberId())
        );
    }

    /** Submits the one-time code Amex sent by SMS or e-mail. */
    @PostMapping("/auth/complete")
    public ResponseEntity<?> complete(@Valid @RequestBody CompleteRequest req, HttpServletRequest request) {
        if (!consumeAuthToken(request)) return rateLimited();
        return ResponseEntity.ok(
            service.completeAuth(req.processId(), req.otp(), userContext.currentMemberId())
        );
    }

    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        if (!consumeSyncToken(request)) {
            return rateLimited("Too many American Express synchronization requests. Please wait before retrying.");
        }
        return ResponseEntity.accepted().body(service.queueSync(userContext.currentMemberId()));
    }

    @PostMapping("/history-recovery")
    public ResponseEntity<?> recoverHistory(HttpServletRequest request) {
        if (!consumeSyncToken(request)) {
            return rateLimited("Too many American Express history recovery requests. Please wait before retrying.");
        }
        return ResponseEntity.accepted().body(service.queueHistoryRecovery(userContext.currentMemberId()));
    }

    @GetMapping("/status")
    public AmexSyncService.SessionStatusResponse status() {
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
            key -> RateLimitConfig.createAmexAuthBucket()
        ).tryConsume(1);
    }

    private boolean consumeSyncToken(HttpServletRequest request) {
        return syncBuckets.computeIfAbsent(
            ClientIp.resolve(request),
            key -> RateLimitConfig.createSyncBucket()
        ).tryConsume(1);
    }

    private ResponseEntity<ProblemDetail> rateLimited() {
        return rateLimited("Too many American Express authentication attempts. Please wait before retrying.");
    }

    private ResponseEntity<ProblemDetail> rateLimited(String message) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail(message);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }

    record InitiateRequest(
        @NotBlank @Size(max = 100) String login,
        @NotBlank @Size(max = 100) String password,
        @NotBlank @Pattern(regexp = "sms|email") String method
    ) {}

    record CompleteRequest(
        @NotBlank @Size(max = 100) String processId,
        @NotBlank @Pattern(regexp = "\\d{4,10}") String otp
    ) {}
}
