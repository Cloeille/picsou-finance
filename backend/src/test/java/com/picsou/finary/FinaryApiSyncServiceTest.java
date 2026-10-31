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
import com.picsou.exception.SyncException;
import com.picsou.finary.client.FinaryApiClient;
import com.picsou.finary.dto.FinaryAccountCurrency;
import com.picsou.finary.dto.FinaryAccountDto;
import com.picsou.finary.dto.FinaryLoanDto;
import com.picsou.finary.dto.FinaryTransactionAccount;
import com.picsou.finary.dto.FinaryTransactionCurrency;
import com.picsou.finary.dto.FinaryTransactionDto;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinaryApiSyncServiceTest {

    @Mock FinaryApiClient finaryApiClient;
    @Mock CryptoEncryption encryption;
    @Mock AccountRepository accountRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock FinarySessionRepository finarySessionRepository;
    @Mock FinaryPersistenceHelper persistenceHelper;
    @Mock FinaryHoldingsImporter holdingsImporter;

    @InjectMocks FinaryApiSyncService service;

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
        stubPreview(Map.of("checkings", List.of(checking)), List.of(loan), Map.of(), List.of());
    }

    private void stubPreview(Map<String, List<FinaryAccountDto>> accountsByCategory,
                             List<FinaryLoanDto> loans,
                             Map<String, List<FinaryTransactionDto>> txByCategory,
                             List<Account> existing) {
        when(finarySessionRepository.findByMemberId(1L)).thenReturn(Optional.of(connectedSession()));
        when(encryption.decrypt("enc-email")).thenReturn("user@example.com");
        when(encryption.decrypt("enc-pass")).thenReturn("secret");
        when(finaryApiClient.authenticate("user@example.com", "secret", null)).thenReturn("jwt");
        when(finaryApiClient.fetchOrganizationContext("jwt"))
            .thenReturn(new FinaryApiClient.OrgContext("org", "mem"));
        when(finaryApiClient.fetchCategoryAccounts(eq("jwt"), any(), anyString()))
            .thenAnswer(inv -> accountsByCategory.getOrDefault(inv.<String>getArgument(2), List.of()));
        when(finaryApiClient.fetchLoans("jwt")).thenReturn(loans);
        when(finaryApiClient.fetchCategoryTransactions(any(), any(), anyString(), anyInt(), anyInt()))
            .thenAnswer(inv -> txByCategory.getOrDefault(inv.<String>getArgument(2), List.of()));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(existing);
    }

    private static FinaryAccountDto eurChecking(double balance, double displayBalance) {
        return new FinaryAccountDto(
            "chk-1", "Checking", null, balance, balance, null,
            new FinaryAccountCurrency("EUR", "€"), false,
            displayBalance, null, null, null, null, null, null, null);
    }

    private static FinaryAccountDto usdWallet() {
        return new FinaryAccountDto(
            "w1", "Bitcoin bridge wallet", null, 9179.30, 9179.30, null,
            new FinaryAccountCurrency("USD", "$"), false,
            7920.94, null, null, null, null, null, null, null);
    }

    private static FinaryTransactionDto tx(String accountId, String date, double value, String currency) {
        return new FinaryTransactionDto(
            1L, "tx", null, null, date, value, "debit", null,
            new FinaryTransactionCurrency(currency, currency), new FinaryTransactionAccount(accountId, null, null));
    }

    private static Account existingAccount(Long id, String name, AccountType type) {
        Account account = Account.builder()
            .name(name).type(type).currency("EUR").currentBalance(java.math.BigDecimal.ZERO).build();
        account.setId(id);
        return account;
    }

    private FinaryAccountMapping mapExisting(String finaryId, String category, Long targetId) {
        return new FinaryAccountMapping(finaryId, finaryId, category, FinaryMappingAction.MAP_EXISTING, targetId, null);
    }

    @Test
    void preview_includesLoanAccountsFromLoansEndpoint() {
        FinaryAccountDto checking = FinaryAccountDto.withoutHoldings(
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
        FinaryAccountDto checking = FinaryAccountDto.withoutHoldings(
            "chk-1", "Checking", null, 1000.0, 1000.0, null,
            new FinaryAccountCurrency("EUR", "€"), false);
        FinaryLoanDto loan = new FinaryLoanDto(
            "loan-1", "loan", "PRET CONSO Auto", 12345.67, 250.0,
            "2023-01-01", "2028-01-01", new FinaryAccountCurrency("EUR", "€"), null);

        stubPreviewFlow(checking, loan);
        when(accountRepository.findByExternalAccountIdAndMemberId(anyString(), eq(1L)))
            .thenReturn(Optional.empty());
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
        org.mockito.Mockito.verify(holdingsImporter)
            .importHoldings(org.mockito.ArgumentMatchers.any(Account.class), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
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
    void autoSync_isTransactional_becauseItSelfInvokesExecute() throws NoSuchMethodException {
        var method = FinaryApiSyncService.class.getMethod("autoSync", Long.class);
        assertThat(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNotNull();
    }

    @Test
    void previewAndExecute_keepTheNativeUsdBalance_whenNothingProvesTheDisplayCurrencyIsEur() {
        stubPreview(Map.of("cryptos", List.of(usdWallet())), List.of(), Map.of(), List.of());
        when(familyMemberRepository.findById(1L)).thenReturn(Optional.of(FamilyMember.builder().id(1L).build()));
        when(accountRepository.findByExternalAccountIdAndMemberId(anyString(), eq(1L))).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        FinaryPreviewResponse preview = service.preview(null, 1L);
        FinaryAccountPreview row = preview.accounts().get(0);
        assertThat(row.currentBalance()).isEqualTo(9179.30);
        assertThat(row.nativeCurrency()).isEqualTo("USD");

        service.execute(preview.fileToken(), List.of(new FinaryAccountMapping(
            "w1", "Bitcoin bridge wallet", "cryptos", FinaryMappingAction.CREATE_NEW, null,
            new NewAccountDetails("Wallet", AccountType.CRYPTO, "Finary", "EUR", null))), 1L);

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getCurrentBalance()).isEqualByComparingTo("9179.30");
        assertThat(saved.getValue().getCurrency()).isEqualTo("USD");
        verify(holdingsImporter).importHoldings(any(Account.class), any(), eq(false));
    }

    @Test
    void previewAndExecute_useTheEurDisplayBalance_onceAnEurAccountProvesTheDisplayCurrency() {
        stubPreview(Map.of("checkings", List.of(eurChecking(1000.0, 1000.0)), "cryptos", List.of(usdWallet())),
            List.of(), Map.of(), List.of());
        when(familyMemberRepository.findById(1L)).thenReturn(Optional.of(FamilyMember.builder().id(1L).build()));
        when(accountRepository.findByExternalAccountIdAndMemberId(anyString(), eq(1L))).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        FinaryPreviewResponse preview = service.preview(null, 1L);
        FinaryAccountPreview wallet = preview.accounts().stream()
            .filter(a -> "w1".equals(a.finaryId())).findFirst().orElseThrow();
        assertThat(wallet.currentBalance()).isEqualTo(7920.94);
        assertThat(wallet.nativeCurrency()).isEqualTo("EUR");

        service.execute(preview.fileToken(), List.of(new FinaryAccountMapping(
            "w1", "Bitcoin bridge wallet", "cryptos", FinaryMappingAction.CREATE_NEW, null,
            new NewAccountDetails("Wallet", AccountType.CRYPTO, "Finary", "USD", null))), 1L);

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getCurrentBalance()).isEqualByComparingTo("7920.94");
        assertThat(saved.getValue().getCurrency()).isEqualTo("EUR");
        verify(holdingsImporter).importHoldings(any(Account.class), any(), eq(true));
    }

    @Test
    void execute_handsTransactionsOverInTheirOwnCurrency_forTheHistoryToConvert() {
        FinaryAccountDto usdChecking = new FinaryAccountDto(
            "usd-1", "US checking", null, 1000.0, 1000.0, null,
            new FinaryAccountCurrency("USD", "$"), false,
            900.0, null, null, null, null, null, null, null);
        stubPreview(Map.of("checkings", List.of(usdChecking, eurChecking(5000.0, 5000.0))),
            List.of(), Map.of("checkings", List.of(tx("usd-1", "2026-10-30T10:00:00Z", 100.0, "USD"))), List.of());
        Account target = existingAccount(7L, "US checking", AccountType.CHECKING);
        when(accountRepository.findByIdAndMemberId(7L, 1L)).thenReturn(Optional.of(target));
        when(familyMemberRepository.findById(1L)).thenReturn(Optional.of(FamilyMember.builder().id(1L).build()));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        FinaryPreviewResponse preview = service.preview(null, 1L);
        service.execute(preview.fileToken(), List.of(mapExisting("usd-1", "checkings", 7L)), 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FinaryPersistenceHelper.ParsedFinaryTransaction>> txs = ArgumentCaptor.forClass(List.class);
        verify(persistenceHelper).importTransactions(eq(target), any(), txs.capture());
        verify(persistenceHelper).reconstructSnapshotsFromDb(target);
        assertThat(target.getCurrency()).isEqualTo("EUR");
        assertThat(txs.getValue()).singleElement().satisfies(parsed -> {
            assertThat(parsed.amount()).isEqualByComparingTo("100.0");
            assertThat(parsed.currency()).isEqualTo("USD");
        });
    }

    @Test
    void previewAndExecute_keepTheNativeBalance_whenTheOnlyEurWitnessIsTooSmallToProveTheDisplayCurrency() {
        // €0.01 also reads $0.01 once rounded, so it cannot tell a EUR display from a USD one.
        stubPreview(Map.of("checkings", List.of(eurChecking(0.01, 0.01)), "cryptos", List.of(usdWallet())),
            List.of(), Map.of(), List.of());
        when(familyMemberRepository.findById(1L)).thenReturn(Optional.of(FamilyMember.builder().id(1L).build()));
        when(accountRepository.findByExternalAccountIdAndMemberId(anyString(), eq(1L))).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        FinaryPreviewResponse preview = service.preview(null, 1L);
        FinaryAccountPreview wallet = preview.accounts().stream()
            .filter(a -> "w1".equals(a.finaryId())).findFirst().orElseThrow();
        assertThat(wallet.currentBalance()).isEqualTo(9179.30);
        assertThat(wallet.nativeCurrency()).isEqualTo("USD");

        service.execute(preview.fileToken(), List.of(new FinaryAccountMapping(
            "w1", "Bitcoin bridge wallet", "cryptos", FinaryMappingAction.CREATE_NEW, null,
            new NewAccountDetails("Wallet", AccountType.CRYPTO, "Finary", "EUR", null))), 1L);

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getCurrentBalance()).isEqualByComparingTo("9179.30");
        assertThat(saved.getValue().getCurrency()).isEqualTo("USD");
        verify(holdingsImporter).importHoldings(any(Account.class), any(), eq(false));
    }

    @Test
    void execute_keepsTheExternalIdAndHoldingsOfAnAccountOwnedByAnotherConnector() {
        FinaryAccountDto investment = FinaryAccountDto.withoutHoldings(
            "inv-1", "Trade Republic", null, 500.0, 500.0, null,
            new FinaryAccountCurrency("EUR", "€"), false);
        stubPreview(Map.of("investments", List.of(investment)), List.of(), Map.of(), List.of());
        Account tradeRepublic = existingAccount(5L, "Trade Republic", AccountType.COMPTE_TITRES);
        tradeRepublic.setManual(false);
        tradeRepublic.setExternalAccountId("tr_portfolio_42");
        when(accountRepository.findByIdAndMemberId(5L, 1L)).thenReturn(Optional.of(tradeRepublic));
        when(familyMemberRepository.findById(1L)).thenReturn(Optional.of(FamilyMember.builder().id(1L).build()));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        FinaryPreviewResponse preview = service.preview(null, 1L);
        service.execute(preview.fileToken(), List.of(mapExisting("inv-1", "investments", 5L)), 1L);

        assertThat(tradeRepublic.getExternalAccountId()).isEqualTo("tr_portfolio_42");
        verify(holdingsImporter, never()).importHoldings(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void execute_rejectsTwoRowsMappedOntoOneAccount_beforeChangingAnything() {
        FinaryAccountDto livret = FinaryAccountDto.withoutHoldings(
            "sav-1", "Livret A", null, 100.0, 100.0, null, new FinaryAccountCurrency("EUR", "€"), false);
        stubPreview(Map.of("checkings", List.of(eurChecking(1000.0, 1000.0)), "savings", List.of(livret)),
            List.of(), Map.of(), List.of());

        FinaryPreviewResponse preview = service.preview(null, 1L);
        List<FinaryAccountMapping> clash = List.of(
            mapExisting("chk-1", "checkings", 7L), mapExisting("sav-1", "savings", 7L));

        assertThatThrownBy(() -> service.execute(preview.fileToken(), clash, 1L))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("7");
        verify(accountRepository, never()).save(any());
        verify(holdingsImporter, never()).importHoldings(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void execute_rejectsACreateNewRowWhoseUpsertTargetIsAlsoMappedExplicitly_beforeChangingAnything() {
        FinaryAccountDto a = FinaryAccountDto.withoutHoldings(
            "a", "PEA", null, 100.0, 100.0, null, new FinaryAccountCurrency("EUR", "€"), false);
        FinaryAccountDto b = FinaryAccountDto.withoutHoldings(
            "b", "CTO", null, 200.0, 200.0, null, new FinaryAccountCurrency("EUR", "€"), false);
        stubPreview(Map.of("investments", List.of(a, b)), List.of(), Map.of(), List.of());
        Account ownsA = existingAccount(7L, "PEA", AccountType.PEA);
        ownsA.setExternalAccountId("finary_investments_a");
        when(accountRepository.findByExternalAccountIdAndMemberId("finary_investments_a", 1L))
            .thenReturn(Optional.of(ownsA));

        FinaryPreviewResponse preview = service.preview(null, 1L);
        List<FinaryAccountMapping> clash = List.of(
            new FinaryAccountMapping("a", "PEA", "investments", FinaryMappingAction.CREATE_NEW, null,
                new NewAccountDetails("PEA", AccountType.PEA, "Finary", "EUR", null)),
            mapExisting("b", "investments", 7L));

        assertThatThrownBy(() -> service.execute(preview.fileToken(), clash, 1L))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("7");
        verify(accountRepository, never()).save(any());
        verify(holdingsImporter, never()).importHoldings(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(persistenceHelper, never()).importTransactions(any(), any(), any());
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
