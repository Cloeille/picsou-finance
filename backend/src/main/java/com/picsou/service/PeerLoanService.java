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
import com.picsou.dto.TransactionRequest;
import com.picsou.dto.TransactionResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
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

    /** The account types a peer loan can draw from and repay into. */
    private static final java.util.Set<AccountType> CASH_TYPES = java.util.Set.of(
        AccountType.CHECKING, AccountType.SAVINGS, AccountType.LEP, AccountType.LIVRET_A,
        AccountType.LDDS, AccountType.LIVRET_JEUNE, AccountType.PEL, AccountType.CEL);

    private final AccountRepository accountRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final PeerCounterpartyRepository counterpartyRepository;
    private final PeerLoanTermsRepository termsRepository;
    private final PeerLoanRepaymentRepository repaymentRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final AccountService accountService;
    private final ManualTransactionService manualTransactionService;
    private final CategorizationService categorizationService;

    public PeerLoanService(
        AccountRepository accountRepository,
        FamilyMemberRepository familyMemberRepository,
        PeerCounterpartyRepository counterpartyRepository,
        PeerLoanTermsRepository termsRepository,
        PeerLoanRepaymentRepository repaymentRepository,
        TransactionRepository transactionRepository,
        CategoryRepository categoryRepository,
        AccountService accountService,
        ManualTransactionService manualTransactionService,
        CategorizationService categorizationService
    ) {
        this.accountRepository = accountRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.counterpartyRepository = counterpartyRepository;
        this.termsRepository = termsRepository;
        this.repaymentRepository = repaymentRepository;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.accountService = accountService;
        this.manualTransactionService = manualTransactionService;
        this.categorizationService = categorizationService;
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
            ? PeerLoanCalculator.simulateByMonths(request.principal(), request.annualRate(), request.months())
            : PeerLoanCalculator.simulateByPayment(request.principal(), request.annualRate(), request.monthlyPayment());
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
        if (termsRepository.findByLendingTransactionId(transaction.getId()).isPresent()) {
            throw new IllegalArgumentException("This transaction is already linked to a peer loan");
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

        accountService.upsertSnapshot(account, principal, LocalDate.now());
        return build(account, terms, LocalDate.now());
    }

    // --- Writes ---

    @Transactional
    public PeerLoanDetailResponse saveTerms(Long accountId, Long memberId, PeerLoanTermsRequest request) {
        Account account = requireAccount(accountId, memberId);
        requireType(account);
        validatePlan(request);
        if (request.expectedBack() != null && request.expectedBack().isBefore(request.lentAt())) {
            throw new IllegalArgumentException("The expected return date cannot precede the lending date");
        }
        if (request.linkedAccountId().equals(accountId)) {
            throw new IllegalArgumentException("A loan cannot be linked to itself");
        }

        PeerLoanTerms terms = termsRepository.findByAccountId(accountId).orElseGet(() -> PeerLoanTerms.builder()
            .account(account)
            .build());
        if (terms.getClosedAt() != null) {
            throw new IllegalArgumentException(
                "This loan is closed. Delete its write-off to reopen it before editing the terms.");
        }

        Account linked = accountRepository.findByIdAndMemberId(request.linkedAccountId(), memberId)
            .orElseThrow(() -> ResourceNotFoundException.account(request.linkedAccountId()));
        if (!CASH_TYPES.contains(linked.getType())) {
            throw new IllegalArgumentException("A peer loan must be linked to a cash account");
        }

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
        }

        terms.setCounterparty(getOrCreateCounterparty(memberId, account.getName()));
        terms.setPrincipal(request.principal());
        terms.setLentAt(request.lentAt());
        terms.setExpectedBack(request.expectedBack());
        terms.setAnnualRate(request.annualRate());
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
        syncLendingLeg(account, terms, linked, memberId);
        termsRepository.save(terms);

        refreshBalance(account, terms);
        return build(account, terms, LocalDate.now());
    }

    @Transactional
    public PeerLoanRepaymentResponse addRepayment(
        Long accountId, Long memberId, PeerLoanRepaymentRequest request
    ) {
        Account account = requireAccount(accountId, memberId);
        requireType(account);
        PeerLoanTerms terms = requireConfiguredTerms(accountId);
        requireOpen(terms);

        PeerLoanRepayment repayment = PeerLoanRepayment.builder()
            .account(account)
            .paidOn(request.paidOn())
            .amount(request.amount())
            .kind(request.kind() != null ? request.kind() : PeerLoanRepaymentKind.PRINCIPAL)
            .note(request.note())
            .build();
        repaymentRepository.save(repayment);

        syncRepaymentLeg(terms, repayment, memberId);
        repaymentRepository.save(repayment);

        refreshBalance(account, terms);
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
        PeerLoanTerms terms = requireConfiguredTerms(accountId);
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
        if (repaymentRepository.findByCashTransactionId(transaction.getId()).isPresent()) {
            throw new IllegalArgumentException("This transaction is already linked to a repayment");
        }
        if (termsRepository.findByLendingTransactionId(transaction.getId()).isPresent()) {
            throw new IllegalArgumentException("This transaction is already linked to a peer loan");
        }

        PeerLoanRepayment repayment = repaymentRepository.save(PeerLoanRepayment.builder()
            .account(account)
            .paidOn(transaction.getDate())
            .amount(transaction.getAmount())
            .kind(kind)
            .cashTransactionId(transaction.getId())
            .cashLegOwned(false)
            .build());

        // The whole received movement is one transfer; the interest part is tracked in
        // the loan ledger, not booked as budget income (one row, one category).
        if (kind == PeerLoanRepaymentKind.PRINCIPAL) {
            transaction.setCategoryRef(transferCategory(memberId));
            transaction.setCategoryManual(true);
            transactionRepository.save(transaction);
        }

        refreshBalance(account, terms);
        return build(account, terms, LocalDate.now());
    }

    @Transactional
    public PeerLoanRepaymentResponse updateRepayment(
        Long accountId, Long repaymentId, Long memberId, PeerLoanRepaymentRequest request
    ) {
        Account account = requireAccount(accountId, memberId);
        requireType(account);
        PeerLoanTerms terms = requireConfiguredTerms(accountId);
        requireOpen(terms);

        PeerLoanRepayment repayment = repaymentRepository.findByIdAndAccountId(repaymentId, accountId)
            .orElseThrow(() -> new ResourceNotFoundException("Repayment not found"));
        if (!repayment.isCashLegOwned()) {
            throw new IllegalArgumentException(
                "This repayment is linked to an existing transaction. Delete it and assign the "
                    + "transaction again to change it.");
        }

        deleteCashLeg(terms, repayment, memberId);
        repayment.setPaidOn(request.paidOn());
        repayment.setAmount(request.amount());
        repayment.setKind(request.kind());
        repayment.setNote(request.note());
        repaymentRepository.save(repayment);

        syncRepaymentLeg(terms, repayment, memberId);
        repaymentRepository.save(repayment);

        refreshBalance(account, terms);
        return PeerLoanRepaymentResponse.from(repayment);
    }

    @Transactional
    public void deleteRepayment(Long accountId, Long repaymentId, Long memberId) {
        Account account = requireAccount(accountId, memberId);
        requireType(account);
        PeerLoanTerms terms = requireConfiguredTerms(accountId);

        PeerLoanRepayment repayment = repaymentRepository.findByIdAndAccountId(repaymentId, accountId)
            .orElseThrow(() -> new ResourceNotFoundException("Repayment not found"));
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

        refreshBalance(account, terms);
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
        PeerLoanTerms terms = requireConfiguredTerms(accountId);

        LocalDate date = closedAt != null ? closedAt : LocalDate.now();
        if (terms.getClosedAt() != null) {
            return build(account, terms, LocalDate.now());
        }
        if (terms.getLentAt() != null && date.isBefore(terms.getLentAt())) {
            throw new IllegalArgumentException("The closure date cannot precede the lending date");
        }

        List<PeerLoanRepayment> repayments =
            repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(accountId);
        BigDecimal residual = PeerLoanCalculator.compute(terms, repayments, date).principalRemaining();
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

        refreshBalance(account, terms);
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

    private void refreshBalance(Account account, PeerLoanTerms terms) {
        List<PeerLoanRepayment> repayments =
            repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(account.getId());
        BigDecimal remaining = PeerLoanCalculator
            .compute(terms, repayments, LocalDate.now())
            .principalRemaining();
        account.setCurrentBalance(remaining);
        accountRepository.save(account);
        accountService.upsertSnapshot(account, remaining, LocalDate.now());
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

    private PeerLoanTerms requireConfiguredTerms(Long accountId) {
        return termsRepository.findByAccountId(accountId)
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
            BigDecimal.ZERO, BigDecimal.ZERO, null, null,
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
        PeerLoanCalculator.Computed computed = PeerLoanCalculator.compute(terms, repayments, date);
        PeerLoanCalculator.Plan plan = PeerLoanCalculator.plan(terms, computed.principalRemaining(), date);
        return new PeerLoanDetailResponse.CurvePoint(
            date,
            computed.principalRemaining(),
            plan == null ? null : plan.scheduledRemaining());
    }
}
