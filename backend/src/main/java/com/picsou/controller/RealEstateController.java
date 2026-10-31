package com.picsou.controller;

import com.picsou.dto.PropertyValuationResponse;
import com.picsou.dto.RealEstateSummaryResponse;
import com.picsou.model.PropertyValuation;
import com.picsou.model.ValuationConfidence;
import com.picsou.service.PropertyValuationService;
import com.picsou.service.RealEstateSummaryService;
import com.picsou.service.UserContext;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Property wealth: the gross/net roll-up and per-property valuation history.
 *
 * <p>Triggering a valuation lives on {@code /api/accounts/{id}/valuation} instead, next to
 * the other per-account operations.
 */
@RestController
@RequestMapping("/api/real-estate")
public class RealEstateController {

    private final RealEstateSummaryService summaryService;
    private final PropertyValuationService valuationService;
    private final UserContext userContext;

    public RealEstateController(RealEstateSummaryService summaryService,
                                PropertyValuationService valuationService,
                                UserContext userContext) {
        this.summaryService = summaryService;
        this.valuationService = valuationService;
        this.userContext = userContext;
    }

    /** Gross value, outstanding mortgage debt and net equity, all weighted by the member's shares. */
    @GetMapping("/summary")
    public RealEstateSummaryResponse summary() {
        return summaryService.summarize(userContext.currentMemberId());
    }

    /** Past estimates for one property, newest first. Readable by co-owners. */
    @GetMapping("/{accountId}/valuations")
    public List<ValuationHistoryEntry> valuations(@PathVariable Long accountId) {
        return valuationService.history(accountId, userContext.currentMemberId()).stream()
            .map(ValuationHistoryEntry::from)
            .toList();
    }

    /**
     * A past estimate.
     *
     * <p>{@code methodDetail} is deliberately excluded: it is a large JSON blob only the
     * current valuation panel needs, and shipping it for every point of a multi-year chart
     * would dwarf the numbers themselves.
     */
    public record ValuationHistoryEntry(
        LocalDate valuedAt,
        BigDecimal estimatedValue,
        BigDecimal lowValue,
        BigDecimal highValue,
        BigDecimal pricePerSqm,
        String provider,
        ValuationConfidence confidence,
        Integer sampleSize,
        Short sourceYear
    ) {
        public static ValuationHistoryEntry from(PropertyValuation v) {
            return new ValuationHistoryEntry(
                v.getValuedAt(),
                v.getEstimatedValue(),
                v.getLowValue(),
                v.getHighValue(),
                v.getPricePerSqm(),
                v.getProvider(),
                v.getConfidence(),
                v.getSampleSize(),
                v.getSourceYear()
            );
        }
    }
}
