package com.picsou.service;

import com.picsou.repository.AccountHoldingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the profile refresh on demand, off the request thread.
 *
 * <p>The weekly pass is the normal way profiles get warm. It is not enough on its own: a fresh
 * install shows an entirely unclassified breakdown until the next Sunday, which reads as the
 * feature being broken rather than merely cold. So the same work is reachable from a button.
 *
 * <p>It cannot be synchronous. {@code refreshStale} makes one or two HTTP calls per ticker with
 * no pacing between them, so a portfolio of forty securities is a minute of scraping — far past
 * any sane request timeout, and holding a connection open for it would be the wrong shape anyway.
 *
 * <p>The pass covers every distinct ticker in the instance, not just the caller's. A profile is
 * global reference data — the same reasoning the scheduled pass is built on — so scoping it per
 * member would rescrape the same securities once per household member for no gain.
 *
 * <p>Single thread and an in-flight flag, so leaning on the button cannot fan out into concurrent
 * scrapes of two unofficial sources. A second call while one is running is reported as such
 * rather than queued: the answer the caller wants is "it is already happening".
 */
@Service
public class SecurityProfileRefreshRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityProfileRefreshRunner.class);

    /** Same cap as the scheduled pass, for the same reason: the sources are rate-limited. */
    static final int BATCH = 40;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "security-profile-refresh");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AccountHoldingRepository holdingRepository;
    private final SecurityProfileService profileService;

    public SecurityProfileRefreshRunner(AccountHoldingRepository holdingRepository,
                                        SecurityProfileService profileService) {
        this.holdingRepository = holdingRepository;
        this.profileService = profileService;
    }

    /**
     * Queues a pass unless one is already in flight.
     *
     * @return how many distinct tickers the pass will consider, or -1 when one was already running
     */
    public int trigger() {
        if (!running.compareAndSet(false, true)) return -1;

        Set<String> tickers = new TreeSet<>(holdingRepository.findDistinctTickers());
        if (tickers.isEmpty()) {
            running.set(false);
            return 0;
        }

        executor.submit(() -> {
            try {
                int refreshed = profileService.refreshStale(tickers, BATCH);
                log.info("Security profiles: on-demand refresh updated {} of {} tickers",
                    refreshed, tickers.size());
            } catch (Exception ex) {
                log.error("On-demand security profile refresh failed", ex);
            } finally {
                running.set(false);
            }
        });
        return tickers.size();
    }

    public boolean isRunning() {
        return running.get();
    }
}
