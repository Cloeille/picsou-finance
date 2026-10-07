package com.picsou.controller;

import com.picsou.config.RateLimitConfig;
import com.picsou.dto.SyncProgress;
import com.picsou.service.RevolutSyncService;
import com.picsou.service.UserContext;
import com.picsou.service.sync.SyncProvider;
import com.picsou.service.sync.SyncProgressService;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Manual on-demand Revolut sync is a background job so the frontend can stream live progress
 * (phase / mobile-approval countdown / accounts-found) instead of blocking on one long request:
 * {@code POST /sync} kicks off {@link RevolutSyncService#discover} on the {@code revolutSyncExecutor}
 * and returns {@code 202} with the initial {@link SyncProgress}; the frontend polls
 * {@code GET /sync/progress}; once discovery is done the member picks which accounts to import and
 * {@code POST /sync/confirm} persists just that subset. The scheduler's unattended resync stays on
 * the synchronous {@link RevolutSyncService#sync} path (auto-imports everything).
 */
@RestController
@RequestMapping("/api/revolut")
public class RevolutController {

    private final RevolutSyncService  revolutService;
    private final UserContext         userContext;
    private final SyncProgressService progressService;
    private final Executor            revolutSyncExecutor;
    private final Map<String, Bucket> revolutAuthBuckets;

    public RevolutController(
        RevolutSyncService revolutService,
        UserContext userContext,
        SyncProgressService progressService,
        @Qualifier("revolutSyncExecutor") Executor revolutSyncExecutor,
        @Qualifier("revolutAuthBuckets") Map<String, Bucket> revolutAuthBuckets
    ) {
        this.revolutService      = revolutService;
        this.userContext         = userContext;
        this.progressService     = progressService;
        this.revolutSyncExecutor = revolutSyncExecutor;
        this.revolutAuthBuckets  = revolutAuthBuckets;
    }

    /**
     * Starts an on-demand discovery in the background (blank phoneNumber/passcode falls back to
     * remembered credentials) and returns {@code 202} + the initial progress. A discovery already
     * running for this member just returns its current progress. Rate-limited per IP.
     */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(@RequestBody SyncRequest req, HttpServletRequest request) {
        if (!checkAuthRateLimit(request)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many sync attempts. Please wait before trying again.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }
        Long memberId = userContext.currentMemberId();
        if (progressService.startIfIdle(memberId, SyncProvider.REVOLUT)) {
            revolutSyncExecutor.execute(() -> revolutService.discover(memberId, req.phoneNumber(), req.passcode()));
        }
        return ResponseEntity.accepted().body(progressService.status(memberId, SyncProvider.REVOLUT));
    }

    /** Live progress of the in-flight discovery (polled by the frontend). */
    @GetMapping("/sync/progress")
    public SyncProgress syncProgress() {
        return progressService.status(userContext.currentMemberId(), SyncProvider.REVOLUT);
    }

    /**
     * Persists the subset of the last discovery the member selected; {@code remember} opts into
     * stored creds. {@code voluntary} must be true for an explicit Add-account re-selection (lifts
     * soft-delete tombstones for the selected accounts) and false for an auto-sync confirm (leaves
     * tombstones alone, so a trash-deleted account stays deleted).
     */
    @PostMapping("/sync/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmSync(@RequestBody ConfirmSyncRequest req) {
        revolutService.confirmSync(
            userContext.currentMemberId(), req.selectedExternalIds(), req.remember(), req.voluntary());
    }

    /** Connection status: are credentials remembered, and when did we last sync? */
    @GetMapping("/status")
    public RevolutSyncService.StatusResponse getStatus() {
        return revolutService.getStatus(userContext.currentMemberId());
    }

    /** Forgets any remembered credentials (accounts already synced are left untouched). */
    @DeleteMapping("/session")
    public ResponseEntity<Void> disconnect() {
        revolutService.disconnect(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    // --- Rate limiting ---

    private boolean checkAuthRateLimit(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        Bucket bucket = revolutAuthBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createRevolutAuthBucket());
        return bucket.tryConsume(1);
    }

    record SyncRequest(String phoneNumber, String passcode) {}

    record ConfirmSyncRequest(List<String> selectedExternalIds, boolean remember, boolean voluntary) {}
}
