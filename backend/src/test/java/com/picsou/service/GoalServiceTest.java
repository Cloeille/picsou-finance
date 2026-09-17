package com.picsou.service;

import com.picsou.dto.GoalAllocationRequest;
import com.picsou.dto.GoalProgressResponse;
import com.picsou.dto.GoalRequest;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.Goal;
import com.picsou.model.GoalAllocation;
import com.picsou.model.GoalType;
import com.picsou.model.GoalManualContribution;
import com.picsou.model.GoalMonthOverride;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.GoalManualContributionRepository;
import com.picsou.repository.GoalMonthOverrideRepository;
import com.picsou.repository.GoalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoalServiceTest {

    @Mock GoalRepository goalRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository accountHoldingRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock AccountService accountService;
    @Mock GoalMonthOverrideRepository overrideRepository;
    @Mock GoalManualContributionRepository manualContributionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock HistoryService historyService;

    @Mock AccountAccessResolver accessResolver;

    /** Goals are member-scoped in the schema; fixtures must reflect that. */
    private static final FamilyMember GOAL_OWNER = FamilyMember.builder().id(1L).displayName("Owner").build();

    @InjectMocks GoalService goalService;

    @BeforeEach
    void stubOwnershipShares() {
        // No fixture splits an account, so every one resolves to the owning member's 100%.
        // Tests that care about co-ownership override this.
        // Mirrors the batch resolver: every fixture account is wholly owned, so weighting is the
        // identity and these tests keep measuring what they were written to measure.
        lenient().when(accessResolver.sharesFor(any(), any())).thenAnswer(inv -> {
            java.util.Collection<com.picsou.model.Account> accounts = inv.getArgument(0);
            java.util.Map<Long, java.math.BigDecimal> shares = new java.util.HashMap<>();
            for (com.picsou.model.Account a : accounts) {
                shares.put(a.getId(), new java.math.BigDecimal("100"));
            }
            return shares;
        });
    }

    @Test
    void progressCalculation_onTrack() {
        Account account = Account.builder()
            .id(1L)
            .name("LEP")
            .type(AccountType.LEP)
            .currency("EUR")
            .currentBalance(new BigDecimal("5000"))
            .color("#6366f1")
            .build();

        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L)
            .name("Apport immobilier")
            .targetAmount(new BigDecimal("20000"))
            .deadline(LocalDate.now().plusMonths(6))
            .accounts(List.of(account))
            .build();

        when(accountService.toResponse(account)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "LEP", AccountType.LEP, null, "EUR",
                new BigDecimal("5000"), new BigDecimal("5000"),
                null, null, true, "#6366f1", null, null, null, null, null, null, null, null, null, false, null, null
            )
        );
        when(accountService.signedLiveBalanceEur(account)).thenReturn(new BigDecimal("5000"));
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.currentTotal()).isEqualByComparingTo("5000");
        assertThat(progress.targetAmount()).isEqualByComparingTo("20000");

        // The deadline is `now + 6 months`, but ChronoUnit.MONTHS.between clamps to 5
        // when today's day-of-month doesn't exist in the target month (e.g. running on
        // the 31st → November has 30 days). Derive the expectation from the actual
        // monthsLeft so the test verifies the *relationship* (monthlyNeeded = needed /
        // monthsLeft) and stays green on every calendar day.
        long monthsLeft = progress.monthsLeft();
        assertThat(monthsLeft).isIn(5L, 6L);
        assertThat(progress.monthlyNeeded()).isEqualByComparingTo(
            new BigDecimal("15000").divide(BigDecimal.valueOf(monthsLeft), 2, RoundingMode.HALF_UP));
        assertThat(progress.percentComplete()).isEqualByComparingTo("25.0000");
    }

    @Test
    void progressCalculation_resolvesSharesInOneQuery() {
        // toProgressResponse runs for every goal on the page, so a per-account share lookup
        // multiplies out across the list. Two accounts, still one query.
        Account lep = Account.builder().id(1L).name("LEP").type(AccountType.LEP)
            .currency("EUR").currentBalance(new BigDecimal("5000")).color("#6366f1").build();
        Account livret = Account.builder().id(2L).name("Livret").type(AccountType.SAVINGS)
            .currency("EUR").currentBalance(new BigDecimal("3000")).color("#22c55e").build();
        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L)
            .name("Apport immobilier")
            .targetAmount(new BigDecimal("20000"))
            .deadline(LocalDate.now().plusMonths(6))
            .accounts(List.of(lep, livret))
            .build();

        when(accountService.signedLiveBalanceEur(lep)).thenReturn(new BigDecimal("5000"));
        when(accountService.signedLiveBalanceEur(livret)).thenReturn(new BigDecimal("3000"));

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.currentTotal()).isEqualByComparingTo("8000");
        verify(accessResolver, times(1)).sharesFor(any(), any());
        verify(accessResolver, never()).shareFor(any(), any());
    }

    @Test
    void progressCalculation_linkedLoan_countsNegatively() {
        Account asset = Account.builder()
            .id(1L)
            .name("LEP")
            .type(AccountType.LEP)
            .currency("EUR")
            .currentBalance(new BigDecimal("5000"))
            .color("#6366f1")
            .build();
        Account loan = Account.builder()
            .id(2L)
            .name("Prêt")
            .type(AccountType.LOAN)
            .currency("EUR")
            .currentBalance(new BigDecimal("2000"))
            .color("#ef4444")
            .build();

        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L)
            .name("Apport net")
            .targetAmount(new BigDecimal("20000"))
            .deadline(LocalDate.now().plusMonths(6))
            .accounts(List.of(asset, loan))
            .build();

        when(accountService.toResponse(asset)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "LEP", AccountType.LEP, null, "EUR",
                new BigDecimal("5000"), new BigDecimal("5000"),
                null, null, true, "#6366f1", null, null, null, null, null, null, null, null, null, false, null, null
            )
        );
        when(accountService.toResponse(loan)).thenReturn(
            new com.picsou.dto.AccountResponse(
                2L, "Prêt", AccountType.LOAN, null, "EUR",
                new BigDecimal("2000"), new BigDecimal("2000"),
                null, null, true, "#ef4444", null, null, null, null, null, null, null, null, null, false, null, null
            )
        );
        when(accountService.signedLiveBalanceEur(asset)).thenReturn(new BigDecimal("5000"));
        // LOAN: the signed helper returns the outstanding debt as a NEGATIVE value.
        when(accountService.signedLiveBalanceEur(loan)).thenReturn(new BigDecimal("-2000"));
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        // 5000 − 2000: the linked loan reduces goal progress.
        assertThat(progress.currentTotal()).isEqualByComparingTo("3000");
        long monthsLeft = progress.monthsLeft();
        assertThat(monthsLeft).isIn(5L, 6L);
        // monthlyNeeded derives from the netted total: (20000 − 3000) / monthsLeft.
        assertThat(progress.monthlyNeeded()).isEqualByComparingTo(
            new BigDecimal("17000").divide(BigDecimal.valueOf(monthsLeft), 2, RoundingMode.HALF_UP));
    }

    // ─── IDOR regression (GHSA security audit 2026-06-27) ──────────────────────
    // A member must not attach (and thereby read the live balance of) another
    // member's account. The account lookup must be member-scoped, never the
    // inherited, unscoped findAllById.

    // ─── Recurring investment plans ───────────────────────────────────────────
    // The savings-target tests above must all stay green: this type is additive, and the
    // existing shape is what every goal written before V85 still is.

    private Goal recurringPlan() {
        return Goal.builder()
            .id(9L).member(GOAL_OWNER).name("PEA monthly")
            .type(GoalType.RECURRING_INVESTMENT)
            .monthlyAmount(new BigDecimal("300"))
            .accounts(new java.util.ArrayList<>())
            .build();
    }

    @Test
    void recurringPlan_reportsItsPlanWithoutATargetAndWithoutNpe() {
        // toProgressResponse runs for every goal on the goals page AND from DashboardService,
        // where target.subtract(currentTotal) on a null target would be a 500.
        Goal plan = recurringPlan();

        GoalProgressResponse response = goalService.toProgressResponse(plan);

        assertThat(response.type()).isEqualTo(GoalType.RECURRING_INVESTMENT);
        assertThat(response.monthlyAmount()).isEqualByComparingTo("300");
        assertThat(response.targetAmount()).isNull();
        assertThat(response.deadline()).isNull();
        assertThat(response.percentComplete()).isNull();
        assertThat(response.monthlyNeeded()).isNull();
    }

    @Test
    void recurringPlan_isRefusedByTheMonthlyCalendar() {
        // The calendar counts towards a deadline the plan does not have. Refusing explicitly is
        // a 400; letting it through is a 500 somewhere deeper.
        when(goalRepository.findByIdAndMemberId(9L, 42L)).thenReturn(java.util.Optional.of(recurringPlan()));

        assertThatThrownBy(() -> goalService.getMonthlyEntries(9L, 42L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recurringPlan_isRefusedByTheBackfillAndTheOverrides() {
        when(goalRepository.findByIdAndMemberId(9L, 42L))
            .thenReturn(java.util.Optional.of(recurringPlan()));

        assertThatThrownBy(() -> goalService.extendHistory(9L, 42L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> goalService.extendHistoryByMonth(9L, 42L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> goalService.setMonthOverride(9L, "2026-01", BigDecimal.TEN, 42L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> goalService.setManualContribution(9L, "2026-01", BigDecimal.TEN, 42L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_persistsTheRecurringFields() {
        FamilyMember member = FamilyMember.builder().id(42L).build();
        Account account = Account.builder()
            .id(1L).name("PEA").type(AccountType.PEA).currency("EUR")
            .currentBalance(BigDecimal.ZERO).build();
        when(accountRepository.findByIdInAndMemberId(List.of(1L), 42L)).thenReturn(List.of(account));
        when(goalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GoalProgressResponse response = goalService.create(GoalRequest.recurringInvestment(
            "PEA monthly", new BigDecimal("300"), new BigDecimal("7.5"), null, null, 1L), member);

        assertThat(response.type()).isEqualTo(GoalType.RECURRING_INVESTMENT);
        assertThat(response.monthlyAmount()).isEqualByComparingTo("300");
        assertThat(response.expectedReturn()).isEqualByComparingTo("7.5");
    }

    // ─── The monthly split ────────────────────────────────────────────────────

    private static Account pea() {
        return Account.builder()
            .id(1L).name("PEA").type(AccountType.PEA).currency("EUR")
            .currentBalance(BigDecimal.ZERO).build();
    }

    private static AccountHolding holding(String ticker, String name) {
        return AccountHolding.builder().ticker(ticker).name(name).quantity(BigDecimal.ONE).build();
    }

    private static GoalRequest planWithSplit(GoalAllocationRequest... lines) {
        return new GoalRequest("PEA monthly", GoalType.RECURRING_INVESTMENT, null, null,
            new BigDecimal("400"), null, null, null, List.of(lines), List.of(1L));
    }

    @Test
    void create_persistsTheSplitAndNamesItFromTheAccountHoldings() {
        FamilyMember member = FamilyMember.builder().id(42L).build();
        when(accountRepository.findByIdInAndMemberId(List.of(1L), 42L)).thenReturn(List.of(pea()));
        when(accountHoldingRepository.findByAccount_Id(1L))
            .thenReturn(List.of(holding("CW8", "Amundi MSCI World"), holding("ESE", "BNP S&P 500")));
        when(goalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GoalProgressResponse response = goalService.create(planWithSplit(
            new GoalAllocationRequest("CW8", new BigDecimal("250")),
            new GoalAllocationRequest("ESE", new BigDecimal("150"))), member);

        assertThat(response.allocations()).hasSize(2);
        assertThat(response.allocations().getFirst().ticker()).isEqualTo("CW8");
        assertThat(response.allocations().getFirst().name()).isEqualTo("Amundi MSCI World");
        assertThat(response.allocations().getFirst().monthlyAmount()).isEqualByComparingTo("250");
    }

    @Test
    void create_refusesASplitOnAPositionTheAccountDoesNotHold() {
        // The rule the feature exists under: a plan details money going into a position that is
        // already there, never into one the member has yet to buy.
        FamilyMember member = FamilyMember.builder().id(42L).build();
        when(accountRepository.findByIdInAndMemberId(List.of(1L), 42L)).thenReturn(List.of(pea()));
        when(accountHoldingRepository.findByAccount_Id(1L)).thenReturn(List.of(holding("CW8", "World")));

        assertThatThrownBy(() -> goalService.create(
            planWithSplit(new GoalAllocationRequest("NVDA", new BigDecimal("100"))), member))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NVDA");

        verify(goalRepository, never()).save(any());
    }

    @Test
    void update_replacesTheSplitInPlaceRatherThanSwappingTheList() {
        // Goal.allocations is mapped with orphanRemoval: handing Hibernate a fresh ArrayList
        // makes it throw instead of deleting the rows that went away. The list identity has to
        // survive the edit, which is what this asserts.
        Goal plan = recurringPlan();
        plan.getAllocations().add(GoalAllocation.builder()
            .goal(plan).ticker("CW8").monthlyAmount(new BigDecimal("300")).build());
        List<GoalAllocation> original = plan.getAllocations();

        when(goalRepository.findByIdAndMemberId(9L, 42L)).thenReturn(Optional.of(plan));
        when(accountRepository.findByIdInAndMemberId(List.of(1L), 42L)).thenReturn(List.of(pea()));
        when(accountHoldingRepository.findByAccount_Id(1L))
            .thenReturn(List.of(holding("CW8", "World"), holding("ESE", "S&P 500")));
        when(goalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        goalService.update(9L, planWithSplit(
            new GoalAllocationRequest("ESE", new BigDecimal("400"))), 42L);

        assertThat(plan.getAllocations()).isSameAs(original);
        assertThat(plan.getAllocations()).singleElement()
            .satisfies(a -> assertThat(a.getTicker()).isEqualTo("ESE"));
    }

    @Test
    void update_clearsTheSplitWhenTheRequestCarriesNone() {
        Goal plan = recurringPlan();
        plan.getAllocations().add(GoalAllocation.builder()
            .goal(plan).ticker("CW8").monthlyAmount(new BigDecimal("300")).build());

        when(goalRepository.findByIdAndMemberId(9L, 42L)).thenReturn(Optional.of(plan));
        when(accountRepository.findByIdInAndMemberId(List.of(1L), 42L)).thenReturn(List.of(pea()));
        when(goalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        goalService.update(9L, GoalRequest.recurringInvestment(
            "PEA monthly", new BigDecimal("400"), null, null, null, 1L), 42L);

        assertThat(plan.getAllocations()).isEmpty();
        // No split to validate and none to name: the holdings table is never touched. This runs
        // once per goal on the goals page, so the query has to stay conditional.
        verify(accountHoldingRepository, never()).findByAccount_Id(any());
    }

    @Test
    void recurringPlan_withoutASplitReportsAnEmptyListRatherThanNull() {
        // Clients map over this array. An omitted key would reach them as undefined.
        GoalProgressResponse response = goalService.toProgressResponse(recurringPlan());

        assertThat(response.allocations()).isEmpty();
    }

    @Test
    void create_isMemberScoped_andRejectsForeignAccounts() {
        FamilyMember member = FamilyMember.builder().id(42L).build();
        GoalRequest req = GoalRequest.savingsTarget(
            "Trip", new BigDecimal("1000"), LocalDate.now().plusMonths(3), List.of(1L, 2L));
        // Account 2 belongs to someone else → the member-scoped finder returns only the owned one.
        Account owned = Account.builder()
            .id(1L).name("LEP").type(AccountType.LEP).currency("EUR")
            .currentBalance(BigDecimal.ZERO).build();
        when(accountRepository.findByIdInAndMemberId(List.of(1L, 2L), 42L))
            .thenReturn(List.of(owned));

        assertThatThrownBy(() -> goalService.create(req, member))
            .isInstanceOf(IllegalArgumentException.class);

        verify(accountRepository).findByIdInAndMemberId(List.of(1L, 2L), 42L);
        verify(accountRepository, never()).findAllById(any());
        verify(goalRepository, never()).save(any());
    }

    @Test
    void update_isMemberScoped_andRejectsForeignAccounts() {
        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(5L).name("Trip").targetAmount(new BigDecimal("1000"))
            .deadline(LocalDate.now().plusMonths(3))
            .accounts(new java.util.ArrayList<>()).build();
        when(goalRepository.findByIdAndMemberId(5L, 42L)).thenReturn(java.util.Optional.of(goal));
        GoalRequest req = GoalRequest.savingsTarget(
            "Trip", new BigDecimal("1000"), LocalDate.now().plusMonths(3), List.of(1L, 2L));
        Account owned = Account.builder()
            .id(1L).name("LEP").type(AccountType.LEP).currency("EUR")
            .currentBalance(BigDecimal.ZERO).build();
        when(accountRepository.findByIdInAndMemberId(List.of(1L, 2L), 42L))
            .thenReturn(List.of(owned));

        assertThatThrownBy(() -> goalService.update(5L, req, 42L))
            .isInstanceOf(IllegalArgumentException.class);

        verify(accountRepository).findByIdInAndMemberId(List.of(1L, 2L), 42L);
        verify(accountRepository, never()).findAllById(any());
        verify(goalRepository, never()).save(any());
    }

    @Test
    void deleteMonthOverride_foreignGoal_doesNotDelete() {
        when(goalRepository.findByIdAndMemberId(99L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goalService.deleteMonthOverride(99L, "2026-06", 42L))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(overrideRepository, never()).findByGoalIdAndYearMonth(any(), any());
        verify(overrideRepository, never()).delete(any());
    }

    @Test
    void deleteManualContribution_foreignGoal_doesNotDelete() {
        when(goalRepository.findByIdAndMemberId(99L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goalService.deleteManualContribution(99L, "2026-06", 42L))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(manualContributionRepository, never()).findByGoalIdAndYearMonth(any(), any());
        verify(manualContributionRepository, never()).delete(any());
    }

    @Test
    void deleteMonthOverride_ownedGoal_deletesEntry() {
        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(99L)
            .name("Trip")
            .targetAmount(new BigDecimal("1200"))
            .deadline(LocalDate.now().plusMonths(6))
            .accounts(List.of())
            .build();
        GoalMonthOverride override = new GoalMonthOverride();
        override.setGoal(goal);
        override.setYearMonth("2026-06");
        override.setAmount(new BigDecimal("150"));
        when(goalRepository.findByIdAndMemberId(99L, 42L)).thenReturn(Optional.of(goal));
        when(overrideRepository.findByGoalIdAndYearMonth(99L, "2026-06"))
            .thenReturn(Optional.of(override));

        var response = goalService.deleteMonthOverride(99L, "2026-06", 42L);

        verify(overrideRepository).delete(override);
        assertThat(response.yearMonth()).isEqualTo("2026-06");
        assertThat(response.override()).isNull();
    }

    @Test
    void deleteManualContribution_ownedGoal_deletesEntry() {
        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(99L)
            .name("Trip")
            .targetAmount(new BigDecimal("1200"))
            .deadline(LocalDate.now().plusMonths(6))
            .accounts(List.of())
            .build();
        GoalManualContribution contribution = new GoalManualContribution();
        contribution.setGoal(goal);
        contribution.setYearMonth("2026-06");
        contribution.setAmount(new BigDecimal("150"));
        when(goalRepository.findByIdAndMemberId(99L, 42L)).thenReturn(Optional.of(goal));
        when(manualContributionRepository.findByGoalIdAndYearMonth(99L, "2026-06"))
            .thenReturn(Optional.of(contribution));

        var response = goalService.deleteManualContribution(99L, "2026-06", 42L);

        verify(manualContributionRepository).delete(contribution);
        assertThat(response.yearMonth()).isEqualTo("2026-06");
        assertThat(response.manualActual()).isNull();
    }

    @Test
    void isOnTrack_false_whenPastEffectivesBelowPastObjectives() {
        // 3 past months, each with snapshot delta = 1000€.
        // Target 12000, current 0, deadline +3 months → monthlyNeeded = 4000.
        // sumObjectivePast = 3 * 4000 = 12000 ; sumEffectivePast = 3 * 1000 = 3000 → behind.
        Account account = Account.builder()
            .id(1L).name("Livret").type(AccountType.SAVINGS)
            .currency("EUR").currentBalance(BigDecimal.ZERO)
            .color("#000").build();

        java.time.Instant created = LocalDate.now().minusMonths(3).withDayOfMonth(1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L).name("Test").targetAmount(new BigDecimal("12000"))
            .deadline(LocalDate.now().plusMonths(3))
            .accounts(List.of(account))
            .build();
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "createdAt", created);

        when(accountService.toResponse(account)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "Livret", AccountType.SAVINGS, null, "EUR",
                BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, true, "#000", null, null, null, null, null, null, null, null, null, false, null, null
            )
        );
        when(accountService.signedLiveBalanceEur(account)).thenReturn(BigDecimal.ZERO);
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        // Per-month-end balances so each past month delta = 1000.
        // M-4 end = 0, M-3 end = 1000, M-2 end = 2000, M-1 end = 3000.
        java.time.YearMonth now = java.time.YearMonth.now();
        for (int i = 1; i <= 3; i++) {
            java.time.YearMonth past = now.minusMonths(i);
            LocalDate prevEnd = past.minusMonths(1).atEndOfMonth();
            LocalDate thisEnd = past.atEndOfMonth();
            BigDecimal prevBalance = new BigDecimal(String.valueOf((4 - i - 1) * 1000));
            BigDecimal thisBalance = new BigDecimal(String.valueOf((4 - i) * 1000));
            lenient().when(snapshotRepository
                .findFirstByAccountIdAndDateLessThanEqualOrderByDateDesc(1L, prevEnd))
                .thenReturn(java.util.Optional.of(
                    com.picsou.model.BalanceSnapshot.builder()
                        .balance(prevBalance).date(prevEnd).build()));
            lenient().when(snapshotRepository
                .findFirstByAccountIdAndDateLessThanEqualOrderByDateDesc(1L, thisEnd))
                .thenReturn(java.util.Optional.of(
                    com.picsou.model.BalanceSnapshot.builder()
                        .balance(thisBalance).date(thisEnd).build()));
        }
        lenient().when(overrideRepository.findByGoalId(1L)).thenReturn(List.of());
        lenient().when(manualContributionRepository.findByGoalId(1L)).thenReturn(List.of());

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.isOnTrack()).isFalse();
    }

    @Test
    void avgMonthlyContribution_loanPaydown_countsAsPositiveProgress() {
        // Outstanding debt shrinks 12000 → 9000 over 3 months: the raw snapshot delta
        // is −1000/month, but paying down a linked loan is positive progress.
        Account loan = Account.builder()
            .id(1L).name("Mortgage").type(AccountType.LOAN)
            .currency("EUR").currentBalance(new BigDecimal("9000"))
            .color("#ef4444").build();

        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L).name("Rembourser").targetAmount(new BigDecimal("12000"))
            .deadline(LocalDate.now().plusMonths(12))
            .accounts(List.of(loan))
            .build();

        when(accountService.toResponse(loan)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "Mortgage", AccountType.LOAN, null, "EUR",
                new BigDecimal("9000"), new BigDecimal("9000"),
                null, null, true, "#ef4444", null, null, null, null, null, null, null, null, null, false, null, null
            )
        );
        when(accountService.signedLiveBalanceEur(loan)).thenReturn(new BigDecimal("-9000"));
        LocalDate threeMonthsAgo = LocalDate.now().minusMonths(3);
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of(
            com.picsou.model.BalanceSnapshot.builder()
                .balance(new BigDecimal("12000")).date(threeMonthsAgo).build(),
            com.picsou.model.BalanceSnapshot.builder()
                .balance(new BigDecimal("9000")).date(LocalDate.now()).build()
        ));
        lenient().when(overrideRepository.findByGoalId(1L)).thenReturn(List.of());
        lenient().when(manualContributionRepository.findByGoalId(1L)).thenReturn(List.of());

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        // (12000 − 9000) / 3 months, sign flipped for the LOAN account.
        assertThat(progress.avgMonthlyContribution()).isEqualByComparingTo("1000");
    }

    @Test
    void isOnTrack_true_whenManualContributionCoversShortfall() {
        // Same setup as the "behind" test but user declares 4000€ manual contribution
        // for each of the 3 past months → effective matches objective → on track.
        Account account = Account.builder()
            .id(1L).name("Livret").type(AccountType.SAVINGS)
            .currency("EUR").currentBalance(BigDecimal.ZERO)
            .color("#000").build();

        java.time.Instant created = LocalDate.now().minusMonths(3).withDayOfMonth(1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L).name("Test").targetAmount(new BigDecimal("12000"))
            .deadline(LocalDate.now().plusMonths(3))
            .accounts(List.of(account))
            .build();
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "createdAt", created);

        when(accountService.toResponse(account)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "Livret", AccountType.SAVINGS, null, "EUR",
                BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, true, "#000", null, null, null, null, null, null, null, null, null, false, null, null
            )
        );
        when(accountService.signedLiveBalanceEur(account)).thenReturn(BigDecimal.ZERO);
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        when(overrideRepository.findByGoalId(1L)).thenReturn(List.of());

        java.time.YearMonth now = java.time.YearMonth.now();
        java.util.List<com.picsou.model.GoalManualContribution> manuals = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            com.picsou.model.GoalManualContribution m = new com.picsou.model.GoalManualContribution();
            m.setGoal(goal);
            m.setYearMonth(now.minusMonths(i).toString());
            m.setAmount(new BigDecimal("4000"));
            manuals.add(m);
        }
        when(manualContributionRepository.findByGoalId(1L)).thenReturn(manuals);

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.isOnTrack()).isTrue();
    }

    @Test
    void isOnTrack_true_whenNoPastMonthHasData() {
        // Goal created this month → no past months → benefit of the doubt.
        Account account = Account.builder()
            .id(1L).name("LEP").type(AccountType.LEP)
            .currency("EUR").currentBalance(BigDecimal.ZERO)
            .color("#000").build();

        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L).name("Tout neuf").targetAmount(new BigDecimal("10000"))
            .deadline(LocalDate.now().plusMonths(6))
            .accounts(List.of(account))
            .build();
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "createdAt", java.time.Instant.now());

        when(accountService.toResponse(account)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "LEP", AccountType.LEP, null, "EUR",
                BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, true, "#000", null, null, null, null, null, null, null, null, null, false, null, null
            )
        );
        when(accountService.signedLiveBalanceEur(account)).thenReturn(BigDecimal.ZERO);
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.isOnTrack()).isTrue();
    }

    // ─── Backfill history ───────────────────────────────────────────────────

    private Goal backfillGoal(String historyStartMonth) {
        java.time.Instant created = LocalDate.now().withDayOfMonth(1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        Goal goal = Goal.builder()
            .member(GOAL_OWNER)
            .id(1L).name("Backfill").targetAmount(new BigDecimal("1000"))
            .deadline(LocalDate.now().plusMonths(2))
            .accounts(List.of())
            .historyStartMonth(historyStartMonth)
            .build();
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "createdAt", created);
        return goal;
    }

    @Test
    void getMonthlyEntries_usesHistoryStartWhenEarlierThanCreatedAt() {
        String historyStart = java.time.YearMonth.now().minusYears(1).toString();
        Goal goal = backfillGoal(historyStart);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));

        var entries = goalService.getMonthlyEntries(1L, 1L);

        assertThat(entries).isNotEmpty();
        assertThat(entries.get(0).yearMonth()).isEqualTo(historyStart);
        // No accounts and no manual/override data → backfilled month is empty.
        assertThat(entries.get(0).effective()).isNull();
    }

    @Test
    void getMonthlyEntries_ignoresHistoryStartWhenLaterThanCreatedAt() {
        String historyStart = java.time.YearMonth.now().plusMonths(2).toString();
        Goal goal = backfillGoal(historyStart);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));

        var entries = goalService.getMonthlyEntries(1L, 1L);

        assertThat(entries.get(0).yearMonth()).isEqualTo(java.time.YearMonth.now().toString());
    }

    @Test
    void extendHistory_decrementsByOneYearFromCreatedAt() {
        Goal goal = backfillGoal(null);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));
        when(goalRepository.save(goal)).thenReturn(goal);

        GoalProgressResponse progress = goalService.extendHistory(1L, 1L);

        String expected = java.time.YearMonth.now().minusYears(1).toString();
        assertThat(goal.getHistoryStartMonth()).isEqualTo(expected);
        assertThat(progress.historyStartMonth()).isEqualTo(expected);
    }

    @Test
    void extendHistory_decrementsFromExistingHistoryStart() {
        String existing = java.time.YearMonth.now().minusYears(1).toString();
        Goal goal = backfillGoal(existing);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));
        when(goalRepository.save(goal)).thenReturn(goal);

        goalService.extendHistory(1L, 1L);

        assertThat(goal.getHistoryStartMonth())
            .isEqualTo(java.time.YearMonth.now().minusYears(2).toString());
    }

    @Test
    void extendHistoryByMonth_decrementsByOneMonthFromCreatedAt() {
        Goal goal = backfillGoal(null);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));
        when(goalRepository.save(goal)).thenReturn(goal);

        GoalProgressResponse progress = goalService.extendHistoryByMonth(1L, 1L);

        String expected = java.time.YearMonth.now().minusMonths(1).toString();
        assertThat(goal.getHistoryStartMonth()).isEqualTo(expected);
        assertThat(progress.historyStartMonth()).isEqualTo(expected);
    }

    @Test
    void extendHistoryByMonth_decrementsFromExistingHistoryStart() {
        String existing = java.time.YearMonth.now().minusMonths(1).toString();
        Goal goal = backfillGoal(existing);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));
        when(goalRepository.save(goal)).thenReturn(goal);

        goalService.extendHistoryByMonth(1L, 1L);

        assertThat(goal.getHistoryStartMonth())
            .isEqualTo(java.time.YearMonth.now().minusMonths(2).toString());
    }

    @Test
    void isOnTrack_unaffectedByHistoryStart() {
        // History extended far back with no data → on-track stays anchored to createdAt
        // (created this month → no past months → benefit of the doubt).
        Goal goal = backfillGoal(java.time.YearMonth.now().minusYears(5).toString());
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));

        GoalProgressResponse progress = goalService.findById(1L, 1L);

        assertThat(progress.isOnTrack()).isTrue();
    }
}
