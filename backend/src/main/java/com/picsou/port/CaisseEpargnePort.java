package com.picsou.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The domain's typed contract onto the Caisse d'Epargne sidecar. Everything that knows
 * about the bank's OAuth/SAML replay and its JSON APIs lives in the sidecar behind
 * {@code CaisseEpargneAdapter}.
 *
 * <p>Login is three calls and the password never reaches Picsou: {@link #initiateAuth} returns the
 * bank's keypad, the user clicks his digits in his browser, {@link #submitKeypad} forwards only the
 * key positions, the human approves in Sécur'Pass, and {@link #completeAuth} hands back the cookie
 * jar. None of them is ever retried: a wrong password consumes a bank attempt and can lock the
 * account.
 */
public interface CaisseEpargnePort {

    /**
     * {@code POST /initiate}: types the identifier on the bank's login page and answers with the
     * ten-key pad for the user to click. No password, no click on the pad. One attempt, no retry.
     */
    InitiateResult initiateAuth(String customerId);

    /**
     * {@code POST /keypad}: clicks the positions the user chose (indexes into the pad returned by
     * {@link #initiateAuth}) and waits for the Sécur'Pass page. Single use, no retry. The positions
     * are never logged.
     */
    void submitKeypad(String processId, List<Integer> positions);

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

    /**
     * The pending login: the bank's keypad (to be clicked by the user, never by Picsou) and for how
     * long the keypad step stays valid.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record InitiateResult(String processId, Keypad keypad, int expiresInSeconds) {}

    /** Ten {@code data:image/png;base64,...} images in DOM order, laid out in {@code columns} columns. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Keypad(java.util.List<String> images, int columns) {}

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
        /** Cards only: smallest {@code dueDate} after today among the card's operations, or null. */
        LocalDate nextDueDate,
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
        String label,
        /** The bank's {@code transactionTypeCode} as text; null when the bank sent none. */
        String typeCode
    ) {}

    record AccountsSnapshot(List<AccountData> accounts, List<Unsupported> unsupported) {
        public AccountsSnapshot {
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
            unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
        }
    }
}
