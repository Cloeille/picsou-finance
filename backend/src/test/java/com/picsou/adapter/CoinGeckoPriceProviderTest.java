package com.picsou.adapter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/**
 * Covers the adapter's failure contract, which is the part of this class that repeatedly
 * regressed: an <em>expected</em> upstream failure must return no prices and be logged at a
 * severity matching whose problem it is, while a genuine bug must propagate rather than hide
 * behind an empty map.
 *
 * <p>Failures are injected as {@code Mono.error(...)} rather than by real I/O, which also
 * exercises the reactor wrapping that matters here: {@code block()} wraps a <em>checked</em>
 * exception (notably {@link TimeoutException}) in a {@code ReactiveException}, so the adapter
 * has to unwrap before classifying. Matching on declared types alone would silently stop
 * catching timeouts.
 */
class CoinGeckoPriceProviderTest {

    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CoinGeckoPriceProvider.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private CoinGeckoPriceProvider providerReturning(Mono<ClientResponse> response) {
        ExchangeFunction exchange = request -> response;
        return new CoinGeckoPriceProvider(WebClient.builder().exchangeFunction(exchange).build());
    }

    private CoinGeckoPriceProvider providerWithJson(String json) {
        return providerReturning(Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(json).build()));
    }

    private CoinGeckoPriceProvider providerWithStatus(HttpStatus status, String body) {
        return providerReturning(Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body).build()));
    }

    private CoinGeckoPriceProvider providerFailingWith(Throwable ex) {
        return providerReturning(Mono.error(ex));
    }

    private List<ILoggingEvent> eventsAt(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    // ── Ticker coverage ───────────────────────────────────────────────────────

    @Test
    void supports_coversTheCoinsReachableThroughMeria() {
        // An unmapped ticker is left unpriced by refreshCryptoPrices, so dropping an entry here
        // shrinks a balance without failing anything.
        var provider = providerWithJson("{}");

        assertThat(List.of("EGLD", "XTZ", "ALGO", "KSM", "TIA", "INJ", "ROSE", "KAVA",
                "ZEC", "ETC", "XLM", "TRX", "BCH", "MINA", "OSMO", "AKT", "DYDX", "CELO",
                "BAND", "XMR", "VET", "HBAR", "GRT"))
            .allSatisfy(ticker -> assertThat(provider.supports(ticker)).as(ticker).isTrue());
    }

    @Test
    void supports_rejectsSymbolsSharedWithListedEquities() {
        // This map routes PriceService: claiming a symbol here sends the *stock* of the same name
        // to CoinGecko too. Seagate (STX), TD SYNNEX (SNX), Solaris Energy (SEI) and Alpha Pro
        // Tech (APT) must keep going to Yahoo, even though Meria offers coins with those symbols.
        var provider = providerWithJson("{}");

        assertThat(List.of("STX", "SNX", "SEI", "APT"))
            .allSatisfy(ticker -> assertThat(provider.supports(ticker)).as(ticker).isFalse());
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void getPricesEur_parsesPrices_andKeysThemUpperCase() {
        var provider = providerWithJson("{\"bitcoin\":{\"eur\":50000.0},\"ethereum\":{\"eur\":3000.0}}");

        Map<String, BigDecimal> prices = provider.getPricesEur(Set.of("BTC", "ETH"));

        assertThat(prices).containsOnlyKeys("BTC", "ETH");
        assertThat(prices.get("BTC")).isEqualByComparingTo("50000");
        assertThat(prices.get("ETH")).isEqualByComparingTo("3000");
    }

    @Test
    void getPricesEur_acceptsLowerCaseTickers_andStillKeysResultsUpperCase() {
        // supports() is case-insensitive, so the port contract permits lower case. The
        // result must still be keyed upper-case, and no "missing ticker" warning should
        // fire on what is a completely successful fetch.
        var provider = providerWithJson("{\"bitcoin\":{\"eur\":50000.0}}");

        Map<String, BigDecimal> prices = provider.getPricesEur(Set.of("btc"));

        assertThat(prices).containsOnlyKeys("BTC");
        assertThat(eventsAt(Level.WARN)).isEmpty();
    }

    @Test
    void getPricesEur_warnsOnlyForTheTickersActuallyMissing() {
        var provider = providerWithJson("{\"bitcoin\":{\"eur\":50000.0}}");

        Map<String, BigDecimal> prices = provider.getPricesEur(Set.of("BTC", "ETH"));

        assertThat(prices).containsOnlyKeys("BTC");
        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).contains("ETH").doesNotContain("BTC"));
    }

    @Test
    void resolvesEveryTickerAddedForTheEvmFanOut_toItsCoinGeckoId() {
        // POL/USDT/USDC/DAI/EURC were added alongside the EVM multichain work. A typo in a
        // coin id degrades silently: the request simply omits it, the price comes back
        // missing, and the asset drops out of the wallet total with only a generic warning.
        // Assert on the outgoing request so the mapping itself is pinned, not just the
        // stubbed response.
        Map<String, String> expectedIds = Map.of(
            "POL", "polygon-ecosystem-token",
            "USDT", "tether",
            "USDC", "usd-coin",
            "DAI", "dai",
            "EURC", "euro-coin");

        expectedIds.forEach((ticker, coinId) -> {
            var captured = new java.util.concurrent.atomic.AtomicReference<String>();
            ExchangeFunction exchange = request -> {
                captured.set(request.url().toString());
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"" + coinId + "\":{\"eur\":1.0}}").build());
            };
            var provider = new CoinGeckoPriceProvider(
                WebClient.builder().exchangeFunction(exchange).build());

            Map<String, BigDecimal> prices = provider.getPricesEur(Set.of(ticker));

            assertThat(captured.get())
                .as("%s must be requested as CoinGecko id '%s'", ticker, coinId)
                .contains("ids=" + coinId);
            assertThat(prices)
                .as("%s must resolve back to its ticker key", ticker)
                .containsKey(ticker);
        });
    }

    // ── Expected upstream failures: no prices, graded log, never thrown ───────

    @Test
    void rateLimit_returnsNoPrices_andWarns() {
        var provider = providerWithStatus(HttpStatus.TOO_MANY_REQUESTS, "{\"status\":\"rate limited\"}");

        assertThat(provider.getPricesEur(Set.of("BTC"))).isEmpty();
        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).contains("429"));
        assertThat(eventsAt(Level.ERROR)).isEmpty();
    }

    @Test
    void rateLimit_stopsSendingRequestsUntilTheCooldownExpires() {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        ExchangeFunction exchange = request -> {
            requests.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"status\":\"rate limited\"}").build());
        };
        var provider = new CoinGeckoPriceProvider(WebClient.builder().exchangeFunction(exchange).build());

        provider.getPricesEur(Set.of("BTC"));
        provider.getPricesEur(Set.of("ETH"));
        provider.getHistoricalPricesEur("SOL", LocalDate.now().minusDays(1), LocalDate.now());

        // One request, then silence. CoinGecko's free tier counts the calls it rejects, so
        // answering a 429 with more traffic is what turns a one-minute limit into a morning of
        // missing prices. The pause covers every endpoint: they share one per-IP budget.
        assertThat(requests.get()).isEqualTo(1);
        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).contains("pausing calls for 60s"));
    }

    @Test
    void rateLimit_honoursRetryAfter_withinReason() {
        var provider = providerReturning(Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .header("Retry-After", "120")
            .body("{}").build()));

        provider.getPricesEur(Set.of("BTC"));

        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).contains("pausing calls for 120s"));
    }

    @Test
    void rateLimit_ignoresAnAbsurdRetryAfter() {
        // A day-long pause would leave the instance unable to price anything long after the real
        // limit lifted -- the cure being worse than the disease.
        var provider = providerReturning(Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .header("Retry-After", "86400")
            .body("{}").build()));

        provider.getPricesEur(Set.of("BTC"));

        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).contains("pausing calls for 900s"));
    }

    @Test
    void serverError_returnsNoPrices_andWarnsRatherThanErrors() {
        // Their outage, not our bug. These callers run on a scheduler and per-ticker, so
        // grading this ERROR would flood the log for the whole outage.
        var provider = providerWithStatus(HttpStatus.BAD_GATEWAY, "<html>bad gateway</html>");

        assertThat(provider.getPricesEur(Set.of("BTC"))).isEmpty();
        assertThat(eventsAt(Level.WARN)).hasSize(1);
        assertThat(eventsAt(Level.ERROR)).isEmpty();
    }

    @Test
    void notFound_returnsNoPrices_andErrors() {
        // A 404 points at a bad TICKER_TO_ID coin id -- something we can actually fix.
        var provider = providerWithStatus(HttpStatus.NOT_FOUND, "{\"error\":\"unknown coin\"}");

        assertThat(provider.getPricesEur(Set.of("BTC"))).isEmpty();
        assertThat(eventsAt(Level.ERROR)).hasSize(1);
    }

    @Test
    void forbidden_returnsNoPrices_andWarns() {
        // Free-tier access policy, not a bug on our side.
        var provider = providerWithStatus(HttpStatus.FORBIDDEN, "{\"error\":\"forbidden\"}");

        assertThat(provider.getPricesEur(Set.of("BTC"))).isEmpty();
        assertThat(eventsAt(Level.WARN)).hasSize(1);
        assertThat(eventsAt(Level.ERROR)).isEmpty();
    }

    @Test
    void timeout_returnsNoPrices_andWarns_despiteReactorWrapping() {
        // TimeoutException is CHECKED, so block() delivers it wrapped in a ReactiveException.
        // If the adapter matched on the declared type without unwrapping, this -- the most
        // common real CoinGecko failure -- would escape as an unhandled bug.
        var provider = providerFailingWith(new TimeoutException("simulated timeout"));

        assertThat(provider.getPricesEur(Set.of("BTC"))).isEmpty();
        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).containsIgnoringCase("timed out"));
        assertThat(eventsAt(Level.ERROR)).isEmpty();
    }

    @Test
    void connectionFailure_returnsNoPrices_andWarns() {
        var provider = providerFailingWith(new WebClientRequestException(
            new IOException("connection refused"), org.springframework.http.HttpMethod.GET,
            URI.create("https://api.coingecko.com"), org.springframework.http.HttpHeaders.EMPTY));

        assertThat(provider.getPricesEur(Set.of("BTC"))).isEmpty();
        assertThat(eventsAt(Level.WARN)).hasSize(1);
        assertThat(eventsAt(Level.ERROR)).isEmpty();
    }

    @Test
    void errorBody_withLineBreaks_isLoggedOnOneLine_andTruncated() {
        String forged = "<html>bad gateway</html>\r\nERROR fake entry " + "x".repeat(300);
        var provider = providerWithStatus(HttpStatus.BAD_GATEWAY, forged);

        provider.getPricesEur(Set.of("BTC"));

        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage())
                .contains("<html>bad gateway</html>?ERROR fake entry")
                .endsWith("... (truncated)")
                .doesNotContain("\r", "\n"));
    }

    // ── Genuine bugs propagate ────────────────────────────────────────────────

    @Test
    void unexpectedException_propagates_insteadOfBeingSwallowedAsNoPrices() {
        // The contract this class exists to defend: a bug that presents as "no prices" is
        // indistinguishable from a quiet outage and would never get fixed.
        var provider = providerFailingWith(new IllegalStateException("a real bug"));

        assertThatThrownBy(() -> provider.getPricesEur(Set.of("BTC")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("a real bug");
    }

    // ── Logos: same failure contract, and a null that must mean "show the ticker" ──

    @Test
    void getLogoUrls_parsesImages_andKeysThemUpperCase() {
        // /coins/markets keys by coin id and answers with a list, so the response has to be
        // re-keyed by ticker. A mapping slip here is silent: the portfolio renders tickers
        // and nothing logs an error.
        var provider = providerWithJson(
            "[{\"id\":\"bitcoin\",\"image\":\"https://img/btc.png\"},"
          + "{\"id\":\"ethereum\",\"image\":\"https://img/eth.png\"}]");

        Map<String, String> logos = provider.getLogoUrls(Set.of("btc", "ETH"));

        assertThat(logos).containsOnly(
            entry("BTC", "https://img/btc.png"), entry("ETH", "https://img/eth.png"));
        assertThat(eventsAt(Level.WARN)).isEmpty();
    }

    @Test
    void getLogoUrls_returnsNothing_forAnEquityTicker_withoutAskingTheProvider() {
        // The gate is the coin registry, not the account type. AAPL has no coin id, so the
        // request must not be made at all -- an equity must never resolve to an unrelated coin
        // that happens to share its symbol.
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        ExchangeFunction exchange = request -> {
            requests.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("[]").build());
        };
        var provider = new CoinGeckoPriceProvider(WebClient.builder().exchangeFunction(exchange).build());

        assertThat(provider.getLogoUrls(Set.of("AAPL"))).isEmpty();
        assertThat(requests.get()).isZero();
    }

    @Test
    void getLogoUrls_returnsNothing_andWarns_whenNoRequestedCoinHasAnImage() {
        // A 200 with nothing in it is the shape a renamed or delisted coin takes. It is
        // decoration, so it degrades to a warn -- never an ERROR, which the scheduler callers
        // would read as a bug.
        var provider = providerWithJson("[{\"id\":\"bitcoin\",\"image\":\"\"}]");

        assertThat(provider.getLogoUrls(Set.of("BTC"))).isEmpty();
        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).contains("no image"));
        assertThat(eventsAt(Level.ERROR)).isEmpty();
    }

    @Test
    void getLogoUrls_rateLimit_returnsNothing_andArmsTheCooldownThePricePathAlsoReads() {
        // The cooldown is shared on purpose: a provider paused on the price path must not be
        // asked for decoration, and a 429 on the logo path must not leave prices flowing.
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        ExchangeFunction exchange = request -> {
            requests.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"status\":\"rate limited\"}").build());
        };
        var provider = new CoinGeckoPriceProvider(WebClient.builder().exchangeFunction(exchange).build());

        assertThat(provider.getLogoUrls(Set.of("BTC"))).isEmpty();
        assertThat(provider.getPricesEur(Set.of("ETH"))).isEmpty();
        assertThat(provider.getLogoUrls(Set.of("SOL"))).isEmpty();

        // One request, then silence on every endpoint -- which is the whole point of sharing it.
        assertThat(requests.get()).isEqualTo(1);
        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).contains("429"));
    }

    @Test
    void getLogoUrls_propagatesAGenuineBug_insteadOfSwallowingItAsNoLogo() {
        // The contract getPricesEur already has, on a new code path. If the catch here ever
        // widens to a bare Exception, a parse defect would become a 500 on GET /holdings --
        // decoration would take the page down -- and nothing else would notice.
        var provider = providerFailingWith(new IllegalStateException("a real bug"));

        assertThatThrownBy(() -> provider.getLogoUrls(Set.of("BTC")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("a real bug");
    }

    // ── Defensive parsing of the history payload ──────────────────────────────

    private static String pricePoint(LocalDate date, double price) {
        long millis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        return "[" + millis + "," + price + "]";
    }

    @Test
    void getHistoricalPricesEur_parsesWellFormedPoints() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 3);
        var provider = providerWithJson("{\"prices\":[" + pricePoint(from, 100.0) + "]}");

        Map<LocalDate, BigDecimal> prices = provider.getHistoricalPricesEur("BTC", from, to);

        assertThat(prices).hasSize(1);
        assertThat(prices.get(from)).isEqualByComparingTo("100");
    }

    @Test
    void getHistoricalPricesEur_skipsMalformedPoints_withoutThrowing() {
        // A CoinGecko format change must degrade to a warn and a skip. Since genuine bugs
        // now propagate, a blind cast here would abort the daily snapshot batch instead.
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 3);
        String json = "{\"prices\":["
            + pricePoint(from, 100.0) + ","
            + "[\"not-a-number\",\"also-not\"],"   // string-encoded
            + "[12345],"                            // short pair
            + "\"not-even-a-pair\""                 // wrong shape entirely
            + "]}";
        var provider = providerWithJson(json);

        Map<LocalDate, BigDecimal> prices = provider.getHistoricalPricesEur("BTC", from, to);

        assertThat(prices).hasSize(1);
        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).contains("3 malformed"));
    }

    @Test
    void getHistoricalPricesEur_returnsEmpty_whenPricesFieldIsNotAList() {
        var provider = providerWithJson("{\"prices\":{\"unexpected\":\"object\"}}");

        assertThatCode(() -> assertThat(
            provider.getHistoricalPricesEur("BTC", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3)))
            .isEmpty()).doesNotThrowAnyException();
        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).contains("non-list"));
    }

    @Test
    void getIntradayPricesEur_skipsMalformedPoints_withoutThrowing() {
        var from = LocalDate.of(2026, 1, 1).atStartOfDay();
        var to = from.plusDays(2);
        long millis = from.toInstant(ZoneOffset.UTC).toEpochMilli();
        var provider = providerWithJson(
            "{\"prices\":[[" + millis + ",100.0],[\"bad\",\"pair\"]]}");

        var prices = provider.getIntradayPricesEur("BTC", from, to);

        assertThat(prices).hasSize(1);
        assertThat(eventsAt(Level.WARN)).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).contains("1 malformed"));
    }
}
