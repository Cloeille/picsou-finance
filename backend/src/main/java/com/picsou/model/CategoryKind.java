package com.picsou.model;

/**
 * Drives three behaviours from one concept:
 * INCOME counts as cashflow income; EXPENSE counts in envelopes and cashflow outflow;
 * TRANSFER (money moved between the user's own accounts) is excluded from both and
 * only feeds the allocation view.
 */
public enum CategoryKind {
    INCOME,
    EXPENSE,
    TRANSFER
}
