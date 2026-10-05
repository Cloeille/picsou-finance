package com.picsou.model;

/**
 * Whether the estimator is allowed to write the account balance.
 *
 * <p>{@link #MANUAL} is the escape hatch: the user's own figure wins and refreshes stop
 * overwriting it. Estimates are still computed and recorded so the comparison stays
 * visible — they just do not touch the balance.
 *
 * <p><b>One exception, by design.</b> {@code PropertyValuationService.withCostBasisFloor}
 * seeds the balance from the cost basis in {@code MANUAL} mode too, on the manual refresh and
 * on the monthly job alike. The lock protects a <em>figure the user gave</em>, and a null or
 * zero balance is not one — it is the absence of one, which renders as a 100% loss against the
 * purchase price and reads as "your property is worthless". The floor only ever lifts a zero;
 * any non-zero balance, however it got there, is left untouched.
 */
public enum ValuationMode {
    ESTIMATED,
    MANUAL
}
