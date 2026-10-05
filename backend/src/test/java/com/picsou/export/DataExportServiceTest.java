package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.model.*;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.GoalContributorRepository;
import com.picsou.repository.GoalManualContributionRepository;
import com.picsou.repository.GoalMonthOverrideRepository;
import com.picsou.repository.GoalRepository;
import com.picsou.repository.RequisitionRepository;
import com.picsou.repository.SharedResourceRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.repository.WalletAddressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataExportServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private AccountHoldingRepository holdingRepository;
    @Mock private RequisitionRepository requisitionRepository;
    @Mock private DebtRepository debtRepository;
    @Mock private GoalRepository goalRepository;
    @Mock private GoalContributorRepository goalContributorRepository;
    @Mock private GoalManualContributionRepository goalManualContributionRepository;
    @Mock private GoalMonthOverrideRepository goalMonthOverrideRepository;
    @Mock private SharedResourceRepository sharedResourceRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private WalletAddressRepository walletAddressRepository;
    @Mock private BalanceSnapshotRepository balanceSnapshotRepository;

    private DataExportService service;
    private AppUser user;
    private FamilyMember member;

    private static final String SECRET_PASSWORD_HASH = "$2a$10$NeVeRsHoUlDaPpEaR";
    private static final String SECRET_TOTP = "JBSWY3DPEHPK3PXPSeCrEt";
    private static final String SECRET_TOKEN = "ACTIVATION_TOKEN_aBcDeF123";
    // Requisition.authLink: "a one-shot OAuth-style URL with a temporary token" (see the
    // exclusion rationale in BankConnectionsExporter's Javadoc) — deliberately not exported.
    private static final String SECRET_BANK_AUTH_LINK =
        "https://auth.enablebanking.com/start?token=SECRET_ONE_SHOT_AUTH_XyZ789";

    @BeforeEach
    void setUp() {
        member = FamilyMember.builder()
            .id(42L).displayName("Chloé Test").avatarColor("#abcdef").managed(false)
            .build();
        user = AppUser.builder()
            .id(7L).username("chloe").role(UserRole.MEMBER)
            .passwordHash(SECRET_PASSWORD_HASH)
            .activationToken(SECRET_TOKEN)
            .activated(true).acknowledgedWarning(true)
            .member(member)
            .build();

        // Wire ALL exporter beans, matching production Spring injection
        // (DataExportService.java receives List<EntityExporter> for every @Component
        // exporter). A test built with only a subset never exercises the exporters it
        // omits — see plan 005 for the incident this closes.
        ProfileExporter profile = new ProfileExporter();
        AccountsExporter accounts = new AccountsExporter(accountRepository);
        HoldingsExporter holdings = new HoldingsExporter(accountRepository, holdingRepository);
        BankConnectionsExporter bankConnections = new BankConnectionsExporter(requisitionRepository);
        DebtsExporter debts = new DebtsExporter(debtRepository);
        GoalsExporter goals = new GoalsExporter(
            goalRepository, goalContributorRepository, goalManualContributionRepository, goalMonthOverrideRepository);
        SharedResourcesExporter sharedResources = new SharedResourcesExporter(sharedResourceRepository);
        TransactionsExporter transactions = new TransactionsExporter(accountRepository, transactionRepository);
        WalletAddressesExporter walletAddresses = new WalletAddressesExporter(walletAddressRepository);
        BalanceSnapshotsExporter balanceSnapshots =
            new BalanceSnapshotsExporter(accountRepository, balanceSnapshotRepository);

        service = new DataExportService(List.of(
            profile, accounts, holdings, bankConnections, debts, goals,
            sharedResources, transactions, walletAddresses, balanceSnapshots
        ));
    }

    @Test
    void exportsZipWithExpectedEntries_andNoSecretLeak() throws Exception {
        Account a = Account.builder()
            .id(100L).member(member).name("Crypto wallet").type(AccountType.CRYPTO)
            .currency("EUR").currentBalance(new BigDecimal("12345.67"))
            .cashBalance(new BigDecimal("345.67"))
            .ticker("BTC").color("#fa0").isManual(true).provider("manual")
            .lastSyncedAt(Instant.parse("2026-01-01T00:00:00Z"))
            .build();
        AccountHolding h = AccountHolding.builder()
            .id(200L).account(a).ticker("BTC").name("Bitcoin")
            .quantity(new BigDecimal("0.5")).averageBuyIn(new BigDecimal("30000"))
            .currentPrice(new BigDecimal("65000"))
            .quoteCurrency("USD")
            .providerValueEur(new BigDecimal("30000"))
            .providerPnlEur(new BigDecimal("15000"))
            .build();
        Requisition connection = Requisition.builder()
            .id(300L).member(member)
            .requisitionId("req_enablebanking_abc123")
            .institutionId("bnpparibas").institutionName("BNP Paribas")
            .status(RequisitionStatus.LINKED)
            .authLink(SECRET_BANK_AUTH_LINK)
            .lastSyncedAt(Instant.parse("2026-01-02T00:00:00Z"))
            .build();
        Debt debt = Debt.builder()
            .id(400L).member(member).account(a)
            .borrowedAmount(new BigDecimal("150000"))
            .interestRate(new BigDecimal("0.0345"))
            .monthlyPayment(new BigDecimal("850.23"))
            .lenderName("Crédit Agricole")
            .startDate(LocalDate.of(2022, 1, 1)).endDate(LocalDate.of(2042, 1, 1))
            .insuranceMonthly(new BigDecimal("12.50")).fileFees(new BigDecimal("300"))
            .build();
        Goal goal = Goal.builder()
            .id(500L).member(member).name("Vacation fund")
            .targetAmount(new BigDecimal("5000")).deadline(LocalDate.of(2027, 6, 1))
            .accounts(List.of(a))
            .build();
        GoalContributor contributor = GoalContributor.builder()
            .id(new GoalContributorId(500L, 42L)).goal(goal).member(member)
            .build();
        GoalManualContribution manualContribution = new GoalManualContribution();
        manualContribution.setId(501L);
        manualContribution.setGoal(goal);
        manualContribution.setMember(member);
        manualContribution.setYearMonth("2026-01");
        manualContribution.setAmount(new BigDecimal("200"));
        GoalMonthOverride monthOverride = new GoalMonthOverride();
        monthOverride.setId(502L);
        monthOverride.setGoal(goal);
        monthOverride.setYearMonth("2026-02");
        monthOverride.setAmount(new BigDecimal("250"));
        SharedResource sharedResource = SharedResource.builder()
            .id(600L).ownerMember(member)
            .resourceType("ACCOUNT").resourceId(100L)
            .createdAt(Instant.parse("2026-01-03T00:00:00Z"))
            .build();
        Transaction tx = Transaction.builder()
            .id(700L).account(a)
            .date(LocalDate.of(2026, 1, 5)).description("Grocery store")
            .amount(new BigDecimal("-45.67")).type("EXPENSE").category("Food")
            .nativeCurrency("EUR").isManual(true)
            .txType(TransactionType.BUY).ticker("BTC")
            .quantity(new BigDecimal("0.001")).pricePerUnit(new BigDecimal("65000"))
            .fees(new BigDecimal("0.50"))
            .build();
        // No tripwire on WalletAddress.address: resolving the "is a wallet xpub secret?"
        // question from plan 005's escape hatch. address is polymorphic -- BitcoinWalletAdapter
        // documents accepting a plain address OR an xpub/zpub/descriptor, stored verbatim
        // (WalletSyncService passes wallet.getAddress() straight through, no normalization) --
        // but it is the exporter's one intentionally-exported payload field regardless of
        // which shape it holds, not a deliberately-excluded secret like Requisition.authLink.
        // Returning a user's own wallet-tracking credential in their own GDPR export is
        // correct (Art. 20 portability), not a leak, so it is out of the tripwire net.
        WalletAddress wallet = WalletAddress.builder()
            .id(800L).member(member).chain(Chain.BITCOIN)
            .address("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh").label("Cold storage")
            .lastSyncedAt(Instant.parse("2026-01-04T00:00:00Z"))
            .build();
        BalanceSnapshot snapshot = BalanceSnapshot.builder()
            .id(900L).account(a)
            .date(LocalDate.of(2026, 1, 1))
            .balance(new BigDecimal("12345.67")).investedAmount(new BigDecimal("10000"))
            .build();

        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(42L))
            .thenReturn(List.of(a));
        when(holdingRepository.findByAccount_Id(100L)).thenReturn(List.of(h));
        when(requisitionRepository.findAllByMemberId(42L)).thenReturn(List.of(connection));
        when(debtRepository.findAllByMemberId(42L)).thenReturn(List.of(debt));
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of(goal));
        when(goalContributorRepository.findByGoalId(500L)).thenReturn(List.of(contributor));
        when(goalManualContributionRepository.findByGoalId(500L)).thenReturn(List.of(manualContribution));
        when(goalMonthOverrideRepository.findByGoalId(500L)).thenReturn(List.of(monthOverride));
        when(sharedResourceRepository.findAllByOwnerMemberIdAndResourceType(42L, "ACCOUNT"))
            .thenReturn(List.of(sharedResource));
        when(transactionRepository.findByAccountIdOrderByDateDesc(100L)).thenReturn(List.of(tx));
        when(walletAddressRepository.findAllByMemberId(42L)).thenReturn(List.of(wallet));
        when(balanceSnapshotRepository.findByAccountIdOrderByDateAsc(100L)).thenReturn(List.of(snapshot));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // includeBalanceSnapshots=true so balance_snapshots.csv (gated) is in scope for the net too.
        service.export(user, new ExportContext(true), out);
        byte[] zipBytes = out.toByteArray();

        Map<String, byte[]> entries = readZip(zipBytes);
        assertThat(entries.keySet())
            .contains(
                "data.json", "README.txt",
                "profile.csv", "accounts.csv", "holdings.csv",
                "bank_connections.csv", "debts.csv", "goals.csv",
                "shared_resources.csv", "transactions.csv",
                "wallet_addresses.csv", "balance_snapshots.csv"
            );

        String json = new String(entries.get("data.json"));
        assertThat(json).contains("\"username\" : \"chloe\"");
        assertThat(json).contains("\"ticker\" : \"BTC\"");

        JsonNode parsed = new ObjectMapper().readTree(json);
        assertThat(parsed.get("schema_version").asText()).isEqualTo("1");
        assertThat(parsed.get("user_id").asLong()).isEqualTo(7L);
        assertThat(parsed.get("profile").get("username").asText()).isEqualTo("chloe");
        assertThat(parsed.get("accounts")).hasSize(1);
        assertThat(parsed.get("holdings")).hasSize(1);
        assertThat(parsed.get("accounts").get(0).get("cash_balance").decimalValue())
            .isEqualByComparingTo("345.67");
        assertThat(parsed.get("holdings").get(0).get("quote_currency").asText())
            .isEqualTo("USD");
        assertThat(parsed.get("holdings").get(0).get("provider_value_eur").decimalValue())
            .isEqualByComparingTo("30000");

        String accountsCsv = new String(entries.get("accounts.csv"));
        assertThat(accountsCsv).contains("Crypto wallet").contains("BTC").contains("12345.67");
        String holdingsCsv = new String(entries.get("holdings.csv"));
        assertThat(holdingsCsv).contains("USD").contains("30000").contains("15000");

        // Whole-archive byte-grep: NONE of the known secret tokens may appear anywhere
        // (data.json, csv files, README, ZIP metadata) — this is the GDPR safety net.
        // Covers all 10 exporters, matching production wiring (see setUp()).
        String archive = new String(zipBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(archive).doesNotContain(SECRET_PASSWORD_HASH);
        assertThat(archive).doesNotContain(SECRET_TOTP);
        assertThat(archive).doesNotContain(SECRET_TOKEN);
        assertThat(archive).doesNotContain(SECRET_BANK_AUTH_LINK);
        assertThat(archive).doesNotContain("password_hash");
        assertThat(archive).doesNotContain("activation_token");
    }

    @Test
    void exportsCleanlyEvenWithNoData() throws Exception {
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.export(user, ExportContext.defaults(), out);

        Map<String, byte[]> entries = readZip(out.toByteArray());
        assertThat(entries).containsKey("data.json").containsKey("accounts.csv").containsKey("holdings.csv");
        JsonNode parsed = new ObjectMapper().readTree(entries.get("data.json"));
        assertThat(parsed.get("accounts")).hasSize(0);
        assertThat(parsed.get("holdings")).hasSize(0);
    }

    /**
     * A mid-stream exporter failure cannot change the HTTP status (headers are gone with
     * the first ZIP byte) — the contract is: rethrow so the controller logs it, but first
     * stamp an {@code __EXPORT_FAILED__.txt} entry into the archive so the user can tell
     * a truncated download from a complete one.
     */
    @Test
    void exporterFailureMidStream_stampsFailureMarkerAndRethrows() throws Exception {
        EntityExporter failing = new EntityExporter() {
            @Override public String name() { return "failing"; }
            @Override public List<String> csvHeader() { return List.of("x"); }
            @Override public void writeCsv(AppUser u, ExportContext c, CsvWriter w) { }
            @Override public void writeJson(AppUser u, ExportContext c, JsonGenerator json) throws IOException {
                throw new IOException("simulated mid-stream failure");
            }
        };
        DataExportService failingService = new DataExportService(List.of(failing));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThatThrownBy(() -> failingService.export(user, ExportContext.defaults(), out))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("simulated mid-stream failure");

        Map<String, byte[]> entries = readZip(out.toByteArray());
        assertThat(entries).containsKey("__EXPORT_FAILED__.txt");
        assertThat(new String(entries.get("__EXPORT_FAILED__.txt"))).contains("incomplete");
    }

    private static Map<String, byte[]> readZip(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                zin.transferTo(buf);
                entries.put(e.getName(), buf.toByteArray());
            }
        }
        return entries;
    }
}
