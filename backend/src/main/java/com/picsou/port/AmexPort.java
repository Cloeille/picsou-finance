package com.picsou.port;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.picsou.model.AccountType;

import java.math.BigDecimal;
import java.util.List;

/**
 * The domain's typed contract onto American Express France. Everything that
 * knows about Amex's HTML or its one-time-code verification choreography
 * lives in the sidecar behind {@code AmexAdapter}; this interface does not
 * change when Amex redesigns.
 */
public interface AmexPort {

    /**
     * Signs in with the card member's username and password, asking Amex to
     * send its one-time code over the given channel.
     *
     * <p>Either completes outright — {@code mfaRequired == false} and
     * {@code sessionState} populated — or reports that Amex is waiting for
     * the code it just sent.
     *
     * @param method "sms" or "email"
     */
    InitiateResult initiateAuth(String userId, String password, String method);

    /** Submits the one-time code Amex sent, then returns the session. */
    String completeAuth(String processId, String otp);

    /** Reads every in-scope card account with its balance, statement and recent transactions. */
    List<AccountData> fetchAccounts(String sessionState);

    /** Explicit, provider-maximum transaction retrieval using the same authenticated session. */
    List<AccountData> fetchTransactionHistory(String sessionState);

    record InitiateResult(
        String processId,
        boolean mfaRequired,
        String mfaType,
        /** Populated only when {@code mfaRequired == false}. */
        String sessionState
    ) {}

    record DirectDebit(
        boolean enabled,
        String bankName,
        String iban
    ) {}

    /**
     * The sidecar's {@code TransactionPayload} carries no stable id and spells the label/amount
     * fields {@code description}/{@code amount} (see services/amex-auth/main.py); the aliases
     * below map onto Picsou's naming without requiring a sidecar change. {@code status} is the
     * feed the row came from, {@code "posted"} or {@code "pending"}.
     */
    record Transaction(
        String externalId,
        String date,
        @JsonAlias("description") String label,
        @JsonAlias("amount") BigDecimal amountEur,
        String status
    ) {
        public boolean pending() {
            return "pending".equalsIgnoreCase(status);
        }
    }

    record AccountData(
        String externalId,
        String name,
        AccountType type,
        BigDecimal balanceEur,
        BigDecimal statementBalance,
        @JsonAlias("amountDue") BigDecimal paymentDueAmount,
        String dueDate,
        BigDecimal minimumPayment,
        @JsonAlias("rewardPoints") Long rewardPoints,
        List<Transaction> transactions,
        /** False when the sidecar's pending feed failed: the pending rows are then unknown. */
        boolean pendingComplete,
        boolean snapshotComplete
    ) {}
}
