package com.picsou.service.budget;

import com.picsou.port.MerchantLogoPort;
import com.picsou.port.MerchantLogoPort.LogoImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves a brand's logo, fetching online (via {@link MerchantLogoPort}) at most once per
 * brand per TTL and caching the bytes in memory. Mirrors {@link com.picsou.service.PriceService}'s
 * cache discipline — a {@link ConcurrentHashMap} keyed by brand id with TTL'd entries — so a
 * page full of avatars triggers no repeat network calls.
 *
 * <p>The {@code brandId → logoDomain} resolution goes through the in-memory
 * {@link MerchantKnowledgeBase} snapshot, so it never touches the database. <b>Misses are
 * cached too</b> (brand with no logo domain, or an upstream that returned nothing) on a
 * shorter TTL, so an unknown or temporarily-failing logo doesn't get re-fetched on every render.
 *
 * <p>This service does <b>not</b> enforce the per-member opt-in — that gate lives in
 * {@link com.picsou.controller.MerchantController}, since the cache is keyed by the global
 * brand id and is identical for every member who has logos enabled.
 */
@Service
public class MerchantLogoService {

    private static final Logger log = LoggerFactory.getLogger(MerchantLogoService.class);
    private static final long HIT_TTL_SECONDS = 86_400;  // 24h — brand logos are effectively static
    private static final long MISS_TTL_SECONDS = 3_600;  // 1h  — retry unknown/failed sooner

    private final MerchantKnowledgeBase knowledgeBase;
    private final MerchantLogoPort logoPort;

    /** brandId → cached logo (present or absent), with its own expiry. */
    private final ConcurrentMap<Long, CachedLogo> cache = new ConcurrentHashMap<>();

    public MerchantLogoService(MerchantKnowledgeBase knowledgeBase, MerchantLogoPort logoPort) {
        this.knowledgeBase = knowledgeBase;
        this.logoPort = logoPort;
    }

    /**
     * The logo for a brand id, or empty if the brand is unknown, has no logo domain, or the
     * upstream fetch failed. Cheap on repeat calls within the TTL (served from memory).
     */
    public Optional<LogoImage> getLogo(Long brandId) {
        if (brandId == null) {
            return Optional.empty();
        }
        CachedLogo cached = cache.get(brandId);
        if (cached != null && !cached.isExpired()) {
            return cached.logo();
        }

        String domain = knowledgeBase.findById(brandId)
            .map(MerchantKnowledgeBase.Brand::logoDomain)
            .orElse(null);
        if (domain == null || domain.isBlank()) {
            cache.put(brandId, CachedLogo.miss());
            return Optional.empty();
        }

        Optional<LogoImage> fetched = logoPort.fetch(domain);
        cache.put(brandId, fetched.map(CachedLogo::hit).orElseGet(CachedLogo::miss));
        return fetched;
    }

    /** Drop the in-memory logo cache (diagnostics / tests). */
    public void clearCache() {
        cache.clear();
    }

    /** A cached fetch result: a present logo (24h TTL) or a recorded miss (1h TTL). */
    private record CachedLogo(Optional<LogoImage> logo, Instant expiresAt) {
        static CachedLogo hit(LogoImage image) {
            return new CachedLogo(Optional.of(image), Instant.now().plusSeconds(HIT_TTL_SECONDS));
        }

        static CachedLogo miss() {
            return new CachedLogo(Optional.empty(), Instant.now().plusSeconds(MISS_TTL_SECONDS));
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
