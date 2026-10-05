package com.picsou.service;

import com.picsou.dto.HoldingLogoUrls;
import com.picsou.model.AccountType;
import com.picsou.model.InstrumentLogo;
import com.picsou.model.InstrumentLogoStatus;
import com.picsou.port.InstrumentLogoPort;
import com.picsou.port.InstrumentLogoPort.Lookup;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.InstrumentLogoRepository;
import com.picsou.repository.PriceSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Owns the durable store of share and fund marks (issue #162, equity half).
 *
 * <p>Two paths kept apart, as in {@link SecurityProfileService}: {@link #storedUrls} and
 * {@link #image} only read the table, so a page render never waits on Yahoo; {@link #resolvePending}
 * does the network work and runs off-thread, after the hourly price pass.
 *
 * <p>Why after the price pass rather than inside each sync that writes a holding: a ticker only
 * becomes a candidate once a price provider has answered for it ({@code price_snapshot}). That is
 * the request-free proof that it is a quoted symbol and not a fund code or a cash line, and the
 * price pass is what writes it. Hooking the nine sync services would trigger passes that find
 * nothing new yet, and would put logo work on their transaction path.
 */
@Service
public class InstrumentLogoService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentLogoService.class);

    /** Lookups per pass. Each is a ~1 MB page from the host the price path also depends on. */
    static final int BATCH = 10;

    /**
     * When a ticker whose lookup got no answer is asked again. A week rather than a day: the
     * pass runs hourly, and a page Yahoo refuses to serve this host should cost a request a week,
     * not one an hour, while a real outage still heals within days.
     */
    static final Duration RETRY_FAILED_AFTER = Duration.ofDays(7);

    /** Breathing room between lookups, as {@link SecurityProfileService#PACING}. */
    static final Duration PACING = Duration.ofMillis(500);

    static final String PATH = "/api/instrument-logos/";

    private final InstrumentLogoRepository repository;
    private final AccountHoldingRepository holdingRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final InstrumentLogoPort logoPort;
    private final Clock clock;
    private final boolean enabled;
    private final Executor executor;
    private final Duration pacing;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public InstrumentLogoService(InstrumentLogoRepository repository,
                                 AccountHoldingRepository holdingRepository,
                                 PriceSnapshotRepository priceSnapshotRepository,
                                 InstrumentLogoPort logoPort,
                                 Clock clock,
                                 @Value("${app.instrument-logos.enabled:true}") boolean enabled) {
        this(repository, holdingRepository, priceSnapshotRepository, logoPort, clock, enabled,
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "instrument-logo-resolver");
                t.setDaemon(true);
                return t;
            }), PACING);
    }

    InstrumentLogoService(InstrumentLogoRepository repository,
                          AccountHoldingRepository holdingRepository,
                          PriceSnapshotRepository priceSnapshotRepository,
                          InstrumentLogoPort logoPort,
                          Clock clock,
                          boolean enabled,
                          Executor executor,
                          Duration pacing) {
        this.repository = repository;
        this.holdingRepository = holdingRepository;
        this.priceSnapshotRepository = priceSnapshotRepository;
        this.logoPort = logoPort;
        this.clock = clock;
        this.enabled = enabled;
        this.executor = executor;
        this.pacing = pacing;
    }

    /**
     * Queues a pass off the caller's thread, unless one is in flight or the feature is switched
     * off ({@code INSTRUMENT_LOGOS_ENABLED=false}). Never blocks and never throws, so the price
     * pass that calls it cannot be slowed or failed by it.
     */
    public void requestResolution() {
        if (!enabled) return;
        if (!running.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    resolvePending();
                } catch (Exception ex) {
                    log.error("Instrument logo pass failed", ex);
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException ex) {
            running.set(false);
            log.warn("Could not queue the instrument logo pass: {}", ex.getMessage());
        }
    }

    /**
     * One capped pass over the tickers that are due. Returns how many marks were stored.
     *
     * <p>Stops at the first sign that Yahoo is not answering: a 429 or a cooldown records nothing
     * (the ticker was not really asked, so it stays first in line), a 5xx or timeout records the
     * ticker as FAILED. Either way the rest of the batch waits for the next pass rather than
     * spending more of the budget the price path depends on. A lookup that throws is a bug, not
     * an answer from Yahoo: the ticker is recorded as FAILED and the pass goes on.
     */
    int resolvePending() {
        List<String> due = dueTickers();
        int stored = 0;
        for (int i = 0; i < due.size(); i++) {
            if (i > 0) pause();
            String ticker = due.get(i);
            Lookup result;
            try {
                result = logoPort.lookup(ticker);
            } catch (RuntimeException ex) {
                // A bug, not an outage, so it is logged loudly. Left unrecorded, the same ticker
                // would head the sorted batch every hour and starve every ticker after it.
                log.error("Instrument logo lookup for {} failed unexpectedly; retrying it in {} days",
                    ticker, RETRY_FAILED_AFTER.toDays(), ex);
                record(ticker, Instant.now(clock), logo -> logo.setStatus(InstrumentLogoStatus.FAILED));
                continue;
            }
            Instant now = Instant.now(clock);
            switch (result) {
                case Lookup.Found found -> {
                    record(ticker, now, logo -> {
                        logo.setStatus(InstrumentLogoStatus.STORED);
                        logo.setImage(found.light().bytes());
                        logo.setContentType(found.light().contentType());
                        logo.setImageDark(found.dark() == null ? null : found.dark().bytes());
                        logo.setContentTypeDark(found.dark() == null ? null : found.dark().contentType());
                        logo.setFetchedAt(now);
                    });
                    stored++;
                }
                case Lookup.Absent absent -> record(ticker, now, logo -> logo.setStatus(InstrumentLogoStatus.ABSENT));
                case Lookup.Unavailable unavailable -> {
                    record(ticker, now, logo -> logo.setStatus(InstrumentLogoStatus.FAILED));
                    log.info("Instrument logos: stopping this pass after {} ({}); {} left for later",
                        ticker, unavailable.reason(), due.size() - i - 1);
                    return stored;
                }
                case Lookup.RateLimited limited -> {
                    log.info("Instrument logos: Yahoo is rate-limiting, {} lookups left for a later pass",
                        due.size() - i);
                    return stored;
                }
            }
        }
        if (!due.isEmpty()) {
            log.info("Instrument logos: stored {} of {} looked up", stored, due.size());
        }
        return stored;
    }

    /**
     * Held, quoted, not crypto, and never answered for (or answered with a failure long enough
     * ago), capped at {@link #BATCH}. Sorted so a capped pass is deterministic.
     */
    List<String> dueTickers() {
        Set<String> crypto = upper(holdingRepository.findDistinctTickersByAccountType(AccountType.CRYPTO));
        Set<String> candidates = upper(holdingRepository.findDistinctTickers()).stream()
            .filter(t -> !crypto.contains(t))
            .filter(logoPort::supports)
            .collect(Collectors.toCollection(TreeSet::new));
        if (candidates.isEmpty()) return List.of();

        candidates.retainAll(priceSnapshotRepository.findPricedTickers(candidates));
        if (candidates.isEmpty()) return List.of();

        Instant retryCutoff = Instant.now(clock).minus(RETRY_FAILED_AFTER);
        candidates.removeAll(repository.findSettledTickers(candidates, retryCutoff));
        return candidates.stream().limit(BATCH).toList();
    }

    /**
     * Where to load the mark for each of {@code tickers} that has one, keyed upper-case. One query,
     * and it reads no image bytes. Tickers without a stored mark are absent from the map.
     *
     * <p>The URL carries {@code fetchedAt} as a version, so the endpoint can let the browser
     * cache it for a long time: a mark that is ever replaced gets a new URL.
     */
    public Map<String, HoldingLogoUrls> storedUrls(Collection<String> tickers) {
        Set<String> keys = upper(tickers);
        if (keys.isEmpty()) return Map.of();
        Map<String, HoldingLogoUrls> urls = new HashMap<>();
        for (var ref : repository.findByStatusAndTickerIn(InstrumentLogoStatus.STORED, keys)) {
            String base = PATH + UriUtils.encodePathSegment(ref.getTicker(), StandardCharsets.UTF_8)
                + "?v=" + ref.getFetchedAt().getEpochSecond();
            urls.put(ref.getTicker(), new HoldingLogoUrls(
                base,
                ref.getContentTypeDark() == null ? null : base + "&variant=dark"));
        }
        return urls;
    }

    /** The stored mark to serve. A missing dark variant falls back to the light one. */
    public Optional<ServedImage> image(String ticker, boolean dark) {
        if (ticker == null || ticker.isBlank()) return Optional.empty();
        return repository.findByTicker(ticker.toUpperCase(Locale.ROOT))
            .filter(logo -> logo.getStatus() == InstrumentLogoStatus.STORED && logo.getImage() != null)
            .map(logo -> dark && logo.getImageDark() != null
                ? new ServedImage(logo.getImageDark(), logo.getContentTypeDark(), logo.getFetchedAt(), true)
                : new ServedImage(logo.getImage(), logo.getContentType(), logo.getFetchedAt(), false));
    }

    public record ServedImage(byte[] bytes, String contentType, Instant fetchedAt, boolean dark) {}

    private void record(String ticker, Instant now, java.util.function.Consumer<InstrumentLogo> apply) {
        InstrumentLogo logo = repository.findByTicker(ticker)
            .orElseGet(() -> InstrumentLogo.builder().ticker(ticker).build());
        logo.setAttemptedAt(now);
        apply.accept(logo);
        repository.save(logo);
    }

    private void pause() {
        if (pacing.isZero()) return;
        try {
            Thread.sleep(pacing.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static Set<String> upper(Collection<String> tickers) {
        if (tickers == null) return Set.of();
        return tickers.stream()
            .filter(Objects::nonNull)
            .filter(t -> !t.isBlank())
            .map(t -> t.toUpperCase(Locale.ROOT))
            .collect(Collectors.toCollection(TreeSet::new));
    }
}
