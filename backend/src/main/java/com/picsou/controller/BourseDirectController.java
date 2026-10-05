package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.service.BourseDirectSyncService;
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
@RequestMapping("/api/bourse-direct")
public class BourseDirectController {
    private final BourseDirectSyncService service;
    private final UserContext userContext;
    private final Map<String, Bucket> authBuckets;

    public BourseDirectController(
        BourseDirectSyncService service,
        UserContext userContext,
        @org.springframework.beans.factory.annotation.Qualifier("bourseDirectAuthBuckets") Map<String, Bucket> authBuckets
    ) {
        this.service = service;
        this.userContext = userContext;
        this.authBuckets = authBuckets;
    }

    @PostMapping("/auth/initiate")
    public ResponseEntity<?> initiate(@Valid @RequestBody InitiateRequest req, HttpServletRequest request) {
        if (!consumeAuthToken(request)) return rateLimited();
        return ResponseEntity.ok(service.initiateAuth(req.login(), req.password(), userContext.currentMemberId()));
    }

    @PostMapping("/auth/complete")
    public ResponseEntity<?> complete(@Valid @RequestBody CompleteRequest req, HttpServletRequest request) {
        if (!consumeAuthToken(request)) return rateLimited();
        return ResponseEntity.ok(service.completeAuth(req.processId(), req.code(), userContext.currentMemberId()));
    }

    @PostMapping("/sync")
    public ResponseEntity<BourseDirectSyncService.SessionStatusResponse> sync() {
        return ResponseEntity.accepted().body(service.queueSync(userContext.currentMemberId()));
    }

    @GetMapping("/status")
    public BourseDirectSyncService.SessionStatusResponse status() { return service.getStatus(userContext.currentMemberId()); }

    @DeleteMapping("/session")
    public ResponseEntity<Void> clear() {
        service.clearSession(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    private boolean consumeAuthToken(HttpServletRequest request) {
        return authBuckets.computeIfAbsent(
            ClientIp.resolve(request),
            key -> RateLimitConfig.createBourseDirectAuthBucket()
        )
            .tryConsume(1);
    }

    private ResponseEntity<ProblemDetail> rateLimited() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail("Too many Bourse Direct authentication attempts. Please wait before retrying.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }

    record InitiateRequest(
        @NotBlank @Size(max = 100) String login,
        @NotBlank @Size(max = 100) String password
    ) {}
    record CompleteRequest(
        @NotBlank @Size(max = 100) String processId,
        @NotBlank @Pattern(regexp = "\\d{6}") String code
    ) {}
}
