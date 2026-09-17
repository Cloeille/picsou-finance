package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.dto.HoldingResponse;
import com.picsou.dto.RealEstateSummaryResponse;
import com.picsou.dto.WealthPyramidResponse;
import com.picsou.model.*;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.HoldingClassificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Places the member's assets on the investment pyramid and scores the result.
 *
 * <p>Aggregation copies {@code DashboardService.getDashboard} deliberately and literally:
 * readable accounts, then their shares, then one valuation per account, then
 * {@link AccountAccessResolver#weigh} applied <em>exactly once</em>. Weighting twice — in a helper
 * and again in a total — is the easy bug here, and it would make this page quietly disagree with
 * the dashboard about the same portfolio.
 */
@Service
@Transactional(readOnly = true)
public class WealthPyramidService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int SCALE = 8;

    /** Weight of the safety net in the global score: the base of the pyramid carries the most. */
    private static final BigDecimal SAFETY_WEIGHT = new BigDecimal("0.40");
    private static final BigDecimal ALLOCATION_WEIGHT = new BigDecimal("0.60");

    /** Coverage above which the cushion is idle money rather than prudence. */
    private static final BigDecimal COMFORTABLE_COVERAGE = BigDecimal.ONE;
    private static final BigDecimal MAX_SAFETY_PENALTY = new BigDecimal("40");
    /** Coverage at which the over-funding penalty saturates: three times the target. */
    /**
     * Share of total assets idle above the cushion target at which the over-funding penalty is
     * fully applied. Half your wealth doing nothing is the point at which nothing else you get
     * right can compensate.
     */
    private static final BigDecimal IDLE_PENALTY_SPAN = new BigDecimal("0.50");

    /** Share of the crypto sleeve the majors should hold. Below it, the penalty starts. */
    private static final BigDecimal TOP_TEN_FLOOR = new BigDecimal("0.80");
    private static final BigDecimal MAX_CRYPTO_PENALTY = BigDecimal.TEN;

    private static final BigDecimal MAX_LEVERAGE_BONUS = new BigDecimal("5");
    private static final BigDecimal LEVERAGE_RAMP_TOP = new BigDecimal("0.60");
    private static final BigDecimal LEVERAGE_DECAY_START = new BigDecimal("0.85");

    /**
     * Market-cap leaders as of 2026-08, reviewed by hand, stablecoins excluded — USDT and USDC
     * rank inside any top ten but are cash, not a bet, so counting them as "majors" would let a
     * portfolio parked in stablecoins score as a disciplined one.
     *
     * <p>Static on purpose. CoinGecko's {@code /coins/markets} would keep this current, but that
     * free tier is hostile enough that {@code CoinGeckoPriceProvider} carries a whole 429 cooldown
     * machinery for it — and a score that silently flips between two page loads is worse than one
     * that is stable and slightly stale.
     */
    /**
     * The tiers the four target percentages divide between. {@code SAFETY_NET} is deliberately
     * absent: it is measured in euros against an absolute target, and expressing the same money a
     * second time as a share of something else read as a contradiction on screen.
     */
    private static final List<WealthTier> INVESTMENT_TIERS = List.of(
        WealthTier.REAL_ESTATE, WealthTier.EQUITY, WealthTier.CRYPTO, WealthTier.ALTERNATIVE);

    private static final Set<String> TOP_TIER_COINS = Set.of(
        "BTC", "ETH", "BNB", "SOL", "XRP", "ADA", "DOGE", "TRX", "AVAX", "LINK"
    );

    private final AccountAccessResolver accessResolver;
    private final AccountService accountService;
    private final AccountHoldingRepository holdingRepository;
    private final HoldingClassificationRepository classificationRepository;
    private final AllocationTargetService allocationTargetService;
    private final RealEstateSummaryService realEstateSummaryService;
    private final CoinGeckoPriceProvider coinGecko;

    public WealthPyramidService(AccountAccessResolver accessResolver,
                                AccountService accountService,
                                AccountHoldingRepository holdingRepository,
                                HoldingClassificationRepository classificationRepository,
                                AllocationTargetService allocationTargetService,
                                RealEstateSummaryService realEstateSummaryService,
                                CoinGeckoPriceProvider coinGecko) {
        this.accessResolver = accessResolver;
        this.accountService = accountService;
        this.holdingRepository = holdingRepository;
        this.classificationRepository = classificationRepository;
        this.allocationTargetService = allocationTargetService;
        this.realEstateSummaryService = realEstateSummaryService;
        this.coinGecko = coinGecko;
    }

    public WealthPyramidResponse pyramid(Long memberId) {
        List<Account> accounts = accessResolver.readableAccounts(memberId);
        Map<Long, BigDecimal> shares = accessResolver.sharesFor(accounts, memberId);
        MemberAllocationProfile profile = allocationTargetService.profileFor(memberId);

        Map<String, WealthTier> overrides = new HashMap<>();
        for (HoldingClassification c : classificationRepository.findByMemberId(memberId)) {
            if (c.getWealthTier() != null) {
                overrides.put(c.getTicker().toUpperCase(Locale.ROOT), c.getWealthTier());
            }
        }

        Map<WealthTier, BigDecimal> byTier = new EnumMap<>(WealthTier.class);
        Map<WealthTier, List<WealthPyramidResponse.TierAccount>> accountsByTier =
            new EnumMap<>(WealthTier.class);
        for (WealthTier t : WealthTier.values()) {
            byTier.put(t, BigDecimal.ZERO);
            accountsByTier.put(t, new ArrayList<>());
        }

        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal cryptoTopTen = BigDecimal.ZERO;
        // Current-account money is tracked apart from the cushion. A current account is where
        // this month's money passes through — rent, groceries, the card — not what stands
        // between the member and a bad month, so counting it would report a buffer that is
        // largely already committed.
        BigDecimal dailyCash = BigDecimal.ZERO;
        // Only crypto we actually saw line by line. An exchange tracked as a single balance has
        // no per-coin detail, and judging its composition from silence would penalise a portfolio
        // for a breakdown we never received.
        BigDecimal cryptoDetailed = BigDecimal.ZERO;

        for (Account account : accounts) {
            // Loans are liabilities. Property already enters the pyramid net of the debt
            // financing it, via RealEstateSummaryService below, so counting a loan here would
            // subtract it twice.
            if (account.getType() == AccountType.LOAN) continue;

            BigDecimal share = shares.get(account.getId());
            WealthTier accountTier = WealthTier.of(account.getType());

            boolean currentAccount = account.getType() == AccountType.CHECKING;

            // The account's own valuation is the figure both branches reconcile against, and it
            // is what keeps this total equal to the dashboard's.
            BigDecimal accountTotal = AccountAccessResolver.weigh(
                accountService.valuation(account).liveEur(), share);

            if (!holdingRepository.existsForReadableAccount(account.getId(), memberId)) {
                totalAssets = totalAssets.add(accountTotal);
                if (currentAccount && accountTier == WealthTier.SAFETY_NET) {
                    dailyCash = dailyCash.add(accountTotal);
                } else {
                    byTier.merge(accountTier, accountTotal, BigDecimal::add);
                    accountsByTier.get(accountTier).add(new WealthPyramidResponse.TierAccount(
                        account.getId(), account.getName(), account.getColor(), accountTotal));
                }
                continue;
            }

            // A wrapper does not determine the asset: a gold ETC or a bitcoin ETP inside a
            // brokerage account belongs to another tier entirely. Split the account across tiers
            // line by line, then reconcile against the account's own valuation so the pyramid's
            // total still matches the dashboard's.
            List<HoldingResponse> lines = accountService.getHoldings(account.getId(), memberId);
            BigDecimal linesTotal = BigDecimal.ZERO;
            Map<WealthTier, BigDecimal> perTier = new EnumMap<>(WealthTier.class);

            for (HoldingResponse line : lines) {
                if (line.currentValueEur() == null) continue;
                BigDecimal value = AccountAccessResolver.weigh(line.currentValueEur(), share);
                WealthTier tier = tierFor(line.ticker(), accountTier, overrides);
                perTier.merge(tier, value, BigDecimal::add);
                linesTotal = linesTotal.add(value);
                if (tier == WealthTier.CRYPTO) {
                    cryptoDetailed = cryptoDetailed.add(value);
                    if (isTopTier(line.ticker())) {
                        cryptoTopTen = cryptoTopTen.add(value);
                    }
                }
            }

            // Cash inside the envelope, plus anything the lines could not account for, stays with
            // the account's own tier. Without this the euro fund of a life-insurance policy — and
            // any position the price providers dropped — would vanish from the pyramid while
            // still counting in the dashboard's net worth.
            BigDecimal residual = accountTotal.subtract(linesTotal);
            if (residual.signum() != 0) {
                perTier.merge(accountTier, residual, BigDecimal::add);
            }

            totalAssets = totalAssets.add(accountTotal);
            BigDecimal cashFromThisAccount = BigDecimal.ZERO;
            for (Map.Entry<WealthTier, BigDecimal> entry : perTier.entrySet()) {
                WealthTier tier = entry.getKey();
                BigDecimal value = entry.getValue();
                // An explicit per-ticker override wins even here: if the member says a line in a
                // current account is equity, it is equity. Only money still resolving to the
                // cushion is set aside as daily cash.
                if (currentAccount && tier == WealthTier.SAFETY_NET) {
                    cashFromThisAccount = cashFromThisAccount.add(value);
                    continue;
                }
                byTier.merge(tier, value, BigDecimal::add);
                accountsByTier.get(tier).add(new WealthPyramidResponse.TierAccount(
                    account.getId(), account.getName(), account.getColor(), value));
            }
            dailyCash = dailyCash.add(cashFromThisAccount);
        }

        // Property net of its mortgage, from the one service that already resolves both sides'
        // shares together. Re-deriving gross-minus-loans here would let this page and the
        // property card disagree about the same house.
        RealEstateSummaryResponse property = realEstateSummaryService.summarize(memberId);
        BigDecimal propertyDebt = property.outstandingDebt() == null
            ? BigDecimal.ZERO : property.outstandingDebt();
        if (propertyDebt.signum() > 0) {
            totalAssets = totalAssets.subtract(propertyDebt);
            byTier.merge(WealthTier.REAL_ESTATE, propertyDebt.negate(), BigDecimal::add);
        }

        return assemble(byTier, accountsByTier, totalAssets, dailyCash, cryptoTopTen, cryptoDetailed,
            profile, property.loanToValue());
    }

    /**
     * The tier a holding belongs to, cheapest test first and never a network call: this endpoint
     * renders a page, and {@code SecurityInsightService.classify()} is a Yahoo round trip per
     * ticker behind a cache that dies on restart.
     */
    private WealthTier tierFor(String ticker, WealthTier accountTier, Map<String, WealthTier> overrides) {
        if (ticker == null || ticker.isBlank()) return accountTier;
        WealthTier override = overrides.get(ticker.toUpperCase(Locale.ROOT));
        if (override != null) return override;
        // An in-memory map lookup, and the same first test SecurityInsightService already uses.
        if (coinGecko.supports(ticker)) return WealthTier.CRYPTO;
        return accountTier;
    }

    private boolean isTopTier(String ticker) {
        return ticker != null && TOP_TIER_COINS.contains(ticker.toUpperCase(Locale.ROOT));
    }

    private WealthPyramidResponse assemble(Map<WealthTier, BigDecimal> byTier,
                                           Map<WealthTier, List<WealthPyramidResponse.TierAccount>> accountsByTier,
                                           BigDecimal totalAssets,
                                           BigDecimal dailyCash,
                                           BigDecimal cryptoTopTen,
                                           BigDecimal cryptoDetailed,
                                           MemberAllocationProfile profile,
                                           BigDecimal loanToValuePercent) {
        BigDecimal safetyValue = byTier.get(WealthTier.SAFETY_NET);
        BigDecimal expenses = profile.getMonthlyEssentialExpenses();
        boolean known = expenses != null && expenses.signum() > 0;

        BigDecimal safetyTarget = known
            ? expenses.multiply(BigDecimal.valueOf(profile.getSafetyNetMonths())) : null;
        BigDecimal coverage = known && safetyTarget.signum() > 0
            ? safetyValue.divide(safetyTarget, SCALE, RoundingMode.HALF_UP) : null;
        // Zero when unknown: a target we cannot compute must not be assumed to be exceeded.
        BigDecimal excess = known ? safetyValue.subtract(safetyTarget).max(BigDecimal.ZERO) : BigDecimal.ZERO;

        // Everything cash-like sits outside the allocation: the cushion is measured in euros
        // against an absolute target, and current-account money is neither cushion nor
        // investment. What remains is what the four percentages divide.
        BigDecimal allocatable = totalAssets.subtract(safetyValue).subtract(dailyCash)
            .max(BigDecimal.ZERO);

        List<WealthPyramidResponse.TierLine> lines = new ArrayList<>();
        BigDecimal divergence = BigDecimal.ZERO;
        for (WealthTier tier : INVESTMENT_TIERS) {
            BigDecimal value = byTier.get(tier);
            BigDecimal actual = percentOf(value, allocatable);
            BigDecimal target = profile.targetFor(tier);
            divergence = divergence.add(actual.subtract(target).abs());
            lines.add(new WealthPyramidResponse.TierLine(
                tier, value, scale2(actual), scale2(target),
                // The target in euros, because a gap of "-6.4 points" is not something a member
                // can act on and "you are 21 000 € short here" is.
                scale2(allocatable.multiply(target).divide(HUNDRED, SCALE, RoundingMode.HALF_UP)),
                scale2(actual.subtract(target)),
                accountsByTier.get(tier)));
        }

        // Half the L1 distance: literally the fraction of allocatable wealth that would have to
        // move to hit every target. Bounded in [0,1] by construction, so the 0-100 mapping needs
        // no clamp, and it is the only divergence measure that states something a user can act on.
        BigDecimal misplaced = divergence.divide(new BigDecimal("2"), SCALE, RoundingMode.HALF_UP);
        // Null, not 100, when there is nothing to allocate. Someone holding everything in cash
        // has no allocation to be right or wrong about, and scoring them perfect was the single
        // biggest lie in this formula: combined with the cushion floor it put an all-cash
        // portfolio at 84 and a textbook one with a slightly short cushion at 88.
        //
        // Same principle already applied in the other direction: an unrated tier is unrated, not
        // zero. Inventing 100 is the same mistake as inventing 0.
        BigDecimal allocationScore = allocatable.signum() == 0
            ? null : HUNDRED.subtract(misplaced).max(BigDecimal.ZERO);

        Integer safetyScore = known ? safetyScore(coverage, excess, totalAssets) : null;
        // Measured against the crypto we have a breakdown for, not against the whole sleeve:
        // null means "no per-coin detail", which must not read as "no majors".
        BigDecimal cryptoShare = cryptoDetailed.signum() > 0
            ? cryptoTopTen.divide(cryptoDetailed, SCALE, RoundingMode.HALF_UP) : null;
        BigDecimal cryptoPenalty = cryptoPenalty(cryptoShare, percentOf(byTier.get(WealthTier.CRYPTO), allocatable));
        BigDecimal leverageBonus = leverageBonus(loanToValuePercent);

        // Either term may be missing, and the answer to "both missing" is not a number.
        //
        // Falling back to whichever term survived is what let a member with everything in a
        // current account and no stated expenses score 100/100 — the safety term was dropped for
        // want of a figure and the allocation term was invented. Declaring your expenses could
        // then only lower your score, which is a perverse thing for a form to reward.
        Integer global = combine(safetyScore, allocationScore, cryptoPenalty, leverageBonus);

        return new WealthPyramidResponse(
            scale2(totalAssets),
            scale2(allocatable),
            new WealthPyramidResponse.SafetyNet(
                scale2(safetyValue), scale2(dailyCash), scale2(safetyTarget),
                coverage == null ? null : coverage.setScale(4, RoundingMode.HALF_UP),
                scale2(excess), known, safetyScore),
            lines,
            new WealthPyramidResponse.Score(
                global,
                allocationScore == null
                    ? null : allocationScore.setScale(0, RoundingMode.HALF_UP).intValue(),
                scale2(misplaced),
                scale2(cryptoPenalty),
                scale2(leverageBonus),
                cryptoShare == null ? null : scale2(cryptoShare.multiply(HUNDRED)),
                scale2(loanToValuePercent)),
            alerts(accountsByTier, byTier, totalAssets, excess, coverage, known)
        );
    }

    /** Above this share of total assets, one asset is a concentration worth naming. */
    private static final BigDecimal SINGLE_ASSET_ALERT = new BigDecimal("40");

    /** Above this coverage the cushion is not prudence any more, it is idle money. */
    private static final BigDecimal OVERFUNDED_ALERT = new BigDecimal("1.20");

    /**
     * Observations that hold whatever the member's targets say.
     *
     * <p>The score cannot produce these: it measures distance to targets the member chose, so
     * setting the targets to match the portfolio scores full marks whatever the portfolio looks
     * like. These come from the portfolio alone, and cannot be silenced by editing a target.
     */
    private static List<WealthPyramidResponse.Alert> alerts(
        Map<WealthTier, List<WealthPyramidResponse.TierAccount>> accountsByTier,
        Map<WealthTier, BigDecimal> byTier,
        BigDecimal totalAssets,
        BigDecimal excess,
        BigDecimal coverage,
        boolean known) {

        List<WealthPyramidResponse.Alert> alerts = new ArrayList<>();
        if (totalAssets.signum() <= 0) return alerts;

        // One asset carrying most of a patrimoine is the risk a target-based score is blindest
        // to: a member who wants 75 % property scores perfectly on 75 % property, whether that is
        // one flat or a dozen.
        accountsByTier.values().stream()
            .flatMap(List::stream)
            .filter(a -> a.valueEur() != null)
            .max(Comparator.comparing(WealthPyramidResponse.TierAccount::valueEur))
            .ifPresent(biggest -> {
                BigDecimal share = percentOf(biggest.valueEur(), totalAssets);
                if (share.compareTo(SINGLE_ASSET_ALERT) > 0) {
                    alerts.add(new WealthPyramidResponse.Alert(
                        "SINGLE_ASSET_CONCENTRATION", biggest.name(),
                        scale2(biggest.valueEur()), scale2(share)));
                }
            });

        // A tier at exactly zero is not a small gap, it is an absence — and the allocation score
        // dilutes it into a couple of points.
        for (WealthTier tier : INVESTMENT_TIERS) {
            if (byTier.get(tier).signum() == 0) {
                alerts.add(new WealthPyramidResponse.Alert(
                    "EMPTY_TIER", tier.name(), BigDecimal.ZERO, BigDecimal.ZERO));
            }
        }

        // Money beyond the cushion is not a cushion. Two conditions, not one: comfortably over
        // target *and* material against the whole patrimoine. A cushion 7 % over its target holds
        // about a thousand euros too much on a quarter-million — naming that would be noise, and
        // an alert that fires on noise stops being read.
        if (known && coverage != null && coverage.compareTo(OVERFUNDED_ALERT) > 0
            && percentOf(excess, totalAssets).compareTo(BigDecimal.ONE) > 0) {
            alerts.add(new WealthPyramidResponse.Alert(
                "CUSHION_OVERFUNDED", null, scale2(excess),
                scale2(percentOf(excess, totalAssets))));
        }

        return alerts;
    }

    /**
     * Weighs the two sub-scores, with either or both possibly absent.
     *
     * <p>When one is missing the other carries the whole score — but only because there is
     * genuinely nothing else to weigh, not as a fallback that rewards leaving a field empty. When
     * both are missing there is no score, and the response says so rather than picking a number.
     */
    private static Integer combine(Integer safety, BigDecimal allocation,
                                   BigDecimal cryptoPenalty, BigDecimal leverageBonus) {
        BigDecimal base;
        if (safety != null && allocation != null) {
            base = SAFETY_WEIGHT.multiply(BigDecimal.valueOf(safety))
                .add(ALLOCATION_WEIGHT.multiply(allocation));
        } else if (safety != null) {
            base = BigDecimal.valueOf(safety);
        } else if (allocation != null) {
            base = allocation;
        } else {
            return null;
        }
        return base.subtract(cryptoPenalty).add(leverageBonus)
            .max(BigDecimal.ZERO).min(HUNDRED)
            .setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /**
     * Asymmetric on purpose: falling short is dangerous and scores in proportion, overshooting is
     * only a drag on yield and bottoms out at 60 rather than collapsing to zero.
     */
    private Integer safetyScore(BigDecimal coverage, BigDecimal excess, BigDecimal totalAssets) {
        if (coverage == null) return null;
        if (coverage.compareTo(COMFORTABLE_COVERAGE) <= 0) {
            return HUNDRED.multiply(coverage).setScale(0, RoundingMode.HALF_UP).intValue();
        }
        // Over-funding is scored by how much of the *whole* patrimoine sits idle beyond need, not
        // by the coverage ratio alone. Against the ratio, a cushion three times its target was
        // penalised as hard as one thirty times over — and someone with a small target and a
        // modest surplus was punished like someone whose entire wealth was doing nothing.
        //
        // The share of total assets is what actually costs a member: 1 101 EUR of surplus on a
        // 265 000 EUR patrimoine is a rounding error; 185 000 EUR of surplus on 200 000 EUR is the
        // whole strategy.
        if (totalAssets == null || totalAssets.signum() <= 0) return HUNDRED.intValue();
        BigDecimal idleShare = excess.divide(totalAssets, SCALE, RoundingMode.HALF_UP);
        BigDecimal over = idleShare.divide(IDLE_PENALTY_SPAN, SCALE, RoundingMode.HALF_UP)
            .min(BigDecimal.ONE);
        return HUNDRED.subtract(MAX_SAFETY_PENALTY.multiply(over))
            .setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /**
     * Scaled by how much crypto actually weighs, so a 2%-crypto portfolio holding nothing but
     * small caps loses 0.2 points — which is right, it is a rounding error in their wealth — while
     * a 30%-crypto one loses 3.
     */
    private BigDecimal cryptoPenalty(BigDecimal topTenShare, BigDecimal cryptoPercent) {
        if (topTenShare == null || topTenShare.compareTo(TOP_TEN_FLOOR) >= 0) return BigDecimal.ZERO;
        BigDecimal shortfall = TOP_TEN_FLOOR.subtract(topTenShare)
            .divide(TOP_TEN_FLOOR, SCALE, RoundingMode.HALF_UP);
        BigDecimal weight = cryptoPercent.divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        return MAX_CRYPTO_PENALTY.multiply(weight).multiply(shortfall);
    }

    /**
     * Rewards borrowing against property, but not without limit: the bonus rises to its maximum at
     * 60% LTV, holds to 85%, then decays back to zero by 100%. A fully mortgaged property is not a
     * better position than a well-leveraged one, and a monotonic bonus would claim it is.
     */
    private BigDecimal leverageBonus(BigDecimal loanToValuePercent) {
        if (loanToValuePercent == null || loanToValuePercent.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal ltv = loanToValuePercent.divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        BigDecimal ramp = ltv.divide(LEVERAGE_RAMP_TOP, SCALE, RoundingMode.HALF_UP).min(BigDecimal.ONE);
        BigDecimal decay = BigDecimal.ONE.subtract(
            ltv.subtract(LEVERAGE_DECAY_START).max(BigDecimal.ZERO)
                .divide(BigDecimal.ONE.subtract(LEVERAGE_DECAY_START), SCALE, RoundingMode.HALF_UP)
        ).max(BigDecimal.ZERO);
        return MAX_LEVERAGE_BONUS.multiply(ramp).multiply(decay);
    }

    private static BigDecimal percentOf(BigDecimal value, BigDecimal total) {
        if (total == null || total.signum() == 0) return BigDecimal.ZERO;
        return value.divide(total, SCALE, RoundingMode.HALF_UP).multiply(HUNDRED);
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
