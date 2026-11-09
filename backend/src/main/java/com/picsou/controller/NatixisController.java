package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.service.NatixisSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/natixis")
public class NatixisController {
    private static final String TOO_MANY_AUTH =
        "Too many Natixis Interépargne authentication attempts. Please wait before retrying.";

    private final NatixisSyncService service;
    private final UserContext userContext;
    private final Map<String, Bucket> authBuckets;
    private final Map<String, Bucket> completeBuckets;
    private final Map<String, Bucket> syncBuckets;

    public NatixisController(
        NatixisSyncService service,
        UserContext userContext,
        @Qualifier("natixisAuthBuckets") Map<String, Bucket> authBuckets,
        @Qualifier("natixisCompleteBuckets") Map<String, Bucket> completeBuckets,
        @Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.service = service;
        this.userContext = userContext;
        this.authBuckets = authBuckets;
        this.completeBuckets = completeBuckets;
        this.syncBuckets = syncBuckets;
    }

    @PostMapping("/auth/initiate")
    public ResponseEntity<?> initiate(@Valid @RequestBody InitiateRequest req, HttpServletRequest request) {
        if (!consume(authBuckets, request, RateLimitConfig::createNatixisAuthBucket)) return rateLimited(TOO_MANY_AUTH);
        return ResponseEntity.ok(service.initiateAuth(req.email(), req.password(), userContext.currentMemberId()));
    }

    @PostMapping("/auth/complete")
    public ResponseEntity<?> complete(@Valid @RequestBody CompleteRequest req, HttpServletRequest request) {
        if (!consume(completeBuckets, request, RateLimitConfig::createNatixisCompleteBucket)) return rateLimited(TOO_MANY_AUTH);
        return ResponseEntity.ok(service.completeAuth(req.processId(), req.code(), userContext.currentMemberId()));
    }

    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        if (!consume(syncBuckets, request, RateLimitConfig::createSyncBucket)) {
            return rateLimited("Too many Natixis Interépargne synchronization requests. Please wait before retrying.");
        }
        return ResponseEntity.accepted().body(service.queueSync(userContext.currentMemberId()));
    }

    @GetMapping("/status")
    public NatixisSyncService.SessionStatusResponse status() { return service.getStatus(userContext.currentMemberId()); }

    @DeleteMapping("/session")
    public ResponseEntity<Void> clear() {
        service.clearSession(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    private static boolean consume(Map<String, Bucket> buckets, HttpServletRequest request,
                                   java.util.function.Supplier<Bucket> factory) {
        return buckets.computeIfAbsent(ClientIp.resolve(request), key -> factory.get()).tryConsume(1);
    }

    private ResponseEntity<ProblemDetail> rateLimited(String message) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail(message);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }

    /** Matches the sidecar's own bounds, so a value it would refuse never leaves the backend. */
    record InitiateRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 100) String password
    ) {}

    /** {@code code} is the SMS, voice or e-mail code; Natixis has no mobile confirmation. */
    record CompleteRequest(
        @NotBlank @Size(max = 100) String processId,
        @NotNull @Pattern(regexp = "\\d{4,12}") String code
    ) {}
}
