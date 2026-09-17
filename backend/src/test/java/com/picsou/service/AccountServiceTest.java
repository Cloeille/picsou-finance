package com.picsou.service;

import com.picsou.dto.AccountRequest;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.DebtRequest;
import com.picsou.dto.HoldingResponse;
import com.picsou.dto.RealEstateMetadataResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.Debt;
import com.picsou.model.FamilyMember;
import com.picsou.model.PropertyKind;
import com.picsou.model.PropertyValuation;
import com.picsou.model.RealEstateMetadata;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.PropertyValuationRepository;
import com.picsou.repository.RealEstateMetadataRepository;
import com.picsou.repository.SavingsInterestConfigRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock RealEstateMetadataRepository realEstateMetadataRepository;
    @Mock PropertyValuationRepository propertyValuationRepository;
    @Mock DebtRepository debtRepository;
    @Mock SavingsInterestConfigRepository savingsInterestConfigRepository;
    @Mock PriceService priceService;
    @Mock LoanAmortizationService loanAmortizationService;
    @Mock AccountAccessResolver accessResolver;
    @Mock BankLogoResolver bankLogoResolver;
    @InjectMocks AccountService accountService;

    private Account ownedAccount() {
        return Account.builder()
            .id(1L)
            .name("TR Titres")
            .type(AccountType.COMPTE_TITRES)
            .currency("EUR")
            .build();
    }

    /**
     * Stubs the batched resolution: {@code "AAPL", "200"} means a live quote at 200 EUR, a null
     * price means the asset resolved to nothing at all (no live price and none recorded).
     */
    private void stubQuotes(String... tickerThenPrice) {
        Map<String, PriceService.Quote> quotes = new java.util.HashMap<>();
        for (int i = 0; i < tickerThenPrice.length; i += 2) {
            String price = tickerThenPrice[i + 1];
            if (price != null) {
                quotes.put(tickerThenPrice[i],
                    new PriceService.Quote(new BigDecimal(price), LocalDate.now(), true));
            }
        }
        when(priceService.getQuotes(any())).thenReturn(quotes);
    }

    // ─── logo key ─────────────────────────────────────────────────────────────

    @Test
    void update_setsTheLogoKeyThePickerSent() {
        Account account = Account.builder().id(1L).name("BITCOIN Wallet").type(AccountType.CRYPTO)
            .currency("EUR").logoKey("blockchain").build();
        when(accountRepository.findByIdAndMemberId(1L, 7L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.update(1L, logoRequest("ledger"), 7L);

        assertThat(account.getLogoKey()).isEqualTo("ledger");
    }

    @Test
    void update_keepsTheStoredLogoKey_whenTheClientSendsNone() {
        // Unlike ticker, an absent logoKey means "this client doesn't know about logos" -- the
        // MCP update_account tool sends none. Clearing it there would drop a wallet's Ledger
        // mark as a side effect of renaming the account.
        Account account = Account.builder().id(1L).name("BITCOIN Wallet").type(AccountType.CRYPTO)
            .currency("EUR").logoKey("ledger").build();
        when(accountRepository.findByIdAndMemberId(1L, 7L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.update(1L, logoRequest(null), 7L);

        assertThat(account.getLogoKey()).isEqualTo("ledger");
    }

    @Test
    void update_dropsTheLogoKey_whenTheAccountIsRetypedAwayFromCrypto() {
        // The picker offers no "none", and an omitted key is kept -- so without this the
        // blockchain mark would follow a wallet retyped to CHECKING forever, with no way back.
        Account account = Account.builder().id(1L).name("BITCOIN Wallet").type(AccountType.CRYPTO)
            .currency("EUR").logoKey("ledger").build();
        when(accountRepository.findByIdAndMemberId(1L, 7L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.update(1L, new AccountRequest("Livret", AccountType.SAVINGS, "BTC", "EUR",
            null, false, "#f59e0b", null, null, null, null), 7L);

        assertThat(account.getLogoKey()).isNull();
    }

    @Test
    void update_ignoresALogoKeyOnACryptoAccountThatCarriesNone() {
        // CRYPTO covers exchange accounts too, and those already have a brand mark keyed on
        // provider -- a key sent by hand would win over it and show a Ledger on a Meria
        // account. Only an account WalletSyncService already seeded gets to swap its mark.
        Account exchange = Account.builder().id(1L).name("Meria").type(AccountType.CRYPTO)
            .provider("MERIA").currency("EUR").build();
        when(accountRepository.findByIdAndMemberId(1L, 7L)).thenReturn(Optional.of(exchange));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.update(1L, new AccountRequest("Meria", AccountType.CRYPTO, "MERIA", "EUR",
            null, false, "#f59e0b", null, "ledger", null, null), 7L);

        assertThat(exchange.getLogoKey()).isNull();
    }

    @Test
    void create_ignoresALogoKeyOnANonCryptoAccount() {
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse created = accountService.create(
            new AccountRequest("Livret", AccountType.SAVINGS, null, "EUR",
                null, true, "#f59e0b", null, "ledger", null, null),
            FamilyMember.builder().id(7L).build());

        assertThat(created.logoKey()).isNull();
    }

    @Test
    void create_ignoresALogoKeyOnACryptoAccountToo() {
        // A key is never introduced by a request: only WalletSyncService seeds one, and it
        // builds the wallet's row itself rather than going through create().
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse created = accountService.create(
            new AccountRequest("BITCOIN Wallet", AccountType.CRYPTO, "BTC", "EUR",
                null, false, "#f59e0b", null, "ledger", null, null),
            FamilyMember.builder().id(7L).build());

        assertThat(created.logoKey()).isNull();
    }

    // --- Bank logo on a manual account -------------------------------------------------

    @Test
    void create_resolvesTheBankLogoOfAManualAccountFromTheInstitutionThePickerSent() {
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bankLogoResolver.logoUrlOrNull("FR", "Crédit Agricole::FR::personal", "Crédit Agricole"))
            .thenReturn("https://cdn.example/ca.png");

        AccountResponse created = accountService.create(
            bankRequest("Crédit Agricole", "Crédit Agricole::FR::personal", true),
            FamilyMember.builder().id(7L).build());

        assertThat(created.logoUrl()).isEqualTo("https://cdn.example/ca.png");
    }

    @Test
    void create_searchesTheDefaultCountryWhenNoInstitutionWasPicked() {
        // A hand-typed bank name, or the MCP tools. An unfiltered search would pull the whole
        // multi-country catalog on a path that runs on every account write.
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.create(bankRequest("Crédit Agricole", null, true),
            FamilyMember.builder().id(7L).build());

        verify(bankLogoResolver).logoUrlOrNull("FR", null, "Crédit Agricole");
    }

    @Test
    void create_doesNotLookUpALogoForASyncedAccount() {
        // A connector owns its accounts' logos -- Enable Banking copies one off the requisition,
        // and a named provider (Trade Republic, BoursoBank) resolves a bundled asset client-side.
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse created = accountService.create(
            bankRequest("Trade Republic", null, false),
            FamilyMember.builder().id(7L).build());

        assertThat(created.logoUrl()).isNull();
        verify(bankLogoResolver, never()).logoUrlOrNull(any(), any(), any());
    }

    @Test
    void update_reResolvesTheLogoWhenTheBankChanges() {
        Account account = manualBankAccount("Crédit Agricole", "https://cdn.example/ca.png");
        when(accountRepository.findByIdAndMemberId(1L, 7L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bankLogoResolver.logoUrlOrNull("FR", "BNP Paribas::FR::personal", "BNP Paribas"))
            .thenReturn("https://cdn.example/bnp.png");

        accountService.update(1L, bankRequest("BNP Paribas", "BNP Paribas::FR::personal", true), 7L);

        assertThat(account.getLogoUrl()).isEqualTo("https://cdn.example/bnp.png");
    }

    @Test
    void update_keepsTheLogoAndSkipsTheLookupWhenTheBankIsUnchanged() {
        // Renaming an account or correcting its balance must not cost a catalog round-trip.
        Account account = manualBankAccount("Crédit Agricole", "https://cdn.example/ca.png");
        when(accountRepository.findByIdAndMemberId(1L, 7L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.update(1L, bankRequest("Crédit Agricole", null, true), 7L);

        assertThat(account.getLogoUrl()).isEqualTo("https://cdn.example/ca.png");
        verify(bankLogoResolver, never()).logoUrlOrNull(any(), any(), any());
    }

    @Test
    void update_looksTheLogoUpAgainForAnAccountThatNeverGotOne() {
        // The account predates the picker: its bank was typed by hand and matched nothing at the
        // time. Opening the form and saving is what gives it a second chance.
        Account account = manualBankAccount("Crédit Agricole", null);
        when(accountRepository.findByIdAndMemberId(1L, 7L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bankLogoResolver.logoUrlOrNull("FR", "Crédit Agricole::FR::personal", "Crédit Agricole"))
            .thenReturn("https://cdn.example/ca.png");

        accountService.update(1L, bankRequest("Crédit Agricole", "Crédit Agricole::FR::personal", true), 7L);

        assertThat(account.getLogoUrl()).isEqualTo("https://cdn.example/ca.png");
    }

    @Test
    void update_clearsTheLogoWhenTheBankIsCleared() {
        Account account = manualBankAccount("Crédit Agricole", "https://cdn.example/ca.png");
        when(accountRepository.findByIdAndMemberId(1L, 7L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.update(1L, bankRequest(null, null, true), 7L);

        assertThat(account.getLogoUrl()).isNull();
    }

    @Test
    void update_neverTouchesTheLogoOfASyncedAccount() {
        // Enable Banking wrote this one from its requisition; a free-text provider edit -- or an
        // MCP client that blanks provider outright -- must not be able to drop it.
        Account synced = Account.builder().id(1L).name("BoursoBank").type(AccountType.CHECKING)
            .currency("EUR").provider("BoursoBank").logoUrl("https://cdn.example/bourso.png")
            .isManual(false).build();
        when(accountRepository.findByIdAndMemberId(1L, 7L)).thenReturn(Optional.of(synced));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.update(1L, bankRequest(null, null, false), 7L);

        assertThat(synced.getLogoUrl()).isEqualTo("https://cdn.example/bourso.png");
        verify(bankLogoResolver, never()).logoUrlOrNull(any(), any(), any());
    }

    private static AccountRequest bankRequest(String provider, String institutionId, boolean isManual) {
        return new AccountRequest("Compte", AccountType.CHECKING, provider, "EUR",
            null, isManual, "#6366f1", null, null, institutionId, null);
    }

    private static Account manualBankAccount(String provider, String logoUrl) {
        return Account.builder().id(1L).name("Compte").type(AccountType.CHECKING)
            .currency("EUR").provider(provider).logoUrl(logoUrl).isManual(true).build();
    }

    private static AccountRequest logoRequest(String logoKey) {
        return new AccountRequest("BITCOIN Wallet", AccountType.CRYPTO, "BTC", "EUR",
            null, false, "#f59e0b", null, logoKey, null, null);
    }

    @Test
    void findAll_excludesHiddenAccounts_byDefault() {
        Account visible = ownedAccount();
        Account hidden = Account.builder()
            .id(2L).name("Hidden One").type(AccountType.CHECKING).currency("EUR").hidden(true)
            .build();
        when(accessResolver.readableAccounts(1L)).thenReturn(List.of(visible, hidden));
        when(accessResolver.sharesFor(any(), eq(1L))).thenAnswer(inv -> {
            java.util.Collection<Account> accs = inv.getArgument(0);
            java.util.Map<Long, BigDecimal> shares = new java.util.HashMap<>();
            for (Account a : accs) shares.put(a.getId(), new BigDecimal("100"));
            return shares;
        });

        List<com.picsou.dto.AccountResponse> result = accountService.findAll(1L);

        assertThat(result).hasSize(1);
        assertThat(result).noneMatch(com.picsou.dto.AccountResponse::hidden);
        verify(accessResolver).readableAccounts(1L);
    }

    @Test
    void findAll_includesHiddenAccounts_whenRequested() {
        Account visible = ownedAccount();
        Account hidden = Account.builder()
            .id(2L).name("Hidden One").type(AccountType.CHECKING).currency("EUR").hidden(true)
            .build();
        when(accessResolver.readableAccounts(1L)).thenReturn(List.of(visible, hidden));
        when(accessResolver.sharesFor(any(), eq(1L))).thenAnswer(inv -> {
            java.util.Collection<Account> accs = inv.getArgument(0);
            java.util.Map<Long, BigDecimal> shares = new java.util.HashMap<>();
            for (Account a : accs) shares.put(a.getId(), new BigDecimal("100"));
            return shares;
        });

        List<com.picsou.dto.AccountResponse> result = accountService.findAll(1L, true);

        assertThat(result).hasSize(2);
        assertThat(result).anyMatch(com.picsou.dto.AccountResponse::hidden);
        verify(accessResolver).readableAccounts(1L);
    }

    @Test
    void setHidden_persistsFlag_forOwnedAccount() {
        Account account = ownedAccount();
        when(accountRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        AccountResponse response = accountService.setHidden(1L, 1L, true);

        assertThat(account.isHidden()).isTrue();
        assertThat(response.hidden()).isTrue();
        verify(accountRepository).save(account);
    }

    @Test
    void setHidden_throws_whenAccountNotOwnedByMember() {
        when(accountRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.setHidden(1L, 1L, true))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void pruneHoldings_deletesOnlyTickersNotKept() {
        accountService.pruneHoldings(ownedAccount(), Set.of("BTC", "ETH"));

        verify(holdingRepository).deleteByAccountIdAndTickerNotIn(1L, Set.of("BTC", "ETH"));
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void pruneHoldings_emptyKeepSet_clearsAllHoldings() {
        // No asset survived (empty wallet) -> remove every holding, but never issue
        // a NOT IN () against an empty set.
        accountService.pruneHoldings(ownedAccount(), Set.of());

        verify(holdingRepository).deleteByAccountId(1L);
        verify(holdingRepository, never()).deleteByAccountIdAndTickerNotIn(any(), any());
    }

    @Test
    void getHoldings_returnsNullValue_whenPriceServiceHasNoPrice() {
        when(accountRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(ownedAccount()));
        AccountHolding holding = AccountHolding.builder()
            .id(10L)
            .ticker("PHYMF")
            .quantity(new BigDecimal("10"))
            .averageBuyIn(new BigDecimal("100"))
            // Stored from a broker sync in unknown currency — must NOT be used as EUR.
            .currentPrice(new BigDecimal("999"))
            .build();
        when(holdingRepository.findByAccountIdOrderByCurrentPriceDesc(1L))
            .thenReturn(List.of(holding));
        stubQuotes("PHYMF", null);

        List<HoldingResponse> result = accountService.getHoldings(1L, 1L);

        assertThat(result).hasSize(1);
        HoldingResponse h = result.get(0);
        // The key invariant: no fallback to holding.currentPrice (999) × quantity (10) = 9990.
        assertThat(h.currentValueEur()).isNull();
        assertThat(h.pnlEur()).isNull();
        assertThat(h.pnlPercent()).isNull();
    }

    @Test
    void getHoldings_usesBrokerEurSnapshot_whenLivePriceIsUnavailable() {
        when(accountRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(ownedAccount()));
        AccountHolding holding = AccountHolding.builder()
            .id(10L)
            .ticker("FR0000000001")
            .quantity(new BigDecimal("10"))
            .averageBuyIn(new BigDecimal("90"))
            .currentPrice(new BigDecimal("100"))
            .quoteCurrency("EUR")
            .providerValueEur(new BigDecimal("1000"))
            .providerPnlEur(new BigDecimal("200"))
            .build();
        when(holdingRepository.findByAccountIdOrderByCurrentPriceDesc(1L))
            .thenReturn(List.of(holding));
        stubQuotes("FR0000000001", null);

        HoldingResponse result = accountService.getHoldings(1L, 1L).getFirst();

        assertThat(result.currentValueEur()).isEqualByComparingTo("1000");
        assertThat(result.costBasisEur()).isEqualByComparingTo("800");
        assertThat(result.pnlEur()).isEqualByComparingTo("200");
        assertThat(result.pnlPercent()).isEqualByComparingTo("25");
    }

    @Test
    void getHoldings_computesValue_whenPriceServiceHasPrice() {
        when(accountRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(ownedAccount()));
        AccountHolding holding = AccountHolding.builder()
            .id(10L)
            .ticker("AAPL")
            .quantity(new BigDecimal("5"))
            .averageBuyIn(new BigDecimal("150"))
            .currentPrice(new BigDecimal("180"))  // native-currency, must be ignored
            .build();
        when(holdingRepository.findByAccountIdOrderByCurrentPriceDesc(1L))
            .thenReturn(List.of(holding));
        // Yahoo returned 200 EUR/share after FX conversion (e.g. ~217 USD × 0.92).
        stubQuotes("AAPL", "200");

        List<HoldingResponse> result = accountService.getHoldings(1L, 1L);

        HoldingResponse h = result.get(0);
        assertThat(h.currentValueEur()).isEqualByComparingTo("1000"); // 5 × 200
        assertThat(h.pnlEur()).isEqualByComparingTo("250"); // 1000 − (5 × 150)
        assertThat(h.pnlPercent().doubleValue()).isCloseTo(33.33, within(0.1));
    }

    @Test
    void updateDebtMetadata_rejectsLinkedAccount_notOwnedByMember() {
        // Caller (member 1) owns the loan account (id 1)...
        when(accountRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(ownedAccount()));
        when(debtRepository.findByAccountId(1L)).thenReturn(Optional.empty());
        // ...but points linkedAccountId at account 2, which is NOT theirs: the member-scoped
        // lookup finds nothing. Previously this used an unscoped findById, leaking the foreign
        // account's name back via DebtResponse (BOLA). It must now be rejected.
        when(accountRepository.findByIdAndMemberId(2L, 1L)).thenReturn(Optional.empty());

        DebtRequest req = new DebtRequest(
            2L, new BigDecimal("100000"), new BigDecimal("0.03"), new BigDecimal("500"),
            "Bank", null, null, null, null);

        assertThatThrownBy(() -> accountService.updateDebtMetadata(1L, 1L, req))
            .isInstanceOf(ResourceNotFoundException.class);
        // The cross-member reference must never be persisted.
        verify(debtRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // ─── liveBalanceEur characterization ──────────────────────────────────────

    private Account loanAccount() {
        return Account.builder()
            .id(1L)
            .name("Mortgage")
            .type(AccountType.LOAN)
            .currency("EUR")
            .currentBalance(new BigDecimal("12000"))
            .build();
    }

    @Test
    void liveBalanceEur_loanWithDebt_returnsPositiveRemainingBalance() {
        Account loan = loanAccount();
        Debt debt = Debt.builder().build();
        when(debtRepository.findByAccountId(1L)).thenReturn(Optional.of(debt));
        when(loanAmortizationService.computeRemainingBalance(eq(debt), any(LocalDate.class)))
            .thenReturn(new BigDecimal("8500"));

        BigDecimal result = accountService.liveBalanceEur(loan);

        // computeRemainingBalance returns a POSITIVE outstanding amount and
        // liveBalanceEur passes it through unnegated — callers negate loans themselves.
        assertThat(result).isEqualByComparingTo("8500");
    }

    @Test
    void liveBalanceEur_loanWithoutDebt_fallsBackToStoredBalance() {
        Account loan = loanAccount();
        when(debtRepository.findByAccountId(1L)).thenReturn(Optional.empty());
        when(priceService.toEur(new BigDecimal("12000"), "EUR", null)).thenReturn(new BigDecimal("12000"));

        BigDecimal result = accountService.liveBalanceEur(loan);

        // No Debt row → plain toEur pass-through of the stored balance, sign untouched.
        assertThat(result).isEqualByComparingTo("12000");
    }

    @Test
    void liveBalanceEur_skipsHoldingsWithoutLivePrice() {
        Account account = ownedAccount();
        AccountHolding priced = AccountHolding.builder()
            .ticker("AAPL").quantity(new BigDecimal("5")).build();
        AccountHolding unpriced = AccountHolding.builder()
            .ticker("PHYMF").quantity(new BigDecimal("10")).build();
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of(priced, unpriced));
        stubQuotes("AAPL", "200", "PHYMF", null);

        BigDecimal result = accountService.liveBalanceEur(account);

        // CHARACTERIZATION: unpriced holdings are silently skipped (audit TEST-04).
        assertThat(result).isEqualByComparingTo("1000"); // 5 × 200; PHYMF contributes nothing
    }

    @Test
    void liveBalanceEur_usesBrokerPositionValue_whenLivePriceIsUnavailable() {
        Account account = ownedAccount();
        AccountHolding priced = AccountHolding.builder()
            .ticker("AAPL").quantity(new BigDecimal("5")).build();
        AccountHolding brokerValued = AccountHolding.builder()
            .ticker("PHYMF").quantity(new BigDecimal("10"))
            .providerValueEur(new BigDecimal("840")).build();
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of(priced, brokerValued));
        stubQuotes("AAPL", "200", "PHYMF", null);

        BigDecimal result = accountService.liveBalanceEur(account);

        assertThat(result).isEqualByComparingTo("1840"); // 5 × 200 + broker's 840
    }

    /**
     * The value/cost pairing for a broker-valued line. Adding its EUR value while skipping its
     * cost basis reports a gain the exact size of the position — the -85% mismatch with the sign
     * flipped — and dailySnapshots would write that into balance_snapshot.
     */
    @Test
    void valuation_countsTheCostBasisOfABrokerValuedLine_notJustItsValue() {
        Account account = ownedAccount();
        AccountHolding brokerValued = AccountHolding.builder()
            .ticker("PHYMF").quantity(new BigDecimal("10"))
            .providerValueEur(new BigDecimal("840"))
            .providerPnlEur(new BigDecimal("40"))   // cost basis = 840 - 40 = 800
            .build();
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of(brokerValued));
        stubQuotes("PHYMF", null);

        AccountService.Valuation valuation = accountService.valuation(account);

        assertThat(valuation.liveEur()).isEqualByComparingTo("840");
        assertThat(valuation.investedEur()).isEqualByComparingTo("800");
    }

    @Test
    void liveBalanceEur_cashAccount_convertsStoredBalance() {
        Account cash = Account.builder()
            .id(2L)
            .name("USD Cash")
            .type(AccountType.CHECKING)
            .currency("USD")
            .currentBalance(new BigDecimal("2500"))
            .build();
        when(holdingRepository.findByAccount_Id(2L)).thenReturn(List.of());
        when(priceService.toEur(new BigDecimal("2500"), "USD", null)).thenReturn(new BigDecimal("2300"));

        BigDecimal result = accountService.liveBalanceEur(cash);

        assertThat(result).isEqualByComparingTo("2300");
    }

    @Test
    void liveBalanceEur_bourseDirect_addsCashWhenAllPositionsArePriced() {
        Account account = Account.builder().id(3L).name("PEA Bourse Direct")
            .type(AccountType.PEA).provider("Bourse Direct").currency("EUR")
            .currentBalance(new BigDecimal("1250")).cashBalance(new BigDecimal("250")).build();
        AccountHolding holding = AccountHolding.builder()
            .ticker("ACME").quantity(new BigDecimal("10")).build();
        when(holdingRepository.findByAccount_Id(3L)).thenReturn(List.of(holding));
        stubQuotes("ACME", "100");

        assertThat(accountService.liveBalanceEur(account)).isEqualByComparingTo("1250");
    }

    @Test
    void liveBalanceEur_bourseDirect_usesBrokerTotalWhenAnyPositionIsUnpriced() {
        Account account = Account.builder().id(3L).name("PEA Bourse Direct")
            .type(AccountType.PEA).provider("Bourse Direct").currency("EUR")
            .currentBalance(new BigDecimal("1250")).cashBalance(new BigDecimal("250")).build();
        AccountHolding holding = AccountHolding.builder()
            .ticker("UNKNOWN").quantity(new BigDecimal("10")).build();
        when(holdingRepository.findByAccount_Id(3L)).thenReturn(List.of(holding));
        stubQuotes("UNKNOWN", null);

        assertThat(accountService.liveBalanceEur(account)).isEqualByComparingTo("1250");
    }

    /**
     * Yahoo can never quote an FCPE, so without the provider-valued fallback an
     * Amundi plan collapses to its (null) cash sleeve -- i.e. zero -- and the
     * dashboard books the whole plan as a loss.
     */
    @Test
    void liveBalanceEur_amundi_usesTheProviderTotalWhenNoFcpeCanBePriced() {
        Account account = Account.builder().id(4L).name("PEG — ACME SA")
            .type(AccountType.EMPLOYEE_SAVINGS).provider("Amundi Épargne Salariale")
            .currency("EUR").currentBalance(new BigDecimal("1234.56")).build();
        AccountHolding holding = AccountHolding.builder()
            .ticker("FR0010405035").quantity(new BigDecimal("12.3456")).build();
        when(holdingRepository.findByAccount_Id(4L)).thenReturn(List.of(holding));
        stubQuotes("FR0010405035", null);

        assertThat(accountService.liveBalanceEur(account)).isEqualByComparingTo("1234.56");
    }

    @Test
    void liveBalanceEur_amundi_stillPrefersLivePricesWhenEveryFcpeResolves() {
        Account account = Account.builder().id(4L).name("PEG — ACME SA")
            .type(AccountType.EMPLOYEE_SAVINGS).provider("Amundi Épargne Salariale")
            .currency("EUR").currentBalance(new BigDecimal("1000")).build();
        AccountHolding holding = AccountHolding.builder()
            .ticker("FR0010405035").quantity(new BigDecimal("10")).build();
        when(holdingRepository.findByAccount_Id(4L)).thenReturn(List.of(holding));
        stubQuotes("FR0010405035", "123.456");

        assertThat(accountService.liveBalanceEur(account)).isEqualByComparingTo("1234.56");
    }

    /**
     * BoursoBank's trading board exposes only its own instrument symbol, so a
     * line whose ISIN never resolved is unpriceable by construction. Without the
     * provider-valued fallback the PEA reads as its cash sleeve alone.
     */
    @Test
    void liveBalanceEur_boursoBank_usesTheProviderTotalWhenALineKeptItsBoursoSymbol() {
        Account account = Account.builder().id(5L).name("PEA DOE")
            .type(AccountType.PEA).provider("BoursoBank")
            .currency("EUR").currentBalance(new BigDecimal("143088.89")).build();
        AccountHolding holding = AccountHolding.builder()
            .ticker("1rTCW8").quantity(new BigDecimal("1000"))
            .providerValueEur(new BigDecimal("140000.00")).build();
        when(holdingRepository.findByAccount_Id(5L)).thenReturn(List.of(holding));
        stubQuotes("1RTCW8", null);

        assertThat(accountService.liveBalanceEur(account)).isEqualByComparingTo("143088.89");
    }

    @Test
    void calculateInvestedAmount_includesCashAndPrefersBrokerEurCostBasis() {
        Account account = Account.builder().id(3L)
            .currentBalance(new BigDecimal("1250"))
            .cashBalance(new BigDecimal("250"))
            .build();
        AccountHolding holding = AccountHolding.builder()
            .quantity(new BigDecimal("10"))
            .averageBuyIn(new BigDecimal("90"))
            .providerValueEur(new BigDecimal("1000"))
            .providerPnlEur(new BigDecimal("200"))
            .build();
        when(holdingRepository.findByAccount_Id(3L)).thenReturn(List.of(holding));

        assertThat(accountService.calculateInvestedAmount(account))
            .isEqualByComparingTo("1050");
    }

    @Test
    void anUnpricedHoldingLeavesTheCostBasisTooNotJustTheValue() {
        // The regression this pins, with the real numbers from 2026-08-01: a Meria account of
        // 448 EUR whose BTC and SOL failed to price reported -85%, because the two positions
        // left the value side and stayed on the cost side. Whatever is dropped from one must be
        // dropped from the other, or the account invents a loss the size of the missing lines.
        Account account = Account.builder().id(5L).name("MERIA").type(AccountType.CRYPTO)
            .currency("EUR").currentBalance(new BigDecimal("448.24")).build();
        AccountHolding priced = AccountHolding.builder()
            .ticker("ATOM").quantity(new BigDecimal("33.15"))
            .averageBuyIn(new BigDecimal("1.06")).build();
        AccountHolding unpriced = AccountHolding.builder()
            .ticker("BTC").quantity(new BigDecimal("0.00487"))
            .averageBuyIn(new BigDecimal("54570")).build();
        when(holdingRepository.findByAccount_Id(5L)).thenReturn(List.of(priced, unpriced));
        // Crypto account -> crypto-only resolution, and BTC resolves to nothing at all.
        when(priceService.getCryptoQuotes(any())).thenReturn(Map.of(
            "ATOM", new PriceService.Quote(new BigDecimal("1.06"), LocalDate.now(), true)));

        AccountService.Valuation valuation = accountService.valuation(account);

        assertThat(valuation.liveEur()).isEqualByComparingTo("35.139");   // 33.15 × 1.06
        assertThat(valuation.investedEur()).isEqualByComparingTo("35.139"); // and only that line
        assertThat(valuation.allPriced()).isFalse();
    }

    @Test
    void bourseDirectBrokerTotalIsPairedWithTheCostOfEveryPosition() {
        // The override swaps in a total covering all ten lines, so the cost basis must cover all
        // ten too. Pairing the broker's full valuation with a basis that dropped the unpriced
        // positions reports a gain the size of their cost — the -85% mismatch with the sign
        // flipped, and dailySnapshots would write it into balance_snapshot permanently.
        Account account = Account.builder().id(3L).name("PEA Bourse Direct")
            .type(AccountType.PEA).provider("Bourse Direct").currency("EUR")
            .currentBalance(new BigDecimal("5000")).build();
        AccountHolding priced = AccountHolding.builder()
            .ticker("ACME").quantity(new BigDecimal("10"))
            .averageBuyIn(new BigDecimal("90")).build();
        AccountHolding unpriced = AccountHolding.builder()
            .ticker("PHYMF").quantity(new BigDecimal("10"))
            .averageBuyIn(new BigDecimal("400")).build();
        when(holdingRepository.findByAccount_Id(3L)).thenReturn(List.of(priced, unpriced));
        stubQuotes("ACME", "100", "PHYMF", null);

        AccountService.Valuation valuation = accountService.valuation(account);

        assertThat(valuation.liveEur()).isEqualByComparingTo("5000");   // the broker's own total
        assertThat(valuation.investedEur()).isEqualByComparingTo("4900"); // 900 + 4000, both lines
    }

    @Test
    void anAccountWhereNothingCanBePricedReportsItRatherThanReturningZero() {
        // liveEur is 0 here, and a caller that persists valuations must be able to tell that
        // apart from an account genuinely worth nothing: writing it stamps a permanent dip into
        // the net-worth chart for what is usually a transient provider outage.
        Account account = Account.builder().id(5L).type(AccountType.CRYPTO).currency("EUR")
            .currentBalance(new BigDecimal("448.24")).build();
        AccountHolding holding = AccountHolding.builder()
            .ticker("BTC").quantity(new BigDecimal("0.00487"))
            .averageBuyIn(new BigDecimal("54570")).build();
        when(holdingRepository.findByAccount_Id(5L)).thenReturn(List.of(holding));
        when(priceService.getCryptoQuotes(any())).thenReturn(Map.of());

        AccountService.Valuation valuation = accountService.valuation(account);

        assertThat(valuation.anyPriced()).isFalse();
        assertThat(valuation.liveEur()).isEqualByComparingTo("0");
    }

    @Test
    void aHoldingTheProviderValuedItselfCountsAsPriced() {
        // No ticker means no lookup to fail, so this is not an unpriced holding: the provider
        // reported its EUR value directly. Reporting anyPriced() == false for it made
        // SchedulerService.dailySnapshots skip such an account *every* day — a refusal meant for
        // a transient outage, applied to a condition that never changes, so the account's
        // net-worth history simply stopped.
        Account account = ownedAccount();
        AccountHolding holding = AccountHolding.builder()
            .quantity(new BigDecimal("3"))
            .providerValueEur(new BigDecimal("500"))
            .providerPnlEur(new BigDecimal("100"))
            .build();
        // No price stub at all, deliberately: with nothing to look up there is no provider call
        // to make, which is the whole reason this holding is not an unpriced one.
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of(holding));

        AccountService.Valuation valuation = accountService.valuation(account);

        assertThat(valuation.anyPriced()).isTrue();
        assertThat(valuation.allPriced()).isTrue();
        // The flag must never outrun the figure: a snapshot taken on anyPriced() alone would
        // otherwise record cash-only for an account holding 500 EUR of assets.
        assertThat(valuation.liveEur()).isEqualByComparingTo("500");
        assertThat(valuation.investedEur()).isEqualByComparingTo("400"); // 500 - 100 of gain
    }

    @Test
    void aHoldingWithNoTickerAndNoProviderValueLeavesBothSidesAlone() {
        // Nothing can value this line — no ticker to look up, no figure from the provider — so it
        // must leave the account unpriced rather than contribute a cost with no value. Counting
        // one side without the other is the -85% disagreement, and claiming the account is priced
        // would let dailySnapshots engrave it.
        Account account = ownedAccount();
        AccountHolding holding = AccountHolding.builder()
            .quantity(new BigDecimal("3")).averageBuyIn(new BigDecimal("120")).build();
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of(holding));

        AccountService.Valuation valuation = accountService.valuation(account);

        assertThat(valuation.anyPriced()).isFalse();
        assertThat(valuation.allPriced()).isFalse();
        assertThat(valuation.liveEur()).isEqualByComparingTo("0");
        assertThat(valuation.investedEur()).isEqualByComparingTo("0");
    }

    @Test
    void aRecordedPriceStillValuesTheAccount_andIsReportedAsStale() {
        Account account = ownedAccount();
        AccountHolding holding = AccountHolding.builder()
            .ticker("AAPL").quantity(new BigDecimal("5"))
            .averageBuyIn(new BigDecimal("150")).build();
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of(holding));
        when(priceService.getQuotes(any())).thenReturn(Map.of(
            "AAPL", new PriceService.Quote(new BigDecimal("200"), LocalDate.now().minusDays(1), false)));

        AccountService.Valuation valuation = accountService.valuation(account);

        // Yesterday's price is a valuation, not a hole: the account is worth 1000 EUR, flagged.
        assertThat(valuation.liveEur()).isEqualByComparingTo("1000");
        assertThat(valuation.allPriced()).isTrue();
        assertThat(valuation.anyStale()).isTrue();
    }

    @Test
    void cryptoAccountsAreNeverPricedThroughTheStockRoute() {
        // A coin sharing its symbol with a listed equity (ATOM/Atomera, SUI, TIA...) must not be
        // valued at that company's share price. CryptoExchangeSyncService has always taken this
        // care on the write side; the read side used to disagree with it.
        Account account = Account.builder().id(5L).type(AccountType.CRYPTO).currency("EUR")
            .currentBalance(BigDecimal.ZERO).build();
        AccountHolding holding = AccountHolding.builder()
            .ticker("ATOM").quantity(new BigDecimal("10")).build();
        when(holdingRepository.findByAccount_Id(5L)).thenReturn(List.of(holding));
        when(priceService.getCryptoQuotes(any())).thenReturn(Map.of());

        accountService.liveBalanceEur(account);

        verify(priceService, never()).getQuotes(any());
    }

    @Test
    void updateHolding_clearsBrokerValuesThatNoLongerMatchTheUserEdit() {
        Account account = ownedAccount();
        AccountHolding holding = AccountHolding.builder()
            .ticker("ACME")
            .quantity(new BigDecimal("10"))
            .averageBuyIn(new BigDecimal("80"))
            .providerValueEur(new BigDecimal("1000"))
            .providerPnlEur(new BigDecimal("200"))
            .build();
        when(accountRepository.findByIdAndMemberId(1L, 7L)).thenReturn(Optional.of(account));
        when(holdingRepository.findByAccountIdAndTicker(1L, "ACME")).thenReturn(Optional.of(holding));
        when(holdingRepository.save(holding)).thenReturn(holding);

        accountService.updateHolding(
            1L, 7L, "ACME", new BigDecimal("8"), new BigDecimal("75")
        );

        assertThat(holding.getQuantity()).isEqualByComparingTo("8");
        assertThat(holding.getAverageBuyIn()).isEqualByComparingTo("75");
        assertThat(holding.getProviderValueEur()).isNull();
        assertThat(holding.getProviderPnlEur()).isNull();
    }

    // ─── signedLiveBalanceEur ─────────────────────────────────────────────────

    @Test
    void signedLiveBalanceEur_loan_returnsNegativeOutstanding() {
        Account loan = loanAccount();
        Debt debt = Debt.builder().build();
        when(debtRepository.findByAccountId(1L)).thenReturn(Optional.of(debt));
        when(loanAmortizationService.computeRemainingBalance(eq(debt), any(LocalDate.class)))
            .thenReturn(new BigDecimal("8500"));

        BigDecimal result = accountService.signedLiveBalanceEur(loan);

        // LOAN accounts are stored positive; the signed helper applies the liability sign.
        assertThat(result).isEqualByComparingTo("-8500");
    }

    @Test
    void signedLiveBalanceEur_checking_returnsBalanceUnchanged() {
        Account cash = Account.builder()
            .id(2L)
            .name("Checking")
            .type(AccountType.CHECKING)
            .currency("EUR")
            .currentBalance(new BigDecimal("2500"))
            .build();
        when(holdingRepository.findByAccount_Id(2L)).thenReturn(List.of());
        when(priceService.toEur(new BigDecimal("2500"), "EUR", null)).thenReturn(new BigDecimal("2500"));

        BigDecimal result = accountService.signedLiveBalanceEur(cash);

        assertThat(result).isEqualByComparingTo("2500");
    }

    /**
     * A property carries no holdings, so its balance comes straight back out of
     * {@code priceService.toEur}; nothing here exercises pricing.
     */
    private Account propertyAccount() {
        return Account.builder()
            .id(8L)
            .name("Résidence principale")
            .type(AccountType.REAL_ESTATE)
            .currency("EUR")
            .currentBalance(new BigDecimal("412000"))
            .build();
    }

    private RealEstateMetadataResponse propertyResponse(String propertyType, LocalDate lastValuedAt) {
        when(priceService.toEur(any(), eq("EUR"), any())).thenReturn(new BigDecimal("412000"));
        when(realEstateMetadataRepository.findByAccountId(8L)).thenReturn(Optional.of(
            RealEstateMetadata.builder()
                .purchasePrice(new BigDecimal("320000"))
                .propertyType(propertyType)
                .city("Bordeaux")
                .build()));
        when(propertyValuationRepository.findFirstByAccountIdOrderByValuedAtDesc(8L)).thenReturn(
            lastValuedAt == null
                ? Optional.empty()
                : Optional.of(PropertyValuation.builder().valuedAt(lastValuedAt).build()));

        return accountService.toResponse(propertyAccount()).realEstate();
    }

    @Test
    void toResponse_normalizesTheFreeTextPropertyTypeIntoAKind() {
        // property_type predates PropertyKind and is free text, so an old row may hold a French
        // label. Clients pick the card's glyph off the parsed value, never the raw string.
        RealEstateMetadataResponse realEstate = propertyResponse("maison", LocalDate.of(2026, 1, 10));

        assertThat(realEstate.propertyType()).isEqualTo("maison");
        assertThat(realEstate.propertyKind()).isEqualTo(PropertyKind.HOUSE);
    }

    @Test
    void toResponse_reportsWhenThePropertyWasLastValued() {
        RealEstateMetadataResponse realEstate = propertyResponse("HOUSE", LocalDate.of(2026, 1, 10));

        assertThat(realEstate.lastValuedAt()).isEqualTo(LocalDate.of(2026, 1, 10));
    }

    @Test
    void toResponse_leavesBothNullOnAPropertyNeitherDescribedNorValued() {
        // A property has no lastSyncedAt to fall back on -- the card simply renders no
        // freshness line, exactly as a manual account with no provider does.
        RealEstateMetadataResponse realEstate = propertyResponse("chalet", null);

        assertThat(realEstate.propertyKind()).isNull();
        assertThat(realEstate.lastValuedAt()).isNull();
    }
}
