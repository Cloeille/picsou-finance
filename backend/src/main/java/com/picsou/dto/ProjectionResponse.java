package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The investable portfolio projected forward under four return assumptions.
 *
 * <p>The base excludes property, loans and alternative assets: a house does not compound at 7.5%
 * a year, and including it would inflate every scenario by whatever the property is worth. The
 * figure is exposed so the screen can state what it is projecting from.
 *
 * @param baseValueEur      today's investable value, share-weighted
 * @param monthlyInflowEur  the sum of the recurring plans active this month, for context
 * @param scenarios         one series per assumption, all over the same horizon
 * @param allocation        where the mix is heading. The scenarios answer "how much"; this
 *                          answers "in what", which is the question the pyramid asks and the
 *                          curve could not reach. Covers every tier, property included, so the
 *                          shares are shares of the whole patrimoine
 */
public record ProjectionResponse(
    BigDecimal baseValueEur,
    BigDecimal monthlyInflowEur,
    int years,
    List<Scenario> scenarios,
    List<AllocationPoint> allocation
) {

    /**
     * @param key           stable identifier the client labels ({@code PESSIMISTIC},
     *                      {@code CAUTIOUS}, {@code REFERENCE}, {@code OPTIMISTIC})
     * @param annualPercent the <em>effective blended</em> rate this scenario works out to, given
     *                      where the money actually sits and where each plan sends it. Not a
     *                      headline assumption applied to everything: money in a passbook is not
     *                      compounded at an equity rate just because the curve is called
     *                      optimistic, so the number varies per member and has to be reported
     *                      rather than restated client-side
     * @param riskyDelta    the points added to risky assets to obtain this scenario, which is
     *                      what actually distinguishes the four curves
     */
    public record Scenario(
        String key,
        BigDecimal annualPercent,
        BigDecimal riskyDelta,
        List<Point> points
    ) {}

    /**
     * @param contributedEur capital in — the base plus everything paid in since. Carried beside
     *                       the value so the chart can separate contributions from returns, the
     *                       way {@code NetWorthChart} already separates total from invested
     */
    public record Point(LocalDate date, BigDecimal valueEur, BigDecimal contributedEur) {}

    /**
     * The mix at one point in time, under the reference scenario.
     *
     * <p>One scenario only, deliberately: four sets of shares would be four ways to read the same
     * qualitative answer — whether the plans converge on the targets or drift from them — and
     * that answer barely moves with the return assumption. What moves it is where the money goes.
     */
    public record AllocationPoint(LocalDate date, List<TierShare> tiers) {}

    /**
     * @param targetPercent the member's own target, carried alongside so the chart can draw the
     *                      line the projection is converging on or away from
     */
    public record TierShare(
        com.picsou.model.WealthTier tier,
        BigDecimal valueEur,
        BigDecimal percent,
        BigDecimal targetPercent
    ) {}
}
