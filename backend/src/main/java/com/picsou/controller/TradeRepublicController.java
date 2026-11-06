package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.AccountResponse;
import com.picsou.service.TradeRepublicSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

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

    /** Step 1: Sends phone+PIN to TR, triggers 2FA SMS. Credentials are never stored. */
    @PostMapping("/auth/initiate")
    public ResponseEntity<?> initiateAuth(
        @RequestBody InitiateAuthRequest req,
        HttpServletRequest request
    ) {
        if (!checkAuthRateLimit(request)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many authentication attempts. Please wait before trying again.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }
        return ResponseEntity.ok(trService.initiateAuth(req.phoneNumber(), req.pin()));
    }

    /**
     * Step 2: blocks while the member confirms in the Trade Republic app (up to two minutes), or
     * checks the 4-digit code of the fallback login. The session is stored and the first import queued.
     */
    @PostMapping("/auth/complete")
    public TradeRepublicSyncService.SessionStatusResponse completeAuth(@RequestBody CompleteAuthRequest req) {
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

    record InitiateAuthRequest(String phoneNumber, String pin) {}

    record CompleteAuthRequest(String processId, String tan) {}
}
