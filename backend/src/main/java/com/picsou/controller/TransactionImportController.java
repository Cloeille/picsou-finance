package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.TransactionImportPreviewResponse;
import com.picsou.dto.TransactionImportRequest;
import com.picsou.dto.TransactionImportResultResponse;
import com.picsou.service.TransactionImportService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Two-phase CSV import of investment transactions into a single account. Both endpoints are
 * member-scoped (the service resolves the account by member) and IP-throttled with the shared
 * sync buckets to bound upload abuse.
 */
@RestController
@RequestMapping("/api/accounts/{id}/transactions/import")
public class TransactionImportController {

    private final TransactionImportService importService;
    private final UserContext userContext;
    private final Map<String, Bucket> syncBuckets;

    public TransactionImportController(
        TransactionImportService importService,
        UserContext userContext,
        @Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.importService = importService;
        this.userContext = userContext;
        this.syncBuckets = syncBuckets;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> preview(
        @PathVariable Long id,
        @RequestParam("file") MultipartFile file,
        HttpServletRequest request
    ) {
        if (!checkRateLimit(request)) {
            return tooManyRequests();
        }
        TransactionImportPreviewResponse preview =
            importService.preview(id, userContext.currentMemberId(), file);
        return ResponseEntity.ok(preview);
    }

    @PostMapping
    public ResponseEntity<?> execute(
        @PathVariable Long id,
        @Valid @RequestBody TransactionImportRequest req,
        HttpServletRequest request
    ) {
        if (!checkRateLimit(request)) {
            return tooManyRequests();
        }
        TransactionImportResultResponse result =
            importService.executeImport(id, userContext.currentMemberId(), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    private boolean checkRateLimit(HttpServletRequest request) {
        String ip = ClientIp.resolve(request);
        Bucket bucket = syncBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createSyncBucket());
        return bucket.tryConsume(1);
    }

    private static ResponseEntity<ProblemDetail> tooManyRequests() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail("Too many import requests. Please wait a moment.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }
}
