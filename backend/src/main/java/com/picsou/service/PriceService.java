package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.YahooFinancePriceProvider;
import com.picsou.model.PriceSnapshot;
import com.picsou.repository.PriceSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class PriceService {

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);
    private static final long CACHE_TTL_SECONDS = 900; // 15 minutes
    /**
     * A remembered miss expires sooner than a hit: a miss is more likely to be transient (a
     * rate limit) than a hit is to be stale, so recovery stays fast while a storm of retries
     * still collapses to one call per ticker per minute (docs/features/price-service.md).
     * Retries, that is: the cache is a plain map with no in-flight de-duplication, so threads
     * asking for the same uncached ticker at the same instant each reach the provider once
     * before the first answer lands.
     */
    private static final long MISS_CACHE_TTL_SECONDS = 60;

    /**
     * How stale a {@code price_snapshot} row may be before it stops being an acceptable answer.
     * A day-old crypto price is a slightly wrong number; a month-old one is fiction, and would
     * be worse than the honest "unknown" it replaces.
     */
    private static final int MAX_FALLBACK_AGE_DAYS = 7;

    /**
     * The longest hole a recorded history may contain before the backfill considers it incomplete.
     * Sized for closed markets, not for outages: a weekend is two days, and an Easter or Christmas
     * week can reach five.
     */
    private static final int MAX_HISTORY_GAP_DAYS = 7;

    private final CoinGeckoPriceProvider coinGecko;
    private final YahooFinancePriceProvider yahoo;
    private final PriceSnapshotRepository priceSnapshotRepository;

    // Simple in-memory price cache: ticker → (price, cachedAt)
    private final Map<String, CachedPrice> priceCache = new ConcurrentHashMap<>();

    public PriceService(CoinGeckoPriceProvider coinGecko, YahooFinancePriceProvider yahoo,
                        PriceSnapshotRepository priceSnapshotRepository) {
        this.coinGecko = coinGecko;
        this.yahoo = yahoo;
        this.priceSnapshotRepository = priceSnapshotRepository;
    }

    /**
     * A EUR price together with how current it is.
     *
     * @param price the EUR price, never null
     * @param asOf  the day the price is for — today for a live quote, the snapshot's date for a
     *              fallback
     * @param live  true when the number came from the provider (or its 15-minute cache), false
     *              when it is the last price we ever managed to record
     */
    public record Quote(BigDecimal price, LocalDate asOf, boolean live) {}

    /**
     * Returns EUR price for the given ticker.
     * Returns BigDecimal.ONE if ticker is "EUR" (no conversion needed).
     * Returns null if no price is available at all — not even a recent recorded one.
     */
    public BigDecimal getPriceEur(String ticker) {
        Quote quote = getQuote(ticker);
        return quote == null ? null : quote.price();
    }

    /**
     * Like {@link #getPriceEur}, for a ticker known to be crypto: an asset CoinGecko doesn't map
     * returns {@code null} instead of being valued at the share price of the equity trading under
     * the same symbol. See {@link #refreshCryptoPrices}.
     */
    public BigDecimal getCryptoPriceEur(String ticker) {
        Quote quote = getCryptoQuote(ticker);
        return quote == null ? null : quote.price();
    }

    /** {@link #getPriceEur} with the freshness attached. Null when nothing can be resolved. */
    public Quote getQuote(String ticker) {
        return singleQuote(ticker, false);
    }

    /** {@link #getCryptoPriceEur} with the freshness attached. Null when nothing can be resolved. */
    public Quote getCryptoQuote(String ticker) {
        return singleQuote(ticker, true);
    }

    /** Resolve a whole set at once — one provider round-trip instead of one per ticker. */
    public Map<String, Quote> getQuotes(Set<String> tickers) {
        return resolve(tickers, false);
    }

    /** {@link #getQuotes} restricted to CoinGecko, never falling through to Yahoo Finance. */
    public Map<String, Quote> getCryptoQuotes(Set<String> tickers) {
        return resolve(tickers, true);
    }

    private Quote singleQuote(String ticker, boolean cryptoOnly) {
        if (ticker == null || ticker.isBlank() || "EUR".equalsIgnoreCase(ticker)) {
            return new Quote(BigDecimal.ONE, LocalDate.now(), true);
        }
        return resolve(Set.of(ticker), cryptoOnly).get(ticker.toUpperCase(Locale.ROOT));
    }

    /**
     * Resolves EUR prices for {@code tickers}, in order: the in-memory cache, then one batched
     * provider call for whatever is left, then the last recorded {@code price_snapshot}.
     *
     * <p>The third step is what keeps a rate-limited morning from blanking the interface. Every
     * priced ticker already has a daily row in {@code price_snapshot}, so a provider outage now
     * degrades the number's <em>age</em> rather than its existence — and the callers that used
     * to drop an unpriced asset from a total (and from its daily snapshot) get something to
     * value it with. A tickers absent from that table too — a coin with no CoinGecko mapping,
     * a currency we never priced — still resolves to nothing, which callers must keep handling.
     *
     * <p>{@code cryptoOnly} keeps the crypto side away from both Yahoo Finance <em>and</em> the
     * snapshot table: the fallback is keyed by ticker alone, exactly like the cache, so an
     * unmapped symbol must never be allowed to read a row that a stock of the same name wrote.
     */
    private Map<String, Quote> resolve(Set<String> tickers, boolean cryptoOnly) {
        if (tickers.isEmpty()) return Map.of();

        LocalDate today = LocalDate.now();
        Map<String, Quote> resolved = new HashMap<>();
        Set<String> pending = new TreeSet<>();
        Set<String> missCached = new TreeSet<>();

        for (String ticker : tickers) {
            if (ticker == null || ticker.isBlank()) continue;
            String upper = ticker.toUpperCase(Locale.ROOT);

            if ("EUR".equals(upper)) {
                resolved.put(upper, new Quote(BigDecimal.ONE, today, true));
                continue;
            }
            if (cryptoOnly && !coinGecko.supports(upper)) {
                continue;
            }
            CachedPrice cached = priceCache.get(upper);
            if (cached != null && !cached.isExpired() && cached.price() != null) {
                resolved.put(upper, new Quote(cached.price(), today, true));
                continue;
            }
            // A cached entry with no price is a remembered miss: the provider was asked
            // recently and had nothing. Skip the network -- that is what the shorter
            // MISS_CACHE_TTL_SECONDS buys -- and go straight to the recorded fallback.
            if (cached != null && !cached.isExpired()) {
                missCached.add(upper);
            }
            pending.add(upper);
        }

        if (pending.isEmpty()) return resolved;

        Set<String> fetchable = pending.stream()
            .filter(t -> !missCached.contains(t))
            .collect(Collectors.toCollection(TreeSet::new));

        if (!fetchable.isEmpty()) {
            Map<String, BigDecimal> live = fetchLive(fetchable, cryptoOnly);
            Instant fetchedAt = Instant.now();
            for (String ticker : fetchable) {
                BigDecimal price = live.get(ticker);
                // Misses are cached too: that null entry *is* the negative cache, and it
                // expires on its own shorter TTL.
                priceCache.put(ticker, new CachedPrice(price, fetchedAt));
                if (price != null) {
                    resolved.put(ticker, new Quote(price, today, true));
                }
            }
        }

        Set<String> unresolved = pending.stream()
            .filter(t -> !resolved.containsKey(t))
            .collect(Collectors.toCollection(TreeSet::new));
        if (unresolved.isEmpty()) return resolved;

        Map<String, PriceSnapshot> lastKnown = lastKnownPrices(unresolved, today);
        lastKnown.forEach((ticker, snapshot) ->
            resolved.put(ticker, new Quote(snapshot.getPriceEur(), snapshot.getDate(), false)));

        // Only trace the attempts that actually reached out: the negative cache means the same
        // outage would otherwise log on every page render for as long as it lasts.
        Set<String> tried = fetchable.stream()
            .filter(t -> !resolved.containsKey(t) || !resolved.get(t).live())
            .collect(Collectors.toCollection(TreeSet::new));
        if (!tried.isEmpty()) {
            Set<String> stale = tried.stream().filter(lastKnown::containsKey)
                .collect(Collectors.toCollection(TreeSet::new));
            Set<String> unknown = tried.stream().filter(t -> !lastKnown.containsKey(t))
                .collect(Collectors.toCollection(TreeSet::new));
            if (!stale.isEmpty()) {
                log.warn("No live price for {} -- falling back to the last recorded one ({})",
                    stale, stale.stream().map(t -> t + "=" + lastKnown.get(t).getDate()).toList());
            }
            if (!unknown.isEmpty()) {
                log.warn("No price at all for {} -- not from the provider, and nothing recorded "
                    + "in the last {} days", unknown, MAX_FALLBACK_AGE_DAYS);
            }
        }

        return resolved;
    }

    /** One batched provider call, routed the same way {@link #refreshPrices} routes. */
    private Map<String, BigDecimal> fetchLive(Set<String> tickers, boolean cryptoOnly) {
        Set<String> crypto = tickers.stream().filter(coinGecko::supports)
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> stocks = cryptoOnly ? Set.of() : tickers.stream()
            .filter(t -> !coinGecko.supports(t))
            .collect(Collectors.toCollection(TreeSet::new));

        Map<String, BigDecimal> live = new HashMap<>();
        if (!crypto.isEmpty()) live.putAll(coinGecko.getPricesEur(crypto));
        if (!stocks.isEmpty()) live.putAll(yahoo.getPricesEur(stocks));
        return live;
    }

    /**
     * The most recent {@code price_snapshot} per ticker within {@link #MAX_FALLBACK_AGE_DAYS},
     * in one query. The reduction is order-independent on purpose — relying on the query's
     * {@code ORDER BY} would make the fallback silently pick the wrong row if that clause were
     * ever edited.
     */
    private Map<String, PriceSnapshot> lastKnownPrices(Set<String> tickers, LocalDate today) {
        Map<String, PriceSnapshot> latest = new HashMap<>();
        for (PriceSnapshot snapshot : priceSnapshotRepository.findRecentByTickers(
            tickers, today.minusDays(MAX_FALLBACK_AGE_DAYS), today)) {
            latest.merge(snapshot.getTicker(), snapshot,
                (a, b) -> a.getDate().isAfter(b.getDate()) ? a : b);
        }
        return latest;
    }

    /**
     * Bulk fetch and refresh cache for tickers known to be crypto.
     *
     * <p>Same as {@link #refreshPrices} except that a ticker CoinGecko doesn't know is left
     * <em>unpriced</em> instead of being handed to Yahoo Finance. That fallback is right for a
     * mixed portfolio but wrong for an exchange or wallet: dozens of coins share a symbol with a
     * listed equity (SUI, ATOM, TIA…), so an unmapped coin would be valued at the share price of
     * an unrelated company and written into the balance and its daily snapshot, with nothing in
     * the logs to reveal it. Callers already treat a missing price as "not valued this cycle".
     */
    public Map<String, BigDecimal> refreshCryptoPrices(Set<String> tickers) {
        return refreshPrices(tickers, true);
    }

    /**
     * {@link #refreshCryptoPrices} with the last-known-price fallback applied to whatever the
     * provider could not deliver, and with each result's freshness attached.
     *
     * <p>For sync paths, which both value an account <em>and</em> write that valuation into its
     * daily {@code BalanceSnapshot}. Dropping an asset there does not merely blank a cell: it
     * shrinks a number that is then engraved in the net-worth history, where nothing later
     * corrects it. A day-old price is a far better record of the day than a hole.
     *
     * <p>Only live prices reach {@code price_snapshot} (that write lives in
     * {@link #refreshPrices}); re-recording a fallback under today's date would launder a stale
     * price into a fresh-looking one and let the fallback drift forward forever.
     */
    public Map<String, Quote> refreshCryptoQuotes(Set<String> tickers) {
        Map<String, BigDecimal> live = refreshPrices(tickers, true);
        LocalDate today = LocalDate.now();

        Map<String, Quote> quotes = new HashMap<>();
        live.forEach((ticker, price) -> {
            if (price != null) quotes.put(ticker, new Quote(price, today, true));
        });

        Set<String> unresolved = tickers.stream()
            .map(t -> t.toUpperCase(Locale.ROOT))
            .filter(coinGecko::supports)
            .filter(t -> !quotes.containsKey(t))
            .collect(Collectors.toCollection(TreeSet::new));
        if (unresolved.isEmpty()) return quotes;

        Map<String, PriceSnapshot> lastKnown = lastKnownPrices(unresolved, today);
        lastKnown.forEach((ticker, snapshot) ->
            quotes.put(ticker, new Quote(snapshot.getPriceEur(), snapshot.getDate(), false)));

        if (!lastKnown.isEmpty()) {
            log.warn("No live price for {} -- valuing from the last recorded price instead ({})",
                lastKnown.keySet(),
                lastKnown.values().stream().map(s -> s.getTicker() + "=" + s.getDate()).toList());
        }
        return quotes;
    }

    /**
     * Bulk price lookup: serves still-fresh cache entries and fetches only the
     * expired or missing tickers from the upstream providers. Honoring the TTL
     * here matters because {@code GET /prices} is polled by the frontend on an
     * interval — bypassing the cache would turn every open dashboard tab into
     * a steady stream of Yahoo/CoinGecko calls.
     */
    public Map<String, BigDecimal> refreshPrices(Set<String> tickers) {
        return refreshPrices(tickers, false);
    }

    private Map<String, BigDecimal> refreshPrices(Set<String> tickers, boolean cryptoOnly) {
        if (tickers.isEmpty()) return Map.of();

        Map<String, BigDecimal> result = new HashMap<>();

        Set<String> cryptoTickers = new HashSet<>();
        Set<String> stockTickers = new HashSet<>();

        for (String ticker : tickers) {
            String upper = ticker.toUpperCase(Locale.ROOT);
            if ("EUR".equals(upper)) {
                result.put(upper, BigDecimal.ONE);
                continue;
            }
            CachedPrice cached = priceCache.get(upper);
            if (cached != null && !cached.isExpired()) {
                // A remembered miss skips the network like a hit does, but it is not a price:
                // leaving the ticker out of the result lets refreshCryptoQuotes reach the last
                // recorded price. Returning it as a null price wrapped that null in a Quote,
                // hid the fallback, and let an exchange sync engrave a partial total.
                if (cached.price() != null) {
                    result.put(upper, cached.price());
                }
            } else if (coinGecko.supports(upper)) {
                cryptoTickers.add(upper);
            } else if (cryptoOnly) {
                log.warn("No CoinGecko mapping for crypto ticker {} -- leaving it unpriced rather "
                    + "than valuing it as the stock trading under that symbol", upper);
            } else {
                stockTickers.add(upper);
            }
        }

        Map<String, BigDecimal> fetched = new HashMap<>();

        if (!cryptoTickers.isEmpty()) {
            coinGecko.getPricesEur(cryptoTickers).forEach((k, v) -> {
                priceCache.put(k, new CachedPrice(v, Instant.now()));
                fetched.put(k, v);
            });
        }

        if (!stockTickers.isEmpty()) {
            yahoo.getPricesEur(stockTickers).forEach((k, v) -> {
                priceCache.put(k, new CachedPrice(v, Instant.now()));
                fetched.put(k, v);
            });
        }

        result.putAll(fetched);
        log.debug("Refreshed prices: {} fetched, {} served from cache", fetched.size(), result.size() - fetched.size());

        // Persist daily price snapshots — only for freshly fetched prices;
        // cache-served values were already persisted when they were fetched.
        LocalDate today = LocalDate.now();
        for (var entry : fetched.entrySet()) {
            if ("EUR".equals(entry.getKey())) continue;
            if (entry.getValue() == null) continue;
            Optional<PriceSnapshot> existing = priceSnapshotRepository.findByTickerAndDate(entry.getKey(), today);
            if (existing.isPresent()) {
                existing.get().setPriceEur(entry.getValue());
                priceSnapshotRepository.save(existing.get());
            } else {
                priceSnapshotRepository.save(PriceSnapshot.builder()
                    .ticker(entry.getKey())
                    .date(today)
                    .priceEur(entry.getValue())
                    .build());
            }
        }

        return result;
    }

    /** Convert an account's balance to EUR using its currency/ticker. */
    public BigDecimal toEur(BigDecimal balance, String currency, String ticker) {
        if (balance == null) return BigDecimal.ZERO;

        // Already in EUR
        if ("EUR".equalsIgnoreCase(currency) && (ticker == null || ticker.isBlank())) {
            return balance;
        }

        // No ticker: the balance is plain cash in `currency`, and cash converts with an FX
        // rate, never with a chart symbol. Yahoo resolves a bare currency code as whatever
        // instrument trades under it (chart/USD is the ProShares Ultra Semiconductors ETF,
        // ~89 USD a share), so sending "USD" down the price path multiplied every USD cash
        // balance by an ETF price and reported it, and its daily snapshots, at ~76x.
        if (ticker == null || ticker.isBlank()) {
            BigDecimal rate = yahoo.getFxRateToEur(currency);
            if (rate == null) {
                log.error("No EUR rate for {} -- returning the balance UNCONVERTED, so any total "
                    + "including it is wrong until the rate is available", currency);
                return balance;
            }
            return balance.multiply(rate);
        }

        String symbol = ticker;
        BigDecimal price = getPriceEur(symbol);

        if (price == null) {
            // The returned number is now WRONG, not merely missing: an unconverted USD or
            // GBP balance flows into net worth and its snapshots as though it were EUR.
            // ERROR, because unlike a missing crypto price (which the wallet sync refuses to
            // record) this one silently corrupts a figure the user reads as authoritative.
            //
            // Deliberately NOT thrown, and deliberately still returning the raw balance:
            // toEur backs liveBalanceEur, the dashboard and the history charts, so throwing
            // would 500 all of them on one missing FX rate, and substituting zero would
            // understate net worth just as silently. Changing the number either way shifts
            // every user's totals; making the failure loud does not.
            log.error("No EUR rate for {} -- returning the balance UNCONVERTED, so any total "
                + "including it is wrong until the rate is available", symbol);
            return balance;
        }

        return balance.multiply(price);
    }

    /**
     * Backfill historical prices for the given tickers from external APIs.
     * Fetches daily prices for the last 12 months and saves as PriceSnapshots.
     * Skips dates that already have a snapshot.
     */
    public int backfillHistoricalPrices(Set<String> tickers, LocalDate from) {
        return backfillHistoricalPrices(tickers, from, false);
    }

    /**
     * {@code cryptoOnly} mirrors {@code refreshPrices(tickers, true)}: a coin CoinGecko cannot map
     * is left without history rather than sent to Yahoo, where the same symbol may belong to a
     * listed company (STX is Stacks in a wallet and Seagate on Nasdaq). Twelve months of the
     * company's closes recorded under the coin's symbol would feed the range P&L and the
     * last-known-price fallback for as long as the rows exist.
     */
    public int backfillHistoricalPrices(Set<String> tickers, LocalDate from, boolean cryptoOnly) {
        LocalDate to = LocalDate.now();
        int saved = 0;
        int failed = 0;
        int skipped = 0;

        for (String ticker : tickers) {
            String upper = ticker.toUpperCase(Locale.ROOT);
            if ("EUR".equals(upper)) continue;

            if (alreadyCovered(upper, from, to)) {
                skipped++;
                continue;
            }

            // Guard per ticker: this runs from PriceBackfillRunner, an ApplicationRunner, so
            // an unguarded throw here would fail Spring Boot startup outright.
            try {
                Map<LocalDate, BigDecimal> prices;
                if (coinGecko.supports(upper)) {
                    prices = coinGecko.getHistoricalPricesEur(upper, from, to);
                } else if (cryptoOnly) {
                    log.warn("No CoinGecko mapping for crypto ticker {} -- leaving its history empty "
                        + "rather than recording the stock trading under that symbol", upper);
                    skipped++;
                    continue;
                } else {
                    prices = yahoo.getHistoricalPricesEur(upper, from, to);
                }

                for (var entry : prices.entrySet()) {
                    if (priceSnapshotRepository.findByTickerAndDate(upper, entry.getKey()).isEmpty()) {
                        priceSnapshotRepository.save(PriceSnapshot.builder()
                            .ticker(upper)
                            .date(entry.getKey())
                            .priceEur(entry.getValue())
                            .build());
                        saved++;
                    }
                }

                log.info("Backfilled {} prices for {}", prices.size(), upper);
            } catch (Exception ex) {
                // ERROR, not WARN: the providers return an empty map for expected upstream
                // failures, so anything thrown here is a genuine bug. Still skip rather than
                // propagate -- this runs from PriceBackfillRunner, an ApplicationRunner, where
                // an escaping exception fails Spring Boot startup outright.
                failed++;
                log.error("Historical price backfill failed for {} -- skipping it", upper, ex);
            }
        }

        // The per-ticker guard means a run can "succeed" having backfilled almost nothing,
        // and the returned count alone cannot distinguish "1 of 1" from "1 of 100". Summarise
        // so a sparse backfill is visible without grepping for individual failures.
        if (failed > 0) {
            log.error("Historical price backfill completed with {} of {} tickers failing ({} prices saved, {} already covered)",
                failed, tickers.size(), saved, skipped);
        } else {
            log.info("Historical price backfill completed for {} tickers ({} prices saved, {} already covered)",
                tickers.size(), saved, skipped);
        }

        return saved;
    }

    /**
     * Whether {@code ticker} already has continuous history over the requested range, in which
     * case the backfill has nothing to add and the provider call is pure waste.
     *
     * <p>This runs at every boot, once per held ticker, against providers whose free tiers count
     * requests per IP — and the previous version re-requested twelve months of history for tickers
     * that already had all of it, only to discard every row as a duplicate. On this instance that
     * burned the whole rate-limit budget seconds after startup and left the price cache (which
     * does not survive a restart) with nothing to fill itself from.
     *
     * <p>It scans the whole range rather than probing its two ends. Checking the edges alone
     * declares a ticker covered as soon as it has an old row and a recent one, so an instance that
     * was off for three months — leaving a hole with history on both sides of it — would skip that
     * ticker at every boot and never fill the hole, while {@code HistoryService} flat-lines the
     * chart across it at the last pre-outage price. One query returns the range (at most ~370 rows,
     * one per day by {@code uk_price_snapshot_ticker_date}) and the gaps are measured in memory.
     *
     * <p>Gaps are tolerated up to {@link #MAX_HISTORY_GAP_DAYS} because markets close: a weekend is
     * a two-day hole in every equity series, and an Easter or Christmas week can stretch that to
     * five. A ticker whose history simply starts late — an asset younger than the range — is
     * reported uncovered and re-requested each boot, which is the pre-existing behaviour: we
     * cannot tell "the provider has nothing before this date" from "we never fetched it" without
     * asking.
     */
    private boolean alreadyCovered(String ticker, LocalDate from, LocalDate to) {
        List<PriceSnapshot> rows =
            priceSnapshotRepository.findByTickerInAndDateBetween(Set.of(ticker), from, to);
        if (rows.isEmpty()) return false;

        // The query orders by date ascending; walk from the range start so a missing head, a
        // missing tail and an interior hole are all the same check.
        LocalDate cursor = from;
        for (PriceSnapshot row : rows) {
            if (ChronoUnit.DAYS.between(cursor, row.getDate()) > MAX_HISTORY_GAP_DAYS) return false;
            cursor = row.getDate();
        }
        return ChronoUnit.DAYS.between(cursor, to) <= MAX_HISTORY_GAP_DAYS;
    }

    private record CachedPrice(BigDecimal price, Instant cachedAt) {
        boolean isExpired() {
            long ttl = price == null ? MISS_CACHE_TTL_SECONDS : CACHE_TTL_SECONDS;
            return Instant.now().isAfter(cachedAt.plusSeconds(ttl));
        }
    }

    /** Drop the in-memory price cache. Used by PriceFxCleanupRunner. */
    public void clearPriceCache() {
        priceCache.clear();
    }

    /**
     * Fetch intraday (hourly) prices for a ticker over the given time range.
     * Routes to CoinGecko for crypto, Yahoo Finance for stocks/ETFs.
     */
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(String ticker, LocalDateTime from, LocalDateTime to) {
        if (ticker == null || ticker.isBlank() || "EUR".equalsIgnoreCase(ticker)) {
            return Map.of();
        }

        String upper = ticker.toUpperCase(Locale.ROOT);

        if (coinGecko.supports(upper)) {
            return coinGecko.getIntradayPricesEur(upper, from, to);
        } else {
            return yahoo.getIntradayPricesEur(upper, from, to);
        }
    }
}
