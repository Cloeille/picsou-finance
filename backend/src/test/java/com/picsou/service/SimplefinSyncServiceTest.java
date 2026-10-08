package com.picsou.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.SimplefinConnectionStatusResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.SimplefinConnection;
import com.picsou.model.Transaction;
import com.picsou.port.BankConnectorPort;
import com.picsou.port.BankConnectorPort.TransactionData;
import com.picsou.port.SimplefinPort;
import com.picsou.port.SimplefinPort.SimplefinAccount;
import com.picsou.port.SimplefinPort.SimplefinAccountSet;
import com.picsou.port.SimplefinPort.SimplefinTransaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SimplefinConnectionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.sync.SourceSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimplefinSyncServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final String ACCESS = "https://user1234:secret@beta-bridge.simplefin.org/simplefin";

    @Mock SimplefinPort simplefinPort;
    @Mock SimplefinConnectionRepository connectionRepository;
    @Mock AccountRepository accountRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock BankTransactionImportService transactionImportService;
    @Mock CryptoEncryption encryption;
    @Mock SimplefinStatusWriter statusWriter;

    private SimplefinSyncService service;

    @BeforeEach
    void setUp() {
        service = new SimplefinSyncService(
            simplefinPort, connectionRepository, accountRepository, familyMemberRepository,
            accountService, transactionImportService, encryption, statusWriter);
    }

    @Test
    void connectStoresTheEncryptedAccessUrl() {
        when(simplefinPort.claim("token")).thenReturn(ACCESS);
        when(encryption.encrypt(ACCESS)).thenReturn("ciphertext");
        FamilyMember member = new FamilyMember();
        member.setId(MEMBER_ID);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        service.connect("token", MEMBER_ID);

        ArgumentCaptor<SimplefinConnection> saved = ArgumentCaptor.forClass(SimplefinConnection.class);
        verify(connectionRepository).save(saved.capture());
        assertThat(saved.getValue().getAccessUrl()).isEqualTo("ciphertext");
        assertThat(saved.getValue().getStatus()).isEqualTo("CONNECTED");
        assertThat(saved.getValue().getMember()).isSameAs(member);
    }

    @Test
    void syncCreatesAccountsSkipsNonIsoAndDoesNotResurrectDeletedOnes() {
        SimplefinConnection connection = connection();
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("ciphertext")).thenReturn(ACCESS);
        LocalDate start = LocalDate.now().minusDays(30);
        when(transactionImportService.sharedHistoryStart()).thenReturn(start);
        FamilyMember member = new FamilyMember();
        member.setId(MEMBER_ID);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(simplefinPort.fetchAccounts(ACCESS, start)).thenReturn(new SimplefinAccountSet(
            List.of(),
            List.of(
                account("sfin_CON-1_chk", "Chase", "Checking", "USD", "10.00",
                    new SimplefinTransaction("tx-1", LocalDate.of(2026, 1, 2), new BigDecimal("-4.50"), "Coffee")),
                account("sfin_CON-1_sav", "Chase", "Savings", "usd", "80.00"),
                account("sfin_CON-1_miles", "Chase", "Rewards", "https://example.com/miles", "12"),
                account("sfin_CON-1_gone", "Chase", "Old", "USD", "1.00"),
                account("sfin_CON-1_huge", "Chase", "Overflow", "USD", "1000000000000")
            )));
        when(accountRepository.findByExternalAccountIdAndMemberId("sfin_CON-1_chk", MEMBER_ID))
            .thenReturn(Optional.empty());
        when(accountRepository.findByExternalAccountIdAndMemberId("sfin_CON-1_sav", MEMBER_ID))
            .thenReturn(Optional.empty());
        when(accountRepository.findByExternalAccountIdAndMemberId("sfin_CON-1_gone", MEMBER_ID))
            .thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(any(), eq(MEMBER_ID)))
            .thenAnswer(invocation -> "sfin_CON-1_gone".equals(invocation.getArgument(0)));
        when(accountRepository.save(any())).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(saved.getExternalAccountId().endsWith("sav") ? 2L : 1L);
            return saved;
        });
        when(accountService.toResponse(any())).thenReturn(org.mockito.Mockito.mock(AccountResponse.class));

        List<AccountResponse> synced = service.sync(MEMBER_ID);

        assertThat(synced).hasSize(2);
        ArgumentCaptor<Account> accounts = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, org.mockito.Mockito.times(2)).save(accounts.capture());
        Account checking = accounts.getAllValues().get(0);
        assertThat(checking.getName()).isEqualTo("Chase — Checking");
        assertThat(checking.getType()).isEqualTo(AccountType.CHECKING);
        assertThat(checking.getProvider()).isEqualTo("SimpleFIN");
        assertThat(checking.getCurrency()).isEqualTo("USD");
        assertThat(checking.isManual()).isFalse();
        Account savings = accounts.getAllValues().get(1);
        assertThat(savings.getType()).isEqualTo(AccountType.CHECKING);
        assertThat(savings.getName()).isEqualTo("Chase — Savings");

        ArgumentCaptor<List<TransactionData>> imported = ArgumentCaptor.forClass(List.class);
        verify(transactionImportService).importProvided(eq(checking), imported.capture());
        assertThat(imported.getValue()).singleElement().satisfies(tx -> {
            assertThat(tx.externalId()).isEqualTo("tx-1");
            assertThat(tx.amount()).isEqualByComparingTo("-4.50");
        });
        verify(accountService).upsertSnapshotFromNative(eq(checking), eq(new BigDecimal("10.00")), any());
        verify(accountRepository, never()).save(org.mockito.ArgumentMatchers.argThat(
            account -> "sfin_CON-1_gone".equals(account.getExternalAccountId())));
        assertThat(connection.getStatus()).isEqualTo("CONNECTED");
        assertThat(connection.getLastSyncedAt()).isNotNull();
    }

    @Test
    void revokedAccessMarksTheConnectionInError() {
        SimplefinConnection connection = connection();
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("ciphertext")).thenReturn(ACCESS);
        when(transactionImportService.sharedHistoryStart()).thenReturn(LocalDate.of(2025, 10, 1));
        when(simplefinPort.fetchAccounts(any(), any()))
            .thenThrow(new SyncException("SimpleFIN refused the stored access. It may have been revoked."));

        assertThatThrownBy(() -> service.sync(MEMBER_ID))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("revoked");
        verify(statusWriter).markError(42L);
    }

    @Test
    void maskKeepsOnlyTheLastFourCharactersOfTheUsername() {
        assertThat(SimplefinSyncService.mask(ACCESS)).isEqualTo("••••1234");
        assertThat(SimplefinSyncService.accountName("Chase", "Checking")).isEqualTo("Chase — Checking");
        assertThat(SimplefinSyncService.accountName("", "x".repeat(99) + "\uD83D\uDE00")).isEqualTo("x".repeat(99));
        LocalDate today = LocalDate.of(2026, 10, 5);
        assertThat(SimplefinSyncService.bridgeStart(today.minusDays(90), today)).isEqualTo(today.minusDays(89));
        assertThat(SimplefinSyncService.bridgeStart(today.minusDays(30), today)).isEqualTo(today.minusDays(30));
        assertThat(SimplefinSyncService.isIsoCurrency("https://example.com/miles")).isFalse();
    }

    @Test
    void aResyncKeepsTheTypeTheMemberChose() {
        SimplefinConnection connection = connection();
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("ciphertext")).thenReturn(ACCESS);
        LocalDate start = LocalDate.now(ZoneOffset.UTC).minusDays(30);
        when(transactionImportService.sharedHistoryStart()).thenReturn(start);
        FamilyMember member = new FamilyMember();
        member.setId(MEMBER_ID);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(simplefinPort.fetchAccounts(ACCESS, start)).thenReturn(new SimplefinAccountSet(
            List.of(), List.of(account("sfin_CON-1_card", "Chase", "Sapphire Reserve", "USD", "-1146.69"))));
        Account card = new Account();
        card.setId(5L);
        card.setType(AccountType.CREDIT_CARD);
        card.setCurrentBalance(new BigDecimal("-1000.00"));
        card.setExternalAccountId("sfin_CON-1_card");
        when(accountRepository.findByExternalAccountIdAndMemberId("sfin_CON-1_card", MEMBER_ID))
            .thenReturn(Optional.of(card));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountService.toResponse(any())).thenReturn(org.mockito.Mockito.mock(AccountResponse.class));

        service.sync(MEMBER_ID);

        assertThat(card.getType()).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(card.getCurrentBalance()).isEqualByComparingTo("-1146.69");
    }

    @Test
    void theHistoryWindowIsClampedOnTheUtcDate() {
        SimplefinConnection connection = connection();
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("ciphertext")).thenReturn(ACCESS);
        when(transactionImportService.sharedHistoryStart()).thenReturn(LocalDate.of(2020, 1, 1));
        when(simplefinPort.fetchAccounts(any(), any())).thenThrow(new SyncException("stop"));

        assertThatThrownBy(() -> service.sync(MEMBER_ID)).isInstanceOf(SyncException.class);

        verify(simplefinPort).fetchAccounts(ACCESS, LocalDate.now(ZoneOffset.UTC).minusDays(89));
    }

    @Test
    void theDailyJobReportsInsteadOfThrowing() {
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());
        assertThat(service.resyncReporting(MEMBER_ID).status()).isEqualTo(SourceSyncResult.Status.SKIPPED_NOT_CONNECTED);

        SimplefinConnection connection = connection();
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("ciphertext")).thenReturn(ACCESS);
        when(transactionImportService.sharedHistoryStart()).thenReturn(LocalDate.of(2025, 10, 1));
        when(simplefinPort.fetchAccounts(any(), any())).thenThrow(new SyncException("revoked"));

        SourceSyncResult failed = service.resyncReporting(MEMBER_ID);

        assertThat(failed.source()).isEqualTo("simplefin");
        assertThat(failed.status()).isEqualTo(SourceSyncResult.Status.FAILED);
        verify(statusWriter).markError(42L);
    }

    private static SimplefinConnection connection() {
        SimplefinConnection connection = new SimplefinConnection();
        connection.setId(42L);
        connection.setAccessUrl("ciphertext");
        connection.setStatus("CONNECTED");
        return connection;
    }

    private static SimplefinAccount account(
        String externalId, String bank, String name, String currency, String balance, SimplefinTransaction... txs
    ) {
        return new SimplefinAccount(externalId, bank, name, currency, new BigDecimal(balance), List.of(txs));
    }

    // ------------------------------------------------------------------------------------
    // Data edge cases: history window, names, currency, balances, reconnect, duplicates.
    // These run the real BankTransactionImportService over in-memory repositories (see Ledger).
    // ------------------------------------------------------------------------------------

    @Test
    void bridgeStart_acrossAMonthEndLandsExactly89DaysBack() {
        LocalDate today = LocalDate.of(2026, 3, 31);

        LocalDate start = SimplefinSyncService.bridgeStart(LocalDate.of(2020, 1, 1), today);

        assertThat(start).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(ChronoUnit.DAYS.between(start, today)).isEqualTo(89);
    }

    @Test
    void bridgeStart_onTheLeapDayCountsFebruary29() {
        LocalDate today = LocalDate.of(2028, 2, 29);

        LocalDate start = SimplefinSyncService.bridgeStart(LocalDate.of(2020, 1, 1), today);

        assertThat(start).isEqualTo(LocalDate.of(2027, 12, 2));
        assertThat(ChronoUnit.DAYS.between(start, today)).isEqualTo(89);
    }

    @Test
    void bridgeStart_theDayAfterTheLeapDayStillSpans89Days() {
        LocalDate today = LocalDate.of(2028, 3, 1);

        LocalDate start = SimplefinSyncService.bridgeStart(LocalDate.of(2020, 1, 1), today);

        assertThat(start).isEqualTo(LocalDate.of(2027, 12, 3));
        assertThat(ChronoUnit.DAYS.between(start, today)).isEqualTo(89);
    }

    @Test
    void bridgeStart_acrossANewYearLandsInTheYearBefore() {
        LocalDate today = LocalDate.of(2026, 1, 1);

        assertThat(SimplefinSyncService.bridgeStart(LocalDate.of(2020, 1, 1), today))
            .isEqualTo(LocalDate.of(2025, 10, 4));
    }

    @Test
    void bridgeStart_exactly89DaysBackIsLeftAlone() {
        LocalDate today = LocalDate.of(2026, 10, 5);
        assertThat(SimplefinSyncService.bridgeStart(today.minusDays(89), today)).isEqualTo(today.minusDays(89));
    }

    @Test
    void bridgeStart_oneDayBeyondTheLimitIsPulledIn() {
        LocalDate today = LocalDate.of(2026, 10, 5);
        assertThat(SimplefinSyncService.bridgeStart(today.minusDays(90), today)).isEqualTo(today.minusDays(89));
    }

    @Test
    void bridgeStart_aSharedStartMuchEarlierIsPulledIn() {
        LocalDate today = LocalDate.of(2026, 10, 5);
        assertThat(SimplefinSyncService.bridgeStart(LocalDate.of(1900, 1, 1), today)).isEqualTo(today.minusDays(89));
    }

    @Test
    void bridgeStart_aSharedStartInsideTheWindowIsLeftAlone() {
        LocalDate today = LocalDate.of(2026, 10, 5);
        assertThat(SimplefinSyncService.bridgeStart(today.minusDays(88), today)).isEqualTo(today.minusDays(88));
        assertThat(SimplefinSyncService.bridgeStart(today, today)).isEqualTo(today);
    }

    @Test
    void bridgeStart_aSharedStartInTheFutureIsNotMovedBack() {
        LocalDate today = LocalDate.of(2026, 10, 5);
        assertThat(SimplefinSyncService.bridgeStart(today.plusDays(1), today)).isEqualTo(today.plusDays(1));
    }

    @Test
    void accountName_anEmojiThatEndsExactlyOnTheLimitIsKept() {
        String name = "x".repeat(98) + "\uD83D\uDE00";

        assertThat(SimplefinSyncService.accountName("", name)).isEqualTo(name).hasSize(100);
    }

    @Test
    void accountName_anEmojiStraddlingTheLimitAfterABankPrefixIsDroppedWhole() {
        // "B — " is 4 characters, so the emoji starts at index 99 and would end at 101.
        String result = SimplefinSyncService.accountName("B", "x".repeat(95) + "\uD83D\uDE00tail");

        assertThat(result).isEqualTo("B — " + "x".repeat(95)).hasSize(99);
    }

    @Test
    void accountName_aCombiningMarkPastTheLimitIsCutLeavingValidUtf16() {
        String result = SimplefinSyncService.accountName("", "x".repeat(99) + "e\u0301");

        assertThat(result).isEqualTo("x".repeat(99) + "e");
        assertThat(Character.isSurrogate(result.charAt(result.length() - 1))).isFalse();
    }

    @Test
    void accountName_aCombiningMarkInsideTheLimitIsKept() {
        String name = "x".repeat(98) + "e\u0301";

        assertThat(SimplefinSyncService.accountName("", name)).isEqualTo(name).hasSize(100);
    }

    @Test
    void accountName_aRunOfEmojiNeverEndsOnALoneSurrogate() {
        String result = SimplefinSyncService.accountName("Bank", "\uD83D\uDE00".repeat(100));

        // "Bank — " is 7 characters; 46 whole emoji (92) fit, a 47th would end at 101.
        assertThat(result).hasSize(99);
        assertThat(Character.isLowSurrogate(result.charAt(result.length() - 1))).isTrue();
        assertThat(result.substring(7).codePoints().allMatch(cp -> cp == 0x1F600)).isTrue();
    }

    @Test
    void accountName_aBankAlreadyInTheNameIsNotRepeatedWhateverTheCase() {
        assertThat(SimplefinSyncService.accountName("chase", "CHASE Total Checking")).isEqualTo("CHASE Total Checking");
    }

    @Test
    void accountName_blankOrMissingPartsFallBack() {
        assertThat(SimplefinSyncService.accountName(null, null)).isEqualTo("Account");
        assertThat(SimplefinSyncService.accountName("   ", "  ")).isEqualTo("Account");
        assertThat(SimplefinSyncService.accountName(" Chase ", " Checking ")).isEqualTo("Chase — Checking");
    }

    @Test
    void isIsoCurrency_acceptsAnyCaseAndRefusesNonCodes() {
        assertThat(SimplefinSyncService.isIsoCurrency("usd")).isTrue();
        assertThat(SimplefinSyncService.isIsoCurrency("Eur")).isTrue();
        assertThat(SimplefinSyncService.isIsoCurrency(null)).isFalse();
        assertThat(SimplefinSyncService.isIsoCurrency("")).isFalse();
        assertThat(SimplefinSyncService.isIsoCurrency("US")).isFalse();
        assertThat(SimplefinSyncService.isIsoCurrency("US$")).isFalse();
        assertThat(SimplefinSyncService.isIsoCurrency("USDX")).isFalse();
        assertThat(SimplefinSyncService.isIsoCurrency("ZZZ")).isFalse();
        assertThat(SimplefinSyncService.isIsoCurrency("BTC")).isFalse();
    }

    @Test
    void sync_aLowercaseCurrencyIsStoredUpperCase() {
        Ledger ledger = new Ledger();

        ledger.sync(account("sfin_C_a", "Chase", "Checking", "usd", "1.00"));

        assertThat(ledger.accounts).singleElement().satisfies(a -> assertThat(a.getCurrency()).isEqualTo("USD"));
    }

    @Test
    void sync_aMissingCurrencyIsSkippedAndTheOthersStillImport() {
        Ledger ledger = new Ledger();

        List<AccountResponse> synced = ledger.sync(
            account("sfin_C_none", "Chase", "No currency", null, "1.00"),
            account("sfin_C_ok", "Chase", "Checking", "USD", "2.00"));

        assertThat(synced).hasSize(1);
        assertThat(ledger.accounts).extracting(Account::getExternalAccountId).containsExactly("sfin_C_ok");
    }

    @Test
    void sync_negativeZeroBalanceIsStoredAsZero() {
        Ledger ledger = new Ledger();

        ledger.sync(account("sfin_C_a", "Chase", "Checking", "USD", "-0.00"));

        assertThat(ledger.accounts).singleElement()
            .satisfies(a -> assertThat(a.getCurrentBalance()).isEqualByComparingTo("0"));
    }

    @Test
    void sync_aBalanceJustInsideTheLedgerLimitIsKeptAndOneAtTheLimitIsSkipped() {
        Ledger ledger = new Ledger();

        ledger.sync(
            account("sfin_C_in", "Chase", "Inside", "USD", "999999999999.99999999"),
            account("sfin_C_at", "Chase", "At limit", "USD", "1000000000000"),
            account("sfin_C_neg", "Chase", "Negative limit", "USD", "-1000000000000"),
            account("sfin_C_sci", "Chase", "Scientific", "USD", "1e30"));

        assertThat(ledger.accounts).extracting(Account::getExternalAccountId).containsExactly("sfin_C_in");
    }

    @Test
    void sync_aBalanceWithAnAbsurdExponentIsSkippedWithoutBlowingUp() {
        Ledger ledger = new Ledger();

        ledger.sync(
            account("sfin_C_big", "Chase", "Big", "USD", "1e999999999"),
            account("sfin_C_ok", "Chase", "Checking", "USD", "1.00"));

        assertThat(ledger.accounts).extracting(Account::getExternalAccountId).containsExactly("sfin_C_ok");
    }

    @Test
    void sync_aBalanceWithMoreThanEightDecimalsIsPassedToTheColumnUnrounded() {
        Ledger ledger = new Ledger();

        ledger.sync(account("sfin_C_a", "Chase", "Checking", "USD", "0.123456789012"));

        assertThat(ledger.accounts).singleElement()
            .satisfies(a -> assertThat(a.getCurrentBalance().toPlainString()).isEqualTo("0.123456789012"));
    }

    @Test
    void connect_aSecondTokenForTheSameMemberReusesTheConnectionRow() {
        Ledger ledger = new Ledger();
        ledger.connect("token-1");
        SimplefinConnection first = ledger.connection.get();

        ledger.connect("token-2");

        assertThat(ledger.connection.get()).isSameAs(first);
        assertThat(ledger.connectionsSaved).isEqualTo(2);
    }

    @Test
    void reconnect_aNewSetupTokenForTheSameBridgeConnectionReusesAccountsAndTransactions() {
        Ledger ledger = new Ledger();
        ledger.connect("token-1");
        SimplefinConnection first = ledger.connection.get();
        SimplefinAccount firstPayload = account("sfin_CON-1_chk", "Chase", "Checking", "USD", "10.00",
            new SimplefinTransaction("tx-1", ledger.daysAgo(10), new BigDecimal("-4.50"), "Coffee"));
        ledger.givenBridgeReturns(firstPayload);
        ledger.service.sync(MEMBER_ID);
        Account original = ledger.accounts.get(0);

        // The member disconnects, then pastes a brand-new token for the same Bridge connection.
        assertThat(ledger.service.deleteConnection(MEMBER_ID)).isTrue();
        ledger.connect("token-2");
        assertThat(ledger.connection.get()).isNotSameAs(first);
        ledger.givenBridgeReturns(
            account("sfin_CON-1_chk", "Chase", "Checking", "USD", "12.00",
                new SimplefinTransaction("tx-1", ledger.daysAgo(10), new BigDecimal("-4.50"), "Coffee"),
                new SimplefinTransaction("tx-2", ledger.daysAgo(2), new BigDecimal("-3.00"), "Tea")));
        ledger.service.sync(MEMBER_ID);

        assertThat(ledger.accounts).hasSize(1).first().isSameAs(original);
        assertThat(original.getCurrentBalance()).isEqualByComparingTo("12.00");
        assertThat(ledger.transactions).extracting(Transaction::getExternalTransactionId)
            .containsExactlyInAnyOrder("tx-1", "tx-2");
    }

    @Test
    void reconnect_aNewBridgeConnectionIdIsANewAccountBecauseNothingLinksItToTheOldOne() {
        Ledger ledger = new Ledger();
        ledger.connect("token-1");
        ledger.givenBridgeReturns(account("sfin_CON-1_chk", "Chase", "Checking", "USD", "10.00"));
        ledger.service.sync(MEMBER_ID);

        ledger.givenBridgeReturns(account("sfin_CON-2_chk", "Chase", "Checking", "USD", "10.00"));
        ledger.service.sync(MEMBER_ID);

        assertThat(ledger.accounts).extracting(Account::getExternalAccountId)
            .containsExactly("sfin_CON-1_chk", "sfin_CON-2_chk");
    }

    @Test
    void sync_theSameAccountIdTwiceInOneResponseFoldsIntoOneAccountAndTheLastBalanceWins() {
        Ledger ledger = new Ledger();

        ledger.sync(
            account("sfin_C_chk", "Chase", "Checking", "USD", "1.00",
                new SimplefinTransaction("tx-1", ledger.daysAgo(5), new BigDecimal("-1.00"), "Coffee")),
            account("sfin_C_chk", "Chase", "Checking", "USD", "2.00",
                new SimplefinTransaction("tx-1", ledger.daysAgo(5), new BigDecimal("-1.00"), "Coffee"),
                new SimplefinTransaction("tx-2", ledger.daysAgo(4), new BigDecimal("-2.00"), "Tea")));

        assertThat(ledger.accounts).hasSize(1);
        assertThat(ledger.accounts.get(0).getCurrentBalance()).isEqualByComparingTo("2.00");
        assertThat(ledger.transactions).extracting(Transaction::getExternalTransactionId)
            .containsExactlyInAnyOrder("tx-1", "tx-2");
    }

    @Test
    void sync_theSameTransactionIdOnTwoAccountsIsStoredOnBoth() {
        Ledger ledger = new Ledger();

        ledger.sync(
            account("sfin_C_chk", "Chase", "Checking", "USD", "1.00",
                new SimplefinTransaction("shared", ledger.daysAgo(3), new BigDecimal("-5.00"), "Transfer out")),
            account("sfin_C_sav", "Chase", "Savings", "USD", "5.00",
                new SimplefinTransaction("shared", ledger.daysAgo(3), new BigDecimal("5.00"), "Transfer in")));

        assertThat(ledger.accounts).hasSize(2);
        assertThat(ledger.transactions).hasSize(2);
        assertThat(ledger.transactions).extracting(t -> t.getAccount().getExternalAccountId())
            .containsExactlyInAnyOrder("sfin_C_chk", "sfin_C_sav");
    }

    @Test
    void sync_aSecondRunOfTheSamePayloadInsertsNothingNew() {
        Ledger ledger = new Ledger();
        SimplefinAccount payload = account("sfin_C_chk", "Chase", "Checking", "USD", "1.00",
            new SimplefinTransaction("tx-1", ledger.daysAgo(3), new BigDecimal("-5.00"), "Coffee"));

        ledger.sync(payload);
        ledger.sync(payload);

        assertThat(ledger.accounts).hasSize(1);
        assertThat(ledger.transactions).hasSize(1);
    }

    /** In-memory stand-ins for the repositories, behind the real import service. */
    private static final class Ledger {
        final List<Account> accounts = new java.util.ArrayList<>();
        final List<Transaction> transactions = new java.util.ArrayList<>();
        final AtomicReference<SimplefinConnection> connection = new AtomicReference<>();
        int connectionsSaved;
        final SimplefinPort port = lenient(SimplefinPort.class);
        final SimplefinSyncService service;
        private long nextId = 100;

        Ledger() {
            SimplefinConnectionRepository connections = lenient(SimplefinConnectionRepository.class);
            AccountRepository accountRepository = lenient(AccountRepository.class);
            FamilyMemberRepository members = lenient(FamilyMemberRepository.class);
            AccountService accountService = lenient(AccountService.class);
            TransactionRepository transactionRepository = lenient(TransactionRepository.class);
            CryptoEncryption encryption = lenient(CryptoEncryption.class);

            FamilyMember member = new FamilyMember();
            member.setId(MEMBER_ID);
            when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member));
            when(encryption.encrypt(any())).thenReturn("ciphertext");
            when(encryption.decrypt("ciphertext")).thenReturn(ACCESS);
            when(port.claim(any())).thenReturn(ACCESS);

            when(connections.findByMemberId(MEMBER_ID)).thenAnswer(i -> Optional.ofNullable(connection.get()));
            when(connections.save(any())).thenAnswer(i -> {
                SimplefinConnection saved = i.getArgument(0);
                if (saved.getId() == null) saved.setId(nextId++);
                connection.set(saved);
                connectionsSaved++;
                return saved;
            });
            org.mockito.Mockito.doAnswer(i -> {
                connection.set(null);
                return null;
            }).when(connections).delete(any());

            when(accountRepository.findByExternalAccountIdAndMemberId(any(), eq(MEMBER_ID))).thenAnswer(i ->
                accounts.stream().filter(a -> i.getArgument(0).equals(a.getExternalAccountId())).findFirst());
            when(accountRepository.save(any())).thenAnswer(i -> {
                Account saved = i.getArgument(0);
                if (saved.getId() == null) saved.setId(nextId++);
                if (!accounts.contains(saved)) accounts.add(saved);
                return saved;
            });
            when(accountService.toResponse(any())).thenReturn(lenient(AccountResponse.class));

            when(transactionRepository.findLatestSyncedDateByAccountId(any())).thenAnswer(i ->
                transactions.stream().filter(t -> t.getAccount().getId().equals(i.getArgument(0)))
                    .map(Transaction::getDate).max(LocalDate::compareTo).orElse(null));
            when(transactionRepository.findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(any(), any()))
                .thenAnswer(i -> transactions.stream()
                    .filter(t -> t.getAccount().getId().equals(i.getArgument(0)))
                    .filter(t -> !t.getDate().isBefore(i.getArgument(1)))
                    .toList());
            when(transactionRepository.saveAll(any())).thenAnswer(i -> {
                List<Transaction> saved = i.getArgument(0);
                transactions.addAll(saved);
                return saved;
            });

            BankTransactionImportService importer = new BankTransactionImportService(
                lenient(BankConnectorPort.class), transactionRepository, 90);
            service = new SimplefinSyncService(
                port, connections, accountRepository, members, accountService, importer, encryption,
                lenient(SimplefinStatusWriter.class));
        }

        private static <T> T lenient(Class<T> type) {
            return mock(type, org.mockito.Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        }

        LocalDate daysAgo(int days) {
            return LocalDate.now(ZoneOffset.UTC).minusDays(days);
        }

        void connect(String token) {
            service.connect(token, MEMBER_ID);
        }

        void givenBridgeReturns(SimplefinAccount... accounts) {
            when(port.fetchAccounts(any(), any())).thenReturn(new SimplefinAccountSet(List.of(), List.of(accounts)));
        }

        List<AccountResponse> sync(SimplefinAccount... accounts) {
            if (connection.get() == null) connect("token");
            givenBridgeReturns(accounts);
            return service.sync(MEMBER_ID);
        }
    }

    // ------------------------------------------------------------------------------------
    // Secrets and failures at the service level (moved from SimplefinClientTest).
    // The adapter tests own URL validation; here the port is a mock that fails the way it would.
    // ------------------------------------------------------------------------------------

    private static final String SECRET_USERNAME = "usr98765";
    private static final String SECRET_PASSWORD = "s3cr3tPassw0rd";
    private static final String SECRET_ACCESS =
        "https://" + SECRET_USERNAME + ":" + SECRET_PASSWORD + "@beta-bridge.simplefin.org/simplefin";

    @Test
    void connect_claimRefused_persistsNothing() {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(new FamilyMember()));
        when(simplefinPort.claim("token")).thenThrow(new SyncException("Picsou only connects to SimpleFIN Bridge."));

        assertThatThrownBy(() -> service.connect("token", MEMBER_ID)).isInstanceOf(SyncException.class);

        verifyNoInteractions(connectionRepository, encryption);
    }

    @Test
    void connect_unknownMember_neverClaims() {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.connect("token", MEMBER_ID)).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(simplefinPort, connectionRepository, encryption);
    }

    @Test
    void connect_token_isHandedToThePortUntouched() {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(new FamilyMember()));
        when(simplefinPort.claim("  token\n")).thenReturn(ACCESS);
        when(encryption.encrypt(ACCESS)).thenReturn("ciphertext");
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        service.connect("  token\n", MEMBER_ID);

        verify(simplefinPort).claim("  token\n");
    }

    @Test
    void connect_blankToken_isLeftToThePortToRefuse() {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(new FamilyMember()));
        when(simplefinPort.claim("  ")).thenThrow(new SyncException("A SimpleFIN setup token is required."));

        assertThatThrownBy(() -> service.connect("  ", MEMBER_ID))
            .isInstanceOf(SyncException.class).hasMessageContaining("required");

        verifyNoInteractions(connectionRepository, encryption);
    }

    @Test
    void resyncReporting_failedFetch_logsAndReportsNoCredentials() {
        Logger logger = (Logger) LoggerFactory.getLogger(SimplefinSyncService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection()));
            when(encryption.decrypt("ciphertext")).thenReturn(SECRET_ACCESS);
            when(transactionImportService.sharedHistoryStart()).thenReturn(LocalDate.now().minusDays(30));
            when(simplefinPort.fetchAccounts(any(), any())).thenThrow(new SyncException("Bridge refused the request."));

            SourceSyncResult result = service.resyncReporting(MEMBER_ID);

            assertThat(result.status()).isEqualTo(SourceSyncResult.Status.FAILED);
            assertThat(result.message()).doesNotContain(SECRET_PASSWORD).doesNotContain(SECRET_USERNAME);
            assertThat(logs.list).isNotEmpty();
            for (ILoggingEvent event : logs.list) {
                String text = event.getFormattedMessage();
                if (event.getThrowableProxy() != null) text += "\n" + ThrowableProxyUtil.asString(event.getThrowableProxy());
                assertThat(text).doesNotContain(SECRET_PASSWORD).doesNotContain(SECRET_USERNAME);
            }
        } finally {
            logger.detachAppender(logs);
        }
    }

    @ParameterizedTest
    @CsvSource({"usr98765,••••8765", "abcd,••••", "ab,••••"})
    void getConnectionStatus_storedAccess_showsOnlyTheLastFourOfTheUsername(String username, String expectedMask) {
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection()));
        when(encryption.decrypt("ciphertext"))
            .thenReturn("https://" + username + ":" + SECRET_PASSWORD + "@beta-bridge.simplefin.org/simplefin");

        SimplefinConnectionStatusResponse status = service.getConnectionStatus(MEMBER_ID);

        assertThat(status.maskedToken()).isEqualTo(expectedMask);
        assertThat(new ObjectMapper().valueToTree(status).toString())
            .doesNotContain(SECRET_PASSWORD).doesNotContain("ciphertext").doesNotContain("https://");
    }

    @Test
    void getConnectionStatus_undecryptableAccess_showsErrorAndAFixedMask() {
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection()));
        when(encryption.decrypt("ciphertext")).thenThrow(new IllegalStateException("bad key for ciphertext"));

        SimplefinConnectionStatusResponse status = service.getConnectionStatus(MEMBER_ID);

        assertThat(status.status()).isEqualTo("ERROR");
        assertThat(status.maskedToken()).isEqualTo("••••");
        assertThat(status.toString()).doesNotContain("ciphertext");
    }
}
