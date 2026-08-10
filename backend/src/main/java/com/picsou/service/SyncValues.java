package com.picsou.service;

import com.picsou.exception.SyncException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Value helpers shared by the browser-sidecar sync services (Amundi, Bourse
 * Direct): string normalization, cost-basis arithmetic over possibly-unknown
 * figures, and the reconciliation tolerance that decides whether an upstream
 * total agrees with its own lines.
 *
 * <p>The tolerance lives here on purpose -- both providers must accept or reject
 * the same rounding drift, otherwise the same 5-cent discrepancy would erase a
 * portfolio on one integration and not the other.
 */
public final class SyncValues {

    /** Rounding drift always tolerated, whatever the amount. */
    private static final BigDecimal ABSOLUTE_RECONCILIATION_TOLERANCE = new BigDecimal("0.05");
    /** Additional tolerance proportional to the expected amount (0.1%). */
    private static final BigDecimal RELATIVE_RECONCILIATION_TOLERANCE = new BigDecimal("0.001");
    /** Scale of derived per-unit figures, matching {@code HoldingComputeService}. */
    private static final int UNIT_PRICE_SCALE = 8;

    private SyncValues() {}

    /** Trimmed value, or null when blank -- upstream absence and "  " mean the same thing. */
    public static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** {@link #clean(String)}, falling back to {@code fallback} and truncated to {@code maxLength}. */
    public static String limit(String value, int maxLength, String fallback) {
        String cleaned = clean(value);
        if (cleaned == null) {
            cleaned = fallback;
        }
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    /**
     * Quantity-weighted mean of two per-unit figures. Null when either side is
     * unknown or the merged quantity is flat: a cost basis derived from half the
     * lots would be a wrong number rather than a missing one.
     */
    public static BigDecimal weightedAverage(
        BigDecimal left,
        BigDecimal leftQuantity,
        BigDecimal right,
        BigDecimal rightQuantity,
        BigDecimal totalQuantity
    ) {
        if (left == null || right == null || totalQuantity.signum() == 0) {
            return null;
        }
        return left.multiply(leftQuantity)
            .add(right.multiply(rightQuantity))
            .divide(totalQuantity, UNIT_PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /** Sum that stays unknown when either side is: a partial total is not a total. */
    public static BigDecimal sumComplete(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : left.add(right);
    }

    /**
     * Whether two amounts agree within the reconciliation tolerance -- the
     * absolute floor, or 0.1% of the expected amount for large portfolios.
     */
    public static boolean moneyClose(BigDecimal actual, BigDecimal expected) {
        BigDecimal tolerance = ABSOLUTE_RECONCILIATION_TOLERANCE.max(
            expected.abs().multiply(RELATIVE_RECONCILIATION_TOLERANCE)
        );
        return actual.subtract(expected).abs().compareTo(tolerance) <= 0;
    }

    /** Unwraps a {@code TransactionTemplate.execute} result that a callback always fills in. */
    public static <T> T requireTransactionResult(T value) {
        return Objects.requireNonNull(value, "Transaction callback returned no result");
    }

    /**
     * Maps a {@link SyncException}'s free-form code onto a provider error enum,
     * falling back when the sidecar reports something this backend does not know.
     */
    public static <E extends Enum<E>> E errorCode(SyncException exception, Class<E> type, E fallback) {
        if (exception.getCode() == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, exception.getCode());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
