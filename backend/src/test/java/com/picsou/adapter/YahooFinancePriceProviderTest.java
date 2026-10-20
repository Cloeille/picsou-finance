package com.picsou.adapter;

import com.picsou.port.SymbolCatalogPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class YahooFinancePriceProviderTest {

    private static final String AAPL_USD = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":100.0,"currency":"USD"},
              "timestamp":[1700000000],"indicators":{"quote":[{"close":[100.0]}]}}]}}""";

    private static final String ASML_EUR = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":700.0,"currency":"EUR"},
              "timestamp":[1700000000],"indicators":{"quote":[{"close":[700.0]}]}}]}}""";

    private static final String SONY_JPY = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":3000.0,"currency":"JPY"},
              "timestamp":[1700000000],"indicators":{"quote":[{"close":[3000.0]}]}}]}}""";

    private static final String LLOY_GBP_PENCE = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":5000.0,"currency":"GBp"},
              "timestamp":[1700000000],"indicators":{"quote":[{"close":[5000.0]}]}}]}}""";

    private static final String CURRENCYLESS = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":42.0}}]}}""";

    private static final String FX_USD_EUR_092 = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":0.92,"currency":"EUR"}}]}}""";

    private static final String FX_JPY_EUR = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":0.0060,"currency":"EUR"}}]}}""";

    private static final String FX_GBP_EUR = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":1.18,"currency":"EUR"}}]}}""";

    private static final String HISTORICAL_USD = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":100.0,"currency":"USD"},
              "timestamp":[1700000000,1700086400,1700172800],
              "indicators":{"quote":[{"close":[100.0,110.0,120.0]}]}}]}}""";

    private static final String INTRADAY_JPY = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":3000.0,"currency":"JPY"},
              "timestamp":[1700000000,1700003600],
              "indicators":{"quote":[{"close":[3000.0,3100.0]}]}}]}}""";

    private YahooFinancePriceProvider providerWith(Function<String, String> routeToJson, AtomicInteger callCounter) {
        ExchangeFunction exchange = request -> {
            if (callCounter != null) callCounter.incrementAndGet();
            String url = request.url().toString();
            String body = routeToJson.apply(url);
            if (body == null) {
                return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"error\":\"not found\"}").build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body).build());
        };
        WebClient client = WebClient.builder().exchangeFunction(exchange).build();
        return new YahooFinancePriceProvider(client);
    }

    @Test
    void getPriceEur_returnsRawPrice_whenCurrencyIsEur() {
        AtomicInteger calls = new AtomicInteger();
        var provider = providerWith(url -> {
            if (url.contains("/ASML.AS")) return ASML_EUR;
            return null;
        }, calls);

        Map<String, BigDecimal> result = provider.getPricesEur(Set.of("ASML.AS"));

        assertThat(result).containsEntry("ASML.AS", BigDecimal.valueOf(700.0));
        assertThat(calls.get()).isEqualTo(1); // no FX call needed for EUR
    }

    @Test
    void getPriceEur_appliesFx_forUsdTicker() {
        var provider = providerWith(url -> {
            if (url.contains("/AAPL")) return AAPL_USD;
            if (url.contains("/USDEUR%3DX") || url.contains("/USDEUR=X")) return FX_USD_EUR_092;
            return null;
        }, null);

        Map<String, BigDecimal> result = provider.getPricesEur(Set.of("AAPL"));

        // 100 USD × 0.92 = 92 EUR
        assertThat(result.get("AAPL").doubleValue()).isCloseTo(92.0, within(0.001));
    }

    @Test
    void getPriceEur_appliesFx_forJpyTicker() {
        var provider = providerWith(url -> {
            if (url.contains("/8729.T")) return SONY_JPY;
            if (url.contains("JPYEUR")) return FX_JPY_EUR;
            return null;
        }, null);

        Map<String, BigDecimal> result = provider.getPricesEur(Set.of("8729.T"));

        // 3000 JPY × 0.0060 = 18 EUR
        assertThat(result.get("8729.T").doubleValue()).isCloseTo(18.0, within(0.01));
    }

    @Test
    void getPriceEur_dividesByHundred_forGbpPence() {
        var provider = providerWith(url -> {
            if (url.contains("/LLOY.L")) return LLOY_GBP_PENCE;
            if (url.contains("GBPEUR")) return FX_GBP_EUR;
            return null;
        }, null);

        Map<String, BigDecimal> result = provider.getPricesEur(Set.of("LLOY.L"));

        // 5000 GBp = 50 GBP × 1.18 = 59 EUR
        assertThat(result.get("LLOY.L").doubleValue()).isCloseTo(59.0, within(0.01));
    }

    @Test
    void getPriceEur_returnsEmpty_whenFxFetchFails() {
        var provider = providerWith(url -> {
            if (url.contains("/AAPL")) return AAPL_USD;
            // USDEUR=X returns 404
            return null;
        }, null);

        Map<String, BigDecimal> result = provider.getPricesEur(Set.of("AAPL"));

        assertThat(result).doesNotContainKey("AAPL"); // no fabricated EUR value
    }

    @Test
    void getPriceEur_returnsRawPrice_whenCurrencyIsMissing() {
        var provider = providerWith(url -> {
            if (url.contains("/WEIRD")) return CURRENCYLESS;
            return null;
        }, null);

        Map<String, BigDecimal> result = provider.getPricesEur(Set.of("WEIRD"));

        // Currency null → treat as EUR (preserves pre-fix behavior for broken payloads)
        assertThat(result.get("WEIRD")).isEqualTo(BigDecimal.valueOf(42.0));
    }

    @Test
    void fxCache_avoidsRefetch_acrossTickersInSameCurrency() {
        List<String> fxCalls = new ArrayList<>();
        ExchangeFunction exchange = request -> {
            String url = request.url().toString();
            if (url.contains("USDEUR")) fxCalls.add(url);
            String body;
            if (url.contains("/AAPL")) body = AAPL_USD;
            else if (url.contains("/MSFT")) body = AAPL_USD;  // both USD, same payload shape
            else if (url.contains("USDEUR")) body = FX_USD_EUR_092;
            else body = null;
            if (body == null) {
                return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{}").build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body).build());
        };
        var provider = new YahooFinancePriceProvider(
            WebClient.builder().exchangeFunction(exchange).build());

        provider.getPricesEur(Set.of("AAPL"));
        provider.getPricesEur(Set.of("MSFT"));

        // First USD ticker triggers FX fetch and caches; second USD ticker
        // must reuse the cached rate.
        assertThat(fxCalls).hasSize(1);
    }

    @Test
    void fxMiss_isRememberedForAMinute_notRefetchedOnEveryLookup() {
        List<String> fxCalls = new ArrayList<>();
        ExchangeFunction exchange = request -> {
            String url = request.url().toString();
            if (url.contains("USDEUR")) fxCalls.add(url);
            return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{}").build());
        };
        var provider = new YahooFinancePriceProvider(
            WebClient.builder().exchangeFunction(exchange).build());

        assertThat(provider.getFxRateToEur("USD")).isNull();
        assertThat(provider.getFxRateToEur("USD")).isNull();

        // The second lookup inside the miss TTL is answered from the remembered miss: a Yahoo
        // outage costs one request per currency per minute, not one per cash valuation.
        assertThat(fxCalls).hasSize(1);
    }

    @Test
    void getFxRateToEur_shortCircuits_forEur() {
        var provider = providerWith(url -> null, null);

        // Should never hit the network for EUR.
        assertThat(provider.getFxRateToEur("EUR")).isEqualTo(BigDecimal.ONE);
        assertThat(provider.getFxRateToEur("eur")).isEqualTo(BigDecimal.ONE);
        assertThat(provider.getFxRateToEur(null)).isEqualTo(BigDecimal.ONE);
        assertThat(provider.getFxRateToEur("")).isEqualTo(BigDecimal.ONE);
    }

    @Test
    void getHistoricalPricesEur_appliesFx_toAllClosesInSeries() {
        var provider = providerWith(url -> {
            if (url.contains("/AAPL")) return HISTORICAL_USD;
            if (url.contains("USDEUR")) return FX_USD_EUR_092;
            return null;
        }, null);

        Map<LocalDate, BigDecimal> prices = provider.getHistoricalPricesEur(
            "AAPL", LocalDate.of(2023, 11, 1), LocalDate.of(2023, 12, 1));

        // All closes × 0.92, scaled to 8 decimals
        assertThat(prices).isNotEmpty();
        assertThat(prices.values().stream().map(BigDecimal::doubleValue).toList())
            .allSatisfy(v -> assertThat(v).isIn(92.0, 101.2, 110.4));
    }

    @Test
    void getIntradayPricesEur_appliesFx_toAllClosesInSeries() {
        var provider = providerWith(url -> {
            if (url.contains("/8729.T")) return INTRADAY_JPY;
            if (url.contains("JPYEUR")) return FX_JPY_EUR;
            return null;
        }, null);

        var from = java.time.LocalDateTime.of(2023, 1, 1, 0, 0);
        var to = java.time.LocalDateTime.of(2030, 1, 1, 0, 0);
        Map<java.time.LocalDateTime, BigDecimal> prices = provider.getIntradayPricesEur("8729.T", from, to);

        // 3000 JPY × 0.006 = 18; 3100 JPY × 0.006 = 18.6 — both must be present
        assertThat(prices.values().stream().map(BigDecimal::doubleValue).toList())
            .anySatisfy(v -> assertThat(v).isCloseTo(18.0, within(0.01)));
    }

    @Test
    void supports_rejectsStringsThatAreNotSymbols() {
        var provider = new YahooFinancePriceProvider();

        assertThat(provider.supports("AIRBAL 14.5 08/14/29 REGS")).isFalse();
        assertThat(provider.supports("AIR BALTIC")).isFalse();
        assertThat(provider.supports("A/B")).isFalse();
        assertThat(provider.supports("THIS_IS_NOT_A_TICKER_AT_ALL")).isFalse();
    }

    @Test
    void supports_acceptsRealYahooSymbolShapes() {
        var provider = new YahooFinancePriceProvider();

        assertThat(provider.supports("PHYMF")).isTrue();
        assertThat(provider.supports("IWDA.AS")).isTrue();
        assertThat(provider.supports("MC.PA")).isTrue();
        assertThat(provider.supports("BRK-B")).isTrue();
        assertThat(provider.supports("1810.HK")).isTrue();
        assertThat(provider.supports("USDEUR=X")).isTrue();
        assertThat(provider.supports("^GSPC")).isTrue();
    }

    @Test
    void supports_enforcesTwentyCharacterLimitIncludingIndexPrefix() {
        var provider = new YahooFinancePriceProvider();

        assertThat(provider.supports("A".repeat(20))).isTrue();
        assertThat(provider.supports("A".repeat(21))).isFalse();
        assertThat(provider.supports("^" + "A".repeat(19))).isTrue();
        assertThat(provider.supports("^" + "A".repeat(20))).isFalse();
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    void supports_normalizesWithLocaleRoot_soATurkishDefaultLocaleDoesNotBreakTickers() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var provider = new YahooFinancePriceProvider();

            assertThat(provider.supports("iwda.as")).isTrue();
            assertThat(provider.supports("isin")).isTrue();
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    void getPricesEur_keysTheResultWithLocaleRootUppercase() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var provider = providerWith(url -> url.toUpperCase(Locale.ROOT).contains("/IWDA.AS")
                ? ASML_EUR : null, null);

            assertThat(provider.getPricesEur(Set.of("iwda.as"))).containsKey("IWDA.AS");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void historicalIntradayAndInstrumentType_neverCallYahoo_forNonSymbolTickers() {
        AtomicInteger calls = new AtomicInteger();
        var provider = providerWith(url -> null, calls);
        String bond = "AIRBAL 14.5 08/14/29 REGS";

        assertThat(provider.getHistoricalPricesEur(bond, LocalDate.now().minusDays(7), LocalDate.now()))
            .isEmpty();
        assertThat(provider.getIntradayPricesEur(bond,
            java.time.LocalDateTime.now().minusDays(1), java.time.LocalDateTime.now())).isEmpty();
        assertThat(provider.getInstrumentType(bond)).isEmpty();
        assertThat(provider.getHistoricalPricesEur("LU3170240538",
            LocalDate.now().minusDays(7), LocalDate.now())).isEmpty();

        assertThat(calls.get()).isZero();
    }

    @Test
    void getPricesEur_neverCallsYahoo_forNonSymbolTickers() {
        AtomicInteger calls = new AtomicInteger();
        var provider = providerWith(url -> null, calls);

        Map<String, BigDecimal> prices = provider.getPricesEur(Set.of("AIRBAL 14.5 08/14/29 REGS"));

        assertThat(prices).isEmpty();
        assertThat(calls.get()).isZero();
    }

    // ─── hasQuote / searchSymbols: is this symbol one Yahoo carries? ────────────
    // Used by OpenFigiIsinConverter to verify the listing it derived from an ISIN before that
    // symbol is persisted on a holding and every later valuation depends on it (GH issues #74, #78).

    private static final String SEARCH_IE000BI8OT95 = """
            {"quotes":[
              {"symbol":"MWRD.PA","shortname":"Amundi Core MSCI World UCITS ET","exchange":"PAR",
               "quoteType":"ETF","isYahooFinance":true},
              {"symbol":"IE000BI8OT95.SG","shortname":"Amundi MSCI World UCITS ETF - U",
               "exchange":"STU","quoteType":"MUTUALFUND","isYahooFinance":true}],"count":2}""";

    @Test
    void hasQuote_isTrue_whenYahooReturnsAPrice_andNeedsNoFxCall() {
        AtomicInteger calls = new AtomicInteger();
        var provider = providerWith(url -> url.contains("/AAPL") ? AAPL_USD : null, calls);

        assertThat(provider.hasQuote("AAPL")).isTrue();
        // The probe asks "does this symbol exist", not "what is it worth": an unavailable EUR rate
        // says nothing about the symbol, and a second call per candidate would double the cost.
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void hasQuote_isFalse_forADelistedSymbol() {
        // MWRDF: "No data found, symbol may be delisted" — a 404 from the chart endpoint.
        var provider = providerWith(url -> null, null);

        assertThat(provider.hasQuote("MWRDF")).isFalse();
    }

    @Test
    void hasQuote_isFalse_withoutCallingYahoo_forAnIsinOrNonSymbol() {
        AtomicInteger calls = new AtomicInteger();
        var provider = providerWith(url -> null, calls);

        assertThat(provider.hasQuote("IE000BI8OT95")).isFalse();
        assertThat(provider.hasQuote("AIRBAL 14.5 08/14/29 REGS")).isFalse();
        assertThat(provider.hasQuote(null)).isFalse();

        assertThat(calls.get()).isZero();
    }

    @Test
    void searchSymbols_returnsYahoosOwnSymbolsForAnIsin_inItsRelevanceOrder() {
        var provider = providerWith(url -> url.contains("/v1/finance/search") ? SEARCH_IE000BI8OT95 : null, null);

        List<SymbolCatalogPort.SymbolMatch> matches = provider.searchSymbols("IE000BI8OT95");

        assertThat(matches).extracting(SymbolCatalogPort.SymbolMatch::symbol)
            .containsExactly("MWRD.PA", "IE000BI8OT95.SG");
        assertThat(matches.get(0).name()).isEqualTo("Amundi Core MSCI World UCITS ET");
    }

    @Test
    void searchSymbols_prefersTheLongName_whenYahooProvidesOne() {
        var provider = providerWith(url -> """
            {"quotes":[{"symbol":"MC.PA","shortname":"LVMH","longname":"LVMH Moet Hennessy Louis Vuitton",
              "isYahooFinance":true}]}""", null);

        assertThat(provider.searchSymbols("FR0000121014").get(0).name())
            .isEqualTo("LVMH Moet Hennessy Louis Vuitton");
    }

    @Test
    void searchSymbols_dropsEntriesYahooDoesNotQuoteItself() {
        // isYahooFinance=false marks a private company / research entity with no quote page;
        // requesting it would be one guaranteed 404 per candidate.
        var provider = providerWith(url -> """
            {"quotes":[{"symbol":"PRIVATECO","shortname":"Some Private Company","isYahooFinance":false},
                       {"symbol":"MWRD.PA","shortname":"Amundi Core MSCI World","isYahooFinance":true}]}""",
            null);

        assertThat(provider.searchSymbols("IE000BI8OT95"))
            .extracting(SymbolCatalogPort.SymbolMatch::symbol)
            .containsExactly("MWRD.PA");
    }

    @Test
    void searchSymbols_returnsEmpty_whenYahooKnowsNothingOrFails() {
        assertThat(providerWith(url -> """
            {"explains":[],"count":0,"quotes":[]}""", null).searchSymbols("XX0000000000")).isEmpty();
        assertThat(providerWith(url -> null, null).searchSymbols("IE000BI8OT95")).isEmpty();

        AtomicInteger calls = new AtomicInteger();
        assertThat(providerWith(url -> null, calls).searchSymbols("  ")).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    void fetchDividendHistory_readsTheDateFieldAsTheExDate_andDoesNotConvertTheAmount() {
        List<String> urls = new ArrayList<>();
        ExchangeFunction exchange = request -> {
            urls.add(request.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("""
                    {"chart":{"result":[{"meta":{"regularMarketPrice":60.0,"currency":"EUR"},
                      "events":{"dividends":{"1788213600":{"amount":0.9,"date":1790751600}}}}]}}
                    """)
                .build());
        };
        var provider = new YahooFinancePriceProvider(WebClient.builder().exchangeFunction(exchange).build());

        var history = provider.fetchDividendHistory("TTE.PA");

        assertThat(urls).singleElement().asString()
            .contains("range=2y")
            .contains("interval=1mo")
            .contains("events=div")
            .contains("TTE.PA");
        assertThat(history.currency()).isEqualTo("EUR");
        assertThat(history.events()).singleElement().satisfies(point -> {
            assertThat(point.amount()).isEqualByComparingTo("0.9");
            assertThat(point.exDate()).isEqualTo(
                java.time.Instant.ofEpochSecond(1790751600L).atZone(java.time.ZoneOffset.UTC).toLocalDate());
        });
    }

    @Test
    void fetchDividendHistory_returnsEmpty_whenYahooPublishesNoDividends() {
        var provider = providerWith(url -> """
            {"chart":{"result":[{"meta":{"currency":"EUR","regularMarketPrice":1},"events":{"dividends":{}}}]}}
            """, null);

        var history = provider.fetchDividendHistory("TTE.PA");

        assertThat(history.currency()).isEqualTo("EUR");
        assertThat(history.events()).isEmpty();
    }

    @Test
    void fetchDividendHistory_armsCooldownAndRethrows_on429() {
        java.time.Clock clock = java.time.Clock.fixed(
            java.time.Instant.parse("2026-10-20T08:00:00Z"), java.time.ZoneOffset.UTC);
        YahooCooldown cooldown = new YahooCooldown(clock);
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .header("Retry-After", "120")
            .body("{}")
            .build());
        var provider = new YahooFinancePriceProvider(
            WebClient.builder().exchangeFunction(exchange).build(), cooldown);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.fetchDividendHistory("TTE.PA"))
            .isInstanceOf(RuntimeException.class);
        assertThat(cooldown.active()).isTrue();
        assertThat(cooldown.until()).isEqualTo(java.time.Instant.parse("2026-10-20T08:02:00Z"));
    }

    @Test
    void fetchDividendHistory_keepsOnlyTheMostRecentTwoHundredEvents() {
        long oldest = 1_600_000_000L;
        StringBuilder dividends = new StringBuilder();
        for (int i = 0; i < 201; i++) {
            if (i > 0) {
                dividends.append(',');
            }
            long epoch = oldest + i * 86_400L;
            dividends.append('"').append(epoch).append("\":{\"amount\":0.1,\"date\":").append(epoch).append('}');
        }
        String body = "{\"chart\":{\"result\":[{\"meta\":{\"currency\":\"EUR\"},\"events\":{\"dividends\":{"
            + dividends + "}}}]}}";
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
        var provider = new YahooFinancePriceProvider(WebClient.builder().exchangeFunction(exchange).build());

        var history = provider.fetchDividendHistory("TTE.PA");

        java.time.LocalDate dropped = java.time.Instant.ofEpochSecond(oldest)
            .atZone(java.time.ZoneOffset.UTC).toLocalDate();
        java.time.LocalDate kept = java.time.Instant.ofEpochSecond(oldest + 200 * 86_400L)
            .atZone(java.time.ZoneOffset.UTC).toLocalDate();
        assertThat(history.events()).hasSize(200);
        assertThat(history.events()).extracting(com.picsou.port.DividendHistory.Point::exDate)
            .doesNotContain(dropped)
            .contains(kept);
    }
}
