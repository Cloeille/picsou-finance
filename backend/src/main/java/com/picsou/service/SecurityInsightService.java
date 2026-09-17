package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.YahooFinancePriceProvider;
import com.picsou.dto.EtfComposition;
import com.picsou.dto.FundFacts;
import com.picsou.dto.SecurityInsightResponse;
import com.picsou.dto.SecurityRef;
import com.picsou.port.EtfCompositionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the "Insight" payload for a security: its asset type (ETF / Action /
 * Crypto) and, for ETFs, its composition broken down by company, country and
 * sector. Results are cached in memory for a few days (cleared on restart).
 *
 * Asset type comes from the unauthenticated Yahoo chart endpoint already used
 * for prices ({@code instrumentType}); composition comes from an external
 * provider behind {@link EtfCompositionProvider}.
 */
@Service
public class SecurityInsightService {

    private static final Logger log = LoggerFactory.getLogger(SecurityInsightService.class);
    private static final long CACHE_TTL_SECONDS = 3L * 24 * 3600; // 3 days

    private final List<EtfCompositionProvider> compositionProviders;
    private final YahooFinancePriceProvider yahoo;
    private final CoinGeckoPriceProvider coinGecko;
    private final Map<String, CachedInsight> cache = new ConcurrentHashMap<>();

    public SecurityInsightService(List<EtfCompositionProvider> compositionProviders,
                                  YahooFinancePriceProvider yahoo,
                                  CoinGeckoPriceProvider coinGecko) {
        this.compositionProviders = compositionProviders;
        this.yahoo = yahoo;
        this.coinGecko = coinGecko;
    }

    public SecurityInsightResponse getInsight(String ticker, String name) {
        return getInsight(ticker, name, null);
    }

    /**
     * As above, but told which ISIN the ticker came from.
     *
     * <p>The ISIN is part of the cache key rather than ignored: a call from the security
     * controller has none, and letting its thinner answer occupy the same entry would deny the
     * refresh path the identifier that makes the lookup succeed.
     */
    public SecurityInsightResponse getInsight(String ticker, String name, String isin) {
        if (ticker == null || ticker.isBlank()) {
            return new SecurityInsightResponse(ticker, "UNKNOWN", null);
        }
        String upper = ticker.toUpperCase();
        String cacheKey = isin == null || isin.isBlank() ? upper : upper + '|' + isin;

        CachedInsight cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.response();
        }

        String assetType = classify(upper);
        EtfComposition composition = "ETF".equals(assetType)
            ? resolveComposition(new SecurityRef(ticker, name, isin))
            : null;

        SecurityInsightResponse response = new SecurityInsightResponse(upper, assetType, composition);
        cache.put(cacheKey, new CachedInsight(response, Instant.now()));
        return response;
    }

    /** crypto via CoinGecko, else map Yahoo's instrumentType, else UNKNOWN. */
    private String classify(String upperTicker) {
        if (coinGecko.supports(upperTicker)) {
            return "CRYPTO";
        }
        Optional<String> instrumentType = yahoo.getInstrumentType(upperTicker);
        if (instrumentType.isEmpty()) {
            return "UNKNOWN";
        }
        return switch (instrumentType.get().toUpperCase()) {
            case "ETF", "MUTUALFUND" -> "ETF";
            case "EQUITY" -> "STOCK";
            case "CRYPTOCURRENCY" -> "CRYPTO";
            default -> "UNKNOWN";
        };
    }

    /**
     * Merges the providers, but not uniformly: slices as a unit, facts independently.
     *
     * <p>The breakdown is taken whole from the first provider that has one. Mixing a Boursorama
     * sector split with a justETF country split would put two different reference dates behind
     * one {@code asOf}, and the two sources publish to different depths — ten slices with no
     * residual against four plus an explicit remainder.
     *
     * <p>The fee and distribution policy are taken from whichever provider has them, which is
     * never the same one: Boursorama's composition page does not carry them. That is the same
     * field-by-field reasoning {@code SecurityProfileService.resolveEquity} already applies to
     * sector and country.
     */
    private EtfComposition resolveComposition(SecurityRef ref) {
        EtfComposition slices = null;
        FundFacts facts = null;
        List<String> sources = new ArrayList<>();

        for (EtfCompositionProvider provider : compositionProviders) {
            if (!provider.supports(ref)) {
                continue;
            }
            if (slices != null && facts != null) {
                break;
            }
            Optional<EtfComposition> answer = provider.fetch(ref);
            if (answer.isEmpty()) {
                continue;
            }
            EtfComposition c = answer.get();
            if (slices == null && hasAnyData(c)) {
                slices = c;
                if (c.source() != null) sources.add(c.source());
            }
            if (facts == null && c.facts() != null && !c.facts().isEmpty()) {
                facts = c.facts();
                if (c.facts().source() != null && !sources.contains(c.facts().source())) {
                    sources.add(c.facts().source());
                }
            }
        }

        if (slices == null && facts == null) {
            log.debug("No provider resolved composition for {} ({})", ref.ticker(), ref.isin());
            return null;
        }
        String source = String.join(" · ", sources);
        if (slices == null) {
            // Facts without a breakdown: still worth storing — the fee scanner wants it, and the
            // fund simply reports as unclassified in the meantime.
            return new EtfComposition(List.of(), List.of(), List.of(), source, null, facts);
        }
        return new EtfComposition(slices.companies(), slices.countries(), slices.sectors(),
            source, slices.asOf(), facts);
    }

    private static boolean hasAnyData(EtfComposition c) {
        return !c.companies().isEmpty() || !c.countries().isEmpty() || !c.sectors().isEmpty();
    }

    /** Drop the in-memory insight cache. */
    public void clearCache() {
        cache.clear();
    }

    private record CachedInsight(SecurityInsightResponse response, Instant cachedAt) {
        boolean isExpired() {
            return Instant.now().isAfter(cachedAt.plusSeconds(CACHE_TTL_SECONDS));
        }
    }
}
