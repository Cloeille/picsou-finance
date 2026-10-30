package com.picsou.service;

import com.picsou.dto.EssentialExpenseEstimateResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EssentialExpenseEstimatorTest {

    private static final Long MEMBER = 1L;

    @Mock AccountAccessResolver accessResolver;
    @Mock TransactionRepository transactionRepository;
    @Mock PriceService priceService;

    @InjectMocks EssentialExpenseEstimator estimator;

    private final List<Account> accounts = new ArrayList<>();
    private final Map<Long, BigDecimal> shares = new HashMap<>();
    private final List<Transaction> transactions = new ArrayList<>();
    private long nextTxId = 1;

    private Account current;
    private Account livret;

    @BeforeEach
    void setUp() {
        current = account(1L, AccountType.CHECKING);
        livret = account(2L, AccountType.LIVRET_A);

        lenient().when(accessResolver.readableAccounts(MEMBER)).thenReturn(accounts);
        lenient().when(accessResolver.sharesFor(any(), any())).thenReturn(shares);
        // Honour the date window the service asks for, as the real derived query does — otherwise
        // "the current month is excluded" could never be observed here.
        lenient().when(transactionRepository.findByAccountIdInAndDateBetweenOrderByDateAsc(
            any(), any(), any())).thenAnswer(inv -> {
                LocalDate from = inv.getArgument(1);
                LocalDate to = inv.getArgument(2);
                return transactions.stream()
                    .filter(t -> !t.getDate().isBefore(from) && !t.getDate().isAfter(to))
                    .toList();
            });
        // EUR in, EUR out — currency conversion is PriceService's business, not this one's.
        lenient().when(priceService.toEur(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Account account(Long id, AccountType type) {
        Account account = Account.builder().id(id).name(type.name()).type(type)
            .currency("EUR").color("#000000").build();
        accounts.add(account);
        shares.put(id, new BigDecimal("100"));
        return account;
    }

    /** {@code monthsAgo} counted from the last complete month, so 1 is the most recent one. */
    private Transaction tx(Account account, int monthsAgo, int day, String amount) {
        Transaction t = Transaction.builder()
            .id(nextTxId++)
            .account(account)
            .date(YearMonth.now().minusMonths(monthsAgo).atDay(day))
            .amount(new BigDecimal(amount))
            .nativeCurrency("EUR")
            .build();
        transactions.add(t);
        return t;
    }

    @Test
    void averagesDebitsOverTheMonthsThatActuallyCarriedData() {
        tx(current, 1, 5, "-1000");
        tx(current, 2, 5, "-1200");
        tx(current, 3, 5, "-800");

        EssentialExpenseEstimateResponse response = estimator.estimate(MEMBER);

        // Divided by the three months observed, never by the six looked at: an account synced two
        // months ago would otherwise report a third of what the member really spends.
        assertThat(response.monthsObserved()).isEqualTo(3);
        assertThat(response.estimate()).isEqualByComparingTo("1000.00");
    }

    @Test
    void aMonthWhoseOnlyDebitWasExcludedDoesNotDivideTheAverage() {
        tx(current, 1, 5, "-1000");
        // The whole of month 2 is one internal transfer: its counterpart lands on the livret.
        tx(current, 2, 5, "-500");
        tx(livret, 2, 6, "500");

        EssentialExpenseEstimateResponse response = estimator.estimate(MEMBER);

        // Two months carried transactions but only one carried spending. Counting the other
        // would report 750 for someone who spends 1000, and a safety net sized on the low side
        // is the one failure this estimator must not produce.
        assertThat(response.monthsObserved()).isEqualTo(1);
        assertThat(response.estimate()).isEqualByComparingTo("1000.00");
        assertThat(response.excludedTransferCount()).isEqualTo(1);
    }

    @Test
    void aMonthWhoseAmountsCouldNotBeConvertedDoesNotDivideTheAverageEither() {
        tx(current, 1, 5, "-1000");
        Transaction unconvertible = tx(current, 2, 5, "-9999");
        when(priceService.toEur(unconvertible.getAmount().abs(), "EUR", null)).thenReturn(null);

        EssentialExpenseEstimateResponse response = estimator.estimate(MEMBER);

        // Same reasoning as above: a month dropped from the numerator must leave the divisor.
        assertThat(response.monthsObserved()).isEqualTo(1);
        assertThat(response.estimate()).isEqualByComparingTo("1000.00");
    }

    @Test
    void ignoresTheCurrentMonthSoAPartialOneCannotDragTheAverageDown() {
        tx(current, 1, 5, "-1000");
        tx(current, 0, 2, "-50");   // current month, two days in

        EssentialExpenseEstimateResponse response = estimator.estimate(MEMBER);

        // The repository is asked for a window that ends with the last complete month, so the
        // partial one never reaches the average — the direction that under-sizes a safety net.
        assertThat(response.monthsObserved()).isEqualTo(1);
        assertThat(response.estimate()).isEqualByComparingTo("1000.00");
    }

    @Test
    void excludesATransferWhoseCounterpartLandsOnAnotherAccount() {
        tx(current, 1, 5, "-1000");     // real spending
        tx(current, 1, 10, "-500");     // savings top-up...
        tx(livret, 1, 11, "500");       // ...and its other leg, a day later

        EssentialExpenseEstimateResponse response = estimator.estimate(MEMBER);

        assertThat(response.excludedTransferCount()).isEqualTo(1);
        assertThat(response.estimate()).isEqualByComparingTo("1000.00");
    }

    @Test
    void oneIncomingCreditCannotExcuseSeveralDebitsOfTheSameAmount() {
        tx(current, 1, 5, "-500");
        tx(current, 1, 6, "-500");
        tx(livret, 1, 5, "500");

        EssentialExpenseEstimateResponse response = estimator.estimate(MEMBER);

        // The matched credit is consumed, so only the first debit is written off as a transfer.
        assertThat(response.excludedTransferCount()).isEqualTo(1);
        assertThat(response.estimate()).isEqualByComparingTo("500.00");
    }

    @Test
    void aCreditTooFarApartIsNotTreatedAsTheOtherLeg() {
        tx(current, 1, 5, "-500");
        tx(livret, 1, 20, "500");

        assertThat(estimator.estimate(MEMBER).excludedTransferCount()).isZero();
    }

    @Test
    void excludesInvestmentLegs() {
        tx(current, 1, 5, "-1000");
        Transaction buy = tx(current, 1, 6, "-3000");
        buy.setTxType(TransactionType.BUY);
        buy.setTicker("CW8");

        EssentialExpenseEstimateResponse response = estimator.estimate(MEMBER);

        assertThat(response.estimate()).isEqualByComparingTo("1000.00");
    }

    @Test
    void fallsBackToTheLabelOnlyWhenNoCounterpartWasFound() {
        tx(current, 1, 5, "-1000");
        Transaction labelled = tx(current, 1, 6, "-400");
        labelled.setDescription("VIREMENT INTERNE VERS LIVRET");

        EssentialExpenseEstimateResponse response = estimator.estimate(MEMBER);

        assertThat(response.excludedTransferCount()).isEqualTo(1);
        assertThat(response.estimate()).isEqualByComparingTo("1000.00");
    }

    @Test
    void ordinaryTransferWordingIsNotEnoughToExcludeSpending() {
        // "VIREMENT" alone covers rent as readily as a savings top-up. The label rules stay
        // narrow precisely so a false positive cannot shrink the safety-net target.
        tx(current, 1, 5, "-1000");
        Transaction rent = tx(current, 1, 6, "-900");
        rent.setDescription("VIREMENT SCI DUPONT LOYER");

        assertThat(estimator.estimate(MEMBER).excludedTransferCount()).isZero();
    }

    @Test
    void countsOnlyCurrentAccounts() {
        tx(current, 1, 5, "-1000");
        tx(livret, 1, 5, "-2000");

        assertThat(estimator.estimate(MEMBER).estimate()).isEqualByComparingTo("1000.00");
    }

    @Test
    void appliesTheMembersShareSoAJointAccountIsNotCountedTwice() {
        shares.put(current.getId(), new BigDecimal("50"));
        tx(current, 1, 5, "-1000");

        assertThat(estimator.estimate(MEMBER).estimate()).isEqualByComparingTo("500.00");
    }

    @Test
    void withoutHistoryItSaysSoRatherThanReturningZero() {
        EssentialExpenseEstimateResponse response = estimator.estimate(MEMBER);

        // Zero would be indistinguishable from "this member spends nothing", and would set a
        // safety-net target of zero.
        assertThat(response.estimate()).isNull();
        assertThat(response.monthsObserved()).isZero();
    }

    @Test
    void withoutAnyAccountItReturnsEmptyWithoutQuerying() {
        accounts.clear();

        assertThat(estimator.estimate(MEMBER).estimate()).isNull();
        // A null estimate also comes back when the query runs and finds nothing, so without this
        // the test name is the only thing claiming the early return happened.
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void creditsOnCurrentAccountsAreNotCountedAsSpending() {
        tx(current, 1, 1, "2500");      // salary
        tx(current, 1, 5, "-1000");

        assertThat(estimator.estimate(MEMBER).estimate()).isEqualByComparingTo("1000.00");
    }

    @Test
    void looksBackSixCompleteMonths() {
        estimator.estimate(MEMBER);

        LocalDate expectedFrom = YearMonth.now().minusMonths(6).atDay(1);
        LocalDate expectedTo = YearMonth.now().minusMonths(1).atEndOfMonth();
        org.mockito.Mockito.verify(transactionRepository)
            .findByAccountIdInAndDateBetweenOrderByDateAsc(any(), org.mockito.ArgumentMatchers.eq(expectedFrom),
                org.mockito.ArgumentMatchers.eq(expectedTo));
    }
}
