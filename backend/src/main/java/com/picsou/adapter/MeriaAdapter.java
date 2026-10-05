package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.model.ExchangeType;
import com.picsou.port.CryptoExchangePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;

/**
 * Reads balances from Meria (<a href="https://docs.meria.com">docs.meria.com</a>).
 *
 * <p>Unlike Binance, Meria authenticates with a <em>single read-only API key</em> sent as an
 * {@code API-KEY} header — no secret, no request signing. A user generates it at
 * {@code dashboard.meria.com/account/api}.
 *
 * <p>A Meria balance is spread over three products, all shaped as {@code currencyCode} + amount,
 * each reported as its own {@code ExchangePosition} so the account page can show which is which:
 * <ul>
 *   <li>{@code GET /wallets} — spot balances</li>
 *   <li>{@code GET /stakings} — staking contracts, counted as {@code amount}, with {@code reward}
 *       reported as the interest <em>already inside</em> it (never added on top)</li>
 *   <li>{@code GET /lendings} — lending contracts, same shape as stakings</li>
 * </ul>
 *
 * <p><b>All or nothing.</b> Every one of the three calls must return a {@code success:true}
 * envelope; anything else fails the whole sync. The three products sum into <em>one</em> account
 * balance that {@code CryptoExchangeSyncService} writes to a daily {@code BalanceSnapshot}, so
 * silently dropping one call would not surface as a missing sub-account — it would quietly dent
 * the net-worth history with a smaller total, exactly the failure mode
 * {@code JsonRpcResponse.requireResult} exists to prevent on-chain.
 *
 * <p><b>The staking payload is big.</b> Each contract carries its full {@code variations} and
 * {@code credits} history, so {@code /stakings} runs to megabytes for a long-standing account and
 * needs {@link #MAX_RESPONSE_BYTES}; the framework default truncates it into a failure that
 * masquerades as an empty 200. {@link #fetch} reads the body as raw bytes for the same family of
 * reasons: no content-type negotiation, and the status, declared type and truncated body all reach
 * the log, so a shape nobody anticipated is diagnosable from one line instead of a guess.
 *
 * <p>Meria rate-limits at 30 requests per window ({@code x-ratelimit-limit}) and sits behind
 * Cloudflare, so the three calls are deliberately sequential and a non-JSON body is a realistic
 * failure — hence the truncated response body in the logs.
 */
@Component
public class MeriaAdapter implements CryptoExchangePort {

    private static final Logger log = LoggerFactory.getLogger(MeriaAdapter.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final String API_KEY_HEADER = "API-KEY";

    private static final TypeReference<MeriaEnvelope<MeriaWallet>> WALLETS = new TypeReference<>() {};
    private static final TypeReference<MeriaEnvelope<MeriaPosition>> POSITIONS = new TypeReference<>() {};
    private static final byte[] NO_BODY = new byte[0];

    /**
     * 16 MB, against WebClient's 256 KB default.
     *
     * <p>Not a safety margin — a hard requirement. {@code /stakings} returns each contract with
     * its full {@code variations} and {@code credits} history, one entry per reward credit, so a
     * few years of daily staking rewards runs to megabytes. The API offers no way to ask for less.
     * Over the default limit the body is never assembled and the failure surfaces as a
     * {@link org.springframework.core.io.buffer.DataBufferLimitException} — or, through a typed
     * codec, as a {@code WebClientResponseException} carrying the original {@code 200} and an
     * empty body, which reads exactly like an empty response and cost two wrong diagnoses.
     */
    static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    /** Shared with the tests so they exercise the real limit rather than the framework default. */
    static final ExchangeStrategies STRATEGIES = ExchangeStrategies.builder()
        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
        .build();

    private final WebClient client;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    @Autowired
    public MeriaAdapter(
        @Value("${app.meria.base-url:https://api.meria.com/v1}") String baseUrl,
        ObjectMapper objectMapper
    ) {
        this(WebClient.builder().baseUrl(baseUrl).exchangeStrategies(STRATEGIES).build(),
            objectMapper, DEFAULT_TIMEOUT);
    }

    // Package-private for tests — inject a WebClient backed by an ExchangeFunction.
    MeriaAdapter(WebClient client, ObjectMapper objectMapper, Duration timeout) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
    }

    @Override
    public String exchangeName() {
        return ExchangeType.MERIA.name();
    }

    @Override
    public boolean requiresApiSecret() {
        return false;
    }

    @Override
    public List<ExchangePosition> fetchPositions(String apiKey, String unusedSecret) {
        // A TreeMap per product, not one map overall: the same asset can be held in several
        // products at once, and each stays its own position line.
        List<ExchangePosition> positions = new ArrayList<>();

        Map<String, BigDecimal> spot = new TreeMap<>();
        for (MeriaWallet wallet : fetch("/wallets", apiKey, WALLETS)) {
            add(spot, wallet.currencyCode(), wallet.balance());
        }
        spot.forEach((symbol, quantity) -> positions.add(ExchangePosition.spot(symbol, quantity)));

        positions.addAll(contractPositions(Product.STAKING, fetch("/stakings", apiKey, POSITIONS)));
        positions.addAll(contractPositions(Product.LENDING, fetch("/lendings", apiKey, POSITIONS)));

        log.info("Meria: fetched {} non-zero positions across wallets, stakings and lendings",
            positions.size());
        return positions;
    }

    /**
     * Staking and lending contracts as positions, one per currency.
     *
     * <p>{@code interest} is a <em>decomposition</em> of the quantity, never an addition to it:
     * {@code amount} already carries the accrued reward, so the principal is what remains once the
     * reward is taken out. See {@link MeriaPosition#quantity()}.
     */
    private static List<ExchangePosition> contractPositions(Product product, List<MeriaPosition> contracts) {
        Map<String, ExchangePosition> byCurrency = new TreeMap<>();
        for (MeriaPosition contract : contracts) {
            String symbol = symbolOf(contract.currencyCode());
            BigDecimal quantity = contract.quantity();
            if (symbol == null || quantity.signum() <= 0) continue;
            // interest is a decomposition of quantity, so it only means anything inside
            // [0, quantity]: a contract cannot have earned more than it holds, and a negative
            // reward would make principal exceed the quantity. Outside that range both halves are
            // dropped — reporting the quantity with no decomposition. Keeping the reward while
            // blanking the principal, as this did, published the impossible figure on its own,
            // where a reader has nothing left to judge it against.
            BigDecimal reward = nz(contract.reward());
            boolean decomposable = reward.signum() >= 0 && reward.compareTo(quantity) <= 0;
            BigDecimal interest = decomposable ? reward : null;
            BigDecimal principal = decomposable ? quantity.subtract(reward) : null;
            byCurrency.merge(symbol,
                new ExchangePosition(product, symbol, quantity, principal, interest),
                MeriaAdapter::mergePositions);
        }
        return List.copyOf(byCurrency.values());
    }

    /**
     * Two contracts on the same currency and product read as one position.
     *
     * <p>A merged position is decomposed only if both sides were: summing a known interest with an
     * unknown one would report a total the principal cannot be reconciled against, which is the
     * same half-stated figure the range check above exists to avoid.
     */
    private static ExchangePosition mergePositions(ExchangePosition a, ExchangePosition b) {
        BigDecimal quantity = a.quantity().add(b.quantity());
        if (a.principal() == null || b.principal() == null) {
            return new ExchangePosition(a.product(), a.symbol(), quantity, null, null);
        }
        return new ExchangePosition(a.product(), a.symbol(), quantity,
            a.principal().add(b.principal()), nz(a.interest()).add(nz(b.interest())));
    }

    @Override
    public boolean testConnection(String apiKey, String unusedSecret) {
        try {
            // The only place reading an untyped body: /status is documented as the key-check call
            // but its success payload is not, so we assert no more than the shared envelope flag.
            JsonNode body = client.get()
                .uri("/status")
                .header(API_KEY_HEADER, apiKey)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .block();
            return body != null && body.path("success").asBoolean(false);
        } catch (Exception ex) {
            log.warn("Meria connection test failed: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * One Meria collection, or a {@link SyncException}. Never an empty list standing in for a
     * failure — see the all-or-nothing rule on the class.
     *
     * <p>Reads the body as text and parses it here rather than letting the codec bind it, because
     * <b>Meria answers a product the user hasn't subscribed to with HTTP 200 and an entirely empty
     * body</b> — observed on {@code /stakings} for an account holding only spot wallets. A codec
     * turns that into a decode error indistinguishable from a Cloudflare interstitial, which would
     * fail the whole sync for a perfectly healthy account. An empty body on a 2xx is "nothing
     * here"; a failure still arrives as a non-2xx (which {@code retrieve()} throws on) or as a
     * {@code success:false} envelope, so the all-or-nothing rule is unaffected.
     */
    private <T> List<T> fetch(String path, String apiKey, TypeReference<MeriaEnvelope<T>> type) {
        RawResponse raw;
        try {
            raw = client.get()
                .uri(path)
                .header(API_KEY_HEADER, apiKey)
                .accept(MediaType.APPLICATION_JSON)
                // exchangeToMono + byte[], not retrieve() + a typed body: the byte[] decoder
                // accepts every media type, so no combination of content type (absent, wrong,
                // malformed) can turn a successful response into a decode error, and a body Spring
                // could not assemble no longer reports itself as a WebClientResponseException
                // carrying the original 200. Status handling moves here because exchangeToMono,
                // unlike retrieve(), does not throw on 4xx/5xx.
                .exchangeToMono(response -> response.bodyToMono(byte[].class)
                    .defaultIfEmpty(NO_BODY)
                    .map(bytes -> new RawResponse(
                        response.statusCode().value(),
                        response.headers().contentType().map(Object::toString).orElse("<none>"),
                        bytes)))
                .timeout(timeout)
                .block();
        } catch (RuntimeException ex) {
            throw failure(path, ex, timeout);
        }

        if (raw == null) {
            throw new SyncException("Meria returned no response for " + path + ".");
        }
        if (raw.status() < 200 || raw.status() >= 300) {
            log.warn("Meria answered HTTP {} for {} (content-type {}) -- failing the sync: {}",
                raw.status(), path, raw.contentType(), truncate(raw.body()));
            if (raw.status() == 401 || raw.status() == 403) {
                throw new SyncException("Meria rejected the API key.");
            }
            if (raw.status() == 429) {
                throw new SyncException("Meria rate-limited the sync. Please try again later.");
            }
            throw new SyncException("Meria answered HTTP " + raw.status() + " for " + path + ".");
        }

        byte[] body = raw.body();
        if (isBlank(body)) {
            // A 2xx with nothing in it carries no failure signal — failures come as a non-2xx or
            // a success:false envelope — so it can only mean "no entries".
            log.info("Meria returned an empty body for {} on HTTP {} (content-type {}) -- reading "
                + "it as no entries", path, raw.status(), raw.contentType());
            return List.of();
        }

        MeriaEnvelope<T> envelope;
        try {
            // Parsed straight from the bytes: /stakings runs to MAX_RESPONSE_BYTES, and decoding
            // it to a String first would hold a second full copy of it alongside the array while
            // Jackson builds a third representation from it. Only the failure paths below need
            // the text form, and they need at most 200 characters of it.
            envelope = objectMapper.readValue(body, type);
        } catch (IOException ex) {
            // IOException rather than JacksonException (its subclass): the byte[] overload
            // declares the wider type, and reading from an in-memory array cannot fail any other
            // way — there is no stream left to break.
            //
            // WARN, not ERROR: an unparseable body is upstream's doing (a Cloudflare challenge
            // page is the likely one), not a request we built wrong — same severity grading as
            // CoinGeckoPriceProvider. The truncated body is what makes it diagnosable.
            log.warn("Could not parse Meria's response for {} -- failing the sync: {}",
                path, truncate(body));
            throw new SyncException("Meria returned an unreadable response for " + path + ".", ex);
        }

        if (envelope == null) {
            throw new SyncException("Meria returned an empty response for " + path + ".");
        }
        // Gate on the envelope, never on the status: Meria can answer success:false on HTTP 200.
        if (!Boolean.TRUE.equals(envelope.success())) {
            throw new SyncException("Meria rejected " + path + ": " + describe(envelope.error()) + ".");
        }
        return envelope.data() == null ? List.of() : envelope.data();
    }

    /**
     * Classifies a failed call and turns it into a {@link SyncException} — always. This mirrors
     * {@code CoinGeckoPriceProvider.handleFetchFailure} with the opposite outcome: prices may
     * degrade to "not valued this cycle", balances may not degrade to "smaller balance".
     *
     * <p>Unwraps first: {@code Mono.timeout()} signals a <em>checked</em> {@link TimeoutException}
     * that {@code block()} wraps in a reactor exception, so matching the declared type without
     * unwrapping would miss the most common real failure. Never logs the API key, only the path.
     */
    private static SyncException failure(String path, RuntimeException ex, Duration timeout) {
        Throwable cause = reactor.core.Exceptions.unwrap(ex);
        if (cause instanceof WebClientResponseException http) {
            // Reachable only for something the byte[] read could not absorb — the status is now
            // handled inline in fetch(). The exception type is logged because a
            // WebClientResponseException carrying a 2xx means a decode failure, not an HTTP one,
            // and that distinction cost a debugging round-trip once already.
            int status = http.getStatusCode().value();
            log.warn("Meria answered HTTP {} for {} ({}) -- failing the sync: {}",
                status, path, cause.getClass().getSimpleName(), lazyBody(http));
            if (status == 401 || status == 403) {
                return new SyncException("Meria rejected the API key.", ex);
            }
            if (status == 429) {
                return new SyncException("Meria rate-limited the sync. Please try again later.", ex);
            }
            return new SyncException("Meria answered HTTP " + status + " for " + path + ".", ex);
        }
        if (cause instanceof TimeoutException) {
            // The configured timeout, not the constant: the package-private constructor lets a
            // caller (the tests, today) pass its own, and logging the default then contradicts
            // what actually happened.
            log.warn("Meria request for {} timed out after {} -- failing the sync", path, timeout);
            return new SyncException("Meria took too long to answer. Please try again later.", ex);
        }
        if (cause instanceof WebClientRequestException) {
            log.warn("Meria request for {} could not reach the API ({}) -- failing the sync",
                path, cause.getMessage());
            return new SyncException("Meria is unreachable. Please try again later.", ex);
        }
        // Not a recognised upstream failure: a defect on our side, or a shape nothing above
        // anticipated. Log the type and the stacktrace so it stays diagnosable rather than
        // reading as an outage.
        log.error("Meria request for {} failed unexpectedly ({}) -- failing the sync",
            path, cause.getClass().getName(), ex);
        return new SyncException("Could not read " + path + " from Meria.", ex);
    }

    /** Adds one entry to the per-currency running total, skipping what cannot be counted. */
    private static void add(Map<String, BigDecimal> totals, String currencyCode, BigDecimal quantity) {
        String symbol = symbolOf(currencyCode);
        if (symbol == null || quantity == null || quantity.signum() <= 0) return;
        totals.merge(symbol, quantity, BigDecimal::add);
    }

    /**
     * The ticker for a Meria currency code, or {@code null} if it cannot be used.
     *
     * <p>{@code Locale.ROOT} is mandatory: a bare {@code toUpperCase()} under a Turkish default
     * locale mangles identifiers (documented in docs/features/crypto-tracking.md).
     */
    private static String symbolOf(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            log.warn("Meria returned an entry with no currencyCode -- skipped");
            return null;
        }
        return currencyCode.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String describe(MeriaError error) {
        if (error == null) return "no error detail";
        if (error.message() == null) return "code " + error.code();
        return error.message() + " (code " + error.code() + ")";
    }

    /**
     * Defers decoding the upstream error body until the log level is known to be enabled, and caps
     * it — Meria sits behind Cloudflare, whose challenge pages are multi-kilobyte HTML.
     */
    private static Object lazyBody(WebClientResponseException http) {
        return new Object() {
            @Override public String toString() {
                return truncate(http.getResponseBodyAsByteArray());
            }
        };
    }

    private static String truncate(byte[] body) {
        if (body == null || body.length == 0) return "<empty body>";
        // Decode only what can be logged. 200 characters occupy at most 800 UTF-8 bytes, so
        // slicing there can never drop a character that would have been shown — at worst it
        // splits the one just past the limit, which is then cut away anyway.
        int slice = Math.min(body.length, 800);
        String text = new String(body, 0, slice, StandardCharsets.UTF_8);
        if (text.isBlank()) return "<empty body>";
        if (slice == body.length && text.length() <= 200) return text;
        return text.substring(0, Math.min(text.length(), 200)) + "... (truncated)";
    }

    /**
     * Whether a body holds nothing but whitespace, without decoding it.
     *
     * <p>Any byte outside ASCII whitespace is content, including every continuation byte of a
     * multi-byte character (they are negative as {@code byte}, and never whitespace), so the scan
     * stops at the first one — a 16 MB payload costs a single comparison.
     */
    private static boolean isBlank(byte[] body) {
        for (byte b : body) {
            if (!Character.isWhitespace(b)) return false;
        }
        return true;
    }

    /** A response read without interpreting it: status, declared content type, body as bytes. */
    private record RawResponse(int status, String contentType, byte[] body) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MeriaEnvelope<T>(Boolean success, List<T> data, MeriaError error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MeriaError(Integer code, String message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MeriaWallet(String currencyCode, BigDecimal balance) {}

    /** A staking or lending contract — same shape for both products. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MeriaPosition(String currencyCode, BigDecimal amount, BigDecimal reward, BigDecimal lockedReward) {
        /**
         * The contract's holding — {@code amount} alone.
         *
         * <p>{@code reward} is <b>not</b> a pending balance to add on top: it is the contract's
         * <em>cumulative</em> interest to date, and {@code amount} already reflects it. Verified
         * against a real account: Meria's UI showed 33.154 ATOM total and 13.424 ATOM of total
         * interest, and this adapter reported 46.577 — the sum, i.e. the interest counted twice.
         * The same holds without compounding, where accrued interest is credited into the spot
         * wallet instead (the contract's {@code credits} entries carry {@code released: 1}) and so
         * arrives through {@code /wallets}; adding {@code reward} would double-count it there too.
         *
         * <p>{@code lockedReward} is left out for the same reason and one more: whether it sits
         * inside {@code amount} could not be established (every observed contract reports 0), and
         * understating a holding is recoverable in a way that overstating net worth is not.
         */
        BigDecimal quantity() {
            return nz(amount);
        }
    }
}
