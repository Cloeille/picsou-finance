package com.picsou.model;

/**
 * The period the member used when stating the loan's rate. The stored {@code annualRate}
 * is always normalized to a fraction per year; this only decides how it is displayed and
 * pre-filled, so a loan agreed at 0.5 %/month reopens as 0.5 %/month rather than 6 %/year.
 */
public enum PeerLoanRatePeriod {
    ANNUAL,
    MONTHLY
}
