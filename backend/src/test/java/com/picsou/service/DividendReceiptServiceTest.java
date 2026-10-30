package com.picsou.service;

import com.picsou.dto.ReceivedDividendResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.DividendEvent;
import com.picsou.model.DividendSource;
import com.picsou.model.DividendSourceStatus;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.DividendSourceRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
class DividendReceiptServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);
    private static final long MEMBER = 7L;

    @Mock AccountRepository accounts;
    @Mock AccountAccessResolver access;
    @Mock AccountHoldingRepository holdings;
    @Mock DividendSourceRepository sources;
    @Mock TransactionRepository transactions;
    @Mock CategorizationService categorization;

    private DividendReceiptService service;
    private Category revenusCapital;
    private Category autresRevenus;
    private Category virement;

    @BeforeEach
    void setUp() {
        service = new DividendReceiptService(accounts, access, holdings, sources, transactions, categorization);
        revenusCapital = category(100L, "revenus-capital", CategoryKind.INCOME);
        autresRevenus = category(101L, "autres-revenus", CategoryKind.INCOME);
        virement = category(102L, "virement-interne", CategoryKind.TRANSFER);
        lenient().when(categorization.categoriesBySlug(MEMBER)).thenReturn(Map.of(
            "revenus-capital", revenusCapital,
            "autres-revenus", autresRevenus,
            "virement-interne", virement));
    }

    @Test
    void aLabelledCreditThatFitsTheForecastBecomesADividend() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "59.50", "DIVIDENDE TOTALENERGIES");
        stage(List.of(cto), List.of(tx), List.of(line(cto, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isEqualTo(1);

        assertThat(tx.getTxType()).isEqualTo(TransactionType.DIVIDEND);
        assertThat(tx.getTicker()).isEqualTo("TTE.PA");
        assertThat(tx.getName()).isEqualTo("TotalEnergies");
        assertThat(tx.getCategoryRef()).isSameAs(revenusCapital);
        verify(transactions).saveAll(List.of(tx));
    }

    @Test
    void aSecondRunChangesNothing() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "59.50", "DIVIDENDE TOTALENERGIES");
        stage(List.of(cto), List.of(tx), List.of(line(cto, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isEqualTo(1);
        assertThat(service.classifyRecent(MEMBER, TODAY)).isZero();

        verify(transactions, org.mockito.Mockito.times(1)).saveAll(any());
    }

    @Test
    void aBareAmountCoincidenceWithoutLabelEvidenceStaysUntouched() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "85.00", "VIREMENT RECU");
        stage(List.of(cto), List.of(tx), List.of(line(cto, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isZero();

        assertThat(tx.getTxType()).isNull();
        assertThat(tx.getTicker()).isNull();
        assertThat(tx.getCategoryRef()).isNull();
        verify(transactions, never()).saveAll(any());
    }

    @Test
    void aKeywordWithTwoFittingCandidatesAndNoInstrumentMatchIsAmbiguous() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "50.00", "DIVIDENDE");
        stage(List.of(cto), List.of(tx),
            List.of(line(cto, "TTE.PA", "TotalEnergies", "100"), line(cto, "SAN.PA", "Sanofi", "50")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null)),
            source("SAN.PA", "EUR", event("2026-10-02", "1.00", "1.00", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isZero();

        assertThat(tx.getTxType()).isNull();
        verify(transactions, never()).saveAll(any());
    }

    @Test
    void theLabelNamingOneInstrumentPicksIt() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "50.00", "COUPON SANOFI");
        stage(List.of(cto), List.of(tx),
            List.of(line(cto, "TTE.PA", "TotalEnergies", "100"), line(cto, "SAN.PA", "Sanofi", "50")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null)),
            source("SAN.PA", "EUR", event("2026-10-02", "1.00", "1.00", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isEqualTo(1);

        assertThat(tx.getTicker()).isEqualTo("SAN.PA");
        assertThat(tx.getName()).isEqualTo("Sanofi");
    }

    @Test
    void anAmountOutsideTheRatioBandIsNotThatDividend() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tooSmall = credit(1L, cto, "2026-10-10", "10.00", "DIVIDENDE TOTALENERGIES");
        Transaction tooBig = credit(2L, cto, "2026-10-11", "100.00", "DIVIDENDE TOTALENERGIES");
        stage(List.of(cto), List.of(tooSmall, tooBig), List.of(line(cto, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isZero();

        assertThat(tooSmall.getTxType()).isNull();
        assertThat(tooBig.getTxType()).isNull();
    }

    @Test
    void aCreditThirtyDaysBeforeTheExDateIsNotThatDividend() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "59.50", "DIVIDENDE TOTALENERGIES");
        stage(List.of(cto), List.of(tx), List.of(line(cto, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-11-09", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isZero();

        assertThat(tx.getTxType()).isNull();
    }

    @Test
    void aCreditSixtyOneDaysAfterTheExDateIsAnotherPayment() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-12", "59.50", "DIVIDENDE TOTALENERGIES");
        stage(List.of(cto), List.of(tx), List.of(line(cto, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-08-12", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isZero();

        assertThat(tx.getTxType()).isNull();
    }

    @Test
    void aPaymentDateFarFromTheCreditRejectsTheEvent() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "59.50", "DIVIDENDE TOTALENERGIES");
        stage(List.of(cto), List.of(tx), List.of(line(cto, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", "2026-09-01")));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isZero();

        assertThat(tx.getTxType()).isNull();
    }

    @Test
    void aPaymentDateWithinSevenDaysKeepsTheEvent() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "59.50", "DIVIDENDE TOTALENERGIES");
        stage(List.of(cto), List.of(tx), List.of(line(cto, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", "2026-10-03")));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isEqualTo(1);
    }

    @Test
    void aManualCategoryIsNeverTouched() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "59.50", "DIVIDENDE TOTALENERGIES");
        tx.setCategoryRef(autresRevenus);
        tx.setCategoryManual(true);
        stage(List.of(cto), List.of(tx), List.of(line(cto, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isZero();

        assertThat(tx.getTxType()).isNull();
        assertThat(tx.getCategoryRef()).isSameAs(autresRevenus);
    }

    @Test
    void anAutoTransferCategoryIsNeverRetypedEvenWithAKeyword() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "59.50", "DIVIDENDE TOTALENERGIES");
        tx.setCategoryRef(virement);
        stage(List.of(cto), List.of(tx), List.of(line(cto, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isZero();

        assertThat(tx.getTxType()).isNull();
        assertThat(tx.getTicker()).isNull();
        assertThat(tx.getCategoryRef()).isSameAs(virement);
        verify(transactions, never()).saveAll(any());
    }

    @Test
    void anAutoIncomeCategoryIsReplacedByCapitalIncome() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "59.50", "DIVIDENDE TOTALENERGIES");
        tx.setCategoryRef(autresRevenus);
        stage(List.of(cto), List.of(tx), List.of(line(cto, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isEqualTo(1);

        assertThat(tx.getCategoryRef()).isSameAs(revenusCapital);
    }

    @Test
    void aRowAlreadyTypedAsDividendByTheBrokerIsFiledWithoutAKeyword() {
        Account cto = account(12L, "CTO Fortuneo", AccountType.COMPTE_TITRES, "Fortuneo");
        Transaction tx = credit(1L, cto, "2026-10-10", "59.50", "ENCAISSEMENT");
        tx.setTxType(TransactionType.DIVIDEND);
        tx.setTicker("TTE.PA");
        stage(List.of(cto), List.of(tx), List.of(line(cto, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isEqualTo(1);

        assertThat(tx.getTicker()).isEqualTo("TTE.PA");
        assertThat(tx.getCategoryRef()).isSameAs(revenusCapital);
    }

    @Test
    void tradeRepublicCashCreditMatchesTheLineHeldOnThePea() {
        Account cash = account(20L, "TR Cash", AccountType.CHECKING, "Trade Republic");
        Account pea = account(21L, "TR PEA", AccountType.PEA, "Trade Republic");
        Transaction tx = credit(1L, cash, "2026-10-10", "59.50", "Dividend");
        stage(List.of(cash, pea), List.of(tx), List.of(line(pea, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isEqualTo(1);

        assertThat(tx.getTicker()).isEqualTo("TTE.PA");
        assertThat(tx.getTxType()).isEqualTo(TransactionType.DIVIDEND);
    }

    @Test
    void aUsdCreditMatchesTheSourceAmountAndAGbpCreditOnAUsdSourceIsSkipped() {
        Account usd = account(30L, "IBKR", AccountType.COMPTE_TITRES, null);
        Transaction inUsd = credit(1L, usd, "2026-10-10", "50.00", "DIVIDEND MICROSOFT");
        inUsd.setNativeCurrency("USD");
        Transaction inGbp = credit(2L, usd, "2026-10-11", "50.00", "DIVIDEND MICROSOFT");
        inGbp.setNativeCurrency("GBP");
        // amountEur (0.46 x 100 = 46) is deliberately far outside the band for 50 USD only if
        // used; the USD amount (0.75 x 100 = 75) fits.
        stage(List.of(usd), List.of(inUsd, inGbp), List.of(line(usd, "MSFT", "Microsoft", "100")),
            source("MSFT", "USD", event("2026-10-01", "0.75", "0.10", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isEqualTo(1);

        assertThat(inUsd.getTxType()).isEqualTo(TransactionType.DIVIDEND);
        assertThat(inGbp.getTxType()).isNull();
    }

    @Test
    void aGbpCreditMatchesAPenceSourceInPounds() {
        Account isa = account(31L, "ISA", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, isa, "2026-10-10", "1.75", "DIVIDEND LLOYDS");
        tx.setNativeCurrency("GBP");
        stage(List.of(isa), List.of(tx), List.of(line(isa, "LLOY.L", "Lloyds Banking Group", "100")),
            source("LLOY.L", "GBp", event("2026-10-01", "2.5", "0.029", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isEqualTo(1);

        assertThat(tx.getTicker()).isEqualTo("LLOY.L");
    }

    @Test
    void receivedInGbpReportsAPenceSourcePerShareInPounds() {
        Account isa = account(31L, "ISA", AccountType.COMPTE_TITRES, null);
        Transaction tx = dividend(1L, isa, "2026-10-10", "1.75");
        tx.setTicker("LLOY.L");
        tx.setNativeCurrency("GBP");
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(isa));
        when(transactions.findByAccountIdInAndDateBetweenOrderByDateAsc(anyCollection(), any(), any()))
            .thenReturn(List.of(tx));
        when(sources.findAllWithEventsByTickerIn(anyCollection())).thenReturn(List.of(
            source("LLOY.L", "GBX", event("2026-10-01", "2.5", "0.029", null))));

        assertThat(service.received(MEMBER, TODAY, 30)).singleElement()
            .satisfies(line -> assertThat(line.amountPerShare()).isEqualByComparingTo("0.025"));
    }

    @Test
    void aManualEntryWithACategoryKeepsIt() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "59.50", "DIVIDENDE TOTALENERGIES");
        tx.setManual(true);
        tx.setCategoryRef(autresRevenus);
        stage(List.of(cto), List.of(tx), List.of(line(cto, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isZero();

        assertThat(tx.getCategoryRef()).isSameAs(autresRevenus);
        verify(transactions, never()).saveAll(any());
    }

    @Test
    void receivedExposesTheForecastIdAndTheBudgetFlag() {
        Account pea = account(12L, "PEA", AccountType.PEA, null);
        Account cto = account(13L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction inPea = dividend(1L, pea, "2026-10-10", "59.50");
        Transaction inCto = dividend(2L, cto, "2026-10-10", "59.50");
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(pea, cto));
        when(transactions.findByAccountIdInAndDateBetweenOrderByDateAsc(
            anyCollection(), eq(TODAY.minusDays(30)), eq(TODAY))).thenReturn(List.of(inPea, inCto));
        when(sources.findAllWithEventsByTickerIn(anyCollection())).thenReturn(List.of(
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null))));

        List<ReceivedDividendResponse> out = service.received(MEMBER, TODAY, 30);

        assertThat(out).extracting(ReceivedDividendResponse::id)
            .containsExactly("HOLDING:13:TTE.PA:2026-10-01", "HOLDING:12:TTE.PA:2026-10-01");
        assertThat(out.get(0).countsInBudget()).isTrue();
        assertThat(out.get(1).countsInBudget()).isFalse();
        assertThat(out.get(0).exDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(out.get(0).amountPerShare()).isEqualByComparingTo("0.85");
        assertThat(out.get(0).amount()).isEqualByComparingTo("59.50");
    }

    @Test
    void receivedFallsBackToTheTransactionIdWhenNoExDateIsKnown() {
        Account cto = account(13L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = dividend(5L, cto, "2026-10-10", "59.50");
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(cto));
        when(transactions.findByAccountIdInAndDateBetweenOrderByDateAsc(anyCollection(), any(), any()))
            .thenReturn(List.of(tx));
        when(sources.findAllWithEventsByTickerIn(anyCollection())).thenReturn(List.of());

        assertThat(service.received(MEMBER, TODAY, 30)).singleElement().satisfies(line -> {
            assertThat(line.id()).isEqualTo("HOLDING:13:TTE.PA:tx5");
            assertThat(line.exDate()).isNull();
        });
    }

    @Test
    void tradeRepublicBuyAndSellLegsAreNeverDividends() {
        Account cash = account(20L, "TR Cash", AccountType.CHECKING, "Trade Republic");
        Account pea = account(21L, "TR PEA", AccountType.PEA, "Trade Republic");
        Category investissement = category(103L, "investissement", CategoryKind.TRANSFER);
        Category achat = category(104L, "investissement-achat", CategoryKind.EXPENSE);
        Transaction buyLeg = credit(1L, pea, "2026-10-10", "55.00", "BUY TotalEnergies");
        buyLeg.setTxType(TransactionType.DEPOSIT);
        buyLeg.setCategory("TRADING");
        buyLeg.setCategoryRef(investissement);
        Transaction sellLeg = credit(2L, cash, "2026-10-10", "60.00", "SELL TotalEnergies");
        sellLeg.setTxType(TransactionType.DEPOSIT);
        sellLeg.setCategory("TRADING");
        sellLeg.setCategoryRef(achat);
        Transaction uncategorisedSell = credit(3L, cash, "2026-10-11", "60.00", "SELL TotalEnergies");
        uncategorisedSell.setTxType(TransactionType.DEPOSIT);
        uncategorisedSell.setCategory("TRADING");
        Transaction dividend = credit(4L, cash, "2026-10-10", "59.50", "Dividend");
        dividend.setTxType(TransactionType.DEPOSIT);
        stage(List.of(cash, pea), List.of(buyLeg, sellLeg, uncategorisedSell, dividend),
            List.of(line(pea, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isEqualTo(1);

        assertThat(buyLeg.getTxType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(buyLeg.getTicker()).isNull();
        assertThat(sellLeg.getTxType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(sellLeg.getTicker()).isNull();
        assertThat(uncategorisedSell.getTxType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(dividend.getTxType()).isEqualTo(TransactionType.DIVIDEND);
        verify(transactions).saveAll(List.of(dividend));
    }

    @Test
    void individuelIsNotTheDividendKeyword() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "50.00", "VIREMENT COMPTE TITRES INDIVIDUEL");
        tx.setTxType(TransactionType.DEPOSIT);
        stage(List.of(cto), List.of(tx), List.of(line(cto, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isZero();

        assertThat(tx.getTxType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(tx.getCategoryRef()).isNull();
    }

    @Test
    void aKeywordIsAWordPrefix() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "59.50", "DIVIDENDES RECUS");
        stage(List.of(cto), List.of(tx), List.of(line(cto, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isEqualTo(1);
    }

    @Test
    void aThreeLetterTickerRootAloneIsNotEvidence() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction plain = credit(1L, cto, "2026-10-10", "50.00", "VIREMENT AIR LIQUIDE");
        Transaction withKeyword = credit(2L, cto, "2026-10-11", "50.00", "COUPON AIR");
        stage(List.of(cto), List.of(plain, withKeyword),
            List.of(line(cto, "AIR.PA", "Airbus", "50"), line(cto, "TTE.PA", "TotalEnergies", "100")),
            source("AIR.PA", "EUR", event("2026-10-01", "1.00", "1.00", null)),
            source("TTE.PA", "EUR", event("2026-10-01", "0.55", "0.55", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isEqualTo(1);

        assertThat(plain.getTxType()).isNull();
        assertThat(withKeyword.getTicker()).isEqualTo("AIR.PA");
    }

    @Test
    void receivedOnTradeRepublicCashUsesTheForecastIdOfThePeaLine() {
        Account cash = account(20L, "TR Cash", AccountType.CHECKING, "Trade Republic");
        Account pea = account(21L, "TR PEA", AccountType.PEA, "Trade Republic");
        Transaction tx = dividend(1L, cash, "2026-10-10", "59.50");
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(cash, pea));
        when(transactions.findByAccountIdInAndDateBetweenOrderByDateAsc(anyCollection(), any(), any()))
            .thenReturn(List.of(tx));
        when(holdings.findPositiveByAccountIdIn(anyCollection()))
            .thenReturn(List.of(line(pea, "TTE.PA", "TotalEnergies", "100")));
        when(sources.findAllWithEventsByTickerIn(anyCollection())).thenReturn(List.of(
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null))));

        assertThat(service.received(MEMBER, TODAY, 30)).singleElement().satisfies(line -> {
            assertThat(line.id()).isEqualTo("HOLDING:21:TTE.PA:2026-10-01");
            assertThat(line.accountId()).isEqualTo(21L);
            assertThat(line.accountType()).isEqualTo(AccountType.PEA);
            assertThat(line.countsInBudget()).isFalse();
            assertThat(line.transactionId()).isEqualTo(1L);
        });
    }

    @Test
    void receivedKeepsTheCreditedAccountWhenTwoSameProviderAccountsHoldTheLine() {
        Account cash = account(20L, "TR Cash", AccountType.CHECKING, "Trade Republic");
        Account pea = account(21L, "TR PEA", AccountType.PEA, "Trade Republic");
        Account cto = account(22L, "TR CTO", AccountType.COMPTE_TITRES, "Trade Republic");
        Transaction tx = dividend(1L, cash, "2026-10-10", "59.50");
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(cash, pea, cto));
        when(transactions.findByAccountIdInAndDateBetweenOrderByDateAsc(anyCollection(), any(), any()))
            .thenReturn(List.of(tx));
        when(holdings.findPositiveByAccountIdIn(anyCollection())).thenReturn(List.of(
            line(pea, "TTE.PA", "TotalEnergies", "50"), line(cto, "TTE.PA", "TotalEnergies", "50")));
        when(sources.findAllWithEventsByTickerIn(anyCollection())).thenReturn(List.of());

        assertThat(service.received(MEMBER, TODAY, 30)).singleElement()
            .satisfies(line -> assertThat(line.accountId()).isEqualTo(20L));
    }

    @Test
    void receivedDropsRowsOfAccountsTheMemberCannotRead() {
        Account mine = account(13L, "CTO", AccountType.COMPTE_TITRES, null);
        Account theirs = account(50L, "Other CTO", AccountType.COMPTE_TITRES, null);
        Transaction readable = dividend(1L, mine, "2026-10-10", "59.50");
        Transaction foreign = dividend(2L, theirs, "2026-10-10", "59.50");
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(mine));
        when(transactions.findByAccountIdInAndDateBetweenOrderByDateAsc(anyCollection(), any(), any()))
            .thenReturn(List.of(readable, foreign));
        when(sources.findAllWithEventsByTickerIn(anyCollection())).thenReturn(List.of());

        assertThat(service.received(MEMBER, TODAY, 30))
            .extracting(ReceivedDividendResponse::transactionId).containsExactly(1L);
        verify(transactions).findByAccountIdInAndDateBetweenOrderByDateAsc(
            org.mockito.ArgumentMatchers.argThat(ids -> ids.contains(13L) && !ids.contains(50L)), any(), any());
    }

    @Test
    void receivedOnACoOwnedAccountListsTheCreditAsBooked() {
        Account shared = account(13L, "Joint CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = dividend(1L, shared, "2026-10-10", "59.50");
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(shared));
        when(transactions.findByAccountIdInAndDateBetweenOrderByDateAsc(anyCollection(), any(), any()))
            .thenReturn(List.of(tx));
        when(sources.findAllWithEventsByTickerIn(anyCollection())).thenReturn(List.of(
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null))));

        assertThat(service.received(MEMBER, TODAY, 30)).singleElement()
            .satisfies(line -> assertThat(line.amount()).isEqualByComparingTo("59.50"));
        verify(access, never()).sharesFor(any(), any());
    }

    @Test
    void receivedInUsdUsesTheSourcePerShareAmount() {
        Account ibkr = account(30L, "IBKR", AccountType.COMPTE_TITRES, null);
        Transaction tx = dividend(1L, ibkr, "2026-10-10", "75.00");
        tx.setTicker("MSFT");
        tx.setNativeCurrency("USD");
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(ibkr));
        when(transactions.findByAccountIdInAndDateBetweenOrderByDateAsc(anyCollection(), any(), any()))
            .thenReturn(List.of(tx));
        when(sources.findAllWithEventsByTickerIn(anyCollection())).thenReturn(List.of(
            source("MSFT", "USD", event("2026-10-01", "0.75", "0.69", null))));

        assertThat(service.received(MEMBER, TODAY, 30)).singleElement().satisfies(line -> {
            assertThat(line.currency()).isEqualTo("USD");
            assertThat(line.amountPerShare()).isEqualByComparingTo("0.75");
        });
    }

    @Test
    void aGenericNameWordAloneIsNotEvidence() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "50.00", "VIREMENT BANK DUPONT");
        tx.setTxType(TransactionType.DEPOSIT);
        stage(List.of(cto), List.of(tx), List.of(line(cto, "BAC", "Bank of America", "100")),
            source("BAC", "EUR", event("2026-10-01", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isZero();

        assertThat(tx.getTxType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(tx.getTicker()).isNull();
        assertThat(tx.getCategoryRef()).isNull();
    }

    @Test
    void everyDistinctiveNameWordInTheLabelIsEvidence() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "50.00", "VIREMENT BANK OF AMERICA");
        stage(List.of(cto), List.of(tx), List.of(line(cto, "BAC", "Bank of America", "100")),
            source("BAC", "EUR", event("2026-10-01", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isEqualTo(1);

        assertThat(tx.getTicker()).isEqualTo("BAC");
    }

    @Test
    void anEarlierEventThatFitsIsUsedWhenTheLatestPaymentDateIsFarFromTheCredit() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "59.50", "DIVIDENDE TOTALENERGIES");
        stage(List.of(cto), List.of(tx), List.of(line(cto, "TTE.PA", "TotalEnergies", "100")),
            source("TTE.PA", "EUR",
                event("2026-09-20", "0.85", "0.85", "2026-10-10"),
                event("2026-10-01", "0.90", "0.90", "2026-11-10")));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isEqualTo(1);

        assertThat(tx.getTxType()).isEqualTo(TransactionType.DIVIDEND);
    }

    @Test
    void receivedTakesTheNewestEventWhosePaymentDateFitsTheCredit() {
        Account cto = account(13L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = dividend(1L, cto, "2026-10-10", "59.50");
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(cto));
        when(transactions.findByAccountIdInAndDateBetweenOrderByDateAsc(anyCollection(), any(), any()))
            .thenReturn(List.of(tx));
        when(sources.findAllWithEventsByTickerIn(anyCollection())).thenReturn(List.of(
            source("TTE.PA", "EUR",
                event("2026-08-20", "0.80", "0.80", "2026-10-08"),
                event("2026-09-20", "0.85", "0.85", "2026-10-10"),
                event("2026-10-01", "0.90", "0.90", "2026-11-10"))));

        assertThat(service.received(MEMBER, TODAY, 30)).singleElement().satisfies(line -> {
            assertThat(line.id()).isEqualTo("HOLDING:13:TTE.PA:2026-09-20");
            assertThat(line.exDate()).isEqualTo(LocalDate.of(2026, 9, 20));
            assertThat(line.amountPerShare()).isEqualByComparingTo("0.85");
        });
    }

    @Test
    void receivedListsAManualDividendWithoutATickerOnTheCreditedAccount() {
        Account cto = account(13L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(9L, cto, "2026-10-10", "20.00", "Dividende");
        tx.setManual(true);
        tx.setTxType(TransactionType.DIVIDEND);
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(cto));
        when(transactions.findByAccountIdInAndDateBetweenOrderByDateAsc(anyCollection(), any(), any()))
            .thenReturn(List.of(tx));

        assertThat(service.received(MEMBER, TODAY, 30)).singleElement().satisfies(line -> {
            assertThat(line.id()).isEqualTo("HOLDING:13::tx9");
            assertThat(line.transactionId()).isEqualTo(9L);
            assertThat(line.accountId()).isEqualTo(13L);
            assertThat(line.ticker()).isNull();
            assertThat(line.amount()).isEqualByComparingTo("20.00");
            assertThat(line.exDate()).isNull();
            assertThat(line.amountPerShare()).isNull();
            assertThat(line.countsInBudget()).isTrue();
        });
    }

    @Test
    void aMixedCaseHoldingTickerIsLookedUpInUppercase() {
        Account cto = account(12L, "CTO", AccountType.COMPTE_TITRES, null);
        Transaction tx = credit(1L, cto, "2026-10-10", "59.50", "DIVIDENDE TOTALENERGIES");
        stage(List.of(cto), List.of(tx), List.of(line(cto, "tte.pa", "TotalEnergies", "100")),
            source("TTE.PA", "EUR", event("2026-10-01", "0.85", "0.85", null)));

        assertThat(service.classifyRecent(MEMBER, TODAY)).isEqualTo(1);

        assertThat(tx.getTicker()).isEqualTo("tte.pa");
    }

    @Test
    void daysAreClampedToOneThrough366() {
        assertThat(DividendReceiptService.clampDays(0)).isEqualTo(1);
        assertThat(DividendReceiptService.clampDays(999)).isEqualTo(366);
        assertThat(DividendReceiptService.clampDays(30)).isEqualTo(30);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private void stage(List<Account> owned, List<Transaction> credits, List<AccountHolding> lines,
                       DividendSource... stored) {
        when(accounts.findAllByMemberIdOrderByCreatedAtAsc(MEMBER)).thenReturn(owned);
        when(transactions.findByAccountIdInAndDateBetweenOrderByDateAsc(
            anyCollection(), eq(TODAY.minusDays(45)), eq(TODAY))).thenReturn(credits);
        lenient().when(holdings.findPositiveByAccountIdIn(anyCollection())).thenReturn(lines);
        // Matches stored uppercase tickers only, like the repository's IN clause.
        lenient().when(sources.findAllWithEventsByTickerIn(anyCollection())).thenAnswer(invocation -> {
            Collection<String> tickers = invocation.getArgument(0);
            return Arrays.stream(stored).filter(s -> tickers.contains(s.getTicker())).toList();
        });
    }

    private static Account account(long id, String name, AccountType type, String provider) {
        return Account.builder().id(id).name(name).type(type).provider(provider).currency("EUR").build();
    }

    private static AccountHolding line(Account account, String ticker, String name, String quantity) {
        return AccountHolding.builder()
            .account(account).ticker(ticker).name(name).quantity(new BigDecimal(quantity)).build();
    }

    private static Transaction credit(long id, Account account, String date, String amount, String description) {
        return Transaction.builder()
            .id(id).account(account).date(LocalDate.parse(date))
            .amount(new BigDecimal(amount)).description(description).nativeCurrency("EUR").build();
    }

    private static Transaction dividend(long id, Account account, String date, String amount) {
        Transaction tx = credit(id, account, date, amount, "DIVIDENDE TOTALENERGIES");
        tx.setTxType(TransactionType.DIVIDEND);
        tx.setTicker("TTE.PA");
        return tx;
    }

    private static Category category(long id, String slug, CategoryKind kind) {
        return Category.builder().id(id).slug(slug).name(slug).kind(kind).build();
    }

    private static DividendSource source(String ticker, String currency, DividendEvent... events) {
        DividendSource source = DividendSource.builder()
            .ticker(ticker).currency(currency).status(DividendSourceStatus.OK)
            .events(new ArrayList<>()).build();
        for (DividendEvent event : events) {
            event.setSource(source);
            source.getEvents().add(event);
        }
        return source;
    }

    private static DividendEvent event(String exDate, String amount, String amountEur, String paymentDate) {
        return DividendEvent.builder()
            .exDate(LocalDate.parse(exDate))
            .amount(new BigDecimal(amount))
            .amountEur(new BigDecimal(amountEur))
            .paymentDate(paymentDate == null ? null : LocalDate.parse(paymentDate))
            .build();
    }
}
