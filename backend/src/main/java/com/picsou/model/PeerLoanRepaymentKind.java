package com.picsou.model;

/**
 * What a ledger row records.
 *
 * <p>{@code PRINCIPAL} and {@code INTEREST} are cash received and must be recorded
 * separately: the principal is an asset swap (a {@code TRANSFER}, excluded from the
 * budget) while the interest is real income (an {@code INCOME}). Folding them into one
 * row would make one of the two wrong in the cashflow.
 *
 * <p>{@code WRITE_OFF} is not a payment. It reduces the nominal capital because the
 * member decided the rest will not come back, and it carries no cash movement.
 */
public enum PeerLoanRepaymentKind {
    PRINCIPAL,
    INTEREST,
    WRITE_OFF
}
