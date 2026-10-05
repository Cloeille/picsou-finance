package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.AccountResponse;
import com.picsou.model.Requisition;
import com.picsou.port.BankConnectorPort;
import com.picsou.service.SyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncService syncService;
    private final UserContext userContext;
    private final Map<String, Bucket> syncBuckets;

    public SyncController(
        SyncService syncService,
        UserContext userContext,
        @org.springframework.beans.factory.annotation.Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.syncService = syncService;
        this.userContext = userContext;
        this.syncBuckets = syncBuckets;
    }

    @GetMapping("/institutions")
    public List<BankConnectorPort.InstitutionData> searchInstitutions(
        @RequestParam(required = false, defaultValue = "") String query,
        @RequestParam(required = false, defaultValue = BankConnectorPort.DEFAULT_COUNTRY) String country
    ) {
        return syncService.searchInstitutions(query, country);
    }

    @GetMapping("/countries")
    public ResponseEntity<?> listCountries(HttpServletRequest httpReq) {
        if (!checkSyncRateLimit(httpReq, "countries")) {
            return tooManyRequests();
        }
        return ResponseEntity.ok(syncService.listCountries());
    }

    @PostMapping("/initiate")
    public ResponseEntity<?> initiate(
        @RequestBody @Valid InitiateRequest req,
        HttpServletRequest httpReq
    ) {
        if (!checkSyncRateLimit(httpReq, "initiate")) {
            return tooManyRequests();
        }

        SyncService.InitiateResponse response = syncService.initiateConnection(
            req.institutionId(),
            req.institutionName(),
            userContext.currentMemberId()
        );
        return ResponseEntity.ok(response);
    }

    /** OAuth callback completion — rate-limited on its own bucket like initiate/reconnect (exchangeCode + fetchBalances hit the provider). */
    @GetMapping("/complete")
    public ResponseEntity<?> complete(
        @RequestParam String code,
        @RequestParam(required = false) String state,
        HttpServletRequest httpReq
    ) {
        if (!checkSyncRateLimit(httpReq, "complete")) {
            return tooManyRequests();
        }
        return ResponseEntity.ok(syncService.completeConnection(code, state, userContext.currentMemberId()));
    }

    @GetMapping("/status")
    public List<Requisition> getStatus() {
        return syncService.getAllRequisitions(userContext.currentMemberId());
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable Long id) {
        List<AccountResponse> accounts = syncService.retrySync(id, userContext.currentMemberId());
        return ResponseEntity.ok(accounts);
    }

    /** Re-initiate the OAuth flow for a dead requisition (rate-limited: triggers an outbound EB auth call). */
    @PostMapping("/{id}/reconnect")
    public ResponseEntity<?> reconnect(@PathVariable Long id, HttpServletRequest httpReq) {
        if (!checkSyncRateLimit(httpReq, "reconnect")) {
            return tooManyRequests();
        }
        return ResponseEntity.ok(syncService.reconnect(id, userContext.currentMemberId()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRequisition(@PathVariable Long id) {
        syncService.deleteRequisition(id, userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Each endpoint gets its own bucket (keyed by ip + endpoint name) rather than sharing
     * one per-IP budget — otherwise a passive, auto-fetched read (e.g. the country picker
     * populating on every "Add Account" open) could exhaust the budget meant for an explicit
     * user action like initiating a bank connection.
     */
    private boolean checkSyncRateLimit(HttpServletRequest request, String bucketKey) {
        String key = ClientIp.resolve(request) + ":" + bucketKey;
        Bucket bucket = syncBuckets.computeIfAbsent(key, k -> RateLimitConfig.createSyncBucket());
        return bucket.tryConsume(1);
    }

    private static ResponseEntity<ProblemDetail> tooManyRequests() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail("Too many sync requests. Please wait a moment.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }

    record InitiateRequest(@NotBlank String institutionId, @NotBlank String institutionName) {}
}
