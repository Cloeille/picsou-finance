package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.WiseConnectRequest;
import com.picsou.dto.WiseConnectionStatusResponse;
import com.picsou.service.UserContext;
import com.picsou.service.WiseSyncService;
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

/** Wise jars: connect once with a personal API token, then sync refreshes the jar balances. */
@RestController
@RequestMapping("/api/wise")
public class WiseController {

    private final WiseSyncService wiseService;
    private final UserContext userContext;
    private final Map<String, Bucket> wiseRequestBuckets;

    public WiseController(
        WiseSyncService wiseService,
        UserContext userContext,
        @Qualifier("wiseRequestBuckets") Map<String, Bucket> wiseRequestBuckets
    ) {
        this.wiseService = wiseService;
        this.userContext = userContext;
        this.wiseRequestBuckets = wiseRequestBuckets;
    }

    /** Check the token against Wise, store it and import the jars. */
    @PostMapping("/connect")
    public ResponseEntity<?> connect(@Valid @RequestBody WiseConnectRequest req, HttpServletRequest request) {
        if (!checkRateLimit(request)) return tooManyRequests();
        List<AccountResponse> accounts = wiseService.connect(req.token(), userContext.currentMemberId());
        return ResponseEntity.ok(accounts);
    }

    @GetMapping("/status")
    public WiseConnectionStatusResponse getStatus() {
        return wiseService.getConnectionStatus(userContext.currentMemberId());
    }

    /** Manual sync with the stored token. Shares the per-IP limit with connect. */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        if (!checkRateLimit(request)) return tooManyRequests();
        return ResponseEntity.ok(wiseService.sync(userContext.currentMemberId()));
    }

    /** Forget the stored token. Imported jars stay. */
    @DeleteMapping("/connection")
    public ResponseEntity<Void> clearConnection() {
        wiseService.deleteConnection(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    private boolean checkRateLimit(HttpServletRequest request) {
        String ip = ClientIp.resolve(request);
        Bucket bucket = wiseRequestBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createWiseRequestBucket());
        return bucket.tryConsume(1);
    }

    private static ResponseEntity<ProblemDetail> tooManyRequests() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail("Too many Wise requests. Please wait a moment before trying again.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }
}
