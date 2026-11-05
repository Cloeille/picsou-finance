package com.picsou.model;

/**
 * What a ledger row records.
 *
 * <p>{@code PRINCIPAL} and {@code INTEREST} are payments: both are allocated interest
 * first, then capital, so the UI can ask for a single amount. The label no longer changes
 * the arithmetic; it is kept so an explicit interest-only entry remains expressible
 * through the API.
 *
 * <p>{@code WRITE_OFF} is not a payment. It reduces the nominal capital because the
 * member decided the rest will not come back, and it carries no cash movement.
 */
public enum PeerLoanRepaymentKind {
    PRINCIPAL,
    INTEREST,
    WRITE_OFF
}
