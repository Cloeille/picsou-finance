package com.picsou.service;

import com.picsou.adapter.YahooCooldown;
import com.picsou.model.DividendEvent;
import com.picsou.model.DividendSource;
import com.picsou.model.DividendSourceStatus;
import com.picsou.port.DividendHistory;
import com.picsou.port.DividendHistoryPort;
import com.picsou.port.NasdaqDividend;
import com.picsou.port.NasdaqDividendPort;
import com.picsou.port.PriceProviderPort;
import com.picsou.repository.DividendSourceRepository;
import com.picsou.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps {@code dividend_source} warm. Shaped like {@link SecurityProfileService#refreshStale}:
 * one transaction per ticker, a pause between attempts, and a failure that never erases the
 * last good history.
 *
 * <p>Two differences, both because Yahoo rate-limits and is still the history of record. The whole pass
 * is skipped while {@link YahooCooldown} is active — prices arm that pause and do not read it,
 * but a weekly dividend scrape can afford to wait. And a success is refreshed after six days,
 * not thirty: an ex-date moves, a sector does not.
 */
@Service
public class DividendHistoryService {

    private static final Logger log = LoggerFactory.getLogger(DividendHistoryService.class);

    /**
     * Anything older than this is refreshed by the Sunday pass. Deliberately under the 7-day cron
     * period: {@code refreshedAt} is stamped after the fetch, so a threshold of exactly seven days
     * would find every source a few minutes too fresh the following Sunday and skip it, halving
     * the refresh rate to once a fortnight.
     */
    public static final Duration REFRESH_AFTER = Duration.ofDays(6);

    /** A failure is usually a 429 or a blip, so it comes back round far sooner than a success. */
    public static final Duration RETRY_AFTER_FAILURE = Duration.ofDays(3);

    /** Delay between tickers within a pass. See {@link #pause()}. */
    static final Duration PACING = Duration.ofMillis(500);

    private static final int MONEY_SCALE = 8;

    private final DividendSourceRepository repository;
    private final DividendHistoryPort historyPort;
    private final NasdaqDividendPort nasdaq;
    private final PriceProviderPort prices;
    private final YahooCooldown cooldown;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    public DividendHistoryService(
        DividendSourceRepository repository,
        DividendHistoryPort historyPort,
        NasdaqDividendPort nasdaq,
        PriceProviderPort prices,
        YahooCooldown cooldown,
        TransactionTemplate txTemplate,
        Clock clock
    ) {
        this.repository = repository;
        this.historyPort = historyPort;
        this.nasdaq = nasdaq;
        this.prices = prices;
        this.cooldown = cooldown;
        this.txTemplate = txTemplate;
        this.clock = clock;
    }

    /**
     * Refreshes the stalest tickers, capped. Returns how many were actually refreshed.
     *
     * <p>Deliberately not {@code @Transactional}. The loop is network-bound, and wrapping it
     * would hold one database connection open for the whole burst and let a single constraint
     * violation roll back the entire batch. Each ticker commits on its own.
     *
     * <p>Returns immediately while Yahoo is cooling down. A 429 armed by this pass, or by a
     * price fetch that ran earlier, stops the rest of the tickers too — continuing would be
     * the request that turns a one-minute pause into a ban.
     */
    public int refreshStale(Collection<String> tickers, int limit) {
        if (tickers == null || tickers.isEmpty() || limit <= 0) {
            return 0;
        }
        if (cooldown.active()) {
            log.info("Skipping dividend history pass: Yahoo cooldown is active until {}", cooldown.until());
            return 0;
        }

        List<String> wanted = tickers.stream()
            .filter(Objects::nonNull)
            .map(t -> t.toUpperCase(Locale.ROOT))
            .distinct()
            .toList();
        if (wanted.isEmpty()) {
            return 0;
        }

        Map<String, DividendSource> known = repository.findByTickerIn(wanted).stream()
            .collect(Collectors.toMap(s -> s.getTicker().toUpperCase(Locale.ROOT), s -> s, (a, b) -> a));
        Instant now = clock.instant();
        Instant successCutoff = now.minus(REFRESH_AFTER);
        Instant failureCutoff = now.minus(RETRY_AFTER_FAILURE);

        // Null refreshedAt first (never fetched), then the oldest stamp, then the cap.
        // Alphabetical order plus a cap of 40 leaves the tail unfetched forever.
        List<String> due = wanted.stream()
            .filter(t -> isDue(known.get(t), successCutoff, failureCutoff))
            .sorted(Comparator
                .comparing((String t) -> refreshedAt(known.get(t)), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Comparator.naturalOrder()))
            .limit(limit)
            .toList();

        int refreshed = 0;
        int attempted = 0;
        for (String ticker : due) {
            if (cooldown.active()) {
                log.info("Stopping dividend history pass: Yahoo cooldown armed mid-pass");
                break;
            }
            // Paced on attempts, not successes: a run of failures is exactly when a source is
            // most likely to be rate-limiting us.
            if (attempted++ > 0) {
                pause();
            }
            try {
                // HTTP first, then a short transaction for the write. The scheduler thread must
                // not hold a connection across Yahoo and Nasdaq.
                Fetched fetched = fetch(ticker);
                DividendSource result = txTemplate.execute(status -> persist(ticker, fetched));
                if (result != null && result.getStatus() != DividendSourceStatus.FAILED) {
                    refreshed++;
                }
            } catch (Exception ex) {
                log.warn("Dividend history refresh failed for {}: {}",
                    LogSanitizer.safe(ticker), LogSanitizer.safe(ex.getMessage()));
            }
        }
        return refreshed;
    }

    /**
     * Two cutoffs, not one. A failure has to come back round sooner than a success, or "retry
     * later" is just the seven-day lockout under another name.
     *
     * The failure check comes first on purpose. A ticker that has never succeeded has a null
     * {@code refreshedAt}, and reading that as "due immediately" would retry a 429 on the next
     * pass instead of waiting the three days. A success is due at the cutoff, not strictly
     * before it: a stamp of exactly six days is the Sunday boundary, not a miss.
     */
    private static boolean isDue(DividendSource source, Instant successCutoff, Instant failureCutoff) {
        if (source == null) {
            return true;
        }
        if (source.getStatus() == DividendSourceStatus.FAILED) {
            return source.getLastAttemptAt() == null || source.getLastAttemptAt().isBefore(failureCutoff);
        }
        if (source.getRefreshedAt() == null) {
            return true;
        }
        return !source.getRefreshedAt().isAfter(successCutoff);
    }

    private static Instant refreshedAt(DividendSource source) {
        return source == null ? null : source.getRefreshedAt();
    }

    /**
     * Yahoo, FX and Nasdaq. No repository call: this runs before the write transaction so a
     * slow chart response does not hold a database connection.
     */
    private Fetched fetch(String ticker) {
        DividendHistory history;
        try {
            history = historyPort.fetchDividendHistory(ticker);
        } catch (Exception ex) {
            return Fetched.failed(ex);
        }
        if (history == null || history.events() == null || history.events().isEmpty()) {
            return Fetched.ok(history, null, fetchNasdaq(ticker));
        }
        String currency = history.currency();
        if (currency == null || currency.isBlank()) {
            return Fetched.failed(new IllegalStateException("dividend currency missing"));
        }
        BigDecimal rate = prices.getFxRateToEur(currency);
        if (rate == null) {
            return Fetched.failed(new IllegalStateException("FX rate unavailable for " + currency));
        }
        return Fetched.ok(history, rate, fetchNasdaq(ticker));
    }

    private NasdaqFetch fetchNasdaq(String ticker) {
        if (nasdaq == null || !nasdaqEligible(ticker)) {
            return NasdaqFetch.notConsulted();
        }
        try {
            return NasdaqFetch.of(nasdaq.fetchDividends(ticker));
        } catch (Exception ex) {
            log.warn("Nasdaq dividend complement failed for {}: {}",
                LogSanitizer.safe(ticker), LogSanitizer.safe(ex.getMessage()));
            return NasdaqFetch.unsuccessful();
        }
    }

    /** The write half of a refresh. Opens no HTTP call. */
    private DividendSource persist(String ticker, Fetched fetched) {
        DividendSource source = repository.findByTicker(ticker)
            .orElseGet(() -> DividendSource.builder().ticker(ticker).build());
        source.setLastAttemptAt(clock.instant());
        if (fetched.failure() != null) {
            return repository.save(markFailed(source, fetched.failure()));
        }

        DividendHistory history = fetched.history();
        if (history == null || history.events() == null || history.events().isEmpty()) {
            // A successful empty answer is "this symbol pays nothing", not a failure. Past events
            // Yahoo no longer lists are dropped. A future declaration stays when Nasdaq did not
            // return that ex-date — absence is not a withdrawal.
            Complemented complemented = complement(
                ticker, List.of(), null, history == null ? null : history.currency(), fetched.nasdaq());
            writeEvents(source, complemented.events(), complemented.keepAbsentFutureDeclared());
            if (history != null && history.currency() != null && !history.currency().isBlank()) {
                source.setCurrency(history.currency());
            }
            source.setStatus(source.getEvents() == null || source.getEvents().isEmpty()
                ? DividendSourceStatus.NO_DATA : DividendSourceStatus.OK);
            source.setLastError(null);
            source.setRefreshedAt(clock.instant());
            return repository.save(source);
        }

        String currency = history.currency();
        BigDecimal rate = fetched.rate();
        Map<LocalDate, DividendHistory.Point> byDate = new LinkedHashMap<>();
        for (DividendHistory.Point point : history.events()) {
            if (point == null || point.exDate() == null || point.amount() == null) {
                continue;
            }
            byDate.put(point.exDate(), point);
        }
        if (byDate.isEmpty()) {
            Complemented complemented = complement(ticker, List.of(), rate, currency, fetched.nasdaq());
            writeEvents(source, complemented.events(), complemented.keepAbsentFutureDeclared());
            source.setCurrency(currency);
            source.setStatus(source.getEvents() == null || source.getEvents().isEmpty()
                ? DividendSourceStatus.NO_DATA : DividendSourceStatus.OK);
            source.setLastError(null);
            source.setRefreshedAt(clock.instant());
            return repository.save(source);
        }

        List<DividendEvent> events = new ArrayList<>();
        for (DividendHistory.Point point : byDate.values()) {
            events.add(DividendEvent.builder()
                .exDate(point.exDate())
                .amount(point.amount())
                .amountEur(point.amount().multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .build());
        }
        Complemented complemented = complement(ticker, events, rate, currency, fetched.nasdaq());
        writeEvents(source, complemented.events(), complemented.keepAbsentFutureDeclared());
        source.setCurrency(currency);
        source.setStatus(DividendSourceStatus.OK);
        source.setLastError(null);
        source.setRefreshedAt(clock.instant());
        return repository.save(source);
    }

    /** Yahoo and Nasdaq answers, already fetched. {@code failure} means do not touch the events. */
    private record Fetched(DividendHistory history, Exception failure, BigDecimal rate, NasdaqFetch nasdaq) {
        static Fetched failed(Exception failure) {
            return new Fetched(null, failure, null, NasdaqFetch.notConsulted());
        }

        static Fetched ok(DividendHistory history, BigDecimal rate, NasdaqFetch nasdaq) {
            return new Fetched(history, null, rate, nasdaq);
        }
    }

    /** Whether Nasdaq was asked, and what it returned. A failure is not an empty table. */
    private record NasdaqFetch(List<NasdaqDividend> rows, boolean failed, boolean consulted) {
        static NasdaqFetch notConsulted() {
            return new NasdaqFetch(null, false, false);
        }

        static NasdaqFetch unsuccessful() {
            return new NasdaqFetch(null, true, true);
        }

        static NasdaqFetch of(List<NasdaqDividend> rows) {
            return new NasdaqFetch(rows, false, true);
        }
    }

    /**
     * Merges by ex-date. A clear-and-replace drops a stored payment date the new row does not
     * repeat, and a future declaration Nasdaq failed to send back.
     *
     * <p>A stored payment date stays when the new row has none. A stored future declared event
     * stays when Nasdaq failed or returned empty. Any ex-date after today is a declaration,
     * payment date left null unless Nasdaq published one. A past event absent from this fetch
     * is dropped. One older than Yahoo's two-year window may be pruned for that reason; a
     * future event may not — being outside the historical window is not a withdrawal.
     *
     * <p>In-place, so the unique {@code (source_id, ex_date)} is never asked to insert a second
     * row for a date that is still there. An already-persisted source still flushes, so the
     * session sees the merge before the ticker transaction continues.
     */
    private void writeEvents(
        DividendSource source, List<DividendEvent> incoming, boolean keepAbsentFutureDeclared
    ) {
        LocalDate today = LocalDate.now(clock);
        List<DividendEvent> stored = source.getEvents();
        if (stored == null) {
            stored = new ArrayList<>();
            source.setEvents(stored);
        }
        Map<LocalDate, DividendEvent> byDate = new LinkedHashMap<>();
        for (DividendEvent event : stored) {
            if (event.getExDate() != null) {
                byDate.putIfAbsent(event.getExDate(), event);
            }
        }
        Set<LocalDate> incomingDates = new HashSet<>();
        if (incoming != null) {
            for (DividendEvent row : incoming) {
                if (row == null || row.getExDate() == null) {
                    continue;
                }
                incomingDates.add(row.getExDate());
                if (row.getExDate().isAfter(today)) {
                    row.setDeclared(true);
                }
                DividendEvent existing = byDate.get(row.getExDate());
                if (existing == null) {
                    row.setSource(source);
                    stored.add(row);
                    byDate.put(row.getExDate(), row);
                    continue;
                }
                existing.setAmount(row.getAmount());
                existing.setAmountEur(row.getAmountEur());
                if (row.getPaymentDate() != null) {
                    existing.setPaymentDate(row.getPaymentDate());
                }
                existing.setDeclared(row.isDeclared() || row.getExDate().isAfter(today));
            }
        }
        stored.removeIf(event -> dropAbsent(event, incomingDates, today, keepAbsentFutureDeclared));
        if (source.getId() != null) {
            repository.saveAndFlush(source);
        }
    }

    /**
     * {@code true} when the stored row is not in this fetch and should go.
     * A future declaration is kept when Nasdaq failed or came back empty. A past event older
     * than Yahoo's two-year window may be pruned; a past event inside that window that Yahoo
     * did not repeat is gone too, because the window would have included it.
     */
    private static boolean dropAbsent(
        DividendEvent event,
        Set<LocalDate> incomingDates,
        LocalDate today,
        boolean keepAbsentFutureDeclared
    ) {
        if (event == null || event.getExDate() == null) {
            return true;
        }
        if (incomingDates.contains(event.getExDate())) {
            return false;
        }
        if (event.getExDate().isAfter(today)) {
            return !(keepAbsentFutureDeclared && event.isDeclared());
        }
        return true;
    }

    /** Yahoo rows plus whether a missing future declaration must be kept. */
    private record Complemented(List<DividendEvent> events, boolean keepAbsentFutureDeclared) {}

    private static DividendSource markFailed(DividendSource source, Exception ex) {
        source.setStatus(DividendSourceStatus.FAILED);
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        source.setLastError(message.length() > 200 ? message.substring(0, 200) : message);
        log.warn("Dividend history lookup failed for {}: {}",
            LogSanitizer.safe(source.getTicker()), LogSanitizer.safe(message));
        return source;
    }

    /**
     * Nasdaq-listed symbols (no Yahoo exchange suffix) may publish a payment date and a
     * future ex-date Yahoo does not have yet. NYSE names and ETFs often come back with empty
     * rows: that is the normal case, logged at debug, and the Yahoo result stays.
     * A thrown failure is a warning and also leaves the Yahoo rows untouched.
     *
     * <p>The rows are already fetched. A stored future declaration stays unless this payload
     * actually supplied that ex-date. A skipped row (no amount, no FX) is not a supply.
     */
    private Complemented complement(
        String ticker, List<DividendEvent> yahoo, BigDecimal rate, String currency, NasdaqFetch nasdaqFetch
    ) {
        LocalDate today = LocalDate.now(clock);
        List<DividendEvent> marked = markFutureDeclared(yahoo, today);
        if (nasdaqFetch == null || !nasdaqFetch.consulted()) {
            return new Complemented(marked, false);
        }
        if (nasdaqFetch.failed()) {
            return new Complemented(marked, true);
        }
        List<NasdaqDividend> rows = nasdaqFetch.rows();
        if (rows == null || rows.isEmpty()) {
            log.debug("Nasdaq returned no dividend rows for {}; keeping the Yahoo result",
                LogSanitizer.safe(ticker));
            return new Complemented(marked, true);
        }
        List<DividendEvent> merged = new ArrayList<>(marked);
        for (NasdaqDividend row : rows) {
            if (row == null || row.exDate() == null) {
                continue;
            }
            DividendEvent existing = eventOn(merged, row.exDate());
            if (existing != null) {
                if (row.paymentDate() != null) {
                    existing.setPaymentDate(row.paymentDate());
                }
                if (row.exDate().isAfter(today)) {
                    existing.setDeclared(true);
                    applyNasdaqAmount(existing, row, rate, currency, ticker);
                }
                continue;
            }
            if (!row.exDate().isAfter(today) || row.amount() == null) {
                continue;
            }
            String needed = row.currency() != null ? row.currency() : currency;
            BigDecimal rowRate = rateFor(needed, rate, currency);
            if (rowRate == null) {
                log.warn("Nasdaq future dividend for {} skipped: no FX rate for {}",
                    LogSanitizer.safe(ticker), LogSanitizer.safe(needed));
                continue;
            }
            merged.add(DividendEvent.builder()
                .exDate(row.exDate())
                .paymentDate(row.paymentDate())
                .amount(row.amount())
                .amountEur(row.amount().multiply(rowRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .declared(true)
                .build());
        }
        // Keep a stored future declaration Nasdaq did not put in this merge. A non-empty
        // payload that omits a date, or skips it for a missing amount or FX rate, is not
        // a withdrawal.
        return new Complemented(markFutureDeclared(merged, today), true);
    }

    /**
     * A future overlap takes Nasdaq's amount when it is present and the FX rate exists.
     * A missing rate leaves the Yahoo amount: skipping the row must not blank it.
     */
    private void applyNasdaqAmount(
        DividendEvent existing, NasdaqDividend row, BigDecimal rate, String currency, String ticker
    ) {
        if (row.amount() == null) {
            return;
        }
        String needed = row.currency() != null ? row.currency() : currency;
        BigDecimal rowRate = rateFor(needed, rate, currency);
        if (rowRate == null) {
            log.warn("Nasdaq amount for {} on {} left unchanged: no FX rate for {}",
                LogSanitizer.safe(ticker), row.exDate(), LogSanitizer.safe(needed));
            return;
        }
        existing.setAmount(row.amount());
        existing.setAmountEur(row.amount().multiply(rowRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    /** An ex-date after today is a declaration even when Yahoo published no payment date. */
    private static List<DividendEvent> markFutureDeclared(List<DividendEvent> events, LocalDate today) {
        if (events == null || events.isEmpty()) {
            return events == null ? List.of() : events;
        }
        for (DividendEvent event : events) {
            if (event != null && event.getExDate() != null && event.getExDate().isAfter(today)) {
                event.setDeclared(true);
            }
        }
        return events;
    }

    /** Nasdaq-listed symbols have no Yahoo exchange suffix. {@code TTE.PA} is not one of them. */
    static boolean nasdaqEligible(String ticker) {
        return ticker != null && !ticker.isBlank() && ticker.indexOf('.') < 0;
    }

    private BigDecimal rateFor(String needed, BigDecimal knownRate, String knownCurrency) {
        // An unknown currency is not euros: null makes the caller skip the row rather than
        // store its raw amount as EUR.
        if (needed == null || needed.isBlank()) {
            return null;
        }
        if ("EUR".equalsIgnoreCase(needed)) {
            return BigDecimal.ONE;
        }
        if (knownRate != null && needed.equalsIgnoreCase(knownCurrency)) {
            return knownRate;
        }
        return prices.getFxRateToEur(needed);
    }

    private static DividendEvent eventOn(List<DividendEvent> events, LocalDate exDate) {
        for (DividendEvent event : events) {
            if (exDate.equals(event.getExDate())) {
                return event;
            }
        }
        return null;
    }

    /** Breathing room between tickers. Forty back-to-back chart calls is how Yahoo starts refusing. */
    private void pause() {
        try {
            Thread.sleep(PACING.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
