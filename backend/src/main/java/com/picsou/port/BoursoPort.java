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
    SidecarInitiate initiateAuth(String customerId, String password);

    /**
     * Waits for the customer to approve the push, then returns the session.
     *
     * <p>Takes no code: the app push is the only second factor supported, and
     * an SMS/e-mail prompt is reported as {@link SidecarErrorCode#MFA_TYPE_UNSUPPORTED}
     * during {@link #initiateAuth} rather than half-driven here.
     */
    String completeAuth(String processId);

    /**
     * Reads every in-scope account: current accounts, livrets, the securities
     * accounts with their positions and the assurance-vie contracts with their
     * unit-linked supports (euro fund in {@code cashBalance}). Aggregated
     * third-party accounts, loans and children's accounts are filtered out by
     * the sidecar.
     */
    List<AccountData> fetchAccounts(String sessionState);

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
        boolean snapshotComplete,
        String iban,
        List<SidecarTransaction> transactions,
        /**
         * Assurance vie only: the rows the sidecar reconciled against the valuation,
         * euro-fund rows included. Those are summed into {@code cashBalance}, so
         * {@code positions} alone cannot tell how many rounded amounts there were.
         */
        Integer valuationLineCount
    ) {
        public AccountData(String externalId, String name, AccountType type, BigDecimal balanceEur,
                           BigDecimal cashBalance, List<Position> positions, boolean snapshotComplete) {
            this(externalId, name, type, balanceEur, cashBalance, positions, snapshotComplete, null, List.of(), null);
        }
    }
}
