package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.AccountResponse;
import com.picsou.service.TradeRepublicSyncService;
import com.picsou.port.SidecarErrorCode;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/tr")
public class TradeRepublicController {

    private final TradeRepublicSyncService trService;
    private final UserContext userContext;
    private final Map<String, Bucket>      trAuthBuckets;
    private final Map<String, Bucket>      syncBuckets;

    public TradeRepublicController(
        TradeRepublicSyncService trService,
        UserContext userContext,
        @org.springframework.beans.factory.annotation.Qualifier("trAuthBuckets") Map<String, Bucket> trAuthBuckets,
        @org.springframework.beans.factory.annotation.Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.trService     = trService;
        this.userContext   = userContext;
        this.trAuthBuckets = trAuthBuckets;
        this.syncBuckets   = syncBuckets;
    }

    /** Step 1: Sends phone+PIN (or password) to TR, which asks for the second factor. The PIN is never stored. */
    @PostMapping("/auth/initiate")
    public ResponseEntity<?> initiateAuth(
        @Valid @RequestBody InitiateAuthRequest req,
        HttpServletRequest request
    ) {
        if (!checkAuthRateLimit(request)) {
            return tooManyAttempts();
        }
        return ResponseEntity.ok(trService.initiateAuth(req.phoneNumber(), req.pin()));
    }

    /**
     * Step 1 for a returning member: only the PIN/password is asked, the phone number comes from the
     * stored session (active or expired). Completion is the same {@code /auth/complete}.
     */
    @PostMapping("/auth/reconnect")
    public ResponseEntity<?> reconnect(
        @Valid @RequestBody ReconnectRequest req,
        HttpServletRequest request
    ) {
        if (!checkAuthRateLimit(request)) {
            return tooManyAttempts();
        }
        Optional<String> phone = trService.storedPhone(userContext.currentMemberId());
        if (phone.isEmpty()) {
            ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "No phone number is stored for this Trade Republic session. Please sign in with your phone number.");
            detail.setProperty("code", SidecarErrorCode.INVALID_SESSION_STATE.name());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(detail);
        }
        return ResponseEntity.ok(trService.initiateAuth(phone.get(), req.pin()));
    }

    /**
     * Step 2: blocks while the member confirms in the Trade Republic app (up to two minutes), or
     * checks the 4-digit code or the 6-digit authenticator code. The session is stored and the first import queued.
     */
    @PostMapping("/auth/complete")
    public TradeRepublicSyncService.SessionStatusResponse completeAuth(@Valid @RequestBody CompleteAuthRequest req) {
        return trService.completeAuth(req.processId(), req.tan(), userContext.currentMemberId());
    }

    /**
     * Manual sync of the stored session: queues the import and returns where it stands. Throttled
     * like the other sync entry points, since each import opens a session on Trade Republic.
     */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        Bucket bucket = syncBuckets.computeIfAbsent(ClientIp.resolve(request), k -> RateLimitConfig.createSyncBucket());
        if (!bucket.tryConsume(1)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many Trade Republic synchronization requests. Please wait before retrying.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }
        return ResponseEntity.ok(trService.queueSync(userContext.currentMemberId()));
    }

    /** Session status: connected or not, and how the last import went. */
    @GetMapping("/status")
    public TradeRepublicSyncService.SessionStatusResponse getStatus() {
        return trService.getStatus(userContext.currentMemberId());
    }

    /** CSV fallback import (accounts/balances). */
    @PostMapping("/import")
    public List<AccountResponse> importCsv(@RequestParam("file") MultipartFile file) {
        return trService.importCsv(file, userContext.currentMemberId());
    }

    /** CSV import: parses the full TR transaction history CSV and creates double-entry transactions. */
    @PostMapping("/accounts/{id}/transactions/import-csv")
    public ResponseEntity<TradeRepublicSyncService.ImportResult> importTransactionsCsv(
        @PathVariable Long id,
        @RequestParam("file") MultipartFile file
    ) {
        TradeRepublicSyncService.ImportResult result = trService.importTransactionsCsv(id, file, userContext.currentMemberId());
        return ResponseEntity.ok(result);
    }

    /** Clear stored session token (forces re-authentication). */
    @DeleteMapping("/session")
    public ResponseEntity<Void> clearSession() {
        trService.clearSession(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    // --- Rate limiting ---

    private boolean checkAuthRateLimit(HttpServletRequest request) {
        String ip = ClientIp.resolve(request);
        Bucket bucket = trAuthBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createTrAuthBucket());
        return bucket.tryConsume(1);
    }

    private static ResponseEntity<?> tooManyAttempts() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail("Too many authentication attempts. Please wait before trying again.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }

    /** The PIN is a 4-digit code or a password: any non-empty string up to 128 characters, as the sidecar accepts. */
    record InitiateAuthRequest(
        @NotBlank @Size(max = 32) String phoneNumber,
        @NotNull @Size(min = 1, max = 128) String pin
    ) {
        @Override
        public String toString() {
            return "InitiateAuthRequest[pin=***]";
        }
    }

    record ReconnectRequest(@NotNull @Size(min = 1, max = 128) String pin) {
        @Override
        public String toString() {
            return "ReconnectRequest[pin=***]";
        }
    }

    /** {@code tan}: 4-digit code, 6-digit authenticator code, or absent for the in-app confirmation. */
    record CompleteAuthRequest(
        @NotBlank @Size(max = 100) String processId,
        @Pattern(regexp = "\\d{4}|\\d{6}") String tan
    ) {}
}
