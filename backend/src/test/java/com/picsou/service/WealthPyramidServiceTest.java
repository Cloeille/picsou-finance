package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.dto.HoldingResponse;
import com.picsou.dto.RealEstateSummaryResponse;
import com.picsou.dto.WealthPyramidResponse;
import com.picsou.model.*;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.HoldingClassificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WealthPyramidServiceTest {

    private static final Long MEMBER = 1L;

    @Mock AccountAccessResolver accessResolver;
    @Mock AccountService accountService;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock HoldingClassificationRepository classificationRepository;
    @Mock AllocationTargetService allocationTargetService;
    @Mock RealEstateSummaryService realEstateSummaryService;
    @Mock CoinGeckoPriceProvider coinGecko;

    @InjectMocks WealthPyramidService service;

    private final List<Account> accounts = new java.util.ArrayList<>();
    private final Map<Long, BigDecimal> shares = new HashMap<>();
    private long nextId = 1;

    @BeforeEach
    void wireDefaults() {
        lenient().when(accessResolver.readableAccounts(MEMBER)).thenReturn(accounts);
        lenient().when(accessResolver.sharesFor(any(), any())).thenReturn(shares);
        lenient().when(classificationRepository.findByMemberId(MEMBER)).thenReturn(List.of());
        lenient().when(allocationTargetService.profileFor(MEMBER))
            .thenReturn(MemberAllocationProfile.builder().build());
        lenient().when(realEstateSummaryService.summarize(MEMBER)).thenReturn(noProperty());
        // The service asks only whether an account holds anything; the lines themselves come back
        // through AccountService.getHoldings.
        lenient().when(holdingRepository.existsForReadableAccount(anyLong(), anyLong()))
            .thenReturn(false);
        lenient().when(coinGecko.supports(any())).thenReturn(false);
    }

    private static RealEstateSummaryResponse noProperty() {
        return new RealEstateSummaryResponse(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, List.of());
    }

    /** A balance-only account worth {@code value}, wholly owned. */
    private Account cash(AccountType type, String value) {
        Account account = Account.builder()
            .id(nextId++).name(type.name()).type(type).currency("EUR").color("#000000")
            .currentBalance(new BigDecimal(value)).build();
        accounts.add(account);
        // The real sharesFor returns an explicit 100 for a wholly-owned account; weigh() treats a
        // null share as "worth nothing to you", so a null here would silently zero every fixture.
        shares.put(account.getId(), new BigDecimal("100"));
        lenient().when(accountService.valuation(account)).thenReturn(
            new AccountService.Valuation(new BigDecimal(value), new BigDecimal(value), true, true, false));
        return account;
    }

    /** An account holding positions; {@code lines} are ticker/value pairs summing to {@code value}. */
    private Account withHoldings(AccountType type, String value, Map<String, String> lines) {
        Account account = cash(type, value);
        lenient().when(holdingRepository.existsForReadableAccount(account.getId(), MEMBER))
            .thenReturn(true);
        lenient().when(accountService.getHoldings(account.getId(), MEMBER)).thenReturn(
            lines.entrySet().stream().map(e -> new HoldingResponse(
                e.getKey(), e.getKey(), BigDecimal.ONE, null, null, "EUR",
                new BigDecimal(e.getValue()), null, null, null, null, null, false)).toList());
        return account;
    }

    private WealthPyramidResponse.TierLine tier(WealthPyramidResponse response, WealthTier tier) {
        return response.tiers().stream().filter(l -> l.tier() == tier).findFirst().orElseThrow();
    }

    private boolean hasTier(WealthPyramidResponse response, WealthTier tier) {
        return response.tiers().stream().anyMatch(l -> l.tier() == tier);
    }

    private void expenses(String monthly) {
        when(allocationTargetService.profileFor(MEMBER)).thenReturn(
            MemberAllocationProfile.builder().monthlyEssentialExpenses(new BigDecimal(monthly)).build());
    }

    private void cushion(String monthly, int months) {
        when(allocationTargetService.profileFor(MEMBER)).thenReturn(MemberAllocationProfile.builder()
            .monthlyEssentialExpenses(new BigDecimal(monthly))
            .safetyNetMonths((short) months)
            .build());
    }

    private void targets(String monthly, int months, String realEstate, String equity,
                         String crypto, String alternative) {
        when(allocationTargetService.profileFor(MEMBER)).thenReturn(MemberAllocationProfile.builder()
            .monthlyEssentialExpenses(new BigDecimal(monthly))
            .safetyNetMonths((short) months)
            .realEstatePct(new BigDecimal(realEstate))
            .equityPct(new BigDecimal(equity))
            .cryptoPct(new BigDecimal(crypto))
            .alternativePct(new BigDecimal(alternative))
            .build());
    }

    // --- Classification ---

    @Test
    void accountsLandInTheirTypesTier() {
        cash(AccountType.LIVRET_A, "5000");
        cash(AccountType.PEA, "20000");
        cash(AccountType.CRYPTO, "3000");
        cash(AccountType.OTHER, "2000");

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.totalAssetsEur()).isEqualByComparingTo("30000");
        assertThat(response.safetyNet().valueEur()).isEqualByComparingTo("5000");
        assertThat(tier(response, WealthTier.EQUITY).valueEur()).isEqualByComparingTo("20000");
        assertThat(tier(response, WealthTier.CRYPTO).valueEur()).isEqualByComparingTo("3000");
        assertThat(tier(response, WealthTier.ALTERNATIVE).valueEur()).isEqualByComparingTo("2000");
    }

    @Test
    void onlySavingsPassbooksCountAsTheCushion() {
        // A current account is where this month's money passes through, not what stands between
        // the member and a bad month.
        cash(AccountType.LIVRET_A, "5000");
        cash(AccountType.CHECKING, "3000");

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.safetyNet().valueEur()).isEqualByComparingTo("5000");
        assertThat(response.safetyNet().dailyCashEur()).isEqualByComparingTo("3000");
        // Reported, so the money is visible somewhere — but it is still part of what the member owns.
        assertThat(response.totalAssetsEur()).isEqualByComparingTo("8000");
    }

    @Test
    void currentAccountCashIsOutsideTheAllocationToo() {
        cash(AccountType.CHECKING, "4000");
        cash(AccountType.LIVRET_A, "6000");
        cash(AccountType.PEA, "10000");

        WealthPyramidResponse response = service.pyramid(MEMBER);

        // Neither cushion nor investment: it divides nothing.
        assertThat(response.allocatableEur()).isEqualByComparingTo("10000");
        assertThat(tier(response, WealthTier.EQUITY).actualPercent()).isEqualByComparingTo("100");
    }

    @Test
    void theSafetyNetIsNotOneOfTheAllocationLines() {
        // It is measured in euros against an absolute target; a second line expressing the same
        // money as a share of something else read as a contradiction.
        cash(AccountType.LIVRET_A, "6000");
        cash(AccountType.PEA, "10000");

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(hasTier(response, WealthTier.SAFETY_NET)).isFalse();
        assertThat(response.tiers()).hasSize(4);
    }

    @Test
    void eachLineCarriesItsTargetInEurosNotJustInPoints() {
        expenses("1000");                        // cushion target 6000
        cash(AccountType.LIVRET_A, "6000");
        cash(AccountType.PEA, "100000");         // allocatable = 100000, equity target 50%

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(tier(response, WealthTier.EQUITY).targetEur()).isEqualByComparingTo("50000.00");
        assertThat(tier(response, WealthTier.REAL_ESTATE).targetEur()).isEqualByComparingTo("30000.00");
    }

    @Test
    void aLoanIsNeverCountedAsAnAsset() {
        cash(AccountType.LIVRET_A, "5000");
        cash(AccountType.LOAN, "150000");

        assertThat(service.pyramid(MEMBER).totalAssetsEur()).isEqualByComparingTo("5000");
    }

    @Test
    void aCryptoLineInsideABrokerageAccountCountsAsCrypto() {
        // The wrapper does not determine the asset. A bitcoin ETP in a CTO is crypto exposure,
        // and calling it listed equity would misstate two tiers at once.
        withHoldings(AccountType.COMPTE_TITRES, "10000", Map.of("BTC", "4000", "CW8", "6000"));
        when(coinGecko.supports("BTC")).thenReturn(true);

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(tier(response, WealthTier.CRYPTO).valueEur()).isEqualByComparingTo("4000");
        assertThat(tier(response, WealthTier.EQUITY).valueEur()).isEqualByComparingTo("6000");
    }

    @Test
    void aManualOverrideBeatsBothTheAccountTypeAndTheCoinLookup() {
        withHoldings(AccountType.COMPTE_TITRES, "10000", Map.of("GOLD", "10000"));
        when(classificationRepository.findByMemberId(MEMBER)).thenReturn(List.of(
            HoldingClassification.builder().ticker("gold").wealthTier(WealthTier.ALTERNATIVE).build()));

        WealthPyramidResponse response = service.pyramid(MEMBER);

        // Matched case-insensitively: the user types the ticker, the sync stores it uppercased.
        assertThat(tier(response, WealthTier.ALTERNATIVE).valueEur()).isEqualByComparingTo("10000");
        assertThat(tier(response, WealthTier.EQUITY).valueEur()).isEqualByComparingTo("0");
    }

    @Test
    void cashInsideAnEnvelopeStaysWithItsAccountTier() {
        // A life-insurance euro fund is not a line: without the residual it would vanish from the
        // pyramid while still counting in the dashboard's net worth.
        withHoldings(AccountType.ASSURANCE_VIE, "50000", Map.of("CW8", "30000"));

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.totalAssetsEur()).isEqualByComparingTo("50000");
        assertThat(tier(response, WealthTier.EQUITY).valueEur()).isEqualByComparingTo("50000");
    }

    @Test
    void sharesAreAppliedExactlyOnce() {
        Account joint = cash(AccountType.LIVRET_A, "10000");
        shares.put(joint.getId(), new BigDecimal("50"));

        assertThat(service.pyramid(MEMBER).totalAssetsEur()).isEqualByComparingTo("5000");
    }

    @Test
    void propertyEntersNetOfTheMortgageFinancingIt() {
        cash(AccountType.REAL_ESTATE, "300000");
        when(realEstateSummaryService.summarize(MEMBER)).thenReturn(new RealEstateSummaryResponse(
            new BigDecimal("300000"), new BigDecimal("120000"), new BigDecimal("180000"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("40"),
            BigDecimal.ZERO, List.of()));

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.totalAssetsEur()).isEqualByComparingTo("180000");
        assertThat(tier(response, WealthTier.REAL_ESTATE).valueEur()).isEqualByComparingTo("180000");
    }

    // --- Safety net ---

    @Test
    void withoutStatedExpensesTheSafetyNetIsUnratedRatherThanZero() {
        // Scoring someone 0/100 because they have not filled in a form is a lie, and it is the
        // kind that makes people stop believing the page.
        cash(AccountType.LIVRET_A, "5000");
        cash(AccountType.PEA, "50000");

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.safetyNet().known()).isFalse();
        assertThat(response.safetyNet().score()).isNull();
        assertThat(response.safetyNet().targetEur()).isNull();
        assertThat(response.score().global()).isGreaterThan(0);
    }

    @Test
    void anUnderfundedCushionScoresInProportion() {
        expenses("1000");                       // target = 6000
        cash(AccountType.LIVRET_A, "3000");     // coverage = 0.5

        assertThat(service.pyramid(MEMBER).safetyNet().score()).isEqualTo(50);
    }

    @Test
    void aCoveredCushionScoresFull() {
        expenses("1000");
        cash(AccountType.LIVRET_A, "6000");

        WealthPyramidResponse response = service.pyramid(MEMBER);
        assertThat(response.safetyNet().score()).isEqualTo(100);
        assertThat(response.safetyNet().excessEur()).isEqualByComparingTo("0");
    }

    @Test
    void anOverfundedCushionFloorsAtSixtyRatherThanCollapsing() {
        expenses("1000");
        cash(AccountType.LIVRET_A, "60000");    // coverage = 10, far past the saturation point

        assertThat(service.pyramid(MEMBER).safetyNet().score()).isEqualTo(60);
    }

    @Test
    void cushionBeyondTheTargetIsReportedButNotAllocated() {
        expenses("1000");                        // target = 6000
        cash(AccountType.LIVRET_A, "10000");     // 4000 of it is idle
        cash(AccountType.PEA, "6000");

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.safetyNet().excessEur()).isEqualByComparingTo("4000");
        // The whole cushion sits outside the allocation, excess included: only the four
        // investment tiers divide what is left.
        assertThat(response.allocatableEur()).isEqualByComparingTo("6000");
        assertThat(hasTier(response, WealthTier.SAFETY_NET)).isFalse();
    }

    // --- Scoring ---

    @Test
    void aPortfolioOnItsTargetsScoresOneHundred() {
        expenses("1000");
        cash(AccountType.LIVRET_A, "6000");         // exactly covered
        cash(AccountType.REAL_ESTATE, "30000");     // 30%
        cash(AccountType.PEA, "50000");             // 50%
        cash(AccountType.CRYPTO, "10000");          // 10%
        cash(AccountType.OTHER, "10000");           // 10%

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.score().allocation()).isEqualTo(100);
        assertThat(response.score().misplacedPercent()).isEqualByComparingTo("0.00");
        assertThat(response.score().global()).isEqualTo(100);
    }

    @Test
    void misplacedIsTheShareOfWealthThatWouldHaveToMove() {
        expenses("1000");
        cash(AccountType.LIVRET_A, "6000");
        // Everything in equity: 50 points of it are where they belong, 50 are not.
        cash(AccountType.PEA, "100000");

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.score().misplacedPercent()).isEqualByComparingTo("50.00");
        assertThat(response.score().allocation()).isEqualTo(50);
    }

    @Test
    void theCryptoPenaltyScalesWithHowMuchCryptoWeighs() {
        expenses("1000");
        cash(AccountType.LIVRET_A, "6000");
        cash(AccountType.PEA, "90000");
        // A small sleeve of nothing but minor coins: real, but a rounding error in the wealth.
        withHoldings(AccountType.CRYPTO, "10000", Map.of("SHIB", "10000"));

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.score().cryptoTopTenShare()).isEqualByComparingTo("0.00");
        // 10 x 10% weight x full shortfall = 1 point.
        assertThat(response.score().cryptoPenalty()).isEqualByComparingTo("1.00");
    }

    @Test
    void majorsAboveTheFloorDrawNoCryptoPenalty() {
        expenses("1000");
        cash(AccountType.LIVRET_A, "6000");
        withHoldings(AccountType.CRYPTO, "10000", Map.of("BTC", "9000", "SHIB", "1000"));
        when(coinGecko.supports(any())).thenReturn(true);

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.score().cryptoTopTenShare()).isEqualByComparingTo("90.00");
        assertThat(response.score().cryptoPenalty()).isEqualByComparingTo("0.00");
    }

    @Test
    void aCryptoAccountWithNoPerCoinDetailIsNotPenalised() {
        // An exchange tracked as a single balance tells us nothing about its composition.
        // Scoring that silence as "holds no majors" would punish a portfolio for a breakdown
        // the connector never sent.
        expenses("1000");
        cash(AccountType.LIVRET_A, "6000");
        cash(AccountType.REAL_ESTATE, "30000");
        cash(AccountType.PEA, "50000");
        cash(AccountType.CRYPTO, "10000");
        cash(AccountType.OTHER, "10000");

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.score().cryptoTopTenShare()).isNull();
        assertThat(response.score().cryptoPenalty()).isEqualByComparingTo("0.00");
        assertThat(response.score().global()).isEqualTo(100);
    }

    @Test
    void theLeverageBonusPeaksInTheMiddleAndVanishesWhenFullyMortgaged() {
        assertThat(leverageBonusAt("60")).isEqualByComparingTo("5.00");
        assertThat(leverageBonusAt("85")).isEqualByComparingTo("5.00");
        assertThat(leverageBonusAt("30")).isEqualByComparingTo("2.50");
        // A property mortgaged to the hilt is not a better position than a well-leveraged one.
        assertThat(leverageBonusAt("100")).isEqualByComparingTo("0.00");
    }

    private BigDecimal leverageBonusAt(String ltv) {
        accounts.clear();
        shares.clear();
        cash(AccountType.REAL_ESTATE, "100000");
        when(realEstateSummaryService.summarize(MEMBER)).thenReturn(new RealEstateSummaryResponse(
            new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("100000"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(ltv),
            BigDecimal.ZERO, List.of()));
        return service.pyramid(MEMBER).score().leverageBonus();
    }

    @Test
    void anEmptyPortfolioDoesNotDivideByZero() {
        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.totalAssetsEur()).isEqualByComparingTo("0");
        assertThat(response.allocatableEur()).isEqualByComparingTo("0");
        // And has no score rather than a flattering one: nothing to allocate and no stated
        // expenses means there is nothing to judge. It used to return 100.
        assertThat(response.score().global()).isNull();
        assertThat(response.score().allocation()).isNull();
    }

    @Test
    void everythingInCashIsNotScoredAsAPerfectAllocation() {
        // The single biggest lie the formula told. With nothing allocatable the allocation score
        // was 100, so an all-cash portfolio scored 84 while a textbook one with a slightly short
        // cushion scored 88.
        cash(AccountType.LIVRET_A, "200000");
        cushion("1500", 6);

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.score().allocation()).isNull();
        // Now the cushion carries it alone, and 185 000 EUR idle out of 200 000 is what the score
        // reflects rather than a coverage ratio that saturates at three months' worth.
        assertThat(response.score().global()).isLessThan(70);
    }

    @Test
    void leavingTheExpensesBlankNoLongerBuysAPerfectScore() {
        // The perverse incentive: no expenses meant no safety term, and with nothing allocatable
        // the surviving allocation term was an invented 100. Stating your expenses could only
        // lower your score.
        cash(AccountType.CHECKING, "120000");

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.score().global()).isNull();
        assertThat(response.score().allocation()).isNull();
        assertThat(response.safetyNet().known()).isFalse();
    }

    @Test
    void aModestCushionSurplusIsNotPunishedLikeIdleWealth() {
        // Scored against the coverage ratio alone, a cushion 7 % over target lost points at the
        // same rate as one thirty times over. What costs a member is the share of the whole
        // patrimoine sitting idle, and 1 101 EUR on 265 000 is a rounding error.
        cash(AccountType.LIVRET_A, "16101");
        cash(AccountType.REAL_ESTATE, "249085");
        cushion("1500", 10);

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.safetyNet().excessEur()).isEqualByComparingTo("1101.00");
        assertThat(response.safetyNet().score()).isGreaterThanOrEqualTo(99);
    }

    @Test
    void oneAssetCarryingMostOfThePatrimoineIsNamedWhateverTheTargets() {
        // The observation a target-based score cannot make: a member who wants 75 % property
        // scores perfectly on 75 % property, whether that is one flat or a dozen.
        cash(AccountType.REAL_ESTATE, "189351");
        cash(AccountType.PEA, "42159");
        cash(AccountType.LIVRET_A, "16101");
        targets("1500", 10, "75", "18", "5", "2");

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.alerts()).extracting(WealthPyramidResponse.Alert::code)
            .contains("SINGLE_ASSET_CONCENTRATION");
        WealthPyramidResponse.Alert concentration = response.alerts().stream()
            .filter(a -> a.code().equals("SINGLE_ASSET_CONCENTRATION")).findFirst().orElseThrow();
        assertThat(concentration.percent()).isGreaterThan(new BigDecimal("70"));
        // And the score still says the allocation is nearly perfect, which is exactly why the
        // alert has to exist separately.
        assertThat(response.score().allocation()).isGreaterThan(90);
    }

    @Test
    void aTierAtZeroIsCalledOutRatherThanDilutedIntoAFewPoints() {
        cash(AccountType.REAL_ESTATE, "100000");
        cushion("1000", 6);

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.alerts()).extracting(WealthPyramidResponse.Alert::code)
            .contains("EMPTY_TIER");
        assertThat(response.alerts()).extracting(WealthPyramidResponse.Alert::label)
            .contains("EQUITY", "CRYPTO", "ALTERNATIVE");
    }

    @Test
    void aBarelyOverfundedCushionRaisesNoAlert() {
        // An alert that fires on noise stops being read.
        cash(AccountType.LIVRET_A, "16101");
        cash(AccountType.REAL_ESTATE, "249085");
        cushion("1500", 10);

        WealthPyramidResponse response = service.pyramid(MEMBER);

        assertThat(response.alerts()).extracting(WealthPyramidResponse.Alert::code)
            .doesNotContain("CUSHION_OVERFUNDED");
    }
}
