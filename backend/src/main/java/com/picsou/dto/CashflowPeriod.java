package com.picsou.dto;

/** Which span a cashflow query covers. Range (explicit from/to) is handled separately. */
public enum CashflowPeriod {
    /** The current payday budget cycle only. */
    CYCLE,
    /** Calendar year-to-date (Jan 1 → today), bucketed by pay cycle. */
    YTD
}
