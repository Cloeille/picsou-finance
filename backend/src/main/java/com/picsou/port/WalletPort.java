package com.picsou.port;

import com.picsou.model.Chain;

import java.math.BigDecimal;
import java.util.List;

public interface WalletPort {

    /**
     * The chain this adapter serves. {@code WalletSyncService} dispatches on it to pick the
     * adapter for a wallet, so exactly one adapter may claim each {@link Chain}.
     */
    Chain chain();

    /**
     * Rejects an address this chain can never resolve, before {@code addWallet} attempts
     * a sync with it. Turns a typo into an HTTP 400 naming the expected format, rather
     * than a 422 "try again later" for input that can never succeed, and saves a pointless
     * round-trip to the chain's RPC/explorer.
     *
     * <p>This is <em>not</em> what keeps a bad row out of the database — {@code
     * WalletSyncService} is {@code @Transactional}, so a failing sync already rolls the
     * insert back.
     *
     * <p>Throws {@link IllegalArgumentException} (surfaced as HTTP 400) when the format
     * is wrong. The default accepts anything — a chain whose format is not cheaply
     * checkable offline (Bitcoin's several encodings, Solana's base58) keeps deferring
     * to the RPC call, which is the current behaviour for those adapters.
     */
    default void validateAddress(String address) {
        // No offline format check for this chain -- fetchBalances is the gate.
    }

    /**
     * Returns one entry per asset held at this address. Always at least one
     * entry for the chain's native asset (SOL, ETH, BTC...) — even if zero —
     * plus one entry per non-zero token (SPL on Solana, ERC-20 on Ethereum…).
     */
    List<WalletBalance> fetchBalances(String address);

    record WalletBalance(String symbol, BigDecimal amount) {}
}
