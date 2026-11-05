package com.picsou.service;

import com.picsou.dto.PeerLoanDetailResponse;
import com.picsou.dto.PeerLoanDetailResponse.PeerCounterpartyHistory;
import com.picsou.dto.PeerLoanRepaymentRequest;
import com.picsou.dto.PeerLoanRepaymentResponse;
import com.picsou.dto.PeerLoanSimulationRequest;
import com.picsou.dto.PeerLoanSimulationResponse;
import com.picsou.dto.PeerLoanTermsRequest;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.PeerCounterparty;
import com.picsou.model.PeerLoanRepayment;
import com.picsou.model.PeerLoanRepaymentKind;
import com.picsou.model.PeerLoanTerms;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.PeerCounterpartyRepository;
import com.picsou.repository.PeerLoanRepaymentRepository;
import com.picsou.repository.PeerLoanTermsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Peer loans: money lent to a person, tracked as a receivable.
 *
 * <p>Only facts are written here (terms and dated ledger rows). Remaining capital,
 * accrued interest and the borrower history are recomputed from them on every read, the
 * same way {@link LoanAmortizationService} derives a bank loan's schedule.
 */
@Service
public class PeerLoanService {

    /** Note attached to the write-off a closure creates, so a reopen can identify it. */
    static final String CLOSURE_NOTE = "Closure write-off";

    private final AccountRepository accountRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final PeerCounterpartyRepository counterpartyRepository;
    private final PeerLoanTermsRepository termsRepository;
    private final PeerLoanRepaymentRepository repaymentRepository;
    private final AccountService accountService;

    public PeerLoanService(
        AccountRepository accountRepository,
        FamilyMemberRepository familyMemberRepository,
        PeerCounterpartyRepository counterpartyRepository,
        PeerLoanTermsRepository termsRepository,
        PeerLoanRepaymentRepository repaymentRepository,
        AccountService accountService
    ) {
        this.accountRepository = accountRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.counterpartyRepository = counterpartyRepository;
        this.termsRepository = termsRepository;
        this.repaymentRepository = repaymentRepository;
        this.accountService = accountService;
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

    // --- Writes ---

    @Transactional
    public PeerLoanDetailResponse saveTerms(Long accountId, Long memberId, PeerLoanTermsRequest request) {
        Account account = requireAccount(accountId, memberId);
        requireType(account);

        boolean plannedMissing = request.plannedMonthlyAmount() == null && request.plannedMonths() == null;
        boolean plannedComplete = request.plannedMonthlyAmount() != null && request.plannedMonths() != null;
        if (!plannedMissing && !plannedComplete) {
            throw new IllegalArgumentException("A plan needs both a monthly amount and a month count");
        }
        if (request.expectedBack() != null && request.expectedBack().isBefore(request.lentAt())) {
            throw new IllegalArgumentException("The expected return date cannot precede the lending date");
        }

        PeerLoanTerms terms = termsRepository.findByAccountId(accountId).orElseGet(() -> PeerLoanTerms.builder()
            .account(account)
            .build());
        if (terms.getClosedAt() != null) {
            throw new IllegalArgumentException(
                "This loan is closed. Delete its write-off to reopen it before editing the terms.");
        }

        PeerCounterparty counterparty = getOrCreateCounterparty(memberId, request.counterpartyName());
        Account linked = null;
        if (request.linkedAccountId() != null) {
            if (request.linkedAccountId().equals(accountId)) {
                throw new IllegalArgumentException("A loan cannot be linked to itself");
            }
            linked = accountRepository.findByIdAndMemberId(request.linkedAccountId(), memberId)
                .orElseThrow(() -> ResourceNotFoundException.account(request.linkedAccountId()));
        }

        terms.setCounterparty(counterparty);
        terms.setPrincipal(request.principal());
        terms.setLentAt(request.lentAt());
        terms.setExpectedBack(request.expectedBack());
        terms.setAnnualRate(request.annualRate());
        terms.setPlannedMonthlyAmount(request.plannedMonthlyAmount());
        terms.setPlannedMonths(request.plannedMonths());
        terms.setLinkedAccount(linked);
        terms.setNote(request.note());
        termsRepository.save(terms);

        // The principal and the ledger are EUR figures by construction. Keeping a USD
        // account here would convert them a second time in valuation().
        account.setCurrency("EUR");
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
            .kind(request.kind())
            .note(request.note())
            .build();
        repaymentRepository.save(repayment);

        refreshBalance(account, terms);
        return PeerLoanRepaymentResponse.from(repayment);
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

        repayment.setPaidOn(request.paidOn());
        repayment.setAmount(request.amount());
        repayment.setKind(request.kind());
        repayment.setNote(request.note());
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

    private PeerCounterparty getOrCreateCounterparty(Long memberId, String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("A counterparty name is required");
        }
        return counterpartyRepository.findByMemberIdAndNameIgnoreCase(memberId, normalized)
            .orElseGet(() -> counterpartyRepository.save(PeerCounterparty.builder()
                .member(familyMemberRepository.getReferenceById(memberId))
                .name(normalized)
                .build()));
    }

    private PeerLoanDetailResponse unconfigured(Account account) {
        BigDecimal principal = account.getCurrentBalance() == null
            ? BigDecimal.ZERO
            : account.getCurrentBalance();
        return new PeerLoanDetailResponse(
            account.getId(), account.getName(), false,
            null, null,
            principal, account.getCreatedAt() == null ? null : account.getCreatedAt()
                .atZone(java.time.ZoneOffset.UTC).toLocalDate(),
            null, null, null, null, null, null, null, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, null, null,
            List.of(), null);
    }

    private PeerLoanDetailResponse build(Account account, PeerLoanTerms terms, LocalDate asOf) {
        List<PeerLoanRepayment> repayments =
            repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(account.getId());
        PeerLoanCalculator.Computed computed = PeerLoanCalculator.compute(terms, repayments, asOf);

        BigDecimal principal = terms.getPrincipal() == null ? BigDecimal.ZERO : terms.getPrincipal();
        BigDecimal totalRepaid = computed.principalPaid().add(computed.interestReceived());
        BigDecimal repaidPct = PeerLoanCalculator.repaidPct(principal, computed.principalPaid());
        PeerLoanCalculator.Plan plan = PeerLoanCalculator.plan(terms, computed.principalRemaining(), asOf);

        PeerCounterpartyHistory history = terms.getCounterparty() == null
            ? null
            : counterpartyHistory(terms.getCounterparty().getId(), account.getMember().getId(), asOf);

        return new PeerLoanDetailResponse(
            account.getId(),
            account.getName(),
            true,
            terms.getCounterparty() == null ? null : terms.getCounterparty().getId(),
            terms.getCounterparty() == null ? null : terms.getCounterparty().getName(),
            principal,
            terms.getLentAt(),
            terms.getExpectedBack(),
            terms.getAnnualRate(),
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
            repayments.stream().map(PeerLoanRepaymentResponse::from).toList(),
            history
        );
    }

    /**
     * The borrower's history, as facts. Three loans to the same person are three data
     * points, not a score: the UI shows them, the member draws the conclusion.
     */
    private PeerLoanDetailResponse.PeerCounterpartyHistory counterpartyHistory(
        Long counterpartyId, Long memberId, LocalDate asOf
    ) {
        List<PeerLoanTerms> loans = termsRepository.findAllByAccountMemberId(memberId).stream()
            .filter(terms -> terms.getCounterparty() != null
                && counterpartyId.equals(terms.getCounterparty().getId()))
            .toList();

        List<Long> accountIds = loans.stream().map(terms -> terms.getAccount().getId()).toList();
        Map<Long, List<PeerLoanRepayment>> repaymentsByAccount = new HashMap<>();
        if (!accountIds.isEmpty()) {
            for (PeerLoanRepayment repayment : repaymentRepository.findAllByAccountIdIn(accountIds)) {
                repaymentsByAccount
                    .computeIfAbsent(repayment.getAccount().getId(), key -> new ArrayList<>())
                    .add(repayment);
            }
        }

        int open = 0;
        int closed = 0;
        BigDecimal totalLent = BigDecimal.ZERO;
        BigDecimal totalRecovered = BigDecimal.ZERO;
        BigDecimal totalWrittenOff = BigDecimal.ZERO;
        BigDecimal totalInterest = BigDecimal.ZERO;
        BigDecimal openExposure = BigDecimal.ZERO;
        long delaySum = 0;
        int delayCount = 0;
        List<PeerLoanDetailResponse.LoanBrief> briefs = new ArrayList<>();

        for (PeerLoanTerms terms : loans) {
            List<PeerLoanRepayment> repayments =
                repaymentsByAccount.getOrDefault(terms.getAccount().getId(), List.of());
            PeerLoanCalculator.Computed computed = PeerLoanCalculator.compute(terms, repayments, asOf);
            BigDecimal principal = terms.getPrincipal() == null ? BigDecimal.ZERO : terms.getPrincipal();

            totalLent = totalLent.add(principal);
            totalRecovered = totalRecovered
                .add(computed.principalPaid())
                .add(computed.interestReceived());
            totalWrittenOff = totalWrittenOff.add(computed.writtenOff());
            totalInterest = totalInterest.add(computed.interestReceived());

            if (terms.getClosedAt() != null) {
                closed++;
                if (terms.getExpectedBack() != null) {
                    delaySum += ChronoUnit.DAYS.between(terms.getExpectedBack(), terms.getClosedAt());
                    delayCount++;
                }
            } else {
                open++;
                openExposure = openExposure.add(computed.principalRemaining());
            }

            briefs.add(new PeerLoanDetailResponse.LoanBrief(
                terms.getAccount().getId(),
                terms.getAccount().getName(),
                principal,
                computed.principalRemaining(),
                terms.getLentAt(),
                terms.getClosedAt()
            ));
        }

        Long averageDelay = delayCount == 0 ? null : Math.round((double) delaySum / delayCount);
        String name = loans.isEmpty() ? null : loans.getFirst().getCounterparty().getName();

        return new PeerLoanDetailResponse.PeerCounterpartyHistory(
            counterpartyId,
            name,
            loans.size(),
            open,
            closed,
            totalLent.setScale(2, RoundingMode.HALF_UP),
            totalRecovered.setScale(2, RoundingMode.HALF_UP),
            totalWrittenOff.setScale(2, RoundingMode.HALF_UP),
            totalInterest.setScale(2, RoundingMode.HALF_UP),
            averageDelay,
            openExposure.setScale(2, RoundingMode.HALF_UP),
            briefs
        );
    }
}
