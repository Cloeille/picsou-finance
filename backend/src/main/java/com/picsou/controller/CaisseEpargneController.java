package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.service.CaisseEpargneSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Caisse d'Epargne endpoints. Login is two calls ({@code /auth/initiate}, {@code /auth/complete});
 * the password is read once, handed to the service and never stored, logged or echoed. The sync is
 * on demand only.
 */
@RestController
@RequestMapping("/api/caisse-epargne")
public class CaisseEpargneController {
    static final String DELETE_MESSAGE =
        "The stored session was deleted from Picsou. The Caisse d'Epargne session itself is not "
            + "revoked and may remain active until it expires.";

    private final CaisseEpargneSyncService service;
    private final UserContext userContext;
    private final Map<String, Bucket> authBuckets;
    private final Map<String, Bucket> syncBuckets;

    public CaisseEpargneController(
        CaisseEpargneSyncService service,
        UserContext userContext,
        @Qualifier("caisseEpargneAuthBuckets") Map<String, Bucket> authBuckets,
        @Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.service = service;
        this.userContext = userContext;
        this.authBuckets = authBuckets;
        this.syncBuckets = syncBuckets;
    }

    /**
     * Starts the login. Throttled at 3 per 15 minutes per IP: a wrong password costs a bank
     * attempt. The body is validated first, so a malformed request spends neither an attempt
     * nor a browser slot. Never retried here.
     */
    @PostMapping("/auth/initiate")
    public ResponseEntity<?> initiate(@Valid @RequestBody InitiateRequest req, HttpServletRequest request) {
        ConsumptionProbe probe = consumeAuthToken(request);
        if (!probe.isConsumed()) {
            return authRateLimited(probe);
        }
        return ResponseEntity.ok(
            service.initiateAuth(req.customerId(), req.password(), userContext.currentMemberId()));
    }

    /**
     * Blocks while the customer approves the Sécur'Pass push (up to 150 s on the sidecar). Takes
     * no password and does not draw from the login bucket: it cannot spend a bank attempt.
     */
    @PostMapping("/auth/complete")
    public ResponseEntity<?> complete(@Valid @RequestBody CompleteRequest req) {
        service.completeAuth(req.processId(), userContext.currentMemberId());
        return ResponseEntity.ok(new CompleteResponse(true));
    }

    /**
     * Throttled like every other sync entry point: queueing takes a row lock and decrypts the
     * stored session. {@code queueSync} already refuses to stack jobs.
     */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        if (!consumeSyncToken(request)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many Caisse d'Epargne synchronization requests. Please wait before retrying.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }
        return ResponseEntity.accepted().body(service.queueSync(userContext.currentMemberId()));
    }

    @GetMapping("/status")
    public CaisseEpargneSyncService.SessionStatusResponse status() {
        return service.getStatus(userContext.currentMemberId());
    }

    /** Forgets the stored session in Picsou only; the answer never claims the bank session ended. */
    @DeleteMapping("/session")
    public ResponseEntity<DeleteResponse> clear() {
        boolean removed = service.clearSession(userContext.currentMemberId());
        return ResponseEntity.ok(new DeleteResponse(removed, false, DELETE_MESSAGE));
    }

    private ConsumptionProbe consumeAuthToken(HttpServletRequest request) {
        return authBuckets.computeIfAbsent(
            ClientIp.resolve(request),
            key -> RateLimitConfig.createCaisseEpargneAuthBucket()
        ).tryConsumeAndReturnRemaining(1);
    }

    private ResponseEntity<ProblemDetail> authRateLimited(ConsumptionProbe probe) {
        long seconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).plusMillis(999).toSeconds());
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail("Too many Caisse d'Epargne sign-in attempts. Please wait before retrying.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
            .body(detail);
    }

    private boolean consumeSyncToken(HttpServletRequest request) {
        return syncBuckets.computeIfAbsent(
            ClientIp.resolve(request),
            key -> RateLimitConfig.createSyncBucket()
        ).tryConsume(1);
    }

    /**
     * The password is validated as digits only (the bank's keypad has nothing else), and
     * {@code toString} redacts it so a logged or exception-wrapped DTO cannot leak it.
     */
    record InitiateRequest(
        @NotBlank @Pattern(regexp = "\\d{1,20}") String customerId,
        @NotBlank @Pattern(regexp = "\\d{4,20}") String password
    ) {
        @Override
        public String toString() {
            return "InitiateRequest[customerId=" + customerId + ", password=***]";
        }
    }

    record CompleteRequest(@NotBlank @Size(max = 100) String processId) {}

    record CompleteResponse(boolean connected) {}

    record DeleteResponse(boolean removed, boolean bankSessionRevoked, String message) {}
}
