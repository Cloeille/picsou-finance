package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.adapter.OpenFigiIsinConverter.TickerResult;
import com.picsou.config.CryptoEncryption;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.FamilyMember;
import com.picsou.model.TradeRepublicSession;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.port.TradeRepublicPort;
import com.picsou.port.TradeRepublicPort.TrAccountData;
import com.picsou.port.TradeRepublicPort.TrPosition;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TradeRepublicSessionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeRepublicSyncServiceTest {

    @Mock TradeRepublicPort trPort;
    @Mock TradeRepublicSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock OpenFigiIsinConverter isinConverter;
    @Mock SecurityIdentityService identityService;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock CategorizationService categorizationService;
    @Mock CategoryRepository categoryRepository;

    @InjectMocks TradeRepublicSyncService service;

    /**
     * When two ISINs resolve to the same ticker, the saved holding's averageBuyIn
     * must be the VWAP -- not whichever position HashMap iteration happens to yield first.
     *
     * Scenario: ISIN_A (qty=2, avg=10) and ISIN_B (qty=3, avg=20) both resolve to "RKLB".
     * Expected merged holding: quantity=5, averageBuyIn = (2*10 + 3*20)/5 = 16,
     * provider value = 2*100 + 3*110 = 530.
     */
    @Test
    void sync_mergesDuplicateTickersWithVwap() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");

        TrPosition pos1 = new TrPosition("IE00ISIN_A", bd("2"), bd("10"), bd("100"));
        TrPosition pos2 = new TrPosition("IE00ISIN_B", bd("3"), bd("20"), bd("110"));
        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("530"), List.of(pos1, pos2));
        when(trPort.fetchAccounts("plain-session")).thenReturn(List.of(accountData));

        when(isinConverter.resolve("IE00ISIN_A")).thenReturn(new TickerResult("RKLB", "Rocket Lab"));
        when(isinConverter.resolve("IE00ISIN_B")).thenReturn(new TickerResult("RKLB", "Rocket Lab"));

        when(accountRepository.findByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("530")));

        service.sync(memberId);

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());

        AccountHolding saved = captor.getValue();
        assertThat(saved.getTicker()).isEqualTo("RKLB");
        assertThat(saved.getQuantity()).isEqualByComparingTo("5");
        // VWAP: (2*10 + 3*20) / 5 = 16  -- scale-8 representation 16.00000000
        assertThat(saved.getAverageBuyIn()).isEqualByComparingTo("16.00000000");
        assertThat(saved.getProviderValueEur()).isEqualByComparingTo("530");
    }

    @Test
    void sync_storesTheBrokerPositionValueInEur() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");

        TrPosition unpriceable = new TrPosition("IE000BI8OT95", bd("10"), bd("80"), bd("84"));
        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("840"), List.of(unpriceable));
        when(trPort.fetchAccounts("plain-session")).thenReturn(List.of(accountData));
        when(isinConverter.resolve("IE000BI8OT95"))
            .thenReturn(new TickerResult("MWRDF", "Amundi Core MSCI World"));

        when(accountRepository.findByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("840")));

        service.sync(memberId);

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());

        AccountHolding saved = captor.getValue();
        assertThat(saved.getQuoteCurrency()).isEqualTo("EUR");
        assertThat(saved.getProviderValueEur()).isEqualByComparingTo("840"); // 10 × 84
    }

    @Test
    void sync_fallsBackToAverageBuyIn_whenTradeRepublicHasNoLivePrice() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");

        TrPosition noPrice = new TrPosition("IE000BI8OT95", bd("10"), bd("80"), bd("0"));
        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("800"), List.of(noPrice));
        when(trPort.fetchAccounts("plain-session")).thenReturn(List.of(accountData));
        when(isinConverter.resolve("IE000BI8OT95"))
            .thenReturn(new TickerResult("MWRDF", "Amundi Core MSCI World"));

        when(accountRepository.findByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("800")));

        service.sync(memberId);

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository).save(captor.capture());

        assertThat(captor.getValue().getProviderValueEur()).isEqualByComparingTo("800"); // 10 × 80
    }

    @Test
    void sync_deletesOldHoldingsWhenPortfolioReturnsEmpty() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");

        TrAccountData accountData = new TrAccountData(
            "tr_cto", "TR Titres", AccountType.COMPTE_TITRES, bd("0"), List.of());
        when(trPort.fetchAccounts("plain-session")).thenReturn(List.of(accountData));

        Account existingAccount = Account.builder()
            .id(42L)
            .member(member)
            .name("TR Titres")
            .type(AccountType.COMPTE_TITRES)
            .provider("Trade Republic")
            .currency("EUR")
            .currentBalance(bd("1000"))
            .externalAccountId("tr_cto")
            .isManual(false)
            .build();
        when(accountRepository.findByExternalAccountIdAndMemberId("tr_cto", memberId))
            .thenReturn(Optional.of(existingAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> com.picsou.dto.AccountResponse.from(inv.getArgument(0), bd("0")));

        service.sync(memberId);

        verify(holdingRepository).deleteByAccountId(42L);
        verify(holdingRepository).flush();
        verify(holdingRepository, never()).save(any(AccountHolding.class));
    }

    // ─── CSV transaction import ────────────────────────────────────────────────

    private static final Long MEMBER = 7L;

    private Account trCash() {
        return Account.builder().id(1L).name("TR Cash").type(AccountType.CHECKING)
            .provider("Trade Republic").currency("EUR").build();
    }

    private Account trPea() {
        return Account.builder().id(2L).name("TR PEA").type(AccountType.PEA)
            .provider("Trade Republic").currency("EUR").build();
    }

    /** Seeded TRANSFER category used to tag the investment leg (kept out of cashflow). */
    private Category investTransferCategory() {
        return Category.builder().id(9L).name("Investissement").slug("investissement")
            .kind(CategoryKind.TRANSFER).build();
    }

    /** EXPENSE category used to tag the cash leg of a buy/sell (counts as spending). */
    private Category investPurchaseCategory() {
        return Category.builder().id(10L).name("Investissement").slug("investissement-titres")
            .kind(CategoryKind.EXPENSE).build();
    }

    /** Category map as a member who already has both categories would resolve. */
    private Map<String, Category> categoriesWithBoth(Category transfer, Category purchase) {
        return Map.of("investissement", transfer, "investissement-titres", purchase);
    }

    /** Builds one 19-column TR CSV data row with the fields the parser reads at their real indices. */
    private static String row(String date, String accountType, String category, String type,
                              String stock, String amount, String description, String externalId) {
        String[] cols = new String[19];
        Arrays.fill(cols, "");
        cols[1] = date;
        cols[2] = accountType;
        cols[3] = category;
        cols[4] = type;
        cols[6] = stock;
        // Quote the free-text/amount fields the way a real TR export does — the amount uses a
        // decimal comma, so it must be quoted to survive comma-delimited splitting.
        cols[10] = '"' + amount + '"';
        cols[17] = '"' + description + '"';
        cols[18] = externalId;
        return String.join(",", cols);
    }

    private static MockMultipartFile csv(String... dataRows) {
        String header = "datetime" + ",".repeat(18); // first line contains "datetime" → skipped as header
        String body = header + "\n" + String.join("\n", dataRows) + "\n";
        return new MockMultipartFile("file", "tr.csv", "text/csv", body.getBytes(StandardCharsets.UTF_8));
    }

    private void stubTrAccounts(Account... accounts) {
        when(accountRepository.findByIdAndMemberId(1L, MEMBER)).thenReturn(Optional.of(accounts[0]));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER)).thenReturn(List.of(accounts));
    }

    /**
     * A BUY on the PEA produces a mirrored double entry: cash leg WITHDRAWAL (−amount) on TR Cash,
     * investment leg DEPOSIT (+amount) on TR PEA. The cash leg is booked under the "Investissement"
     * EXPENSE category so cashflow counts the purchase as spending; only the investment leg is
     * tagged TRANSFER, which keeps the same movement from being counted a second time (as income).
     */
    @Test
    void importTransactions_buyOnPea_cashLegIsExpense_investmentLegIsTransfer() {
        Account cash = trCash();
        Account pea = trPea();
        Category transfer = investTransferCategory();
        Category purchase = investPurchaseCategory();
        stubTrAccounts(cash, pea);
        when(categorizationService.categoriesBySlug(MEMBER)).thenReturn(categoriesWithBoth(transfer, purchase));
        when(transactionRepository.existsByAccountIdAndExternalId(anyLong(), anyString())).thenReturn(false);

        // FR decimal comma exercised on purpose; BUY amount is negative in the TR export.
        var file = csv(row("2024-03-01", "PEA", "TRADING", "BUY", "S&P 500", "-59,31", "Buy S&P 500", "ext-1"));

        TradeRepublicSyncService.ImportResult result = service.importTransactionsCsv(1L, file, MEMBER);

        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.skipped()).isZero();

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(2)).save(captor.capture());
        List<Transaction> saved = captor.getAllValues();

        Transaction cashLeg = saved.stream().filter(t -> t.getAccount() == cash).findFirst().orElseThrow();
        assertThat(cashLeg.getAmount()).isEqualByComparingTo("-59.31");
        assertThat(cashLeg.getTxType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(cashLeg.getExternalId()).isEqualTo("ext-1_cash");
        // EXPENSE "Investissement" → cashflow counts the buy as spending.
        assertThat(cashLeg.getCategoryRef()).isEqualTo(purchase);

        Transaction invLeg = saved.stream().filter(t -> t.getAccount() == pea).findFirst().orElseThrow();
        assertThat(invLeg.getAmount()).isEqualByComparingTo("59.31");
        assertThat(invLeg.getTxType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(invLeg.getExternalId()).isEqualTo("ext-1_inv");
        // TRANSFER-kind → excluded from cashflow so the movement is not counted twice.
        assertThat(invLeg.getCategoryRef()).isEqualTo(transfer);
        // Never books BUY/SELL as tx_type, so HoldingComputeService ignores these rows.
        assertThat(invLeg.getTxType()).isNotIn(TransactionType.BUY, TransactionType.SELL);
    }

    /** A SELL mirrors the other way: cash DEPOSIT (+), investment WITHDRAWAL (−). */
    @Test
    void importTransactions_sellOnPea_mirrorsSigns() {
        Account cash = trCash();
        Account pea = trPea();
        stubTrAccounts(cash, pea);
        when(categorizationService.categoriesBySlug(MEMBER))
            .thenReturn(categoriesWithBoth(investTransferCategory(), investPurchaseCategory()));
        when(transactionRepository.existsByAccountIdAndExternalId(anyLong(), anyString())).thenReturn(false);

        var file = csv(row("2024-03-02", "PEA", "TRADING", "SELL", "Dell", "72.72", "Sell Dell", "ext-2"));

        service.importTransactionsCsv(1L, file, MEMBER);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(2)).save(captor.capture());
        Transaction cashLeg = captor.getAllValues().stream().filter(t -> t.getAccount() == cash).findFirst().orElseThrow();
        Transaction invLeg = captor.getAllValues().stream().filter(t -> t.getAccount() == pea).findFirst().orElseThrow();
        assertThat(cashLeg.getAmount()).isEqualByComparingTo("72.72");
        assertThat(cashLeg.getTxType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(invLeg.getAmount()).isEqualByComparingTo("-72.72");
        assertThat(invLeg.getTxType()).isEqualTo(TransactionType.WITHDRAWAL);
    }

    /** A plain CASH row (e.g. a card payment) is a single cash leg, left uncategorized so cashflow counts it. */
    @Test
    void importTransactions_cashRow_singleUncategorizedLeg() {
        Account cash = trCash();
        stubTrAccounts(cash);
        when(categorizationService.categoriesBySlug(MEMBER)).thenReturn(Map.of("investissement", investTransferCategory()));
        when(transactionRepository.existsByAccountIdAndExternalId(anyLong(), anyString())).thenReturn(false);

        var file = csv(row("2024-03-03", "DEFAULT", "CASH", "CARD_PAYMENT", "", "-12.50", "Coffee", "ext-3"));

        TradeRepublicSyncService.ImportResult result = service.importTransactionsCsv(1L, file, MEMBER);

        assertThat(result.inserted()).isEqualTo(1);
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(1)).save(captor.capture());
        Transaction leg = captor.getValue();
        assertThat(leg.getAccount()).isEqualTo(cash);
        assertThat(leg.getTxType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(leg.getCategoryRef()).isNull();
    }

    /** TRANSFER_IN / TRANSFER_OUT rows are ignored (already covered by the TRADING double-entry). */
    @Test
    void importTransactions_transferRows_ignored() {
        stubTrAccounts(trCash(), trPea());
        when(categorizationService.categoriesBySlug(MEMBER)).thenReturn(Map.of("investissement", investTransferCategory()));

        var file = csv(
            row("2024-03-04", "PEA", "TRANSFER", "TRANSFER_IN", "", "100.00", "Top-up", "ext-4"),
            row("2024-03-05", "PEA", "TRANSFER", "TRANSFER_OUT", "", "-100.00", "Top-up", "ext-5"));

        TradeRepublicSyncService.ImportResult result = service.importTransactionsCsv(1L, file, MEMBER);

        assertThat(result.inserted()).isZero();
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    /** Re-importing the same CSV inserts nothing and counts the row as skipped. */
    @Test
    void importTransactions_reimport_skipsExisting() {
        stubTrAccounts(trCash(), trPea());
        when(categorizationService.categoriesBySlug(MEMBER)).thenReturn(Map.of("investissement", investTransferCategory()));
        when(transactionRepository.existsByAccountIdAndExternalId(anyLong(), anyString())).thenReturn(true);

        var file = csv(row("2024-03-01", "PEA", "TRADING", "BUY", "S&P 500", "-59,31", "Buy", "ext-1"));

        TradeRepublicSyncService.ImportResult result = service.importTransactionsCsv(1L, file, MEMBER);

        assertThat(result.inserted()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    /** The triggering {id} must belong to the member — a spoofed id is rejected before any work. */
    @Test
    void importTransactions_unauthorizedAccount_throws() {
        when(accountRepository.findByIdAndMemberId(999L, MEMBER)).thenReturn(Optional.empty());

        var file = csv(row("2024-03-01", "PEA", "TRADING", "BUY", "S&P 500", "-59,31", "Buy", "ext-1"));

        assertThatThrownBy(() -> service.importTransactionsCsv(999L, file, MEMBER))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    /**
     * A member who predates the "investissement-titres" category (existing members are never
     * re-seeded) gets it created on demand on their first TRADING row, and the cash leg is booked
     * under it — so the purchase shows as an "Investissement" expense.
     */
    @Test
    void importTransactions_missingExpenseCategory_createsItOnDemand() {
        Account cash = trCash();
        Account pea = trPea();
        stubTrAccounts(cash, pea);
        // Map has the transfer category but NOT the purchase one → it must be created.
        when(categorizationService.categoriesBySlug(MEMBER))
            .thenReturn(Map.of("investissement", investTransferCategory()));
        when(transactionRepository.existsByAccountIdAndExternalId(anyLong(), anyString())).thenReturn(false);
        when(familyMemberRepository.getReferenceById(MEMBER))
            .thenReturn(FamilyMember.builder().id(MEMBER).build());
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> {
            Category c = inv.getArgument(0);
            c.setId(100L);
            return c;
        });

        var file = csv(row("2024-03-01", "PEA", "TRADING", "BUY", "S&P 500", "-59,31", "Buy", "ext-1"));

        service.importTransactionsCsv(1L, file, MEMBER);

        ArgumentCaptor<Category> catCaptor = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).save(catCaptor.capture());
        Category created = catCaptor.getValue();
        assertThat(created.getSlug()).isEqualTo("investissement-titres");
        assertThat(created.getKind()).isEqualTo(CategoryKind.EXPENSE);
        assertThat(created.getName()).isEqualTo("Investissement");

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(2)).save(txCaptor.capture());
        Transaction cashLeg = txCaptor.getAllValues().stream()
            .filter(t -> t.getAccount() == cash).findFirst().orElseThrow();
        assertThat(cashLeg.getCategoryRef()).isSameAs(created);
    }

    // --- Session lifecycle: refresh instead of dying at the 2h heuristic ---

    @Test
    void resync_attemptsRefreshWhenExpired() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .refreshToken("enc-refresh")
            .expiresAt(java.time.Instant.now().minusSeconds(3600)) // past the heuristic window
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");
        when(encryption.decrypt("enc-refresh")).thenReturn("plain-refresh");
        when(encryption.encrypt(any(String.class))).thenAnswer(inv -> "enc:" + inv.getArgument(0));

        when(trPort.fetchAccounts("plain-session"))
            .thenThrow(new com.picsou.exception.SyncException("SESSION_EXPIRED"));
        when(trPort.refreshSession("plain-refresh"))
            .thenReturn(new TradeRepublicPort.TrTokens("new-session", "new-refresh"));
        when(trPort.fetchAccounts("new-session")).thenReturn(List.of());

        service.resyncIfSessionActive(memberId);

        verify(trPort).refreshSession("plain-refresh");
        verify(trPort).fetchAccounts("new-session");
        verify(sessionRepository).save(storedSession);
        verify(sessionRepository, never()).delete(any(TradeRepublicSession.class));
        assertThat(storedSession.getSessionToken()).isEqualTo("enc:new-session");
        assertThat(storedSession.getRefreshToken()).isEqualTo("enc:new-refresh");
    }

    @Test
    void refreshFailure_transient_keepsSession() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .refreshToken("enc-refresh")
            .expiresAt(java.time.Instant.now().minusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");
        when(encryption.decrypt("enc-refresh")).thenReturn("plain-refresh");

        when(trPort.fetchAccounts("plain-session"))
            .thenThrow(new com.picsou.exception.SyncException("SESSION_EXPIRED"));
        when(trPort.refreshSession("plain-refresh"))
            .thenThrow(new com.picsou.exception.SyncException(
                "Trade Republic authentication service is unavailable. Please make sure tr-auth is running on port 8001."));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.sync(memberId))
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("unavailable");

        verify(sessionRepository, never()).delete(any(TradeRepublicSession.class));
    }

    @Test
    void refreshFailure_expired_clearsSession() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession storedSession = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .refreshToken("enc-refresh")
            .expiresAt(java.time.Instant.now().minusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(storedSession));
        when(encryption.decrypt("enc-session")).thenReturn("plain-session");
        when(encryption.decrypt("enc-refresh")).thenReturn("plain-refresh");

        when(trPort.fetchAccounts("plain-session"))
            .thenThrow(new com.picsou.exception.SyncException("SESSION_EXPIRED"));
        when(trPort.refreshSession("plain-refresh"))
            .thenThrow(new com.picsou.exception.SyncException("SESSION_EXPIRED"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.sync(memberId))
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("reconnect");

        verify(sessionRepository).delete(storedSession);
    }

    @Test
    void getSessionStatus_activeWhenRefreshTokenPresent() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        TradeRepublicSession expiredWithRefresh = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .refreshToken("enc-refresh")
            .expiresAt(java.time.Instant.now().minusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(expiredWithRefresh));
        assertThat(service.getSessionStatus(memberId).isActive()).isTrue();

        TradeRepublicSession expiredNoRefresh = TradeRepublicSession.builder()
            .member(member)
            .sessionToken("enc-session")
            .expiresAt(java.time.Instant.now().minusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(memberId)).thenReturn(Optional.of(expiredNoRefresh));
        assertThat(service.getSessionStatus(memberId).isActive()).isFalse();
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
