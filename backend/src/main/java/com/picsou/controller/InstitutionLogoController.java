package com.picsou.controller;

import com.picsou.config.RateLimitConfig;
import com.picsou.port.MerchantLogoPort.LogoImage;
import com.picsou.service.InstitutionLogoService;
import com.picsou.service.UserContext;
import com.picsou.service.budget.BudgetSettingsService;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Opt-in logo proxy for the bank behind an aggregator account. Same gates as
 * {@link MerchantController}: per-IP rate limit, then the member's logo opt-in (404 when off, so a
 * disabled feature looks like a missing logo and the avatar falls back to its monogram).
 */
@RestController
@RequestMapping("/api/accounts")
public class InstitutionLogoController {

    private final InstitutionLogoService logoService;
    private final BudgetSettingsService budgetSettingsService;
    private final UserContext userContext;
    private final Map<String, Bucket> logoBuckets;

    public InstitutionLogoController(
        InstitutionLogoService logoService,
        BudgetSettingsService budgetSettingsService,
        UserContext userContext,
        @Qualifier("logoBuckets") Map<String, Bucket> logoBuckets
    ) {
        this.logoService = logoService;
        this.budgetSettingsService = budgetSettingsService;
        this.userContext = userContext;
        this.logoBuckets = logoBuckets;
    }

    @GetMapping("/{id}/institution-logo")
    public ResponseEntity<byte[]> logo(@PathVariable Long id, HttpServletRequest request) {
        Bucket bucket = logoBuckets.computeIfAbsent(request.getRemoteAddr(), k -> RateLimitConfig.createLogoBucket());
        if (!bucket.tryConsume(1)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        Long memberId = userContext.currentMemberId();
        if (!budgetSettingsService.logoFetchEnabled(memberId)) {
            return ResponseEntity.notFound().build();
        }
        Optional<LogoImage> logo = logoService.logoFor(id, memberId);
        if (logo.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        LogoImage image = logo.get();
        return ResponseEntity.ok()
            // The type was derived from the bytes by the service. The sandbox policy is a second
            // line: even if a browser were talked into rendering this as a document, it would
            // have no scripts and no origin.
            .contentType(MediaType.parseMediaType(image.contentType()))
            .header("Content-Security-Policy", "default-src 'none'; sandbox")
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
            .body(image.bytes());
    }
}
