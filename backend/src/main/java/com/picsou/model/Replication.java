package com.picsou.model;

/**
 * How a fund tracks its index: by holding the constituents, or through a swap.
 *
 * <p>Worth keeping beside the fee. A synthetic fund's published country and sector split is the
 * <em>index's</em>, not what the fund holds — which is the right number for diversification, and
 * the wrong one for counterparty risk.
 */
public enum Replication { PHYSICAL, SYNTHETIC }
