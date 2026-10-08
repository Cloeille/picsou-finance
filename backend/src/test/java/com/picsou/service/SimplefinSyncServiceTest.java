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
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.mockito.quality.Strictness.LENIENT;

@ExtendWith(MockitoExtension.class)
class SimplefinSyncServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final String ACCESS = "https://user1234:secret@beta-bridge.simplefin.org/simplefin";
    private static final String SECRET_USERNAME = "usr98765";
    private static final String SECRET_PASSWORD = "s3cr3tPassw0rd";
    private static final String SECRET_ACCESS =
        "https://" + SECRET_USERNAME + ":" + SECRET_PASSWORD + "@beta-bridge.simplefin.org/simplefin";

    @Mock SimplefinPort simplefinPort;
    @Mock SimplefinConnectionRepository connectionRepository;
    @Mock AccountRepository accountRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock BankTransactionImportService transactionImportService;
    @Mock CryptoEncryption encryption;
    @Mock SimplefinStatusWriter statusWriter;

    private SimplefinSyncService service;
    private SimplefinConnection connection;

    @BeforeEach
    void setUp() {
        service = new SimplefinSyncService(
            simplefinPort, connectionRepository, accountRepository, familyMemberRepository,
            accountService, transactionImportService, encryption, statusWriter);
        connection = SimplefinConnection.builder().id(42L).accessUrl("ciphertext").status("CONNECTED").build();
    }

    // ---- connect ----------------------------------------------------------------------

    @Test
    void connect_newMember_storesTheEncryptedAccessUrlAsConnected() {
        FamilyMember member = member();
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(simplefinPort.claim("token")).thenReturn(ACCESS);
        when(encryption.encrypt(ACCESS)).thenReturn("ciphertext");
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        service.connect("token", MEMBER_ID);

        ArgumentCaptor<SimplefinConnection> saved = ArgumentCaptor.forClass(SimplefinConnection.class);
        verify(connectionRepository).save(saved.capture());
        assertThat(saved.getValue().getAccessUrl()).isEqualTo("ciphertext");
        assertThat(saved.getValue().getStatus()).isEqualTo("CONNECTED");
        assertThat(saved.getValue().getMember()).isSameAs(member);
    }

    @Test
    void connect_unknownMember_neverClaims() {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.connect("token", MEMBER_ID)).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(simplefinPort, connectionRepository, encryption);
    }

    @Test
    void connect_untrimmedToken_reachesThePortAsIs() {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(simplefinPort.claim("  token\n")).thenReturn(ACCESS);
        when(encryption.encrypt(ACCESS)).thenReturn("ciphertext");

        service.connect("  token\n", MEMBER_ID);

        verify(simplefinPort).claim("  token\n");
    }

    @Test
    void connect_claimFails_leavesTheStoredConnectionUntouched() {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(simplefinPort.claim("used-token")).thenThrow(new SyncException("token already used"));

        assertThatThrownBy(() -> service.connect("used-token", MEMBER_ID)).isInstanceOf(SyncException.class);

        verifyNoInteractions(connectionRepository, encryption);
    }

    @Test
    void connect_encryptionFails_doesNotTouchTheExistingConnection() {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(simplefinPort.claim("fresh")).thenReturn(ACCESS);
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.encrypt(ACCESS)).thenThrow(new IllegalStateException("key unavailable"));

        assertThatThrownBy(() -> service.connect("fresh", MEMBER_ID)).isInstanceOf(IllegalStateException.class);

        assertThat(connection.getAccessUrl()).isEqualTo("ciphertext");
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void connect_existingConnection_replacesTheAccessUrlInPlaceAndClearsError() {
        connection.setStatus("ERROR");
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(simplefinPort.claim("fresh")).thenReturn(ACCESS);
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.encrypt(ACCESS)).thenReturn("new-ciphertext");

        service.connect("fresh", MEMBER_ID);

        verify(connectionRepository).save(connection);
        assertThat(connection.getAccessUrl()).isEqualTo("new-ciphertext");
        assertThat(connection.getStatus()).isEqualTo("CONNECTED");
        assertThat(connection.getId()).isEqualTo(42L);
    }

    // ---- sync -------------------------------------------------------------------------

    @Test
    void sync_noConnection_throwsWithoutFetching() {
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sync(MEMBER_ID)).isInstanceOf(SyncException.class);

        verifyNoInteractions(simplefinPort);
    }

    @Test
    void sync_newAccount_isCreatedAsAProviderOwnedChecking() {
        givenBridgeReturns(account("sfin_CON-1_chk", "Chase", "Checking", "USD", "10.00"));

        service.sync(MEMBER_ID);

        assertThat(savedAccounts()).singleElement().satisfies(created -> {
            assertThat(created.getName()).isEqualTo("Chase — Checking");
            assertThat(created.getType()).isEqualTo(AccountType.CHECKING);
            assertThat(created.getProvider()).isEqualTo("SimpleFIN");
            assertThat(created.getCurrency()).isEqualTo("USD");
            assertThat(created.getExternalAccountId()).isEqualTo("sfin_CON-1_chk");
            assertThat(created.isManual()).isFalse();
        });
    }

    @Test
    void sync_newAccount_importsItsTransactions() {
        givenBridgeReturns(account("sfin_CON-1_chk", "Chase", "Checking", "USD", "10.00",
            new SimplefinTransaction("tx-1", LocalDate.of(2026, 1, 2), new BigDecimal("-4.50"), "Coffee")));

        service.sync(MEMBER_ID);

        Account created = savedAccounts().get(0);
        ArgumentCaptor<List<TransactionData>> imported = ArgumentCaptor.forClass(List.class);
        verify(transactionImportService).importProvided(eq(created), imported.capture());
        assertThat(imported.getValue()).singleElement().satisfies(tx -> {
            assertThat(tx.externalId()).isEqualTo("tx-1");
            assertThat(tx.amount()).isEqualByComparingTo("-4.50");
            assertThat(tx.currency()).isEqualTo("USD");
        });
    }

    @Test
    void sync_newAccount_writesABalanceSnapshot() {
        givenBridgeReturns(account("sfin_CON-1_chk", "Chase", "Checking", "USD", "10.00"));

        service.sync(MEMBER_ID);

        Account created = savedAccounts().get(0);
        verify(accountService).upsertSnapshotFromNative(eq(created), eq(new BigDecimal("10.00")), any());
    }

    @Test
    void sync_success_marksTheConnectionConnectedWithALastSync() {
        connection.setStatus("ERROR");
        givenBridgeReturns();

        service.sync(MEMBER_ID);

        assertThat(connection.getStatus()).isEqualTo("CONNECTED");
        assertThat(connection.getLastSyncedAt()).isNotNull();
        verify(connectionRepository).save(connection);
    }

    @Test
    void sync_softDeletedAccount_isNotResurrected() {
        givenBridgeReturns(account("sfin_CON-1_gone", "Chase", "Old", "USD", "1.00"));
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("sfin_CON-1_gone", MEMBER_ID))
            .thenReturn(true);

        List<AccountResponse> synced = service.sync(MEMBER_ID);

        assertThat(synced).isEmpty();
        verify(accountRepository, never()).save(any());
    }

    @Test
    void sync_existingAccount_keepsTheTypeTheMemberChoseAndUpdatesTheBalance() {
        givenBridgeReturns(account("sfin_CON-1_card", "Chase", "Sapphire Reserve", "USD", "-1146.69"));
        Account card = Account.builder().id(5L).type(AccountType.CREDIT_CARD)
            .currentBalance(new BigDecimal("-1000.00")).externalAccountId("sfin_CON-1_card").build();
        when(accountRepository.findByExternalAccountIdAndMemberId("sfin_CON-1_card", MEMBER_ID))
            .thenReturn(Optional.of(card));

        service.sync(MEMBER_ID);

        assertThat(card.getType()).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(card.getCurrentBalance()).isEqualByComparingTo("-1146.69");
    }

    @Test
    void sync_lowercaseCurrency_isStoredUpperCase() {
        givenBridgeReturns(account("sfin_C_a", "Chase", "Checking", "usd", "1.00"));

        service.sync(MEMBER_ID);

        assertThat(savedAccounts()).singleElement().satisfies(created -> assertThat(created.getCurrency()).isEqualTo("USD"));
    }

    @Test
    void sync_accountWithoutCurrency_isSkippedAndTheOthersStillImport() {
        givenBridgeReturns(
            account("sfin_C_none", "Chase", "No currency", null, "1.00"),
            account("sfin_C_ok", "Chase", "Checking", "USD", "2.00"));

        List<AccountResponse> synced = service.sync(MEMBER_ID);

        assertThat(synced).hasSize(1);
        assertThat(savedAccounts()).extracting(Account::getExternalAccountId).containsExactly("sfin_C_ok");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1000000000000", "-1000000000000", "1e30", "1e999999999"})
    void sync_balanceOutsideTheLedger_skipsTheAccount(String balance) {
        givenBridgeReturns(account("sfin_C_big", "Chase", "Big", "USD", balance));

        assertThat(service.sync(MEMBER_ID)).isEmpty();

        verify(accountRepository, never()).save(any());
    }

    @Test
    void sync_balanceJustInsideTheLedger_isImported() {
        givenBridgeReturns(account("sfin_C_in", "Chase", "Inside", "USD", "999999999999.99999999"));

        assertThat(service.sync(MEMBER_ID)).hasSize(1);
    }

    @Test
    void sync_longSharedHistory_isClampedTo89DaysBackOnTheUtcDate() {
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("ciphertext")).thenReturn(ACCESS);
        when(transactionImportService.sharedHistoryStart()).thenReturn(LocalDate.of(2020, 1, 1));
        when(simplefinPort.fetchAccounts(eq(ACCESS), any())).thenThrow(new SyncException("stop"));
        LocalDate before = LocalDate.now(ZoneOffset.UTC);

        assertThatThrownBy(() -> service.sync(MEMBER_ID)).isInstanceOf(SyncException.class);

        LocalDate after = LocalDate.now(ZoneOffset.UTC);
        ArgumentCaptor<LocalDate> start = ArgumentCaptor.forClass(LocalDate.class);
        verify(simplefinPort).fetchAccounts(eq(ACCESS), start.capture());
        assertThat(start.getValue()).isBetween(before.minusDays(89), after.minusDays(89));
    }

    @Test
    void sync_bridgeRefusesAccess_marksErrorAndRethrows() {
        givenStoredConnection();
        when(simplefinPort.fetchAccounts(eq(ACCESS), any()))
            .thenThrow(new SyncException("SimpleFIN refused the stored access.", null, "SESSION_EXPIRED"));

        assertThatThrownBy(() -> service.sync(MEMBER_ID))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("refused");

        verify(statusWriter).markError(42L);
    }

    // ---- resyncReporting --------------------------------------------------------------

    @Test
    void resyncReporting_noConnection_isSkippedNotConnected() {
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        assertThat(service.resyncReporting(MEMBER_ID).status()).isEqualTo(SourceSyncResult.Status.SKIPPED_NOT_CONNECTED);
    }

    @Test
    void resyncReporting_sessionExpired_needsReauthAndMarksError() {
        givenStoredConnection();
        when(simplefinPort.fetchAccounts(eq(ACCESS), any()))
            .thenThrow(new SyncException("refused", null, "SESSION_EXPIRED"));

        SourceSyncResult result = service.resyncReporting(MEMBER_ID);

        assertThat(result.source()).isEqualTo("simplefin");
        assertThat(result.status()).isEqualTo(SourceSyncResult.Status.NEEDS_REAUTH);
        verify(statusWriter).markError(42L);
    }

    @Test
    void resyncReporting_uncodedSyncException_isFailed() {
        givenStoredConnection();
        when(simplefinPort.fetchAccounts(eq(ACCESS), any())).thenThrow(new SyncException("Bridge unavailable"));

        SourceSyncResult result = service.resyncReporting(MEMBER_ID);

        assertThat(result.status()).isEqualTo(SourceSyncResult.Status.FAILED);
        verify(statusWriter).markError(42L);
    }

    @Test
    void resyncReporting_unexpectedRuntimeException_isFailedWithAGenericMessage() {
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenThrow(new IllegalStateException("db down"));

        SourceSyncResult result = service.resyncReporting(MEMBER_ID);

        assertThat(result.status()).isEqualTo(SourceSyncResult.Status.FAILED);
        assertThat(result.message()).isEqualTo("Unexpected sync error");
    }

    @Test
    void resyncReporting_failedFetch_neitherReportsNorLogsTheCredentials() {
        Logger logger = (Logger) LoggerFactory.getLogger(SimplefinSyncService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
            when(encryption.decrypt("ciphertext")).thenReturn(SECRET_ACCESS);
            when(transactionImportService.sharedHistoryStart()).thenReturn(LocalDate.now().minusDays(30));
            when(simplefinPort.fetchAccounts(eq(SECRET_ACCESS), any())).thenThrow(new SyncException("Bridge refused."));

            SourceSyncResult result = service.resyncReporting(MEMBER_ID);

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

    // ---- connection status ------------------------------------------------------------

    @Test
    void getConnectionStatus_storedAccess_exposesOnlyTheMaskedToken() {
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("ciphertext")).thenReturn(SECRET_ACCESS);

        SimplefinConnectionStatusResponse status = service.getConnectionStatus(MEMBER_ID);

        assertThat(status.maskedToken()).isEqualTo("••••8765");
        assertThat(new ObjectMapper().valueToTree(status).toString())
            .doesNotContain(SECRET_PASSWORD).doesNotContain("ciphertext").doesNotContain("https://");
    }

    @Test
    void getConnectionStatus_undecryptableAccess_showsErrorAndAFixedMask() {
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("ciphertext")).thenThrow(new IllegalStateException("bad key for ciphertext"));

        SimplefinConnectionStatusResponse status = service.getConnectionStatus(MEMBER_ID);

        assertThat(status.status()).isEqualTo("ERROR");
        assertThat(status.maskedToken()).isEqualTo("••••");
        assertThat(status.toString()).doesNotContain("ciphertext");
    }

    @ParameterizedTest
    @CsvSource({
        "https://user1234:secret@beta-bridge.simplefin.org/simplefin, ••••1234",
        "https://abcd:secret@beta-bridge.simplefin.org/simplefin, ••••",
        "https://beta-bridge.simplefin.org/simplefin, ••••",
        "not a url, ••••"
    })
    void mask_accessUrl_keepsOnlyTheLastFourOfTheUsername(String accessUrl, String expected) {
        assertThat(SimplefinSyncService.mask(accessUrl)).isEqualTo(expected);
    }

    // ---- pure helpers -----------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "2026-03-31, 2026-01-01",
        "2026-01-01, 2025-10-04",
        "2028-02-29, 2027-12-02",
        "2028-03-01, 2027-12-03"
    })
    void bridgeStart_acrossCalendarEdges_landsExactly89DaysBack(LocalDate today, LocalDate expected) {
        assertThat(SimplefinSyncService.bridgeStart(LocalDate.of(2020, 1, 1), today)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {90, 36_500})
    void bridgeStart_requestedBeyondTheLimit_isPulledIn(int daysBack) {
        LocalDate today = LocalDate.of(2026, 10, 5);

        assertThat(SimplefinSyncService.bridgeStart(today.minusDays(daysBack), today)).isEqualTo(today.minusDays(89));
    }

    @ParameterizedTest
    @ValueSource(ints = {89, 88, 0, -1})
    void bridgeStart_requestedInsideTheWindowOrInTheFuture_isLeftAlone(int daysBack) {
        LocalDate today = LocalDate.of(2026, 10, 5);

        assertThat(SimplefinSyncService.bridgeStart(today.minusDays(daysBack), today)).isEqualTo(today.minusDays(daysBack));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "usd", "Eur"})
    void isIsoCurrency_realCurrency_isAcceptedInAnyCase(String code) {
        assertThat(SimplefinSyncService.isIsoCurrency(code)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"US", "US$", "USDX", "ZZZ", "BTC", "XXX", "XAU", "XDR", "https://example.com/miles"})
    void isIsoCurrency_nonCodeOrCurrencyWithoutMinorUnit_isRefused(String code) {
        assertThat(SimplefinSyncService.isIsoCurrency(code)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "Chase, Checking, Chase — Checking",
        "chase, CHASE Total Checking, CHASE Total Checking",
        "' Chase ', ' Checking ', Chase — Checking",
        "' ', ' ', Account"
    })
    void accountName_bankAndName_areCombinedWithoutRepeatingTheBank(String bank, String name, String expected) {
        assertThat(SimplefinSyncService.accountName(bank, name)).isEqualTo(expected);
    }

    @Test
    void accountName_nullParts_fallBackToAccount() {
        assertThat(SimplefinSyncService.accountName(null, null)).isEqualTo("Account");
    }

    @Test
    void accountName_emojiEndingExactlyOnTheLimit_isKept() {
        String name = "x".repeat(98) + "\uD83D\uDE00";

        assertThat(SimplefinSyncService.accountName("", name)).isEqualTo(name).hasSize(100);
    }

    @Test
    void accountName_emojiStraddlingTheLimitAfterABankPrefix_isDroppedWhole() {
        // "B — " is 4 characters, so the emoji starts at index 99 and would end at 101.
        String result = SimplefinSyncService.accountName("B", "x".repeat(95) + "\uD83D\uDE00tail");

        assertThat(result).isEqualTo("B — " + "x".repeat(95));
    }

    // ---- reconnect and dedup round trips (real import service over in-memory repositories) ----

    @Test
    void reconnect_newTokenForTheSameBridgeConnection_reusesAccountsAndTransactions() {
        Ledger ledger = new Ledger();
        ledger.service.connect("token-1", MEMBER_ID);
        ledger.givenBridgeReturns(account("sfin_CON-1_chk", "Chase", "Checking", "USD", "10.00",
            new SimplefinTransaction("tx-1", ledger.daysAgo(10), new BigDecimal("-4.50"), "Coffee")));
        ledger.service.sync(MEMBER_ID);
        Account original = ledger.accounts.get(0);

        // The member disconnects, then pastes a brand-new token for the same Bridge connection.
        assertThat(ledger.service.deleteConnection(MEMBER_ID)).isTrue();
        ledger.service.connect("token-2", MEMBER_ID);
        ledger.givenBridgeReturns(account("sfin_CON-1_chk", "Chase", "Checking", "USD", "12.00",
            new SimplefinTransaction("tx-1", ledger.daysAgo(10), new BigDecimal("-4.50"), "Coffee"),
            new SimplefinTransaction("tx-2", ledger.daysAgo(2), new BigDecimal("-3.00"), "Tea")));
        ledger.service.sync(MEMBER_ID);

        assertThat(ledger.accounts).hasSize(1).first().isSameAs(original);
        assertThat(original.getCurrentBalance()).isEqualByComparingTo("12.00");
        assertThat(ledger.transactions).extracting(Transaction::getExternalTransactionId)
            .containsExactlyInAnyOrder("tx-1", "tx-2");
    }

    @Test
    void reconnect_newBridgeConnectionId_createsANewAccount() {
        Ledger ledger = new Ledger();
        ledger.sync(account("sfin_CON-1_chk", "Chase", "Checking", "USD", "10.00"));

        ledger.sync(account("sfin_CON-2_chk", "Chase", "Checking", "USD", "10.00"));

        assertThat(ledger.accounts).extracting(Account::getExternalAccountId)
            .containsExactly("sfin_CON-1_chk", "sfin_CON-2_chk");
    }

    @Test
    void sync_sameAccountIdTwiceInOneResponse_foldsIntoOneAccountWithTheLastBalance() {
        Ledger ledger = new Ledger();

        ledger.sync(
            account("sfin_C_chk", "Chase", "Checking", "USD", "1.00"),
            account("sfin_C_chk", "Chase", "Checking", "USD", "2.00"));

        assertThat(ledger.accounts).singleElement()
            .satisfies(folded -> assertThat(folded.getCurrentBalance()).isEqualByComparingTo("2.00"));
    }

    @Test
    void sync_sameTransactionIdRepeatedForOneAccount_isStoredOnce() {
        Ledger ledger = new Ledger();
        SimplefinTransaction coffee = new SimplefinTransaction("tx-1", ledger.daysAgo(5), new BigDecimal("-1.00"), "Coffee");

        ledger.sync(
            account("sfin_C_chk", "Chase", "Checking", "USD", "1.00", coffee),
            account("sfin_C_chk", "Chase", "Checking", "USD", "2.00", coffee));

        assertThat(ledger.transactions).hasSize(1);
    }

    @Test
    void sync_sameTransactionIdOnTwoAccounts_isStoredOnBoth() {
        Ledger ledger = new Ledger();

        ledger.sync(
            account("sfin_C_chk", "Chase", "Checking", "USD", "1.00",
                new SimplefinTransaction("shared", ledger.daysAgo(3), new BigDecimal("-5.00"), "Transfer out")),
            account("sfin_C_sav", "Chase", "Savings", "USD", "5.00",
                new SimplefinTransaction("shared", ledger.daysAgo(3), new BigDecimal("5.00"), "Transfer in")));

        assertThat(ledger.transactions).extracting(t -> t.getAccount().getExternalAccountId())
            .containsExactlyInAnyOrder("sfin_C_chk", "sfin_C_sav");
    }

    @Test
    void sync_secondRunOfTheSamePayload_insertsNothingNew() {
        Ledger ledger = new Ledger();
        SimplefinAccount payload = account("sfin_C_chk", "Chase", "Checking", "USD", "1.00",
            new SimplefinTransaction("tx-1", ledger.daysAgo(3), new BigDecimal("-5.00"), "Coffee"));

        ledger.sync(payload);
        ledger.sync(payload);

        assertThat(ledger.accounts).hasSize(1);
        assertThat(ledger.transactions).hasSize(1);
    }

    // ---- helpers ----------------------------------------------------------------------

    private static FamilyMember member() {
        FamilyMember member = new FamilyMember();
        member.setId(MEMBER_ID);
        return member;
    }

    private static SimplefinAccount account(
        String externalId, String bank, String name, String currency, String balance, SimplefinTransaction... txs
    ) {
        return new SimplefinAccount(externalId, bank, name, currency, new BigDecimal(balance), List.of(txs));
    }

    /** The member's connection, its decryptable access and a shared history start well inside the window. */
    private void givenStoredConnection() {
        lenient().when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        lenient().when(encryption.decrypt("ciphertext")).thenReturn(ACCESS);
        lenient().when(transactionImportService.sharedHistoryStart()).thenReturn(LocalDate.now().minusDays(30));
        lenient().when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        lenient().when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(accountService.toResponse(any())).thenReturn(mock(AccountResponse.class));
    }

    private void givenBridgeReturns(SimplefinAccount... accounts) {
        givenStoredConnection();
        when(simplefinPort.fetchAccounts(eq(ACCESS), any()))
            .thenReturn(new SimplefinAccountSet(List.of(), List.of(accounts)));
    }

    private List<Account> savedAccounts() {
        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, atLeast(0)).save(saved.capture());
        return saved.getAllValues();
    }

    /** In-memory stand-ins for the repositories, behind the real import service. */
    private static final class Ledger {
        final List<Account> accounts = new ArrayList<>();
        final List<Transaction> transactions = new ArrayList<>();
        final AtomicReference<SimplefinConnection> connection = new AtomicReference<>();
        final SimplefinPort port = lenientMock(SimplefinPort.class);
        final SimplefinSyncService service;
        private long nextId = 100;

        Ledger() {
            SimplefinConnectionRepository connections = lenientMock(SimplefinConnectionRepository.class);
            AccountRepository accountRepository = lenientMock(AccountRepository.class);
            FamilyMemberRepository members = lenientMock(FamilyMemberRepository.class);
            AccountService accountService = lenientMock(AccountService.class);
            TransactionRepository transactionRepository = lenientMock(TransactionRepository.class);
            CryptoEncryption encryption = lenientMock(CryptoEncryption.class);

            when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
            when(encryption.encrypt(any())).thenReturn("ciphertext");
            when(encryption.decrypt("ciphertext")).thenReturn(ACCESS);
            when(port.claim(any())).thenReturn(ACCESS);

            when(connections.findByMemberId(MEMBER_ID)).thenAnswer(i -> Optional.ofNullable(connection.get()));
            when(connections.save(any())).thenAnswer(i -> {
                SimplefinConnection saved = i.getArgument(0);
                if (saved.getId() == null) saved.setId(nextId++);
                connection.set(saved);
                return saved;
            });
            doAnswer(i -> {
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
            when(accountService.toResponse(any())).thenReturn(lenientMock(AccountResponse.class));

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
                lenientMock(BankConnectorPort.class), transactionRepository, 90);
            service = new SimplefinSyncService(
                port, connections, accountRepository, members, accountService, importer, encryption,
                lenientMock(SimplefinStatusWriter.class));
        }

        private static <T> T lenientMock(Class<T> type) {
            return mock(type, withSettings().strictness(LENIENT));
        }

        LocalDate daysAgo(int days) {
            return LocalDate.now(ZoneOffset.UTC).minusDays(days);
        }

        void givenBridgeReturns(SimplefinAccount... accounts) {
            when(port.fetchAccounts(any(), any())).thenReturn(new SimplefinAccountSet(List.of(), List.of(accounts)));
        }

        void sync(SimplefinAccount... accounts) {
            if (connection.get() == null) service.connect("token", MEMBER_ID);
            givenBridgeReturns(accounts);
            service.sync(MEMBER_ID);
        }
    }
}
