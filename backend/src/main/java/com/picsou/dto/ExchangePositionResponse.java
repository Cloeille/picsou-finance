package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One line of a crypto exchange account's per-product breakdown.
 *
 * @param principal    the capital part of {@code quantity}, null when the exchange doesn't split it
 * @param interest     yield <em>already included</em> in {@code quantity}, never an addition to it
 * @param averageBuyIn the asset's unit cost basis, taken from its {@code AccountHolding} — cost is
 *                     tracked per asset, not per product, so every line of the same asset shares
 *                     it and the per-line figures still add up to the holding's
 * @param costBasisEur {@code averageBuyIn × quantity}, i.e. this line's share of that cost
 * @param priceAsOf    the day {@code currentPriceEur} is for; null when no price was resolvable
 * @param priceStale   true when the price is the last one recorded rather than a live quote, so
 *                     the client can mark the figure instead of hiding it
 */
public record ExchangePositionResponse(
    String product,
    String ticker,
    // The coin's image, or null when CoinGecko has no mark for it. See HoldingResponse.logoUrl.
    String logoUrl,
    BigDecimal quantity,
    BigDecimal principal,
    BigDecimal interest,
    BigDecimal averageBuyIn,      // null when the asset has no recorded cost basis
    BigDecimal currentPriceEur,   // null when the asset has no CoinGecko mapping
    BigDecimal currentValueEur,   // null when the price is unknown
    BigDecimal costBasisEur,
    BigDecimal pnlEur,
    BigDecimal pnlPercent,
    LocalDate priceAsOf,
    boolean priceStale
) {}
