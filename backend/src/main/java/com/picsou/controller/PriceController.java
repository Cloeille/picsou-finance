package com.picsou.controller;

import com.picsou.model.PriceSnapshot;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.service.PriceService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/prices")
public class PriceController {

    private final PriceService priceService;
    private final PriceSnapshotRepository priceSnapshotRepository;

    public PriceController(PriceService priceService, PriceSnapshotRepository priceSnapshotRepository) {
        this.priceService = priceService;
        this.priceSnapshotRepository = priceSnapshotRepository;
    }

    @GetMapping
    public Map<String, BigDecimal> getPrices(@RequestParam String tickers) {
        Set<String> tickerSet = Arrays.stream(tickers.split(","))
            .map(String::trim)
            .filter(t -> !t.isBlank())
            .collect(Collectors.toSet());

        return priceService.refreshPrices(tickerSet);
    }

    /**
     * Historical daily prices for a single ticker from the price_snapshot table.
     */
    @GetMapping("/{ticker}/history")
    public List<Map<String, Object>> getPriceHistory(
        @PathVariable String ticker,
        @RequestParam(defaultValue = "12") int months
    ) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusMonths(months);
        List<PriceSnapshot> snapshots = priceSnapshotRepository
            .findByTickerInAndDateBetween(Set.of(ticker.toUpperCase()), from, to);

        List<Map<String, Object>> result = new ArrayList<>();
        for (PriceSnapshot ps : snapshots) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", ps.getDate().toString());
            point.put("priceEur", ps.getPriceEur());
            result.add(point);
        }
        return result;
    }

    /**
     * Intraday hourly prices for a single ticker (last 24h) from external providers.
     *
     * <p>Deliberately does <em>not</em> catch and degrade, unlike
     * {@code HistoryService.buildIntradayHistory}. That method loops over many tickers, so
     * swallowing one failure still leaves a useful chart; here the single ticker <em>is</em>
     * the whole response, and returning an empty list would be indistinguishable from "this
     * ticker genuinely has no intraday data". The price providers already swallow expected
     * upstream failures and return no prices, so anything propagating here is a real bug and
     * a 500 is the honest answer.
     */
    @GetMapping("/{ticker}/intraday")
    public List<Map<String, Object>> getPriceIntraday(@PathVariable String ticker) {
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusHours(24);
        Map<LocalDateTime, BigDecimal> prices = priceService.getIntradayPricesEur(ticker, from, to);

        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : prices.entrySet()) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("timestamp", entry.getKey().toString());
            point.put("priceEur", entry.getValue());
            result.add(point);
        }
        return result;
    }
}
