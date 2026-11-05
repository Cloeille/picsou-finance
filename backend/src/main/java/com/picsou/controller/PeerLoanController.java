package com.picsou.controller;

import com.picsou.dto.PeerLoanDetailResponse;
import com.picsou.dto.PeerLoanRepaymentRequest;
import com.picsou.dto.PeerLoanRepaymentResponse;
import com.picsou.dto.PeerLoanSimulationRequest;
import com.picsou.dto.PeerLoanSimulationResponse;
import com.picsou.dto.PeerLoanTermsRequest;
import com.picsou.service.PeerLoanService;
import com.picsou.service.UserContext;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Peer loans (receivables between individuals).
 *
 * <p>Every account-scoped route resolves the account through {@code findByIdAndMemberId}
 * inside the service before touching a ledger row: the repayments are scoped through
 * their account, never through an id the caller supplied on its own.
 */
@RestController
@RequestMapping("/api")
public class PeerLoanController {

    private final PeerLoanService peerLoanService;
    private final UserContext userContext;

    public PeerLoanController(PeerLoanService peerLoanService, UserContext userContext) {
        this.peerLoanService = peerLoanService;
        this.userContext = userContext;
    }

    @GetMapping("/accounts/{id}/peer-loan")
    public PeerLoanDetailResponse get(@PathVariable Long id) {
        return peerLoanService.get(id, userContext.currentMemberId());
    }

    @PutMapping("/accounts/{id}/peer-loan")
    public PeerLoanDetailResponse saveTerms(
        @PathVariable Long id,
        @Valid @RequestBody PeerLoanTermsRequest request
    ) {
        return peerLoanService.saveTerms(id, userContext.currentMemberId(), request);
    }

    @PostMapping("/accounts/{id}/peer-loan/repayments")
    @ResponseStatus(HttpStatus.CREATED)
    public PeerLoanRepaymentResponse addRepayment(
        @PathVariable Long id,
        @Valid @RequestBody PeerLoanRepaymentRequest request
    ) {
        return peerLoanService.addRepayment(id, userContext.currentMemberId(), request);
    }

    @PutMapping("/accounts/{id}/peer-loan/repayments/{repaymentId}")
    public PeerLoanRepaymentResponse updateRepayment(
        @PathVariable Long id,
        @PathVariable Long repaymentId,
        @Valid @RequestBody PeerLoanRepaymentRequest request
    ) {
        return peerLoanService.updateRepayment(id, repaymentId, userContext.currentMemberId(), request);
    }

    @DeleteMapping("/accounts/{id}/peer-loan/repayments/{repaymentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRepayment(@PathVariable Long id, @PathVariable Long repaymentId) {
        peerLoanService.deleteRepayment(id, repaymentId, userContext.currentMemberId());
    }

    /**
     * The residual is written off on that date, which is when the loss enters the
     * net-worth history. Repeating the call on an already-closed loan is a no-op.
     */
    @PostMapping("/accounts/{id}/peer-loan/close")
    public PeerLoanDetailResponse close(
        @PathVariable Long id,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return peerLoanService.close(id, userContext.currentMemberId(), date);
    }

    /** Duration ↔ monthly payment preview. Never stored; the plan is saved via the terms. */
    @PostMapping("/peer-loans/simulate")
    public PeerLoanSimulationResponse simulate(@Valid @RequestBody PeerLoanSimulationRequest request) {
        return peerLoanService.simulate(request);
    }
}
