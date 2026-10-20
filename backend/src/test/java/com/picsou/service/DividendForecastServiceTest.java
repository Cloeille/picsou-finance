package com.picsou.service;

import com.picsou.dto.UpcomingDividendResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.DistributionFrequency;
import com.picsou.model.DividendBasis;
import com.picsou.model.DividendCadence;
import com.picsou.model.DividendEvent;
import com.picsou.model.DividendOrigin;
import com.picsou.model.DividendPolicy;
import com.picsou.model.DividendSource;
import com.picsou.model.DividendSourceStatus;
import com.picsou.model.ScpiPosition;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.DividendSourceRepository;
import com.picsou.repository.ScpiPositionRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DividendForecastServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final long MEMBER = 7L;

    @Mock AccountAccessResolver access;
    @Mock AccountHoldingRepository holdings;
    @Mock DividendSourceRepository sources;
    @Mock ScpiPositionRepository positions;
    @Mock TransactionRepository transactions;

    private DividendForecastService service;

    @BeforeEach
    void setUp() {
        service = new DividendForecastService(access, holdings, sources, positions, transactions);
    }

    @Test
    void monthlyQuarterlySemiAnnualAndAnnualBandsEachProjectTheNextDate() {
        assertThat(forecast(AccountType.COMPTE_TITRES, "TTE.PA", "40", "100", 60, "EUR", "0.85", "0.85",
            "2026-07-20", "2026-08-20", "2026-09-20"))
            .extracting(UpcomingDividendResponse::cadence)
            .containsOnly(DividendCadence.MONTHLY);

        assertThat(forecast(AccountType.COMPTE_TITRES, "MC.PA", "1", "100", 60, "EUR", "1", "1",
            "2026-03-17", "2026-06-16", "2026-09-15"))
            .extracting(UpcomingDividendResponse::expectedDate)
            .containsExactly(LocalDate.of(2026, 12, 15));

        assertThat(forecast(AccountType.COMPTE_TITRES, "SAN.PA", "1", "100", 60, "EUR", "1", "1",
            "2025-06-20", "2025-12-17", "2026-06-15"))
            .singleElement()
            .satisfies(line -> {
                assertThat(line.cadence()).isEqualTo(DividendCadence.SEMI_ANNUAL);
                assertThat(line.expectedDate()).isEqualTo(LocalDate.of(2026, 12, 15));
                assertThat(line.basis()).isEqualTo(DividendBasis.HISTORY_CADENCE);
            });

        assertThat(forecast(AccountType.COMPTE_TITRES, "OR.PA", "1", "100", 60, "EUR", "1", "1",
            "2024-12-15", "2025-12-15"))
            .singleElement()
            .satisfies(line -> assertThat(line.cadence()).isEqualTo(DividendCadence.ANNUAL));
    }

    @Test
    void aFailedRefreshKeepsForecastingFromTheStoredEvents() {
        // A FAILED refresh keeps the last good events on purpose; one 429 must not blank the
        // forecast for a week.
        Account account = account(12L, "CTO Bourso", AccountType.COMPTE_TITRES);
        Prepared prepared = prepared(account, "MC.PA", "1", "EUR", "1", "1",
            "2026-03-17", "2026-06-16", "2026-09-15");
        prepared.source().setStatus(DividendSourceStatus.FAILED);
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(account));
        when(access.sharesFor(any(), eq(MEMBER))).thenReturn(Map.of(12L, new BigDecimal("100")));
        when(holdings.findPositiveByAccountIdIn(any())).thenReturn(List.of(prepared.holding()));
        when(sources.findAllWithEventsByTickerIn(any())).thenReturn(List.of(prepared.source()));

        assertThat(service.upcoming(MEMBER, TODAY, 60))
            .extracting(UpcomingDividendResponse::expectedDate)
            .containsExactly(LocalDate.of(2026, 12, 15));
    }

    @Test
    void aFailedSourceThatNeverStoredAnEventForecastsNothing() {
        Account account = account(12L, "CTO Bourso", AccountType.COMPTE_TITRES);
        Prepared prepared = prepared(account, "MC.PA", "1", "EUR", "1", "1");
        prepared.source().setStatus(DividendSourceStatus.FAILED);
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(account));
        when(access.sharesFor(any(), eq(MEMBER))).thenReturn(Map.of(12L, new BigDecimal("100")));
        when(holdings.findPositiveByAccountIdIn(any())).thenReturn(List.of(prepared.holding()));
        when(sources.findAllWithEventsByTickerIn(any())).thenReturn(List.of(prepared.source()));

        assertThat(service.upcoming(MEMBER, TODAY, 60)).isEmpty();
    }

    @Test
    void anIrregularSeriesAndAStalePayerForecastNothing() {
        assertThat(forecast(AccountType.COMPTE_TITRES, "TTE.PA", "10", "100", 60, "EUR", "1", "1",
            "2026-04-15", "2026-05-15", "2026-08-13")).isEmpty();
        assertThat(forecast(AccountType.COMPTE_TITRES, "TTE.PA", "10", "100", 60, "EUR", "1", "1",
            "2026-04-15", "2026-05-15", "2026-06-15")).isEmpty();
    }

    @Test
    void horizonIsInclusiveOnBothEnds() {
        assertThat(forecast(AccountType.COMPTE_TITRES, "TTE.PA", "10", "100", 31, "EUR", "1", "1",
            "2026-07-20", "2026-08-20", "2026-09-20"))
            .extracting(UpcomingDividendResponse::expectedDate)
            .containsExactly(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 20));
        assertThat(forecast(AccountType.COMPTE_TITRES, "TTE.PA", "10", "100", 30, "EUR", "1", "1",
            "2026-07-20", "2026-08-20", "2026-09-20"))
            .extracting(UpcomingDividendResponse::expectedDate)
            .containsExactly(LocalDate.of(2026, 10, 20));
    }

    @Test
    void amountEurUsesTheStoredEuroAmountTimesQuantityTimesShare() {
        List<UpcomingDividendResponse> lines = forecast(
            AccountType.COMPTE_TITRES, "AAPL", "10", "50", 60, "USD", "1.00", "0.92",
            "2026-07-20", "2026-08-20", "2026-09-20");

        assertThat(lines).isNotEmpty();
        assertThat(lines.get(0).amountPerShare()).isEqualByComparingTo("1.00");
        assertThat(lines.get(0).currency()).isEqualTo("USD");
        assertThat(lines.get(0).quantity()).isEqualByComparingTo("10");
        assertThat(lines.get(0).amountEur()).isEqualByComparingTo("4.6");
    }

    @Test
    void idIsDeterministicAndLinesSortByDateThenAccountThenTicker() {
        Account zoo = account(12L, "Zoo", AccountType.COMPTE_TITRES);
        Account alpha = account(4L, "Alpha", AccountType.PEA);
        Prepared zzz = prepared(zoo, "ZZZ", "1", "EUR", "1", "1", "2026-07-20", "2026-08-20", "2026-09-20");
        Prepared aaa = prepared(alpha, "AAA", "1", "EUR", "1", "1", "2026-07-20", "2026-08-20", "2026-09-20");
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(zoo, alpha));
        when(access.sharesFor(any(), eq(MEMBER))).thenReturn(Map.of(
            12L, new BigDecimal("100"), 4L, new BigDecimal("100")));
        when(holdings.findPositiveByAccountIdIn(any())).thenReturn(List.of(zzz.holding(), aaa.holding()));
        when(sources.findAllWithEventsByTickerIn(any())).thenReturn(List.of(zzz.source(), aaa.source()));

        List<UpcomingDividendResponse> once = service.upcoming(MEMBER, TODAY, 31);
        List<UpcomingDividendResponse> twice = service.upcoming(MEMBER, TODAY, 31);

        assertThat(once).extracting(UpcomingDividendResponse::id).containsExactly(
            "HOLDING:4:AAA:2026-10-20",
            "HOLDING:12:ZZZ:2026-10-20",
            "HOLDING:4:AAA:2026-11-20",
            "HOLDING:12:ZZZ:2026-11-20");
        assertThat(twice).extracting(UpcomingDividendResponse::id)
            .containsExactlyElementsOf(once.stream().map(UpcomingDividendResponse::id).toList());
        assertThat(once.get(0).source()).isEqualTo(DividendOrigin.HOLDING);
        assertThat(once.get(0).countsInBudget()).isFalse();
    }

    @Test
    void aPeaAssuranceVieOrPerHoldingIsForecastButDoesNotCountInTheBudget() {
        for (AccountType locked : List.of(AccountType.PEA, AccountType.ASSURANCE_VIE, AccountType.PER)) {
            List<UpcomingDividendResponse> lines = forecast(locked, "TTE.PA", "40", "100", 60, "EUR", "0.85", "0.85",
                "2026-07-20", "2026-08-20", "2026-09-20");
            assertThat(lines).as(locked.name()).isNotEmpty();
            assertThat(lines).allSatisfy(line -> {
                assertThat(line.countsInBudget()).isFalse();
                assertThat(line.accountType()).isEqualTo(locked);
                assertThat(line.reinvested()).isFalse();
            });
        }
    }

    @Test
    void anEmployeeSavingsHoldingIsForecastButDoesNotCountInTheBudget() {
        List<UpcomingDividendResponse> lines = forecast(
            AccountType.EMPLOYEE_SAVINGS, "TTE.PA", "40", "100", 60, "EUR", "0.85", "0.85",
            "2026-07-20", "2026-08-20", "2026-09-20");

        assertThat(lines).as("employee savings is forecast without widening isInvestment()").isNotEmpty();
        assertThat(AccountType.EMPLOYEE_SAVINGS.isInvestment()).isFalse();
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.countsInBudget()).isFalse();
            assertThat(line.accountType()).isEqualTo(AccountType.EMPLOYEE_SAVINGS);
            assertThat(line.reinvested()).isFalse();
        });
    }

    @Test
    void aCompteTitresHoldingCountsInTheBudget() {
        List<UpcomingDividendResponse> lines = forecast(
            AccountType.COMPTE_TITRES, "TTE.PA", "40", "100", 60, "EUR", "0.85", "0.85",
            "2026-07-20", "2026-08-20", "2026-09-20");

        assertThat(lines).isNotEmpty().allSatisfy(line -> assertThat(line.countsInBudget()).isTrue());
        assertThat(lines.get(0).id()).isEqualTo("HOLDING:12:TTE.PA:2026-10-20");
        assertThat(lines.get(0).amountEur()).isEqualByComparingTo("34");
    }

    @Test
    void cryptoHoldingsAreNotForecast() {
        Account crypto = account(12L, "Wallet", AccountType.CRYPTO);
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(crypto));
        when(access.sharesFor(any(), eq(MEMBER))).thenReturn(Map.of(12L, new BigDecimal("100")));

        assertThat(service.upcoming(MEMBER, TODAY, 60)).isEmpty();
        verify(holdings, never()).findPositiveByAccountIdIn(any());
    }

    @Test
    void scpiMonthlyAndQuarterlyPaymentsUseTheDeclaredAmount() {
        List<UpcomingDividendResponse> monthly = scpi(
            12L, "10", "12", DistributionFrequency.MONTHLY, null, DividendPolicy.CASH, "FR0012345678", TODAY, 40);
        assertThat(monthly).extracting(UpcomingDividendResponse::expectedDate)
            .containsExactly(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 25));
        assertThat(monthly.get(0).cadence()).isEqualTo(DividendCadence.MONTHLY);
        assertThat(monthly.get(0).basis()).isEqualTo(DividendBasis.SCPI_DECLARED);
        assertThat(monthly.get(0).amountPerShare()).isEqualByComparingTo("1");
        assertThat(monthly.get(0).amountEur()).isEqualByComparingTo("10");
        assertThat(monthly.get(0).currency()).isEqualTo("EUR");
        assertThat(monthly.get(0).name()).isEqualTo("CORUM Origin");
        assertThat(monthly.get(0).id()).isEqualTo("SCPI:12:FR0012345678:2026-10-25");

        List<UpcomingDividendResponse> quarterly = scpi(
            3L, "4", "8", DistributionFrequency.QUARTERLY, null, DividendPolicy.CASH, "FR0012345678",
            LocalDate.of(2026, 10, 1), 30);
        assertThat(quarterly).singleElement().satisfies(line -> {
            assertThat(line.expectedDate()).isEqualTo(LocalDate.of(2026, 10, 25));
            assertThat(line.cadence()).isEqualTo(DividendCadence.QUARTERLY);
            assertThat(line.amountPerShare()).isEqualByComparingTo("2");
        });
    }

    @Test
    void aPaymentWhosePeriodEndsBeforeJouissanceIsSkipped() {
        assertThat(scpi(12L, "10", "12", DistributionFrequency.MONTHLY,
            LocalDate.of(2026, 10, 1), DividendPolicy.CASH, "FR0012345678", TODAY, 40))
            .extracting(UpcomingDividendResponse::expectedDate)
            .containsExactly(LocalDate.of(2026, 11, 25));
    }

    @Test
    void scpiCashCountsInTheBudgetAndReinvestedDoesNot() {
        assertThat(scpi(12L, "10", "12", DistributionFrequency.MONTHLY, null, DividendPolicy.CASH,
            "FR0012345678", TODAY, 40))
            .isNotEmpty()
            .allSatisfy(line -> {
                assertThat(line.reinvested()).isFalse();
                assertThat(line.countsInBudget()).isTrue();
                assertThat(line.accountType()).isEqualTo(AccountType.SCPI);
            });

        assertThat(scpi(12L, "10", "12", DistributionFrequency.MONTHLY, null, DividendPolicy.REINVEST,
            "FR0012345678", TODAY, 40))
            .isNotEmpty()
            .allSatisfy(line -> {
                assertThat(line.reinvested()).isTrue();
                assertThat(line.countsInBudget()).isFalse();
            });
    }

    @Test
    void anScpiWithoutAnIsinUsesADashInTheId() {
        UpcomingDividendResponse line = scpi(
            9L, "10", "12", DistributionFrequency.MONTHLY, null, DividendPolicy.CASH, null, TODAY, 40).get(0);

        assertThat(line.id()).isEqualTo("SCPI:9:-:2026-10-25");
        assertThat(line.ticker()).isNull();
    }

    @Test
    void lockedWrappersAreTheEnvelopesAMemberCannotFreelyWithdrawFrom() {
        assertThat(AccountType.PEA.isLockedWrapper()).isTrue();
        assertThat(AccountType.ASSURANCE_VIE.isLockedWrapper()).isTrue();
        assertThat(AccountType.PER.isLockedWrapper()).isTrue();
        assertThat(AccountType.EMPLOYEE_SAVINGS.isLockedWrapper()).isTrue();
        assertThat(AccountType.COMPTE_TITRES.isLockedWrapper()).isFalse();
        assertThat(AccountType.SCPI.isLockedWrapper()).isFalse();
    }

    @Test
    void paymentLagIsTheLowerMedianOfTwoOrMorePairs() {
        Account account = account(12L, "CTO Bourso", AccountType.COMPTE_TITRES);
        Prepared prepared = prepared(account, "TTE.PA", "40", "EUR", "0.85", "0.85",
            "2026-07-20", "2026-08-20", "2026-09-20");
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(account));
        when(access.sharesFor(any(), eq(MEMBER))).thenReturn(Map.of(12L, new BigDecimal("100")));
        when(holdings.findPositiveByAccountIdIn(any())).thenReturn(List.of(prepared.holding()));
        when(sources.findAllWithEventsByTickerIn(any())).thenReturn(List.of(prepared.source()));
        // 10 days after 2026-08-20 (also 41 days after 2026-07-20: the latest ex-date wins).
        // 20, 30 and 60 days after 2026-09-20. Even count, lower central value is 20, not 25 or 30.
        // The other account's 40-day pair must not enter the median.
        when(transactions.findDividendCashDates(any())).thenReturn(List.of(
            cash(12L, "tte.pa", "2026-08-30"),
            cash(12L, "TTE.PA", "2026-10-10"),
            cash(12L, "TTE.PA", "2026-10-20"),
            cash(12L, "TTE.PA", "2026-11-19"),
            cash(4L, "TTE.PA", "2026-10-30")));

        List<UpcomingDividendResponse> lines = service.upcoming(MEMBER, TODAY, 60);

        assertThat(lines).extracting(UpcomingDividendResponse::id).containsExactly(
            "HOLDING:12:TTE.PA:2026-10-20",
            "HOLDING:12:TTE.PA:2026-11-20");
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.exDate()).isEqualTo(line.expectedDate());
            assertThat(line.paymentDate()).isEqualTo(line.expectedDate().plusDays(20));
            assertThat(line.paymentDateEstimated()).isTrue();
            assertThat(line.amountPerShareEur()).isEqualByComparingTo("0.85");
        });
        verify(transactions, times(1)).findDividendCashDates(any());
    }

    @Test
    void noDividendTransactionLeavesPaymentDateNull() {
        List<UpcomingDividendResponse> lines = forecast(
            AccountType.COMPTE_TITRES, "TTE.PA", "40", "100", 60, "EUR", "0.85", "0.85",
            "2026-07-20", "2026-08-20", "2026-09-20");

        assertThat(lines).hasSize(2);
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.paymentDate()).isNull();
            assertThat(line.paymentDateEstimated()).isFalse();
            assertThat(line.exDate()).isEqualTo(line.expectedDate());
            assertThat(line.amountPerShareEur()).isEqualByComparingTo("0.85");
        });
        verify(transactions, times(1)).findDividendCashDates(any());
        verify(access, never()).requireReadable(any(), any());
    }

    @Test
    void aTransactionMoreThan60DaysAfterTheExDateIsIgnored() {
        Account account = account(12L, "CTO Bourso", AccountType.COMPTE_TITRES);
        Prepared prepared = prepared(account, "TTE.PA", "40", "EUR", "0.85", "0.85",
            "2026-07-20", "2026-08-20", "2026-09-20");
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(account));
        when(access.sharesFor(any(), eq(MEMBER))).thenReturn(Map.of(12L, new BigDecimal("100")));
        when(holdings.findPositiveByAccountIdIn(any())).thenReturn(List.of(prepared.holding()));
        when(sources.findAllWithEventsByTickerIn(any())).thenReturn(List.of(prepared.source()));
        when(transactions.findDividendCashDates(any())).thenReturn(List.of(
            cash(12L, "TTE.PA", "2026-11-20")));

        List<UpcomingDividendResponse> lines = service.upcoming(MEMBER, TODAY, 60);

        assertThat(lines).isNotEmpty().allSatisfy(line -> {
            assertThat(line.exDate()).isEqualTo(line.expectedDate());
            assertThat(line.paymentDate()).isNull();
            assertThat(line.paymentDateEstimated()).isFalse();
        });
    }

    @Test
    void aTransactionExactly60DaysAfterTheExDateStillPairs() {
        Account account = account(12L, "CTO Bourso", AccountType.COMPTE_TITRES);
        Prepared prepared = prepared(account, "TTE.PA", "1", "EUR", "1", "1",
            "2026-07-20", "2026-08-20", "2026-09-20");
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(account));
        when(access.sharesFor(any(), eq(MEMBER))).thenReturn(Map.of(12L, new BigDecimal("100")));
        when(holdings.findPositiveByAccountIdIn(any())).thenReturn(List.of(prepared.holding()));
        when(sources.findAllWithEventsByTickerIn(any())).thenReturn(List.of(prepared.source()));
        when(transactions.findDividendCashDates(any())).thenReturn(List.of(
            cash(12L, "TTE.PA", "2026-11-19")));

        assertThat(service.upcoming(MEMBER, TODAY, 60)).isNotEmpty().allSatisfy(line ->
            assertThat(line.paymentDate()).isEqualTo(line.exDate().plusDays(60)));
    }

    @Test
    void paymentLagFallsBackToAnotherReadableAccount() {
        Account holding = account(12L, "CTO Bourso", AccountType.COMPTE_TITRES);
        Account other = account(4L, "PEA", AccountType.PEA);
        Prepared prepared = prepared(holding, "TTE.PA", "40", "EUR", "0.85", "0.85",
            "2026-07-20", "2026-08-20", "2026-09-20");
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(holding, other));
        when(access.sharesFor(any(), eq(MEMBER))).thenReturn(Map.of(
            12L, new BigDecimal("100"), 4L, new BigDecimal("100")));
        when(holdings.findPositiveByAccountIdIn(any())).thenReturn(List.of(prepared.holding()));
        when(sources.findAllWithEventsByTickerIn(any())).thenReturn(List.of(prepared.source()));
        when(transactions.findDividendCashDates(any())).thenReturn(List.of(
            cash(12L, "TTE.PA", "2026-11-20"),
            cash(4L, "tte.pa", "2026-07-24"),
            cash(4L, "TTE.PA", "2026-08-28")));

        List<UpcomingDividendResponse> lines = service.upcoming(MEMBER, TODAY, 60);

        assertThat(lines).isNotEmpty().allSatisfy(line -> {
            assertThat(line.accountId()).isEqualTo(12L);
            assertThat(line.paymentDate()).isEqualTo(line.exDate().plusDays(4));
            assertThat(line.paymentDateEstimated()).isTrue();
        });
        verify(transactions, times(1)).findDividendCashDates(argThat(ids ->
            ids.contains(12L) && ids.contains(4L)));
    }

    @Test
    void accountIdFilterReturnsOnlyThatAccount() {
        Account zoo = account(12L, "Zoo", AccountType.COMPTE_TITRES);
        Account alpha = account(4L, "Alpha", AccountType.PEA);
        Prepared zzz = prepared(zoo, "ZZZ", "1", "EUR", "1", "1", "2026-07-20", "2026-08-20", "2026-09-20");
        Prepared aaa = prepared(alpha, "AAA", "1", "EUR", "1", "1", "2026-07-20", "2026-08-20", "2026-09-20");
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(zoo, alpha));
        when(access.sharesFor(any(), eq(MEMBER))).thenReturn(Map.of(
            12L, new BigDecimal("100"), 4L, new BigDecimal("100")));
        when(holdings.findPositiveByAccountIdIn(any())).thenReturn(List.of(zzz.holding(), aaa.holding()));
        when(sources.findAllWithEventsByTickerIn(any())).thenReturn(List.of(zzz.source(), aaa.source()));

        List<UpcomingDividendResponse> lines = service.upcoming(MEMBER, TODAY, 60, 4L);

        assertThat(lines).isNotEmpty().allSatisfy(line -> assertThat(line.accountId()).isEqualTo(4L));
        assertThat(lines).extracting(UpcomingDividendResponse::ticker).containsOnly("AAA");
        verify(access).requireReadable(4L, MEMBER);
        verify(holdings).findPositiveByAccountIdIn(argThat(ids -> ids.contains(4L) && !ids.contains(12L)));
        verify(transactions).findDividendCashDates(argThat(ids -> ids.contains(12L) && ids.contains(4L)));
    }

    @Test
    void anUnreadableAccountIsRejected() {
        when(access.requireReadable(99L, MEMBER)).thenThrow(ResourceNotFoundException.account(99L));

        assertThatThrownBy(() -> service.upcoming(MEMBER, TODAY, 366, 99L))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(access, never()).readableAccounts(any());
        verify(transactions, never()).findDividendCashDates(any());
    }

    @Test
    void anScpiLineHasNoExDateAndUsesTheDeclaredPaymentDate() {
        UpcomingDividendResponse line = scpi(
            12L, "10", "12", DistributionFrequency.MONTHLY, null, DividendPolicy.CASH, "FR0012345678", TODAY, 40)
            .get(0);

        assertThat(line.exDate()).isNull();
        assertThat(line.paymentDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(line.paymentDate()).isEqualTo(line.expectedDate());
        assertThat(line.paymentDateEstimated()).isTrue();
        assertThat(line.amountPerShareEur()).isEqualByComparingTo("1");
    }

    @Test
    void aDeclaredFutureEventBecomesAnAnnouncedLineAndReplacesTheNearbyProjection() {
        Account account = account(12L, "CTO", AccountType.COMPTE_TITRES);
        Prepared prepared = prepared(account, "MSFT", "1", "USD", "0.91", "0.84",
            "2026-07-20", "2026-08-20", "2026-09-20");
        prepared.source().getEvents().add(DividendEvent.builder()
            .exDate(LocalDate.of(2026, 11, 19))
            .paymentDate(LocalDate.of(2026, 12, 10))
            .amount(new BigDecimal("0.98"))
            .amountEur(new BigDecimal("0.90"))
            .declared(true)
            .build());
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(account));
        when(access.sharesFor(any(), eq(MEMBER))).thenReturn(Map.of(12L, new BigDecimal("100")));
        when(holdings.findPositiveByAccountIdIn(any())).thenReturn(List.of(prepared.holding()));
        when(sources.findAllWithEventsByTickerIn(any())).thenReturn(List.of(prepared.source()));

        List<UpcomingDividendResponse> lines = service.upcoming(MEMBER, TODAY, 60);

        assertThat(lines).extracting(UpcomingDividendResponse::expectedDate).containsExactly(
            LocalDate.of(2026, 10, 20),
            LocalDate.of(2026, 11, 19));
        assertThat(lines).extracting(UpcomingDividendResponse::id)
            .doesNotContain("HOLDING:12:MSFT:2026-11-20");
        assertThat(lines.get(1).basis()).isEqualTo(DividendBasis.ANNOUNCED);
        assertThat(lines.get(1).exDate()).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(lines.get(1).paymentDate()).isEqualTo(LocalDate.of(2026, 12, 10));
        assertThat(lines.get(1).paymentDateEstimated()).isFalse();
        assertThat(lines.get(1).amountPerShareEur()).isEqualByComparingTo("0.90");
        assertThat(lines.get(1).id()).isEqualTo("HOLDING:12:MSFT:2026-11-19");
    }

    @Test
    void aFutureYahooExDateBecomesAnAnnouncedLineAndEstimatesPaymentFromTheLearnedLag() {
        Account account = account(12L, "PEA", AccountType.PEA);
        Prepared prepared = prepared(account, "TTE.PA", "40", "EUR", "0.85", "0.85",
            "2026-06-15", "2026-09-15");
        prepared.source().getEvents().get(0).setPaymentDate(LocalDate.of(2026, 6, 29));
        prepared.source().getEvents().get(1).setPaymentDate(LocalDate.of(2026, 9, 29));
        // Yahoo publishes the ex-date and nothing else: no declared flag, no payment date.
        prepared.source().getEvents().add(DividendEvent.builder()
            .exDate(LocalDate.of(2026, 12, 15))
            .amount(new BigDecimal("0.90"))
            .amountEur(new BigDecimal("0.90"))
            .declared(false)
            .build());
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(account));
        when(access.sharesFor(any(), eq(MEMBER))).thenReturn(Map.of(12L, new BigDecimal("100")));
        when(holdings.findPositiveByAccountIdIn(any())).thenReturn(List.of(prepared.holding()));
        when(sources.findAllWithEventsByTickerIn(any())).thenReturn(List.of(prepared.source()));

        List<UpcomingDividendResponse> lines = service.upcoming(MEMBER, TODAY, 90);

        assertThat(lines).filteredOn(line -> line.basis() == DividendBasis.ANNOUNCED)
            .singleElement()
            .satisfies(line -> {
                assertThat(line.ticker()).isEqualTo("TTE.PA");
                assertThat(line.exDate()).isEqualTo(LocalDate.of(2026, 12, 15));
                assertThat(line.expectedDate()).isEqualTo(LocalDate.of(2026, 12, 15));
                assertThat(line.paymentDate()).isEqualTo(LocalDate.of(2026, 12, 29));
                assertThat(line.paymentDateEstimated()).isTrue();
                assertThat(line.amountPerShareEur()).isEqualByComparingTo("0.90");
            });
    }

    @Test
    void aFutureYahooExDateWithNoLearnedLagKeepsPaymentDateNull() {
        Account account = account(12L, "PEA", AccountType.PEA);
        Prepared prepared = prepared(account, "TTE.PA", "40", "EUR", "0.85", "0.85", "2026-09-15");
        prepared.source().getEvents().add(DividendEvent.builder()
            .exDate(LocalDate.of(2026, 12, 15))
            .amount(new BigDecimal("0.90"))
            .amountEur(new BigDecimal("0.90"))
            .build());
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(account));
        when(access.sharesFor(any(), eq(MEMBER))).thenReturn(Map.of(12L, new BigDecimal("100")));
        when(holdings.findPositiveByAccountIdIn(any())).thenReturn(List.of(prepared.holding()));
        when(sources.findAllWithEventsByTickerIn(any())).thenReturn(List.of(prepared.source()));

        List<UpcomingDividendResponse> lines = service.upcoming(MEMBER, TODAY, 90);

        assertThat(lines).filteredOn(line -> line.basis() == DividendBasis.ANNOUNCED)
            .singleElement()
            .satisfies(line -> {
                assertThat(line.paymentDate()).isNull();
                assertThat(line.paymentDateEstimated()).isFalse();
            });
    }

    @Test
    void paymentLagIsLearnedFromStoredPaymentDatesBeforeTransactions() {
        Account account = account(12L, "CTO", AccountType.COMPTE_TITRES);
        Prepared prepared = prepared(account, "MSFT", "1", "USD", "1", "1",
            "2026-07-20", "2026-08-20", "2026-09-20");
        prepared.source().getEvents().get(0).setPaymentDate(LocalDate.of(2026, 8, 3));
        prepared.source().getEvents().get(1).setPaymentDate(LocalDate.of(2026, 9, 10));
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(account));
        when(access.sharesFor(any(), eq(MEMBER))).thenReturn(Map.of(12L, new BigDecimal("100")));
        when(holdings.findPositiveByAccountIdIn(any())).thenReturn(List.of(prepared.holding()));
        when(sources.findAllWithEventsByTickerIn(any())).thenReturn(List.of(prepared.source()));
        when(transactions.findDividendCashDates(any())).thenReturn(List.of(cash(12L, "MSFT", "2026-07-25")));

        assertThat(service.upcoming(MEMBER, TODAY, 60)).isNotEmpty().allSatisfy(line ->
            assertThat(line.paymentDate()).isEqualTo(line.exDate().plusDays(14)));
    }

    private List<UpcomingDividendResponse> forecast(
        AccountType type, String ticker, String quantity, String share, int horizon,
        String currency, String amount, String amountEur, String... dates
    ) {
        Account account = account(12L, "CTO Bourso", type);
        Prepared prepared = prepared(account, ticker, quantity, currency, amount, amountEur, dates);
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(account));
        when(access.sharesFor(any(), eq(MEMBER))).thenReturn(Map.of(12L, new BigDecimal(share)));
        when(holdings.findPositiveByAccountIdIn(any())).thenReturn(List.of(prepared.holding()));
        when(sources.findAllWithEventsByTickerIn(any())).thenReturn(List.of(prepared.source()));
        return service.upcoming(MEMBER, TODAY, horizon);
    }

    private List<UpcomingDividendResponse> scpi(
        long accountId, String shares, String annual, DistributionFrequency frequency,
        LocalDate jouissance, DividendPolicy policy, String isin, LocalDate today, int horizon
    ) {
        Account account = account(accountId, "CORUM Origin", AccountType.SCPI);
        ScpiPosition position = ScpiPosition.builder()
            .account(account)
            .shareCount(new BigDecimal(shares))
            .annualDistributionPerShareEur(new BigDecimal(annual))
            .distributionFrequency(frequency)
            .jouissanceDate(jouissance)
            .dividendPolicy(policy)
            .isin(isin)
            .build();
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(account));
        when(access.sharesFor(any(), eq(MEMBER))).thenReturn(Map.of(accountId, new BigDecimal("100")));
        when(positions.findWithAccountByAccountIdIn(any())).thenReturn(List.of(position));
        return service.upcoming(MEMBER, today, horizon);
    }

    private static Prepared prepared(
        Account account, String ticker, String quantity, String currency,
        String amount, String amountEur, String... dates
    ) {
        AccountHolding holding = AccountHolding.builder()
            .account(account)
            .ticker(ticker)
            .name("TotalEnergies")
            .quantity(new BigDecimal(quantity))
            .build();
        DividendSource source = DividendSource.builder()
            .ticker(ticker)
            .currency(currency)
            .status(DividendSourceStatus.OK)
            .events(new ArrayList<>())
            .build();
        for (String date : dates) {
            source.getEvents().add(DividendEvent.builder()
                .exDate(LocalDate.parse(date))
                .amount(new BigDecimal(amount))
                .amountEur(new BigDecimal(amountEur))
                .source(source)
                .build());
        }
        return new Prepared(holding, source);
    }

    private static Account account(long id, String name, AccountType type) {
        return Account.builder().id(id).name(name).type(type).currency("EUR").build();
    }

    private record Prepared(AccountHolding holding, DividendSource source) {}

    private record CashDate(Long accountId, String ticker, LocalDate date)
        implements TransactionRepository.DividendCashDate {
        @Override public Long getAccountId() { return accountId; }
        @Override public String getTicker() { return ticker; }
        @Override public LocalDate getDate() { return date; }
    }

    private static TransactionRepository.DividendCashDate cash(long accountId, String ticker, String date) {
        return new CashDate(accountId, ticker, LocalDate.parse(date));
    }
}
