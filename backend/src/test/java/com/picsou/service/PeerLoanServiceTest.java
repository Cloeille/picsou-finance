package com.picsou.service;

import com.picsou.dto.PeerLoanDetailResponse;
import com.picsou.dto.PeerLoanRepaymentRequest;
import com.picsou.dto.PeerLoanSimulationRequest;
import com.picsou.dto.PeerLoanTermsRequest;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.PeerCounterparty;
import com.picsou.model.PeerLoanRepayment;
import com.picsou.model.PeerLoanRepaymentKind;
import com.picsou.model.PeerLoanTerms;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.PeerCounterpartyRepository;
import com.picsou.repository.PeerLoanRepaymentRepository;
import com.picsou.repository.PeerLoanTermsRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PeerLoanServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long ACCOUNT_ID = 5L;

    @Mock AccountRepository accountRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock PeerCounterpartyRepository counterpartyRepository;
    @Mock PeerLoanTermsRepository termsRepository;
    @Mock PeerLoanRepaymentRepository repaymentRepository;
    @Mock AccountService accountService;

    @InjectMocks PeerLoanService service;

    private static FamilyMember member() {
        return FamilyMember.builder().id(MEMBER_ID).build();
    }

    private static Account loanAccount(BigDecimal balance) {
        return Account.builder()
            .id(ACCOUNT_ID)
            .name("Prêt à Marie")
            .type(AccountType.PEER_LOAN)
            .member(member())
            .currency("EUR")
            .isManual(true)
            .currentBalance(balance)
            .build();
    }

    private static PeerCounterparty counterparty() {
        return PeerCounterparty.builder().id(3L).member(member()).name("Marie").build();
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

    @Test
    void getWithoutTermsReturnsTheAccountAsUnconfigured() {
        Account account = loanAccount(new BigDecimal("500"));
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        PeerLoanDetailResponse response = service.get(ACCOUNT_ID, MEMBER_ID);

        assertThat(response.configured()).isFalse();
        assertThat(response.accountName()).isEqualTo("Prêt à Marie");
        assertThat(response.principal()).isEqualByComparingTo("500");
        assertThat(response.repayments()).isEmpty();
    }

    @Test
    void getRejectsAnAccountThatIsNotAPeerLoan() {
        Account account = loanAccount(BigDecimal.ZERO);
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
    void saveTermsCreatesTheCounterpartyAndRefreshesTheBalance() {
        Account account = loanAccount(new BigDecimal("500"));
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(termsRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(familyMemberRepository.getReferenceById(MEMBER_ID)).thenReturn(member());
        when(counterpartyRepository.findByMemberIdAndNameIgnoreCase(MEMBER_ID, "marie"))
            .thenReturn(Optional.empty());
        when(counterpartyRepository.save(any(PeerCounterparty.class))).thenAnswer(invocation -> {
            PeerCounterparty saved = invocation.getArgument(0);
            saved.setId(3L);
            return saved;
        });
        when(termsRepository.save(any(PeerLoanTerms.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repaymentRepository.findByAccountIdOrderByPaidOnAscIdAsc(ACCOUNT_ID)).thenReturn(List.of());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PeerLoanDetailResponse response = service.saveTerms(ACCOUNT_ID, MEMBER_ID, new PeerLoanTermsRequest(
            new BigDecimal("500"), "marie", LocalDate.parse("2026-01-01"), null,
            BigDecimal.ZERO, null, null, null, null));

        assertThat(response.configured()).isTrue();
        assertThat(response.counterpartyName()).isEqualTo("marie");
        assertThat(response.principalRemaining()).isEqualByComparingTo("500.00");
        verify(counterpartyRepository).save(any(PeerCounterparty.class));
        verify(accountService).upsertSnapshot(eq(account), eq(new BigDecimal("500.00")), any(LocalDate.class));
    }

    @Test
    void saveTermsRejectsAHalfConfiguredPlan() {
        Account account = loanAccount(BigDecimal.ZERO);
        when(accountRepository.findByIdAndMemberId(ACCOUNT_ID, MEMBER_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.saveTerms(ACCOUNT_ID, MEMBER_ID, new PeerLoanTermsRequest(
            new BigDecimal("500"), "Marie", LocalDate.parse("2026-01-01"), null,
            BigDecimal.ZERO, new BigDecimal("100"), null, null, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("both");
    }

    @Test
    void addRepaymentIsRefusedOnAClosedLoan() {
        Account account = loanAccount(new BigDecimal("500"));
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
        Account account = loanAccount(new BigDecimal("500"));
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
        Account account = loanAccount(BigDecimal.ZERO);
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
