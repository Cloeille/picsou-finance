package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.IbkrConnectRequest;
import com.picsou.dto.IbkrConnectionStatusResponse;
import com.picsou.service.IbkrSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Interactive Brokers Flex Web Service endpoints (Open Positions sync).
 *
 * <p>Connect once with a read-only Flex token + query id; sync pulls the current
 * open positions (end-of-day) into accounts + holdings. Mirrors
 * {@link TradeRepublicController}'s shape: connect / status / sync / clear.
 */
@RestController
@RequestMapping("/api/ibkr")
public class IbkrController {

    private final IbkrSyncService ibkrService;
    private final UserContext userContext;
    private final Map<String, Bucket> ibkrSyncBuckets;

    public IbkrController(
        IbkrSyncService ibkrService,
        UserContext userContext,
        @Qualifier("ibkrSyncBuckets") Map<String, Bucket> ibkrSyncBuckets
    ) {
        this.ibkrService = ibkrService;
        this.userContext = userContext;
        this.ibkrSyncBuckets = ibkrSyncBuckets;
    }

    /** Store (or replace) the Flex token + query id. Credentials are encrypted at rest. */
    @PostMapping("/connect")
    public ResponseEntity<Void> connect(@Valid @RequestBody IbkrConnectRequest req) {
        ibkrService.connect(req.token(), req.queryId(), userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    /** Connection status: connected?, last sync, masked token. */
    @GetMapping("/status")
    public IbkrConnectionStatusResponse getStatus() {
        return ibkrService.getConnectionStatus(userContext.currentMemberId());
    }

    /** Manual sync using the stored connection. Rate-limited per IP. */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        if (!checkSyncRateLimit(request)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many sync requests. Please wait a moment before trying again.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }
        List<AccountResponse> accounts = ibkrService.sync(userContext.currentMemberId());
        return ResponseEntity.ok(accounts);
    }

    /** Clear the stored connection (forces reconnect). */
    @DeleteMapping("/connection")
    public ResponseEntity<Void> clearConnection() {
        ibkrService.deleteConnection(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    private boolean checkSyncRateLimit(HttpServletRequest request) {
        String ip = ClientIp.resolve(request);
        Bucket bucket = ibkrSyncBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createIbkrSyncBucket());
        return bucket.tryConsume(1);
    }
}
