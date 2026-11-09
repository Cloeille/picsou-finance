package com.picsou.export.xlsx;

import com.picsou.model.PeerLoanRepaymentKind;
import com.picsou.model.PeerLoanTerms;
import com.picsou.service.PeerLoanCalculator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One peer loan as it appears on its account sheet: the terms, the figures derived from the
 * dated ledger, and the journal rows with their interest-first split.
 *
 * <p>Gathered in {@link AccountsWorkbookService} and written by {@link AccountSheetWriter}, the
 * same split property and loan data use: the writer never recomputes domain maths.
 */
record PeerLoanExportData(
    PeerLoanTerms terms,
    PeerLoanCalculator.Computed computed,
    BigDecimal plannedInterest,
    List<RepaymentRow> repayments
) {

    /** One repayment with the interest-first allocation the calculator produced. */
    record RepaymentRow(
        LocalDate paidOn,
        BigDecimal amount,
        PeerLoanRepaymentKind kind,
        String note,
        BigDecimal interestPart,
        BigDecimal capitalPart
    ) {}
}
