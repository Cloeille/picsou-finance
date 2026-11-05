package com.picsou.mcp.tools;

import com.picsou.dto.PeerLoanDetailResponse;
import com.picsou.dto.PeerLoanRepaymentRequest;
import com.picsou.dto.PeerLoanRepaymentResponse;
import com.picsou.dto.PeerLoansOverviewResponse;
import com.picsou.mcp.RequiresScope;
import com.picsou.mcp.Scopes;
import com.picsou.model.PeerLoanRepaymentKind;
import com.picsou.service.PeerLoanService;
import com.picsou.service.UserContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/**
 * MCP tools over a member's peer loans. A peer loan is a {@code PEER_LOAN} account, so the
 * account scopes cover it: a key allowed to read accounts may read a loan, and one allowed to
 * write accounts may record a repayment. Both delegate to {@link PeerLoanService}, which already
 * enforces ownership, the account type, configured terms and open state.
 */
@Component
public class PeerLoanTools {

    private final PeerLoanService peerLoanService;
    private final UserContext userContext;
    private final Validator validator;

    public PeerLoanTools(PeerLoanService peerLoanService, UserContext userContext, Validator validator) {
        this.peerLoanService = peerLoanService;
        this.userContext = userContext;
        this.validator = validator;
    }

    @Tool(name = "get_peer_loan",
        description = "Get a peer loan (a PEER_LOAN account) of the authenticated member: terms, "
            + "remaining capital, accrued and received interest, planned-vs-actual forecast, and the "
            + "dated repayment ledger with each row's interest/capital split.")
    @RequiresScope(Scopes.ACCOUNTS_READ)
    public PeerLoanDetailResponse getPeerLoan(
        @ToolParam(description = "The peer-loan account id") Long accountId) {
        return peerLoanService.get(accountId, userContext.currentMemberId());
    }

    @Tool(name = "add_peer_loan_repayment",
        description = "Record a repayment received on a peer loan of the authenticated member. The "
            + "amount is allocated interest first, then capital. On a manual linked cash account the "
            + "matching deposit is written by the service; a synced account is left untouched. The "
            + "loan must be configured and open. Returns the ledger row; call get_peer_loan for the "
            + "updated summary.")
    @RequiresScope(Scopes.ACCOUNTS_WRITE)
    public PeerLoanRepaymentResponse addPeerLoanRepayment(
        @ToolParam(description = "The peer-loan account id") Long accountId,
        @ToolParam(description = "Payment date, ISO yyyy-MM-dd") LocalDate paidOn,
        @ToolParam(description = "Amount received, greater than zero") BigDecimal amount,
        @ToolParam(description = "PRINCIPAL (default) or INTEREST; both allocate interest first, then capital",
            required = false) PeerLoanRepaymentKind kind,
        @ToolParam(description = "Optional note, at most 255 characters", required = false) String note,
        @ToolParam(description = "Set false to record a compensation/netting with no bank movement "
            + "(the receivable falls, the cash account is untouched); defaults to true",
            required = false) Boolean withCashLeg) {
        // A write-off through this tool would zero the capital without stamping the closure, the
        // half-closed state the REST path reserves for close(). Closing stays a deliberate act.
        if (kind == PeerLoanRepaymentKind.WRITE_OFF) {
            throw new IllegalArgumentException("WRITE_OFF is not available through this tool; close the loan instead");
        }
        PeerLoanRepaymentRequest request =
            validated(new PeerLoanRepaymentRequest(paidOn, amount, kind, note, withCashLeg));
        return peerLoanService.addRepayment(accountId, userContext.currentMemberId(), request);
    }

    @Tool(name = "get_peer_loans_overview",
        description = "Get every configured peer loan of the authenticated member as one view: total "
            + "outstanding, open and closed counts, accrued interest, the outstanding curve, and one "
            + "row per loan with remaining capital, repayment percentage, plan drift, last payment and "
            + "closure date. Closed loans are included, so an advisor can reason from the full history.")
    @RequiresScope(Scopes.ACCOUNTS_READ)
    public PeerLoansOverviewResponse getPeerLoansOverview(
        @ToolParam(description = "History window in months, 1 to 24; defaults to 12", required = false)
        Integer months) {
        return peerLoanService.overview(
            userContext.currentMemberId(), months == null ? 12 : months);
    }

    @Tool(name = "close_peer_loan",
        description = "Close a peer loan of the authenticated member: writes off the remaining capital "
            + "on the given date, if any, and marks the loan closed. Idempotent on an already-closed "
            + "loan. Returns the updated detail; call get_peer_loans_overview for the new totals.")
    @RequiresScope(Scopes.ACCOUNTS_WRITE)
    public PeerLoanDetailResponse closePeerLoan(
        @ToolParam(description = "The peer-loan account id") Long accountId,
        @ToolParam(description = "Closure date, ISO yyyy-MM-dd; defaults to today", required = false)
        LocalDate closedAt) {
        return peerLoanService.close(accountId, userContext.currentMemberId(), closedAt);
    }

    /** The bean validation the REST controller gets from {@code @Valid}; tool arguments bypass it. */
    private <T> T validated(T request) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return request;
    }
}
