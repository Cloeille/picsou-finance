package com.picsou.service;

import com.picsou.dto.PeerLoanDetailResponse;
import com.picsou.dto.PeerLoanFromTransactionRequest;
import com.picsou.dto.PeerLoanOpenResponse;
import com.picsou.dto.PeerLoanRepaymentFromTransactionRequest;
import com.picsou.dto.PeerLoanRepaymentRequest;
import com.picsou.dto.PeerLoanRepaymentResponse;
import com.picsou.dto.PeerLoanSimulationRequest;
import com.picsou.dto.PeerLoanSimulationResponse;
import com.picsou.dto.PeerLoanTermsRequest;
import com.picsou.dto.PeerLoansOverviewResponse;
import com.picsou.dto.DashboardResponse;
import com.picsou.dto.TransactionRequest;
import com.picsou.dto.TransactionResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.PeerCounterparty;
import com.picsou.model.PeerLoanRatePeriod;
import com.picsou.model.PeerLoanRepayment;
import com.picsou.model.PeerLoanRepaymentKind;
import com.picsou.model.PeerLoanTerms;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.PeerCounterpartyRepository;
import com.picsou.repository.PeerLoanRepaymentRepository;
import com.picsou.repository.PeerLoanTermsRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Peer loans: money lent to a person, tracked as a receivable.
 *
 * <p>Only facts are written here (terms and dated ledger rows). Remaining capital,
 * accrued interest and the plan drift are recomputed from them on every read, the same
 * way {@link LoanAmortizationService} derives a bank loan's schedule.
 *
 * <p>One account is one loan: the account's own name is the borrower, and the account
 * page never aggregates another loan's figures.
 *
 * <p>The receivable must balance a real cash movement. A manual linked account gets the
 * cash leg written by this service; a synced one already carries the provider's row, so
 * nothing is written. Linking the money to a transaction at creation reuses that row.
 */
@Service
public class PeerLoanService {

    /** Note attached to the write-off a closure creates. */
    static final String CLOSURE_NOTE = "Closure write-off";

    /** Seeded category for money moving between the member's own accounts. */
    static final String TRANSFER_SLUG = "virement-interne";

    private static final int MAX_ACCOUNT_NAME = 100;

    /**
     * No real loan or payment predates this. A date is the origin of the history repair and of
     * the curve, so an ancient one would draw tens of thousands of points.
     */
    private static final LocalDate MIN_DATE = LocalDate.of(1900, 1, 1);

    /** The account types a peer loan can draw from and repay into. */
    private static final java.util.Set<AccountType> CASH_TYPES = java.util.Set.of(
        AccountType.CHECKING, AccountType.SAVINGS, AccountType.LEP, AccountType.LIVRET_A,
        AccountType.LDDS, AccountType.LIVRET_JEUNE, AccountType.PEL, AccountType.CEL);

    private final AccountRepository accountRepository;
    private final BalanceSnapshotRepository snapshotRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final PeerCounterpartyRepository counterpartyRepository;
    private final PeerLoanTermsRepository termsRepository;
    private final PeerLoanRepaymentRepository repaymentRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final AccountService accountService;
    private final ManualTransactionService manualTransactionService;
    private final CategorizationService categorizationService;
    private final HistoryService historyService;

    public PeerLoanService(
        AccountRepository accountRepository,
        BalanceSnapshotRepository snapshotRepository,
        FamilyMemberRepository familyMemberRepository,
        PeerCounterpartyRepository counterpartyRepository,
        PeerLoanTermsRepository termsRepository,
        PeerLoanRepaymentRepository repaymentRepository,
        TransactionRepository transactionRepository,
        CategoryRepository categoryRepository,
        AccountService accountService,
        ManualTransactionService manualTransactionService,
        CategorizationService categorizationService,
        HistoryService historyService
    ) {
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.counterpartyRepository = counterpartyRepository;
        this.termsRepository = termsRepository;
        this.repaymentRepository = repaymentRepository;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.accountService = accountService;
        this.manualTransactionService = manualTransactionService;
        this.categorizationService = categorizationService;
        this.historyService = historyService;
    }

    // --- Reads ---

    @Transactional(readOnly = true)
    public PeerLoanDetailResponse get(Long accountId, Long memberId) {
        Account account = requireAccount(accountId, memberId);
        requireType(account);
        return termsRepository.findByAccountId(accountId)
            .map(terms -> build(account, terms, LocalDate.now()))
            .orElseGet(() -> unconfigured(account));
    }

    @Transactional(readOnly = true)
    public PeerLoanSimulationResponse simulate(PeerLoanSimulationRequest request) {
        boolean byMonths = request.months() != null;
        boolean byPayment = request.monthlyPayment() != null;
        if (byMonths == byPayment) {
            throw new IllegalArgumentException("Provide exactly one of months or monthlyPayment");
        }
        PeerLoanCalculator.Simulation simulation = byMonths
            ? PeerLoanCalculator.simulateByMonths(request.principal(), PeerLoanCalculator.rate(request.annualRate()), request.months())
            : PeerLoanCalculator.simulateByPayment(request.principal(), PeerLoanCalculator.rate(request.annualRate()), request.monthlyPayment());
        return PeerLoanSimulationResponse.from(simulation);
    }

    /** The open loans a received transaction can be assigned to. */
    @Transactional(readOnly = true)
    public List<PeerLoanOpenResponse> openLoans(Long memberId) {
        List<PeerLoanOpenResponse> result = new ArrayList<>();
        for (Account account : accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId)) {
            if (account.getType() != AccountType.PEER_LOAN) {
                continue;
            }
            Optional<PeerLoanTerms> terms = termsRepository.findByAccountId(account.getId());
            if (terms.isEmpty() || terms.get().getClosedAt() != null) {
                continue;
            }
            BigDecimal remaining = PeerLoanCalculator.compute(
                terms.get(),
                repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(account.getId()),
                LocalDate.now()).principalRemaining();
            result.add(new PeerLoanOpenResponse(account.getId(), account.getName(), remaining));
        }
        result.sort(Comparator.comparing(PeerLoanOpenResponse::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /**
     * Every configured peer loan as one readable view: the total still owed, the open and
     * closed counts, and the total outstanding on each history point.
     *
     * <p>The curve reuses {@link HistoryService} on the same accounts, so the section and the
     * net-worth chart cannot disagree about a loan's balance. Unconfigured accounts are left
     * out: they have no terms to describe and no ledger to summarise.
     */
    @Transactional(readOnly = true)
    public PeerLoansOverviewResponse overview(Long memberId, int months) {
        LocalDate today = LocalDate.now();
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        BigDecimal interestAccrued = BigDecimal.ZERO;
        int openCount = 0;
        int closedCount = 0;
        List<PeerLoansOverviewResponse.LoanEntry> loans = new ArrayList<>();
        List<Long> configuredIds = new ArrayList<>();

        for (Account account : accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId)) {
            if (account.getType() != AccountType.PEER_LOAN) {
                continue;
            }
            Optional<PeerLoanTerms> terms = termsRepository.findByAccountId(account.getId());
            if (terms.isEmpty()) {
                continue;
            }
            PeerLoanTerms loan = terms.get();
            List<PeerLoanRepayment> repayments =
                repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(account.getId());
            PeerLoanCalculator.Computed computed = PeerLoanCalculator.compute(loan, repayments, today);
            PeerLoanCalculator.Plan plan =
                PeerLoanCalculator.plan(loan, computed.principalRemaining(), today);

            if (loan.getClosedAt() == null) {
                openCount++;
                totalOutstanding = totalOutstanding.add(computed.principalRemaining());
            } else {
                closedCount++;
            }
            interestAccrued = interestAccrued.add(computed.interestAccrued());

            PeerLoanRepayment last = repayments.isEmpty() ? null : repayments.getLast();
            loans.add(new PeerLoansOverviewResponse.LoanEntry(
                account.getId(),
                account.getName(),
                loan.getPrincipal(),
                computed.principalRemaining(),
                PeerLoanCalculator.repaidPct(loan.getPrincipal(), computed.principalPaid()),
                computed.interestAccrued(),
                computed.interestReceived(),
                loan.getLentAt(),
                loan.getExpectedBack(),
                plan == null ? null : plan.installmentsBehind(),
                last == null ? null : last.getPaidOn(),
                last == null ? null : last.getAmount(),
                loan.getClosedAt()));
            configuredIds.add(account.getId());
        }

        // Open loans first, then alphabetical: the one still running is what the reader
        // came for, and a closed loan is history that stays available below.
        loans.sort(Comparator.comparing((PeerLoansOverviewResponse.LoanEntry entry) -> entry.closedAt() != null)
            .thenComparing(PeerLoansOverviewResponse.LoanEntry::name, String.CASE_INSENSITIVE_ORDER));

        return new PeerLoansOverviewResponse(
            totalOutstanding, openCount, closedCount, interestAccrued, loans,
            overviewHistory(configuredIds, months, memberId));
    }

    private List<PeerLoansOverviewResponse.Point> overviewHistory(
        List<Long> accountIds, int months, Long memberId
    ) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        List<PeerLoansOverviewResponse.Point> points = new ArrayList<>();
        for (DashboardResponse.NetWorthPoint point
            : historyService.buildHistory(accountIds, Math.clamp(months, 1, 24), true, memberId)) {
            BigDecimal outstanding = BigDecimal.ZERO;
            for (Long accountId : accountIds) {
                DashboardResponse.AccountPoint accountPoint =
                    point.accounts() == null ? null : point.accounts().get(accountId);
                if (accountPoint != null) {
                    outstanding = outstanding.add(accountPoint.total());
                }
            }
            points.add(new PeerLoansOverviewResponse.Point(point.date(), outstanding));
        }
        return points;
    }

    // --- Creation from an existing movement ---

    /**
     * Creates a loan whose lending leg is an existing transaction. The transaction is
     * linked and categorised as a transfer, never copied: the money already left the
     * account, and duplicating the row would count it twice.
     */
    @Transactional
    public PeerLoanDetailResponse createFromTransaction(Long memberId, PeerLoanFromTransactionRequest request) {
        Transaction transaction = transactionRepository.findById(request.transactionId())
            .orElseThrow(() -> ResourceNotFoundException.transaction(request.transactionId()));
        if (transaction.getAccount() == null
            || transaction.getAccount().getMember() == null
            || !memberId.equals(transaction.getAccount().getMember().getId())) {
            throw ResourceNotFoundException.transaction(request.transactionId());
        }
        if (transaction.getAmount() == null || transaction.getAmount().signum() >= 0) {
            throw new IllegalArgumentException("Only an outgoing transaction can fund a peer loan");
        }
        if (!CASH_TYPES.contains(transaction.getAccount().getType())) {
            throw new IllegalArgumentException("Only a cash movement can fund a peer loan");
        }
        requireEur(transaction.getAccount(), "fund a peer loan");
        if (termsRepository.findByLendingTransactionId(transaction.getId()).isPresent()) {
            throw new IllegalArgumentException("This transaction is already linked to a peer loan");
        }
        // The transaction's date becomes the lending date, so it follows the same bounds:
        // a future or ancient bank date would otherwise feed an unbounded curve.
        LocalDate lendingDate = transaction.getDate();
        if (lendingDate != null && lendingDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("The transaction date cannot be in the future");
        }
        if (lendingDate != null && lendingDate.isBefore(MIN_DATE)) {
            throw new IllegalArgumentException("The transaction date is too far in the past");
        }

        String name = beneficiaryName(request.beneficiary(), transaction);
        BigDecimal principal = transaction.getAmount().abs();

        Account account = accountRepository.save(Account.builder()
            .member(familyMemberRepository.getReferenceById(memberId))
            .name(name)
            .type(AccountType.PEER_LOAN)
            .currency("EUR")
            .currentBalance(principal)
            .isManual(true)
            .color("#0d9488")
            .build());

        PeerLoanTerms terms = termsRepository.save(PeerLoanTerms.builder()
            .account(account)
            .counterparty(getOrCreateCounterparty(memberId, name))
            .principal(principal)
            .lentAt(transaction.getDate())
            .annualRate(BigDecimal.ZERO)
            .ratePeriod(PeerLoanRatePeriod.ANNUAL)
            .linkedAccount(transaction.getAccount())
            .lendingTransactionId(transaction.getId())
            .build());

        transaction.setCategoryRef(transferCategory(memberId));
        transaction.setCategoryManual(true);
        transactionRepository.save(transaction);

        // The transaction is the lending event: a backdated row must land on its own date in
        // history, not on the day the loan happened to be recorded.
        refreshBalance(account, terms, transaction.getDate());
        return build(account, terms, LocalDate.now());
    }

    // --- Writes ---

    @Transactional
    public PeerLoanDetailResponse saveTerms(Long accountId, Long memberId, PeerLoanTermsRequest request) {
        Account account = requireAccount(accountId, memberId);
        requireType(account);
        validatePlan(request);
        if (request.lentAt().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("The lending date cannot be in the future");
        }
        if (request.lentAt().isBefore(MIN_DATE)) {
            throw new IllegalArgumentException("The lending date is too far in the past");
        }
        if (request.expectedBack() != null && request.expectedBack().isBefore(request.lentAt())) {
            throw new IllegalArgumentException("The expected return date cannot precede the lending date");
        }
        if (request.linkedAccountId().equals(accountId)) {
            throw new IllegalArgumentException("A loan cannot be linked to itself");
        }

        PeerLoanTerms terms = termsRepository.findByAccountId(accountId).orElseGet(() -> PeerLoanTerms.builder()
            .account(account)
            .build());
        // The lending outflow is only a first-configuration decision: a loan recorded after
        // the fact may already be reflected in the cash account's balance, and writing the
        // withdrawal again would double the outflow in net worth.
        boolean firstConfiguration = terms.getId() == null;
        boolean withCashLeg = request.withCashLeg() == null || request.withCashLeg();
        if (terms.getClosedAt() != null) {
            throw new IllegalArgumentException(
                "This loan is closed. Delete its write-off to reopen it before editing the terms.");
        }

        Account linked = accountRepository.findByIdAndMemberId(request.linkedAccountId(), memberId)
            .orElseThrow(() -> ResourceNotFoundException.account(request.linkedAccountId()));
        if (!CASH_TYPES.contains(linked.getType())) {
            throw new IllegalArgumentException("A peer loan must be linked to a cash account");
        }
        requireEur(linked, "be linked to a peer loan");

        // The cash movement is a fact. Once it exists, changing the amount or the source
        // would silently rewrite it; deleting and recreating the loan is the honest path.
        if (terms.getLendingTransactionId() != null) {
            if (terms.getPrincipal() != null && terms.getPrincipal().compareTo(request.principal()) != 0) {
                throw new IllegalArgumentException(
                    "The amount cannot change once the cash movement is recorded. "
                        + "Delete the loan and create it again.");
            }
            if (terms.getLinkedAccount() != null && !terms.getLinkedAccount().getId().equals(linked.getId())) {
                throw new IllegalArgumentException(
                    "The cash account cannot change once the cash movement is recorded. "
                        + "Delete the loan and create it again.");
            }
            if (terms.getLentAt() != null && !terms.getLentAt().equals(request.lentAt())) {
                throw new IllegalArgumentException(
                    "The lending date cannot change once the cash movement is recorded. "
                        + "Delete the loan and create it again.");
            }
        }

        terms.setCounterparty(getOrCreateCounterparty(memberId, account.getName()));
        terms.setPrincipal(request.principal());
        // Postponing the lending date must repair history from the earlier date too: the
        // snapshots between the old and the new date still show the old principal.
        LocalDate previousLentAt = terms.getLentAt();
        terms.setLentAt(request.lentAt());
        terms.setExpectedBack(request.expectedBack());
        terms.setAnnualRate(PeerLoanCalculator.rate(request.annualRate()));
        terms.setRatePeriod(request.ratePeriod() != null
            ? request.ratePeriod()
            : PeerLoanRatePeriod.ANNUAL);
        terms.setPlannedMonthlyAmount(request.plannedMonthlyAmount());
        terms.setPlannedMonths(request.plannedMonths());
        terms.setLinkedAccount(linked);
        terms.setNote(request.note());
        termsRepository.save(terms);

        // The principal and the ledger are EUR figures by construction. Keeping a USD
        // account here would convert them a second time in valuation().
        account.setCurrency("EUR");
        if (firstConfiguration && withCashLeg) {
            syncLendingLeg(account, terms, linked, memberId);
        }
        termsRepository.save(terms);

        refreshBalance(account, terms,
            previousLentAt != null && previousLentAt.isBefore(request.lentAt())
                ? previousLentAt
                : request.lentAt());
        return build(account, terms, LocalDate.now());
    }

    @Transactional
    public PeerLoanRepaymentResponse addRepayment(
        Long accountId, Long memberId, PeerLoanRepaymentRequest request
    ) {
        Account account = requireAccount(accountId, memberId);
        requireType(account);
        PeerLoanTerms terms = lockConfiguredTerms(accountId);
        requireOpen(terms);

        requireCompletedRepaymentDate(request.paidOn());
        PeerLoanRepaymentKind kind = request.kind() != null
            ? request.kind()
            : PeerLoanRepaymentKind.PRINCIPAL;
        // A write-off is a closure decision, not a payment: it is recorded by close(),
        // which stamps closedAt next to it. An open loan must never carry one.
        if (kind == PeerLoanRepaymentKind.WRITE_OFF) {
            throw new IllegalArgumentException(
                "A write-off is recorded by closing the loan, not by adding a repayment");
        }
        validateInterestRepayment(terms, kind, request.amount(), request.paidOn(), null);
        validateRepaymentAmount(terms, request.amount(), request.paidOn(), null);

        PeerLoanRepayment repayment = PeerLoanRepayment.builder()
            .account(account)
            .paidOn(request.paidOn())
            .amount(request.amount())
            .kind(kind)
            .note(request.note())
            .build();
        repaymentRepository.save(repayment);

        // A compensation or an already-booked payment leaves the cash account alone.
        boolean withCashLeg = request.withCashLeg() == null || request.withCashLeg();
        if (withCashLeg) {
            syncRepaymentLeg(terms, repayment, memberId);
            repaymentRepository.save(repayment);
        }

        refreshBalance(account, terms, request.paidOn());
        return PeerLoanRepaymentResponse.from(repayment);
    }

    /**
     * Links an existing incoming transaction as a repayment. The transaction is the cash
     * row: writing another one would double the money, which is exactly what a synced
     * account must never see.
     */
    @Transactional
    public PeerLoanDetailResponse addRepaymentFromTransaction(
        Long accountId, Long memberId, PeerLoanRepaymentFromTransactionRequest request
    ) {
        Account account = requireAccount(accountId, memberId);
        requireType(account);
        PeerLoanTerms terms = lockConfiguredTerms(accountId);
        requireOpen(terms);
        PeerLoanRepaymentKind kind = request.kind() != null
            ? request.kind()
            : PeerLoanRepaymentKind.PRINCIPAL;
        if (kind == PeerLoanRepaymentKind.WRITE_OFF) {
            throw new IllegalArgumentException("A received transaction cannot be a write-off");
        }

        Transaction transaction = transactionRepository.findById(request.transactionId())
            .orElseThrow(() -> ResourceNotFoundException.transaction(request.transactionId()));
        if (transaction.getAccount() == null
            || transaction.getAccount().getMember() == null
            || !memberId.equals(transaction.getAccount().getMember().getId())) {
            throw ResourceNotFoundException.transaction(request.transactionId());
        }
        if (transaction.getAmount() == null || transaction.getAmount().signum() <= 0) {
            throw new IllegalArgumentException("Only an incoming transaction can repay a peer loan");
        }
        if (!CASH_TYPES.contains(transaction.getAccount().getType())) {
            throw new IllegalArgumentException("Only a cash movement can repay a peer loan");
        }
        requireEur(transaction.getAccount(), "repay a peer loan");
        if (repaymentRepository.findByCashTransactionId(transaction.getId()).isPresent()) {
            throw new IllegalArgumentException("This transaction is already linked to a repayment");
        }
        if (termsRepository.findByLendingTransactionId(transaction.getId()).isPresent()) {
            throw new IllegalArgumentException("This transaction is already linked to a peer loan");
        }
        requireCompletedRepaymentDate(transaction.getDate());
        validateInterestRepayment(terms, kind, transaction.getAmount(), transaction.getDate(), null);
        // No overpayment check here, unlike the manual paths: the transaction already
        // exists in the cash account and is already counted in net worth, so linking it
        // creates no money — the ledger simply clamps to what the loan can absorb.

        PeerLoanRepayment repayment = repaymentRepository.save(PeerLoanRepayment.builder()
            .account(account)
            .paidOn(transaction.getDate())
            .amount(transaction.getAmount())
            .kind(kind)
            .cashTransactionId(transaction.getId())
            .cashLegOwned(false)
            .build());

        // An explicit interest payment is real income: like the manual leg, the row
        // stays uncategorised so the cashflow counts it by sign. Any other kind is one
        // transfer, whatever its interest part: the split lives in the loan ledger, not
        // as budget income (one row, one category).
        if (kind == PeerLoanRepaymentKind.INTEREST) {
            transaction.setCategoryRef(null);
        } else {
            transaction.setCategoryRef(transferCategory(memberId));
        }
        transaction.setCategoryManual(true);
        transactionRepository.save(transaction);

        refreshBalance(account, terms, transaction.getDate());
        return build(account, terms, LocalDate.now());
    }

    @Transactional
    public PeerLoanRepaymentResponse updateRepayment(
        Long accountId, Long repaymentId, Long memberId, PeerLoanRepaymentRequest request
    ) {
        Account account = requireAccount(accountId, memberId);
        requireType(account);
        PeerLoanTerms terms = lockConfiguredTerms(accountId);
        requireOpen(terms);

        PeerLoanRepayment repayment = repaymentRepository.findByIdAndAccountId(repaymentId, accountId)
            .orElseThrow(() -> new ResourceNotFoundException("Repayment not found"));
        if (!repayment.isCashLegOwned()) {
            throw new IllegalArgumentException(
                "This repayment is linked to an existing transaction. Delete it and assign the "
                    + "transaction again to change it.");
        }
        requireCompletedRepaymentDate(request.paidOn());
        PeerLoanRepaymentKind effectiveKind =
            request.kind() != null ? request.kind() : repayment.getKind();
        if (effectiveKind == PeerLoanRepaymentKind.WRITE_OFF) {
            throw new IllegalArgumentException(
                "A write-off is recorded by closing the loan, not by editing a repayment");
        }
        validateInterestRepayment(terms,
            effectiveKind,
            request.amount(), request.paidOn(), repayment.getId());
        validateRepaymentAmount(terms, request.amount(), request.paidOn(), repayment.getId());

        // Moving a repayment changes the balance from the earlier of its two dates: the days
        // it used to affect are stale too, not only the new ones.
        LocalDate previousPaidOn = repayment.getPaidOn();
        LocalDate affectedFrom = request.paidOn() == null
            ? previousPaidOn
            : previousPaidOn != null && previousPaidOn.isBefore(request.paidOn())
                ? previousPaidOn
                : request.paidOn();

        deleteCashLeg(terms, repayment, memberId);
        repayment.setPaidOn(request.paidOn());
        repayment.setAmount(request.amount());
        // An absent kind means "leave it as it was": the edit dialog may only change the
        // amount or the date, and nulling the kind would silently turn a write-off into
        // principal (or an interest payment into capital).
        if (request.kind() != null) {
            repayment.setKind(request.kind());
        }
        repayment.setNote(request.note());
        repayment.setCashTransactionId(null);
        repayment.setCashLegOwned(true);
        if (request.withCashLeg() == null || request.withCashLeg()) {
            syncRepaymentLeg(terms, repayment, memberId);
        }
        repaymentRepository.save(repayment);

        refreshBalance(account, terms, affectedFrom);
        return PeerLoanRepaymentResponse.from(repayment);
    }

    @Transactional
    public void deleteRepayment(Long accountId, Long repaymentId, Long memberId) {
        Account account = requireAccount(accountId, memberId);
        requireType(account);
        PeerLoanTerms terms = lockConfiguredTerms(accountId);

        PeerLoanRepayment repayment = repaymentRepository.findByIdAndAccountId(repaymentId, accountId)
            .orElseThrow(() -> new ResourceNotFoundException("Repayment not found"));
        LocalDate paidOn = repayment.getPaidOn();
        deleteCashLeg(terms, repayment, memberId);
        repaymentRepository.delete(repayment);
        repaymentRepository.flush();

        // Deleting the write-off that closed the loan reopens it, without a separate
        // "reopen" action to keep in sync with the ledger.
        List<PeerLoanRepayment> remaining = repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(accountId);
        BigDecimal outstanding = PeerLoanCalculator.compute(terms, remaining, LocalDate.now()).principalRemaining();
        if (terms.getClosedAt() != null && outstanding.signum() > 0) {
            terms.setClosedAt(null);
            termsRepository.save(terms);
        }

        refreshBalance(account, terms, paidOn);
    }

    /**
     * Closes the loan: the residual is written off as a dated {@code WRITE_OFF} row and
     * the account is worth zero from that date. The past is never rewritten — the daily
     * snapshot records the loss on the closure date.
     */
    @Transactional
    public PeerLoanDetailResponse close(Long accountId, Long memberId, LocalDate closedAt) {
        Account account = requireAccount(accountId, memberId);
        requireType(account);
        PeerLoanTerms terms = lockConfiguredTerms(accountId);

        LocalDate date = closedAt != null ? closedAt : LocalDate.now();
        if (terms.getClosedAt() != null) {
            return build(account, terms, LocalDate.now());
        }
        if (terms.getLentAt() != null && date.isBefore(terms.getLentAt())) {
            throw new IllegalArgumentException("The closure date cannot precede the lending date");
        }
        if (date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("The closure date cannot be in the future");
        }
        if (date.isBefore(MIN_DATE)) {
            throw new IllegalArgumentException("The closure date is too far in the past");
        }

        List<PeerLoanRepayment> repayments =
            repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(accountId);
        // Only the ledger up to the closure date counts: a repayment dated after it belongs
        // to a future that the write-off must not erase.
        List<PeerLoanRepayment> upToDate = repayments.stream()
            .filter(repayment -> repayment.getPaidOn() == null || !repayment.getPaidOn().isAfter(date))
            .toList();
        BigDecimal residual = PeerLoanCalculator.compute(terms, upToDate, date).principalRemaining();
        if (residual.signum() > 0) {
            repaymentRepository.save(PeerLoanRepayment.builder()
                .account(account)
                .paidOn(date)
                .amount(residual)
                .kind(PeerLoanRepaymentKind.WRITE_OFF)
                .note(CLOSURE_NOTE)
                .build());
        }
        terms.setClosedAt(date);
        termsRepository.save(terms);

        refreshBalance(account, terms, date);
        return build(account, terms, LocalDate.now());
    }

    // --- Cash legs ---

    /**
     * Writes the lending outflow on a manual account, once. A synced account already has
     * the provider's row, so nothing is written there.
     */
    private void syncLendingLeg(Account account, PeerLoanTerms terms, Account linked, Long memberId) {
        if (terms.getLendingTransactionId() != null || !linked.isManual()) {
            return;
        }
        TransactionResponse leg = manualTransactionService.addTransaction(linked.getId(), memberId,
            new TransactionRequest(
                terms.getLentAt(),
                lendingDescription(account.getName()),
                terms.getPrincipal().negate(),
                TransactionType.WITHDRAWAL,
                null, null, null, null, "EUR",
                transferCategory(memberId).getId(),
                null));
        terms.setLendingTransactionId(leg.id());
    }

    /**
     * Mirrors one repayment on the manual cash account. Capital is a transfer; interest
     * is real income and stays uncategorised here so the cashflow counts it by sign.
     * A write-off carries no cash at all.
     */
    private void syncRepaymentLeg(PeerLoanTerms terms, PeerLoanRepayment repayment, Long memberId) {
        Account linked = terms.getLinkedAccount();
        if (linked == null || !linked.isManual() || repayment.getKind() == PeerLoanRepaymentKind.WRITE_OFF) {
            return;
        }
        // Fail closed like the connectors: the account was EUR when it was linked, but the
        // member may have changed it since. The leg is written in EUR, so re-check now.
        requireEur(linked, "receive a repayment");
        boolean interest = repayment.getKind() == PeerLoanRepaymentKind.INTEREST;
        String borrower = terms.getAccount().getName();
        TransactionRequest leg = new TransactionRequest(
            repayment.getPaidOn(),
            interest ? interestDescription(borrower) : repaymentDescription(borrower),
            repayment.getAmount(),
            TransactionType.DEPOSIT,
            null, null, null, null, "EUR",
            interest ? null : transferCategory(memberId).getId(),
            null);

        if (repayment.getCashTransactionId() == null) {
            TransactionResponse created = manualTransactionService.addTransaction(linked.getId(), memberId, leg);
            repayment.setCashTransactionId(created.id());
            repayment.setCashLegOwned(true);
        } else {
            manualTransactionService.updateTransaction(linked.getId(), repayment.getCashTransactionId(), memberId, leg);
        }
    }

    /** Removes a leg this service owns. A linked transaction is only unlinked. */
    private void deleteCashLeg(PeerLoanTerms terms, PeerLoanRepayment repayment, Long memberId) {
        if (repayment.getCashTransactionId() == null) {
            return;
        }
        if (!repayment.isCashLegOwned()) {
            // The transaction belongs to the member or the provider: never delete it,
            // only forget the link.
            repayment.setCashTransactionId(null);
            return;
        }
        Account linked = terms.getLinkedAccount();
        if (linked != null && linked.isManual()) {
            try {
                manualTransactionService.deleteTransaction(linked.getId(), repayment.getCashTransactionId(), memberId);
            } catch (ResourceNotFoundException ignored) {
                // The member may have deleted the cash row themselves; the loan stays editable.
            }
        }
        repayment.setCashTransactionId(null);
    }

    // --- Helpers ---

    /**
     * Writes today's balance, then repairs history from the event's own date onwards. A
     * backdated repayment changes every later balance, so each persisted snapshot at or
     * after it is recomputed: writing only the event date would leave the following rows
     * showing the old principal.
     */
    private void refreshBalance(Account account, PeerLoanTerms terms, LocalDate eventDate) {
        List<PeerLoanRepayment> repayments =
            repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(account.getId());
        BigDecimal remaining = PeerLoanCalculator
            .compute(terms, repayments, LocalDate.now())
            .principalRemaining();
        // Reaching zero is the end of the loan: there is nothing left to collect, and an
        // explicit click would let a 0 EUR receivable linger on the dashboard. An explicit
        // close stays for the other case -- abandoning a residual.
        if (remaining.signum() == 0 && terms.getClosedAt() == null) {
            LocalDate closedAt = repayments.isEmpty()
                ? LocalDate.now()
                : repayments.getLast().getPaidOn();
            terms.setClosedAt(closedAt);
            termsRepository.save(terms);
        }
        account.setCurrentBalance(remaining);
        accountRepository.save(account);

        LocalDate affectedFrom = eventDate != null ? eventDate : LocalDate.now();
        boolean eventDatePersisted = false;
        for (BalanceSnapshot snapshot
            : snapshotRepository.findByAccountIdOrderByDateAsc(account.getId())) {
            LocalDate date = snapshot.getDate();
            if (date.isBefore(affectedFrom)) {
                continue;
            }
            if (date.equals(affectedFrom)) {
                eventDatePersisted = true;
            }
            accountService.upsertSnapshot(account, balanceAt(terms, repayments, date), date);
        }
        if (!eventDatePersisted) {
            accountService.upsertSnapshot(
                account, balanceAt(terms, repayments, affectedFrom), affectedFrom);
        }
    }

    /**
     * The receivable on {@code date}: repayments dated after it are not part of that point,
     * and the loan simply does not exist before the money was lent. {@code compute} only
     * uses {@code asOf} for the final accrual, so the ledger has to be filtered here too.
     */
    private static BigDecimal balanceAt(
        PeerLoanTerms terms, List<PeerLoanRepayment> repayments, LocalDate date
    ) {
        if (terms.getLentAt() != null && date.isBefore(terms.getLentAt())) {
            return BigDecimal.ZERO;
        }
        List<PeerLoanRepayment> upToDate = repayments.stream()
            .filter(repayment -> repayment.getPaidOn() == null
                || !repayment.getPaidOn().isAfter(date))
            .toList();
        return PeerLoanCalculator.compute(terms, upToDate, date).principalRemaining();
    }

    /**
     * The ledger holds completed payments; a forecast belongs in {@code planned_*}. The date
     * also seeds the snapshot repair, so it follows the lending date's lower bound.
     */
    private static void requireCompletedRepaymentDate(LocalDate paidOn) {
        if (paidOn == null) {
            return;
        }
        if (paidOn.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("A repayment date cannot be in the future");
        }
        if (paidOn.isBefore(MIN_DATE)) {
            throw new IllegalArgumentException("A repayment date is too far in the past");
        }
    }

    private Account requireAccount(Long accountId, Long memberId) {
        return accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.account(accountId));
    }

    private static void requireType(Account account) {
        if (account.getType() != AccountType.PEER_LOAN) {
            throw new IllegalArgumentException("Account is not a peer loan");
        }
    }

    /**
     * The loan's terms, locked for the rest of the transaction. Used by every method that writes
     * the ledger: the cap check reads the whole ledger, so two concurrent writers must not
     * validate against the same snapshot and both commit a row the other made unabsorbable.
     */
    private PeerLoanTerms lockConfiguredTerms(Long accountId) {
        return termsRepository.findForUpdateByAccountId(accountId)
            .orElseThrow(() -> new ResourceNotFoundException("Peer loan terms are not set for this account"));
    }

    private static void requireOpen(PeerLoanTerms terms) {
        if (terms.getClosedAt() != null) {
            throw new IllegalArgumentException(
                "This loan is closed. Delete its write-off to reopen it before recording a repayment.");
        }
    }

    private static void validatePlan(PeerLoanTermsRequest request) {
        boolean missing = request.plannedMonthlyAmount() == null && request.plannedMonths() == null;
        boolean complete = request.plannedMonthlyAmount() != null && request.plannedMonths() != null;
        if (!missing && !complete) {
            throw new IllegalArgumentException("A plan needs both a monthly amount and a month count");
        }
        // The plan draws one curve point per month; an unbounded count would be a free DoS.
        if (request.plannedMonths() != null && request.plannedMonths() > 1200) {
            throw new IllegalArgumentException("A plan cannot exceed 1200 months");
        }
    }

    /**
     * The loan ledger and the account's principal are EUR figures. Accepting a cash account
     * in another currency would link an amount that is never converted, so the receivable
     * would silently be wrong by the exchange rate.
     */
    private static void requireEur(Account account, String action) {
        String currency = account.getCurrency();
        if (currency != null && !"EUR".equalsIgnoreCase(currency)) {
            throw new IllegalArgumentException(
                "Only a EUR cash account can " + action + " (this one is in " + currency + ")");
        }
    }

    /**
     * An explicit interest payment pays interest that has actually accrued: anything above
     * it would be saved as a transfer while reducing nothing, which hides real income.
     */
    private void validateInterestRepayment(
        PeerLoanTerms terms, PeerLoanRepaymentKind kind, BigDecimal amount,
        LocalDate paidOn, Long excludedRepaymentId
    ) {
        if (kind != PeerLoanRepaymentKind.INTEREST || amount == null || paidOn == null) {
            return;
        }
        BigDecimal accrued = PeerLoanCalculator
            .compute(terms, ledgerUpTo(terms, paidOn, excludedRepaymentId), paidOn)
            .interestAccrued();
        // An interest row pays what has accrued: with nothing accrued there is no
        // interest to pay, so the row would be capital wearing the wrong label.
        // (Open to debate: zero-rate loans never accrue; rejecting here forces the
        // honest PRINCIPAL label instead of a ledger that says "interest".)
        if (amount.compareTo(accrued) > 0) {
            throw new IllegalArgumentException(
                "An interest repayment cannot exceed the accrued interest of "
                    + accrued.setScale(2, RoundingMode.HALF_UP) + " EUR");
        }
    }

    /**
     * Accepting the repayment must not create cash the ledger cannot absorb. A payment is
     * interest first, then capital, so the loan takes at most what is owed; the excess would
     * be written in full to the cash account while the ledger drops it, inflating net worth.
     *
     * <p>The check covers the whole ledger, not only the rows dated before the candidate: a
     * backdated payment pushes the later rows over the cap (and, on an interest-bearing
     * loan, changes how much interest each of them pays). So the ledger is replayed without
     * and with the candidate, and the candidate is refused when it leaves more cash
     * unabsorbed than there already was. Comparing against the existing figure keeps a
     * linked overpaying transaction (documented as clamped, never refused) from blocking
     * every later edit.
     */
    private void validateRepaymentAmount(
        PeerLoanTerms terms, BigDecimal amount, LocalDate paidOn, Long excludedRepaymentId
    ) {
        if (amount == null || paidOn == null) {
            return;
        }
        List<PeerLoanRepayment> others = ledgerExcluding(terms, excludedRepaymentId);
        // The candidate takes the place the saved row will have among same-day rows: an
        // edited row keeps its id, a new one gets the next, so it sorts last.
        PeerLoanRepayment candidate = PeerLoanRepayment.builder()
            .id(excludedRepaymentId != null ? excludedRepaymentId : Long.MAX_VALUE)
            .paidOn(paidOn)
            .amount(amount)
            .build();
        List<PeerLoanRepayment> withCandidate = new ArrayList<>(others);
        withCandidate.add(candidate);

        if (unabsorbed(terms, withCandidate, paidOn).compareTo(unabsorbed(terms, others, paidOn)) > 0) {
            BigDecimal interestAtPaidOn = PeerLoanCalculator
                .compute(terms, upTo(others, paidOn), paidOn)
                .interestAccrued();
            BigDecimal remainingAfterOthers = PeerLoanCalculator
                .compute(terms, others, paidOn)
                .principalRemaining();
            throw new IllegalArgumentException(
                "A repayment cannot exceed "
                    + interestAtPaidOn.add(remainingAfterOthers).setScale(2, RoundingMode.HALF_UP)
                    + " EUR (accrued interest plus remaining capital)");
        }
    }

    /**
     * Cash the ledger recorded as paid but could not apply to interest or capital. Totals,
     * not per-row ids: the splits of the replayed ledger are summed whichever row they belong to.
     */
    private static BigDecimal unabsorbed(
        PeerLoanTerms terms, List<PeerLoanRepayment> rows, LocalDate asOf
    ) {
        BigDecimal paid = rows.stream()
            .filter(row -> row.getKind() != PeerLoanRepaymentKind.WRITE_OFF)
            .map(PeerLoanRepayment::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal absorbed = PeerLoanCalculator.compute(terms, rows, asOf).splits().stream()
            .map(split -> split.interest().add(split.capital()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return paid.subtract(absorbed);
    }

    /** The ledger as it stood on {@code paidOn}, without the row being edited. */
    private List<PeerLoanRepayment> ledgerUpTo(
        PeerLoanTerms terms, LocalDate paidOn, Long excludedRepaymentId
    ) {
        return upTo(ledgerExcluding(terms, excludedRepaymentId), paidOn);
    }

    /** The whole ledger, without the row being edited. */
    private List<PeerLoanRepayment> ledgerExcluding(PeerLoanTerms terms, Long excludedRepaymentId) {
        return repaymentRepository
            .findByAccountIdOrderByPaidOnAscIdAsc(terms.getAccount().getId()).stream()
            .filter(r -> excludedRepaymentId == null || !excludedRepaymentId.equals(r.getId()))
            .toList();
    }

    private static List<PeerLoanRepayment> upTo(List<PeerLoanRepayment> rows, LocalDate date) {
        return rows.stream()
            .filter(r -> r.getPaidOn() == null || !r.getPaidOn().isAfter(date))
            .toList();
    }

    private PeerCounterparty getOrCreateCounterparty(Long memberId, String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("The account name is required as the borrower");
        }
        return counterpartyRepository.findByMemberIdAndNameIgnoreCase(memberId, normalized)
            .orElseGet(() -> counterpartyRepository.save(PeerCounterparty.builder()
                .member(familyMemberRepository.getReferenceById(memberId))
                .name(normalized)
                .build()));
    }

    /**
     * The seeded transfer category, created on demand for an older member whose seeds
     * predate it. Without a TRANSFER category the outgoing leg would count as spending.
     */
    private Category transferCategory(Long memberId) {
        Category existing = categorizationService.categoriesBySlug(memberId).get(TRANSFER_SLUG);
        if (existing != null) {
            return existing;
        }
        return categoryRepository.save(Category.builder()
            .member(familyMemberRepository.getReferenceById(memberId))
            .slug(TRANSFER_SLUG)
            .name("Virement interne")
            .kind(CategoryKind.TRANSFER)
            .color("#64748b")
            .icon("arrow-left-right")
            .isDefault(true)
            .build());
    }

    private static String beneficiaryName(String requested, Transaction transaction) {
        String name = requested != null && !requested.isBlank()
            ? requested.trim()
            : transaction.getMerchantLabel() != null && !transaction.getMerchantLabel().isBlank()
                ? transaction.getMerchantLabel().trim()
                : transaction.getDescription() != null && !transaction.getDescription().isBlank()
                    ? transaction.getDescription().trim()
                    : "Prêt";
        return name.length() > MAX_ACCOUNT_NAME ? name.substring(0, MAX_ACCOUNT_NAME) : name;
    }

    private static String lendingDescription(String borrower) {
        return "Prêt — " + borrower;
    }

    private static String repaymentDescription(String borrower) {
        return "Remboursement — " + borrower;
    }

    private static String interestDescription(String borrower) {
        return "Intérêts — " + borrower;
    }

    private PeerLoanDetailResponse unconfigured(Account account) {
        BigDecimal principal = account.getCurrentBalance() == null
            ? BigDecimal.ZERO
            : account.getCurrentBalance();
        return new PeerLoanDetailResponse(
            account.getId(), account.getName(), false,
            principal,
            account.getCreatedAt() == null ? null : account.getCreatedAt()
                .atZone(java.time.ZoneOffset.UTC).toLocalDate(),
            null, null, PeerLoanRatePeriod.ANNUAL, null, null, null, null, null, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, null, null, null,
            List.of(), List.of());
    }

    private PeerLoanDetailResponse build(Account account, PeerLoanTerms terms, LocalDate asOf) {
        List<PeerLoanRepayment> repayments =
            repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(account.getId());
        PeerLoanCalculator.Computed computed = PeerLoanCalculator.compute(terms, repayments, asOf);

        BigDecimal principal = terms.getPrincipal() == null ? BigDecimal.ZERO : terms.getPrincipal();
        BigDecimal totalRepaid = computed.principalPaid().add(computed.interestReceived());
        BigDecimal repaidPct = PeerLoanCalculator.repaidPct(principal, computed.principalPaid());
        PeerLoanCalculator.Plan plan = PeerLoanCalculator.plan(terms, computed.principalRemaining(), asOf);

        Map<Long, PeerLoanCalculator.PaymentSplit> splitsById = new HashMap<>();
        for (PeerLoanCalculator.PaymentSplit split : computed.splits()) {
            if (split.repaymentId() != null) {
                splitsById.put(split.repaymentId(), split);
            }
        }

        return new PeerLoanDetailResponse(
            account.getId(),
            account.getName(),
            true,
            principal,
            terms.getLentAt(),
            terms.getExpectedBack(),
            terms.getAnnualRate(),
            terms.getRatePeriod(),
            terms.getPlannedMonthlyAmount(),
            terms.getPlannedMonths(),
            terms.getLinkedAccount() == null ? null : terms.getLinkedAccount().getId(),
            terms.getLinkedAccount() == null ? null : terms.getLinkedAccount().getName(),
            terms.getNote(),
            terms.getClosedAt(),
            computed.principalRemaining(),
            computed.interestAccrued(),
            computed.interestReceived(),
            computed.principalPaid(),
            totalRepaid.setScale(2, RoundingMode.HALF_UP),
            computed.writtenOff(),
            repaidPct,
            plan == null ? null : plan.scheduledRemaining(),
            plan == null ? null : plan.installmentsBehind(),
            plan == null ? null : plan.plannedInterest(),
            repayments.stream()
                .map(repayment -> PeerLoanRepaymentResponse.from(repayment, splitsById.get(repayment.getId())))
                .toList(),
            buildCurve(terms, repayments, asOf)
        );
    }

    /**
     * Monthly points from the lending date to today (or the planned end, whichever is
     * later). The actual line steps down with the repayments; the scheduled line is the
     * forecast, and stays null without a plan.
     */
    private List<PeerLoanDetailResponse.CurvePoint> buildCurve(
        PeerLoanTerms terms, List<PeerLoanRepayment> repayments, LocalDate asOf
    ) {
        LocalDate start = terms.getLentAt();
        if (start == null) {
            return List.of();
        }
        LocalDate end = asOf;
        if (terms.getPlannedMonths() != null && terms.getPlannedMonths() > 0) {
            LocalDate plannedEnd = start.plusMonths(terms.getPlannedMonths());
            if (plannedEnd.isAfter(end)) {
                end = plannedEnd;
            }
        }

        List<PeerLoanDetailResponse.CurvePoint> points = new ArrayList<>();
        YearMonth month = YearMonth.from(start);
        YearMonth lastMonth = YearMonth.from(end);
        while (!month.isAfter(lastMonth)) {
            LocalDate date = month.atDay(1);
            if (date.isBefore(start)) {
                date = start;
            }
            points.add(curvePoint(terms, repayments, date));
            month = month.plusMonths(1);
        }
        if (points.isEmpty() || points.getLast().date().isBefore(asOf)) {
            points.add(curvePoint(terms, repayments, asOf));
        }
        return points;
    }

    private static PeerLoanDetailResponse.CurvePoint curvePoint(
        PeerLoanTerms terms, List<PeerLoanRepayment> repayments, LocalDate date
    ) {
        // A point describes the past up to its own date: a repayment recorded for later
        // must not step the curve down early. Null dates keep their existing handling.
        List<PeerLoanRepayment> upToDate = repayments.stream()
            .filter(repayment -> repayment.getPaidOn() == null || !repayment.getPaidOn().isAfter(date))
            .toList();
        PeerLoanCalculator.Computed computed = PeerLoanCalculator.compute(terms, upToDate, date);
        PeerLoanCalculator.Plan plan = PeerLoanCalculator.plan(terms, computed.principalRemaining(), date);
        return new PeerLoanDetailResponse.CurvePoint(
            date,
            computed.principalRemaining(),
            plan == null ? null : plan.scheduledRemaining());
    }
}
