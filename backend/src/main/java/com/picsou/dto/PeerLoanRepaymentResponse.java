package com.picsou.dto;

import com.picsou.model.PeerLoanRepayment;
import com.picsou.model.PeerLoanRepaymentKind;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PeerLoanRepaymentResponse(
    Long id,
    LocalDate paidOn,
    BigDecimal amount,
    PeerLoanRepaymentKind kind,
    String note
) {
    public static PeerLoanRepaymentResponse from(PeerLoanRepayment repayment) {
        return new PeerLoanRepaymentResponse(
            repayment.getId(),
            repayment.getPaidOn(),
            repayment.getAmount(),
            repayment.getKind(),
            repayment.getNote()
        );
    }
}
