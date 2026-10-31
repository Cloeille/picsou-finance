package com.picsou.dto;

import com.picsou.model.RateBasis;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Result of a savings-book interest projection.
 *
 * <p>All interest figures are <em>informational estimates only</em> — they are never
 * written back to account balances or balance snapshots.</p>
 *
 * <p>This record is the shared contract consumed by the REST API (Stream B) and the
 * frontend (Stream C).</p>
 *
 * @param estimatedInterestYtd     Accrued interest from Jan 1 of the projection year up to
 *                                  the {@code asOf} date, using the French quinzaine rule.
 * @param projectedInterestFullYear Full-year estimate: YTD interest plus remaining quinzaines
 *                                  extrapolated at the capital level as of {@code asOf}.
 * @param nextCapitalizationDate   Dec 31 of the projection year (French livrets capitalise annually).
 * @param annualRatePct            Effective NET annual rate expressed as a percentage
 *                                  (e.g. {@code 2.40} for 2.40 %).  For COMMERCIAL+GROSS,
 *                                  this is already the after-tax rate.
 * @param basis                    The {@link RateBasis} of the configured rate.
 * @param netOfTax                 {@code true} when the configured rate is NET (no further
 *                                  tax deduction needed); {@code false} when the rate is GROSS.
 */
public record SavingsInterestProjection(
    BigDecimal estimatedInterestYtd,
    BigDecimal projectedInterestFullYear,
    LocalDate nextCapitalizationDate,
    BigDecimal annualRatePct,
    RateBasis basis,
    boolean netOfTax
) {}
