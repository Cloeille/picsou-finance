package com.picsou.service;

import com.picsou.dto.ProjectionResponse;
import com.picsou.dto.WealthPyramidResponse;
import com.picsou.model.Goal;
import com.picsou.model.GoalType;
import com.picsou.model.WealthTier;
import com.picsou.repository.GoalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Projects the portfolio forward from the member's recurring investment plans.
 *
 * <p>Deliberately not part of {@code GoalService}: that class is about progress against targets,
 * is already 450+ lines, and has no business knowing what the portfolio is worth.
 *
 * <p>Two questions, answered separately. <em>How much</em> — the investable portfolio under four
 * scenarios. <em>In what</em> — where the mix is heading against the member's own targets, which
 * is the question the pyramid asks and a single total could never reach.
 *
 * <p>The starting split comes from {@link WealthPyramidService} rather than from account types.
 * That is the only way the two panels of one screen can agree about the same euro: the pyramid
 * splits an account line by line and honours manual overrides, so a gold ETC inside a brokerage
 * account is alternative in both places instead of alternative in one and equity in the other.
 */
@Service
@Transactional(readOnly = true)
public class ProjectionService {

    /**
     * What each tier is assumed to earn, before a scenario adjusts the risky ones.
     *
     * <p>A rate per tier rather than one rate for everything, because the money is not all in the
     * same place: compounding a passbook at 7.5 % because the curve is labelled "realistic"
     * overstates the projection by however much of the plan lands in cash.
     *
     * <p>Property and alternatives sit at zero, which is a statement rather than an omission —
     * Picsou does not forecast house prices. They still appear in the mix, because a patrimoine
     * that is 79 % property is 79 % property whether or not we pretend to know where it is going.
     */
    private static final Map<WealthTier, BigDecimal> TIER_RATES = Map.of(
        WealthTier.SAFETY_NET,  new BigDecimal("2.0"),
        WealthTier.EQUITY,      new BigDecimal("7.5"),
        // No defensible separate figure exists, so crypto tracks equity rather than inventing
        // one. The scenario spread is what expresses the uncertainty.
        WealthTier.CRYPTO,      new BigDecimal("7.5"),
        WealthTier.REAL_ESTATE, BigDecimal.ZERO,
        WealthTier.ALTERNATIVE, BigDecimal.ZERO
    );

    /** The tiers a scenario's spread applies to. A Livret A does not have a good year. */
    private static final Set<WealthTier> RISKY = EnumSet.of(WealthTier.EQUITY, WealthTier.CRYPTO);

    /**
     * The four curves, as deltas on risky assets rather than absolute rates.
     *
     * <p>They used to be absolute — 2 / 5 / 7.5 / 10 % applied to everything, cash included. The
     * label was then false for any member whose plans were not entirely in equities, and it was
     * the stated reason a per-plan rate could not be honoured: folding one in would have made a
     * line labelled "5 %" mean something else. Expressed as spreads, both work.
     */
    private static final List<Assumption> ASSUMPTIONS = List.of(
        new Assumption("PESSIMISTIC", new BigDecimal("-2.5")),
        new Assumption("CAUTIOUS",    new BigDecimal("-1.0")),
        new Assumption("REFERENCE",   BigDecimal.ZERO),
        new Assumption("OPTIMISTIC",  new BigDecimal("2.5"))
    );

    /**
     * What compounds in the headline curve. Property is excluded because it does not grow at an
     * equity rate and would inflate every scenario by whatever the house is worth; the allocation
     * projection includes it, because a mix that left it out would not be the member's mix.
     */
    private static final Set<WealthTier> INVESTABLE =
        EnumSet.of(WealthTier.EQUITY, WealthTier.CRYPTO, WealthTier.SAFETY_NET);

    private static final int MIN_YEARS = 1;
    private static final int MAX_YEARS = 40;
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    /** @param riskyDelta points added to the {@link #RISKY} tiers to obtain this scenario */
    private record Assumption(String key, BigDecimal riskyDelta) {}

    /** A plan reduced to what the projection needs: how much, where to, and at what rate. */
    private record Plan(BigDecimal monthly, WealthTier tier, BigDecimal annualPercent,
                        YearMonth start, YearMonth end) {

        boolean activeIn(YearMonth month) {
            return (start == null || !month.isBefore(start))
                && (end == null || !month.isAfter(end));
        }
    }

    /**
     * Money grouped by the rate it earns, not merely by the tier it sits in.
     *
     * <p>The tier alone is not enough. A member's Livret A plan at their own 1.7 % and the cushion
     * they already hold both sit in {@code SAFETY_NET}, and once the contribution was added to a
     * single per-tier total the two became indistinguishable — so the stated rate could not
     * survive its own arrival, and the money grew at the tier's rate instead. One pot per source
     * keeps them apart for as long as the projection runs.
     */
    private static final class Pot {
        final WealthTier tier;
        final BigDecimal annualPercent;
        BigDecimal amount;

        Pot(WealthTier tier, BigDecimal annualPercent, BigDecimal amount) {
            this.tier = tier;
            this.annualPercent = annualPercent;
            this.amount = amount;
        }
    }

    private final GoalRepository goalRepository;
    private final WealthPyramidService pyramidService;
    private final AccountAccessResolver accessResolver;

    public ProjectionService(GoalRepository goalRepository,
                             WealthPyramidService pyramidService,
                             AccountAccessResolver accessResolver) {
        this.goalRepository = goalRepository;
        this.pyramidService = pyramidService;
        this.accessResolver = accessResolver;
    }

    public ProjectionResponse project(Long memberId, int requestedYears) {
        int years = Math.max(MIN_YEARS, Math.min(MAX_YEARS, requestedYears));

        WealthPyramidResponse pyramid = pyramidService.pyramid(memberId);
        Map<WealthTier, BigDecimal> startingMix = mixFrom(pyramid);
        Map<WealthTier, BigDecimal> targets = targetsFrom(pyramid);

        List<Plan> plans = plansFor(memberId);
        YearMonth start = YearMonth.now();

        BigDecimal base = INVESTABLE.stream()
            .map(t -> startingMix.getOrDefault(t, BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ProjectionResponse.Scenario> scenarios = ASSUMPTIONS.stream()
            .map(a -> scenario(a, startingMix, plans, start, years))
            .toList();

        return new ProjectionResponse(
            base.setScale(2, RoundingMode.HALF_UP),
            contributionTotal(plans, start).setScale(2, RoundingMode.HALF_UP),
            years,
            scenarios,
            allocation(startingMix, targets, plans, start, years));
    }

    // --- the starting picture, taken from the pyramid ------------------------

    private static Map<WealthTier, BigDecimal> mixFrom(WealthPyramidResponse pyramid) {
        Map<WealthTier, BigDecimal> mix = new EnumMap<>(WealthTier.class);
        // The cushion is a tier here even though the pyramid keeps it out of the allocation
        // vector: a projection that dropped it would lose every euro of a Livret A plan.
        mix.put(WealthTier.SAFETY_NET, pyramid.safetyNet().valueEur());
        for (WealthPyramidResponse.TierLine line : pyramid.tiers()) {
            mix.put(line.tier(), line.valueEur());
        }
        // Current-account money is deliberately absent: the pyramid carves it out as this
        // month's spending, and compounding it for forty years would be worse than ignoring it.
        return mix;
    }

    private static Map<WealthTier, BigDecimal> targetsFrom(WealthPyramidResponse pyramid) {
        Map<WealthTier, BigDecimal> targets = new EnumMap<>(WealthTier.class);
        for (WealthPyramidResponse.TierLine line : pyramid.tiers()) {
            targets.put(line.tier(), line.targetPercent());
        }
        return targets;
    }

    /**
     * The member's plans, each resolved to the tier it actually feeds.
     *
     * <p>The account was recorded, validated as exactly one, and then never read — so a plan
     * funding a Livret A compounded at the same rate as one funding a PEA, and the constraint
     * bought nothing. Reading it is what lets the mix be projected at all.
     *
     * <p>Contributions are share-weighted like the base. A plan funding a half-owned joint
     * account used to add its whole amount on top of a base that counted half the account.
     */
    private List<Plan> plansFor(Long memberId) {
        Map<Long, BigDecimal> shares =
            accessResolver.sharesFor(accessResolver.readableAccounts(memberId), memberId);

        List<Plan> plans = new ArrayList<>();
        for (Goal goal : goalRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId)) {
            if (goal.getType() != GoalType.RECURRING_INVESTMENT) continue;
            if (goal.getMonthlyAmount() == null) continue;
            if (goal.getAccounts().isEmpty()) continue;

            var account = goal.getAccounts().getFirst();
            WealthTier tier = WealthTier.of(account.getType());
            BigDecimal monthly =
                AccountAccessResolver.weigh(goal.getMonthlyAmount(), shares.get(account.getId()));
            if (monthly.signum() <= 0) continue;

            // The member's own figure when they gave one. They typed 1.7 against a Livret A plan
            // and the projection compounded it at 7.5 % — the right information, discarded.
            BigDecimal rate = goal.getExpectedReturn() != null
                ? goal.getExpectedReturn()
                : TIER_RATES.getOrDefault(tier, BigDecimal.ZERO);

            plans.add(new Plan(monthly, tier, rate,
                goal.getStartDate() == null ? null : YearMonth.from(goal.getStartDate()),
                goal.getEndDate() == null ? null : YearMonth.from(goal.getEndDate())));
        }
        return plans;
    }

    // --- the headline curve --------------------------------------------------

    private ProjectionResponse.Scenario scenario(Assumption assumption,
                                                 Map<WealthTier, BigDecimal> startingMix,
                                                 List<Plan> plans,
                                                 YearMonth start,
                                                 int years) {
        List<Plan> funding = plans.stream().filter(p -> INVESTABLE.contains(p.tier())).toList();
        Map<Plan, Pot> planPots = potsFor(startingMix, funding, INVESTABLE);
        List<Pot> pots = new ArrayList<>(potsOf(startingMix, INVESTABLE));
        pots.addAll(planPots.values());

        BigDecimal contributed = total(pots);
        // What each plan actually pays in over this horizon, which is the weight its rate carries
        // in the reported blend — a plan that stops after two years should not colour a
        // thirty-year label as heavily as one that runs the whole way.
        Map<Plan, BigDecimal> paidIn = new LinkedHashMap<>();
        List<ProjectionResponse.Point> points = new ArrayList<>();
        points.add(point(start, total(pots), contributed));

        for (int i = 1; i <= years * 12; i++) {
            YearMonth month = start.plusMonths(i);
            grow(pots, assumption.riskyDelta());
            for (Plan plan : funding) {
                if (!plan.activeIn(month)) continue;
                Pot pot = planPots.get(plan);
                pot.amount = pot.amount.add(plan.monthly());
                contributed = contributed.add(plan.monthly());
                paidIn.merge(plan, plan.monthly(), BigDecimal::add);
            }
            // Monthly maths, yearly points: 480 points per line x 4 lines would be a large
            // payload for a chart that cannot render them distinctly anyway.
            if (i % 12 == 0) points.add(point(month, total(pots), contributed));
        }

        return new ProjectionResponse.Scenario(
            assumption.key(),
            effectiveRate(startingMix, paidIn, assumption.riskyDelta()),
            assumption.riskyDelta(),
            points);
    }

    /**
     * The blended rate this scenario actually works out to.
     *
     * <p>Reported rather than assumed, because it depends on where the member's money sits: the
     * same "optimistic" curve is 10 % for someone fully invested and barely 3 % for someone whose
     * plans mostly feed a passbook. A label that does not vary with that is a label that lies.
     */
    private static BigDecimal effectiveRate(Map<WealthTier, BigDecimal> mix,
                                            Map<Plan, BigDecimal> paidIn,
                                            BigDecimal riskyDelta) {
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (WealthTier tier : INVESTABLE) {
            BigDecimal amount = mix.getOrDefault(tier, BigDecimal.ZERO);
            if (amount.signum() <= 0) continue;
            weighted = weighted.add(
                amount.multiply(adjust(TIER_RATES.getOrDefault(tier, BigDecimal.ZERO), tier, riskyDelta)));
            total = total.add(amount);
        }
        // Weighted by capital in, not by where the money ends up: the horizon's closing balances
        // would give the fastest-growing pot the loudest voice in describing its own growth. This
        // also covers the member with nothing invested yet, who is described by where their plans
        // point rather than by a portfolio that does not exist.
        for (Map.Entry<Plan, BigDecimal> entry : paidIn.entrySet()) {
            Plan plan = entry.getKey();
            weighted = weighted.add(
                entry.getValue().multiply(adjust(plan.annualPercent(), plan.tier(), riskyDelta)));
            total = total.add(entry.getValue());
        }
        return total.signum() == 0
            ? BigDecimal.ZERO
            : weighted.divide(total, 2, RoundingMode.HALF_UP);
    }

    // --- the allocation trajectory -------------------------------------------

    /**
     * Where the mix lands, under the reference scenario, against the member's own targets.
     *
     * <p>This is the join the two panels never had. The pyramid knows the gap today; the curve
     * knew the total tomorrow; neither could say whether the plans close the gap or widen it.
     */
    private List<ProjectionResponse.AllocationPoint> allocation(Map<WealthTier, BigDecimal> startingMix,
                                                                Map<WealthTier, BigDecimal> targets,
                                                                List<Plan> plans,
                                                                YearMonth start,
                                                                int years) {
        Set<WealthTier> everything = EnumSet.allOf(WealthTier.class);
        Map<Plan, Pot> planPots = potsFor(startingMix, plans, everything);
        List<Pot> pots = new ArrayList<>(potsOf(startingMix, everything));
        pots.addAll(planPots.values());

        List<ProjectionResponse.AllocationPoint> points = new ArrayList<>();
        points.add(allocationPoint(start, byTier(pots), targets));

        for (int i = 1; i <= years * 12; i++) {
            YearMonth month = start.plusMonths(i);
            grow(pots, BigDecimal.ZERO);
            for (Plan plan : plans) {
                if (!plan.activeIn(month)) continue;
                Pot pot = planPots.get(plan);
                pot.amount = pot.amount.add(plan.monthly());
            }
            if (i % 12 == 0) points.add(allocationPoint(month, byTier(pots), targets));
        }
        return points;
    }

    /** One pot per tier, holding what the member already owns, growing at the tier's rate. */
    private static List<Pot> potsOf(Map<WealthTier, BigDecimal> startingMix, Set<WealthTier> tiers) {
        List<Pot> pots = new ArrayList<>();
        for (WealthTier tier : tiers) {
            pots.add(new Pot(tier, TIER_RATES.getOrDefault(tier, BigDecimal.ZERO),
                startingMix.getOrDefault(tier, BigDecimal.ZERO)));
        }
        return pots;
    }

    /** One empty pot per plan, carrying the rate that plan's money will earn. */
    private static Map<Plan, Pot> potsFor(Map<WealthTier, BigDecimal> startingMix,
                                          List<Plan> plans, Set<WealthTier> tiers) {
        Map<Plan, Pot> pots = new LinkedHashMap<>();
        for (Plan plan : plans) {
            if (!tiers.contains(plan.tier())) continue;
            pots.put(plan, new Pot(plan.tier(), plan.annualPercent(), BigDecimal.ZERO));
        }
        return pots;
    }

    private static Map<WealthTier, BigDecimal> byTier(List<Pot> pots) {
        Map<WealthTier, BigDecimal> totals = new EnumMap<>(WealthTier.class);
        for (WealthTier tier : WealthTier.values()) totals.put(tier, BigDecimal.ZERO);
        for (Pot pot : pots) totals.merge(pot.tier, pot.amount, BigDecimal::add);
        return totals;
    }

    private static BigDecimal total(List<Pot> pots) {
        return pots.stream().map(p -> p.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static ProjectionResponse.AllocationPoint allocationPoint(YearMonth month,
                                                                      Map<WealthTier, BigDecimal> value,
                                                                      Map<WealthTier, BigDecimal> targets) {
        BigDecimal total = sum(value);
        List<ProjectionResponse.TierShare> shares = new ArrayList<>();
        for (WealthTier tier : WealthTier.values()) {
            BigDecimal amount = value.getOrDefault(tier, BigDecimal.ZERO);
            BigDecimal percent = total.signum() == 0
                ? BigDecimal.ZERO
                : amount.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
            shares.add(new ProjectionResponse.TierShare(
                tier,
                amount.setScale(2, RoundingMode.HALF_UP),
                percent,
                targets.get(tier)));
        }
        return new ProjectionResponse.AllocationPoint(month.atEndOfMonth(), shares);
    }

    // --- maths ---------------------------------------------------------------

    /**
     * Compounds every pot by one month at <em>its own</em> rate.
     *
     * <p>A pot funded by a plan that stated a rate keeps it. The scenario spread still lands only
     * on risky tiers, which is what makes a stated 1.7 % on a passbook identical across all four
     * curves while a stated 8 % on a PEA still varies: the first is contractual, the second is an
     * expectation, and the tier already says which is which.
     */
    private static void grow(List<Pot> pots, BigDecimal riskyDelta) {
        for (Pot pot : pots) {
            if (pot.amount.signum() == 0) continue;
            BigDecimal rate = adjust(pot.annualPercent, pot.tier, riskyDelta);
            if (rate.signum() == 0) continue;
            pot.amount = pot.amount.multiply(BigDecimal.ONE.add(monthlyRate(rate)), MC);
        }
    }

    /** The spread lands on risky tiers only — a passbook does not have a good year. */
    private static BigDecimal adjust(BigDecimal rate, WealthTier tier, BigDecimal riskyDelta) {
        if (!RISKY.contains(tier)) return rate;
        return rate.add(riskyDelta).max(BigDecimal.ZERO);
    }

    /**
     * The <em>geometric</em> monthly rate, {@code (1 + r)^(1/12) − 1}, never {@code r / 12}.
     *
     * <p>Dividing by twelve compounds to more than the rate on the label: 10% split that way
     * reaches 10.47% over a year, which overstates the first year by 5% of the gain and far more
     * across twenty. {@code Math.pow} on the rate is the one place a {@code double} is acceptable
     * — it is a pure ratio, not money, and every amount below stays {@code BigDecimal}.
     */
    static BigDecimal monthlyRate(BigDecimal annualPercent) {
        double annual = annualPercent.doubleValue() / 100.0;
        return BigDecimal.valueOf(Math.pow(1 + annual, 1.0 / 12.0) - 1);
    }

    private static BigDecimal contributionTotal(List<Plan> plans, YearMonth month) {
        return plans.stream()
            .filter(p -> p.activeIn(month))
            .map(Plan::monthly)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sum(Map<WealthTier, BigDecimal> value) {
        return value.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static ProjectionResponse.Point point(YearMonth month, BigDecimal value, BigDecimal contributed) {
        LocalDate date = month.atEndOfMonth();
        return new ProjectionResponse.Point(
            date,
            value.setScale(2, RoundingMode.HALF_UP),
            contributed.setScale(2, RoundingMode.HALF_UP));
    }
}
