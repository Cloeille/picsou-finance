package com.picsou.port;

import com.picsou.model.AccountType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Outbound contract for the isolated, read-only Fortuneo sidecar.
 *
 * <p>Implementations must never expose credentials or browser state in logs. A fetched account
 * collection represents one complete provider snapshot; callers reject the whole collection if
 * any record is incomplete or inconsistent.</p>
 */
public interface FortuneoPort {
    /** Starts browser authentication and returns either an MFA challenge or a completed session. */
    InitiateResult initiateAuth(String login, String password);

    /** Completes a pending MFA challenge and returns the serialized browser session state. */
    String completeAuth(String processId, String code);

    /** Fetches the complete account snapshot represented by the supplied encrypted-at-rest session. */
    List<AccountData> fetchAccounts(String sessionState);

    /**
     * Result of the first authentication step.
     *
     * @param processId pending challenge identifier, or {@code null} when MFA is not required
     * @param mfaRequired whether {@link #completeAuth(String, String)} must be called
     * @param mfaType provider MFA type, or {@code null} when authentication is complete
     * @param sessionState serialized session state when authentication is complete, otherwise {@code null}
     */
    record InitiateResult(String processId, boolean mfaRequired, String mfaType, String sessionState) {}

    /**
     * One Fortuneo holding valued by the provider.
     *
     * @param isin optional ISIN
     * @param symbol provider symbol used as a safe fallback
     * @param label display label
     * @param quantity held quantity
     * @param buyingPriceEur average acquisition price in EUR, when available
     * @param currentPrice native-currency quote, when available
     * @param quoteCurrency ISO currency of {@code currentPrice}, required when that price is present
     * @param currentValueEur authoritative provider valuation in EUR
     * @param pnlEur provider unrealized profit or loss in EUR, when available
     */
    record Position(String isin, String symbol, String label, BigDecimal quantity,
                    BigDecimal buyingPriceEur, BigDecimal currentPrice, String quoteCurrency,
                    BigDecimal currentValueEur, BigDecimal pnlEur) {}

    /**
     * One cash-ledger entry returned by Fortuneo.
     *
     * @param date booking date
     * @param label provider label
     * @param amount signed amount
     * @param category optional provider category
     */
    /**
     * One cash-ledger entry.
     *
     * @param date accounting date
     * @param label human-readable description
     * @param amount signed amount in EUR
     * @param category provider category, when Fortuneo classified the entry
     * @param externalId stable provider identifier, or {@code null} when this response shape
     *     carries none -- the sync then falls back to the rolling-window import
     * @param type provider's structured operation type, or {@code null} when absent
     * @param txType Picsou transaction type the sidecar could establish from the provider's
     *     own operation label, or {@code null} when the operation is not one it recognises
     * @param quantity traded quantity, for a securities-ledger row
     * @param unitPrice execution price, for a securities-ledger row
     * @param fees brokerage fees and levies charged on the operation
     * @param isin instrument the row concerns, taken from the provider's own label-to-ISIN
     *     table, or {@code null} when the provider does not name one
     */
    record Transaction(
        LocalDate date,
        String label,
        BigDecimal amount,
        String category,
        String externalId,
        String type,
        String txType,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal fees,
        String isin
    ) {}

    /**
     * One account in an all-or-nothing Fortuneo snapshot.
     *
     * @param externalId stable provider identifier
     * @param name display name
     * @param type supported Picsou account type
     * @param balanceEur authoritative total valuation in EUR
     * @param cashBalance cash component in EUR
     * @param positions complete open-position list
     * @param transactions recent cash-ledger entries
     * @param snapshotComplete explicit completeness assertion from the sidecar
     */
    record AccountData(String externalId, String name, AccountType type, BigDecimal balanceEur,
                       BigDecimal cashBalance, List<Position> positions, List<Transaction> transactions,
                       boolean snapshotComplete) {}
}
