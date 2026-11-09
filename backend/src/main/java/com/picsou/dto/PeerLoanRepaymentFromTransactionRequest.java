package com.picsou.dto;

import com.picsou.model.PeerLoanRepaymentKind;
import jakarta.validation.constraints.NotNull;

/**
 * Assigns an existing incoming transaction to a repayment of an open loan. The
 * transaction is linked, not copied: a synced account already carries the row.
 */
public record PeerLoanRepaymentFromTransactionRequest(
    @NotNull Long transactionId,
    /** Null defaults to a payment (interest first, then capital). */
    PeerLoanRepaymentKind kind
) {}
