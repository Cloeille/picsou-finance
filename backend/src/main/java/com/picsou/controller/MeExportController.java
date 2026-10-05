package com.picsou.controller;

import com.picsou.config.RateLimitConfig;
import com.picsou.dto.ExportRequest;
import com.picsou.export.DataExportService;
import com.picsou.export.ExportContext;
import com.picsou.model.AppUser;
import com.picsou.service.ReAuthService;
import com.picsou.util.LogSanitizer;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.Map;

/**
 * GDPR self-service data export — streams the authenticated user's full graph
 * as a ZIP (JSON + CSV). Re-authentication required: TOTP if MFA is enabled,
 * password otherwise. Throttled per-user to 5/hour.
 */
@RestController
@RequestMapping("/api/me")
public class MeExportController {

    private static final Logger log = LoggerFactory.getLogger(MeExportController.class);

    private final DataExportService dataExportService;
    private final ReAuthService reAuthService;
    private final Map<String, Bucket> exportBuckets;

    public MeExportController(
        DataExportService dataExportService,
        ReAuthService reAuthService,
        @Qualifier("exportBuckets") Map<String, Bucket> exportBuckets
    ) {
        this.dataExportService = dataExportService;
        this.reAuthService = reAuthService;
        this.exportBuckets = exportBuckets;
    }

    @PostMapping(value = "/export", produces = "application/zip")
    public ResponseEntity<StreamingResponseBody> export(
        @AuthenticationPrincipal AppUser user,
        @Valid @RequestBody ExportRequest req,
        HttpServletRequest httpReq
    ) {
        String key = String.valueOf(user.getId());
        Bucket bucket = exportBuckets.computeIfAbsent(key, k -> RateLimitConfig.createExportBucket());
        if (!bucket.tryConsume(1)) {
            log.warn("export.rate_limited userId={} ip={}", user.getId(), httpReq.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        reAuthService.verify(user, req.reAuth());

        log.warn("export.requested userId={} username={} ip={} includeBalanceSnapshots={}",
            user.getId(), user.getUsername(), LogSanitizer.safe(httpReq.getRemoteAddr()), req.includeBalanceSnapshots());

        ExportContext ctx = new ExportContext(req.includeBalanceSnapshots());
        String filename = "picsou-export-" + user.getUsername() + "-" + filenameTimestamp() + ".zip";
        StreamingResponseBody body = out -> dataExportService.export(user, ctx, out);

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(body);
    }

    private static String filenameTimestamp() {
        return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now());
    }
}
