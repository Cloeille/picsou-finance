package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.picsou.port.LogoProviderPort;
import com.picsou.port.PriceProviderPort;
import com.picsou.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.LongFunction;
import java.util.function.Predicate;

/**
 * Fetches crypto prices from CoinGecko public API (no API key required for free tier).
 * Supports tickers like BTC, ETH, SOL, etc.
 */
@Component
public class CoinGeckoPriceProvider implements PriceProviderPort, LogoProviderPort {

    private static final Logger log = LoggerFactory.getLogger(CoinGeckoPriceProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HISTORY_TIMEOUT = Duration.ofSeconds(15);

    /** Applied when a 429 arrives without a usable {@code Retry-After}. */
    private static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(60);

    /**
     * Ceiling on a server-supplied {@code Retry-After}. A misconfigured (or hostile) value of
     * "86400" would otherwise leave the instance unable to price anything for a day, long after
     * the real limit lifted.
     */
    private static final Duration MAX_COOLDOWN = Duration.ofMinutes(15);

    /** Page size of {@code /coins/markets}, the API maximum: one request per learning batch. */
    private static final int SYMBOL_LOOKUP_PAGE = 250;

    /**
     * A symbol's leading coin must be worth at least this many times the next ranked one to be
     * taken as <em>the</em> coin for that ticker. Every curated coin clears it by a wide margin
     * (DAI, the closest, by 90x); ONE, shared by six coins, sits at 3.6x and must not pass.
     */
    private static final BigDecimal DOMINANCE_RATIO = BigDecimal.TEN;

    private final WebClient webClient;
    private final CryptoAssetRegistry registry;

    /**
     * While this instant is in the future, no request is sent at all.
     *
     * <p>CoinGecko's free tier is keyed on the caller's IP, and it counts the requests it
     * rejects: answering a 429 with more traffic is what turns a one-minute limit into a
     * morning of missing prices. Every valuation path shares the budget, so the pause is
     * per-adapter rather than per-call-site.
     *
     * <p>Volatile, not a lock: concurrent readers racing on the boundary either skip one call
     * they could have made or make one they could have skipped, and neither matters.
     */
    private volatile Instant rateLimitedUntil = Instant.EPOCH;

    @Autowired
    public CoinGeckoPriceProvider(CryptoAssetRegistry registry) {
        this(WebClient.builder()
            .baseUrl("https://api.coingecko.com/api/v3")
            .defaultHeader("Accept", "application/json")
            .build(), registry);
    }

    // Package-private constructor for tests — inject a WebClient backed by an ExchangeFunction.
    CoinGeckoPriceProvider(WebClient webClient, CryptoAssetRegistry registry) {
        this.webClient = webClient;
        this.registry = registry;
    }

    /**
     * A ticker is crypto exactly when the {@link CryptoAssetRegistry} knows it. That makes this
     * the router PriceService relies on, so what enters the registry matters: see
     * {@code CompositePriceProvider#learnCryptoTickers} for why a learned row can never be a
     * symbol a listed equity owns.
     */
    @Override
    public boolean supports(String ticker) {
        return registry.find(ticker).isPresent();
    }

    @Override
    public boolean supportsCrypto(String ticker) {
        return supports(ticker);
    }

    /**
     * The stored display name of a known crypto {@code ticker} (e.g. BTC &rarr; "Bitcoin"), or
     * {@code null} if the ticker is unknown. Used by {@link OpenFigiIsinConverter} to name Trade
     * Republic's on-platform crypto holdings.
     */
    public String displayName(String ticker) {
        return registry.find(ticker).map(CryptoAssetRegistry.Coin::name).orElse(null);
    }

    /**
     * The CoinGecko-hosted image for each known crypto {@code ticker}, or nothing for the rest.
     *
     * <p>Reads {@code image} off {@code /coins/markets}, the one endpoint that returns a mark for
     * a whole batch of coin ids — so a portfolio page costs a single request no matter how many
     * assets it shows. Deliberately crypto-only: this is the same registry-gated
     * {@link CryptoAssetRegistry} the prices come from, so an equity ticker resolves to no image rather
     * than to some unrelated coin that happens to share its symbol.
     *
     * <p>The URLs point at {@code coin-images.coingecko.com} and are handed to the browser to
     * fetch, the same shape as the Enable Banking institution logos Picsou already hotlinks
     * ({@code docs/features/bank-logos.md}). Nothing is stored server-side: an image is a
     * read-only attribute of the coin, not state Picsou owns, so caching it durably would add a
     * migration and a lifecycle for a value the provider already serves for free. An unknown or
     * unmapped ticker is simply absent from the map and the UI shows its ticker.
     *
     * <p>Implements {@link LogoProviderPort}; the service above depends on that, not on this
     * class, so a second mark source is a bean rather than an edit here.
     */
    @Override
    public Map<String, String> getLogoUrls(Set<String> tickers) {
        if (tickers == null || tickers.isEmpty()) return Map.of();

        Set<String> supported = tickers.stream()
            .filter(this::supports)
            .map(t -> t.toUpperCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        if (supported.isEmpty()) return Map.of();
        // A 429 on the price path arms this same cooldown, and vice versa: a paused provider
        // must not be asked for decoration while it is still serving prices.
        if (coolingDown("logos", supported)) return Map.of();

        String ids = supported.stream()
            .map(this::coinId)
            .filter(Objects::nonNull)
            .reduce((a, b) -> a + "," + b)
            .orElse("");

        try {
            List<MarketEntry> markets = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/coins/markets")
                    .queryParam("vs_currency", "eur")
                    .queryParam("ids", ids)
                    .queryParam("per_page", Math.max(supported.size(), 1))
                    .build())
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<List<MarketEntry>>() {})
                .timeout(TIMEOUT)
                .block();

            if (markets == null) {
                log.warn("CoinGecko returned an empty body for logos {} -- returning no logos", LogSanitizer.safe(supported));
                return Map.of();
            }

            Map<String, String> byCoinId = new HashMap<>();
            for (MarketEntry entry : markets) {
                if (entry != null && entry.id() != null && entry.image() != null && !entry.image().isBlank()) {
                    byCoinId.put(entry.id(), entry.image());
                }
            }
            if (byCoinId.isEmpty()) {
                log.warn("CoinGecko returned no image for any of {} requested crypto tickers -- the UI will show their tickers",
                    LogSanitizer.safe(supported));
                return Map.of();
            }

            Map<String, String> result = new HashMap<>();
            for (String ticker : supported) {
                String url = byCoinId.get(coinId(ticker));
                if (url != null) result.put(ticker, url);
            }
            return result;
        } catch (RuntimeException ex) {
            // A logo is decoration: the price path must not degrade because the image call
            // failed, and a 429 here is worth arming the shared cooldown so a render loop does
            // not keep asking. Anything that is not an expected upstream failure is a bug in
            // this method and is rethrown -- same call as the price path, so both routes through
            // one classifier.
            if (isExpectedUpstreamFailure(reactor.core.Exceptions.unwrap(ex))) {
                handleFetchFailure("logos", supported, TIMEOUT, ex);
                return Map.of();
            }
            throw ex;
        }
    }

    @Override
    public Map<String, BigDecimal> getPricesEur(Set<String> tickers) {
        // Normalize once, up front: supports() is case-insensitive, so leaving mixed case
        // in here forces every later step to re-upper-case and makes the result map's keys
        // disagree with this set (which previously produced a false "no EUR price for 2 of
        // 2 tickers" warning, and a count computed over two different domains).
        Set<String> supported = tickers.stream()
            .filter(this::supports)
            .map(t -> t.toUpperCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

        if (supported.isEmpty()) return Map.of();
        if (coolingDown("spot prices", supported)) return Map.of();

        String ids = supported.stream()
            .map(this::coinId)
            .filter(Objects::nonNull)
            .reduce((a, b) -> a + "," + b)
            .orElse("");

        try {
            Map<String, PriceData> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/simple/price")
                    .queryParam("ids", ids)
                    .queryParam("vs_currencies", "eur")
                    .build())
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, PriceData>>() {})
                .timeout(TIMEOUT)
                .block();

            if (response == null) {
                log.warn("CoinGecko returned an empty body for spot prices {} -- returning no prices", LogSanitizer.safe(supported));
                return Map.of();
            }

            Map<String, BigDecimal> result = new HashMap<>();
            for (String ticker : supported) {
                String coinId = coinId(ticker);
                if (coinId != null && response.containsKey(coinId)) {
                    BigDecimal price = response.get(coinId).eur();
                    if (price != null) result.put(ticker, price);
                }
            }
            // Both sides are now normalized, so this is a like-for-like comparison. Gate on
            // the set rather than a size check, which could warn with an empty list.
            Set<String> missing = supported.stream()
                .filter(t -> !result.containsKey(t))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            if (!missing.isEmpty()) {
                log.warn("CoinGecko had no EUR price for {} of {} requested tickers: {}",
                    missing.size(), supported.size(), LogSanitizer.safe(missing));
            }
            return result;
        } catch (RuntimeException ex) {
            handleFetchFailure("spot prices", supported, TIMEOUT, ex);
            return Map.of();
        }
    }

    private String coinId(String ticker) {
        return registry.find(ticker).map(CryptoAssetRegistry.Coin::id).orElse(null);
    }

    /**
     * What {@link #resolveBySymbol} found. {@code answered} is false when CoinGecko could not be
     * asked (cooling down, outage): the tickers missing from {@code accepted} are then
     * <em>undecided</em>, not refused, and the caller must retry soon rather than remember a no.
     */
    public record Resolution(Map<String, CryptoAssetRegistry.Coin> accepted, boolean answered) {}

    /**
     * Looks each ticker up by symbol on {@code /coins/markets} (one request for the batch) and
     * returns the coins it is safe to treat as <em>the</em> coin for that ticker. Persists
     * nothing: deciding whether a symbol may be learned at all is the caller's job.
     *
     * <p>Only candidates whose symbol equals the ticker and that carry a {@code market_cap_rank}
     * count: CoinGecko lists hundreds of unranked bridged and wrapped homonyms per symbol
     * ({@code include_tokens=all}), and a wrong id is a wrong valuation that nothing surfaces.
     * The best-ranked candidate is accepted when alone, or when its market cap is at least
     * {@link #DOMINANCE_RATIO} times the runner-up's; otherwise the symbol is ambiguous and is
     * rejected with a WARN saying why.
     */
    public Resolution resolveBySymbol(Set<String> tickers) {
        Set<String> wanted = tickers == null ? Set.of() : tickers.stream()
            .filter(t -> t != null && !t.isBlank())
            .map(t -> t.trim().toUpperCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        if (wanted.isEmpty()) return new Resolution(Map.of(), true);
        if (coolingDown("symbol lookup", wanted)) return new Resolution(Map.of(), false);

        String symbols = wanted.stream()
            .map(t -> t.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.joining(","));

        try {
            List<MarketEntry> markets = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/coins/markets")
                    .queryParam("vs_currency", "eur")
                    .queryParam("symbols", symbols)
                    .queryParam("include_tokens", "all")
                    .queryParam("per_page", SYMBOL_LOOKUP_PAGE)
                    .build())
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<List<MarketEntry>>() {})
                .timeout(TIMEOUT)
                .block();

            if (markets == null) {
                log.warn("CoinGecko returned an empty body for symbol lookup {} -- learning nothing", LogSanitizer.safe(wanted));
                return new Resolution(Map.of(), false);
            }

            Map<String, CryptoAssetRegistry.Coin> accepted = new TreeMap<>();
            for (String ticker : wanted) {
                List<MarketEntry> ranked = markets.stream()
                    .filter(e -> e != null && e.id() != null && e.symbol() != null
                        && e.symbol().equalsIgnoreCase(ticker) && e.rank() != null)
                    .sorted(java.util.Comparator.comparing(MarketEntry::rank))
                    .toList();
                pickDominant(ticker, ranked).ifPresent(coin -> accepted.put(ticker, coin));
            }
            return new Resolution(accepted, true);
        } catch (RuntimeException ex) {
            handleFetchFailure("symbol lookup", wanted, TIMEOUT, ex);
            return new Resolution(Map.of(), false);
        }
    }

    /** The accept rule of {@link #resolveBySymbol}; logs the reason for a rejection. */
    private static Optional<CryptoAssetRegistry.Coin> pickDominant(String ticker, List<MarketEntry> ranked) {
        if (ranked.isEmpty()) {
            log.warn("CoinGecko has no ranked coin for symbol {} -- not learning it", LogSanitizer.safe(ticker));
            return Optional.empty();
        }
        MarketEntry top = ranked.get(0);
        if (ranked.size() > 1) {
            BigDecimal topCap = top.marketCap();
            BigDecimal nextCap = ranked.get(1).marketCap();
            if (topCap == null || nextCap == null || nextCap.signum() <= 0
                    || topCap.compareTo(nextCap.multiply(DOMINANCE_RATIO)) < 0) {
                String ratio = topCap == null || nextCap == null || nextCap.signum() <= 0
                    ? "unknown"
                    : topCap.divide(nextCap, 1, RoundingMode.HALF_UP).toPlainString() + "x";
                log.warn("CoinGecko symbol {} is ambiguous: {} coins share it and the best ranked is only {} the next -- not learning it",
                    LogSanitizer.safe(ticker), ranked.size(), ratio);
                return Optional.empty();
            }
        }
        String name = top.name() == null || top.name().isBlank() ? ticker : top.name();
        // The one trace of a learned coin: the registry insert that follows is silent, and the
        // rank is what a reviewer needs to judge the pick.
        log.info("CoinGecko symbol {} resolves to {} ({}), market cap rank {}",
            LogSanitizer.safe(ticker), LogSanitizer.safe(top.id()), LogSanitizer.safe(name), top.rank());
        return Optional.of(new CryptoAssetRegistry.Coin(top.id(), name));
    }

    /**
     * Whether a failure is the provider's to own — an HTTP status, a timeout, or a request that
     * never reached the server. Those are logged and turned into "no answer"; anything else is
     * ours and is rethrown.
     *
     * <p>Shared so the decision is written once. The logo path needs the same swallow-or-rethrow
     * call as the price path, and a second {@code instanceof} chain would be a divergence trap:
     * adding a case here later would leave that other gate passing the new failure through as a
     * 500 on a page render.
     */
    private static boolean isExpectedUpstreamFailure(Throwable cause) {
        return cause instanceof WebClientResponseException
            || cause instanceof TimeoutException
            || cause instanceof WebClientRequestException;
    }

    /**
     * Classifies a failed CoinGecko call, and decides whether it is ours to swallow.
     *
     * <p><b>Expected upstream failures</b> (HTTP error, unreachable API, timeout) are logged
     * and the caller returns no prices. That contract is load-bearing: callers read a missing
     * price as "not valued this cycle", never "not held" — {@code WalletSyncService} keys its
     * holdings prune on on-chain balances, so a CoinGecko blip leaves holdings and their cost
     * basis intact. Severity lives in the log, graded by whose problem it is.
     *
     * <p><b>Anything else</b> — an NPE, a {@link ClassCastException}, a parse defect — is
     * <em>rethrown</em>. Swallowing a real bug into an empty map hides it behind data that
     * merely looks unpriced. This is safe because the batch callers guard their own loops
     * ({@code SchedulerService.dailySnapshots} / {@code refreshPrices},
     * {@code PriceService.backfillHistoricalPrices}), so one bad ticker cannot abort a run,
     * and {@code WalletSyncService} already separates expected sync failures from bugs.
     *
     * <p>Note it unwraps first: {@code Mono.timeout()} signals a <em>checked</em>
     * {@link TimeoutException}, which {@code block()} wraps in a reactor
     * {@code ReactiveException}. Matching on the declared type without unwrapping would miss
     * timeouts entirely — the most common real CoinGecko failure.
     *
     * <p>A 429 additionally arms {@link #rateLimitedUntil}, which is why this is an instance
     * method: the classification and the pause are the same decision.
     */
    private void handleFetchFailure(String operation, Object context, Duration timeout, RuntimeException ex) {
        Throwable cause = reactor.core.Exceptions.unwrap(ex);
        String where = LogSanitizer.safe(context);
        if (cause instanceof WebClientResponseException http) {
            int status = http.getStatusCode().value();
            if (status == 429) {
                Duration cooldown = retryAfter(http);
                rateLimitedUntil = Instant.now().plus(cooldown);
                log.warn("CoinGecko rate-limited (429) fetching {} for {} -- returning no prices, "
                    + "and pausing calls for {}s", operation, where, cooldown.toSeconds());
            } else if (http.getStatusCode().is5xxServerError()) {
                // Their outage, not our bug: WARN, matching how the rest of the codebase
                // grades expected external failures. These callers are on a scheduler and
                // run per-ticker, so an hours-long outage would otherwise pour ERROR lines
                // (each carrying a full HTML error page) into a self-hosted instance's log.
                log.warn("CoinGecko server error (HTTP {}) fetching {} for {} -- returning no prices: {}",
                    status, operation, where, bodySnippet(http));
            } else if (status == 400 || status == 404) {
                // A malformed request or an unknown coin id points at a bad registry
                // entry -- something we can actually fix, so ERROR.
                log.error("CoinGecko rejected the {} request for {} with HTTP {} -- returning no prices: {}",
                    operation, where, status, bodySnippet(http));
            } else {
                // Other 4xx (401/403 free-tier restrictions, 451...) are the provider's
                // access policy, not a bug on our side: WARN like the other outage cases.
                log.warn("CoinGecko refused the {} request for {} with HTTP {} -- returning no prices: {}",
                    operation, where, status, bodySnippet(http));
            }
        } else if (cause instanceof TimeoutException) {
            log.warn("CoinGecko {} request for {} timed out after {} -- returning no prices",
                operation, where, timeout);
        } else if (cause instanceof WebClientRequestException) {
            // Never reached the server at all: DNS failure, connection refused/reset, TLS
            // handshake. Same class of expected outage as a 5xx -- WARN, and without the
            // stacktrace, which would otherwise flood the log for the whole outage.
            log.warn("CoinGecko {} request for {} could not reach the API ({}) -- returning no prices",
                operation, where, LogSanitizer.safe(cause.getMessage()));
        } else if (!isExpectedUpstreamFailure(cause)) {
            // Not an upstream failure -- an NPE, ClassCastException or parse defect on our
            // side. Rethrow rather than return an empty map: a bug that presents as "no
            // prices" is indistinguishable from a quiet outage and would never get fixed.
            throw ex;
        } else {
            // An expected failure with no branch of its own above: {@link
            // #isExpectedUpstreamFailure} and these three cases have drifted apart. Log it
            // rather than swallow it silently, so the next addition has to give it a severity.
            log.warn("CoinGecko {} request for {} failed with an unclassified expected failure"
                + " ({}: {}) -- returning no prices",
                operation, where, cause.getClass().getSimpleName(), LogSanitizer.safe(cause.getMessage()));
        }
    }

    /**
     * True when a recent 429 is still in force, in which case the caller must return no prices
     * without touching the network.
     *
     * <p>DEBUG, not WARN: the 429 that armed the pause was already logged once at WARN, and this
     * runs on every read for as long as the pause lasts. The callers turn "no prices" into the
     * last recorded ones ({@code PriceService}), so a skipped call is not a user-visible gap.
     */
    private boolean coolingDown(String operation, Object context) {
        Instant until = rateLimitedUntil;
        if (Instant.now().isBefore(until)) {
            log.debug("CoinGecko still rate-limited until {} -- skipping the {} request for {}",
                until, operation, LogSanitizer.safe(context));
            return true;
        }
        return false;
    }

    /**
     * The pause a 429 buys us: the server's {@code Retry-After} when it sends a sane one,
     * {@link #DEFAULT_COOLDOWN} otherwise. Only the delta-seconds form is read — CoinGecko sends
     * that when it sends the header at all, and an HTTP-date would need clock-skew handling for
     * no practical gain.
     */
    private static Duration retryAfter(WebClientResponseException http) {
        String header = http.getHeaders().getFirst("Retry-After");
        if (header == null || header.isBlank()) return DEFAULT_COOLDOWN;
        try {
            long seconds = Long.parseLong(header.trim());
            if (seconds <= 0) return DEFAULT_COOLDOWN;
            return Duration.ofSeconds(Math.min(seconds, MAX_COOLDOWN.toSeconds()));
        } catch (NumberFormatException ex) {
            return DEFAULT_COOLDOWN;
        }
    }

    /**
     * Walks CoinGecko's {@code prices} field — documented as an array of
     * {@code [epochMillis, price]} pairs — handing each well-formed pair to {@code consumer}.
     *
     * <p>Every step is checked rather than cast. A shape change upstream (an object instead
     * of an array, string-encoded numbers, a short pair) must degrade to a warn and a skip:
     * since {@link #handleFetchFailure} now rethrows anything that is not an upstream
     * failure, a blind cast here would turn a CoinGecko format change into a
     * {@link ClassCastException} propagating into the daily snapshot batch.
     */
    private static void forEachPricePoint(
        Map<String, Object> response, String context, BiConsumer<Long, Double> consumer) {

        Object raw = response.getOrDefault("prices", List.of());
        if (!(raw instanceof List<?> rawPrices)) {
            log.warn("CoinGecko returned a non-list 'prices' field ({}) for {} -- returning no prices",
                raw == null ? "null" : raw.getClass().getSimpleName(), context);
            return;
        }

        int skipped = 0;
        for (Object entry : rawPrices) {
            if (!(entry instanceof List<?> pair) || pair.size() < 2
                || !(pair.get(0) instanceof Number timestamp)
                || !(pair.get(1) instanceof Number price)) {
                skipped++;
                continue;
            }
            consumer.accept(timestamp.longValue(), price.doubleValue());
        }

        // Once per call, not per entry: a wholesale format change would otherwise emit one
        // line per data point, thousands of them for a long range.
        if (skipped > 0) {
            log.warn("CoinGecko returned {} malformed price points (of {}) for {} -- skipped",
                skipped, rawPrices.size(), context);
        }
    }

    /**
     * The upstream error body, capped so one bad gateway's multi-kilobyte HTML page can't fill
     * the log, and sanitised. Built eagerly: it only runs on a failed request, and a plain
     * {@code String} returned by {@link LogSanitizer#safe} is what lets static analysis see
     * the sanitisation (a deferred {@code toString()} hides it).
     */
    private static String bodySnippet(WebClientResponseException http) {
        String body = http.getResponseBodyAsString();
        if (body == null || body.isBlank()) return "<empty body>";
        return LogSanitizer.safe(body.length() <= 200 ? body : body.substring(0, 200) + "... (truncated)");
    }

    /**
     * Fetch hourly prices for a crypto ticker from CoinGecko over the last 24H.
     * CoinGecko's market_chart/range returns hourly data for ranges < 90 days.
     */
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(String ticker, LocalDateTime from, LocalDateTime to) {
        return pricesInRange(
            "intraday prices",
            ticker,
            from.atZone(ZoneId.systemDefault()).toEpochSecond(),
            to.atZone(ZoneId.systemDefault()).toEpochSecond(),
            epochMillis -> Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDateTime(),
            dt -> !dt.isBefore(from) && !dt.isAfter(to)
        );
    }

    /**
     * One {@code market_chart/range} read, keyed by whatever calendar unit the caller
     * cares about: the endpoint, the cooldown guard, the empty-body handling and the
     * failure classification are the same whether the series is hourly or daily. Only
     * the key type and the range filter differ, so the caller supplies those.
     *
     * <p>The range is also re-checked client-side: CoinGecko rounds the window it
     * serves and may return points just outside the one that was asked for.
     */
    private <K> Map<K, BigDecimal> pricesInRange(
        String operation,
        String ticker,
        long fromEpoch,
        long toEpoch,
        LongFunction<K> keyOf,
        Predicate<K> inRange
    ) {
        String coinId = coinId(ticker);
        if (coinId == null) return Map.of();
        String context = LogSanitizer.safe(ticker + " (" + coinId + ")");
        if (coolingDown(operation, context)) return Map.of();

        try {
            Map<String, Object> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/coins/{id}/market_chart/range")
                    .queryParam("vs_currency", "eur")
                    .queryParam("from", fromEpoch)
                    .queryParam("to", toEpoch)
                    .build(coinId))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(HISTORY_TIMEOUT)
                .block();

            if (response == null) {
                log.warn("CoinGecko returned an empty body for {} of {}", operation, context);
                return Map.of();
            }

            Map<K, BigDecimal> prices = new LinkedHashMap<>();
            forEachPricePoint(response, context, (timestamp, price) -> {
                if (price <= 0) return;
                K key = keyOf.apply(timestamp);
                if (inRange.test(key)) {
                    prices.put(key, BigDecimal.valueOf(price).setScale(8, RoundingMode.HALF_UP));
                }
            });

            log.debug("Fetched {} {} for {} from CoinGecko", prices.size(), operation, context);
            return prices;
        } catch (RuntimeException ex) {
            handleFetchFailure(operation, context, HISTORY_TIMEOUT, ex);
            return Map.of();
        }
    }

    /**
     * Fetch historical daily prices for a crypto ticker from CoinGecko.
     * Returns a map of date -> priceEur.
     */
    public Map<LocalDate, BigDecimal> getHistoricalPricesEur(String ticker, LocalDate from, LocalDate to) {
        return pricesInRange(
            "historical prices",
            ticker,
            from.atStartOfDay(ZoneId.systemDefault()).toEpochSecond(),
            to.atStartOfDay(ZoneId.systemDefault()).toEpochSecond(),
            epochMillis -> Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate(),
            date -> !date.isBefore(from) && !date.isAfter(to)
        );
    }

    /** The subset of {@code /coins/markets} we read: id, image, and what the symbol lookup ranks on. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MarketEntry(
        String id,
        String image,
        String symbol,
        String name,
        @JsonProperty("market_cap_rank") Integer rank,
        @JsonProperty("market_cap") BigDecimal marketCap) {}

    static class PriceData {
        private BigDecimal eur;

        @JsonAnySetter
        public void setField(String key, Object value) {
            if ("eur".equals(key) && value instanceof Number n) {
                this.eur = BigDecimal.valueOf(n.doubleValue());
            }
        }

        public BigDecimal eur() { return eur; }
    }

    /** CoinGecko does not handle FX rates — always returns {@code null}. */
    @Override
    public BigDecimal getFxRateToEur(String currency) {
        return null;
    }
}
