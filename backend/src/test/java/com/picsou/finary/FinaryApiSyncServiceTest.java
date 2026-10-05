package com.picsou.finary;

import com.picsou.config.CryptoEncryption;
import com.picsou.dto.FinaryAccountMapping;
import com.picsou.dto.FinaryAccountPreview;
import com.picsou.dto.FinaryCheckTotpResponse;
import com.picsou.dto.FinaryImportResultResponse;
import com.picsou.dto.FinaryMappingAction;
import com.picsou.dto.FinaryPreviewResponse;
import com.picsou.dto.NewAccountDetails;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.finary.client.FinaryApiClient;
import com.picsou.finary.dto.FinaryAccountCurrency;
import com.picsou.finary.dto.FinaryAccountDto;
import com.picsou.finary.dto.FinaryLoanDto;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.FinarySession;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.FinarySessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinaryApiSyncServiceTest {

    @Mock FinaryApiClient finaryApiClient;
    @Mock CryptoEncryption encryption;
    @Mock AccountRepository accountRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock FinarySessionRepository finarySessionRepository;
    @Mock FinaryPersistenceHelper persistenceHelper;

    @InjectMocks FinaryApiSyncService service;

    /**
     * autoSync calls execute() on `this`, past the Spring proxy, so execute's own @Transactional
     * never applies on that path. Without a transaction of its own, the scheduled and the manual
     * auto-sync ran the account deletes, snapshot rebuilds and transaction imports as separate
     * auto-committed statements: a failure halfway left a half-imported account behind.
     */
    @Test
    void autoSync_isTransactional_becauseItSelfInvokesExecute() throws NoSuchMethodException {
        var method = FinaryApiSyncService.class.getMethod("autoSync", Long.class);
        assertThat(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNotNull();
    }

    @Test
    void checkTotp_returnsTrue_whenTotpRequired() {
        FinarySession session = FinarySession.builder()
            .member(FamilyMember.builder().id(1L).build())
            .email("enc-email")
            .password("enc-pass")
            .status("CONNECTED")
            .build();
        when(finarySessionRepository.findByMemberId(1L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("enc-email")).thenReturn("user@example.com");
        when(encryption.decrypt("enc-pass")).thenReturn("secret");
        when(finaryApiClient.checkTotpRequired("user@example.com", "secret")).thenReturn("sign-in-id-123");

        FinaryCheckTotpResponse result = service.checkTotp(1L);

        assertThat(result.totpRequired()).isTrue();
    }

    @Test
    void checkTotp_returnsFalse_whenNoTotpNeeded() {
        FinarySession session = FinarySession.builder()
            .member(FamilyMember.builder().id(1L).build())
            .email("enc-email")
            .password("enc-pass")
            .status("CONNECTED")
            .build();
        when(finarySessionRepository.findByMemberId(1L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("enc-email")).thenReturn("user@example.com");
        when(encryption.decrypt("enc-pass")).thenReturn("secret");
        when(finaryApiClient.checkTotpRequired("user@example.com", "secret")).thenReturn(null);

        FinaryCheckTotpResponse result = service.checkTotp(1L);

        assertThat(result.totpRequired()).isFalse();
    }

    @Test
    void checkTotp_throwsResourceNotFoundException_whenNoSession() {
        when(finarySessionRepository.findByMemberId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkTotp(99L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------------------
    // Loans from the dedicated /loans endpoint (issue #11)
    // ---------------------------------------------------------------------------

    private FinarySession connectedSession() {
        return FinarySession.builder()
            .member(FamilyMember.builder().id(1L).build())
            .email("enc-email")
            .password("enc-pass")
            .status("CONNECTED")
            .build();
    }

    private void stubPreviewFlow(FinaryAccountDto checking, FinaryLoanDto loan) {
        when(finarySessionRepository.findByMemberId(1L)).thenReturn(Optional.of(connectedSession()));
        when(encryption.decrypt("enc-email")).thenReturn("user@example.com");
        when(encryption.decrypt("enc-pass")).thenReturn("secret");
        when(finaryApiClient.authenticate("user@example.com", "secret", null)).thenReturn("jwt");
        when(finaryApiClient.fetchOrganizationContext("jwt"))
            .thenReturn(new FinaryApiClient.OrgContext("org", "mem"));
        when(finaryApiClient.fetchCategoryAccounts(eq("jwt"), any(), anyString()))
            .thenAnswer(inv -> "checkings".equals(inv.getArgument(2)) ? List.of(checking) : List.of());
        when(finaryApiClient.fetchLoans("jwt")).thenReturn(List.of(loan));
        when(finaryApiClient.fetchCategoryTransactions(any(), any(), anyString(), anyInt(), anyInt()))
            .thenReturn(List.of());
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());
        when(accountRepository.findByExternalAccountIdAndMemberId(anyString(), eq(1L)))
            .thenReturn(Optional.empty());
    }

    @Test
    void preview_includesLoanAccountsFromLoansEndpoint() {
        FinaryAccountDto checking = new FinaryAccountDto(
            "chk-1", "Checking", null, 1000.0, 1000.0, null,
            new FinaryAccountCurrency("EUR", "€"), false);
        FinaryLoanDto loan = new FinaryLoanDto(
            "loan-1", "loan", "PRET CONSO Auto", 12345.67, 250.0,
            "2023-01-01", "2028-01-01", new FinaryAccountCurrency("EUR", "€"), null);

        stubPreviewFlow(checking, loan);

        FinaryPreviewResponse response = service.preview(null, 1L);

        // Both the non-loan account and the loan show up in the preview.
        assertThat(response.accounts()).hasSize(2);

        FinaryAccountPreview loanPreview = response.accounts().stream()
            .filter(a -> "loans".equals(a.finaryCategory()))
            .findFirst()
            .orElseThrow();
        assertThat(loanPreview.finaryId()).isEqualTo("loan-1");
        assertThat(loanPreview.finaryName()).isEqualTo("PRET CONSO Auto");
        assertThat(loanPreview.suggestedType()).isEqualTo(AccountType.LOAN);
        // Repo convention: LOAN balances are stored POSITIVE; aggregation negates.
        assertThat(loanPreview.currentBalance()).isEqualTo(12345.67);

        assertThat(response.accounts())
            .anyMatch(a -> "checkings".equals(a.finaryCategory()));
    }

    @Test
    void execute_createsLoanAccount_fromLoanMapping() {
        FinaryAccountDto checking = new FinaryAccountDto(
            "chk-1", "Checking", null, 1000.0, 1000.0, null,
            new FinaryAccountCurrency("EUR", "€"), false);
        FinaryLoanDto loan = new FinaryLoanDto(
            "loan-1", "loan", "PRET CONSO Auto", 12345.67, 250.0,
            "2023-01-01", "2028-01-01", new FinaryAccountCurrency("EUR", "€"), null);

        stubPreviewFlow(checking, loan);
        when(familyMemberRepository.findById(1L))
            .thenReturn(Optional.of(FamilyMember.builder().id(1L).build()));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        FinaryPreviewResponse preview = service.preview(null, 1L);

        FinaryAccountMapping loanMapping = new FinaryAccountMapping(
            "loan-1", "PRET CONSO Auto", "loans", FinaryMappingAction.CREATE_NEW, null,
            new NewAccountDetails("PRET CONSO Auto", AccountType.LOAN, "Finary", "EUR", "#6366f1"));

        FinaryImportResultResponse result =
            service.execute(preview.fileToken(), List.of(loanMapping), 1L);

        assertThat(result.accountsCreated()).isEqualTo(1);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        org.mockito.Mockito.verify(accountRepository).save(captor.capture());
        Account saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(AccountType.LOAN);
        assertThat(saved.getExternalAccountId()).isEqualTo("finary_loans_loan-1");
        assertThat(saved.getCurrentBalance()).isEqualByComparingTo("12345.67");
    }

    @Test
    void toLoanAccountDto_storesOutstandingAmountPositive() {
        FinaryLoanDto loan = new FinaryLoanDto(
            "loan-1", "loan", "PRET IMMO", 12000.0, 600.0,
            "2023-01-01", "2043-01-01", new FinaryAccountCurrency("EUR", "€"), null);

        FinaryAccountDto dto = service.toLoanAccountDto(loan);

        // Repo convention: LOAN balances stored positive; negated only at aggregation.
        assertThat(dto.balance()).isEqualTo(12000.0);
    }

    @Test
    void toLoanAccountDto_keepsNullBalance_whenOutstandingAmountMissing() {
        FinaryLoanDto loan = new FinaryLoanDto(
            "loan-2", "loan", "PRET SANS MONTANT", null, null,
            null, null, new FinaryAccountCurrency("EUR", "€"), null);

        FinaryAccountDto dto = service.toLoanAccountDto(loan);

        assertThat(dto.balance()).isNull();
    }
}
