package com.picsou.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What every BPCE bank sidecar (Caisse d'Epargne, Banque Populaire) answers: the banks run on the
 * same platform, so their sidecars share one HTTP contract and one JSON shape. Only the first
 * login call differs per bank, so it lives on each bank's own port ({@link CaisseEpargnePort},
 * {@link BanquePopulairePort}).
 *
 * <p>The password never reaches Picsou: the bank's own port returns the keypad, the user clicks
 * his digits in his browser, {@link #submitKeypad} forwards only the key positions, the human
 * approves in Sécur'Pass, and {@link #completeAuth} hands back the cookie jar. None of them is
 * ever retried: a wrong password consumes a bank attempt and can lock the account. Failures are
 * {@code SyncException}s whose code is a {@link SidecarErrorCode} name.
 */
public interface BpcePort {

    /**
     * {@code POST /keypad}: clicks the positions the user chose (indexes into the pad returned by
     * the bank's initiate call) and waits for the Sécur'Pass page. Single use, no retry. The
     * positions are never logged.
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
     * float never appears. {@code kind} is {@code CURRENT_ACCOUNT}, {@code LIVRET_A},
     * {@code LIVRET_JEUNE}, {@code LEP}, {@code LDDS}, {@code PER}, {@code LOAN} or {@code CARD}; an unknown kind is rejected by
     * the sync service, not guessed here. A card's {@code balance} is its outstanding (sum of
     * operations with a future {@code dueDate}), zero or negative. A loan's {@code balance} is
     * its outstanding capital as the bank shows it, zero or negative; a PER's is zero or more.
     * {@code loan} is set on a {@code LOAN} only, and only when the sidecar could read the
     * loan's details; null otherwise.
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
        boolean snapshotComplete,
        LoanDetails loan,
        /** Null means legacy/unavailable detail, never an empty portfolio. PER only. */
        PerDetails per
    ) {
        public AccountData {
            transactions = transactions == null ? List.of() : List.copyOf(transactions);
        }

        /** Compatibility with balance-only snapshots and existing callers. */
        public AccountData(
            String externalId, String kind, String name, BigDecimal balance, String currency,
            String iban, boolean ibanAmbiguous, BigDecimal authorizedOverdraft, BigDecimal ceiling,
            BigDecimal remainingDepositCapacity, BigDecimal fillingRatio, String cardNature,
            String parentExternalId, LocalDate nextDueDate, List<Transaction> transactions,
            boolean snapshotComplete, LoanDetails loan
        ) {
            this(externalId, kind, name, balance, currency, iban, ibanAmbiguous, authorizedOverdraft,
                ceiling, remainingDepositCapacity, fillingRatio, cardNature, parentExternalId,
                nextDueDate, transactions, snapshotComplete, loan, null);
        }
    }

    /** Complete insurer valuation: euro-fund pocket plus owned UC, not the product catalogue. */
    record PerDetails(
        BigDecimal balance,
        BigDecimal euroFundBalance,
        LocalDate valuationDate,
        List<PerPosition> positions
    ) {
        public PerDetails {
            // Missing is not an explicit empty, complete portfolio.
            positions = positions == null ? null : List.copyOf(positions);
        }
    }

    /** Bank NAV is a fallback, not an acquisition price or a historical market quote. */
    record PerPosition(
        String isin,
        String name,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal value,
        LocalDate priceDate,
        Integer compartmentId
    ) {}

    /**
     * A personal loan's terms as the bank states them. {@code interestRate} is the nominal rate as
     * a fraction (0.03 for 3 %). {@code insuranceMonthly} is derived by the sidecar (installment
     * minus the annuity at the nominal rate) and null when that difference would be negative.
     * {@code linkedIban} is the account the installments are debited from.
     */
    record LoanDetails(
        BigDecimal borrowedAmount,
        BigDecimal interestRate,
        BigDecimal monthlyPayment,
        BigDecimal insuranceMonthly,
        LocalDate signatureDate,
        LocalDate firstDueDate,
        LocalDate lastDueDate,
        int durationMonths,
        String linkedIban
    ) {}

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
