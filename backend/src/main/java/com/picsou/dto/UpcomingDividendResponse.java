package com.picsou.dto;

import com.picsou.model.AccountType;
import com.picsou.model.DividendBasis;
import com.picsou.model.DividendCadence;
import com.picsou.model.DividendOrigin;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One projected payment. {@code amountEur} is already weighted by the member's share of the
 * account; {@code quantity} and {@code amountPerShareEur} are per share, so neither is
 * multiplied by the share again.
 *
 * <p>{@code exDate} is the projected ex-date for a holding and null for an SCPI.
 * {@code paymentDate} is that ex-date plus a lag learned from past DIVIDEND transactions,
 * or the declared SCPI date. Null means the lag is unknown — never a guessed default.
 * {@code paymentDateEstimated} is true exactly when {@code paymentDate} was derived.
 */
public record UpcomingDividendResponse(
    String id,
    DividendOrigin source,
    Long accountId,
    String accountName,
    String ticker,
    String name,
    LocalDate expectedDate,
    DividendCadence cadence,
    DividendBasis basis,
    BigDecimal quantity,
    BigDecimal amountPerShare,
    String currency,
    BigDecimal amountEur,
    boolean reinvested,
    AccountType accountType,
    boolean countsInBudget,
    LocalDate exDate,
    LocalDate paymentDate,
    boolean paymentDateEstimated,
    BigDecimal amountPerShareEur
) {}
