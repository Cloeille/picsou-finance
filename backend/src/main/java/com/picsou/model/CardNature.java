package com.picsou.model;

/**
 * How a bank card is debited. A property of the card, never an import filter: every active card is
 * imported, the nature only says whether its operations are settled later on the current account.
 */
public enum CardNature {
    IMMEDIATE_DEBIT,
    DEFERRED_DEBIT,
    CREDIT
}
