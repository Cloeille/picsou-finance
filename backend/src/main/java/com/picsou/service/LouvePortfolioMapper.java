package com.picsou.service;

import com.picsou.adapter.LouveAdapter;
import com.picsou.adapter.LouvePortfolioCodes;
import com.picsou.exception.SyncException;
import com.picsou.model.DistributionFrequency;
import com.picsou.port.SidecarErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a Louve Invest snapshot into the fund lines {@link ScpiFundReconciler} writes, applying the
 * SCPI invariants of {@code docs/features/scpi.md} (see {@code docs/features/louve-invest.md}):
 *
 * <ul>
 *   <li>the lines must add up to the SCPI total Louve declares, or nothing is written
 *       ({@code PORTFOLIO_INCOMPLETE});</li>
 *   <li>the balance comes from the withdrawal price, never the subscription price. The fund sheet's
 *       {@code sellingPriceByShare} is preferred; it is cross-checked with the line's own
 *       {@code sellingPrice / nbShares}, and when the two disagree beyond a cent the lower one is
 *       used, so a disagreement can understate the share but never overstate it;</li>
 *   <li>a line held in anything but full ownership ({@code Pleine propriété}) is not valued: a
 *       nue-propriété or usufruct is worth less than the full-ownership price Louve quotes;</li>
 *   <li>the distribution per share is the sheet's {@code distributionRate} times its subscription
 *       price (the French "taux de distribution" is defined on the subscription price), with the
 *       cadence from {@code rentFrequency}; anything incomplete leaves the stored figures alone.</li>
 * </ul>
 *
 * <p>Logs carry fund codes only, never a figure.
 */
final class LouvePortfolioMapper {

    private static final Logger log = LoggerFactory.getLogger(LouvePortfolioMapper.class);

    /** Several lines each rounded to the cent; one euro absorbs that and nothing a missing line could hide. */
    static final BigDecimal ENVELOPE_TOLERANCE = new BigDecimal("1.00");
    /** A per-share price derived from a total rounded to the cent is off by less than this. */
    static final BigDecimal PRICE_TOLERANCE = new BigDecimal("0.01");
    private static final int PRICE_SCALE = 8;
    private static final String FULL_OWNERSHIP = "pleine propriete";

    private LouvePortfolioMapper() {}

    record Mapped(List<ScpiFundReconciler.SyncedFund> funds, List<ScpiFundReconciler.UnsupportedFund> unsupported) {}

    static Mapped map(LouveAdapter.Snapshot snapshot) {
        if (snapshot == null || snapshot.positions() == null) {
            throw error(SidecarErrorCode.PORTFOLIO_INCOMPLETE, "Louve Invest returned no portfolio");
        }
        for (LouveAdapter.Position position : snapshot.positions()) {
            if (!LouvePortfolioCodes.isFundCode(position.fundCode())) {
                throw error(SidecarErrorCode.INVALID_DATA, "Louve Invest returned a position without a usable fund code");
            }
            if (position.shareCount() == null || position.shareCount().signum() < 0) {
                throw error(SidecarErrorCode.INVALID_DATA, "Louve Invest returned a position without a valid share count");
            }
            if (position.sellingTotal() != null && position.sellingTotal().signum() < 0) {
                throw error(SidecarErrorCode.INVALID_DATA, "Louve Invest returned a negative position value");
            }
        }
        checkEnvelope(snapshot);

        Map<String, LouveAdapter.Position> full = new LinkedHashMap<>();
        Map<String, String> dismembered = new LinkedHashMap<>();
        for (LouveAdapter.Position position : snapshot.positions()) {
            if (isFullOwnership(position.ownership())) {
                if (full.put(position.fundCode(), position) != null) {
                    // Two full-ownership lines for one fund would write the share count twice.
                    throw error(SidecarErrorCode.INVALID_DATA, "Louve Invest returned a duplicate fund");
                }
            } else {
                dismembered.putIfAbsent(position.fundCode(), position.label());
            }
        }

        List<ScpiFundReconciler.SyncedFund> funds = new ArrayList<>();
        for (LouveAdapter.Position position : full.values()) {
            funds.add(syncedFund(position, snapshot.funds().get(position.fundCode())));
        }
        List<ScpiFundReconciler.UnsupportedFund> unsupported = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(full.keySet());
        for (Map.Entry<String, String> line : dismembered.entrySet()) {
            if (!seen.add(line.getKey())) {
                // The full-ownership line of the same fund is written; the dismembered one is not added to it.
                log.info("Louve Invest lists a fund in full and dismembered ownership, only the full line is synced (fund={})",
                    line.getKey());
                continue;
            }
            unsupported.add(new ScpiFundReconciler.UnsupportedFund(line.getKey(), line.getValue()));
        }
        return new Mapped(List.copyOf(funds), List.copyOf(unsupported));
    }

    private static void checkEnvelope(LouveAdapter.Snapshot snapshot) {
        if (snapshot.positions().isEmpty()) {
            if (snapshot.declaredSellingTotal() != null
                    && snapshot.declaredSellingTotal().abs().compareTo(ENVELOPE_TOLERANCE) > 0) {
                throw error(SidecarErrorCode.PORTFOLIO_INCOMPLETE, "Louve Invest declared SCPI value but listed no position");
            }
            return;
        }
        BigDecimal declared = snapshot.declaredSellingTotal();
        if (declared == null || declared.signum() < 0) {
            throw error(SidecarErrorCode.PORTFOLIO_INCOMPLETE, "Louve Invest returned no valid SCPI total");
        }
        BigDecimal computed = BigDecimal.ZERO;
        for (LouveAdapter.Position position : snapshot.positions()) {
            if (position.sellingTotal() == null) {
                throw error(SidecarErrorCode.PORTFOLIO_INCOMPLETE, "Louve Invest returned a position without its value");
            }
            computed = computed.add(position.sellingTotal());
        }
        if (computed.subtract(declared).abs().compareTo(ENVELOPE_TOLERANCE) > 0) {
            // No figure in the message: GlobalExceptionHandler and the sync logs print it.
            throw error(SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "The Louve Invest positions do not add up to the declared SCPI total");
        }
    }

    private static ScpiFundReconciler.SyncedFund syncedFund(LouveAdapter.Position position, LouveAdapter.Fund fund) {
        BigDecimal subscription = fund == null ? null : positiveOrNull(fund.subscriptionPrice());
        BigDecimal withdrawal = withdrawalPrice(position, fund);
        String label = position.label() != null ? position.label()
            : fund != null && fund.name() != null ? fund.name() : position.fundCode();
        DistributionFrequency frequency = fund == null ? null : frequency(fund.rentFrequency());
        BigDecimal annual = annualDistribution(position.fundCode(), fund, subscription, frequency);
        return new ScpiFundReconciler.SyncedFund(
            position.fundCode(),
            label,
            position.shareCount(),
            subscription,
            withdrawal,
            fund == null ? null : fund.managementCompany(),
            fund == null ? null : LouvePortfolioCodes.isinOrNull(fund.isinCode()),
            annual,
            annual == null ? null : frequency
        );
    }

    /**
     * The withdrawal price per share, or null ({@code PRICE_INCOMPLETE}). The sheet's published
     * price wins when the line agrees with it to the cent; otherwise the lower of the two.
     */
    static BigDecimal withdrawalPrice(LouveAdapter.Position position, LouveAdapter.Fund fund) {
        BigDecimal published = fund == null ? null : positiveOrNull(fund.withdrawalPrice());
        BigDecimal derived = position.shareCount().signum() > 0 && position.sellingTotal() != null
            ? position.sellingTotal().divide(position.shareCount(), PRICE_SCALE, RoundingMode.HALF_UP)
            : null;
        if (published == null) {
            return derived == null || derived.signum() == 0 ? null : derived;
        }
        if (derived == null || derived.subtract(published).abs().compareTo(PRICE_TOLERANCE) <= 0) {
            return published;
        }
        log.warn("Louve Invest fund sheet and position disagree on the withdrawal price, the lower one is used (fund={})",
            position.fundCode());
        return derived.min(published);
    }

    private static BigDecimal annualDistribution(
        String fundCode, LouveAdapter.Fund fund, BigDecimal subscription, DistributionFrequency frequency
    ) {
        if (fund == null || fund.distributionRate() == null || subscription == null || frequency == null) {
            return null;
        }
        BigDecimal rate = fund.distributionRate();
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            // A fraction is expected (0.065). Anything else is not guessed into a percentage.
            log.warn("Louve Invest returned a distribution rate outside [0, 1), left untouched (fund={})", fundCode);
            return null;
        }
        return rate.multiply(subscription).setScale(2, RoundingMode.HALF_UP);
    }

    static DistributionFrequency frequency(String rentFrequency) {
        if (rentFrequency == null) {
            return null;
        }
        return switch (rentFrequency.trim().toLowerCase(Locale.ROOT)) {
            case "monthly" -> DistributionFrequency.MONTHLY;
            case "quarterly" -> DistributionFrequency.QUARTERLY;
            default -> null;
        };
    }

    /** Exactly one ownership, and it is full ownership. Anything else, missing included, is not. */
    static boolean isFullOwnership(List<String> ownership) {
        return ownership != null && ownership.size() == 1 && FULL_OWNERSHIP.equals(normalize(ownership.get(0)));
    }

    private static String normalize(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ").trim();
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
    }

    private static SyncException error(SidecarErrorCode code, String message) {
        return new SyncException(message, null, code.name());
    }
}
