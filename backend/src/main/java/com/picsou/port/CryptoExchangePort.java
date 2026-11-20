package com.picsou.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * A centralized crypto exchange Picsou can read balances from with the user's API credentials.
 *
 * <p>Implementations are Spring beans; {@code CryptoExchangeSyncService} picks one by matching
 * {@link #exchangeName()} against {@code ExchangeType.name()}, case-insensitively.
 */
public interface CryptoExchangePort {

    /** Must equal the matching {@code ExchangeType} constant's name — that string is the wiring. */
    String exchangeName();

    /**
     * Whether this exchange authenticates with a key <em>and</em> a secret.
     *
     * <p>Binance signs every request with an HMAC over the secret; Meria authenticates with a
     * single read-only key and has no secret at all. {@code CryptoExchangeSyncService.addExchange}
     * is the enforcement point — it rejects a missing secret for an exchange that needs one, and a
     * stray secret for one that doesn't, before any network call. When this returns {@code false},
     * the {@code apiSecret} field passed to {@link #fetchPositions} and {@link #testConnection} is
     * always {@code null}.
     *
     * <p>Defaulted to {@code true} so existing adapters need no edit.
     */
    default boolean requiresApiSecret() {
        return true;
    }

    /**
     * Whether this exchange requires an API passphrase. The sync service rejects missing or
     * unexpected passphrases before testing a connection. When this returns {@code false}, the
     * {@code apiPassphrase} field passed to the adapter is always {@code null}.
     */
    default boolean requiresApiPassphrase() {
        return false;
    }

    /**
     * Upper-case ASCII region codes the user must choose from, in display order.
     * An empty list means this exchange has no region choice.
     */
    default List<String> regions() {
        return List.of();
    }

    /**
     * An API key with the credentials required by the adapter. The secret and passphrase are
     * {@code null} exactly when their respective requirements are false. The region is
     * {@code null} exactly when {@link CryptoExchangePort#regions()} is empty.
     */
    record ExchangeCredentials(String apiKey, String apiSecret, String apiPassphrase, String region) {
        @Override
        public String toString() {
            return "ExchangeCredentials[redacted]";
        }
    }

    /**
     * Every non-zero position held on the exchange, one entry per (product, asset).
     *
     * <p>Positions rather than plain balances because the same asset can be held under several
     * products at once — spot, staked, lent — and a user reading their account wants to see which
     * is which. The caller sums them per asset for the account balance, and keeps the breakdown
     * for display. An exchange with a single product returns everything as {@link Product#SPOT}.
     *
     * <p>Must throw rather than return a partial or empty list when the exchange cannot be read:
     * the caller sums these into a single account balance and writes it to a daily
     * {@code BalanceSnapshot}, so a silently short result quietly dents the net-worth history.
     *
     * <p>Keep credentials out of exceptions and logs.
     */
    List<ExchangePosition> fetchPositions(ExchangeCredentials credentials);

    /**
     * Whether these credentials can read the account. A failed probe returns {@code false}
     * or throws a {@link com.picsou.exception.SyncException} with a safe message for the user.
     *
     * <p>Keep credentials out of logs.
     */
    boolean testConnection(ExchangeCredentials credentials);

    /** What an exchange lets an asset be held as. Persisted by name — do not renumber or rename. */
    enum Product { SPOT, STAKING, LENDING }

    /**
     * One asset held under one product.
     *
     * @param quantity  what the user holds, and the only field that feeds the account balance
     * @param principal the part of {@code quantity} that is capital rather than earned yield,
     *                  or {@code null} when the exchange does not distinguish the two
     * @param interest  yield already included in {@code quantity} — a decomposition of it, never
     *                  something to add on top (Meria's cumulative {@code reward} is exactly this,
     *                  and adding it shipped once as a double count). Null exactly when
     *                  {@code principal} is: the pair only means anything together, so a yield
     *                  the exchange reports outside {@code [0, quantity]} must be dropped whole
     *                  rather than published without the principal to read it against
     */
    record ExchangePosition(
        Product product,
        String symbol,
        BigDecimal quantity,
        BigDecimal principal,
        BigDecimal interest
    ) {
        /** A plain holding with no yield breakdown. */
        public static ExchangePosition spot(String symbol, BigDecimal quantity) {
            return new ExchangePosition(Product.SPOT, symbol, quantity, null, null);
        }
    }
}
