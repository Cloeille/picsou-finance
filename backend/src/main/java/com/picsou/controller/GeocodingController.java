package com.picsou.controller;

import com.picsou.config.RateLimitConfig;
import com.picsou.port.GeocodingPort;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Address autocomplete, proxied server-side.
 *
 * <p>Proxying rather than calling IGN from the browser keeps the external dependency behind
 * the same ports-and-adapters boundary as every other integration — swapping the geocoder
 * for another country's service stays a backend change — and makes the rate limit
 * enforceable, which it would not be from the client.
 */
@RestController
@RequestMapping("/api/geocode")
public class GeocodingController {

    private static final Logger log = LoggerFactory.getLogger(GeocodingController.class);
    private static final int DEFAULT_LIMIT = 5;
    private static final int MIN_QUERY_LENGTH = 3;

    private final GeocodingPort geocoder;
    private final UserContext userContext;
    private final Map<String, Bucket> geocodeBuckets;

    public GeocodingController(GeocodingPort geocoder,
                               UserContext userContext,
                               @Qualifier("geocodeBuckets") Map<String, Bucket> geocodeBuckets) {
        this.geocoder = geocoder;
        this.userContext = userContext;
        this.geocodeBuckets = geocodeBuckets;
    }

    /** @param q free-form address; shorter than 3 characters returns nothing rather than noise */
    @GetMapping
    public ResponseEntity<List<GeocodeSuggestion>> search(
        @RequestParam("q") String q,
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        if (q == null || q.trim().length() < MIN_QUERY_LENGTH) {
            return ResponseEntity.ok(List.of());
        }

        Long memberId = userContext.currentMemberId();
        Bucket bucket = geocodeBuckets.computeIfAbsent(
            String.valueOf(memberId), k -> RateLimitConfig.createGeocodeBucket());
        if (!bucket.tryConsume(1)) {
            log.warn("geocode.rate_limited memberId={}", memberId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        List<GeocodingPort.GeocodeResult> results =
            geocoder.autocomplete(q.trim(), limit != null ? limit : DEFAULT_LIMIT);

        return ResponseEntity.ok(results.stream().map(GeocodeSuggestion::from).toList());
    }

    public record GeocodeSuggestion(
        String label,
        BigDecimal score,
        String postcode,
        String city,
        String inseeCode,
        BigDecimal latitude,
        BigDecimal longitude
    ) {
        static GeocodeSuggestion from(GeocodingPort.GeocodeResult r) {
            return new GeocodeSuggestion(
                r.label(), r.score(), r.postcode(), r.city(), r.inseeCode(), r.latitude(), r.longitude());
        }
    }
}
