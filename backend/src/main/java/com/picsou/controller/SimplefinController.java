package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.SimplefinConnectRequest;
import com.picsou.dto.SimplefinConnectionStatusResponse;
import com.picsou.service.SimplefinSyncService;
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
 * SimpleFIN endpoints. Connect once with a setup token; sync pulls balances and
 * posted transactions. Mirrors {@link IbkrController}.
 */
@RestController
@RequestMapping("/api/simplefin")
public class SimplefinController {

    private final SimplefinSyncService simplefinService;
    private final UserContext userContext;
    private final Map<String, Bucket> simplefinRequestBuckets;

    public SimplefinController(
        SimplefinSyncService simplefinService,
        UserContext userContext,
        @Qualifier("simplefinRequestBuckets") Map<String, Bucket> simplefinRequestBuckets
    ) {
        this.simplefinService = simplefinService;
        this.userContext = userContext;
        this.simplefinRequestBuckets = simplefinRequestBuckets;
    }

    /** Claim a setup token and store the access URL. Rate-limited: the token is single-use. */
    @PostMapping("/connect")
    public ResponseEntity<?> connect(@Valid @RequestBody SimplefinConnectRequest req, HttpServletRequest request) {
        if (!checkRateLimit(request)) return tooManyRequests();
        simplefinService.connect(req.token(), userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    /** Connection status: connected?, last sync, masked username. */
    @GetMapping("/status")
    public SimplefinConnectionStatusResponse getStatus() {
        return simplefinService.getConnectionStatus(userContext.currentMemberId());
    }

    /** Manual sync using the stored access URL. Shares the per-IP limit with connect. */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        if (!checkRateLimit(request)) return tooManyRequests();
        List<AccountResponse> accounts = simplefinService.sync(userContext.currentMemberId());
        return ResponseEntity.ok(accounts);
    }

    /** Clear the stored connection. Imported accounts stay. */
    @DeleteMapping("/connection")
    public ResponseEntity<Void> clearConnection() {
        simplefinService.deleteConnection(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    private boolean checkRateLimit(HttpServletRequest request) {
        String ip = ClientIp.resolve(request);
        Bucket bucket = simplefinRequestBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createSimplefinRequestBucket());
        return bucket.tryConsume(1);
    }

    private static ResponseEntity<ProblemDetail> tooManyRequests() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail("Too many SimpleFIN requests. Please wait a moment before trying again.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }
}
