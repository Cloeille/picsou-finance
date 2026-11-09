package com.picsou.dto;

import java.math.BigDecimal;

/** A closed loan is not offered: it cannot receive a repayment. */
public record PeerLoanOpenResponse(
    Long accountId,
    String name,
    BigDecimal remaining
) {}
