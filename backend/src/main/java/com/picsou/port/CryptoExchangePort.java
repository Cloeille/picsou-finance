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
     * the {@code apiSecret} argument of {@link #fetchPositions} and {@link #testConnection} is
     * always {@code null}.
     *
     * <p>Defaulted to {@code true} so existing adapters need no edit.
     */
    default boolean requiresApiSecret() {
        return true;
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
     * @param apiSecret {@code null} when {@link #requiresApiSecret()} is {@code false}
     */
    List<ExchangePosition> fetchPositions(String apiKey, String apiSecret);

    /**
     * Whether these credentials can read the account. Never throws — a failure is {@code false}.
     *
     * @param apiSecret {@code null} when {@link #requiresApiSecret()} is {@code false}
     */
    boolean testConnection(String apiKey, String apiSecret);

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
