package com.picsou.export.xlsx;

import com.picsou.model.Debt;
import com.picsou.service.LoanAmortizationService.LoanScheduleResponse;

import java.math.BigDecimal;

/**
 * One loan, with whatever its amortization can be worked out to.
 *
 * @param debt     the loan's terms, carrying its own account and the account it finances
 * @param schedule the computed amortization, or null when the terms are too thin to derive one
 */
record DebtExportData(Debt debt, LoanScheduleResponse schedule) {

    /** The account the loan itself is, whose balance is what is still owed. */
    String loanAccountName() {
        return debt.getAccount() == null ? null : debt.getAccount().getName();
    }

    /**
     * What remains owed, taken from the loan account's balance rather than the schedule.
     *
     * <p>The two can disagree — the schedule is derived from the terms, the balance is what the
     * member or their bank last stated — and the balance is the figure the accounts table in
     * this same workbook prints. One document, one number.
     */
    BigDecimal outstanding() {
        return debt.getAccount() == null ? null : debt.getAccount().getCurrentBalance();
    }

    String financedAccountName() {
        return debt.getLinkedAccount() == null ? null : debt.getLinkedAccount().getName();
    }
}
