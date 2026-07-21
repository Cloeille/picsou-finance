package com.picsou.adapter;

import com.picsou.port.PriceProviderPort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    private final CoinGeckoPriceProvider coinGecko;
    private final YahooFinancePriceProvider yahoo;

    public CompositePriceProvider(CoinGeckoPriceProvider coinGecko, YahooFinancePriceProvider yahoo) {
        this.coinGecko = coinGecko;
        this.yahoo = yahoo;
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

    @Override
    public boolean supports(String ticker) {
        return coinGecko.supports(ticker) || yahoo.supports(ticker);
    }

    @Override
    public Map<LocalDate, BigDecimal> getHistoricalPricesEur(String ticker, LocalDate from, LocalDate to) {
        return providerFor(ticker).getHistoricalPricesEur(ticker, from, to);
    }

    @Override
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(String ticker, LocalDateTime from, LocalDateTime to) {
        return providerFor(ticker).getIntradayPricesEur(ticker, from, to);
    }
}
