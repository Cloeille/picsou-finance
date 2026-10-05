package com.picsou.port;

import com.picsou.model.PropertyKind;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Optional;

/**
 * Port for a housing price index, used to carry a figure forward in time.
 *
 * <p>Open transaction data always lags — the freshest DVF vintage is still months old — so a
 * raw median understates today's value in a rising market. Re-indexing closes that gap.
 *
 * <p>Implementations must degrade quietly: a missing index makes an estimate less precise,
 * which is very different from making it fail.
 */
public interface HousingPriceIndexPort {

    /**
     * Ratio to multiply a {@code from}-era price by to express it in {@code to} money.
     *
     * @return empty when either period is unavailable — callers then skip re-indexing
     */
    Optional<BigDecimal> reindexRatio(YearMonth from, YearMonth to, PropertyKind kind);
}
