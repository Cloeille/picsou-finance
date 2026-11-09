package com.picsou.dto;

import com.picsou.model.PeerLoanRepayment;
import com.picsou.model.PeerLoanRepaymentKind;
import com.picsou.service.PeerLoanCalculator;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One ledger row, with the split the calculator applied: interest first, then capital.
 * The two parts are null on a response built outside a computed detail (e.g. the 201 that
 * follows an add); the detail refetch carries them.
 */
public record PeerLoanRepaymentResponse(
    Long id,
    LocalDate paidOn,
    BigDecimal amount,
    PeerLoanRepaymentKind kind,
    String note,
    BigDecimal interestPart,
    BigDecimal capitalPart,
    /** True when this row has a cash movement in a linked account (written or linked). */
    boolean hasCashLeg,
    /**
     * True when this service wrote that cash movement and deletes it with the row; false when
     * the row only links a transaction that stays where it is (and deleting only unlinks it).
     */
    boolean cashLegOwned
) {
    public static PeerLoanRepaymentResponse from(PeerLoanRepayment repayment) {
        return from(repayment, null);
    }

    public static PeerLoanRepaymentResponse from(
        PeerLoanRepayment repayment, PeerLoanCalculator.PaymentSplit split
    ) {
        return new PeerLoanRepaymentResponse(
            repayment.getId(),
            repayment.getPaidOn(),
            repayment.getAmount(),
            repayment.getKind(),
            repayment.getNote(),
            split == null ? null : split.interest(),
            split == null ? null : split.capital(),
            repayment.getCashTransactionId() != null,
            repayment.isCashLegOwned()
        );
    }
}
