package com.picsou.service;

import java.util.Objects;

/**
 * Everything that tells one BPCE bank from another in the shared {@link BpceSyncEngine}: how its
 * accounts are labelled and identified, and how it is named in messages and logs.
 *
 * @param provider         stored on {@code account.provider} and used in user messages and logs
 * @param externalIdPrefix prefixed to every account and transaction id, so two banks never collide
 * @param lenderName       stored on a loan's {@code Debt.lenderName}
 * @param accountName      fallback name of an unnamed current account
 * @param cardName         fallback name of an unnamed card
 * @param loanName         fallback name of an unnamed loan
 * @param transactionLabel fallback label of a transaction without one
 */
public record BpceBank(
    String provider,
    String externalIdPrefix,
    String lenderName,
    String accountName,
    String cardName,
    String loanName,
    String transactionLabel
) {
    public BpceBank {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(externalIdPrefix, "externalIdPrefix");
        Objects.requireNonNull(lenderName, "lenderName");
        Objects.requireNonNull(accountName, "accountName");
        Objects.requireNonNull(cardName, "cardName");
        Objects.requireNonNull(loanName, "loanName");
        Objects.requireNonNull(transactionLabel, "transactionLabel");
    }

    /** A bank whose fallback names are its provider label followed by the product. */
    static BpceBank named(String provider, String externalIdPrefix) {
        return new BpceBank(provider, externalIdPrefix, provider,
            provider + " account", provider + " card", provider + " loan", provider + " transaction");
    }
}
