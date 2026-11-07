package com.picsou.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Turns an existing cash movement into the lending leg of a new peer loan. The
 * transaction is linked, not copied: the money already left the account.
 */
public record PeerLoanFromTransactionRequest(
    @NotNull Long transactionId,
    /** Defaults to the transaction's merchant label or description. */
    @Size(max = 100) String beneficiary
) {}
