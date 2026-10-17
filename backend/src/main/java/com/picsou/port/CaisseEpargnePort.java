package com.picsou.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The domain's typed contract onto the Caisse d'Epargne sidecar. Everything that knows
 * about the bank's OAuth/SAML replay and its JSON APIs lives in the sidecar behind
 * {@code CaisseEpargneAdapter}.
 *
 * <p>Login is two calls and the password only ever travels through {@link #initiateAuth}:
 * the sidecar drives the real login page, the human approves in Sécur'Pass, and
 * {@link #completeAuth} hands back the cookie jar. Neither call is ever retried: a wrong
 * password consumes a bank attempt and can lock the account.
 */
public interface CaisseEpargnePort {

    /**
     * {@code POST /initiate}: types the identifier and password on the bank's keypad and answers
     * with the pending Sécur'Pass challenge. One attempt, no retry.
     */
    InitiateResult initiateAuth(String customerId, String password);

    /**
     * {@code POST /complete}: blocks until the human approved on the phone (the sidecar waits up
     * to 150 s) and returns the opaque session state. Single use, no retry.
     */
    String completeAuth(String processId);

    /** Replays the stored session to a bearer token without reading any data ({@code /token-check}). */
    CheckResult checkSession(String sessionState);

    /**
     * Reads every importable contract with its transactions ({@code /accounts}). The sidecar is
     * all-or-nothing: any parse error is an {@code UPSTREAM_FORMAT_CHANGED}, never a partial list.
     */
    AccountsSnapshot fetchAccounts(String sessionState);

    record CheckResult(boolean ok, long expiresIn) {}

    /** The pending login: what the user must do next and for how long the attempt stays valid. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record InitiateResult(String processId, String mfaType, int expiresInSeconds) {}

    /** Contracts the sidecar saw but does not import (family code not supported). */
    record Unsupported(String externalId, String familyCode) {}

    /**
     * Money is carried as {@link BigDecimal} parsed from the sidecar's decimal strings; a
     * float never appears. {@code kind} is {@code CURRENT_ACCOUNT}, {@code LIVRET_A} or
     * {@code CARD}; an unknown kind is rejected by the sync service, not guessed here.
     * A card's {@code balance} is its outstanding (sum of operations with a future
     * {@code dueDate}), zero or negative.
     */
    record AccountData(
        String externalId,
        String kind,
        String name,
        BigDecimal balance,
        String currency,
        String iban,
        boolean ibanAmbiguous,
        BigDecimal authorizedOverdraft,
        BigDecimal ceiling,
        BigDecimal remainingDepositCapacity,
        BigDecimal fillingRatio,
        /** {@code CREDIT}, {@code DEFERRED_DEBIT}, {@code IMMEDIATE_DEBIT} or null; cards only. */
        String cardNature,
        String parentExternalId,
        List<Transaction> transactions,
        boolean snapshotComplete
    ) {
        public AccountData {
            transactions = transactions == null ? List.of() : List.copyOf(transactions);
        }
    }

    /** {@code amount} is signed: negative is an outflow. */
    record Transaction(
        String externalId,
        LocalDate date,
        LocalDate dueDate,
        BigDecimal amount,
        String currency,
        String label
    ) {}

    record AccountsSnapshot(List<AccountData> accounts, List<Unsupported> unsupported) {
        public AccountsSnapshot {
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
            unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
        }
    }
}
