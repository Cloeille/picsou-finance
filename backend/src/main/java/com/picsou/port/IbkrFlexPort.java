package com.picsou.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * Abstraction over Interactive Brokers' Flex Web Service.
 *
 * <p>The Flex Web Service is a two-step HTTP API: a {@code SendRequest} generates a
 * report instance from a pre-built Flex Query and returns a reference code, then
 * {@code GetStatement} retrieves the XML once generation completes. The concrete
 * adapter ({@code IbkrFlexClient}) hides the polling, XML parsing and error codes;
 * callers see only the parsed open positions.
 *
 * <p>Open Positions Flex Queries carry once-daily (end-of-day) data, which matches
 * Picsou's daily snapshot cadence.
 */
public interface IbkrFlexPort {

    /**
     * Runs the Open Positions Flex Query and returns the current positions grouped
     * by IBKR account. Blocks while IBKR generates the statement (bounded polling).
     *
     * @param token   Flex Web Service token (plaintext; the caller decrypts it)
     * @param queryId Flex Query id for the Open Positions query
     * @return one {@link IbkrAccountData} per IBKR account id found in the statement
     */
    List<IbkrAccountData> fetchOpenPositions(String token, String queryId);

    /**
     * A single open position as reported by IBKR. Amounts are in the security's
     * native {@code currency}; {@code fxRateToBase} converts that currency to the
     * account's IBKR base currency.
     *
     * @param accountId      IBKR account id (e.g. "U1234567")
     * @param isin           ISIN, may be null for instruments without one (some derivatives)
     * @param symbol         IBKR symbol (fallback ticker when there is no ISIN)
     * @param description    human-readable instrument name
     * @param currency       native quote currency (e.g. "USD", "EUR")
     * @param assetCategory  IBKR asset class ("STK", "ETF", "OPT", "FUT", "CASH", ...)
     * @param levelOfDetail  "SUMMARY" (aggregate) or "LOT" (per tax lot); used to de-dupe
     * @param position       signed quantity held
     * @param markPrice      current mark price per unit, native currency
     * @param costBasisPrice cost basis per unit, native currency (may be null)
     * @param fxRateToBase   native-currency → IBKR-base-currency rate (may be null)
     */
    record IbkrPosition(
        String accountId,
        String isin,
        String symbol,
        String description,
        String currency,
        String assetCategory,
        String levelOfDetail,
        BigDecimal position,
        BigDecimal markPrice,
        BigDecimal costBasisPrice,
        BigDecimal fxRateToBase
    ) {}

    /**
     * @param accountId    IBKR account id
     * @param positions    open positions for this account
     * @param baseCurrency the account's IBKR base currency (e.g. "EUR", "USD"), from the
     *                     optional {@code AccountInformation} section of the Flex Query;
     *                     null when that section is not enabled on the query, or absent
     *                     from the statement
     */
    record IbkrAccountData(
        String accountId,
        List<IbkrPosition> positions,
        String baseCurrency
    ) {
        /** Convenience constructor for callers/tests that predate the base-currency field. */
        public IbkrAccountData(String accountId, List<IbkrPosition> positions) {
            this(accountId, positions, null);
        }
    }
}
