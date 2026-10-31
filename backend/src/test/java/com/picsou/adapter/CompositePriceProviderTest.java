package com.picsou.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompositePriceProviderTest {

    @Mock CoinGeckoPriceProvider coinGecko;
    @Mock YahooFinancePriceProvider yahoo;

    private CompositePriceProvider composite() {
        return new CompositePriceProvider(coinGecko, yahoo);
    }

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
}
