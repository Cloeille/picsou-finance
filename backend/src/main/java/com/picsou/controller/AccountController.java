package com.picsou.controller;

import com.picsou.dto.AccountRequest;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.DebtRequest;
import com.picsou.dto.DebtResponse;
import com.picsou.dto.ExchangePositionResponse;
import com.picsou.dto.HoldingRequest;
import com.picsou.dto.HoldingResponse;
import com.picsou.dto.RealEstateMetadataRequest;
import com.picsou.dto.RealEstateMetadataResponse;
import com.picsou.dto.RealizedPnlResponse;
import com.picsou.dto.SnapshotRequest;
import com.picsou.dto.TransactionRequest;
import com.picsou.dto.TransactionResponse;
import com.picsou.model.BalanceSnapshot;
import com.picsou.dto.OwnershipRequest;
import com.picsou.dto.OwnershipResponse;
import com.picsou.dto.PropertyValuationResponse;
import com.picsou.dto.ScpiPositionRequest;
import com.picsou.dto.ScpiPositionResponse;
import com.picsou.service.AccountConnectionService;
import com.picsou.service.ScpiPositionService;
import com.picsou.service.AccountOwnershipService;
import com.picsou.service.AccountService;
import com.picsou.service.CryptoExchangeSyncService;
import com.picsou.service.LoanAmortizationService;
import com.picsou.service.ManualTransactionService;
import com.picsou.service.PropertyValuationService;
import com.picsou.service.RealizedPnlService;
import com.picsou.service.UserContext;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final UserContext userContext;
    private final ManualTransactionService manualTransactionService;
    private final RealizedPnlService realizedPnlService;
    private final CryptoExchangeSyncService cryptoExchangeSyncService;
    private final PropertyValuationService propertyValuationService;
    private final AccountOwnershipService ownershipService;
    private final AccountConnectionService accountConnectionService;
    private final ScpiPositionService scpiPositionService;

    public AccountController(AccountService accountService, UserContext userContext,
                            ManualTransactionService manualTransactionService,
                            RealizedPnlService realizedPnlService,
                            CryptoExchangeSyncService cryptoExchangeSyncService,
                            PropertyValuationService propertyValuationService,
                            AccountOwnershipService ownershipService,
                            AccountConnectionService accountConnectionService,
                            ScpiPositionService scpiPositionService) {
        this.accountConnectionService = accountConnectionService;
        this.accountService = accountService;
        this.userContext = userContext;
        this.manualTransactionService = manualTransactionService;
        this.realizedPnlService = realizedPnlService;
        this.cryptoExchangeSyncService = cryptoExchangeSyncService;
        this.propertyValuationService = propertyValuationService;
        this.ownershipService = ownershipService;
        this.scpiPositionService = scpiPositionService;
    }

    @GetMapping
    public List<AccountResponse> findAll() {
        return accountService.findAll(userContext.currentMemberId());
    }

    @GetMapping("/{id}")
    public AccountResponse findById(@PathVariable Long id) {
        return accountService.findById(id, userContext.currentMemberId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody AccountRequest req) {
        return accountService.create(req, userContext.currentMember());
    }

    @PutMapping("/{id}")
    public AccountResponse update(@PathVariable Long id, @Valid @RequestBody AccountRequest req) {
        return accountService.update(id, req, userContext.currentMemberId());
    }

    /**
     * What deleting this account would also remove, so the confirmation can name it before the
     * user commits. Read-only companion to {@link #delete}.
     */
    @GetMapping("/{id}/deletion-impact")
    public AccountConnectionService.DeletionImpact deletionImpact(@PathVariable Long id) {
        return accountConnectionService.describeDeletion(id, userContext.currentMemberId());
    }

    /**
     * Goes through {@link AccountConnectionService}, not {@code accountService.delete}: the
     * connection feeding this account is removed with it when no other account is left on it,
     * otherwise it keeps syncing and rebuilding what the user just deleted.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        accountConnectionService.deleteAccount(id, userContext.currentMemberId());
    }

    @GetMapping("/{id}/history")
    public List<BalanceSnapshot> getHistory(
        @PathVariable Long id,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return accountService.getHistory(id, userContext.currentMemberId(), from, to);
    }

    @PostMapping("/{id}/history")
    @ResponseStatus(HttpStatus.CREATED)
    public BalanceSnapshot addSnapshot(
        @PathVariable Long id,
        @Valid @RequestBody SnapshotRequest req
    ) {
        return accountService.addManualSnapshot(id, userContext.currentMemberId(), req);
    }

    @GetMapping("/{id}/holdings")
    public List<HoldingResponse> getHoldings(@PathVariable Long id) {
        return accountService.getHoldings(id, userContext.currentMemberId());
    }

    /**
     * The per-product breakdown (spot / staking / lending) behind this account's holdings, or an
     * empty list for accounts that have none — the client falls back to the flat holdings table.
     */
    @GetMapping("/{id}/positions")
    public List<ExchangePositionResponse> getPositions(@PathVariable Long id) {
        return cryptoExchangeSyncService.getPositions(id, userContext.currentMemberId());
    }

    @GetMapping("/{id}/transactions")
    public List<TransactionResponse> getTransactions(@PathVariable Long id) {
        return accountService.getTransactions(id, userContext.currentMemberId());
    }

    @GetMapping("/{id}/realized-pnl")
    public RealizedPnlResponse getRealizedPnl(@PathVariable Long id) {
        return realizedPnlService.compute(id, userContext.currentMemberId());
    }

    @PostMapping("/{id}/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse addTransaction(
        @PathVariable Long id,
        @Valid @RequestBody TransactionRequest req
    ) {
        return manualTransactionService.addTransaction(id, userContext.currentMemberId(), req);
    }

    @PutMapping("/{id}/transactions/{txId}")
    public TransactionResponse updateTransaction(
        @PathVariable Long id,
        @PathVariable Long txId,
        @Valid @RequestBody TransactionRequest req
    ) {
        return manualTransactionService.updateTransaction(id, txId, userContext.currentMemberId(), req);
    }

    @DeleteMapping("/{id}/transactions/{txId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTransaction(@PathVariable Long id, @PathVariable Long txId) {
        manualTransactionService.deleteTransaction(id, txId, userContext.currentMemberId());
    }

    @PutMapping("/{id}/holdings/{ticker}")
    public HoldingResponse updateHolding(
        @PathVariable Long id,
        @PathVariable String ticker,
        @Valid @RequestBody HoldingRequest req
    ) {
        return accountService.updateHolding(id, userContext.currentMemberId(), ticker, req.quantity(), req.averageBuyIn());
    }

    @DeleteMapping("/{id}/holdings/{ticker}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteHolding(@PathVariable Long id, @PathVariable String ticker) {
        accountService.deleteHolding(id, userContext.currentMemberId(), ticker);
    }

    @PutMapping("/{id}/real-estate")
    public RealEstateMetadataResponse updateRealEstateMetadata(
        @PathVariable Long id,
        @Valid @RequestBody RealEstateMetadataRequest req
    ) {
        return accountService.updateRealEstateMetadata(id, userContext.currentMemberId(), req);
    }

    /**
     * Saves a SCPI position. The account balance becomes withdrawal price × share count.
     * A missing withdrawal price leaves the previous balance alone and returns
     * {@code PRICE_INCOMPLETE} — the subscription price is never used as a substitute.
     */
    @PutMapping("/{id}/scpi")
    public ScpiPositionResponse updateScpiPosition(
        @PathVariable Long id,
        @Valid @RequestBody ScpiPositionRequest req
    ) {
        return scpiPositionService.save(id, userContext.currentMemberId(), req);
    }

    /**
     * Re-values a property from open data.
     *
     * <p>Always 200: a non-OK {@code status} in the body ("no data for this commune",
     * "Alsace-Moselle is not covered") is information the user needs, not a request failure.
     */
    @PostMapping("/{id}/valuation/refresh")
    public PropertyValuationResponse refreshValuation(@PathVariable Long id) {
        return propertyValuationService.estimate(id, userContext.currentMemberId());
    }

    @GetMapping("/{id}/ownership")
    public OwnershipResponse getOwnership(@PathVariable Long id) {
        return ownershipService.get(id, userContext.currentMemberId());
    }

    /** Replaces the whole split; an empty list restores "owner holds 100%". */
    @PutMapping("/{id}/ownership")
    public OwnershipResponse updateOwnership(
        @PathVariable Long id,
        @Valid @RequestBody OwnershipRequest req
    ) {
        return ownershipService.replace(id, userContext.currentMemberId(), req);
    }

    @PutMapping("/{id}/debt")
    public DebtResponse updateDebtMetadata(
        @PathVariable Long id,
        @Valid @RequestBody DebtRequest req
    ) {
        return accountService.updateDebtMetadata(id, userContext.currentMemberId(), req);
    }

    @GetMapping("/{id}/loan-summary")
    public LoanAmortizationService.LoanScheduleResponse getLoanSummary(@PathVariable Long id) {
        return accountService.getLoanSummary(id, userContext.currentMemberId());
    }
}
