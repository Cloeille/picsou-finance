package com.picsou.port;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row from Nasdaq's dividend table. Dates other than the ex-date may be absent
 * ({@code N/A} on the page). {@code amount} is per share in {@code currency}, not euros.
 */
public record NasdaqDividend(
    LocalDate exDate,
    LocalDate paymentDate,
    LocalDate recordDate,
    LocalDate declarationDate,
    BigDecimal amount,
    String currency
) {}
