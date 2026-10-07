package com.picsou.service;

import com.picsou.dto.ProjectionResponse;
import com.picsou.dto.WealthPyramidResponse;
import com.picsou.model.*;
import com.picsou.repository.GoalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ProjectionServiceTest {

    private static final Long MEMBER = 1L;

    @Mock AccountAccessResolver accessResolver;
    @Mock GoalRepository goalRepository;
    @Mock WealthPyramidService pyramidService;

    @InjectMocks ProjectionService service;

    private final List<Account> accounts = new ArrayList<>();
    private final Map<Long, BigDecimal> shares = new HashMap<>();
    private final List<Goal> goals = new ArrayList<>();
    private final Map<WealthTier, BigDecimal> mix = new EnumMap<>(WealthTier.class);
    private final Map<WealthTier, BigDecimal> targets = new EnumMap<>(WealthTier.class);
    private long nextId = 1;

    @BeforeEach
    void wireDefaults() {
        lenient().when(accessResolver.readableAccounts(MEMBER)).thenReturn(accounts);
        lenient().when(accessResolver.sharesFor(any(), any())).thenReturn(shares);
        lenient().when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER)).thenReturn(goals);
        lenient().when(pyramidService.pyramid(MEMBER)).thenAnswer(inv -> pyramid());
    }

    /**
     * The starting split now comes from the pyramid rather than from account types, so the two
     * panels of the same screen cannot disagree about the same euro.
     */
    private WealthPyramidResponse pyramid() {
        List<WealthPyramidResponse.TierLine> lines = new ArrayList<>();
        for (WealthTier tier : List.of(WealthTier.REAL_ESTATE, WealthTier.EQUITY,
                                       WealthTier.CRYPTO, WealthTier.ALTERNATIVE)) {
            lines.add(new WealthPyramidResponse.TierLine(
                tier, mix.getOrDefault(tier, BigDecimal.ZERO),
                BigDecimal.ZERO, targets.getOrDefault(tier, BigDecimal.ZERO),
                BigDecimal.ZERO, BigDecimal.ZERO, List.of()));
        }
        return new WealthPyramidResponse(
            BigDecimal.ZERO, BigDecimal.ZERO,
            new WealthPyramidResponse.SafetyNet(
                mix.getOrDefault(WealthTier.SAFETY_NET, BigDecimal.ZERO),
                BigDecimal.ZERO, null, null, BigDecimal.ZERO, false, null),
            lines,
            new WealthPyramidResponse.Score(null, null, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null),
            List.of());
    }

    private void holds(WealthTier tier, String value) {
        mix.put(tier, new BigDecimal(value));
    }

    private void target(WealthTier tier, String percent) {
        targets.put(tier, new BigDecimal(percent));
    }

    private Account account(AccountType type, String share) {
        Account account = Account.builder()
            .id(nextId++).name(type.name()).type(type).currency("EUR").color("#000").build();
        accounts.add(account);
        shares.put(account.getId(), new BigDecimal(share));
        return account;
    }

    private void plan(String monthly, AccountType into, String expectedReturn,
                      LocalDate start, LocalDate end) {
        goals.add(Goal.builder()
            .id(nextId++).name("Plan").type(GoalType.RECURRING_INVESTMENT)
            .monthlyAmount(new BigDecimal(monthly))
            .expectedReturn(expectedReturn == null ? null : new BigDecimal(expectedReturn))
            .startDate(start).endDate(end)
            .accounts(new ArrayList<>(List.of(account(into, "100"))))
            .build());
    }

    private void plan(String monthly, AccountType into) {
        plan(monthly, into, null, null, null);
    }

    private static ProjectionResponse.Scenario scenario(ProjectionResponse r, String key) {
        return r.scenarios().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    private static BigDecimal shareOf(ProjectionResponse.AllocationPoint p, WealthTier tier) {
        return p.tiers().stream().filter(s -> s.tier() == tier).findFirst().orElseThrow().percent();
    }

    // --- the headline curve ---------------------------------------------------

    @Test
    void theBaseIsTheInvestablePortfolioOnly() {
        holds(WealthTier.EQUITY, "50000");
        holds(WealthTier.CRYPTO, "10000");
        holds(WealthTier.SAFETY_NET, "6000");
        // Property does not compound at an equity rate; alternatives have no defensible one.
        holds(WealthTier.REAL_ESTATE, "300000");
        holds(WealthTier.ALTERNATIVE, "5000");

        assertThat(service.project(MEMBER, 10).baseValueEur()).isEqualByComparingTo("66000.00");
    }

    @Test
    void currentAccountMoneyIsNotCompoundedForFortyYears() {
        // The pyramid carves current accounts out as this month's spending. The projection used
        // to put them in SAFETY_NET and grow them anyway, so the same euro was excluded from one
        // panel and compounded in the other.
        holds(WealthTier.EQUITY, "10000");

        assertThat(service.project(MEMBER, 10).baseValueEur()).isEqualByComparingTo("10000.00");
    }

    @Test
    void usesTheGeometricMonthlyRateSoTheLabelStaysTrue() {
        // 7.5% a year compounded monthly must reach exactly x1.075 after twelve months. Dividing
        // by twelve instead reaches 7.76%, overstating the first year and far more over twenty.
        holds(WealthTier.EQUITY, "10000");

        List<ProjectionResponse.Point> points = scenario(service.project(MEMBER, 1), "REFERENCE").points();

        assertThat(points).hasSize(2);
        assertThat(points.get(1).valueEur().doubleValue()).isCloseTo(10750.0, within(0.5));
    }

    @Test
    void aPassbookPlanIsNotCompoundedAtAnEquityRate() {
        // The reason to read the plan's account at all. 250/month into a Livret A used to grow at
        // whatever the curve was labelled; the member had even typed 1.7% against it.
        plan("250", AccountType.LIVRET_A, "1.7", null, null);

        ProjectionResponse response = service.project(MEMBER, 10);
        BigDecimal atTen = scenario(response, "OPTIMISTIC").points().getLast().valueEur();

        // 120 payments of 250 is 30 000 of capital. At 1.7% it lands a little above; at the
        // optimistic equity rate it would be past 45 000.
        assertThat(atTen).isLessThan(new BigDecimal("34000"));
        assertThat(atTen).isGreaterThan(new BigDecimal("30000"));
    }

    @Test
    void aStatedRateActuallyChangesTheResult() {
        // The bug this pot engine exists for. The rate was read, stored on the plan, reported in
        // the label -- and then never used: the money grew at the tier's rate the moment it
        // landed there, so 1.7% and the passbook default of 2.0% produced the identical curve.
        plan("250", AccountType.LIVRET_A, "1.7", null, null);
        BigDecimal stated = scenario(service.project(MEMBER, 10), "REFERENCE").points().getLast().valueEur();

        goals.clear();
        plan("250", AccountType.LIVRET_A, null, null, null);
        BigDecimal defaulted = scenario(service.project(MEMBER, 10), "REFERENCE").points().getLast().valueEur();

        assertThat(stated).isLessThan(defaulted);
    }

    @Test
    void aStatedRateOnCashIsTheSameOnAllFourCurves() {
        // Contractual, not an expectation: a Livret A pays what it pays whatever the year does.
        plan("250", AccountType.LIVRET_A, "1.7", null, null);

        ProjectionResponse response = service.project(MEMBER, 10);

        assertThat(scenario(response, "PESSIMISTIC").points().getLast().valueEur())
            .isEqualByComparingTo(scenario(response, "OPTIMISTIC").points().getLast().valueEur());
    }

    @Test
    void aStatedRateOnEquityStillMovesWithTheScenario() {
        // The other half of the rule, and the reason no extra field is needed to express it: 8%
        // typed against a PEA is a hope, and the scenarios are what put a range around a hope.
        plan("300", AccountType.PEA, "8", null, null);

        ProjectionResponse response = service.project(MEMBER, 10);

        assertThat(scenario(response, "OPTIMISTIC").points().getLast().valueEur())
            .isGreaterThan(scenario(response, "PESSIMISTIC").points().getLast().valueEur());
        // 8 + 2.5, not the tier's 7.5 + 2.5: the spread lands on the member's figure.
        assertThat(scenario(response, "OPTIMISTIC").annualPercent()).isEqualByComparingTo("10.50");
    }

    @Test
    void theStatedRateSurvivesInTheAllocationTrajectoryToo() {
        // The mix is a second engine over the same pots; it read the tier rate as well.
        plan("250", AccountType.LIVRET_A, "0", null, null);
        plan("250", AccountType.PEA, null, null, null);

        List<ProjectionResponse.AllocationPoint> points = service.project(MEMBER, 10).allocation();

        // 30 000 paid into each, but only the equity plan compounds, so it must end ahead. At the
        // cushion's default 2% the passbook would have grown too, and the gap would be smaller.
        BigDecimal cushion = points.getLast().tiers().stream()
            .filter(s -> s.tier() == WealthTier.SAFETY_NET).findFirst().orElseThrow().valueEur();
        assertThat(cushion).isEqualByComparingTo("30000.00");
    }

    @Test
    void theReportedRateFollowsThePlansOfAMemberWhoAlreadyHolds() {
        // The label used to be a picture of the portfolio alone, so a member holding 10 000 in
        // equities and pouring 500 a month into a Livret A was still labelled 7.5%.
        holds(WealthTier.EQUITY, "10000");
        plan("500", AccountType.LIVRET_A, "1.7", null, null);

        BigDecimal reference = scenario(service.project(MEMBER, 10), "REFERENCE").annualPercent();

        // 10 000 at 7.5% against 60 000 paid in at 1.7%.
        assertThat(reference.doubleValue()).isCloseTo(2.53, within(0.02));
    }

    @Test
    void theSpreadDoesNotTouchTheCushion() {
        // A Livret A does not have a good year. Only risky tiers move between scenarios.
        holds(WealthTier.SAFETY_NET, "10000");

        ProjectionResponse response = service.project(MEMBER, 10);

        assertThat(scenario(response, "PESSIMISTIC").points().getLast().valueEur())
            .isEqualByComparingTo(scenario(response, "OPTIMISTIC").points().getLast().valueEur());
    }

    @Test
    void theReportedRateIsWhatTheMoneyActuallyEarns() {
        // A member whose wealth is half cash is not on a 10% curve however the line is labelled.
        holds(WealthTier.EQUITY, "10000");
        holds(WealthTier.SAFETY_NET, "10000");

        BigDecimal optimistic = scenario(service.project(MEMBER, 10), "OPTIMISTIC").annualPercent();

        // (7.5 + 2.5) and 2.0, evenly weighted.
        assertThat(optimistic).isEqualByComparingTo("6.00");
    }

    @Test
    void contributionsLandAtTheEndOfTheMonth() {
        // At the start of the month the first payment would earn growth it never saw, and that
        // error compounds across the whole horizon.
        plan("100", AccountType.PEA);

        List<ProjectionResponse.Point> points = scenario(service.project(MEMBER, 1), "REFERENCE").points();

        // Twelve payments of 100, the last of which earns nothing.
        assertThat(points.getLast().valueEur().doubleValue()).isLessThan(1241.0);
        assertThat(points.getLast().contributedEur()).isEqualByComparingTo("1200.00");
    }

    @Test
    void aPlanOutsideItsWindowContributesNothing() {
        plan("500", AccountType.PEA, null, LocalDate.now().plusYears(50), null);

        assertThat(scenario(service.project(MEMBER, 10), "REFERENCE").points().getLast().valueEur())
            .isEqualByComparingTo("0.00");
    }

    @Test
    void savingsTargetGoalsAreNotContributions() {
        goals.add(Goal.builder()
            .id(99L).name("Trip").type(GoalType.SAVINGS_TARGET)
            .targetAmount(new BigDecimal("5000")).deadline(LocalDate.now().plusYears(2))
            .accounts(new ArrayList<>()).build());

        assertThat(service.project(MEMBER, 5).monthlyInflowEur()).isEqualByComparingTo("0.00");
    }

    @Test
    void aContributionIsWeightedLikeTheBase() {
        // A plan funding a half-owned joint account used to add its whole amount on top of a base
        // that only counted half the account.
        Account joint = account(AccountType.PEA, "50");
        goals.add(Goal.builder()
            .id(nextId++).name("Joint").type(GoalType.RECURRING_INVESTMENT)
            .monthlyAmount(new BigDecimal("400"))
            .accounts(new ArrayList<>(List.of(joint))).build());

        assertThat(service.project(MEMBER, 5).monthlyInflowEur()).isEqualByComparingTo("200.00");
    }

    // --- the allocation trajectory --------------------------------------------

    @Test
    void theSharesAlwaysSumToOneHundred() {
        holds(WealthTier.REAL_ESTATE, "189351");
        holds(WealthTier.EQUITY, "42159");
        holds(WealthTier.CRYPTO, "7667");
        holds(WealthTier.SAFETY_NET, "16101");
        plan("700", AccountType.PEA);

        for (ProjectionResponse.AllocationPoint p : service.project(MEMBER, 10).allocation()) {
            BigDecimal total = p.tiers().stream()
                .map(ProjectionResponse.TierShare::percent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(total.doubleValue()).isCloseTo(100.0, within(0.05));
        }
    }

    @Test
    void aPortfolioWithNoPlansAndNoGrowthKeepsItsMix() {
        holds(WealthTier.REAL_ESTATE, "100000");
        holds(WealthTier.ALTERNATIVE, "100000");

        List<ProjectionResponse.AllocationPoint> points = service.project(MEMBER, 10).allocation();

        assertThat(shareOf(points.getFirst(), WealthTier.REAL_ESTATE)).isEqualByComparingTo("50.00");
        assertThat(shareOf(points.getLast(), WealthTier.REAL_ESTATE)).isEqualByComparingTo("50.00");
    }

    @Test
    void thePlansMoveTheMixAndTheTargetTravelsWithIt() {
        // The join the two panels never had: the pyramid knew today's gap, the curve knew
        // tomorrow's total, and neither could say whether the plans close the gap or widen it.
        holds(WealthTier.REAL_ESTATE, "189351");
        holds(WealthTier.EQUITY, "42159");
        target(WealthTier.REAL_ESTATE, "75");
        target(WealthTier.EQUITY, "18");
        plan("700", AccountType.PEA);

        List<ProjectionResponse.AllocationPoint> points = service.project(MEMBER, 10).allocation();

        // Property earns nothing and receives nothing, so its share falls as equity is fed.
        assertThat(shareOf(points.getLast(), WealthTier.REAL_ESTATE))
            .isLessThan(shareOf(points.getFirst(), WealthTier.REAL_ESTATE));
        assertThat(shareOf(points.getLast(), WealthTier.EQUITY))
            .isGreaterThan(shareOf(points.getFirst(), WealthTier.EQUITY));
        assertThat(points.getLast().tiers().stream()
            .filter(s -> s.tier() == WealthTier.REAL_ESTATE).findFirst().orElseThrow()
            .targetPercent()).isEqualByComparingTo("75");
    }

    @Test
    void aTierNothingFundsStaysAtZero() {
        // The observation worth acting on: no plan feeds alternatives, so no horizon reaches the
        // target however long it is.
        holds(WealthTier.EQUITY, "50000");
        target(WealthTier.ALTERNATIVE, "2");
        plan("700", AccountType.PEA);

        assertThat(shareOf(service.project(MEMBER, 40).allocation().getLast(), WealthTier.ALTERNATIVE))
            .isEqualByComparingTo("0.00");
    }

    @Test
    void anEmptyPortfolioWithNoPlansStaysFlatAtZero() {
        ProjectionResponse response = service.project(MEMBER, 10);

        assertThat(response.baseValueEur()).isEqualByComparingTo("0.00");
        assertThat(scenario(response, "REFERENCE").points().getLast().valueEur())
            .isEqualByComparingTo("0.00");
    }

    @Test
    void allFourScenariosCoverTheSameHorizon() {
        holds(WealthTier.EQUITY, "10000");

        ProjectionResponse response = service.project(MEMBER, 20);

        assertThat(response.scenarios()).hasSize(4);
        assertThat(response.scenarios()).allSatisfy(s -> assertThat(s.points()).hasSize(21));
        assertThat(response.allocation()).hasSize(21);
    }

    @Test
    void aHigherAssumptionAlwaysEndsHigher() {
        holds(WealthTier.EQUITY, "10000");
        plan("200", AccountType.PEA);

        ProjectionResponse response = service.project(MEMBER, 20);

        assertThat(scenario(response, "OPTIMISTIC").points().getLast().valueEur())
            .isGreaterThan(scenario(response, "REFERENCE").points().getLast().valueEur());
        assertThat(scenario(response, "REFERENCE").points().getLast().valueEur())
            .isGreaterThan(scenario(response, "PESSIMISTIC").points().getLast().valueEur());
    }
}
