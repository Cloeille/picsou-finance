package com.picsou.service;

import com.picsou.dto.DiversificationResponse;
import com.picsou.dto.HoldingResponse;
import com.picsou.model.*;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.HoldingClassificationRepository;
import org.junit.jupiter.api.BeforeEach;
import java.util.Set;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioDiversificationServiceTest {

    private static final Long MEMBER = 1L;

    @Mock AccountAccessResolver accessResolver;
    @Mock AccountService accountService;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock HoldingClassificationRepository classificationRepository;
    @Mock SecurityProfileService securityProfileService;

    @InjectMocks PortfolioDiversificationService service;

    private final List<Account> accounts = new ArrayList<>();
    private final Map<Long, BigDecimal> shares = new HashMap<>();
    private final Map<String, SecurityProfile> profiles = new HashMap<>();
    private long nextId = 1;

    @BeforeEach
    void wireDefaults() {
        lenient().when(accessResolver.readableAccounts(MEMBER)).thenReturn(accounts);
        lenient().when(accessResolver.sharesFor(any(), any())).thenReturn(shares);
        lenient().when(classificationRepository.findByMemberId(MEMBER)).thenReturn(List.of());
        lenient().when(securityProfileService.load(any())).thenReturn(profiles);
    }

    private Account equityAccount(Map<String, String> lines) {
        Account account = Account.builder()
            .id(nextId++).name("PEA").type(AccountType.PEA).currency("EUR").color("#000")
            .build();
        accounts.add(account);
        shares.put(account.getId(), new BigDecimal("100"));
        lenient().when(holdingRepository.existsForReadableAccount(account.getId(), MEMBER))
            .thenReturn(true);
        lenient().when(accountService.getHoldings(account.getId(), MEMBER)).thenReturn(
            lines.entrySet().stream().map(e -> new HoldingResponse(
                e.getKey(), e.getKey(), BigDecimal.ONE, null, null, "EUR",
                new BigDecimal(e.getValue()), null, null, null, null, null, false)).toList());
        return account;
    }

    private void stockProfile(String ticker, String sector, String country) {
        profiles.put(ticker, SecurityProfile.builder()
            .ticker(ticker).assetType("STOCK").sectorKey(sector).countryKey(country)
            .refreshedAt(Instant.now()).status(SecurityProfileStatus.OK)
            .slices(new ArrayList<>()).build());
    }

    private void etfProfile(String ticker, Map<String, String> sectors, Map<String, String> countries) {
        List<SecurityCompositionSlice> slices = new ArrayList<>();
        sectors.forEach((label, pct) -> slices.add(SecurityCompositionSlice.builder()
            .kind(SecuritySliceKind.SECTOR).label(label).percent(new BigDecimal(pct)).build()));
        countries.forEach((label, pct) -> slices.add(SecurityCompositionSlice.builder()
            .kind(SecuritySliceKind.COUNTRY).label(label).percent(new BigDecimal(pct)).build()));
        profiles.put(ticker, SecurityProfile.builder()
            .ticker(ticker).assetType("ETF").refreshedAt(Instant.now())
            .status(SecurityProfileStatus.OK).slices(slices).build());
    }

    private static BigDecimal sliceOf(DiversificationResponse.Breakdown b, String label) {
        return b.slices().stream().filter(s -> s.label().equals(label))
            .findFirst().map(s -> s.percent()).orElse(BigDecimal.valueOf(-1));
    }

    @Test
    void aDirectlyHeldShareContributesItsWholeValueToOneSectorAndOneCountry() {
        equityAccount(Map.of("AI.PA", "10000"));
        stockProfile("AI.PA", "basic_materials", "FR");

        DiversificationResponse response = service.diversification(MEMBER);

        assertThat(sliceOf(response.sectors(), "basic_materials")).isEqualByComparingTo("100.00");
        assertThat(sliceOf(response.countries(), "FR")).isEqualByComparingTo("100.00");
        assertThat(response.coveragePercent()).isEqualByComparingTo("100.00");
    }

    @Test
    void anEtfIsLookedThroughAndWeightedByValue() {
        equityAccount(Map.of("CW8.PA", "10000"));
        etfProfile("CW8.PA",
            Map.of("technology", "40", "healthcare", "60"),
            Map.of("US", "70", "JP", "30"));

        DiversificationResponse response = service.diversification(MEMBER);

        assertThat(sliceOf(response.sectors(), "technology")).isEqualByComparingTo("40.00");
        assertThat(sliceOf(response.sectors(), "healthcare")).isEqualByComparingTo("60.00");
        assertThat(sliceOf(response.countries(), "US")).isEqualByComparingTo("70.00");
    }

    @Test
    void whatAProviderDidNotDiscloseStaysUndisclosed() {
        // This used to assert the opposite: percentages were renormalised to the published total
        // and the whole holding declared classified. That inflated the parts we knew to cover the
        // parts we did not -- and it fires in practice, not in theory: Boursorama's own sector
        // breakdowns in this repo's fixtures sum to 87.25 % and 70.04 %.
        equityAccount(Map.of("CW8.PA", "10000"));
        etfProfile("CW8.PA", Map.of("technology", "30"), Map.of("US", "50", "JP", "10"));

        DiversificationResponse response = service.diversification(MEMBER);

        // Within the classified part the shares are unchanged -- they are shares of what was
        // placed, not of the holding.
        assertThat(sliceOf(response.sectors(), "technology")).isEqualByComparingTo("100.00");
        assertThat(sliceOf(response.countries(), "US")).isEqualByComparingTo("83.33");

        // The countries axis placed 60 % of the line, the sectors axis 30 %. Coverage reports the
        // more generous of the two, and the 40 % nobody disclosed is visible rather than invented.
        assertThat(response.classifiedValueEur()).isEqualByComparingTo("6000.00");
        assertThat(response.unclassifiedValueEur()).isEqualByComparingTo("4000.00");
        assertThat(response.coveragePercent()).isEqualByComparingTo("60.00");

        // And each axis states its own, because the headline takes the better of the two: without
        // this the sector score would look as well-founded as the country one when it rests on
        // half the data.
        assertThat(response.sectors().coveragePercent()).isEqualByComparingTo("30.00");
        assertThat(response.countries().coveragePercent()).isEqualByComparingTo("60.00");
    }

    @Test
    void aFullyDisclosedBreakdownStillPlacesTheWholeHolding() {
        // The guard against over-correcting: a source that does publish a complete distribution
        // must still report 100 % coverage, with no rounding leak.
        equityAccount(Map.of("CW8.PA", "10000"));
        etfProfile("CW8.PA", Map.of("technology", "60", "healthcare", "40"),
            Map.of("US", "70", "JP", "30"));

        DiversificationResponse response = service.diversification(MEMBER);

        assertThat(response.classifiedValueEur()).isEqualByComparingTo("10000.00");
        assertThat(response.unclassifiedValueEur()).isEqualByComparingTo("0.00");
        assertThat(response.coveragePercent()).isEqualByComparingTo("100.00");
    }

    @Test
    void aManualOverrideBeatsTheResolvedProfile() {
        equityAccount(Map.of("AI.PA", "10000"));
        stockProfile("AI.PA", "basic_materials", "FR");
        when(classificationRepository.findByMemberId(MEMBER)).thenReturn(List.of(
            HoldingClassification.builder().ticker("AI.PA").sectorKey("energy").countryKey("BE").build()));

        DiversificationResponse response = service.diversification(MEMBER);

        assertThat(sliceOf(response.sectors(), "energy")).isEqualByComparingTo("100.00");
        assertThat(sliceOf(response.countries(), "BE")).isEqualByComparingTo("100.00");
    }

    @Test
    void anOverrideOnOneFieldLeavesTheOtherResolvedNormally() {
        equityAccount(Map.of("AI.PA", "10000"));
        stockProfile("AI.PA", "basic_materials", "FR");
        when(classificationRepository.findByMemberId(MEMBER)).thenReturn(List.of(
            HoldingClassification.builder().ticker("AI.PA").sectorKey("energy").build()));

        DiversificationResponse response = service.diversification(MEMBER);

        assertThat(sliceOf(response.sectors(), "energy")).isEqualByComparingTo("100.00");
        assertThat(sliceOf(response.countries(), "FR")).isEqualByComparingTo("100.00");
    }

    @Test
    void anUnknownTickerIsReportedNotRenormalisedAway() {
        // A bar computed over 60% of the portfolio must not look like one computed over all of
        // it — the same discipline as the Others remainder in the holding modal.
        equityAccount(Map.of("AI.PA", "6000", "MC.PA", "4000"));
        stockProfile("AI.PA", "basic_materials", "FR");

        DiversificationResponse response = service.diversification(MEMBER);

        assertThat(response.totalValueEur()).isEqualByComparingTo("10000.00");
        assertThat(response.classifiedValueEur()).isEqualByComparingTo("6000.00");
        assertThat(response.unclassifiedValueEur()).isEqualByComparingTo("4000.00");
        assertThat(response.coveragePercent()).isEqualByComparingTo("60.00");
        assertThat(response.unclassified()).singleElement().satisfies(line -> {
            assertThat(line.ticker()).isEqualTo("MC.PA");
            assertThat(line.valueEur()).isEqualByComparingTo("4000.00");
            // Never looked up, as opposed to looked up and unknown — the UI offers a refresh for
            // the first and only a manual override for the second.
            assertThat(line.profileLooked()).isFalse();
            assertThat(line.sectorMissing()).isTrue();
            assertThat(line.countryMissing()).isTrue();
        });
        // And the classified part still sums to 100 among itself.
        assertThat(sliceOf(response.sectors(), "basic_materials")).isEqualByComparingTo("100.00");
    }

    @Test
    void aLineMissingOnlyItsCountryIsListedAsSuch() {
        // Yahoo knows Apple's sector; only the ISIN gives its domicile. Reporting the line as
        // wholly unclassified would send someone to re-enter a sector that is already right.
        Account account = equityAccount(Map.of("AAPL", "5000"));
        profiles.put("AAPL", SecurityProfile.builder()
            .ticker("AAPL").assetType("STOCK").sectorKey("technology").countryKey(null)
            .refreshedAt(Instant.now()).status(SecurityProfileStatus.OK)
            .slices(new ArrayList<>()).build());

        DiversificationResponse response = service.diversification(MEMBER);

        assertThat(response.unclassified()).singleElement().satisfies(line -> {
            assertThat(line.ticker()).isEqualTo("AAPL");
            assertThat(line.sectorMissing()).isFalse();
            assertThat(line.countryMissing()).isTrue();
            assertThat(line.profileLooked()).isTrue();
            // The account is what authorises the correction, so the UI must be handed one.
            assertThat(line.accountId()).isEqualTo(account.getId());
        });
    }

    @Test
    void aHandClassifiedLineLeavesTheList() {
        equityAccount(Map.of("QS0009068550", "20000"));
        when(classificationRepository.findByMemberId(MEMBER)).thenReturn(List.of(
            HoldingClassification.builder()
                .ticker("QS0009068550").sectorKey("industrials").countryKey("FR").build()));

        DiversificationResponse response = service.diversification(MEMBER);

        // An employee-savings FCPE no provider covers: the override is the only thing that can
        // ever place it, so it has to count as fully classified afterwards.
        assertThat(response.unclassified()).isEmpty();
        assertThat(response.coveragePercent()).isEqualByComparingTo("100.00");
        assertThat(sliceOf(response.sectors(), "industrials")).isEqualByComparingTo("100.00");
        assertThat(sliceOf(response.countries(), "FR")).isEqualByComparingTo("100.00");
    }

    @Test
    void theListIsOrderedByValueSoTheWorthwhileFixIsFirst() {
        equityAccount(Map.of("SMALL", "100", "BIG", "9000", "MID", "500"));

        DiversificationResponse response = service.diversification(MEMBER);

        assertThat(response.unclassified())
            .extracting(DiversificationResponse.UnclassifiedLine::ticker)
            .containsExactly("BIG", "MID", "SMALL");
    }

    @Test
    void theSameTickerHeldTwiceIsOnePosition() {
        equityAccount(Map.of("AI.PA", "4000"));
        equityAccount(Map.of("AI.PA", "6000"));
        stockProfile("AI.PA", "basic_materials", "FR");

        DiversificationResponse response = service.diversification(MEMBER);

        assertThat(response.totalValueEur()).isEqualByComparingTo("10000.00");
        assertThat(response.sectors().slices()).hasSize(1);
    }

    @Test
    void sharesAreAppliedSoAJointAccountIsNotOverstated() {
        Account joint = equityAccount(Map.of("AI.PA", "10000"));
        shares.put(joint.getId(), new BigDecimal("50"));
        stockProfile("AI.PA", "basic_materials", "FR");

        assertThat(service.diversification(MEMBER).totalValueEur()).isEqualByComparingTo("5000.00");
    }

    @Test
    void nonEquityAccountsAreIgnored() {
        Account crypto = Account.builder()
            .id(nextId++).name("Binance").type(AccountType.CRYPTO).currency("EUR").color("#000").build();
        accounts.add(crypto);
        shares.put(crypto.getId(), new BigDecimal("100"));

        assertThat(service.diversification(MEMBER).totalValueEur()).isEqualByComparingTo("0.00");
    }

    @Test
    void theScoreCountsEffectivePositionsNotBuckets() {
        // Five sectors held 96/1/1/1/1 is not a diversified portfolio, and counting buckets
        // cannot tell it apart from 20/20/20/20/20.
        equityAccount(Map.of("CONC", "10000"));
        etfProfile("CONC", Map.of("technology", "96", "energy", "1", "healthcare", "1",
            "utilities", "1", "industrials", "1"), Map.of());

        DiversificationResponse concentrated = service.diversification(MEMBER);
        assertThat(concentrated.sectors().effectiveCount()).isLessThan(new BigDecimal("1.2"));
        assertThat(concentrated.sectors().score()).isLessThan(25);

        accounts.clear(); shares.clear(); profiles.clear(); nextId = 1;
        equityAccount(Map.of("EVEN", "10000"));
        etfProfile("EVEN", Map.of("technology", "20", "energy", "20", "healthcare", "20",
            "utilities", "20", "industrials", "20"), Map.of());

        DiversificationResponse even = service.diversification(MEMBER);
        assertThat(even.sectors().effectiveCount()).isEqualByComparingTo("5.00");
        assertThat(even.sectors().score()).isEqualTo(83);
    }

    @Test
    void aDirectShareMarksTheCountryBasisAsMixed() {
        // An ETF's countries are look-through exposure, a share's is its domicile. Adding them
        // adds two different quantities, and the client has to be able to say so.
        equityAccount(Map.of("AI.PA", "10000"));
        stockProfile("AI.PA", "basic_materials", "FR");

        assertThat(service.diversification(MEMBER).countries().basis()).isEqualTo("MIXED");
    }

    @Test
    void aPureEtfPortfolioReportsExposureBasis() {
        equityAccount(Map.of("CW8.PA", "10000"));
        etfProfile("CW8.PA", Map.of("technology", "100"), Map.of("US", "100"));

        assertThat(service.diversification(MEMBER).countries().basis()).isEqualTo("EXPOSURE");
    }

    @Test
    void anEmptyPortfolioDoesNotDivideByZero() {
        DiversificationResponse response = service.diversification(MEMBER);

        assertThat(response.totalValueEur()).isEqualByComparingTo("0.00");
        assertThat(response.coveragePercent()).isEqualByComparingTo("0.00");
        assertThat(response.sectors().score()).isZero();
    }

    private static DiversificationResponse.Slice slice(DiversificationResponse.Breakdown b, String label) {
        return b.slices().stream().filter(s -> s.label().equals(label)).findFirst().orElseThrow();
    }

    @Test
    void aSliceNamesTheHoldingsBehindIt() {
        // The whole point of the drill-down: "US 62 %" is not an answer until you can see which
        // lines produced it.
        equityAccount(Map.of("CW8.PA", "10000", "AAPL", "5000"));
        etfProfile("CW8.PA", Map.of("technology", "50"), Map.of("US", "60", "JP", "40"));
        stockProfile("AAPL", "technology", "US");

        DiversificationResponse response = service.diversification(MEMBER);

        DiversificationResponse.Slice us = slice(response.countries(), "US");
        // 60 % of the fund plus all of the share.
        assertThat(us.valueEur()).isEqualByComparingTo("11000.00");
        assertThat(us.contributorCount()).isEqualTo(2);
        // Largest first: 60 % of the 10 000 fund outweighs the whole 5 000 share.
        assertThat(us.contributors()).extracting(DiversificationResponse.Contributor::ticker)
            .containsExactly("CW8.PA", "AAPL");
        assertThat(us.contributors().getFirst().valueEur()).isEqualByComparingTo("6000.00");
        assertThat(us.contributors().getLast().valueEur()).isEqualByComparingTo("5000.00");
    }

    @Test
    void aContributorsSharesSumToTheSlice() {
        equityAccount(Map.of("CW8.PA", "10000", "AAPL", "5000"));
        etfProfile("CW8.PA", Map.of("technology", "50"), Map.of("US", "60", "JP", "40"));
        stockProfile("AAPL", "technology", "US");

        DiversificationResponse.Slice us =
            slice(service.diversification(MEMBER).countries(), "US");

        BigDecimal euros = us.contributors().stream()
            .map(DiversificationResponse.Contributor::valueEur)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(euros).isEqualByComparingTo(us.valueEur());
    }

    @Test
    void aBroadSliceFoldsItsTailInsteadOfListingEverything() {
        // Fifteen holdings in one sector. A tooltip listing all of them answers nothing, and on a
        // real portfolio the payload would repeat every ticker across every slice of both axes.
        Map<String, String> lines = new HashMap<>();
        for (int i = 1; i <= 15; i++) lines.put("T" + i, String.valueOf(1000 - i));
        equityAccount(lines);
        lines.keySet().forEach(t -> stockProfile(t, "technology", "US"));

        DiversificationResponse.Slice tech =
            slice(service.diversification(MEMBER).sectors(), "technology");

        // Twelve real ones plus the fold, but the count still reports the truth.
        assertThat(tech.contributors()).hasSize(13);
        assertThat(tech.contributorCount()).isEqualTo(15);
        assertThat(tech.contributors().getLast().ticker()).isNull();

        BigDecimal euros = tech.contributors().stream()
            .map(DiversificationResponse.Contributor::valueEur)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(euros).isEqualByComparingTo(tech.valueEur());
    }

    @Test
    void everyContributorCanBeNamedFromTheDictionary() {
        equityAccount(Map.of("CW8.PA", "10000"));
        etfProfile("CW8.PA", Map.of("technology", "50"), Map.of("US", "60"));

        DiversificationResponse response = service.diversification(MEMBER);

        // The dictionary exists so a name and account are not repeated across every slice the
        // security appears in; the UI still has to be able to resolve each one.
        Set<String> named = response.securities().stream()
            .map(DiversificationResponse.SecurityInfo::ticker).collect(java.util.stream.Collectors.toSet());
        for (DiversificationResponse.Breakdown b : List.of(response.sectors(), response.countries())) {
            for (DiversificationResponse.Slice sl : b.slices()) {
                for (DiversificationResponse.Contributor c : sl.contributors()) {
                    if (c.ticker() != null) assertThat(named).contains(c.ticker());
                }
            }
        }
        assertThat(response.securities()).extracting(DiversificationResponse.SecurityInfo::accountId)
            .doesNotContainNull();
    }

    @Test
    void anUnlistedHoldingClassifiedAsSuchCountsAsClassified() {
        // An ELTIF that no source covers. Before it had somewhere to go it sat in the "to
        // classify" list forever, dragging coverage down and implying a lookup that would
        // eventually succeed. It never would.
        equityAccount(Map.of("EQTNETB", "10000"));
        when(classificationRepository.findByMemberId(MEMBER)).thenReturn(List.of(
            HoldingClassification.builder()
                .ticker("EQTNETB")
                .sectorKey(ClassificationKeys.SECTOR_PRIVATE_EQUITY)
                .countryKey(ClassificationKeys.COUNTRY_UNLISTED)
                .build()));

        DiversificationResponse response = service.diversification(MEMBER);

        assertThat(response.unclassified()).isEmpty();
        assertThat(response.coveragePercent()).isEqualByComparingTo("100.00");
        assertThat(sliceOf(response.sectors(), "private_equity")).isEqualByComparingTo("100.00");
        assertThat(sliceOf(response.countries(), "XU")).isEqualByComparingTo("100.00");
    }
}
