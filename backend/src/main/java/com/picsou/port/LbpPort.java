package com.picsou.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The domain's typed contract onto the La Banque Postale sidecar. Everything that knows
 * about the bank's web client and its payloads lives in the sidecar behind
 * {@code LbpAdapter}.
 *
 * <p>Login is three calls and the password never reaches Picsou: {@link #initiateAuth}
 * returns the bank's keypad, the user clicks his digits in his browser,
 * {@link #submitKeypad} forwards only the key positions, and {@link #completeAuth} waits
 * for the human to approve the sign-in from the bank's mobile app. None of them is ever
 * retried: a wrong password consumes a bank attempt and can lock the account.
 *
 * <p>The exact login page has not been observed live yet. The keypad shape is deliberately
 * the same as Caisse d'Epargne's so the backend and the UI do not change if the recon shows
 * a plain password field instead: only the sidecar's login layer moves.
 */
public interface LbpPort {

    /** {@code POST /initiate}: submits the identifier and answers with the ten-key pad. */
    InitiateResult initiateAuth(String customerId);

    /**
     * {@code POST /keypad}: clicks the positions the user chose (indexes into the pad
     * returned by {@link #initiateAuth}). Single use, no retry; the positions are never
     * logged.
     */
    void submitKeypad(String processId, List<Integer> positions);

    /**
     * {@code POST /complete}: blocks until the human approved the sign-in in the bank's
     * mobile app (the sidecar waits up to 150 s) and returns the opaque session state.
     * Single use, no retry.
     */
    String completeAuth(String processId);

    /**
     * Replays the stored session without reading any data ({@code /token-check}).
     */
    CheckResult checkSession(String sessionState);

    /**
     * Reads every importable account with its transactions ({@code /accounts}). The sidecar
     * is all-or-nothing: any parse error is an {@code UPSTREAM_FORMAT_CHANGED}, never a
     * partial list.
     */
    AccountsSnapshot fetchAccounts(String sessionState);

    record CheckResult(boolean ok, long expiresIn) {}

    /** The pending login: the bank's keypad and how long the keypad step stays valid. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record InitiateResult(String processId, Keypad keypad, int expiresInSeconds) {}

    /** Ten {@code data:image/png;base64,...} images in DOM order, laid out in {@code columns}. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Keypad(List<String> images, int columns) {}

    /** Products the sidecar saw but does not import (family code not supported). */
    record Unsupported(String externalId, String familyCode) {}

    /**
     * Money is carried as {@link BigDecimal} parsed from the sidecar's decimal strings; a
     * float never appears. {@code kind} is {@code CURRENT_ACCOUNT}, {@code LIVRET_A},
     * {@code LDDS}, {@code LEP}, {@code CSL}, {@code LIVRET_JEUNE}, {@code PEL},
     * {@code CEL} or {@code CARD}; an unknown kind is rejected by the sync service, not
     * guessed here. A card's {@code balance} is its outstanding (zero or negative), like the
     * Amex card. The IBAN is carried for diagnosis only and is never persisted.
     */
    record AccountData(
        String externalId,
        String kind,
        String name,
        BigDecimal balance,
        String currency,
        String iban,
        BigDecimal authorizedOverdraft,
        BigDecimal ceiling,
        BigDecimal remainingDepositCapacity,
        BigDecimal fillingRatio,
        /** {@code CREDIT}, {@code DEFERRED_DEBIT}, {@code IMMEDIATE_DEBIT} or null; cards only. */
        String cardNature,
        String parentExternalId,
        /** Cards only: next due date among the card's operations, or null. */
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
        /** The bank's type code as text; null when the bank sent none. */
        String typeCode
    ) {}

    record AccountsSnapshot(List<AccountData> accounts, List<Unsupported> unsupported) {
        public AccountsSnapshot {
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
            unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
        }
    }
}
