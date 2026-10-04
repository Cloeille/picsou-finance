package com.picsou.dto;

import com.picsou.model.Account;
import com.picsou.model.AccountType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record AccountResponse(
    Long id, String name, AccountType type, String provider, String currency,
    BigDecimal currentBalance, BigDecimal currentBalanceEur, BigDecimal cashBalance,
    Instant lastSyncedAt, boolean isManual, String color, String ticker, String logoUrl,
    String logoKey, Instant createdAt, LocalDate openedAt, RealEstateMetadataResponse realEstate,
    DebtResponse debt, SavingsConfigDto savingsConfig, BigDecimal paymentDueAmount,
    LocalDate paymentDueDate, Long rewardPoints, Long parentAccountId, boolean hidden,
    BigDecimal sharePercent, Boolean isOwner, ScpiPositionResponse scpi
) {
    public AccountResponse(Long id, String name, AccountType type, String provider, String currency,
        BigDecimal currentBalance, BigDecimal currentBalanceEur, BigDecimal cashBalance, Instant lastSyncedAt,
        boolean isManual, String color, String ticker, String logoUrl, String logoKey, Instant createdAt,
        LocalDate openedAt, RealEstateMetadataResponse realEstate, DebtResponse debt, SavingsConfigDto savingsConfig,
        Long parentAccountId, boolean hidden, BigDecimal sharePercent, Boolean isOwner) {
        this(id, name, type, provider, currency, currentBalance, currentBalanceEur, cashBalance, lastSyncedAt,
            isManual, color, ticker, logoUrl, logoKey, createdAt, openedAt, realEstate, debt, savingsConfig,
            null, null, null, parentAccountId, hidden, sharePercent, isOwner, null);
    }

    public static AccountResponse from(Account a, BigDecimal balanceEur) {
        return new AccountResponse(a.getId(), a.getName(), a.getType(), a.getProvider(), a.getCurrency(),
            a.getCurrentBalance(), balanceEur, a.getCashBalance(), a.getLastSyncedAt(), a.isManual(),
            a.getColor(), a.getTicker(), a.getLogoUrl(), a.getLogoKey(), a.getCreatedAt(), a.getOpenedAt(),
            null, null, null, a.getPaymentDueAmount(), a.getPaymentDueDate(), a.getRewardPoints(),
            a.getParentAccountId(), a.isHidden(), null, null, null);
    }

    public AccountResponse withRealEstate(RealEstateMetadataResponse value) { return copy(value, debt, savingsConfig, paymentDueAmount, paymentDueDate, rewardPoints, scpi); }
    public AccountResponse withDebt(DebtResponse value) { return copy(realEstate, value, savingsConfig, paymentDueAmount, paymentDueDate, rewardPoints, scpi); }
    public AccountResponse withOpenedAt(LocalDate value) { return new AccountResponse(id, name, type, provider, currency, currentBalance, currentBalanceEur, cashBalance, lastSyncedAt, isManual, color, ticker, logoUrl, logoKey, createdAt, value, realEstate, debt, savingsConfig, paymentDueAmount, paymentDueDate, rewardPoints, parentAccountId, hidden, sharePercent, isOwner, scpi); }
    public AccountResponse withSavingsConfig(SavingsConfigDto value) { return copy(realEstate, debt, value, paymentDueAmount, paymentDueDate, rewardPoints, scpi); }
    public AccountResponse withViewer(BigDecimal value, Boolean owner) { return new AccountResponse(id, name, type, provider, currency, currentBalance, currentBalanceEur, cashBalance, lastSyncedAt, isManual, color, ticker, logoUrl, logoKey, createdAt, openedAt, realEstate, debt, savingsConfig, paymentDueAmount, paymentDueDate, rewardPoints, parentAccountId, hidden, value, owner, scpi); }
    public AccountResponse withScpi(ScpiPositionResponse value) { return copy(realEstate, debt, savingsConfig, paymentDueAmount, paymentDueDate, rewardPoints, value); }

    private AccountResponse copy(RealEstateMetadataResponse re, DebtResponse d, SavingsConfigDto s,
        BigDecimal pda, LocalDate pdd, Long points, ScpiPositionResponse sp) {
        return new AccountResponse(id, name, type, provider, currency, currentBalance, currentBalanceEur,
            cashBalance, lastSyncedAt, isManual, color, ticker, logoUrl, logoKey, createdAt, openedAt,
            re, d, s, pda, pdd, points, parentAccountId, hidden, sharePercent, isOwner, sp);
    }
}