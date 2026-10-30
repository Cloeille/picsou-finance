package com.picsou.dto;

import com.picsou.model.AccountType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A dividend that has landed on an account. {@code amount} is the credit as booked, not
 * weighted by the member's share. {@code id} is the id the forecast gave the same line
 * ({@code HOLDING:accountId:ticker:exDate}), so a client can tell "forecast, now received"
 * from "new". When no ex-date is known it falls back to {@code HOLDING:accountId:ticker:tx<id>};
 * a dividend entered without a ticker has a null {@code ticker} and {@code HOLDING:accountId::tx<id>}.
 * {@code exDate} and {@code amountPerShare} are null when the stored history has no matching event.
 */
public record ReceivedDividendResponse(
    String id,
    Long transactionId,
    Long accountId,
    String accountName,
    AccountType accountType,
    String ticker,
    String name,
    LocalDate receivedDate,
    BigDecimal amount,
    String currency,
    LocalDate exDate,
    BigDecimal amountPerShare,
    boolean countsInBudget
) {}
