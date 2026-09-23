package com.picsou.dto;

import com.picsou.model.Account;
import com.picsou.model.AccountType;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(
    Long id,
    String name,
    AccountType type,
    String provider,
    String currency,
    BigDecimal currentBalance,
    BigDecimal currentBalanceEur,
    BigDecimal cashBalance,
    Instant lastSyncedAt,
    boolean isManual,
    String color,
    String ticker,
    String logoUrl,
    String logoKey,
    Instant createdAt,
    RealEstateMetadataResponse realEstate,
    DebtResponse debt,
    /**
     * The viewing member's percentage of this account, or null when it is wholly theirs.
     *
     * <p>Null rather than 100 on purpose: it lets the UI treat "co-owned" as a distinct
     * state to badge, without every ordinary account carrying a meaningless 100%.
     */
    BigDecimal sharePercent,
    /**
     * Whether the viewing member administers this account.
     *
     * <p>Distinct from holding a share: a co-owner reads the account and counts their part of
     * it, but only the owner may edit, revalue or delete it. The UI needs this to hide write
     * actions rather than letting the user discover the rule through a 403.
     */
    Boolean isOwner,
    /** Present only for a {@code SCPI} account. Null everywhere else. */
    ScpiPositionResponse scpi
) {
    public static AccountResponse from(Account a, BigDecimal balanceEur) {
        return new AccountResponse(
            a.getId(),
            a.getName(),
            a.getType(),
            a.getProvider(),
            a.getCurrency(),
            a.getCurrentBalance(),
            balanceEur,
            a.getCashBalance(),
            a.getLastSyncedAt(),
            a.isManual(),
            a.getColor(),
            a.getTicker(),
            a.getLogoUrl(),
            a.getLogoKey(),
            a.getCreatedAt(),
            null,
            null,
            null,
            null,
            null
        );
    }

    public AccountResponse withRealEstate(RealEstateMetadataResponse realEstate) {
        return new AccountResponse(id, name, type, provider, currency, currentBalance,
            currentBalanceEur, cashBalance, lastSyncedAt, isManual, color, ticker, logoUrl, logoKey,
            createdAt, realEstate, debt, sharePercent, isOwner, scpi);
    }

    public AccountResponse withDebt(DebtResponse debt) {
        return new AccountResponse(id, name, type, provider, currency, currentBalance,
            currentBalanceEur, cashBalance, lastSyncedAt, isManual, color, ticker, logoUrl, logoKey,
            createdAt, realEstate, debt, sharePercent, isOwner, scpi);
    }

    public AccountResponse withViewer(BigDecimal sharePercent, Boolean isOwner) {
        return new AccountResponse(id, name, type, provider, currency, currentBalance,
            currentBalanceEur, cashBalance, lastSyncedAt, isManual, color, ticker, logoUrl, logoKey,
            createdAt, realEstate, debt, sharePercent, isOwner, scpi);
    }

    public AccountResponse withScpi(ScpiPositionResponse scpi) {
        return new AccountResponse(id, name, type, provider, currency, currentBalance,
            currentBalanceEur, cashBalance, lastSyncedAt, isManual, color, ticker, logoUrl, logoKey,
            createdAt, realEstate, debt, sharePercent, isOwner, scpi);
    }
}
