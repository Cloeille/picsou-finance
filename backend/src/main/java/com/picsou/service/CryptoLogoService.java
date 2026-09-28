package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The image URL to show for a holding's crypto asset, resolved on demand.
 *
 * <p>Crypto only, and deliberately so: {@code CoinGeckoPriceProvider.getLogoUrls} answers from
 * the same coin-id registry that prices crypto, so an equity ticker simply has no entry here and
 * the UI keeps rendering its ticker. Equities have no logo source yet (issue #162) — when one
 * lands, it belongs behind this same method so callers do not learn where the URL came from.
 *
 * <p>The cache is in-memory and long-lived, unlike {@code PriceService}'s 15-minute one, because
 * the value is not time-sensitive: a coin's mark does not go stale, and the URL is stable enough
 * that re-resolving it every quarter of an hour would spend CoinGecko calls to learn nothing. A
 * miss is remembered too, so an unmapped ticker is asked about once per TTL rather than on every
 * render of a page that lists it.
 */
@Service
public class CryptoLogoService {

    /**
     * How long a resolved image URL is trusted before being looked up again. Generous on
     * purpose: a wrong-but-working URL costs nothing, and the entrypoint's real risk is a coin
     * whose mark genuinely changes (a rebrand), which is rare and self-healing.
     */
    private static final long CACHE_TTL_SECONDS = 24 * 3600;

    private final CoinGeckoPriceProvider coinGecko;
    private final Map<String, CachedLogo> cache = new ConcurrentHashMap<>();

    public CryptoLogoService(CoinGeckoPriceProvider coinGecko) {
        this.coinGecko = coinGecko;
    }

    private record CachedLogo(String url, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > CACHE_TTL_SECONDS * 1000L;
        }
    }

    /**
     * Logo URLs for {@code tickers}, keyed by upper-case ticker, batched into one provider call.
     *
     * <p>A ticker with no cached answer is resolved together with every other one in the set, so
     * the number of requests does not grow with the size of the portfolio. Anything the provider
     * does not return is absent from the map — callers must treat that as "show the ticker", not
     * as an error.
     */
    public Map<String, String> getLogoUrls(Set<String> tickers) {
        if (tickers == null || tickers.isEmpty()) return Map.of();

        Map<String, String> resolved = new HashMap<>();
        Set<String> pending = tickers.stream()
            .filter(t -> t != null && !t.isBlank())
            .map(t -> t.toUpperCase(Locale.ROOT))
            .collect(Collectors.toCollection(java.util.TreeSet::new));

        for (String ticker : pending) {
            CachedLogo cached = cache.get(ticker);
            if (cached != null && !cached.isExpired() && cached.url() != null) {
                resolved.put(ticker, cached.url());
            }
        }

        Set<String> missing = pending.stream()
            .filter(t -> {
                CachedLogo cached = cache.get(t);
                return cached == null || cached.isExpired();
            })
            .collect(Collectors.toCollection(java.util.TreeSet::new));

        if (!missing.isEmpty()) {
            long now = System.currentTimeMillis();
            Map<String, String> fetched = coinGecko.getLogoUrls(missing);
            for (String ticker : missing) {
                // Cache the miss as well: a null url is the negative entry, and re-asking on
                // every render is exactly the request storm the cache exists to prevent.
                cache.put(ticker, new CachedLogo(fetched.get(ticker), now));
            }
            fetched.forEach(resolved::put);
        }

        return resolved;
    }

    /** The logo URL for a single ticker, or {@code null} when it has none. */
    public String getLogoUrl(String ticker) {
        if (ticker == null || ticker.isBlank()) return null;
        return getLogoUrls(Set.of(ticker.toUpperCase(Locale.ROOT))).get(ticker.toUpperCase(Locale.ROOT));
    }
}
