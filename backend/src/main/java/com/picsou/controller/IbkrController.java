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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Interactive Brokers endpoints (Open Positions sync).
 *
 * <p>Connect once with a read-only Flex token + query id; sync pulls the current
 * open positions (end-of-day) into accounts + holdings. Mirrors
 * {@link TradeRepublicController}'s shape: connect / status / sync / clear. Where the Flex Web
 * Service cannot be activated, {@code /import} takes the XML report downloaded from Client Portal
 * instead, with no connection.
 */
@RestController
@RequestMapping("/api/ibkr")
public class IbkrController {

    /** Upper bound for an uploaded Flex report. */
    static final long MAX_IMPORT_BYTES = 2L * 1024 * 1024;

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

    /**
     * Import a Flex Query XML report downloaded from Client Portal, for accounts where the Flex
     * Web Service cannot be activated. Needs no connection. Same per-IP budget as {@code /sync}.
     */
    @PostMapping("/import")
    public ResponseEntity<?> importStatement(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        // Spring has buffered the whole body by now, so every attempt spends the budget it shares with
        // /sync, a refused one included: a loop of large or empty files must not run unmetered.
        if (!checkSyncRateLimit(request)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many sync requests. Please wait a moment before trying again.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("The uploaded file is empty.");
        }
        // A real open-positions report is a few kilobytes: the global 10 MB upload limit would let
        // a loop of large files each be copied and DOM-parsed on the shared JVM.
        if (file.getSize() > MAX_IMPORT_BYTES) {
            throw new IllegalArgumentException("The uploaded file is too large for a Flex report.");
        }
        byte[] xml;
        try {
            // Raw bytes, not a String: the XML parser then detects the encoding and any byte-order mark itself.
            xml = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("The uploaded file could not be read.", e);
        }
        return ResponseEntity.ok(ibkrService.importStatement(xml, userContext.currentMemberId()));
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
