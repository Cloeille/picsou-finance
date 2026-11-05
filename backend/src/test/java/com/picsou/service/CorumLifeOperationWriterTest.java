package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.port.CorumPort;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CorumLifeOperationWriterTest {
    private static final Long MEMBER = 7L;

    @Mock TransactionRepository transactionRepository;
    @Mock CategoryService categoryService;
    @InjectMocks CorumLifeOperationWriter writer;

    private final Account account = Account.builder().id(42L).name("CORUM Life").build();
    private final Category investment = Category.builder().id(5L).slug("investissement")
        .kind(CategoryKind.TRANSFER).build();

    // ─── mapping ──────────────────────────────────────────────────────────────

    @Test
    void eachKnownTypeIsMappedOnItsOwnRow() {
        when(categoryService.findOrCreateDefault(MEMBER, "investissement")).thenReturn(Optional.of(investment));

        var result = writer.reconcile(MEMBER, account, allTypes(), holdings());

        assertThat(result).isEqualTo(new CorumLifeOperationWriter.Result(7, 0, 0, 0));
        Map<String, Transaction> rows = byKey(saved());

        Transaction payment = rows.get("corum:9");
        assertThat(payment.getTxType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(payment.isBudgetNeutral()).isFalse();
        assertThat(payment.getCategoryRef()).isSameAs(investment);
        assertThat(payment.getType()).isEqualTo("scheduledPayment");
        assertThat(payment.getDescription()).isEqualTo("Scheduled payment");
        assertThat(payment.getAmount()).isEqualByComparingTo("50");
        assertThat(payment.getDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(payment.getExternalTransactionId()).isEqualTo("corum:9");
        assertThat(payment.getNativeCurrency()).isEqualTo("EUR");
        assertThat(payment.isManual()).isFalse();
        assertThat(payment.getAccount()).isSameAs(account);
        assertThat(payment.getTicker()).isNull();

        Transaction subscription = rows.get("corum:1");
        assertThat(subscription.getTxType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(subscription.isBudgetNeutral()).isFalse();
        assertThat(subscription.getCategoryRef()).isSameAs(investment);

        Transaction coupon = rows.get("corum:10");
        assertThat(coupon.getTxType()).isEqualTo(TransactionType.DIVIDEND);
        assertThat(coupon.isBudgetNeutral()).isTrue();
        assertThat(coupon.getTicker()).isEqualTo("CORUM-LIFE-CC");
        // Named after the holding it belongs to.
        assertThat(coupon.getName()).isEqualTo("CORUM Origin");
        assertThat(coupon.getCategoryRef()).isNull();

        Transaction fees = rows.get("corum:8");
        assertThat(fees.getTxType()).isEqualTo(TransactionType.FEE);
        assertThat(fees.isBudgetNeutral()).isTrue();
        assertThat(fees.getAmount()).isEqualByComparingTo("-1.42");

        Transaction profit = rows.get("corum:5");
        assertThat(profit.getTxType()).isNull();
        assertThat(profit.isBudgetNeutral()).isTrue();

        for (String leg : List.of("corum:6", "corum:7")) {
            assertThat(rows.get(leg).getTxType()).isNull();
            assertThat(rows.get(leg).isBudgetNeutral()).isTrue();
            assertThat(rows.get(leg).getDescription()).isEqualTo("Switch between funds");
        }
        // Never a trade: the holdings are CORUM's own figures.
        assertThat(rows.values()).noneMatch(row ->
            row.getTxType() == TransactionType.BUY || row.getTxType() == TransactionType.SELL);
    }

    @Test
    void aCouponForSeveralFundsOrNoneStaysUnattributed() {
        writer.reconcile(MEMBER, account, List.of(
            op("1", "couponDetachment", "2026-10-31", "3", List.of("EU", "XL")),
            op("2", "couponDetachment", "2026-10-31", "3", null)), holdings());

        assertThat(saved()).allSatisfy(row -> {
            assertThat(row.getTicker()).isNull();
            assertThat(row.getName()).isNull();
            assertThat(row.getTxType()).isEqualTo(TransactionType.DIVIDEND);
        });
    }

    @Test
    void aCouponForAFundTheContractNoLongerHoldsIsNamedAfterItsCode() {
        writer.reconcile(MEMBER, account, List.of(
            op("1", "couponDetachment", "2026-10-31", "3", List.of("eu"))), holdings());

        Transaction coupon = saved().getFirst();
        assertThat(coupon.getTicker()).isEqualTo("CORUM-LIFE-EU");
        assertThat(coupon.getName()).isEqualTo("CORUM EU");
    }

    @Test
    void anUnknownTypeIsImportedNeutralAndCounted() {
        var result = writer.reconcile(MEMBER, account, List.of(
            op("1", "partialSurrender", "2026-10-31", "-200", null)), holdings());

        Transaction row = saved().getFirst();
        assertThat(row.isBudgetNeutral()).isTrue();
        assertThat(row.getTxType()).isNull();
        assertThat(row.getType()).isEqualTo("partialSurrender");
        assertThat(row.getDescription()).isEqualTo("CORUM Life operation");
        assertThat(result.unknownTypes()).isEqualTo(1);
        verifyNoInteractions(categoryService);
    }

    @Test
    void aPaymentStaysUncategorizedWhenTheInvestmentCategoryWasArchived() {
        when(categoryService.findOrCreateDefault(MEMBER, "investissement")).thenReturn(Optional.empty());

        writer.reconcile(MEMBER, account, List.of(op("1", "scheduledPayment", "2026-10-05", "50", null)),
            holdings());

        assertThat(saved().getFirst().getCategoryRef()).isNull();
    }

    @Test
    void aPaymentIsNotFiledUnderAnInvestmentCategoryTurnedIntoAnExpense() {
        when(categoryService.findOrCreateDefault(MEMBER, "investissement")).thenReturn(Optional.of(
            Category.builder().id(5L).slug("investissement").kind(CategoryKind.EXPENSE).build()));

        writer.reconcile(MEMBER, account, List.of(op("1", "scheduledPayment", "2026-10-05", "50", null)),
            holdings());

        assertThat(saved().getFirst().getCategoryRef()).isNull();
    }

    // ─── keys ─────────────────────────────────────────────────────────────────

    @Test
    void twoOperationsSharingAnIdAreKeyedBySign() {
        assertThat(CorumLifeOperationWriter.externalIds(List.of(
            op("7", "arbitrage", "2026-06-15", "-100", null),
            op("7", "arbitrage", "2026-06-15", "100", null),
            op("8", "arbitrage", "2026-06-15", "100", null))))
            .containsExactly("corum:7:-", "corum:7:+", "corum:8");
    }

    @Test
    void twoOperationsStillSharingAKeyHaveNoKeys() {
        assertThat(CorumLifeOperationWriter.externalIds(List.of(
            op("7", "arbitrage", "2026-06-15", "100", null),
            op("7", "arbitrage", "2026-06-16", "100", null)))).isNull();
    }

    @Test
    void aListWithARepeatedKeyKeepsEveryStoredRow() {
        var result = writer.reconcile(MEMBER, account, List.of(
            op("7", "arbitrage", "2026-06-15", "100", null),
            op("7", "arbitrage", "2026-06-16", "100", null)), holdings());

        assertThat(result).isEqualTo(CorumLifeOperationWriter.Result.KEPT);
        verifyNoInteractions(transactionRepository, categoryService);
    }

    // ─── reconciliation ───────────────────────────────────────────────────────

    @Test
    void aResyncUpdatesTheSameRowsAndInsertsNothing() {
        when(categoryService.findOrCreateDefault(MEMBER, "investissement")).thenReturn(Optional.of(investment));
        writer.reconcile(MEMBER, account, allTypes(), holdings());
        List<Transaction> firstRun = saved();
        org.mockito.Mockito.clearInvocations(transactionRepository);
        when(transactionRepository.findByAccountIdAndAccountMemberIdAndIsManualFalse(42L, MEMBER)).thenReturn(new ArrayList<>(firstRun));

        var result = writer.reconcile(MEMBER, account, allTypes(), holdings());

        assertThat(result).isEqualTo(new CorumLifeOperationWriter.Result(0, 7, 0, 0));
        assertThat(saved()).containsExactlyInAnyOrderElementsOf(firstRun);
        verify(transactionRepository, never()).deleteAll(anyList());
    }

    @Test
    void anUpdateNeverTouchesTheCategoryTheMemberChose() {
        Category chosen = Category.builder().id(99L).slug("epargne").kind(CategoryKind.TRANSFER).build();
        Transaction stored = Transaction.builder().id(1L).account(account).externalId("corum:9")
            .date(LocalDate.of(2026, 10, 5)).description("Scheduled payment").amount(new BigDecimal("50"))
            .categoryRef(chosen).categoryManual(true).build();
        when(transactionRepository.findByAccountIdAndAccountMemberIdAndIsManualFalse(42L, MEMBER)).thenReturn(List.of(stored));

        writer.reconcile(MEMBER, account, List.of(op("9", "scheduledPayment", "2026-10-06", "55", null)),
            holdings());

        Transaction row = saved().getFirst();
        assertThat(row).isSameAs(stored);
        assertThat(row.getAmount()).isEqualByComparingTo("55");
        assertThat(row.getDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(row.getCategoryRef()).isSameAs(chosen);
        assertThat(row.isCategoryManual()).isTrue();
        verifyNoInteractions(categoryService);
    }

    @Test
    void onlyRowsNoLongerReportedBetweenTheOldestDateAndTodayAreDeleted() {
        Transaction older = stored(1L, "corum:1", LocalDate.of(2023, 12, 31));
        Transaction dropped = stored(2L, "corum:2", LocalDate.of(2025, 3, 1));
        Transaction legacyKey = stored(3L, "corum:7", LocalDate.of(2026, 6, 15));
        Transaction future = stored(4L, "corum:4", LocalDate.now().plusDays(3));
        // Synced by another importer before the account was adopted: never CORUM's to delete.
        Transaction imported = stored(5L, null, LocalDate.of(2025, 3, 1));
        Transaction otherProvider = stored(6L, "finary:9", LocalDate.of(2025, 3, 1));
        when(transactionRepository.findByAccountIdAndAccountMemberIdAndIsManualFalse(42L, MEMBER))
            .thenReturn(List.of(older, dropped, legacyKey, future, imported, otherProvider));

        // The switch now comes back as two legs under one id: the old single key is obsolete.
        var result = writer.reconcile(MEMBER, account, List.of(
            op("7", "arbitrage", "2026-06-15", "-100", null),
            op("7", "arbitrage", "2026-06-15", "100", null),
            op("5", "profitParticipation", "2024-01-15", "10", null)), holdings());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> deleted = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).deleteAll(deleted.capture());
        assertThat(deleted.getValue()).containsExactlyInAnyOrder(dropped, legacyKey);
        assertThat(result.deleted()).isEqualTo(2);
        assertThat(saved()).extracting(Transaction::getExternalId)
            .containsExactly("corum:7:-", "corum:7:+", "corum:5");
    }

    @Test
    void aLegReplacingASingleKeyRowKeepsTheCategoryTheMemberChose() {
        Category chosen = Category.builder().id(99L).slug("epargne").kind(CategoryKind.TRANSFER).build();
        Transaction single = Transaction.builder().id(1L).account(account).externalId("corum:7")
            .date(LocalDate.of(2026, 6, 15)).description("Switch between funds").amount(new BigDecimal("-100"))
            .categoryRef(chosen).categoryManual(true).build();
        when(transactionRepository.findByAccountIdAndAccountMemberIdAndIsManualFalse(42L, MEMBER))
            .thenReturn(List.of(single));

        var result = writer.reconcile(MEMBER, account, List.of(
            op("7", "arbitrage", "2026-06-15", "-100", null),
            op("7", "arbitrage", "2026-06-15", "100", null)), holdings());

        assertThat(result.deleted()).isEqualTo(1);
        Map<String, Transaction> rows = byKey(saved());
        // The leg with the stored row's sign inherits the member's choice; the other starts clean.
        assertThat(rows.get("corum:7:-").getCategoryRef()).isSameAs(chosen);
        assertThat(rows.get("corum:7:-").isCategoryManual()).isTrue();
        assertThat(rows.get("corum:7:+").getCategoryRef()).isNull();
        assertThat(rows.get("corum:7:+").isCategoryManual()).isFalse();
    }

    @Test
    void reconcileReadsOnlyTheMembersRows() {
        writer.reconcile(MEMBER, account, List.of(op("5", "profitParticipation", "2026-01-15", "10", null)),
            holdings());

        verify(transactionRepository).findByAccountIdAndAccountMemberIdAndIsManualFalse(42L, MEMBER);
        verify(transactionRepository, never()).findByAccountIdAndIsManualFalse(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aMissingOrEmptyListKeepsEveryStoredRow() {
        assertThat(writer.reconcile(MEMBER, account, null, holdings()))
            .isEqualTo(CorumLifeOperationWriter.Result.KEPT);
        assertThat(writer.reconcile(MEMBER, account, List.of(), holdings()))
            .isEqualTo(CorumLifeOperationWriter.Result.KEPT);

        verifyNoInteractions(transactionRepository, categoryService);
    }

    // ─── fixtures ─────────────────────────────────────────────────────────────

    /** One operation of each type seen live, the switch with one id per leg. */
    private static List<CorumPort.LifeOperation> allTypes() {
        return List.of(
            op("10", "couponDetachment", "2026-10-31", "3.21", List.of("CC")),
            op("9", "scheduledPayment", "2026-10-05", "50", null),
            op("8", "administrativeManagementCosts", "2026-09-30", "-1.42", null),
            op("7", "arbitrage", "2026-06-15", "-100", null),
            op("6", "arbitrage", "2026-06-15", "100", null),
            op("5", "profitParticipation", "2025-12-31", "18.40", null),
            op("1", "subscription", "2024-01-15", "1000", null));
    }

    private static List<CorumPort.LifeHolding> holdings() {
        return List.of(new CorumPort.LifeHolding("CC", "CORUM Origin", BigDecimal.ONE, BigDecimal.ONE,
            BigDecimal.ONE, null, null, false, "realEstate"));
    }

    private static CorumPort.LifeOperation op(String id, String type, String date, String amount, List<String> funds) {
        return new CorumPort.LifeOperation(id, type, type, LocalDate.parse(date), new BigDecimal(amount), funds);
    }

    private Transaction stored(Long id, String externalId, LocalDate date) {
        return Transaction.builder().id(id).account(account).externalId(externalId).date(date)
            .description("x").amount(BigDecimal.ONE).build();
    }

    @SuppressWarnings("unchecked")
    private List<Transaction> saved() {
        ArgumentCaptor<List<Transaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAllAndFlush(captor.capture());
        return new ArrayList<>(captor.getValue());
    }

    private static Map<String, Transaction> byKey(List<Transaction> rows) {
        return rows.stream().collect(Collectors.toMap(Transaction::getExternalId, Function.identity()));
    }
}
