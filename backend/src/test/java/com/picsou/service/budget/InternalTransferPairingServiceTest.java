package com.picsou.service.budget;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalTransferPairingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);
    private static final long MEMBER = 7L;

    @Mock AccountRepository accounts;
    @Mock TransactionRepository transactions;
    @Mock CategorizationService categorization;

    private InternalTransferPairingService service;
    private Category virement;
    private Category epargne;
    private Category courses;
    private Account checking;
    private Account livret;
    private Account pea;

    @BeforeEach
    void setUp() {
        service = new InternalTransferPairingService(accounts, transactions, categorization);
        virement = category(102L, "virement-interne", CategoryKind.TRANSFER);
        epargne = category(103L, "epargne", CategoryKind.TRANSFER);
        courses = category(104L, "courses", CategoryKind.EXPENSE);
        lenient().when(categorization.categoriesBySlug(MEMBER)).thenReturn(Map.of(
            "virement-interne", virement, "epargne", epargne, "courses", courses));
        checking = account(1L, AccountType.CHECKING);
        livret = account(2L, AccountType.LIVRET_A);
        pea = account(3L, AccountType.PEA);
    }

    @Test
    void anUncategorisedDebitAndCreditOnTwoOwnedAccountsBecomeAnInternalTransfer() {
        Transaction out = tx(10L, checking, "2026-11-02", "-500.00");
        Transaction in = tx(11L, livret, "2026-11-03", "500");
        stage(List.of(checking, livret), out, in);

        assertThat(service.pairRecent(MEMBER, TODAY)).isEqualTo(2);

        assertThat(out.getCategoryRef()).isSameAs(virement);
        assertThat(in.getCategoryRef()).isSameAs(virement);
        assertThat(out.isCategoryManual()).isFalse();
        verify(transactions).saveAll(List.of(out, in));
        verify(transactions).findByAccountIdInAndDateBetweenOrderByDateAsc(
            eq(List.of(1L, 2L)), eq(TODAY.minusDays(93)), eq(TODAY.plusDays(3)));
    }

    /** The case in the issue: one leg already a transfer, the Livret credit uncategorised. */
    @Test
    void aLegAlreadyInATransferCategoryKeepsItAndItsPartnerIsCategorised() {
        Transaction out = tx(10L, checking, "2026-11-02", "-500");
        out.setCategoryRef(epargne);
        Transaction in = tx(11L, livret, "2026-11-02", "500");
        stage(List.of(checking, livret), out, in);

        assertThat(service.pairRecent(MEMBER, TODAY)).isEqualTo(1);

        assertThat(out.getCategoryRef()).isSameAs(epargne);
        assertThat(in.getCategoryRef()).isSameAs(virement);
        verify(transactions).saveAll(List.of(in));
    }

    @Test
    void anAutoCategorisedExpenseLegIsRecategorisedAndItsAiSuggestionCleared() {
        Transaction out = tx(10L, checking, "2026-11-02", "-120");
        out.setCategoryRef(courses);
        out.setAiSuggestedCategoryId(104L);
        out.setAiConfidence(55);
        Transaction in = tx(11L, livret, "2026-11-04", "120");
        stage(List.of(checking, livret), out, in);

        assertThat(service.pairRecent(MEMBER, TODAY)).isEqualTo(2);

        assertThat(out.getCategoryRef()).isSameAs(virement);
        assertThat(out.getAiSuggestedCategoryId()).isNull();
        assertThat(out.getAiConfidence()).isNull();
    }

    @Test
    void aManuallyCategorisedLegIsNeverOverriddenNorPaired() {
        Transaction out = tx(10L, checking, "2026-11-02", "-500");
        out.setCategoryRef(courses);
        out.setCategoryManual(true);
        Transaction in = tx(11L, livret, "2026-11-02", "500");
        Transaction handOut = tx(12L, checking, "2026-11-09", "-40");
        handOut.setManual(true);
        handOut.setCategoryRef(courses);
        Transaction handIn = tx(13L, livret, "2026-11-09", "40");
        stage(List.of(checking, livret), out, in, handOut, handIn);

        assertThat(service.pairRecent(MEMBER, TODAY)).isZero();

        assertThat(out.getCategoryRef()).isSameAs(courses);
        assertThat(handOut.getCategoryRef()).isSameAs(courses);
        assertThat(in.getCategoryRef()).isNull();
        verify(transactions, never()).saveAll(any());
    }

    @Test
    void legsOutsideTheWindowOnlyMakeAGroupAmbiguous() {
        Transaction edgeOut = tx(10L, checking, "2026-08-17", "-300");
        Transaction edgeIn = tx(11L, livret, "2026-08-18", "300");
        Transaction rivalBeforeWindow = tx(12L, pea, "2026-08-14", "300");
        Transaction oldOut = tx(13L, checking, "2026-08-14", "-75");
        Transaction oldIn = tx(14L, livret, "2026-08-15", "75");
        Transaction todayOut = tx(15L, checking, "2026-11-14", "-60");
        Transaction futureIn = tx(16L, livret, "2026-11-16", "60");
        stage(List.of(checking, livret, pea),
            rivalBeforeWindow, oldOut, oldIn, edgeOut, edgeIn, todayOut, futureIn);

        assertThat(service.pairRecent(MEMBER, TODAY)).isZero();

        assertThat(List.of(edgeOut, edgeIn, rivalBeforeWindow, oldOut, oldIn, todayOut, futureIn))
            .allSatisfy(leg -> assertThat(leg.getCategoryRef()).isNull());
        verify(transactions, never()).saveAll(any());
    }

    @Test
    void twoCandidateCreditsLeaveTheDebitAlone() {
        Transaction out = tx(10L, checking, "2026-11-02", "-500");
        Transaction inLivret = tx(11L, livret, "2026-11-02", "500");
        Transaction inPea = tx(12L, pea, "2026-11-03", "500");
        stage(List.of(checking, livret, pea), out, inLivret, inPea);

        assertThat(service.pairRecent(MEMBER, TODAY)).isZero();

        assertThat(out.getCategoryRef()).isNull();
        assertThat(inLivret.getCategoryRef()).isNull();
        assertThat(inPea.getCategoryRef()).isNull();
    }

    @Test
    void twoCandidateDebitsLeaveTheCreditAlone() {
        Transaction outChecking = tx(10L, checking, "2026-11-02", "-500");
        Transaction outPea = tx(11L, pea, "2026-11-02", "-500");
        Transaction in = tx(12L, livret, "2026-11-02", "500");
        stage(List.of(checking, livret, pea), outChecking, outPea, in);

        assertThat(service.pairRecent(MEMBER, TODAY)).isZero();
        assertThat(in.getCategoryRef()).isNull();
    }

    @Test
    void anAmbiguousGroupDoesNotBlockAnUnrelatedUniquePair() {
        Transaction out = tx(10L, checking, "2026-11-02", "-500");
        Transaction inLivret = tx(11L, livret, "2026-11-02", "500");
        Transaction inPea = tx(12L, pea, "2026-11-02", "500");
        Transaction rentOut = tx(13L, checking, "2026-11-05", "-80");
        Transaction rentIn = tx(14L, pea, "2026-11-05", "80");
        stage(List.of(checking, livret, pea), out, inLivret, inPea, rentOut, rentIn);

        assertThat(service.pairRecent(MEMBER, TODAY)).isEqualTo(2);

        assertThat(rentOut.getCategoryRef()).isSameAs(virement);
        assertThat(rentIn.getCategoryRef()).isSameAs(virement);
        assertThat(out.getCategoryRef()).isNull();
    }

    /** A manual leg is the credit's real partner: an unrelated debit must not take its place. */
    @Test
    void aManualLegStillMakesItsGroupAmbiguous() {
        Transaction manualOut = tx(10L, checking, "2026-11-02", "-50");
        manualOut.setCategoryRef(epargne);
        manualOut.setCategoryManual(true);
        Transaction in = tx(11L, livret, "2026-11-02", "50");
        Transaction rent = tx(12L, pea, "2026-11-03", "-50");
        stage(List.of(checking, livret, pea), manualOut, in, rent);

        assertThat(service.pairRecent(MEMBER, TODAY)).isZero();

        assertThat(rent.getCategoryRef()).isNull();
        assertThat(in.getCategoryRef()).isNull();
    }

    /** A purchase refunded on the same account is not a transfer to an unrelated credit. */
    @Test
    void aSameAccountRefundMakesTheDebitAmbiguous() {
        Transaction purchase = tx(10L, checking, "2026-11-01", "-100");
        Transaction refund = tx(11L, checking, "2026-11-02", "100");
        Transaction unrelatedIn = tx(12L, livret, "2026-11-02", "100");
        stage(List.of(checking, livret), purchase, refund, unrelatedIn);

        assertThat(service.pairRecent(MEMBER, TODAY)).isZero();

        assertThat(purchase.getCategoryRef()).isNull();
        assertThat(unrelatedIn.getCategoryRef()).isNull();
    }

    @Test
    void legsAtMostThreeDaysApartArePairedAndLaterOnesAreNot() {
        Transaction out = tx(10L, checking, "2026-11-01", "-500");
        Transaction in = tx(11L, livret, "2026-11-04", "500");
        Transaction lateOut = tx(12L, checking, "2026-11-01", "-70");
        Transaction lateIn = tx(13L, livret, "2026-11-05", "70");
        stage(List.of(checking, livret), out, in, lateOut, lateIn);

        assertThat(service.pairRecent(MEMBER, TODAY)).isEqualTo(2);
        assertThat(lateOut.getCategoryRef()).isNull();
    }

    @Test
    void differentCurrenciesAmountsOrASingleAccountAreNotPaired() {
        Transaction out = tx(10L, checking, "2026-11-02", "-500");
        Transaction inUsd = tx(11L, livret, "2026-11-02", "500");
        inUsd.setNativeCurrency("USD");
        Transaction outOther = tx(12L, checking, "2026-11-06", "-200");
        Transaction inOther = tx(13L, livret, "2026-11-06", "200.01");
        Transaction refundOut = tx(14L, checking, "2026-11-10", "-30");
        Transaction refundIn = tx(15L, checking, "2026-11-11", "30");
        stage(List.of(checking, livret), out, inUsd, outOther, inOther, refundOut, refundIn);

        assertThat(service.pairRecent(MEMBER, TODAY)).isZero();
    }

    @Test
    void tradeLegsAndBudgetNeutralRowsAreNeverPaired() {
        Transaction buy = tx(10L, checking, "2026-11-02", "-500");
        buy.setTxType(TransactionType.BUY);
        Transaction deposit = tx(11L, pea, "2026-11-02", "500");
        Transaction trading = tx(12L, checking, "2026-11-06", "-300");
        trading.setCategory("TRADING");
        Transaction neutral = tx(13L, pea, "2026-11-06", "300");
        neutral.setBudgetNeutral(true);
        stage(List.of(checking, pea), buy, deposit, trading, neutral);

        assertThat(service.pairRecent(MEMBER, TODAY)).isZero();
    }

    @Test
    void aSecondRunChangesNothing() {
        Transaction out = tx(10L, checking, "2026-11-02", "-500");
        Transaction in = tx(11L, livret, "2026-11-02", "500");
        stage(List.of(checking, livret), out, in);

        assertThat(service.pairRecent(MEMBER, TODAY)).isEqualTo(2);
        assertThat(service.pairRecent(MEMBER, TODAY)).isZero();

        verify(transactions).saveAll(List.of(out, in));
    }

    @Test
    void loanAccountsAreLeftOutSoARepaymentStaysAnExpense() {
        Account loan = account(4L, AccountType.LOAN);
        Transaction repayment = tx(10L, checking, "2026-11-02", "-900");
        when(accounts.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER)).thenReturn(List.of(checking, loan));

        assertThat(service.pairRecent(MEMBER, TODAY)).isZero();

        assertThat(repayment.getCategoryRef()).isNull();
        verify(transactions, never()).findByAccountIdInAndDateBetweenOrderByDateAsc(anyCollection(), any(), any());
    }

    @Test
    void withoutATransferCategoryNothingIsWritten() {
        when(categorization.categoriesBySlug(MEMBER)).thenReturn(Map.of("courses", courses));
        Transaction out = tx(10L, checking, "2026-11-02", "-500");
        Transaction in = tx(11L, livret, "2026-11-02", "500");
        stage(List.of(checking, livret), out, in);

        assertThat(service.pairRecent(MEMBER, TODAY)).isZero();

        assertThat(out.getCategoryRef()).isNull();
        verify(transactions, never()).saveAll(any());
    }

    private void stage(List<Account> owned, Transaction... rows) {
        when(accounts.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER)).thenReturn(owned);
        when(transactions.findByAccountIdInAndDateBetweenOrderByDateAsc(anyCollection(), any(), any()))
            .thenReturn(List.of(rows));
    }

    private static Account account(long id, AccountType type) {
        return Account.builder().id(id).name(type.name()).type(type).currency("EUR").build();
    }

    private static Transaction tx(long id, Account account, String date, String amount) {
        return Transaction.builder()
            .id(id).account(account).date(LocalDate.parse(date))
            .amount(new BigDecimal(amount)).description("VIR").nativeCurrency("EUR").build();
    }

    private static Category category(long id, String slug, CategoryKind kind) {
        return Category.builder().id(id).slug(slug).name(slug).kind(kind).build();
    }
}
