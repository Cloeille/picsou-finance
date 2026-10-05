package com.picsou.service;

import com.picsou.dto.DiversificationResponse;
import com.picsou.dto.HoldingResponse;
import com.picsou.dto.WeightedSlice;
import com.picsou.model.*;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.HoldingClassificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Spreads the equity sleeve across sectors and regions.
 *
 * <p>Reads only persisted profiles — never the network. A ticker nobody has warmed yet counts as
 * unclassified and is listed in {@code unclassified}, which is honest and keeps a page render off
 * an unofficial HTML endpoint. The list carries enough to correct the line by hand, because the
 * securities that land there are largely the ones no provider will ever resolve.
 */
@Service
@Transactional(readOnly = true)
public class PortfolioDiversificationService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int SCALE = 8;

    /**
     * How many sectors and regions count as diversified.
     *
     * <p>Eleven sectors exist, but a portfolio spread evenly over eleven is a closet index fund;
     * six effective sectors is where concentration stops being the dominant risk. Three regions
     * is the equivalent for geography, where the choice is realistically US / Europe / Asia.
     */
    /** Enough to answer "why is this slice this big?"; more is a list, not an answer. */
    private static final int MAX_CONTRIBUTORS_PER_SLICE = 12;

    /** Below this a contributor explains nothing and joins the folded tail. */
    private static final BigDecimal MIN_CONTRIBUTOR_PERCENT = new BigDecimal("0.5");

    private static final int SECTOR_TARGET = 6;
    private static final int COUNTRY_TARGET = 3;

    private final AccountAccessResolver accessResolver;
    private final AccountService accountService;
    private final AccountHoldingRepository holdingRepository;
    private final HoldingClassificationRepository classificationRepository;
    private final SecurityProfileService securityProfileService;

    public PortfolioDiversificationService(AccountAccessResolver accessResolver,
                                           AccountService accountService,
                                           AccountHoldingRepository holdingRepository,
                                           HoldingClassificationRepository classificationRepository,
                                           SecurityProfileService securityProfileService) {
        this.accessResolver = accessResolver;
        this.accountService = accountService;
        this.holdingRepository = holdingRepository;
        this.classificationRepository = classificationRepository;
        this.securityProfileService = securityProfileService;
    }

    public DiversificationResponse diversification(Long memberId) {
        List<Account> accounts = accessResolver.readableAccounts(memberId);
        Map<Long, BigDecimal> shares = accessResolver.sharesFor(accounts, memberId);

        Map<String, HoldingClassification> overrides = new HashMap<>();
        for (HoldingClassification c : classificationRepository.findByMemberId(memberId)) {
            overrides.put(c.getTicker().toUpperCase(Locale.ROOT), c);
        }

        // One pass to collect the equity lines, so profiles can be loaded in a single query.
        Map<String, BigDecimal> valueByTicker = new LinkedHashMap<>();
        // Where each ticker was first seen. Kept only so an unclassified line can be named and
        // linked back to an account the member owns; the classification itself is per-ticker.
        Map<String, HoldingOrigin> originByTicker = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;

        for (Account account : accounts) {
            if (WealthTier.of(account.getType()) != WealthTier.EQUITY) continue;
            if (!holdingRepository.existsForReadableAccount(account.getId(), memberId)) continue;

            BigDecimal share = shares.get(account.getId());
            for (HoldingResponse line : accountService.getHoldings(account.getId(), memberId)) {
                if (line.currentValueEur() == null || line.ticker() == null) continue;
                BigDecimal value = AccountAccessResolver.weigh(line.currentValueEur(), share);
                if (value.signum() == 0) continue;
                // The same ticker held in two accounts is one position for this purpose.
                String ticker = line.ticker().toUpperCase(Locale.ROOT);
                valueByTicker.merge(ticker, value, BigDecimal::add);
                originByTicker.putIfAbsent(ticker,
                    new HoldingOrigin(account.getId(), line.name()));
                total = total.add(value);
            }
        }

        Map<String, SecurityProfile> profiles = securityProfileService.load(valueByTicker.keySet());

        AxisTotals sectorTotals = new AxisTotals();
        AxisTotals countryTotals = new AxisTotals();
        List<DiversificationResponse.UnclassifiedLine> unclassifiedLines = new ArrayList<>();
        BigDecimal sectorClassified = BigDecimal.ZERO;
        BigDecimal countryClassified = BigDecimal.ZERO;
        boolean sectorMixed = false;
        boolean countryMixed = false;

        for (Map.Entry<String, BigDecimal> entry : valueByTicker.entrySet()) {
            String ticker = entry.getKey();
            BigDecimal value = entry.getValue();
            HoldingClassification override = overrides.get(ticker);
            SecurityProfile profile = profiles.get(ticker);

            boolean placedSector = false;
            boolean placedCountry = false;

            // The user's verdict beats anything a provider inferred, per field.
            if (override != null && override.getSectorKey() != null) {
                sectorTotals.add(override.getSectorKey(), ticker, value);
                sectorClassified = sectorClassified.add(value);
                sectorMixed = true;
                placedSector = true;
            }
            if (override != null && override.getCountryKey() != null) {
                countryTotals.add(override.getCountryKey(), ticker, value);
                countryClassified = countryClassified.add(value);
                countryMixed = true;
                placedCountry = true;
            }

            if (!wasLookedUp(profile)) {
                // Never looked up, so whatever the override did not cover is missing.
                // profileLooked=false is what lets the UI offer a refresh instead of sending
                // someone to fill in a form the scheduler would have filled itself.
                //
                // Guarded, because an override can cover both axes on its own: a security no
                // provider carries — an employee-savings FCPE — is fully classified by hand and
                // has no profile, and listing it again would make the correction look ignored.
                if (!placedSector || !placedCountry) {
                    record(unclassifiedLines, originByTicker, ticker, value,
                        !placedSector, !placedCountry, false);
                }
                continue;
            }

            List<SecurityCompositionSlice> slices = profile.getSlices();
            if (!placedSector) {
                BigDecimal placed = spread(slices, SecuritySliceKind.SECTOR, ticker, value, sectorTotals);
                if (placed.signum() > 0) {
                    sectorClassified = sectorClassified.add(placed);
                    placedSector = true;
                } else if (profile.getSectorKey() != null) {
                    sectorTotals.add(profile.getSectorKey(), ticker, value);
                    sectorClassified = sectorClassified.add(value);
                    // A single share contributes one sector; mixing it with a fund's
                    // look-through is a different quantity, and the UI says so.
                    sectorMixed = true;
                    placedSector = true;
                }
            }
            if (!placedCountry) {
                BigDecimal placed = spread(slices, SecuritySliceKind.COUNTRY, ticker, value, countryTotals);
                if (placed.signum() > 0) {
                    countryClassified = countryClassified.add(placed);
                    placedCountry = true;
                } else if (profile.getCountryKey() != null) {
                    countryTotals.add(profile.getCountryKey(), ticker, value);
                    countryClassified = countryClassified.add(value);
                    countryMixed = true;
                    placedCountry = true;
                }
            }

            // Looked up and still short of one axis: a profile exists, so this one is only fixable
            // by hand. Reported per axis because a share often has a sector and no domicile.
            if (!placedSector || !placedCountry) {
                record(unclassifiedLines, originByTicker, ticker, value,
                    !placedSector, !placedCountry, true);
            }
        }

        // A holding counts as classified when *either* breakdown could place it; the two are
        // reported separately below, so the headline coverage is the more generous of the two.
        BigDecimal classified = sectorClassified.max(countryClassified);
        BigDecimal unclassified = total.subtract(classified).max(BigDecimal.ZERO);

        DiversificationResponse.Breakdown sectors =
            breakdown(sectorTotals, sectorClassified, total, SECTOR_TARGET, sectorMixed);
        DiversificationResponse.Breakdown countries =
            breakdown(countryTotals, countryClassified, total, COUNTRY_TARGET, countryMixed);

        return new DiversificationResponse(
            scale2(total),
            scale2(classified),
            scale2(unclassified),
            scale2(percentOf(classified, total)),
            // Biggest first: the line worth correcting by hand is the one moving the most money.
            unclassifiedLines.stream()
                .sorted(Comparator.comparing(
                    DiversificationResponse.UnclassifiedLine::valueEur).reversed())
                .toList(),
            sectors,
            countries,
            securitiesIn(sectors, countries, originByTicker, valueByTicker)
        );
    }

    /**
     * Running totals for one axis, keeping the holdings behind each label.
     *
     * <p>The per-label total used to be a plain {@code Map<String, BigDecimal>} and the ticker was
     * lost at the merge — which is why the breakdown could say "France 8.4 %" and not say thanks
     * to what.
     */
    private static final class AxisTotals {
        final Map<String, BigDecimal> byLabel = new HashMap<>();
        final Map<String, Map<String, BigDecimal>> byLabelAndTicker = new HashMap<>();

        void add(String label, String ticker, BigDecimal amount) {
            byLabel.merge(label, amount, BigDecimal::add);
            byLabelAndTicker
                .computeIfAbsent(label, k -> new HashMap<>())
                .merge(ticker, amount, BigDecimal::add);
        }
    }

    /** Where a ticker was first seen, so an unclassified line can be named and reached. */
    private record HoldingOrigin(Long accountId, String name) {}

    /**
     * Whether a provider has actually been asked about this security.
     *
     * <p>Not {@code profile != null}: a sync seeds a row carrying an ISIN and nothing else, so a
     * profile can exist without anything ever having been looked up. Reading nullity would tell
     * the member "we looked, classify it by hand" about a security nobody has queried yet — and
     * a hand-made override then permanently masks whatever the lookup would have found.
     */
    private static boolean wasLookedUp(SecurityProfile profile) {
        return profile != null && profile.getStatus() != SecurityProfileStatus.NEVER_FETCHED;
    }

    private static void record(List<DiversificationResponse.UnclassifiedLine> into,
                               Map<String, HoldingOrigin> origins, String ticker,
                               BigDecimal value, boolean sectorMissing, boolean countryMissing,
                               boolean profileLooked) {
        HoldingOrigin origin = origins.get(ticker);
        into.add(new DiversificationResponse.UnclassifiedLine(
            ticker,
            origin == null ? null : origin.name(),
            origin == null ? null : origin.accountId(),
            scale2(value),
            sectorMissing,
            countryMissing,
            profileLooked));
    }

    /**
     * Distributes one holding's value across its look-through slices, applying the published
     * percentages <em>literally</em>, and reports how much of the value that actually placed.
     *
     * <p>This used to renormalise to the published total and then declare the whole holding
     * placed. The comment defending that assumed providers publish near-complete distributions.
     * They do not: Boursorama's own sector breakdowns in this repo's fixtures sum to 87.25 % and
     * 70.04 %, and justETF names its remainder outright ("Other 17.84 %"). Renormalising took a
     * fund whose sectors were 70 % disclosed and reported it as fully classified — inventing the
     * missing 30 % by inflating the parts we happened to know.
     *
     * <p>So the undisclosed remainder now stays undisclosed. It lands in
     * {@code unclassifiedValueEur} and lowers {@code coveragePercent}, which is the figure that
     * exists to say how much of the portfolio a score was computed over. Reported coverage drops
     * for some funds; it was overstated before.
     */
    private static BigDecimal spread(List<SecurityCompositionSlice> slices, SecuritySliceKind kind,
                                     String ticker, BigDecimal value, AxisTotals target) {
        BigDecimal placed = BigDecimal.ZERO;
        for (SecurityCompositionSlice slice : slices) {
            if (slice.getKind() != kind) continue;
            BigDecimal part = value.multiply(slice.getPercent())
                .divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
            target.add(slice.getLabel(), ticker, part);
            placed = placed.add(part);
        }
        return placed;
    }

    private static DiversificationResponse.Breakdown breakdown(AxisTotals totals,
                                                               BigDecimal classified,
                                                               BigDecimal total,
                                                               int target,
                                                               boolean mixed) {
        if (classified.signum() <= 0) {
            return new DiversificationResponse.Breakdown(
                0, BigDecimal.ZERO, target, mixed ? "MIXED" : "EXPOSURE",
                BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        }

        List<DiversificationResponse.Slice> slices = totals.byLabel.entrySet().stream()
            .map(e -> new DiversificationResponse.Slice(
                e.getKey(),
                scale2(percentOf(e.getValue(), classified)),
                scale2(e.getValue()),
                contributorsOf(totals.byLabelAndTicker.get(e.getKey()), e.getValue()),
                totals.byLabelAndTicker.getOrDefault(e.getKey(), Map.of()).size()))
            .sorted(Comparator.comparing(DiversificationResponse.Slice::percent).reversed())
            .toList();

        // Inverse Herfindahl: the effective number of positions. Two portfolios can hold five
        // sectors and be nothing alike — 20/20/20/20/20 is five, 96/1/1/1/1 is barely one — and
        // counting buckets cannot tell them apart.
        BigDecimal herfindahl = BigDecimal.ZERO;
        for (BigDecimal value : totals.byLabel.values()) {
            BigDecimal weight = value.divide(classified, SCALE, RoundingMode.HALF_UP);
            herfindahl = herfindahl.add(weight.multiply(weight));
        }
        BigDecimal effective = herfindahl.signum() == 0
            ? BigDecimal.ZERO
            : BigDecimal.ONE.divide(herfindahl, SCALE, RoundingMode.HALF_UP);

        int score = effective.divide(BigDecimal.valueOf(target), SCALE, RoundingMode.HALF_UP)
            .multiply(HUNDRED).min(HUNDRED)
            .setScale(0, RoundingMode.HALF_UP).intValue();

        return new DiversificationResponse.Breakdown(
            score, effective.setScale(2, RoundingMode.HALF_UP), target,
            mixed ? "MIXED" : "EXPOSURE",
            scale2(classified), scale2(percentOf(classified, total)), slices);
    }

    /**
     * Names every security that appears as a contributor, once.
     *
     * <p>Built from the slices rather than from the whole portfolio: a holding that ended up
     * unclassified contributes to nothing, and listing it here would invite the UI to draw it.
     */
    private static List<DiversificationResponse.SecurityInfo> securitiesIn(
        DiversificationResponse.Breakdown sectors,
        DiversificationResponse.Breakdown countries,
        Map<String, HoldingOrigin> origins,
        Map<String, BigDecimal> valueByTicker) {

        Set<String> tickers = new LinkedHashSet<>();
        for (DiversificationResponse.Breakdown b : List.of(sectors, countries)) {
            for (DiversificationResponse.Slice slice : b.slices()) {
                for (DiversificationResponse.Contributor c : slice.contributors()) {
                    if (c.ticker() != null) tickers.add(c.ticker());
                }
            }
        }
        return tickers.stream()
            .map(t -> {
                HoldingOrigin origin = origins.get(t);
                return new DiversificationResponse.SecurityInfo(
                    t,
                    origin == null ? null : origin.name(),
                    origin == null ? null : origin.accountId(),
                    scale2(valueByTicker.getOrDefault(t, BigDecimal.ZERO)));
            })
            .toList();
    }

    /**
     * The holdings behind one slice, largest first, with the tail folded.
     *
     * <p>Capped because a slice is answering "why is this 8.4 %?", and thirty lines of 0.1 % do
     * not answer it — while a broad portfolio would otherwise repeat every ticker across every
     * slice of both axes. The fold keeps the total honest: its euros are the rest, and
     * {@code contributorCount} still reports how many holdings there really are.
     */
    private static List<DiversificationResponse.Contributor> contributorsOf(
        Map<String, BigDecimal> byTicker, BigDecimal sliceTotal) {

        if (byTicker == null || byTicker.isEmpty() || sliceTotal.signum() <= 0) return List.of();

        List<Map.Entry<String, BigDecimal>> ranked = byTicker.entrySet().stream()
            .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
            .toList();

        List<DiversificationResponse.Contributor> out = new ArrayList<>();
        BigDecimal folded = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : ranked) {
            BigDecimal share = percentOf(entry.getValue(), sliceTotal);
            boolean tooSmall = share.compareTo(MIN_CONTRIBUTOR_PERCENT) < 0;
            if (out.size() >= MAX_CONTRIBUTORS_PER_SLICE || tooSmall) {
                folded = folded.add(entry.getValue());
                continue;
            }
            out.add(new DiversificationResponse.Contributor(
                entry.getKey(), scale2(entry.getValue()), scale2(share)));
        }
        if (folded.signum() > 0) {
            // Null ticker is the tail, not a security — the UI renders it as "and N others".
            out.add(new DiversificationResponse.Contributor(
                null, scale2(folded), scale2(percentOf(folded, sliceTotal))));
        }
        return out;
    }

    private static BigDecimal percentOf(BigDecimal value, BigDecimal total) {
        if (total == null || total.signum() == 0) return BigDecimal.ZERO;
        return value.divide(total, SCALE, RoundingMode.HALF_UP).multiply(HUNDRED);
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
