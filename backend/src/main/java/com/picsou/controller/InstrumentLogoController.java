package com.picsou.controller;

import com.picsou.service.InstrumentLogoService;
import com.picsou.service.InstrumentLogoService.ServedImage;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Optional;

/**
 * Serves a share's or fund's stored mark. The URLs come from {@code HoldingResponse.logoUrl},
 * so the browser only ever loads a holding's mark from Picsou, never from Yahoo (issue #162).
 *
 * <p>Authenticated by {@code SecurityConfig}'s catch-all, like every {@code /api} route. Not
 * member-scoped: a company's logo is public reference data and one row serves every member.
 * No rate limit either, unlike {@code MerchantController}'s proxy: this reads one row and never
 * reaches the network, so there is nothing upstream to protect.
 *
 * <p>Cached hard in the browser. The URL carries the version ({@code v}, the fetch time), so a
 * replaced mark would be a different URL. The ETag lets a revalidation end in a 304.
 */
@RestController
@RequestMapping("/api/instrument-logos")
public class InstrumentLogoController {

    static final Duration MAX_AGE = Duration.ofDays(30);

    private final InstrumentLogoService logoService;

    public InstrumentLogoController(InstrumentLogoService logoService) {
        this.logoService = logoService;
    }

    @GetMapping("/{ticker}")
    public ResponseEntity<byte[]> logo(@PathVariable String ticker,
                                       @RequestParam(required = false) String variant) {
        Optional<ServedImage> image = logoService.image(ticker, "dark".equals(variant));
        if (image.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ServedImage served = image.get();
        // Spring answers a matching If-None-Match with a 304 from this header on its own.
        String etag = "\"" + served.fetchedAt().getEpochSecond() + (served.dark() ? "-dark" : "-light") + "\"";
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(served.contentType()))
            .cacheControl(CacheControl.maxAge(MAX_AGE).cachePrivate())
            .eTag(etag)
            .body(served.bytes());
    }
}
