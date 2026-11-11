package com.picsou.model;

/**
 * Origin of a {@link BalanceSnapshot} row.
 *
 * <p>A manual ledger account can carry month-end statements typed by hand alongside a
 * partial transaction history. History rebuilds preserve {@link #MANUAL} rows and only
 * recompute the rest, so a hand-entered statement survives adding a transaction.
 */
public enum SnapshotSource {
    /**
     * Typed by hand (month-end statements modal, typed balance on a manual ledger): ground
     * truth, never rebuilt. The mark applies to any account kind; preservation applies
     * wherever a rebuild runs.
     */
    MANUAL,
    /** Walked back from the ledger by a rebuild: freely deleted and recreated. */
    DERIVED
}
