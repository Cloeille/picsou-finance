package com.picsou.dto;

import com.picsou.model.WealthTier;

import java.math.BigDecimal;
import java.util.List;

/**
 * How the member's assets sit against the investment pyramid, and how well.
 *
 * <p>Built from assets only, never from net worth. That is already the dashboard's rule
 * (assets divide by total assets, liabilities by total liabilities) and it is the right one
 * here too: counting property net of its mortgage would penalise exactly the leverage the
 * pyramid wants maximised. Debt is surfaced instead as {@code loanToValue} beside the
 * property tier.
 *
 * @param totalAssetsEur  every readable account except loans, weighted by the member's shares
 * @param allocatableEur  the base the four target percentages apply to — total assets minus
 *                        everything cash-like (see {@link SafetyNet})
 * @param safetyNet       the base of the pyramid, measured against an absolute target rather
 *                        than a share
 * @param tiers           the allocation vector: the four investment tiers, whose percentages sum
 *                        to 100. The safety net is <em>not</em> among them — it is measured in
 *                        euros against an absolute target, and a second line expressing the same
 *                        money as a share of something else read as a contradiction
 */
public record WealthPyramidResponse(
    BigDecimal totalAssetsEur,
    BigDecimal allocatableEur,
    SafetyNet safetyNet,
    List<TierLine> tiers,
    Score score,
    List<Alert> alerts
) {

    /**
     * @param valueEur     savings passbooks only. A current account is where this month's money
     *                     passes through, not what stands between the member and a bad month, so
     *                     counting it would report a cushion that is largely already spent
     * @param dailyCashEur what sits on current accounts, reported so the money is visible
     *                     somewhere rather than silently dropped, but scored nowhere
     * @param targetEur    {@code monthlyEssentialExpenses × safetyNetMonths}; null when unknown
     * @param coverage     {@code valueEur / targetEur}; null when unknown
     * @param excessEur    {@code max(0, valueEur - targetEur)}; zero when unknown, because a
     *                     target we cannot compute must not be assumed to be exceeded
     * @param known        false when the member has never stated their monthly expenses. The tier
     *                     is then <em>unscored</em>, not scored zero — see {@link Score#global()}
     */
    public record SafetyNet(
        BigDecimal valueEur,
        BigDecimal dailyCashEur,
        BigDecimal targetEur,
        BigDecimal coverage,
        BigDecimal excessEur,
        boolean known,
        Integer score
    ) {}

    /**
     * @param valueEur      share-weighted value counted in this tier
     * @param actualPercent this tier's share of {@code allocatableEur}
     * @param targetEur     what the target percentage is worth today, {@code targetPercent} of
     *                      {@code allocatableEur}. A gap in points is hard to act on; the same gap
     *                      in euros is the amount to move
     * @param gapPercent    {@code actualPercent - targetPercent}, positive when over-weighted
     */
    public record TierLine(
        WealthTier tier,
        BigDecimal valueEur,
        BigDecimal actualPercent,
        BigDecimal targetPercent,
        BigDecimal targetEur,
        BigDecimal gapPercent,
        List<TierAccount> accounts
    ) {}

    public record TierAccount(Long accountId, String name, String color, BigDecimal valueEur) {}

    /**
     * @param global         0-100, or <strong>null</strong> when neither sub-score could be
     *                       computed: no stated expenses and nothing allocatable. Falling back to
     *                       whichever term survived is what let an all-cash member with an empty
     *                       expenses field score 100 — and made declaring their expenses the only
     *                       way to score worse
     * @param allocation     {@code 100 × (1 - ½Σ|actual - target|)}: 100 minus the fraction of
     *                       wealth sitting in the wrong tier. <strong>Null</strong> when nothing
     *                       is allocatable — having no allocation is not the same as a perfect one
     * @param misplacedPercent the same quantity in its plain form — the share of allocatable
     *                       wealth that would have to move to hit every target
     * @param cryptoPenalty  0-10, scaled by how much crypto actually weighs
     * @param leverageBonus  0-5, peaking between 60% and 85% LTV
     * @param cryptoTopTenShare null when the member holds no crypto
     * @param loanToValue    null when the member owns no property
     */
    public record Score(
        Integer global,
        Integer allocation,
        BigDecimal misplacedPercent,
        BigDecimal cryptoPenalty,
        BigDecimal leverageBonus,
        BigDecimal cryptoTopTenShare,
        BigDecimal loanToValue
    ) {}

    /**
     * A fact about the portfolio's shape that holds regardless of the member's own targets.
     *
     * <p>The score measures conformity to targets the member set themselves, so it cannot say
     * anything about whether those targets are wise: aligning them with the current portfolio
     * scores full marks forever. These are the observations that survive that — they are derived
     * from the portfolio alone and cannot be silenced by editing a target.
     *
     * <p>Deliberately observations rather than advice. Picsou states that 71 % of a patrimoine
     * sits in one asset; it does not say whether that is wrong for this member.
     *
     * @param code  what was observed, translated client-side
     * @param label the subject — an account name, a tier — when the observation is about one
     */
    public record Alert(String code, String label, BigDecimal valueEur, BigDecimal percent) {}
}
