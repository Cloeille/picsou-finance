package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.dto.DashboardResponse;
import com.picsou.dto.GoalAllocationRequest;
import com.picsou.dto.GoalAllocationResponse;
import com.picsou.dto.GoalMonthEntryResponse;
import com.picsou.dto.GoalProgressResponse;
import com.picsou.dto.GoalRequest;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.FamilyMember;
import com.picsou.model.Goal;
import com.picsou.model.GoalAllocation;
import com.picsou.model.GoalManualContribution;
import com.picsou.model.GoalType;
import com.picsou.model.GoalMonthOverride;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.GoalManualContributionRepository;
import com.picsou.repository.GoalMonthOverrideRepository;
import com.picsou.repository.GoalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class GoalService {

    private final GoalRepository goalRepository;
    private final AccountRepository accountRepository;
    private final AccountHoldingRepository accountHoldingRepository;
    private final BalanceSnapshotRepository snapshotRepository;
    private final AccountService accountService;
    private final GoalMonthOverrideRepository overrideRepository;
    private final GoalManualContributionRepository manualContributionRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final HistoryService historyService;
    private final AccountAccessResolver accessResolver;

    public GoalService(
        GoalRepository goalRepository,
        AccountRepository accountRepository,
        AccountHoldingRepository accountHoldingRepository,
        BalanceSnapshotRepository snapshotRepository,
        AccountService accountService,
        GoalMonthOverrideRepository overrideRepository,
        GoalManualContributionRepository manualContributionRepository,
        FamilyMemberRepository familyMemberRepository,
        HistoryService historyService,
        AccountAccessResolver accessResolver
    ) {
        this.goalRepository = goalRepository;
        this.accountRepository = accountRepository;
        this.accountHoldingRepository = accountHoldingRepository;
        this.snapshotRepository = snapshotRepository;
        this.accountService = accountService;
        this.overrideRepository = overrideRepository;
        this.manualContributionRepository = manualContributionRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.historyService = historyService;
        this.accessResolver = accessResolver;
    }

    public List<GoalProgressResponse> findAll(Long memberId) {
        return goalRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .map(this::toProgressResponse)
            .toList();
    }

    public GoalProgressResponse findById(Long id, Long memberId) {
        return toProgressResponse(getOrThrow(id, memberId));
    }

    @Transactional
    public GoalProgressResponse create(GoalRequest req, FamilyMember member) {
        // Scope the lookup to the caller's member: a goal must never attach (and then
        // expose the live balance of) another member's account. findByIdInAndMemberId
        // returns only owned ids, so a foreign/nonexistent id fails the size check below.
        List<Account> accounts = accountRepository.findByIdInAndMemberId(req.accountIds(), member.getId());
        if (accounts.size() != req.accountIds().size()) {
            throw new IllegalArgumentException("One or more account IDs not found");
        }

        Goal goal = Goal.builder()
            .name(req.name())
            .type(req.type())
            .targetAmount(req.targetAmount())
            .deadline(req.deadline())
            .monthlyAmount(req.monthlyAmount())
            .expectedReturn(req.expectedReturn())
            .startDate(req.startDate())
            .endDate(req.endDate())
            .member(member)
            .accounts(new ArrayList<>(accounts))
            .build();
        replaceAllocations(goal, req);

        return toProgressResponse(goalRepository.save(goal));
    }

    @Transactional
    public GoalProgressResponse update(Long id, GoalRequest req, Long memberId) {
        Goal goal = getOrThrow(id, memberId);

        // Member-scoped (see create): never attach another member's account to this goal.
        List<Account> accounts = accountRepository.findByIdInAndMemberId(req.accountIds(), memberId);
        if (accounts.size() != req.accountIds().size()) {
            throw new IllegalArgumentException("One or more account IDs not found");
        }

        goal.setName(req.name());
        goal.setType(req.type());
        goal.setTargetAmount(req.targetAmount());
        goal.setDeadline(req.deadline());
        goal.setMonthlyAmount(req.monthlyAmount());
        goal.setExpectedReturn(req.expectedReturn());
        goal.setStartDate(req.startDate());
        goal.setEndDate(req.endDate());
        goal.setAccounts(new ArrayList<>(accounts));
        replaceAllocations(goal, req);

        return toProgressResponse(goalRepository.save(goal));
    }

    @Transactional
    public void delete(Long id, Long memberId) {
        Goal goal = getOrThrow(id, memberId);
        goalRepository.delete(goal);
    }


    // ─── Allocations ──────────────────────────────────────────────────────────

    /**
     * Rewrites a plan's monthly split from the request.
     *
     * <p>Mutates the existing list rather than replacing it: {@code Goal.allocations} is mapped
     * with {@code orphanRemoval}, and handing Hibernate a fresh {@code ArrayList} makes it throw
     * "a collection with cascade=all-delete-orphan was no longer referenced" instead of deleting
     * the rows that went away.
     *
     * <p>Every ticker must already be held in the funded account. The check lives here rather
     * than on the DTO because it needs the database, and it answers 400: the client picks from a
     * list of the account's own holdings, so an unknown ticker is a malformed request, not a
     * user mistake worth a field-level message.
     */
    private void replaceAllocations(Goal goal, GoalRequest req) {
        List<GoalAllocationRequest> lines = req.allocations();
        goal.getAllocations().clear();
        if (lines.isEmpty()) return;

        Long accountId = goal.getAccounts().getFirst().getId();
        Set<String> held = accountHoldingRepository.findByAccount_Id(accountId).stream()
            .map(AccountHolding::getTicker)
            .collect(Collectors.toSet());

        for (GoalAllocationRequest line : lines) {
            if (!held.contains(line.ticker())) {
                throw new IllegalArgumentException(
                    "Position " + line.ticker() + " is not held in the funded account");
            }
            goal.getAllocations().add(GoalAllocation.builder()
                .goal(goal)
                .ticker(line.ticker())
                .monthlyAmount(line.monthlyAmount())
                .build());
        }
    }

    /**
     * The plan's split, with each line's name resolved from the account it is funded into.
     *
     * <p>Skips the query entirely for a plan with no split — which is most of them — because this
     * runs once per goal on the goals page.
     */
    private List<GoalAllocationResponse> toAllocationResponses(Goal goal) {
        if (goal.getAllocations().isEmpty()) return List.of();

        Map<String, String> names = new LinkedHashMap<>();
        if (!goal.getAccounts().isEmpty()) {
            for (AccountHolding holding : accountHoldingRepository.findByAccount_Id(
                goal.getAccounts().getFirst().getId())) {
                names.put(holding.getTicker(), holding.getName());
            }
        }

        return goal.getAllocations().stream()
            .map(a -> GoalAllocationResponse.of(a, names.get(a.getTicker())))
            .toList();
    }

    /**
     * The monthly calendar, the backfill and the per-month overrides all assume a deadline to
     * count towards. A recurring plan has none, so they refuse it rather than dividing by a null
     * somewhere deeper — an unguarded path here is a 500, not a 400.
     */
    private static Goal requireSavingsTarget(Goal goal) {
        if (goal.getType() == GoalType.RECURRING_INVESTMENT) {
            throw new IllegalArgumentException(
                "This operation applies to savings goals, not to a recurring investment plan");
        }
        return goal;
    }

    // ─── Progress calculation ─────────────────────────────────────────────────

    /**
     * Called for every goal on the goals page <em>and</em> by {@code DashboardService}, so the
     * branch has to live here rather than at the call sites: a recurring plan has no target, and
     * {@code target.subtract(currentTotal)} on a null target is a 500.
     */
    GoalProgressResponse toProgressResponse(Goal goal) {
        return goal.getType() == GoalType.RECURRING_INVESTMENT
            ? toRecurringResponse(goal)
            : toSavingsTargetResponse(goal);
    }

    private GoalProgressResponse toRecurringResponse(Goal goal) {
        List<AccountResponse> accountResponses = goal.getAccounts().stream()
            .map(accountService::toResponse)
            .toList();
        Long goalMemberId = goal.getMember().getId();
        Map<Long, BigDecimal> shares = accessResolver.sharesFor(goal.getAccounts(), goalMemberId);
        BigDecimal currentTotal = goal.getAccounts().stream()
            .map(a -> AccountAccessResolver.weigh(
                accountService.signedLiveBalanceEur(a), shares.get(a.getId())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return GoalProgressResponse.recurring(goal, accountResponses, currentTotal,
            toAllocationResponses(goal));
    }

    private GoalProgressResponse toSavingsTargetResponse(Goal goal) {
        List<AccountResponse> accountResponses = goal.getAccounts().stream()
            .map(accountService::toResponse)
            .toList();

        // Use live balance (with PnL from current prices) for each account.
        // Signed: linked LOAN accounts count negatively against the goal.
        // Weighted by the goal owner's share, so a goal backed by a half-owned property
        // does not report twice the progress actually available to them.
        Long goalMemberId = goal.getMember().getId();
        // One query for the goal's accounts rather than one each: this runs for every goal on
        // the goals page, so the per-account form multiplies out.
        Map<Long, BigDecimal> shares = accessResolver.sharesFor(goal.getAccounts(), goalMemberId);
        BigDecimal currentTotal = goal.getAccounts().stream()
            .map(a -> AccountAccessResolver.weigh(
                accountService.signedLiveBalanceEur(a),
                shares.get(a.getId())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal target = goal.getTargetAmount();
        long monthsLeft = Math.max(0, ChronoUnit.MONTHS.between(LocalDate.now(), goal.getDeadline()));

        BigDecimal needed = target.subtract(currentTotal);
        BigDecimal monthlyNeeded;

        if (monthsLeft > 0) {
            monthlyNeeded = needed.divide(BigDecimal.valueOf(monthsLeft), 2, RoundingMode.HALF_UP);
        } else {
            monthlyNeeded = needed; // deadline passed or this month
        }

        BigDecimal percentComplete = target.compareTo(BigDecimal.ZERO) > 0
            ? currentTotal.divide(target, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
            : BigDecimal.ZERO;

        BigDecimal avgMonthlyContribution = calculateAvgMonthlyContribution(goal);

        // isOnTrack now compares cumulative past objectives vs cumulative past effectives
        // (overrides + manual contributions included). Indirect user override via the calendar.
        boolean isOnTrack = isOnTrackFromPastMonths(goal, monthlyNeeded);

        BigDecimal surplus = avgMonthlyContribution != null
            ? avgMonthlyContribution.subtract(monthlyNeeded)
            : BigDecimal.ZERO;

        return GoalProgressResponse.from(
            goal, accountResponses, currentTotal, percentComplete,
            monthsLeft, monthlyNeeded, avgMonthlyContribution, isOnTrack, surplus
        );
    }

    /**
     * Average monthly contribution. Primary source: balance snapshots of linked
     * accounts over the last 3 months (first vs last, averaged over elapsed months).
     * Fallback for manually-tracked goals (no linked-account snapshot data): the mean
     * of the recorded manual contributions, which backfilled history then refines.
     * Returns null when neither source has data.
     */
    private BigDecimal calculateAvgMonthlyContribution(Goal goal) {
        List<Account> accounts = goal.getAccounts();

        LocalDate threeMonthsAgo = LocalDate.now().minusMonths(3).withDayOfMonth(1);
        BigDecimal totalContribution = BigDecimal.ZERO;
        int accountsWithData = 0;

        for (Account account : accounts) {
            List<BalanceSnapshot> snapshots = snapshotRepository
                .findRecentByAccountId(account.getId(), threeMonthsAgo);

            if (snapshots.size() >= 2) {
                BigDecimal first = snapshots.get(0).getBalance();
                BigDecimal last = snapshots.get(snapshots.size() - 1).getBalance();
                long months = Math.max(1, ChronoUnit.MONTHS.between(
                    snapshots.get(0).getDate(),
                    snapshots.get(snapshots.size() - 1).getDate()
                ));
                BigDecimal delta = last.subtract(first);
                // Loan snapshots store outstanding debt (positive): paying it down
                // shrinks the balance but is positive progress toward the goal.
                if (account.getType() == AccountType.LOAN) {
                    delta = delta.negate();
                }
                totalContribution = totalContribution.add(
                    delta.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP)
                );
                accountsWithData++;
            }
        }

        if (accountsWithData > 0) {
            return totalContribution.divide(BigDecimal.valueOf(accountsWithData), 2, RoundingMode.HALF_UP);
        }

        // Fallback: mean of recorded manual contributions (includes backfilled months).
        List<GoalManualContribution> manuals = manualContributionRepository.findByGoalId(goal.getId());
        if (manuals.isEmpty()) return null;
        BigDecimal manualSum = manuals.stream()
            .map(GoalManualContribution::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return manualSum.divide(BigDecimal.valueOf(manuals.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * isOnTrack = Σ effective(past months) >= Σ objective(past months).
     * - effective = manualActual ?? snapshot delta ; months with neither are skipped.
     * - objective = override ?? monthlyNeeded.
     * - "past" = strictly before current month (current month is in progress).
     * - If goal has no createdAt or no past month with data, returns true (benefit of the doubt).
     */
    private boolean isOnTrackFromPastMonths(Goal goal, BigDecimal monthlyNeeded) {
        if (monthlyNeeded.compareTo(BigDecimal.ZERO) <= 0) return true;
        if (goal.getCreatedAt() == null) return true;

        YearMonth startMonth = YearMonth.from(
            goal.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate()
        );
        YearMonth currentMonth = YearMonth.now();
        if (!startMonth.isBefore(currentMonth)) return true;

        Map<String, BigDecimal> overrideMap = overrideRepository.findByGoalId(goal.getId()).stream()
            .collect(Collectors.toMap(GoalMonthOverride::getYearMonth, GoalMonthOverride::getAmount));
        Map<String, BigDecimal> manualMap = manualContributionRepository.findByGoalId(goal.getId()).stream()
            .collect(Collectors.toMap(GoalManualContribution::getYearMonth, GoalManualContribution::getAmount));

        BigDecimal sumObjective = BigDecimal.ZERO;
        BigDecimal sumEffective = BigDecimal.ZERO;
        boolean anyData = false;

        YearMonth cursor = startMonth;
        while (cursor.isBefore(currentMonth)) {
            String ym = cursor.toString();
            BigDecimal manualActual = manualMap.get(ym);
            BigDecimal actual = manualActual != null ? manualActual : calculateActualForMonth(goal, cursor);
            if (actual != null) {
                BigDecimal objective = overrideMap.getOrDefault(ym, monthlyNeeded);
                sumObjective = sumObjective.add(objective);
                sumEffective = sumEffective.add(actual);
                anyData = true;
            }
            cursor = cursor.plusMonths(1);
        }

        if (!anyData) return true;
        return sumEffective.compareTo(sumObjective) >= 0;
    }

    // ─── History ──────────────────────────────────────────────────────────────

    /**
     * Build daily history for a goal using the shared HistoryService
     * (correct PnL with holdings cost basis + historical prices).
     */
    public List<DashboardResponse.NetWorthPoint> getGoalHistory(Long goalId, Long memberId) {
        Goal goal = getOrThrow(goalId, memberId);
        List<Long> accountIds = goal.getAccounts().stream().map(Account::getId).toList();
        return historyService.buildHistory(accountIds, 12, memberId);
    }

    // ─── Monthly history ──────────────────────────────────────────────────────

    public List<GoalMonthEntryResponse> getMonthlyEntries(Long goalId, Long memberId) {
        Goal goal = requireSavingsTarget(getOrThrow(goalId, memberId));
        BigDecimal objective = toProgressResponse(goal).monthlyNeeded();

        Map<String, BigDecimal> overrideMap = overrideRepository.findByGoalId(goalId).stream()
            .collect(Collectors.toMap(GoalMonthOverride::getYearMonth, GoalMonthOverride::getAmount));

        Map<String, BigDecimal> manualMap = manualContributionRepository.findByGoalId(goalId).stream()
            .collect(Collectors.toMap(GoalManualContribution::getYearMonth, GoalManualContribution::getAmount));

        YearMonth startMonth = effectiveStartMonth(goal);
        YearMonth endMonth = YearMonth.from(goal.getDeadline());

        List<GoalMonthEntryResponse> entries = new ArrayList<>();
        YearMonth current = startMonth;
        while (!current.isAfter(endMonth)) {
            String ym = current.toString();
            BigDecimal actual = calculateActualForMonth(goal, current);
            BigDecimal manualActual = manualMap.get(ym);
            BigDecimal override = overrideMap.get(ym);
            BigDecimal effective = override != null ? override : (manualActual != null ? manualActual : actual);
            entries.add(new GoalMonthEntryResponse(ym, objective, actual, manualActual, override, effective));
            current = current.plusMonths(1);
        }
        return entries;
    }

    /**
     * Earliest month shown in the calendar: the goal's createdAt month, or the
     * backfilled historyStartMonth when it is set and earlier.
     */
    private YearMonth effectiveStartMonth(Goal goal) {
        YearMonth createdMonth = YearMonth.from(
            goal.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate()
        );
        if (goal.getHistoryStartMonth() != null) {
            YearMonth hist = YearMonth.parse(goal.getHistoryStartMonth());
            if (hist.isBefore(createdMonth)) return hist;
        }
        return createdMonth;
    }

    /**
     * Extends the goal's calendar one year further into the past so the user can
     * backfill contributions/objectives from before they started using Picsou.
     * Does NOT affect the on-track calculation, which stays anchored to createdAt.
     */
    @Transactional
    public GoalProgressResponse extendHistory(Long goalId, Long memberId) {
        Goal goal = requireSavingsTarget(getOrThrow(goalId, memberId));
        YearMonth extended = effectiveStartMonth(goal).minusYears(1);
        goal.setHistoryStartMonth(extended.toString());
        return toProgressResponse(goalRepository.save(goal));
    }

    /**
     * Extends the goal's calendar one month further into the past. Used by the
     * "+ previous month" card in the grid for fine-grained backfill (vs the
     * one-year jump of {@link #extendHistory}). Like the yearly variant, this
     * does NOT affect the on-track calculation, which stays anchored to createdAt.
     */
    @Transactional
    public GoalProgressResponse extendHistoryByMonth(Long goalId, Long memberId) {
        Goal goal = requireSavingsTarget(getOrThrow(goalId, memberId));
        YearMonth extended = effectiveStartMonth(goal).minusMonths(1);
        goal.setHistoryStartMonth(extended.toString());
        return toProgressResponse(goalRepository.save(goal));
    }

    @Transactional
    public GoalMonthEntryResponse setMonthOverride(Long goalId, String yearMonth, BigDecimal amount, Long memberId) {
        Goal goal = requireSavingsTarget(getOrThrow(goalId, memberId));
        GoalMonthOverride entry = overrideRepository
            .findByGoalIdAndYearMonth(goalId, yearMonth)
            .orElseGet(GoalMonthOverride::new);
        entry.setGoal(goal);
        entry.setYearMonth(yearMonth);
        entry.setAmount(amount);
        overrideRepository.save(entry);

        BigDecimal objective = toProgressResponse(goal).monthlyNeeded();
        BigDecimal actual = calculateActualForMonth(goal, YearMonth.parse(yearMonth));
        BigDecimal manualActual = manualContributionRepository.findByGoalIdAndYearMonth(goalId, yearMonth)
            .map(GoalManualContribution::getAmount).orElse(null);
        return new GoalMonthEntryResponse(yearMonth, objective, actual, manualActual, amount, amount);
    }

    @Transactional
    public GoalMonthEntryResponse deleteMonthOverride(Long goalId, String yearMonth, Long memberId) {
        Goal goal = requireSavingsTarget(getOrThrow(goalId, memberId));
        overrideRepository.findByGoalIdAndYearMonth(goal.getId(), yearMonth)
            .ifPresent(overrideRepository::delete);
        BigDecimal objective = toProgressResponse(goal).monthlyNeeded();
        BigDecimal actual = calculateActualForMonth(goal, YearMonth.parse(yearMonth));
        BigDecimal manualActual = manualContributionRepository.findByGoalIdAndYearMonth(goal.getId(), yearMonth)
            .map(GoalManualContribution::getAmount).orElse(null);
        BigDecimal effective = manualActual != null ? manualActual : actual;
        return new GoalMonthEntryResponse(yearMonth, objective, actual, manualActual, null, effective);
    }

    @Transactional
    public GoalMonthEntryResponse setManualContribution(Long goalId, String yearMonth, BigDecimal amount, Long memberId) {
        Goal goal = requireSavingsTarget(getOrThrow(goalId, memberId));
        GoalManualContribution entry = manualContributionRepository
            .findByGoalIdAndYearMonth(goalId, yearMonth)
            .orElseGet(GoalManualContribution::new);
        entry.setGoal(goal);
        if (entry.getMember() == null) {
            entry.setMember(familyMemberRepository.getReferenceById(memberId));
        }
        entry.setYearMonth(yearMonth);
        entry.setAmount(amount);
        manualContributionRepository.save(entry);

        BigDecimal objective = toProgressResponse(goal).monthlyNeeded();
        BigDecimal actual = calculateActualForMonth(goal, YearMonth.parse(yearMonth));
        BigDecimal override = overrideRepository.findByGoalIdAndYearMonth(goalId, yearMonth)
            .map(GoalMonthOverride::getAmount).orElse(null);
        BigDecimal effective = override != null ? override : amount;
        return new GoalMonthEntryResponse(yearMonth, objective, actual, amount, override, effective);
    }

    @Transactional
    public GoalMonthEntryResponse deleteManualContribution(Long goalId, String yearMonth, Long memberId) {
        Goal goal = requireSavingsTarget(getOrThrow(goalId, memberId));
        manualContributionRepository.findByGoalIdAndYearMonth(goal.getId(), yearMonth)
            .ifPresent(manualContributionRepository::delete);
        BigDecimal objective = toProgressResponse(goal).monthlyNeeded();
        BigDecimal actual = calculateActualForMonth(goal, YearMonth.parse(yearMonth));
        BigDecimal override = overrideRepository.findByGoalIdAndYearMonth(goal.getId(), yearMonth)
            .map(GoalMonthOverride::getAmount).orElse(null);
        BigDecimal effective = override != null ? override : actual;
        return new GoalMonthEntryResponse(yearMonth, objective, actual, null, override, effective);
    }

    private BigDecimal calculateActualForMonth(Goal goal, YearMonth ym) {
        if (ym.isAfter(YearMonth.now())) return null;

        LocalDate prevMonthEnd = ym.minusMonths(1).atEndOfMonth();
        LocalDate thisMonthEnd = ym.atEndOfMonth();

        BigDecimal total = BigDecimal.ZERO;
        boolean hasData = false;

        for (Account account : goal.getAccounts()) {
            Optional<BalanceSnapshot> prev = snapshotRepository
                .findFirstByAccountIdAndDateLessThanEqualOrderByDateDesc(account.getId(), prevMonthEnd);
            Optional<BalanceSnapshot> curr = snapshotRepository
                .findFirstByAccountIdAndDateLessThanEqualOrderByDateDesc(account.getId(), thisMonthEnd);

            if (prev.isPresent() && curr.isPresent()) {
                BigDecimal delta = curr.get().getBalance().subtract(prev.get().getBalance());
                // Same sign convention as calculateAvgMonthlyContribution: loan paydown
                // (balance decrease) counts as positive progress.
                if (account.getType() == AccountType.LOAN) {
                    delta = delta.negate();
                }
                total = total.add(delta);
                hasData = true;
            }
        }

        return hasData ? total.setScale(2, RoundingMode.HALF_UP) : null;
    }

    private Goal getOrThrow(Long id, Long memberId) {
        return goalRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> ResourceNotFoundException.goal(id));
    }
}
