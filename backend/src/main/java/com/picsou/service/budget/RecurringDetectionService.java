package com.picsou.service.budget;

import com.picsou.model.RecurringCadence;
import com.picsou.model.RecurringSeries;
import com.picsou.model.RecurringStatus;
import com.picsou.model.Transaction;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RecurringSeriesRepository;
import com.picsou.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Detects recurring cash movements (subscriptions, direct debits, salaries) — v2.
 *
 * <p>The v1 detector keyed identity on the raw bank {@code counterparty}, which drifts
 * ({@code "PAYPAL *NETFLIX 4357"} one month, {@code "NETFLIX EU"} the next) and split one
 * subscription across several phantom series. v2 keys on the clean {@code merchant_label} that the
 * categorizer now stamps on every transaction (see {@link MerchantNormalizer}) — a stable identity
 * backed by the unique index {@code (member_id, lower(label))}.
 *
 * <p>What v2 adds on top of "regular interval + stable amount":
 * <ul>
 *   <li><b>Confidence</b> in [0,1] from interval regularity + amount stability + occurrence count.</li>
 *   <li><b>Silent auto-confirm</b>: a high-confidence, fixed-amount series with enough occurrences is
 *       promoted straight to {@link RecurringStatus#CONFIRMED} and flagged {@code autoConfirmed} — the
 *       activity feed + per-item undo are the safety net (see {@code RecurringSeriesService}).</li>
 *   <li><b>Variable series</b>: a moderately-drifting amount (e.g. a utility bill) is kept as a series
 *       but marked {@code isVariable} and never silently auto-confirmed.</li>
 *   <li><b>Price-change tracking</b>: when the expected amount steps to a new level, the old value is
 *       kept in {@code previousAmount} with {@code priceChangedAt}.</li>
 *   <li><b>Back-links</b>: every transaction in a detected group gets {@code recurringSeriesId} set.</li>
 * </ul>
 *
 * <p>Precedence over the user is never inverted: an {@link RecurringStatus#IGNORED} series is never
 * resurrected, and auto-confirm only ever promotes a {@link RecurringStatus#SUGGESTED} one — a
 * user's confirm/ignore decision is left untouched.
 */
@Service
@Transactional(readOnly = true)
public class RecurringDetectionService {

    /** Detection needs at least this many occurrences to call something recurring. */
    static final int MIN_OCCURRENCES = 3;
    /** How far back to look — 400 days covers monthly/quarterly/yearly with margin. */
    static final int LOOKBACK_DAYS = 400;
    /** Each gap must be within this fraction of the median gap to count as regular. */
    static final double INTERVAL_TOLERANCE = 0.30;
    /** Amounts within this fraction of the median are "fixed". */
    static final double FIXED_TOLERANCE = 0.15;
    /** Amounts within this (wider) fraction are a "variable" series; beyond it, not a series at all. */
    static final double VARIABLE_TOLERANCE = 0.40;
    /** Classify amount stability over the most recent occurrences, so a one-off price step is not "variable". */
    static final int RECENT_WINDOW = 4;
    /** A relative move larger than this between the old and new expected amount is a price change. */
    static final double PRICE_CHANGE_PCT = 0.05;
    /** Confidence at/above which a fixed-amount series is auto-confirmed silently. */
    static final BigDecimal AUTO_CONFIRM_CONFIDENCE = new BigDecimal("0.80");
    /** Auto-confirm also needs at least this many occurrences. */
    static final int AUTO_CONFIRM_MIN_OCCURRENCES = 3;

    private final TransactionRepository transactionRepository;
    private final RecurringSeriesRepository seriesRepository;
    private final FamilyMemberRepository familyMemberRepository;

    public RecurringDetectionService(
        TransactionRepository transactionRepository,
        RecurringSeriesRepository seriesRepository,
        FamilyMemberRepository familyMemberRepository
    ) {
        this.transactionRepository = transactionRepository;
        this.seriesRepository = seriesRepository;
        this.familyMemberRepository = familyMemberRepository;
    }

    /**
     * Scan the member's recent transactions and upsert detected series; returns the number created
     * or refreshed. Idempotent: re-running over the same data keeps amounts/dates/confidence current
     * and links transactions, but does not flip any user decision.
     */
    @Transactional
    public int detect(Long memberId, LocalDate today) {
        LocalDate from = today.minusDays(LOOKBACK_DAYS);
        List<Transaction> transactions = transactionRepository
            .findByMemberIdAndDateBetween(memberId, from, today);

        Map<String, List<Transaction>> groups = groupByIdentity(transactions);

        int upserted = 0;
        for (List<Transaction> group : groups.values()) {
            Candidate candidate = analyse(group);
            if (candidate != null && !candidate.label().isBlank()
                && upsert(memberId, candidate, group)) {
                upserted++;
            }
        }
        return upserted;
    }

    // ─── Grouping ─────────────────────────────────────────────────────────────

    /** Group by stable merchant identity, preserving date order within each group. */
    static Map<String, List<Transaction>> groupByIdentity(List<Transaction> transactions) {
        Map<String, List<Transaction>> groups = new LinkedHashMap<>();
        for (Transaction tx : transactions) {
            String key = identityKey(tx);
            if (key.isEmpty()) {
                continue;
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(tx);
        }
        groups.values().forEach(list -> list.sort(Comparator.comparing(Transaction::getDate)
            .thenComparing(Transaction::getId)));
        return groups;
    }

    /**
     * Stable grouping key: the clean {@code merchant_label} when present (the categorizer normally
     * stamps it), falling back to the normalised raw counterparty for un-enriched transactions.
     */
    static String identityKey(Transaction tx) {
        String label = tx.getMerchantLabel();
        if (label != null && !label.isBlank()) {
            return normalise(label);
        }
        return normalise(tx.getCounterparty());
    }

    /** Lowercase, trim, and collapse internal whitespace so "NETFLIX  " == "Netflix". */
    static String normalise(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    // ─── Analysis (pure) ────────────────────────────────────────────────────────

    enum AmountClass { FIXED, VARIABLE, UNSTABLE }

    /** A confirmed-regular group, carrying everything needed to upsert a series. */
    record Candidate(RecurringCadence cadence, BigDecimal expectedAmount,
                     BigDecimal amountMin, BigDecimal amountMax, boolean isVariable,
                     BigDecimal confidence, int occurrences, boolean autoConfirm,
                     LocalDate lastSeen, LocalDate nextDue, String label) {}

    /**
     * Decide whether one identity group forms a regular series. Returns a {@link Candidate} when it
     * does (fixed or variable amount), or {@code null} (too few occurrences, irregular gaps, no known
     * cadence, or wildly unstable amounts).
     */
    static Candidate analyse(List<Transaction> txs) {
        if (txs.size() < MIN_OCCURRENCES) {
            return null;
        }

        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < txs.size(); i++) {
            gaps.add(ChronoUnit.DAYS.between(txs.get(i - 1).getDate(), txs.get(i).getDate()));
        }
        double medianGap = median(gaps.stream().map(Long::doubleValue).toList());
        if (medianGap <= 0 || !gapsAreRegular(gaps, medianGap)) {
            return null;
        }
        RecurringCadence cadence = RecurringCadence.fromMedianDays(medianGap);
        if (cadence == null) {
            return null;
        }

        List<BigDecimal> amounts = txs.stream().map(Transaction::getAmount).toList();
        List<BigDecimal> recent = amounts.subList(Math.max(0, amounts.size() - RECENT_WINDOW), amounts.size());
        BigDecimal expected = medianAmount(recent);
        AmountClass amountClass = classifyAmounts(recent, expected);
        if (amountClass == AmountClass.UNSTABLE) {
            return null;
        }
        boolean variable = amountClass == AmountClass.VARIABLE;

        BigDecimal min = amounts.stream().min(Comparator.naturalOrder()).orElse(expected);
        BigDecimal max = amounts.stream().max(Comparator.naturalOrder()).orElse(expected);
        BigDecimal confidence = confidence(gaps, medianGap, recent, expected, txs.size());
        boolean autoConfirm = !variable
            && txs.size() >= AUTO_CONFIRM_MIN_OCCURRENCES
            && confidence.compareTo(AUTO_CONFIRM_CONFIDENCE) >= 0;

        Transaction last = txs.get(txs.size() - 1);
        return new Candidate(cadence, expected, min, max, variable, confidence, txs.size(),
            autoConfirm, last.getDate(), cadence.next(last.getDate()), cleanLabel(last));
    }

    /** Every gap must sit within {@link #INTERVAL_TOLERANCE} of the median gap. */
    static boolean gapsAreRegular(List<Long> gaps, double medianGap) {
        double allowed = medianGap * INTERVAL_TOLERANCE;
        return gaps.stream().allMatch(g -> Math.abs(g - medianGap) <= allowed);
    }

    /**
     * Classify amount stability (by magnitude) relative to the median: within {@link #FIXED_TOLERANCE}
     * → fixed; within {@link #VARIABLE_TOLERANCE} → variable; beyond → unstable (not a series).
     */
    static AmountClass classifyAmounts(List<BigDecimal> amounts, BigDecimal median) {
        BigDecimal med = median.abs();
        if (med.signum() == 0) {
            return AmountClass.UNSTABLE;
        }
        double maxRel = 0;
        for (BigDecimal a : amounts) {
            double rel = a.abs().subtract(med).abs().doubleValue() / med.doubleValue();
            maxRel = Math.max(maxRel, rel);
        }
        if (maxRel <= FIXED_TOLERANCE) {
            return AmountClass.FIXED;
        }
        return maxRel <= VARIABLE_TOLERANCE ? AmountClass.VARIABLE : AmountClass.UNSTABLE;
    }

    /**
     * Confidence in [0,1] = 0.45·regularity + 0.35·amount-stability + 0.20·volume, where regularity
     * falls as gaps deviate from the median, stability falls as amounts drift, and volume rises with
     * the number of occurrences (saturating). Rounded to 3 decimals.
     */
    static BigDecimal confidence(List<Long> gaps, double medianGap,
                                 List<BigDecimal> recentAmounts, BigDecimal medianAmount, int occurrences) {
        double meanRelGap = gaps.stream()
            .mapToDouble(g -> Math.abs(g - medianGap) / medianGap)
            .average().orElse(0);
        double regularity = clamp01(1 - meanRelGap / INTERVAL_TOLERANCE);

        double med = medianAmount.abs().doubleValue();
        double maxRelAmount = med == 0 ? 1 : recentAmounts.stream()
            .mapToDouble(a -> Math.abs(a.abs().doubleValue() - med) / med)
            .max().orElse(0);
        double stability = clamp01(1 - maxRelAmount / VARIABLE_TOLERANCE);

        double volume = 0.4 + 0.6 * clamp01((occurrences - MIN_OCCURRENCES) / 3.0);

        double score = 0.45 * regularity + 0.35 * stability + 0.20 * volume;
        return BigDecimal.valueOf(score).setScale(3, RoundingMode.HALF_UP);
    }

    /** Prefer the clean merchant label as the series identity; fall back to the raw counterparty. */
    static String cleanLabel(Transaction tx) {
        if (tx.getMerchantLabel() != null && !tx.getMerchantLabel().isBlank()) {
            return tx.getMerchantLabel().trim();
        }
        return tx.getCounterparty() != null ? tx.getCounterparty().trim() : "";
    }

    /** A relative step larger than {@link #PRICE_CHANGE_PCT} (and ≥ 1 cent) is a price change. */
    static boolean isPriceChange(BigDecimal previous, BigDecimal current) {
        if (previous == null || previous.signum() == 0) {
            return false;
        }
        BigDecimal diff = current.subtract(previous).abs();
        if (diff.compareTo(new BigDecimal("0.01")) < 0) {
            return false;
        }
        return diff.doubleValue() / previous.abs().doubleValue() > PRICE_CHANGE_PCT;
    }

    static BigDecimal medianAmount(List<BigDecimal> amounts) {
        List<BigDecimal> sorted = amounts.stream().sorted().toList();
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return sorted.get(mid - 1).add(sorted.get(mid)).divide(BigDecimal.valueOf(2));
    }

    static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        if (sorted.isEmpty()) {
            return 0;
        }
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1
            ? sorted.get(mid)
            : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    // ─── Upsert + back-link ───────────────────────────────────────────────────

    /**
     * Create or refresh the series for this identity, detect a price change, silently auto-confirm a
     * high-confidence one, and back-link the group's transactions. Returns true if a row was written.
     * IGNORED series are left untouched; a user's CONFIRMED decision keeps its status (fields are still
     * refreshed). Returns true on a successful upsert.
     */
    private boolean upsert(Long memberId, Candidate c, List<Transaction> group) {
        RecurringSeries series = seriesRepository
            .findByMemberIdAndLabelIgnoreCase(memberId, c.label())
            .orElse(null);

        if (series != null && series.getStatus() == RecurringStatus.IGNORED) {
            return false;
        }

        boolean isNew = series == null;
        if (isNew) {
            series = RecurringSeries.builder()
                .member(familyMemberRepository.getReferenceById(memberId))
                .label(c.label())
                .status(RecurringStatus.SUGGESTED)
                .build();
        } else if (isPriceChange(series.getExpectedAmount(), c.expectedAmount())) {
            // Step to a new price level: remember the old amount so the activity feed can alert on it.
            series.setPreviousAmount(series.getExpectedAmount());
            series.setPriceChangedAt(c.lastSeen());
        }

        Transaction latest = group.get(group.size() - 1);
        series.setCounterparty(latest.getCounterparty());
        series.setExpectedAmount(c.expectedAmount());
        series.setAmountMin(c.amountMin());
        series.setAmountMax(c.amountMax());
        series.setVariable(c.isVariable());
        series.setConfidence(c.confidence());
        series.setCadence(c.cadence());
        series.setLastSeenDate(c.lastSeen());
        series.setNextDueDate(c.nextDue());

        // Silent auto-confirm only ever promotes a still-suggested series — never a user decision.
        if (series.getStatus() == RecurringStatus.SUGGESTED && c.autoConfirm()) {
            series.setStatus(RecurringStatus.CONFIRMED);
            series.setAutoConfirmed(true);
        }

        RecurringSeries saved = seriesRepository.save(series);
        linkTransactions(saved, group);
        return true;
    }

    /** Stamp {@code recurringSeriesId} on every group transaction not already linked to this series. */
    private void linkTransactions(RecurringSeries series, List<Transaction> group) {
        Long seriesId = series.getId();
        if (seriesId == null) {
            return; // defensive: a persisted IDENTITY entity always has one
        }
        List<Transaction> toLink = group.stream()
            .filter(tx -> !seriesId.equals(tx.getRecurringSeriesId()))
            .toList();
        if (toLink.isEmpty()) {
            return;
        }
        toLink.forEach(tx -> tx.setRecurringSeriesId(seriesId));
        transactionRepository.saveAll(toLink);
    }
}
