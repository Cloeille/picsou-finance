package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.ActualBudgetImportDtos.Preview;
import com.picsou.dto.ActualBudgetImportDtos.Request;
import com.picsou.dto.ActualBudgetImportDtos.Result;
import com.picsou.service.ActualBudgetImportService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Two-phase Actual Budget import. Both endpoints are member-scoped (the preview token is bound to
 * the member) and IP-throttled with the shared sync buckets to bound upload abuse.
 */
@RestController
@RequestMapping("/api/actual/import")
public class ActualBudgetImportController {

    private final ActualBudgetImportService importService;
    private final UserContext userContext;
    private final Map<String, Bucket> syncBuckets;

    public ActualBudgetImportController(
        ActualBudgetImportService importService,
        UserContext userContext,
        @Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.importService = importService;
        this.userContext = userContext;
        this.syncBuckets = syncBuckets;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> preview(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        if (!checkRateLimit(request)) {
            return tooManyRequests();
        }
        Preview preview = importService.preview(file, userContext.currentMemberId());
        return ResponseEntity.ok(preview);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> execute(@Valid @RequestBody Request body, HttpServletRequest request) {
        if (!checkRateLimit(request)) {
            return tooManyRequests();
        }
        Result result = importService.executeImport(body, userContext.currentMemberId());
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
