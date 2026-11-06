package com.picsou.service;

import com.picsou.dto.PeerLoanDetailResponse;
import com.picsou.dto.PeerLoanFromTransactionRequest;
import com.picsou.dto.PeerLoanRepaymentRequest;
import com.picsou.dto.PeerLoanRepaymentResponse;
import com.picsou.dto.PeerLoanRepaymentFromTransactionRequest;
import com.picsou.dto.PeerLoanSimulationRequest;
import com.picsou.dto.PeerLoanTermsRequest;
import com.picsou.dto.PeerLoansOverviewResponse;
import com.picsou.dto.TransactionRequest;
import com.picsou.dto.TransactionResponse;
import com.picsou.dto.DashboardResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.FamilyMember;
import com.picsou.model.PeerCounterparty;
import com.picsou.model.PeerLoanRepayment;
import com.picsou.model.PeerLoanRepaymentKind;
import com.picsou.model.PeerLoanTerms;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.PeerCounterpartyRepository;
import com.picsou.repository.PeerLoanRepaymentRepository;
import com.picsou.repository.PeerLoanTermsRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PeerLoanServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long ACCOUNT_ID = 5L;
    private static final Long CASH_ID = 7L;
    private static final Long TRANSFER_CATEGORY_ID = 42L;

    @Mock AccountRepository accountRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock PeerCounterpartyRepository counterpartyRepository;
    @Mock PeerLoanTermsRepository termsRepository;
    @Mock PeerLoanRepaymentRepository repaymentRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock AccountService accountService;
    @Mock ManualTransactionService manualTransactionService;
    @Mock CategorizationService categorizationService;
    @Mock HistoryService historyService;

    @InjectMocks PeerLoanService service;

    private static FamilyMember member() {
        return FamilyMember.builder().id(MEMBER_ID).build();
    }

    private static Account loanAccount(String name) {
        return Account.builder()
            .id(ACCOUNT_ID)
            .name(name)
            .type(AccountType.PEER_LOAN)
            .member(member())
            .currency("EUR")
            .isManual(true)
            .currentBalance(BigDecimal.ZERO)
            .build();
    }

    private static Account cashAccount(boolean manual) {
        return Account.builder()
            .id(CASH_ID)
            .name("Compte courant")
            .type(AccountType.CHECKING)
            .member(member())
            .currency("EUR")
            .isManual(manual)
            .currentBalance(BigDecimal.ZERO)
            .build();
    }

    private static PeerCounterparty counterparty() {
        return PeerCounterparty.builder().id(3L).member(member()).name("Jean").build();
    }

    private static PeerLoanTerms terms(Account account, BigDecimal principal, LocalDate closedAt) {
        return PeerLoanTerms.builder()
            .id(10L)
            .account(account)
            .counterparty(counterparty())
            .principal(principal)
            .lentAt(LocalDate.parse("2026-01-01"))
            .annualRate(BigDecimal.ZERO)
            .closedAt(closedAt)
            .build();
    }

    private static Category transferCategory() {
        return Category.builder()
            .id(TRANSFER_CATEGORY_ID)
            .slug(PeerLoanService.TRANSFER_SLUG)
            .name("Virement interne")
            .kind(CategoryKind.TRANSFER)
            .build();
    }

    private static PeerLoanTermsRequest termsRequest(BigDecimal principal, Long linkedAccountId) {
        return new PeerLoanTermsRequest(
            principal, LocalDate.parse("2026-01-01"), null, BigDecimal.ZERO, null, null, null,
            linkedAccountId, null);
    }

    @Test
    void getWithoutTermsReturnsTheAccountAsUnconfigured() {
        Account account = loanAccount("Jean");
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        PeerLoanDetailResponse response = service.get(ACCOUNT_ID, MEMBER_ID);

        assertThat(response.configured()).isFalse();
        assertThat(response.accountName()).isEqualTo("Jean");
        assertThat(response.repayments()).isEmpty();
    }

    @Test
    void getRejectsAnAccountThatIsNotAPeerLoan() {
        Account account = loanAccount("Jean");
        account.setType(AccountType.CHECKING);
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.get(ACCOUNT_ID, MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not a peer loan");
    }

    @Test
    void getRejectsAForeignAccount() {
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(ACCOUNT_ID, MEMBER_ID))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void saveTermsUsesTheAccountNameAsTheBorrowerAndWritesNoLegOnASyncedAccount() {
        Account account = loanAccount("Jean");
        Account linked = cashAccount(false);
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(accountRepository.findByIdAndMemberId(CASH_ID, MEMBER_ID)).thenReturn(Optional.of(linked));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(familyMemberRepository.getReferenceById(MEMBER_ID)).thenReturn(member());
        when(counterpartyRepository.findByMemberIdAndNameIgnoreCase(MEMBER_ID, "Jean"))
            .thenReturn(Optional.empty());
        when(counterpartyRepository.save(any(PeerCounterparty.class))).thenAnswer(invocation -> {
            PeerCounterparty saved = invocation.getArgument(0);
            saved.setId(3L);
            return saved;
        });
        when(termsRepository.save(any(PeerLoanTerms.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(ACCOUNT_ID)).thenReturn(List.of());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PeerLoanDetailResponse response = service.saveTerms(ACCOUNT_ID, MEMBER_ID, termsRequest(new BigDecimal("500"), CASH_ID));

        assertThat(response.configured()).isTrue();
        assertThat(response.accountName()).isEqualTo("Jean");
        assertThat(response.principalRemaining()).isEqualByComparingTo("500.00");
        verify(manualTransactionService, never()).addTransaction(anyLong(), anyLong(), any());
        verify(accountService).upsertSnapshot(eq(account), eq(new BigDecimal("500.00")), any(LocalDate.class));
    }

    @Test
    void saveTermsWritesTheOutflowWhenTheLinkedAccountIsManual() {
        Account account = loanAccount("Jean");
        Account linked = cashAccount(true);
        TransactionResponse leg = mock(TransactionResponse.class);
        when(leg.id()).thenReturn(99L);
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(accountRepository.findByIdAndMemberId(CASH_ID, MEMBER_ID)).thenReturn(Optional.of(linked));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(familyMemberRepository.getReferenceById(MEMBER_ID)).thenReturn(member());
        when(counterpartyRepository.findByMemberIdAndNameIgnoreCase(MEMBER_ID, "Jean"))
            .thenReturn(Optional.empty());
        when(counterpartyRepository.save(any(PeerCounterparty.class))).thenAnswer(invocation -> {
            PeerCounterparty saved = invocation.getArgument(0);
            saved.setId(3L);
            return saved;
        });
        when(termsRepository.save(any(PeerLoanTerms.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(ACCOUNT_ID)).thenReturn(List.of());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(categorizationService.categoriesBySlug(MEMBER_ID))
            .thenReturn(Map.of(PeerLoanService.TRANSFER_SLUG, transferCategory()));
        when(manualTransactionService.addTransaction(eq(CASH_ID), eq(MEMBER_ID), any(TransactionRequest.class)))
            .thenReturn(leg);

        service.saveTerms(ACCOUNT_ID, MEMBER_ID, termsRequest(new BigDecimal("500"), CASH_ID));

        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(manualTransactionService).addTransaction(eq(CASH_ID), eq(MEMBER_ID), captor.capture());
        assertThat(captor.getValue().amount()).isEqualByComparingTo("-500");
        assertThat(captor.getValue().txType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(captor.getValue().categoryId()).isEqualTo(TRANSFER_CATEGORY_ID);
    }

    @Test
    void saveTermsWithoutTheLendingCashLegWritesNoTransaction() {
        Account account = loanAccount("Jean");
        Account linked = cashAccount(true);
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(accountRepository.findByIdAndMemberId(CASH_ID, MEMBER_ID)).thenReturn(Optional.of(linked));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(familyMemberRepository.getReferenceById(MEMBER_ID)).thenReturn(member());
        when(counterpartyRepository.findByMemberIdAndNameIgnoreCase(MEMBER_ID, "Jean"))
            .thenReturn(Optional.empty());
        when(counterpartyRepository.save(any(PeerCounterparty.class))).thenAnswer(invocation -> {
            PeerCounterparty saved = invocation.getArgument(0);
            saved.setId(3L);
            return saved;
        });
        when(termsRepository.save(any(PeerLoanTerms.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(ACCOUNT_ID)).thenReturn(List.of());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PeerLoanDetailResponse response = service.saveTerms(ACCOUNT_ID, MEMBER_ID,
            new PeerLoanTermsRequest(new BigDecimal("500"), LocalDate.parse("2026-01-01"), null,
                BigDecimal.ZERO, null, null, null, CASH_ID, null, false));

        // A loan recorded after the fact already shows in the typed balance: no second outflow.
        verify(manualTransactionService, never()).addTransaction(anyLong(), anyLong(), any());
        assertThat(response.principalRemaining()).isEqualByComparingTo("500.00");
    }

    @Test
    void saveTermsRoundsTheRateToTheStoredPrecision() {
        Account account = loanAccount("Jean");
        Account linked = cashAccount(true);
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(accountRepository.findByIdAndMemberId(CASH_ID, MEMBER_ID)).thenReturn(Optional.of(linked));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(familyMemberRepository.getReferenceById(MEMBER_ID)).thenReturn(member());
        when(counterpartyRepository.findByMemberIdAndNameIgnoreCase(MEMBER_ID, "Jean"))
            .thenReturn(Optional.empty());
        when(counterpartyRepository.save(any(PeerCounterparty.class))).thenAnswer(invocation -> {
            PeerCounterparty saved = invocation.getArgument(0);
            saved.setId(3L);
            return saved;
        });
        when(termsRepository.save(any(PeerLoanTerms.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(ACCOUNT_ID)).thenReturn(List.of());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PeerLoanDetailResponse response = service.saveTerms(ACCOUNT_ID, MEMBER_ID,
            new PeerLoanTermsRequest(new BigDecimal("500"), LocalDate.parse("2026-01-01"), null,
                new BigDecimal("0.029000000000000005"), null, null, null, CASH_ID, null, false));

        // A 2.9 % rate typed in the dialog arrives with float noise; it is stored as 0.029000.
        assertThat(response.annualRate()).isEqualByComparingTo("0.029000");
    }

    @Test
    void saveTermsRejectsAHalfConfiguredPlan() {
        Account account = loanAccount("Jean");
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.saveTerms(ACCOUNT_ID, MEMBER_ID, new PeerLoanTermsRequest(
            new BigDecimal("500"), LocalDate.parse("2026-01-01"), null, BigDecimal.ZERO, null,
            new BigDecimal("100"), null, CASH_ID, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("both");
    }

    @Test
    void saveTermsRejectsAnAbsurdLendingDate() {
        Account account = loanAccount("Jean");
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.saveTerms(ACCOUNT_ID, MEMBER_ID, new PeerLoanTermsRequest(
            new BigDecimal("500"), LocalDate.parse("1800-01-01"), null, BigDecimal.ZERO, null,
            null, null, CASH_ID, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("past");
    }

    @Test
    void saveTermsRejectsANonEurLinkedAccount() {
        Account account = loanAccount("Jean");
        Account linked = Account.builder()
            .id(CASH_ID).name("USD checking").type(AccountType.CHECKING)
            .member(member()).currency("USD").isManual(true)
            .currentBalance(BigDecimal.ZERO).build();
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(accountRepository.findByIdAndMemberId(CASH_ID, MEMBER_ID)).thenReturn(Optional.of(linked));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.saveTerms(ACCOUNT_ID, MEMBER_ID, new PeerLoanTermsRequest(
            new BigDecimal("500"), LocalDate.now().minusDays(10), null, BigDecimal.ZERO, null,
            null, null, CASH_ID, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("EUR");
    }

    @Test
    void addRepaymentRefusesARepaymentWhenTheLinkedAccountBecameNonEur() {
        Account account = loanAccount("Jean");
        Account usd = Account.builder()
            .id(CASH_ID).name("USD checking").type(AccountType.CHECKING)
            .member(member()).currency("USD").isManual(true)
            .currentBalance(BigDecimal.ZERO).build();
        PeerLoanTerms terms = PeerLoanTerms.builder()
            .id(10L).account(account).counterparty(counterparty())
            .principal(new BigDecimal("500")).lentAt(LocalDate.parse("2026-01-01"))
            .annualRate(BigDecimal.ZERO).linkedAccount(usd)
            .build();
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(terms));
        when(repaymentRepository.save(any(PeerLoanRepayment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.addRepayment(ACCOUNT_ID, MEMBER_ID, new PeerLoanRepaymentRequest(
            LocalDate.parse("2026-02-01"), new BigDecimal("100"), PeerLoanRepaymentKind.PRINCIPAL, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("EUR");
    }

    @Test
    void createFromTransactionRefusesAnotherMembersTransaction() {
        FamilyMember other = FamilyMember.builder().id(99L).build();
        Account foreignCash = Account.builder()
            .id(77L).name("Other").type(AccountType.CHECKING)
            .member(other).currency("EUR").currentBalance(BigDecimal.ZERO).build();
        Transaction transaction = Transaction.builder()
            .id(60L).account(foreignCash).date(LocalDate.now().minusDays(1))
            .amount(new BigDecimal("-100")).build();
        when(transactionRepository.findById(60L)).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> service.createFromTransaction(
            MEMBER_ID, new PeerLoanFromTransactionRequest(60L, null)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void addRepaymentFromTransactionRefusesAnotherMembersTransaction() {
        Account account = loanAccount("Jean");
        Account foreignCash = Account.builder()
            .id(77L).name("Other").type(AccountType.CHECKING)
            .member(FamilyMember.builder().id(99L).build()).currency("EUR")
            .currentBalance(BigDecimal.ZERO).build();
        Transaction transaction = Transaction.builder()
            .id(60L).account(foreignCash).date(LocalDate.now().minusDays(1))
            .amount(new BigDecimal("100")).build();
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(termsRepository.findByAccountId(ACCOUNT_ID))
            .thenReturn(Optional.of(terms(account, new BigDecimal("500"), null)));
        when(transactionRepository.findById(60L)).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> service.addRepaymentFromTransaction(
            ACCOUNT_ID, MEMBER_ID, new PeerLoanRepaymentFromTransactionRequest(60L, null)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void addRepaymentIsRefusedOnAClosedLoan() {
        Account account = loanAccount("Jean");
        PeerLoanTerms terms = terms(account, new BigDecimal("500"), LocalDate.parse("2026-06-01"));
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(terms));

        assertThatThrownBy(() -> service.addRepayment(ACCOUNT_ID, MEMBER_ID, new PeerLoanRepaymentRequest(
            LocalDate.parse("2026-07-01"), new BigDecimal("50"), PeerLoanRepaymentKind.PRINCIPAL, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("closed");
    }

    @Test
    void addRepaymentOnASyncedAccountWritesNoCashLeg() {
        Account account = loanAccount("Jean");
        PeerLoanTerms terms = PeerLoanTerms.builder()
            .id(10L).account(account).counterparty(counterparty())
            .principal(new BigDecimal("500")).lentAt(LocalDate.parse("2026-01-01"))
            .annualRate(BigDecimal.ZERO).linkedAccount(cashAccount(false))
            .build();
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(terms));
        when(repaymentRepository.save(any(PeerLoanRepayment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(ACCOUNT_ID)).thenReturn(List.of());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addRepayment(ACCOUNT_ID, MEMBER_ID, new PeerLoanRepaymentRequest(
            LocalDate.parse("2026-07-01"), new BigDecimal("50"), PeerLoanRepaymentKind.PRINCIPAL, null));

        // The provider owns the row; writing one would double it in the cashflow.
        verify(manualTransactionService, never()).addTransaction(anyLong(), anyLong(), any());
    }

    @Test
    void addRepaymentWithoutCashLegWritesNoTransaction() {
        Account account = loanAccount("Jean");
        PeerLoanTerms terms = PeerLoanTerms.builder()
            .id(10L).account(account).counterparty(counterparty())
            .principal(new BigDecimal("500")).lentAt(LocalDate.now().minusDays(10))
            .annualRate(BigDecimal.ZERO).linkedAccount(cashAccount(true))
            .build();
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(terms));
        List<PeerLoanRepayment> ledger = new ArrayList<>();
        when(repaymentRepository.save(any(PeerLoanRepayment.class))).thenAnswer(invocation -> {
            PeerLoanRepayment saved = invocation.getArgument(0);
            saved.setId(11L);
            ledger.add(saved);
            return saved;
        });
        when(repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(ACCOUNT_ID))
            .thenAnswer(invocation -> ledger);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PeerLoanRepaymentResponse response = service.addRepayment(ACCOUNT_ID, MEMBER_ID,
            new PeerLoanRepaymentRequest(LocalDate.now(), new BigDecimal("50"),
                PeerLoanRepaymentKind.PRINCIPAL, "Compensation", false));

        // A compensation reduces the receivable without inventing a bank deposit.
        verify(manualTransactionService, never()).addTransaction(anyLong(), anyLong(), any());
        assertThat(response.hasCashLeg()).isFalse();
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("450");
    }

    @Test
    void addRepaymentRejectsAFutureDate() {
        Account account = loanAccount("Jean");
        PeerLoanTerms terms = terms(account, new BigDecimal("500"), null);
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(terms));

        assertThatThrownBy(() -> service.addRepayment(ACCOUNT_ID, MEMBER_ID,
            new PeerLoanRepaymentRequest(LocalDate.now().plusDays(1), new BigDecimal("50"),
                PeerLoanRepaymentKind.PRINCIPAL, null, false)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("future");

        verify(repaymentRepository, never()).save(any(PeerLoanRepayment.class));
    }

    @Test
    void aBackdatedRepaymentRecomputesEveryLaterSnapshot() {
        Account account = loanAccount("Jean");
        PeerLoanTerms terms = PeerLoanTerms.builder()
            .id(10L).account(account).counterparty(counterparty())
            .principal(new BigDecimal("1000")).lentAt(LocalDate.parse("2026-01-01"))
            .annualRate(BigDecimal.ZERO).linkedAccount(cashAccount(true))
            .build();
        PeerLoanRepayment february = PeerLoanRepayment.builder()
            .id(21L).account(account).paidOn(LocalDate.parse("2026-02-01"))
            .amount(new BigDecimal("300")).kind(PeerLoanRepaymentKind.PRINCIPAL).build();
        PeerLoanRepayment march = PeerLoanRepayment.builder()
            .id(22L).account(account).paidOn(LocalDate.parse("2026-03-01"))
            .amount(new BigDecimal("200")).kind(PeerLoanRepaymentKind.PRINCIPAL).build();
        List<PeerLoanRepayment> ledger = new ArrayList<>(List.of(february, march));

        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(terms));
        when(repaymentRepository.save(any(PeerLoanRepayment.class))).thenAnswer(invocation -> {
            PeerLoanRepayment saved = invocation.getArgument(0);
            saved.setId(23L);
            ledger.add(saved);
            return saved;
        });
        when(repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(ACCOUNT_ID))
            .thenAnswer(invocation -> ledger);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotRepository.findByAccountIdOrderByDateAsc(ACCOUNT_ID)).thenReturn(List.of(
            snapshot(account, "2026-02-01"),
            snapshot(account, "2026-03-01"),
            snapshot(account, "2026-04-01")));

        service.addRepayment(ACCOUNT_ID, MEMBER_ID, new PeerLoanRepaymentRequest(
            LocalDate.parse("2026-01-15"), new BigDecimal("100"),
            PeerLoanRepaymentKind.PRINCIPAL, null, false));

        // The January event is the new snapshot; February and March still include the
        // repayments dated after them only when that date has passed, and April inherits them.
        verify(accountService).upsertSnapshot(eq(account), eq(new BigDecimal("900.00")),
            eq(LocalDate.parse("2026-01-15")));
        verify(accountService).upsertSnapshot(eq(account), eq(new BigDecimal("600.00")),
            eq(LocalDate.parse("2026-02-01")));
        verify(accountService).upsertSnapshot(eq(account), eq(new BigDecimal("400.00")),
            eq(LocalDate.parse("2026-03-01")));
        verify(accountService).upsertSnapshot(eq(account), eq(new BigDecimal("400.00")),
            eq(LocalDate.parse("2026-04-01")));
    }

    private static com.picsou.model.BalanceSnapshot snapshot(Account account, String date) {
        return com.picsou.model.BalanceSnapshot.builder()
            .account(account)
            .date(LocalDate.parse(date))
            .balance(BigDecimal.ZERO)
            .investedAmount(BigDecimal.ZERO)
            .build();
    }

    @Test
    void updatingARepaymentCanRemoveItsCashLeg() {
        Account account = loanAccount("Jean");
        Account linked = cashAccount(true);
        PeerLoanTerms terms = PeerLoanTerms.builder()
            .id(10L).account(account).counterparty(counterparty())
            .principal(new BigDecimal("500")).lentAt(LocalDate.now().minusDays(10))
            .annualRate(BigDecimal.ZERO).linkedAccount(linked)
            .build();
        PeerLoanRepayment repayment = PeerLoanRepayment.builder()
            .id(11L).account(account).paidOn(LocalDate.now().minusDays(2))
            .amount(new BigDecimal("100")).kind(PeerLoanRepaymentKind.PRINCIPAL)
            .cashTransactionId(60L).cashLegOwned(true).build();
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(terms));
        when(repaymentRepository.findByIdAndAccountId(11L, ACCOUNT_ID)).thenReturn(Optional.of(repayment));
        when(repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(ACCOUNT_ID))
            .thenReturn(List.of(repayment));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PeerLoanRepaymentResponse response = service.updateRepayment(ACCOUNT_ID, 11L, MEMBER_ID,
            new PeerLoanRepaymentRequest(LocalDate.now(), new BigDecimal("100"),
                PeerLoanRepaymentKind.PRINCIPAL, "Compensation", false));

        verify(manualTransactionService).deleteTransaction(CASH_ID, 60L, MEMBER_ID);
        verify(manualTransactionService, never()).addTransaction(anyLong(), anyLong(), any());
        assertThat(repayment.getCashTransactionId()).isNull();
        assertThat(response.hasCashLeg()).isFalse();
    }

    @Test
    void theRepaymentThatClearsTheCapitalClosesTheLoan() {
        Account account = loanAccount("Jean");
        PeerLoanTerms terms = PeerLoanTerms.builder()
            .id(10L).account(account).counterparty(counterparty())
            .principal(new BigDecimal("500")).lentAt(LocalDate.now().minusDays(10))
            .annualRate(BigDecimal.ZERO).linkedAccount(cashAccount(false))
            .build();
        List<PeerLoanRepayment> ledger = new ArrayList<>();

        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(terms));
        when(repaymentRepository.save(any(PeerLoanRepayment.class))).thenAnswer(invocation -> {
            PeerLoanRepayment saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(11L);
                ledger.add(saved);
            }
            return saved;
        });
        when(repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(ACCOUNT_ID))
            .thenAnswer(invocation -> ledger);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addRepayment(ACCOUNT_ID, MEMBER_ID, new PeerLoanRepaymentRequest(
            LocalDate.now(), new BigDecimal("500"), PeerLoanRepaymentKind.PRINCIPAL, null));

        // Nothing left to collect: the loan closes itself rather than lingering at 0 EUR.
        assertThat(terms.getClosedAt()).isEqualTo(LocalDate.now());
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("0");
    }

    @ParameterizedTest
    @EnumSource(value = PeerLoanRepaymentKind.class, names = {"PRINCIPAL", "INTEREST"})
    void addRepaymentFromTransactionLinksTheRowWithoutCopyingIt(PeerLoanRepaymentKind kind) {
        Account account = loanAccount("Jean");
        Account cash = cashAccount(false);
        PeerLoanTerms terms = PeerLoanTerms.builder()
            .id(10L).account(account).counterparty(counterparty())
            .principal(new BigDecimal("500")).lentAt(LocalDate.parse("2026-01-01"))
            .annualRate(BigDecimal.ZERO).linkedAccount(cash)
            .build();
        Transaction transaction = Transaction.builder()
            .id(60L).account(cash).date(LocalDate.parse("2026-02-01"))
            .description("Virement reçu").merchantLabel("Jean")
            .amount(new BigDecimal("100")).build();
        List<PeerLoanRepayment> ledger = new ArrayList<>();

        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(terms));
        when(transactionRepository.findById(60L)).thenReturn(Optional.of(transaction));
        when(repaymentRepository.findByCashTransactionId(60L)).thenReturn(Optional.empty());
        when(termsRepository.findByLendingTransactionId(60L)).thenReturn(Optional.empty());
        when(repaymentRepository.save(any(PeerLoanRepayment.class))).thenAnswer(invocation -> {
            PeerLoanRepayment saved = invocation.getArgument(0);
            saved.setId(11L);
            ledger.add(saved);
            return saved;
        });
        when(repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(ACCOUNT_ID))
            .thenAnswer(invocation -> List.copyOf(ledger));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(categorizationService.categoriesBySlug(MEMBER_ID))
            .thenReturn(Map.of(PeerLoanService.TRANSFER_SLUG, transferCategory()));

        PeerLoanDetailResponse response = service.addRepaymentFromTransaction(
            ACCOUNT_ID, MEMBER_ID, new PeerLoanRepaymentFromTransactionRequest(60L, kind));

        assertThat(response.principalRemaining()).isEqualByComparingTo("400.00");
        assertThat(ledger).hasSize(1);
        assertThat(ledger.getFirst().getCashTransactionId()).isEqualTo(60L);
        assertThat(ledger.getFirst().isCashLegOwned()).isFalse();
        assertThat(transaction.getCategoryRef().getId()).isEqualTo(TRANSFER_CATEGORY_ID);
        assertThat(transaction.isCategoryManual()).isTrue();
        verify(manualTransactionService, never()).addTransaction(anyLong(), anyLong(), any());
    }

    @Test
    void deletingALinkedRepaymentDoesNotDeleteItsTransaction() {
        Account account = loanAccount("Jean");
        PeerLoanTerms terms = PeerLoanTerms.builder()
            .id(10L).account(account).counterparty(counterparty())
            .principal(new BigDecimal("500")).lentAt(LocalDate.parse("2026-01-01"))
            .annualRate(BigDecimal.ZERO).linkedAccount(cashAccount(true))
            .build();
        PeerLoanRepayment repayment = PeerLoanRepayment.builder()
            .id(11L).account(account).paidOn(LocalDate.parse("2026-02-01"))
            .amount(new BigDecimal("100")).kind(PeerLoanRepaymentKind.PRINCIPAL)
            .cashTransactionId(60L).cashLegOwned(false).build();

        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(terms));
        when(repaymentRepository.findByIdAndAccountId(11L, ACCOUNT_ID)).thenReturn(Optional.of(repayment));
        when(repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(ACCOUNT_ID)).thenReturn(List.of());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.deleteRepayment(ACCOUNT_ID, 11L, MEMBER_ID);

        verify(manualTransactionService, never()).deleteTransaction(anyLong(), anyLong(), anyLong());
    }

    @Test
    void closeWritesTheResidualOffAndZeroesTheAccount() {
        Account account = loanAccount("Jean");
        PeerLoanTerms terms = terms(account, new BigDecimal("500"), null);
        List<PeerLoanRepayment> ledger = new ArrayList<>();
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(terms));
        when(repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(ACCOUNT_ID))
            .thenAnswer(invocation -> List.copyOf(ledger));
        when(repaymentRepository.save(any(PeerLoanRepayment.class))).thenAnswer(invocation -> {
            PeerLoanRepayment saved = invocation.getArgument(0);
            saved.setId(1L);
            ledger.add(saved);
            return saved;
        });
        when(termsRepository.save(any(PeerLoanTerms.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PeerLoanDetailResponse response = service.close(ACCOUNT_ID, MEMBER_ID, LocalDate.parse("2026-06-01"));

        ArgumentCaptor<PeerLoanRepayment> writeOff = ArgumentCaptor.forClass(PeerLoanRepayment.class);
        verify(repaymentRepository).save(writeOff.capture());
        assertThat(writeOff.getValue().getKind()).isEqualTo(PeerLoanRepaymentKind.WRITE_OFF);
        assertThat(writeOff.getValue().getAmount()).isEqualByComparingTo("500");
        assertThat(terms.getClosedAt()).isEqualTo(LocalDate.parse("2026-06-01"));
        assertThat(response.closedAt()).isEqualTo(LocalDate.parse("2026-06-01"));
        assertThat(response.principalRemaining()).isEqualByComparingTo("0.00");
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("0.00");
        verify(accountService).upsertSnapshot(eq(account), eq(new BigDecimal("0.00")), any(LocalDate.class));
    }

    @Test
    void deletingTheClosingWriteOffReopensTheLoan() {
        Account account = loanAccount("Jean");
        PeerLoanTerms terms = terms(account, new BigDecimal("500"), LocalDate.parse("2026-06-01"));
        PeerLoanRepayment writeOff = PeerLoanRepayment.builder()
            .id(9L).account(account).paidOn(LocalDate.parse("2026-06-01"))
            .amount(new BigDecimal("500")).kind(PeerLoanRepaymentKind.WRITE_OFF).build();
        List<PeerLoanRepayment> ledger = new ArrayList<>(List.of(writeOff));

        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(terms));
        when(repaymentRepository.findByIdAndAccountId(9L, ACCOUNT_ID)).thenReturn(Optional.of(writeOff));
        when(repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(ACCOUNT_ID))
            .thenAnswer(invocation -> List.copyOf(ledger));
        org.mockito.Mockito.doAnswer(invocation -> {
            ledger.remove(writeOff);
            return null;
        }).when(repaymentRepository).delete(writeOff);
        when(termsRepository.save(any(PeerLoanTerms.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.deleteRepayment(ACCOUNT_ID, 9L, MEMBER_ID);

        assertThat(terms.getClosedAt()).isNull();
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    void createFromTransactionLinksTheExistingRowAndCategorisesIt() {
        Account cash = cashAccount(false);
        Transaction transaction = Transaction.builder()
            .id(50L)
            .account(cash)
            .date(LocalDate.parse("2026-02-01"))
            .description("Virement")
            .merchantLabel("Jean")
            .amount(new BigDecimal("-350"))
            .build();

        when(transactionRepository.findById(50L)).thenReturn(Optional.of(transaction));
        when(termsRepository.findByLendingTransactionId(50L)).thenReturn(Optional.empty());
        when(familyMemberRepository.getReferenceById(MEMBER_ID)).thenReturn(member());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });
        when(counterpartyRepository.findByMemberIdAndNameIgnoreCase(MEMBER_ID, "Jean"))
            .thenReturn(Optional.empty());
        when(counterpartyRepository.save(any(PeerCounterparty.class))).thenAnswer(invocation -> {
            PeerCounterparty saved = invocation.getArgument(0);
            saved.setId(3L);
            return saved;
        });
        when(termsRepository.save(any(PeerLoanTerms.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(categorizationService.categoriesBySlug(MEMBER_ID))
            .thenReturn(Map.of(PeerLoanService.TRANSFER_SLUG, transferCategory()));

        PeerLoanDetailResponse response = service.createFromTransaction(
            MEMBER_ID, new PeerLoanFromTransactionRequest(50L, null));

        assertThat(response.accountName()).isEqualTo("Jean");
        assertThat(response.principal()).isEqualByComparingTo("350");
        assertThat(response.linkedAccountId()).isEqualTo(CASH_ID);
        assertThat(transaction.getCategoryRef()).isNotNull();
        assertThat(transaction.getCategoryRef().getId()).isEqualTo(TRANSFER_CATEGORY_ID);
        assertThat(transaction.isCategoryManual()).isTrue();
        verify(transactionRepository).save(transaction);
        verify(manualTransactionService, never()).addTransaction(anyLong(), anyLong(), any());
        // The lending event is the transaction's own date, even when it is backdated.
        verify(accountService).upsertSnapshot(any(Account.class), eq(new BigDecimal("350.00")),
            eq(LocalDate.parse("2026-02-01")));
    }

    @Test
    void simulateRequiresExactlyOneDirection() {
        assertThatThrownBy(() -> service.simulate(new PeerLoanSimulationRequest(
            new BigDecimal("1000"), BigDecimal.ZERO, 12, new BigDecimal("100"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one");

        assertThatThrownBy(() -> service.simulate(new PeerLoanSimulationRequest(
            new BigDecimal("1000"), BigDecimal.ZERO, null, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one");
    }

    @Test
    void overviewSummarisesTheLoansAndBuildsTheOutstandingCurve() {
        LocalDate today = LocalDate.now();
        Account open = Account.builder().id(5L).name("Marie").type(AccountType.PEER_LOAN)
            .member(member()).currency("EUR").isManual(true).currentBalance(BigDecimal.ZERO).build();
        Account closed = Account.builder().id(6L).name("Jean").type(AccountType.PEER_LOAN)
            .member(member()).currency("EUR").isManual(true).currentBalance(BigDecimal.ZERO).build();
        Account unconfigured = Account.builder().id(7L).name("Sans conditions").type(AccountType.PEER_LOAN)
            .member(member()).currency("EUR").isManual(true).currentBalance(BigDecimal.ZERO).build();
        Account checking = cashAccount(true);

        PeerLoanTerms openTerms = PeerLoanTerms.builder()
            .id(10L).account(open).counterparty(counterparty())
            .principal(new BigDecimal("1000")).lentAt(today.minusMonths(3))
            .annualRate(BigDecimal.ZERO)
            .plannedMonthlyAmount(new BigDecimal("300")).plannedMonths(4)
            .build();
        PeerLoanTerms closedTerms = PeerLoanTerms.builder()
            .id(11L).account(closed).counterparty(counterparty())
            .principal(new BigDecimal("500")).lentAt(today.minusMonths(6))
            .annualRate(BigDecimal.ZERO).closedAt(today.minusMonths(1))
            .build();
        PeerLoanRepayment openPayment = PeerLoanRepayment.builder()
            .id(20L).account(open).paidOn(today.minusMonths(2))
            .amount(new BigDecimal("300")).kind(PeerLoanRepaymentKind.PRINCIPAL).build();
        PeerLoanRepayment closedPayment = PeerLoanRepayment.builder()
            .id(21L).account(closed).paidOn(today.minusMonths(3))
            .amount(new BigDecimal("250")).kind(PeerLoanRepaymentKind.PRINCIPAL).build();
        PeerLoanRepayment closedPayment2 = PeerLoanRepayment.builder()
            .id(22L).account(closed).paidOn(today.minusMonths(2))
            .amount(new BigDecimal("250")).kind(PeerLoanRepaymentKind.PRINCIPAL).build();

        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID))
            .thenReturn(List.of(open, closed, unconfigured, checking));
        when(termsRepository.findByAccountId(5L)).thenReturn(Optional.of(openTerms));
        when(termsRepository.findByAccountId(6L)).thenReturn(Optional.of(closedTerms));
        when(termsRepository.findByAccountId(7L)).thenReturn(Optional.empty());
        when(repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(5L)).thenReturn(List.of(openPayment));
        when(repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(6L))
            .thenReturn(List.of(closedPayment, closedPayment2));

        Map<Long, DashboardResponse.AccountPoint> first = Map.of(
            5L, new DashboardResponse.AccountPoint(new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO),
            6L, new DashboardResponse.AccountPoint(new BigDecimal("500"), BigDecimal.ZERO, BigDecimal.ZERO));
        Map<Long, DashboardResponse.AccountPoint> last = Map.of(
            5L, new DashboardResponse.AccountPoint(new BigDecimal("700"), BigDecimal.ZERO, BigDecimal.ZERO),
            6L, new DashboardResponse.AccountPoint(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(historyService.buildHistory(List.of(5L, 6L), 12, true, MEMBER_ID)).thenReturn(List.of(
            new DashboardResponse.NetWorthPoint(today.minusMonths(1), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, first),
            new DashboardResponse.NetWorthPoint(today, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, last)));

        PeerLoansOverviewResponse overview = service.overview(MEMBER_ID, 12);

        assertThat(overview.openCount()).isEqualTo(1);
        assertThat(overview.closedCount()).isEqualTo(1);
        assertThat(overview.totalOutstanding()).isEqualByComparingTo("700");
        assertThat(overview.interestAccrued()).isEqualByComparingTo("0");
        assertThat(overview.loans()).extracting(PeerLoansOverviewResponse.LoanEntry::name)
            .containsExactly("Marie", "Jean");
        PeerLoansOverviewResponse.LoanEntry marie = overview.loans().getFirst();
        assertThat(marie.principalRemaining()).isEqualByComparingTo("700");
        assertThat(marie.repaidPct()).isEqualByComparingTo("30");
        assertThat(marie.installmentsBehind()).isEqualTo(2);
        assertThat(marie.lastPaymentDate()).isEqualTo(today.minusMonths(2));
        assertThat(marie.lastPaymentAmount()).isEqualByComparingTo("300");
        assertThat(overview.history()).extracting(PeerLoansOverviewResponse.Point::outstanding)
            .containsExactly(new BigDecimal("1500"), new BigDecimal("700"));
    }

    @Test
    void overviewHasNoHistoryWhenNoLoanIsConfigured() {
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID))
            .thenReturn(List.of(loanAccount("Sans conditions")));

        PeerLoansOverviewResponse overview = service.overview(MEMBER_ID, 12);

        assertThat(overview.loans()).isEmpty();
        assertThat(overview.totalOutstanding()).isEqualByComparingTo("0");
        assertThat(overview.history()).isEmpty();
        verify(historyService, never()).buildHistory(anyList(), anyInt(), anyBoolean(), anyLong());
    }
}
