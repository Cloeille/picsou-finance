package com.picsou.port;

import com.picsou.model.AccountType;

import java.math.BigDecimal;
import java.util.List;

public interface TradeRepublicPort {

    /**
     * Step 1: Sends phone number + PIN to Trade Republic.
     * TR dispatches a 2FA code via SMS/app notification.
     * Credentials are NOT stored — they are used only for this call.
     *
     * @param phoneNumber international format (+33…) or local (06…, auto-converted)
     * @param pin         numeric PIN
     * @return processId to use in {@link #completeAuth(String, String)}
     */
    String initiateAuth(String phoneNumber, String pin);

    /**
     * Step 2: Exchanges the 2FA code for a session token + refresh token.
     *
     * @param processId returned by {@link #initiateAuth()}
     * @param tan       6-digit code from SMS/app
     * @return session tokens
     */
    TrTokens completeAuth(String processId, String tan);

    /**
     * Refreshes the session token using the stored refresh token (no 2FA needed).
     * The refresh token itself has a ~2h validity.
     *
     * @param refreshToken the stored tr_refresh value
     * @return new session tokens (refreshToken may be rotated)
     */
    TrTokens refreshSession(String refreshToken);

    record TrTokens(String sessionToken, String refreshToken) {}

    /**
     * Connects via WebSocket and fetches current balances for all sub-portfolios.
     *
     * NOTE: The TR WebSocket API is reverse-engineered and undocumented.
     * Raw responses are logged at INFO level to help diagnose format changes.
     */
    List<TrAccountData> fetchAccounts(String sessionToken);

    record TrPosition(
        String isin,
        BigDecimal quantity,
        BigDecimal averageBuyIn,
        BigDecimal currentPrice
    ) {}

    record TrAccountData(
        String externalId,   // e.g. "tr_pea", "tr_cto", "tr_cash"
        String name,
        AccountType type,
        BigDecimal balanceEur,
        List<TrPosition> positions,
        /**
         * Cash held inside the envelope and already counted in {@code balanceEur}: the PEA's
         * scoped cash. Null for accounts without a pocket (the CTO's cash is the separate
         * "tr_cash" account). Stored on {@code Account.cashBalance} so the valuation can put
         * it on both sides, like the Bourse Direct, BoursoBank and DEGIRO connectors do.
         */
        BigDecimal cashEur
    ) {
        public TrAccountData(String externalId, String name, AccountType type,
                             BigDecimal balanceEur, List<TrPosition> positions) {
            this(externalId, name, type, balanceEur, positions, null);
        }
    }
}
