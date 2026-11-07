package com.picsou.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.WiseConnectionStatusResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.WiseConnection;
import com.picsou.port.WisePort;
import com.picsou.port.WisePort.WiseJar;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.WiseConnectionRepository;
import com.picsou.service.sync.SourceSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WiseSyncServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final String TOKEN = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";

    @Mock WisePort wisePort;
    @Mock WiseConnectionRepository connectionRepository;
    @Mock AccountRepository accountRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock CryptoEncryption encryption;
    @Mock WiseStatusWriter statusWriter;

    private WiseSyncService service;
    private WiseConnection connection;
    private FamilyMember member;

    @BeforeEach
    void setUp() {
        service = new WiseSyncService(wisePort, connectionRepository, accountRepository, familyMemberRepository,
            accountService, encryption, statusWriter);
        connection = WiseConnection.builder().id(42L).apiToken("ciphertext").status("CONNECTED").build();
        member = new FamilyMember();
        member.setId(MEMBER_ID);
        lenient().when(accountRepository.save(any(Account.class))).thenAnswer(call -> call.getArgument(0));
        lenient().when(accountService.toResponse(any(Account.class))).thenReturn(mock(AccountResponse.class));
    }

    // ---- connect ----------------------------------------------------------------------

    @Test
    void connect_workingToken_storesItEncryptedAndImportsTheJars() {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(wisePort.fetchJars(TOKEN)).thenReturn(List.of(jar(102, "Coup dur", "EUR", "2002.08")));
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());
        when(encryption.encrypt(TOKEN)).thenReturn("ciphertext");

        service.connect(" " + TOKEN + "\n", MEMBER_ID);

        ArgumentCaptor<WiseConnection> saved = ArgumentCaptor.forClass(WiseConnection.class);
        verify(connectionRepository).save(saved.capture());
        assertThat(saved.getValue().getApiToken()).isEqualTo("ciphertext");
        assertThat(saved.getValue().getStatus()).isEqualTo("CONNECTED");
        assertThat(saved.getValue().getLastSyncedAt()).isNotNull();
        assertThat(saved.getValue().getMember()).isSameAs(member);
        assertThat(savedAccounts()).extracting(Account::getName).containsExactly("Coup dur");
    }

    @Test
    void connect_refusedToken_storesNothing() {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(wisePort.fetchJars(TOKEN)).thenThrow(new SyncException("Wise refused the API token.", null, "SESSION_EXPIRED"));

        assertThatThrownBy(() -> service.connect(TOKEN, MEMBER_ID)).isInstanceOf(SyncException.class);

        verifyNoInteractions(connectionRepository, encryption, accountRepository);
    }

    @Test
    void connect_unknownMember_neverCallsWise() {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.connect(TOKEN, MEMBER_ID)).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(wisePort, connectionRepository, encryption);
    }

    @Test
    void connect_existingConnection_replacesTheTokenInPlaceAndClearsError() {
        connection.setStatus("ERROR");
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(wisePort.fetchJars(TOKEN)).thenReturn(List.of());
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.encrypt(TOKEN)).thenReturn("new-ciphertext");

        service.connect(TOKEN, MEMBER_ID);

        verify(connectionRepository).save(connection);
        assertThat(connection.getApiToken()).isEqualTo("new-ciphertext");
        assertThat(connection.getStatus()).isEqualTo("CONNECTED");
        assertThat(connection.getId()).isEqualTo(42L);
    }

    // ---- sync -------------------------------------------------------------------------

    @Test
    void sync_newJar_isCreatedAsAProviderOwnedSavingsAccount() {
        givenWiseReturns(jar(102, "Coup dur", "EUR", "2002.08"));

        service.sync(MEMBER_ID);

        assertThat(savedAccounts()).singleElement().satisfies(created -> {
            assertThat(created.getName()).isEqualTo("Coup dur");
            assertThat(created.getType()).isEqualTo(AccountType.SAVINGS);
            assertThat(created.getProvider()).isEqualTo("Wise Jars");
            assertThat(created.getCurrency()).isEqualTo("EUR");
            assertThat(created.getCurrentBalance()).isEqualByComparingTo("2002.08");
            assertThat(created.getExternalAccountId()).isEqualTo("wise_jar_102");
            assertThat(created.isManual()).isFalse();
        });
        verify(accountService).upsertSnapshotFromNative(any(Account.class), eq(new BigDecimal("2002.08")),
            eq(LocalDate.now()));
    }

    @Test
    void sync_knownJar_updatesTheBalanceAndKeepsTheMembersNameAndType() {
        Account existing = Account.builder().id(9L).member(member).name("Fonds d'urgence")
            .type(AccountType.LIVRET_A).currency("EUR").currentBalance(new BigDecimal("10"))
            .externalAccountId("wise_jar_102").provider("Wise Jars").build();
        when(accountRepository.findByExternalAccountIdAndMemberId("wise_jar_102", MEMBER_ID))
            .thenReturn(Optional.of(existing));
        givenWiseReturns(jar(102, "Coup dur", "EUR", "2500.00"));

        service.sync(MEMBER_ID);

        assertThat(existing.getCurrentBalance()).isEqualByComparingTo("2500.00");
        assertThat(existing.getName()).isEqualTo("Fonds d'urgence");
        assertThat(existing.getType()).isEqualTo(AccountType.LIVRET_A);
        assertThat(existing.getLastSyncedAt()).isNotNull();
    }

    @Test
    void sync_jarTheMemberDeleted_isNotResurrected() {
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("wise_jar_102", MEMBER_ID))
            .thenReturn(true);
        givenWiseReturns(jar(102, "Coup dur", "EUR", "2002.08"));

        assertThat(service.sync(MEMBER_ID)).isEmpty();

        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void sync_currencyWithoutMinorUnit_isSkippedAndTheOthersImport() {
        givenWiseReturns(jar(1, "Gold", "XAU", "1"), jar(2, "Voyage", "EUR", "1.11"));

        service.sync(MEMBER_ID);

        assertThat(savedAccounts()).extracting(Account::getExternalAccountId).containsExactly("wise_jar_2");
    }

    @Test
    void sync_noConnection_throwsWithoutCallingWise() {
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sync(MEMBER_ID)).isInstanceOf(SyncException.class);

        verifyNoInteractions(wisePort);
    }

    @Test
    void sync_tokenRefused_marksTheConnectionReauthRequiredAndRethrows() {
        givenStoredConnection();
        SyncException refused = new SyncException("Wise refused the API token.", null, "SESSION_EXPIRED");
        when(wisePort.fetchJars(TOKEN)).thenThrow(refused);

        assertThatThrownBy(() -> service.sync(MEMBER_ID)).isSameAs(refused);

        verify(statusWriter).markReauthRequired(42L);
        verify(statusWriter, never()).markError(any());
    }

    @Test
    void sync_otherWiseFailure_marksTheConnectionErrorAndRethrows() {
        givenStoredConnection();
        SyncException limited = new SyncException("Wise is limiting requests right now.", null, "RATE_LIMITED");
        when(wisePort.fetchJars(TOKEN)).thenThrow(limited);

        assertThatThrownBy(() -> service.sync(MEMBER_ID)).isSameAs(limited);

        verify(statusWriter).markError(42L);
        verify(statusWriter, never()).markReauthRequired(any());
    }

    @Test
    void sync_undecryptableToken_asksToReconnect() {
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("ciphertext")).thenThrow(new IllegalStateException("bad key"));

        assertThatThrownBy(() -> service.sync(MEMBER_ID))
            .isInstanceOf(SyncException.class).hasMessageContaining("Connect again");

        verify(statusWriter).markError(42L);
        verifyNoInteractions(wisePort);
    }

    // ---- resyncReporting --------------------------------------------------------------

    @Test
    void resyncReporting_refusedToken_needsReauth() {
        givenStoredConnection();
        when(wisePort.fetchJars(TOKEN)).thenThrow(new SyncException("refused", null, "SESSION_EXPIRED"));

        SourceSyncResult result = service.resyncReporting(MEMBER_ID);

        assertThat(result.source()).isEqualTo("wise");
        assertThat(result.status()).isEqualTo(SourceSyncResult.Status.NEEDS_REAUTH);
    }

    @Test
    void resyncReporting_noConnection_isSkipped() {
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        assertThat(service.resyncReporting(MEMBER_ID).status())
            .isEqualTo(SourceSyncResult.Status.SKIPPED_NOT_CONNECTED);
    }

    @Test
    void resyncReporting_unexpectedError_isFailedAndNeverLogsTheToken() {
        givenStoredConnection();
        when(wisePort.fetchJars(TOKEN)).thenThrow(new IllegalStateException("boom"));
        Logger logger = (Logger) LoggerFactory.getLogger(WiseSyncService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            SourceSyncResult result = service.resyncReporting(MEMBER_ID);

            assertThat(result.status()).isEqualTo(SourceSyncResult.Status.FAILED);
            assertThat(logs.list).isNotEmpty().allSatisfy(event -> assertThat(allText(event)).doesNotContain(TOKEN));
        } finally {
            logger.detachAppender(logs);
        }
    }

    // ---- status and disconnect --------------------------------------------------------

    @Test
    void status_connected_masksAllButTheLastFourCharacters() {
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("ciphertext")).thenReturn(TOKEN);

        WiseConnectionStatusResponse status = service.getConnectionStatus(MEMBER_ID);

        assertThat(status.connected()).isTrue();
        assertThat(status.maskedToken()).isEqualTo("••••e1f0");
        assertThat(status.toString()).doesNotContain(TOKEN);
    }

    @Test
    void status_notConnected_carriesNoToken() {
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        assertThat(service.getConnectionStatus(MEMBER_ID))
            .isEqualTo(new WiseConnectionStatusResponse(false, null, null, null, null));
    }

    @Test
    void deleteConnection_removesTheTokenAndLeavesTheAccounts() {
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));

        assertThat(service.deleteConnection(MEMBER_ID)).isTrue();

        verify(connectionRepository).delete(connection);
        verifyNoInteractions(accountRepository);
    }

    // ---- naming -----------------------------------------------------------------------

    @Test
    void accountName_businessJar_saysSo() {
        assertThat(WiseSyncService.accountName(new WiseJar(1, true, "Taxes", "GBP", BigDecimal.ONE)))
            .isEqualTo("Taxes (Business)");
    }

    @Test
    void accountName_unnamedJar_usesItsCurrency() {
        assertThat(WiseSyncService.accountName(new WiseJar(1, false, " ", "EUR", BigDecimal.ONE)))
            .isEqualTo("Jar EUR");
    }

    private void givenStoredConnection() {
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("ciphertext")).thenReturn(TOKEN);
    }

    private void givenWiseReturns(WiseJar... jars) {
        givenStoredConnection();
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(wisePort.fetchJars(TOKEN)).thenReturn(List.of(jars));
    }

    private List<Account> savedAccounts() {
        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, atLeast(0)).save(saved.capture());
        return saved.getAllValues();
    }

    private static WiseJar jar(long id, String name, String currency, String worth) {
        return new WiseJar(id, false, name, currency, new BigDecimal(worth));
    }

    private static String allText(ILoggingEvent event) {
        String text = event.getFormattedMessage();
        if (event.getThrowableProxy() != null) text += "\n" + ThrowableProxyUtil.asString(event.getThrowableProxy());
        return text;
    }
}
