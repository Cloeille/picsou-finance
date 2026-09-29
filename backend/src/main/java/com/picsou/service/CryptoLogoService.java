package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 * render of a page that lists it — but on a much shorter TTL, see
 * {@link #MISS_CACHE_TTL_SECONDS}.
 */
@Service
public class CryptoLogoService {

    /**
     * How long a resolved image URL is trusted before being looked up again. Generous on
     * purpose: a wrong-but-working URL costs nothing, and the entrypoint's real risk is a coin
     * whose mark genuinely changes (a rebrand), which is rare and self-healing.
     */
    private static final long CACHE_TTL_SECONDS = 24 * 3600;

    /**
     * How long a <em>negative</em> answer is trusted. Far shorter than a hit's, for the same
     * reason {@code PriceService} keeps two TTLs: a provider that is rate-limited, timing out or
     * down answers with an absent key, which is indistinguishable from "this coin has no logo",
     * so a miss is far more likely to be transient than a hit is to be stale. Sharing the 24h hit
     * TTL would turn one rate-limited page render into blank marks on every portfolio page for
     * the rest of the day — decoration that was supposed to cost nothing. A minute collapses a
     * request storm without making recovery feel broken.
     */
    private static final long MISS_CACHE_TTL_SECONDS = 60;

    private final CoinGeckoPriceProvider coinGecko;
    private final Clock clock;
    private final Map<String, CachedLogo> cache = new ConcurrentHashMap<>();

    public CryptoLogoService(CoinGeckoPriceProvider coinGecko, Clock clock) {
        this.coinGecko = coinGecko;
        this.clock = clock;
    }

    /**
     * A cached answer, hit or miss, with the TTL it was written under — the two are not the same
     * question, so they are not the same clock. {@code ttlSeconds} travels with the entry
     * rather than being looked up on read, so a hit can never be read with a miss's deadline.
     */
    private record CachedLogo(String url, Instant cachedAt, long ttlSeconds) {
        static CachedLogo hit(String url, Instant now) {
            return new CachedLogo(url, now, CACHE_TTL_SECONDS);
        }

        static CachedLogo miss(Instant now) {
            return new CachedLogo(null, now, MISS_CACHE_TTL_SECONDS);
        }

        boolean isExpired(Instant now) {
            return now.isAfter(cachedAt.plusSeconds(ttlSeconds));
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

        Instant now = Instant.now(clock);
        Map<String, String> resolved = new HashMap<>();
        Set<String> pending = tickers.stream()
            .filter(t -> t != null && !t.isBlank())
            .map(t -> t.toUpperCase(Locale.ROOT))
            .collect(Collectors.toCollection(TreeSet::new));

        for (String ticker : pending) {
            CachedLogo cached = cache.get(ticker);
            if (cached != null && !cached.isExpired(now) && cached.url() != null) {
                resolved.put(ticker, cached.url());
            }
        }

        Set<String> missing = pending.stream()
            .filter(t -> {
                CachedLogo cached = cache.get(t);
                return cached == null || cached.isExpired(now);
            })
            .collect(Collectors.toCollection(TreeSet::new));

        if (!missing.isEmpty()) {
            Map<String, String> fetched = coinGecko.getLogoUrls(missing);
            for (String ticker : missing) {
                // Cache the miss as well: a null url is the negative entry, and re-asking on
                // every render is exactly the request storm the cache exists to prevent. It gets
                // the short TTL because an absent key also means "the provider did not answer".
                String url = fetched.get(ticker);
                cache.put(ticker, url == null ? CachedLogo.miss(now) : CachedLogo.hit(url, now));
            }
            fetched.forEach(resolved::put);
        }

        return resolved;
    }
}
