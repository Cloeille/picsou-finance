package com.picsou.controller;

import com.picsou.config.RateLimitConfig;
import com.picsou.dto.AccountsExportRequest;
import com.picsou.export.xlsx.AccountsWorkbookService;
import com.picsou.export.xlsx.SheetLabels;
import com.picsou.model.AppUser;
import com.picsou.service.UserContext;
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

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Spreadsheet export of a chosen set of accounts — one sheet each, positions and property /
 * loan detail on it, so the figures can be analysed outside Picsou.
 *
 * <p>No re-authentication, unlike {@link MeExportController}: this is a subset the user picks
 * from their own account list during ordinary use, not a full personal-data dump. It is throttled
 * per user all the same.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountExportController {

    private static final String XLSX_MIME =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final Logger log = LoggerFactory.getLogger(AccountExportController.class);

    private final AccountsWorkbookService workbookService;
    private final UserContext userContext;
    private final Map<String, Bucket> accountExportBuckets;

    public AccountExportController(
        AccountsWorkbookService workbookService,
        UserContext userContext,
        @Qualifier("accountExportBuckets") Map<String, Bucket> accountExportBuckets
    ) {
        this.workbookService = workbookService;
        this.userContext = userContext;
        this.accountExportBuckets = accountExportBuckets;
    }

    @PostMapping(value = "/export", produces = XLSX_MIME)
    public ResponseEntity<StreamingResponseBody> export(
        @AuthenticationPrincipal AppUser user,
        @Valid @RequestBody AccountsExportRequest req,
        HttpServletRequest httpReq
    ) {
        String key = String.valueOf(user.getId());
        Bucket bucket = accountExportBuckets.computeIfAbsent(
            key, k -> RateLimitConfig.createAccountExportBucket());
        if (!bucket.tryConsume(1)) {
            log.warn("accounts_export.rate_limited userId={} ip={}",
                user.getId(), httpReq.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        Long memberId = userContext.currentMemberId();
        log.warn("accounts_export.requested userId={} memberId={} accountIds={} ip={}",
            user.getId(), memberId, req.accountIds(), httpReq.getRemoteAddr());

        SheetLabels labels = SheetLabels.of(req.labelsOrEmpty());
        String filename = "picsou-comptes-" + filenameTimestamp() + ".xlsx";

        StreamingResponseBody body = out -> {
            try {
                workbookService.export(req.accountIds(), memberId, labels, out);
            } catch (IOException e) {
                // Headers are long gone by the time a write fails, so this cannot become a 500 —
                // the client gets a truncated file. Same constraint as the GDPR ZIP export.
                log.error("accounts_export.stream_failed userId={} msg={}", user.getId(), e.getMessage());
            }
        };

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(XLSX_MIME))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(body);
    }

    private static String filenameTimestamp() {
        return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now());
    }
}
