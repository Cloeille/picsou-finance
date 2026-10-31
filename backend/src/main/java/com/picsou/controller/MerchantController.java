package com.picsou.controller;

import com.picsou.config.RateLimitConfig;
import com.picsou.port.MerchantLogoPort.LogoImage;
import com.picsou.service.UserContext;
import com.picsou.service.budget.BudgetSettingsService;
import com.picsou.service.budget.MerchantLogoService;
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
 * Opt-in brand-logo proxy. The frontend points {@code MerchantAvatar} at
 * {@code GET /api/merchants/{id}/logo}; the browser only reaches this when the member has
 * enabled logos, and the monogram is always the fallback.
 *
 * <p>Three gates, in order:
 * <ol>
 *   <li><b>Per-IP rate limit</b> → 429, so the proxy can't be abused as an open relay;</li>
 *   <li><b>Per-member opt-in</b> ({@link BudgetSettingsService#logoFetchEnabled}) → 404 when off,
 *       so a disabled feature looks identical to a missing logo and the avatar falls back cleanly;</li>
 *   <li><b>Cache/fetch</b> via {@link MerchantLogoService} → 404 when the brand is unknown or has no logo.</li>
 * </ol>
 * Authentication is enforced upstream by {@code SecurityConfig} ({@code anyRequest().authenticated()}).
 */
@RestController
@RequestMapping("/api/merchants")
public class MerchantController {

    private final MerchantLogoService merchantLogoService;
    private final BudgetSettingsService budgetSettingsService;
    private final UserContext userContext;
    private final Map<String, Bucket> logoBuckets;

    public MerchantController(
        MerchantLogoService merchantLogoService,
        BudgetSettingsService budgetSettingsService,
        UserContext userContext,
        @Qualifier("logoBuckets") Map<String, Bucket> logoBuckets
    ) {
        this.merchantLogoService = merchantLogoService;
        this.budgetSettingsService = budgetSettingsService;
        this.userContext = userContext;
        this.logoBuckets = logoBuckets;
    }

    @GetMapping("/{id}/logo")
    public ResponseEntity<byte[]> logo(@PathVariable Long id, HttpServletRequest request) {
        Bucket bucket = logoBuckets.computeIfAbsent(request.getRemoteAddr(), k -> RateLimitConfig.createLogoBucket());
        if (!bucket.tryConsume(1)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        if (!budgetSettingsService.logoFetchEnabled(userContext.currentMemberId())) {
            return ResponseEntity.notFound().build();
        }

        Optional<LogoImage> logo = merchantLogoService.getLogo(id);
        if (logo.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        LogoImage image = logo.get();
        return ResponseEntity.ok()
            .contentType(parseContentType(image.contentType()))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
            .body(image.bytes());
    }

    private static MediaType parseContentType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException ex) {
            return MediaType.IMAGE_PNG;
        }
    }
}
