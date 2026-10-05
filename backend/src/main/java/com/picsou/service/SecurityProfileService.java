package com.picsou.service;

import com.picsou.dto.EquityProfile;
import com.picsou.dto.EtfComposition;
import com.picsou.dto.FundFacts;
import com.picsou.dto.SecurityInsightResponse;
import com.picsou.dto.WeightedSlice;
import com.picsou.model.SecurityCompositionSlice;
import com.picsou.model.SecurityProfile;
import com.picsou.model.SecurityProfileStatus;
import com.picsou.model.SecuritySliceKind;
import com.picsou.port.EquityProfileProvider;
import com.picsou.repository.SecurityProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Owns the durable cache of what each security is.
 *
 * <p>Two paths, deliberately kept apart: {@link #load} reads the table and nothing else, so
 * rendering a breakdown can never block on a scrape; {@link #refresh} does the network work and
 * is called only by the scheduler.
 *
 * <p>{@code SecurityInsightService} still answers the single-ticker {@code /insight} endpoint
 * from its own in-memory cache. That is one ticker, user-initiated, and fine — replacing it is a
 * separate change with its own tests.
 */
@Service
public class SecurityProfileService {

    private static final Logger log = LoggerFactory.getLogger(SecurityProfileService.class);

    /** A sector does not change. Anything older than this is refreshed by the weekly pass. */
    public static final Duration REFRESH_AFTER = Duration.ofDays(30);

    /** A failure is usually transient, so it comes back round far sooner than a success. */
    public static final Duration RETRY_AFTER_FAILURE = Duration.ofDays(3);

    /** Delay between tickers within a pass. See {@link #pause()}. */
    static final Duration PACING = Duration.ofMillis(500);

    private final SecurityProfileRepository repository;
    private final SecurityInsightService insightService;
    private final SecurityIdentityService identityService;
    private final List<EquityProfileProvider> equityProviders;
    private final TransactionTemplate txTemplate;

    public SecurityProfileService(SecurityProfileRepository repository,
                                  SecurityInsightService insightService,
                                  SecurityIdentityService identityService,
                                  List<EquityProfileProvider> equityProviders,
                                  TransactionTemplate txTemplate) {
        this.repository = repository;
        this.insightService = insightService;
        this.identityService = identityService;
        this.equityProviders = equityProviders;
        this.txTemplate = txTemplate;
    }

    /** Persisted profiles for the tickers asked for, keyed uppercase. Never fetches. */
    @Transactional(readOnly = true)
    public Map<String, SecurityProfile> load(Collection<String> tickers) {
        Set<String> upper = tickers.stream()
            .filter(Objects::nonNull)
            .map(t -> t.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
        if (upper.isEmpty()) return Map.of();
        return repository.findAllWithSlicesByTickerIn(upper).stream()
            .collect(Collectors.toMap(p -> p.getTicker().toUpperCase(Locale.ROOT), p -> p));
    }

    /**
     * Resolves one ticker from the network and stores the answer.
     *
     * <p>Asset type and ETF composition come from {@link SecurityInsightService}, so the two
     * paths can never disagree about what a security is. A single share additionally goes
     * through the {@link EquityProfileProvider}s, merged <strong>field by field</strong> — no one
     * source has both halves, so first-provider-wins would throw away the country whenever the
     * sector arrived first.
     */
    @Transactional
    public SecurityProfile refresh(String ticker) {
        String upper = ticker.toUpperCase(Locale.ROOT);

        SecurityProfile profile = repository.findByTicker(upper)
            .orElseGet(() -> SecurityProfile.builder().ticker(upper).build());

        // The ISIN a sync recorded, or the ticker itself when it is one. Passing it on is what
        // lets Boursorama resolve a fund whose ticker OpenFIGI picked from a US OTC listing.
        String isin = identityService.isinOf(upper).orElse(null);
        if (isin != null && profile.getIsin() == null) {
            profile.setIsin(isin);
        }
        profile.setLastAttemptAt(Instant.now());

        SecurityInsightResponse insight;
        try {
            insight = insightService.getInsight(upper, null, isin);
        } catch (Exception ex) {
            // Never overwrite on failure. The old code wiped the slices here and stamped
            // refreshedAt anyway, so one transient 502 destroyed a good look-through and then
            // locked the ticker out of retry for thirty days.
            return repository.save(markFailed(profile, ex));
        }

        EtfComposition composition = insight.composition();
        boolean resolved;
        if (composition != null) {
            profile.setSource(composition.source());
            profile.setAsOf(composition.asOf());
            List<SecurityCompositionSlice> slices = slicesOf(composition);
            // Facts can arrive without a breakdown, and a breakdown must not be blanked by a
            // source that only had the fee. Only write slices when there are some.
            if (!slices.isEmpty()) {
                profile.replaceSlices(slices);
                profile.setSectorKey(null);
                profile.setCountryKey(null);
            }
            applyFacts(profile, composition.facts());
            resolved = !slices.isEmpty() || composition.facts() != null;
        } else if ("STOCK".equals(insight.assetType())) {
            EquityProfile merged;
            try {
                merged = resolveEquity(upper);
            } catch (Exception ex) {
                return repository.save(markFailed(profile, ex));
            }
            resolved = merged.sectorKey() != null || merged.countryKey() != null;
            if (resolved) {
                profile.setSectorKey(merged.sectorKey());
                profile.setCountryKey(merged.countryKey());
                profile.setSource(merged.source());
                profile.replaceSlices(List.of());
            }
        } else {
            // Not a fund, and not a share we can profile — crypto, or something Yahoo does not
            // quote. Nothing to store, and nothing to destroy either.
            resolved = false;
        }

        profile.setAssetType(insight.assetType());
        profile.setLastError(null);
        profile.setStatus(resolved ? SecurityProfileStatus.OK : SecurityProfileStatus.NO_DATA);
        // Only a real answer advances the weekly clock. NO_DATA still counts as resolved: the
        // source was reachable and had nothing, which is worth remembering for a month.
        profile.setRefreshedAt(Instant.now());

        return repository.save(profile);
    }

    private static void applyFacts(SecurityProfile profile, FundFacts facts) {
        if (facts == null) return;
        // Field by field, and only over nulls we actually have a value for: a source that knows
        // the fee but not the domicile must not erase a domicile another one supplied.
        if (facts.terPercent() != null) profile.setTerPercent(facts.terPercent());
        if (facts.distributionPolicy() != null) profile.setDistributionPolicy(facts.distributionPolicy());
        if (facts.replication() != null) profile.setReplication(facts.replication());
        if (facts.domicileCountryKey() != null) profile.setDomicileCountry(facts.domicileCountryKey());
    }

    private static SecurityProfile markFailed(SecurityProfile profile, Exception ex) {
        profile.setStatus(SecurityProfileStatus.FAILED);
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        profile.setLastError(message.length() > 200 ? message.substring(0, 200) : message);
        log.warn("Security profile lookup failed for {}: {}", profile.getTicker(), message);
        return profile;
    }

    /**
     * Refreshes the stalest tickers, capped. Returns how many were actually refreshed.
     *
     * <p>Deliberately <strong>not</strong> {@code @Transactional}. The loop is network-bound —
     * several blocking HTTP calls per ticker against two unofficial sources — and wrapping it
     * would hold one database connection open for the whole burst and let a single constraint
     * violation roll back the entire batch. Each ticker commits on its own, the pattern
     * {@code IsinTickerRepairRunner} already established here.
     */
    public int refreshStale(Collection<String> tickers, int limit) {
        Map<String, SecurityProfile> known = load(tickers);
        Instant now = Instant.now();
        Instant successCutoff = now.minus(REFRESH_AFTER);
        Instant failureCutoff = now.minus(RETRY_AFTER_FAILURE);

        List<String> due = tickers.stream()
            .filter(Objects::nonNull)
            .map(t -> t.toUpperCase(Locale.ROOT))
            .distinct()
            .filter(t -> isDue(known.get(t), successCutoff, failureCutoff))
            .limit(limit)
            .toList();

        int refreshed = 0;
        int attempted = 0;
        for (String ticker : due) {
            // Paced on attempts, not successes: a run of failures is exactly when a source is
            // most likely to be rate-limiting us.
            if (attempted++ > 0) pause();
            // One bad ticker must not abort the pass — the same discipline dailySnapshots uses
            // per account. refresh() records a provider failure rather than throwing, so the
            // catch here is for everything below it: a repository or transaction failure.
            try {
                SecurityProfile result = txTemplate.execute(status -> refresh(ticker));
                if (result != null && result.getStatus() != SecurityProfileStatus.FAILED) {
                    refreshed++;
                }
            } catch (Exception ex) {
                log.warn("Security profile refresh failed for {}: {}", ticker, ex.getMessage());
            }
        }
        return refreshed;
    }

    /**
     * Two cutoffs, not one.
     *
     * <p>A failure has to come back round sooner than a success, or "retry later" is just the
     * thirty-day lockout under another name. A profile that has never been resolved — the row a
     * sync seeds from an ISIN alone — is due immediately.
     */
    private static boolean isDue(SecurityProfile p, Instant successCutoff, Instant failureCutoff) {
        if (p == null || p.getRefreshedAt() == null) return true;
        if (p.getStatus() == SecurityProfileStatus.FAILED) {
            return p.getLastAttemptAt() == null || p.getLastAttemptAt().isBefore(failureCutoff);
        }
        return p.getRefreshedAt().isBefore(successCutoff);
    }

    /**
     * Breathing room between tickers.
     *
     * <p>Forty back-to-back requests from one residential IP is exactly the shape that gets a
     * source to start refusing — measurably so: Yahoo's crumb endpoint answers "Too Many
     * Requests" to it. The pass is a weekly background job; it can afford to be slow.
     */
    private void pause() {
        try {
            Thread.sleep(PACING.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Field-by-field merge across providers: the first non-null sector wins, and independently
     * the first non-null country. This differs from {@code resolveComposition}, which stops at
     * the first provider with any data at all — deliberately, because here Yahoo has the sector
     * and only Boursorama has the ISIN the country comes from.
     */
    private EquityProfile resolveEquity(String ticker) {
        String sector = null;
        String country = null;
        boolean domicile = false;
        List<String> sources = new ArrayList<>();

        for (EquityProfileProvider provider : equityProviders) {
            if (sector != null && country != null) break;
            if (!provider.supports(ticker)) continue;
            Optional<EquityProfile> answer;
            try {
                answer = provider.fetch(ticker);
            } catch (Exception ex) {
                log.debug("Equity profile provider {} failed for {}: {}",
                    provider.getClass().getSimpleName(), ticker, ex.getMessage());
                continue;
            }
            if (answer.isEmpty() || answer.get().isEmpty()) continue;
            EquityProfile p = answer.get();
            if (sector == null && p.sectorKey() != null) {
                sector = p.sectorKey();
                sources.add(p.source());
            }
            if (country == null && p.countryKey() != null) {
                country = p.countryKey();
                domicile = p.countryIsDomicile();
                if (!sources.contains(p.source())) sources.add(p.source());
            }
        }
        return new EquityProfile(sector, country,
            sources.isEmpty() ? null : String.join(" · ", sources), domicile);
    }

    private static List<SecurityCompositionSlice> slicesOf(EtfComposition composition) {
        List<SecurityCompositionSlice> slices = new ArrayList<>();
        add(slices, composition.companies(), SecuritySliceKind.COMPANY);
        add(slices, composition.countries(), SecuritySliceKind.COUNTRY);
        add(slices, composition.sectors(), SecuritySliceKind.SECTOR);
        return slices;
    }

    private static void add(List<SecurityCompositionSlice> target,
                            List<WeightedSlice> source, SecuritySliceKind kind) {
        if (source == null) return;
        Set<String> seen = new HashSet<>();
        for (WeightedSlice slice : source) {
            if (slice.label() == null || slice.label().isBlank()) continue;
            // The unique key is (profile, kind, label); a provider repeating a label would
            // otherwise fail the whole save rather than the one duplicate line.
            if (!seen.add(slice.label())) continue;
            target.add(SecurityCompositionSlice.builder()
                .kind(kind)
                .label(slice.label())
                .percent(slice.percent())
                .build());
        }
    }
}
