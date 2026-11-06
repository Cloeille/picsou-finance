package com.picsou.dto;

import com.picsou.model.PeerLoanRepaymentKind;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PeerLoanRepaymentRequest(
    @NotNull LocalDate paidOn,
    @NotNull @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    @Digits(integer = 12, fraction = 2, message = "Amount is too large")
    BigDecimal amount,
    /** Null defaults to a payment (interest first, then capital). */
    PeerLoanRepaymentKind kind,
    @Size(max = 255) String note,
    /**
     * Whether Picsou writes the matching cash movement on a manual linked account.
     * Null or true keeps the historical behaviour; false records a compensation (netting,
     * a payment already booked elsewhere) where the receivable falls without a bank row.
     */
    Boolean withCashLeg
) {
    /** Pre-cash-leg shape, kept so existing callers keep the historic behaviour. */
    public PeerLoanRepaymentRequest(
        LocalDate paidOn, BigDecimal amount, PeerLoanRepaymentKind kind, String note
    ) {
        this(paidOn, amount, kind, note, true);
    }
}
