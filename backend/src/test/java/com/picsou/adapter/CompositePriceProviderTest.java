package com.picsou.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompositePriceProviderTest {

    @Mock CoinGeckoPriceProvider coinGecko;
    @Mock YahooFinancePriceProvider yahoo;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-02T08:00:00Z"));
    private final CryptoAssetRegistry registry = CryptoAssetRegistry.of(new HashMap<>());

    private CompositePriceProvider composite() {
        return new CompositePriceProvider(coinGecko, yahoo, registry, clock);
    }

    /** A clock the test can advance, to step over the 15 min and 24 h retry windows. */
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static CoinGeckoPriceProvider.Resolution accepted(String ticker, String id, String name) {
        return new CoinGeckoPriceProvider.Resolution(
            Map.of(ticker, new CryptoAssetRegistry.Coin(id, name)), true);
    }

    private static final CoinGeckoPriceProvider.Resolution NOTHING_ACCEPTED =
        new CoinGeckoPriceProvider.Resolution(Map.of(), true);

    @Test
    void getPricesEur_splitsCryptoToCoinGecko_restToYahoo_andBatchesEachProviderOnce() {
        // Two crypto + two stock tickers, so "one batch call per provider" is
        // distinguishable from a per-ticker regression (the whole point of #35).
        when(coinGecko.supports("BTC")).thenReturn(true);
        when(coinGecko.supports("ETH")).thenReturn(true);
        when(coinGecko.supports("AAPL")).thenReturn(false);
        when(coinGecko.supports("MC.PA")).thenReturn(false);
        when(coinGecko.getPricesEur(Set.of("BTC", "ETH")))
            .thenReturn(Map.of("BTC", new BigDecimal("50000"), "ETH", new BigDecimal("3000")));
        when(yahoo.getPricesEur(Set.of("AAPL", "MC.PA")))
            .thenReturn(Map.of("AAPL", new BigDecimal("200"), "MC.PA", new BigDecimal("700")));

        Map<String, BigDecimal> prices = composite().getPricesEur(Set.of("BTC", "ETH", "AAPL", "MC.PA"));

        assertThat(prices)
            .containsEntry("BTC", new BigDecimal("50000"))
            .containsEntry("ETH", new BigDecimal("3000"))
            .containsEntry("AAPL", new BigDecimal("200"))
            .containsEntry("MC.PA", new BigDecimal("700"));
        // Each provider is called exactly once, with the full batch — not per ticker.
        verify(coinGecko, times(1)).getPricesEur(Set.of("BTC", "ETH"));
        verify(yahoo, times(1)).getPricesEur(Set.of("AAPL", "MC.PA"));
    }

    @Test
    void getPricesEur_empty_callsNoProvider() {
        assertThat(composite().getPricesEur(Set.of())).isEmpty();
        verifyNoInteractions(coinGecko, yahoo);
    }

    @Test
    void historical_routesCryptoToCoinGecko() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 1);
        when(coinGecko.supports("ETH")).thenReturn(true);
        when(coinGecko.getHistoricalPricesEur("ETH", from, to)).thenReturn(Map.of(from, new BigDecimal("3000")));

        assertThat(composite().getHistoricalPricesEur("ETH", from, to)).containsEntry(from, new BigDecimal("3000"));
        verify(yahoo, org.mockito.Mockito.never()).getHistoricalPricesEur(any(), any(), any());
    }

    @Test
    void historical_routesUnsupportedToYahoo_evenWhenYahooAlsoReportsUnsupported() {
        // ISINs: CoinGecko says no, Yahoo says no too — historically they still fall through to Yahoo.
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 1);
        String isin = "US0378331005";
        when(coinGecko.supports(isin)).thenReturn(false);
        when(yahoo.getHistoricalPricesEur(eq(isin), any(), any())).thenReturn(Map.of(from, new BigDecimal("42")));

        assertThat(composite().getHistoricalPricesEur(isin, from, to)).containsEntry(from, new BigDecimal("42"));
    }

    @Test
    void intraday_routesUnsupportedToYahoo() {
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 1, 2, 0, 0);
        when(coinGecko.supports("AAPL")).thenReturn(false);
        when(yahoo.getIntradayPricesEur("AAPL", from, to)).thenReturn(Map.of(from, new BigDecimal("199")));

        assertThat(composite().getIntradayPricesEur("AAPL", from, to)).containsEntry(from, new BigDecimal("199"));
    }

    @Test
    void intraday_routesCryptoToCoinGecko() {
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 1, 2, 0, 0);
        when(coinGecko.supports("BTC")).thenReturn(true);
        when(coinGecko.getIntradayPricesEur("BTC", from, to)).thenReturn(Map.of(from, new BigDecimal("45000")));

        assertThat(composite().getIntradayPricesEur("BTC", from, to)).containsEntry(from, new BigDecimal("45000"));
        verify(yahoo, org.mockito.Mockito.never()).getIntradayPricesEur(any(), any(), any());
    }

    @Test
    void supports_isTrueIfEitherProviderSupports() {
        when(coinGecko.supports("BTC")).thenReturn(true);

        assertThat(composite().supports("BTC")).isTrue();
    }

    // ─── learnCryptoTickers ─────────────────────────────────────────────────────

    @Test
    void learn_quotedByYahoo_isNotLearned_andNotAskedAgainWithin24Hours() {
        CompositePriceProvider composite = composite();
        when(yahoo.probeQuote("STX")).thenReturn(YahooFinancePriceProvider.QuoteProbe.QUOTED);

        composite.learnCryptoTickers(Set.of("STX"));
        clock.advance(Duration.ofHours(23));
        composite.learnCryptoTickers(Set.of("STX"));

        verify(yahoo, times(1)).probeQuote("STX");
        verify(coinGecko, never()).resolveBySymbol(any());
        assertThat(registry.find("STX")).isEmpty();
    }

    @Test
    void learn_quotedByYahoo_isAskedAgainAfter24Hours() {
        CompositePriceProvider composite = composite();
        when(yahoo.probeQuote("STX")).thenReturn(YahooFinancePriceProvider.QuoteProbe.QUOTED);

        composite.learnCryptoTickers(Set.of("STX"));
        clock.advance(Duration.ofHours(24).plusSeconds(1));
        composite.learnCryptoTickers(Set.of("STX"));

        verify(yahoo, times(2)).probeQuote("STX");
    }

    @Test
    void learn_yahooUnavailable_decidesNothing_andRetriesAfter15Minutes() {
        CompositePriceProvider composite = composite();
        when(yahoo.probeQuote("NEXO")).thenReturn(YahooFinancePriceProvider.QuoteProbe.UNAVAILABLE);

        composite.learnCryptoTickers(Set.of("NEXO"));
        clock.advance(Duration.ofMinutes(14));
        composite.learnCryptoTickers(Set.of("NEXO"));
        verify(yahoo, times(1)).probeQuote("NEXO");

        clock.advance(Duration.ofMinutes(2));
        composite.learnCryptoTickers(Set.of("NEXO"));
        verify(yahoo, times(2)).probeQuote("NEXO");
        verify(coinGecko, never()).resolveBySymbol(any());
        assertThat(registry.find("NEXO")).isEmpty();
    }

    @Test
    void learn_unknownToYahoo_andAcceptedByCoinGecko_makesTheTickerCryptoAndRoutesItsPrice() {
        when(yahoo.probeQuote("NEXO")).thenReturn(YahooFinancePriceProvider.QuoteProbe.UNKNOWN_SYMBOL);
        when(coinGecko.resolveBySymbol(Set.of("NEXO"))).thenReturn(accepted("NEXO", "nexo", "NEXO"));
        // The real CoinGecko adapter reads the shared registry; mirror that for the fake.
        when(coinGecko.supports(any())).thenAnswer(inv -> registry.find(inv.getArgument(0)).isPresent());
        when(coinGecko.supportsCrypto(any())).thenAnswer(inv -> registry.find(inv.getArgument(0)).isPresent());
        when(coinGecko.getPricesEur(Set.of("NEXO"))).thenReturn(Map.of("NEXO", new BigDecimal("1.20")));

        CompositePriceProvider composite = composite();
        assertThat(composite.supportsCrypto("NEXO")).isFalse();
        composite.learnCryptoTickers(Set.of("nexo"));

        assertThat(registry.find("NEXO")).contains(new CryptoAssetRegistry.Coin("nexo", "NEXO"));
        assertThat(composite.supportsCrypto("NEXO")).isTrue();
        assertThat(composite.getPricesEur(Set.of("NEXO"))).containsEntry("NEXO", new BigDecimal("1.20"));
        verify(yahoo, never()).getPricesEur(any());
    }

    @Test
    void learn_rejectedByCoinGecko_isNotLearned_andNotRetriedWithin24Hours() {
        CompositePriceProvider composite = composite();
        when(yahoo.probeQuote("ONE")).thenReturn(YahooFinancePriceProvider.QuoteProbe.UNKNOWN_SYMBOL);
        when(coinGecko.resolveBySymbol(Set.of("ONE"))).thenReturn(NOTHING_ACCEPTED);

        composite.learnCryptoTickers(Set.of("ONE"));
        clock.advance(Duration.ofHours(23));
        composite.learnCryptoTickers(Set.of("ONE"));

        verify(coinGecko, times(1)).resolveBySymbol(Set.of("ONE"));
        assertThat(registry.find("ONE")).isEmpty();
    }

    @Test
    void learn_registryFailure_doesNotPropagate_andRetriesAfter15Minutes() {
        CryptoAssetRegistry failing = org.mockito.Mockito.mock(CryptoAssetRegistry.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
            .when(failing).learn("NEXO", "nexo", "NEXO");
        CompositePriceProvider composite = new CompositePriceProvider(coinGecko, yahoo, failing, clock);
        when(yahoo.probeQuote("NEXO")).thenReturn(YahooFinancePriceProvider.QuoteProbe.UNKNOWN_SYMBOL);
        when(coinGecko.resolveBySymbol(Set.of("NEXO"))).thenReturn(accepted("NEXO", "nexo", "NEXO"));

        composite.learnCryptoTickers(Set.of("NEXO"));
        clock.advance(Duration.ofMinutes(14));
        composite.learnCryptoTickers(Set.of("NEXO"));
        verify(yahoo, times(1)).probeQuote("NEXO");

        clock.advance(Duration.ofMinutes(2));
        composite.learnCryptoTickers(Set.of("NEXO"));
        verify(yahoo, times(2)).probeQuote("NEXO");
    }

    @Test
    void learn_symbolLookupThrows_doesNotPropagate_andRetriesAfter15Minutes() {
        when(yahoo.probeQuote("NEXO")).thenReturn(YahooFinancePriceProvider.QuoteProbe.UNKNOWN_SYMBOL);
        when(coinGecko.resolveBySymbol(Set.of("NEXO"))).thenThrow(new IllegalStateException("parse bug"));
        CompositePriceProvider composite = composite();

        composite.learnCryptoTickers(Set.of("NEXO"));
        clock.advance(Duration.ofMinutes(14));
        composite.learnCryptoTickers(Set.of("NEXO"));
        verify(yahoo, times(1)).probeQuote("NEXO");

        clock.advance(Duration.ofMinutes(2));
        composite.learnCryptoTickers(Set.of("NEXO"));
        verify(yahoo, times(2)).probeQuote("NEXO");
        assertThat(registry.find("NEXO")).isEmpty();
    }

    @Test
    void learn_coinGeckoDidNotAnswer_retriesAfter15Minutes_notAfter24Hours() {
        CompositePriceProvider composite = composite();
        when(yahoo.probeQuote("NEXO")).thenReturn(YahooFinancePriceProvider.QuoteProbe.UNKNOWN_SYMBOL);
        when(coinGecko.resolveBySymbol(Set.of("NEXO")))
            .thenReturn(new CoinGeckoPriceProvider.Resolution(Map.of(), false));

        composite.learnCryptoTickers(Set.of("NEXO"));
        clock.advance(Duration.ofMinutes(16));
        composite.learnCryptoTickers(Set.of("NEXO"));

        verify(coinGecko, times(2)).resolveBySymbol(Set.of("NEXO"));
    }

    @Test
    void learn_alreadyKnownTicker_isNotProbedAtAll() {
        when(coinGecko.supports("BTC")).thenReturn(true);

        composite().learnCryptoTickers(Set.of("BTC"));

        verifyNoInteractions(yahoo);
        verify(coinGecko, never()).resolveBySymbol(any());
    }

    @Test
    void learn_sendsOneCoinGeckoBatch_forEveryCandidateYahooDoesNotQuote() {
        when(yahoo.probeQuote("NEXO")).thenReturn(YahooFinancePriceProvider.QuoteProbe.UNKNOWN_SYMBOL);
        when(yahoo.probeQuote("CRO")).thenReturn(YahooFinancePriceProvider.QuoteProbe.UNKNOWN_SYMBOL);
        when(yahoo.probeQuote("STX")).thenReturn(YahooFinancePriceProvider.QuoteProbe.QUOTED);
        when(coinGecko.resolveBySymbol(Set.of("NEXO", "CRO"))).thenReturn(NOTHING_ACCEPTED);

        composite().learnCryptoTickers(Set.of("NEXO", "CRO", "STX", " "));

        verify(coinGecko, times(1)).resolveBySymbol(Set.of("NEXO", "CRO"));
    }

    @Test
    void learn_nullOrEmpty_doesNothing() {
        CompositePriceProvider composite = composite();
        composite.learnCryptoTickers(null);
        composite.learnCryptoTickers(Set.of());

        verifyNoInteractions(yahoo);
    }
}
