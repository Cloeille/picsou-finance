package com.picsou.model;

/**
 * Whether the account balance is the withdrawal value, or was left untouched because that
 * price was missing. The subscription price is never a silent substitute.
 */
public enum ScpiValuationStatus {
    OK,
    PRICE_INCOMPLETE
}
