package com.picsou.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record HoldingResponse(
    String ticker,
    String name,
    // The image to show beside the ticker, or null when the asset has no known logo (see
    // docs/features/holding-logos.md). The UI shows the ticker either way, so a null is a
    // normal answer, not a failure. A crypto mark is a CoinGecko URL; a share or fund mark is
    // always Picsou's own /api/instrument-logos endpoint.
    String logoUrl,
    // The same mark drawn for a dark background, when the source has a distinct one. Null
    // means logoUrl is used in both themes.
    String logoUrlDark,
    BigDecimal quantity,
    BigDecimal averageBuyIn,
    BigDecimal currentPrice,
    String quoteCurrency,
    BigDecimal currentValueEur,  // null if currentPrice unknown
    BigDecimal costBasisEur,
    BigDecimal pnlEur,
    BigDecimal pnlPercent,
    Instant priceUpdatedAt,      // when the price was last fetched (null if unknown)
    // The day currentPriceEur is for, and whether it is a recorded price rather than a live
    // quote. Both null/false when no price could be resolved at all. The client shows the
    // figure either way and marks a stale one, so a provider outage degrades the price's age
    // instead of blanking the line.
    LocalDate priceAsOf,
    boolean priceStale
) {}
