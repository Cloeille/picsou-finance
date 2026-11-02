package com.picsou.adapter;

import com.picsou.port.PriceProviderPort;
import com.picsou.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routing {@link PriceProviderPort} that keeps the crypto-vs-stock decision in a
 * single place so services depend on the port abstraction rather than the
 * concrete adapters.
 *
 * <p>Routing preserves the historical behaviour: CoinGecko handles the tickers
 * it recognises as crypto; <em>everything else</em> — including ISINs that Yahoo
 * itself reports as unsupported — falls back to Yahoo Finance. It is therefore a
 * deliberate {@code coinGecko.supports(t) ? coinGecko : yahoo} fallback, not a
 * "first provider whose supports() is true" scan.
 */
@Component
@Primary
public class CompositePriceProvider implements PriceProviderPort {

    private static final Logger log = LoggerFactory.getLogger(CompositePriceProvider.class);

    /** A definitive answer (Yahoo quotes it, or CoinGecko refused it) is not re-asked for a day. */
    private static final Duration DECIDED_RETRY = Duration.ofHours(24);
    /** "Could not tell" (Yahoo or CoinGecko down or cooling) is retried soon, and never remembered as a no. */
    private static final Duration UNDECIDED_RETRY = Duration.ofMinutes(15);
    /**
     * Wall-clock budget for the serial Yahoo probes of one call (each may take 3 s). This runs
     * inside page renders and syncs; tickers past it get no retry entry and are asked next call.
     */
    private static final Duration PROBE_BUDGET = Duration.ofSeconds(5);

    private final CoinGeckoPriceProvider coinGecko;
    private final YahooFinancePriceProvider yahoo;
    private final CryptoAssetRegistry registry;
    private final Clock clock;

    /** Ticker &rarr; earliest instant it may be probed again. In memory: a restart just re-asks. */
    private final Map<String, Instant> retryAfter = new ConcurrentHashMap<>();

    @Autowired
    public CompositePriceProvider(CoinGeckoPriceProvider coinGecko, YahooFinancePriceProvider yahoo,
                                  CryptoAssetRegistry registry, Clock clock) {
        this.coinGecko = coinGecko;
        this.yahoo = yahoo;
        this.registry = registry;
        this.clock = clock;
    }

    private PriceProviderPort providerFor(String ticker) {
        return coinGecko.supports(ticker) ? coinGecko : yahoo;
    }

    @Override
    public Map<String, BigDecimal> getPricesEur(Set<String> tickers) {
        if (tickers == null || tickers.isEmpty()) return Map.of();

        Set<String> crypto = new HashSet<>();
        Set<String> other = new HashSet<>();
        for (String ticker : tickers) {
            (coinGecko.supports(ticker) ? crypto : other).add(ticker);
        }

        Map<String, BigDecimal> result = new HashMap<>();
        if (!crypto.isEmpty()) result.putAll(coinGecko.getPricesEur(crypto));
        if (!other.isEmpty()) result.putAll(yahoo.getPricesEur(other));
        return result;
    }

    /**
     * Learns, once, which tickers held in a crypto context are coins the registry lacks.
     *
     * <p>The Yahoo probe is the guard that keeps this safe. The registry doubles as the router, so
     * a learned symbol sends every later price, history and logo lookup for that ticker to
     * CoinGecko, and the ticker-keyed price cache and {@code price_snapshot} are shared with
     * stocks. A symbol Yahoo quotes (STX, FET, CAKE) belongs to a listed equity and is never
     * learned; a symbol Yahoo cannot be asked about is left undecided rather than assumed free.
     *
     * <p>Retry windows: 24 h once the answer is definitive, 15 min when it is not. A CoinGecko
     * batch that could not be asked (cooling down, outage) is treated like an unavailable Yahoo:
     * its candidates were never judged, so remembering a refusal would hide them for a day. The
     * Yahoo probes of one call stop after {@link #PROBE_BUDGET}; the rest wait for a later call.
     *
     * <p>Known limit: Yahoo answers 404 both for a symbol it never had and for a delisted one, so
     * a delisted stock symbol held in a crypto account can be learned if CoinGecko has a dominant
     * coin under it. The trigger is a crypto-context holding, never a stock one.
     */
    @Override
    public void learnCryptoTickers(Set<String> tickers) {
        if (tickers == null || tickers.isEmpty()) return;
        Instant now = clock.instant();
        // Expired entries already read as absent; dropping them keeps the map to live windows.
        retryAfter.values().removeIf(next -> !now.isBefore(next));
        Instant probeDeadline = now.plus(PROBE_BUDGET);

        Set<String> candidates = new TreeSet<>();
        for (String raw : tickers) {
            if (raw == null || raw.isBlank()) continue;
            String ticker = raw.trim().toUpperCase(Locale.ROOT);
            if (coinGecko.supports(ticker)) continue;
            Instant next = retryAfter.get(ticker);
            if (next != null && now.isBefore(next)) continue;
            if (!clock.instant().isBefore(probeDeadline)) break;

            switch (yahoo.probeQuote(ticker)) {
                case QUOTED -> retryAfter.put(ticker, now.plus(DECIDED_RETRY));
                case UNAVAILABLE -> retryAfter.put(ticker, now.plus(UNDECIDED_RETRY));
                case UNKNOWN_SYMBOL -> candidates.add(ticker);
            }
        }
        if (candidates.isEmpty()) return;

        CoinGeckoPriceProvider.Resolution resolution;
        try {
            resolution = coinGecko.resolveBySymbol(candidates);
        } catch (RuntimeException ex) {
            // The port promises never to throw: this runs inside page renders and syncs, and a
            // learning bug must cost an unpriced coin, not the whole valuation.
            log.warn("Crypto symbol lookup failed for {} -- retrying in {}", LogSanitizer.safe(candidates), UNDECIDED_RETRY, ex);
            candidates.forEach(t -> retryAfter.put(t, now.plus(UNDECIDED_RETRY)));
            return;
        }
        Duration wait = resolution.answered() ? DECIDED_RETRY : UNDECIDED_RETRY;
        for (String ticker : candidates) {
            CryptoAssetRegistry.Coin coin = resolution.accepted().get(ticker);
            if (coin == null) {
                retryAfter.put(ticker, now.plus(wait));
                continue;
            }
            try {
                registry.learn(ticker, coin.id(), coin.name());
                retryAfter.remove(ticker);
            } catch (RuntimeException e) {
                // Learning is best effort: a DB failure must never break a price read.
                log.warn("Could not store crypto asset {}: {}", LogSanitizer.safe(ticker), LogSanitizer.safe(e.getMessage()));
                retryAfter.put(ticker, now.plus(UNDECIDED_RETRY));
            }
        }
    }

    @Override
    public boolean supports(String ticker) {
        return coinGecko.supports(ticker) || yahoo.supports(ticker);
    }

    @Override
    public boolean supportsCrypto(String ticker) {
        return coinGecko.supportsCrypto(ticker);
    }

    @Override
    public Map<LocalDate, BigDecimal> getHistoricalPricesEur(String ticker, LocalDate from, LocalDate to) {
        return providerFor(ticker).getHistoricalPricesEur(ticker, from, to);
    }

    @Override
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(String ticker, LocalDateTime from, LocalDateTime to) {
        return providerFor(ticker).getIntradayPricesEur(ticker, from, to);
    }

    @Override
    public BigDecimal getFxRateToEur(String currency) {
        return yahoo.getFxRateToEur(currency);
    }
}
