package com.picsou.port;

import com.picsou.model.AccountType;

import java.math.BigDecimal;
import java.util.List;

/**
 * The domain's typed contract onto BoursoBank. Everything that knows about
 * BoursoBank's HTML, its virtual keyboard or its app-push choreography lives in
 * the sidecar behind {@code BoursoAdapter}; this interface does not change when
 * BoursoBank redesigns.
 */
public interface BoursoPort {

    /**
     * Signs in with the customer number and the numeric password.
     *
     * <p>Either completes outright — {@code mfaRequired == false} and
     * {@code sessionState} populated — or reports that BoursoBank pushed a
     * validation to the customer's phone.
     */
    InitiateResult initiateAuth(String customerId, String password);

    /**
     * Waits for the customer to approve the push, then returns the session.
     *
     * <p>Takes no code: the app push is the only second factor supported, and
     * an SMS/e-mail prompt is reported as {@link BoursoErrorCode#MFA_TYPE_UNSUPPORTED}
     * during {@link #initiateAuth} rather than half-driven here.
     */
    String completeAuth(String processId);

    /**
     * Reads every in-scope account: current accounts, livrets and the securities
     * accounts with their positions. Aggregated third-party accounts and loans
     * are filtered out by the sidecar.
     */
    List<AccountData> fetchAccounts(String sessionState);

    record InitiateResult(
        String processId,
        boolean mfaRequired,
        String mfaType,
        /** Populated only when {@code mfaRequired == false}. */
        String sessionState
    ) {}

    /**
     * One open position. {@code currentPrice} is expressed in
     * {@code quoteCurrency}; {@code buyingPriceEur}, {@code currentValueEur} and
     * {@code pnlEur} are always EUR.
     */
    record Position(
        String isin,
        String symbol,
        String label,
        BigDecimal quantity,
        BigDecimal buyingPriceEur,
        BigDecimal currentPrice,
        String quoteCurrency,
        BigDecimal currentValueEur,
        BigDecimal pnlEur
    ) {}

    record AccountData(
        String externalId,
        String name,
        AccountType type,
        BigDecimal balanceEur,
        /** Non-null for securities accounts only; a livret has no cash leg. */
        BigDecimal cashBalance,
        List<Position> positions,
        boolean snapshotComplete
    ) {}
}
