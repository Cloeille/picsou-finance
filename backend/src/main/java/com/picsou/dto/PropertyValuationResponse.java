package com.picsou.dto;

import com.picsou.model.PropertyValuation;
import com.picsou.model.ValuationConfidence;
import com.picsou.model.ValuationMode;
import com.picsou.model.ValuationStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Outcome of a valuation request.
 *
 * <p>A non-OK {@link #status} is not an error response: the fields simply describe why no
 * figure could be produced, so the UI can say something specific instead of failing.
 */
public record PropertyValuationResponse(
    ValuationStatus status,
    ValuationMode mode,
    /** Whether this estimate was written to the account balance. False in MANUAL mode. */
    boolean appliedToBalance,
    BigDecimal estimatedValue,
    BigDecimal lowValue,
    BigDecimal highValue,
    BigDecimal pricePerSqm,
    Integer sampleSize,
    ValuationConfidence confidence,
    Short sourceYear,
    String provider,
    String scale,
    LocalDate valuedAt,
    /** Ratio applied to carry the source vintage forward to today; null when unavailable. */
    BigDecimal reindexRatio,
    List<AdjustmentDto> adjustments
) {
    /** One heuristic correction, so the UI can show exactly what moved the number. */
    public record AdjustmentDto(String code, BigDecimal factor, BigDecimal sqm, BigDecimal amount) {}

    public static PropertyValuationResponse failed(ValuationStatus status, ValuationMode mode) {
        return new PropertyValuationResponse(
            status, mode, false, null, null, null, null, null, null,
            null, null, null, null, null, List.of());
    }

    public static PropertyValuationResponse from(PropertyValuation v, ValuationMode mode,
                                                 boolean appliedToBalance,
                                                 BigDecimal reindexRatio,
                                                 List<AdjustmentDto> adjustments,
                                                 String scale) {
        return new PropertyValuationResponse(
            ValuationStatus.OK,
            mode,
            appliedToBalance,
            v.getEstimatedValue(),
            v.getLowValue(),
            v.getHighValue(),
            v.getPricePerSqm(),
            v.getSampleSize(),
            v.getConfidence(),
            v.getSourceYear(),
            v.getProvider(),
            scale,
            v.getValuedAt(),
            reindexRatio,
            adjustments
        );
    }
}
