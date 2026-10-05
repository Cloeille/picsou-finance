package com.picsou.model;

public enum Chain {
    SOLANA,
    /**
     * The EVM family: a single 0x address is used identically across Ethereum,
     * BNB Chain, Polygon, Arbitrum, Optimism, Base and Avalanche C-Chain. One
     * wallet of this chain fans out over every enabled EVM network — see
     * {@code EvmWalletAdapter}. Replaced the former {@code ETHEREUM} value
     * (migration V54 converts existing rows).
     */
    EVM,
    BITCOIN
}
