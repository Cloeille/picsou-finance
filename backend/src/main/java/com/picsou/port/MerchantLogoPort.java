package com.picsou.port;

import java.util.Optional;

/**
 * Port for fetching a brand logo image by domain. Implement this interface to swap the
 * logo source (e.g. DuckDuckGo icons, Google s2 favicons, Brandfetch) — callers and
 * {@link com.picsou.service.budget.MerchantLogoService} never import an adapter directly.
 *
 * <p>Logos are purely cosmetic and opt-in (ADR 2026-06-09): the result never influences
 * categorization. Implementations must be defensive — a slow or failing upstream returns
 * {@link Optional#empty()} rather than propagating, so a broken provider can never break a page.
 */
public interface MerchantLogoPort {

    /**
     * Fetch the logo for the given domain ({@code "carrefour.fr"}). Returns empty on any
     * failure (timeout, non-2xx, oversized body, unknown domain) — never throws.
     */
    Optional<LogoImage> fetch(String logoDomain);

    /** A fetched logo: the raw bytes plus the MIME type to echo back to the browser. */
    record LogoImage(byte[] bytes, String contentType) {}
}
