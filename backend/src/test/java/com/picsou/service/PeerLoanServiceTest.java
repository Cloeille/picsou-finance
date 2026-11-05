package com.picsou.service;

import com.picsou.dto.PeerLoanDetailResponse;
import com.picsou.dto.PeerLoanFromTransactionRequest;
import com.picsou.dto.PeerLoanRepaymentRequest;
import com.picsou.dto.PeerLoanSimulationRequest;
import com.picsou.dto.PeerLoanTermsRequest;
import com.picsou.dto.TransactionRequest;
import com.picsou.dto.TransactionResponse;
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
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.PeerCounterpartyRepository;
import com.picsou.repository.PeerLoanRepaymentRepository;
import com.picsou.repository.PeerLoanTermsRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock PeerCounterpartyRepository counterpartyRepository;
    @Mock PeerLoanTermsRepository termsRepository;
    @Mock PeerLoanRepaymentRepository repaymentRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock AccountService accountService;
    @Mock ManualTransactionService manualTransactionService;
    @Mock CategorizationService categorizationService;

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
            principal, LocalDate.parse("2026-01-01"), null, BigDecimal.ZERO, null, null,
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
    void saveTermsRejectsAHalfConfiguredPlan() {
        Account account = loanAccount("Jean");
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.saveTerms(ACCOUNT_ID, MEMBER_ID, new PeerLoanTermsRequest(
            new BigDecimal("500"), LocalDate.parse("2026-01-01"), null, BigDecimal.ZERO,
            new BigDecimal("100"), null, CASH_ID, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("both");
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
}
