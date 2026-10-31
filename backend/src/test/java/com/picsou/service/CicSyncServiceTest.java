package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.CicSession;
import com.picsou.model.CicSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.port.CicErrorCode;
import com.picsou.port.CicPort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CicSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.service.sync.SourceSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class CicSyncServiceTest {
    private static final String LIVRET_ID = "cic_1000000012345602";
    private static final String CREDENTIALS_JSON = "{\"login\":\"12345678\",\"password\":\"secret\"}";

    @Mock CicPort port;
    @Mock CicSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountService accountService;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Mock Clock clock;
    @Captor ArgumentCaptor<Account> accountCaptor;

    CicSyncService service;

    @BeforeEach
    void setUp() {
        executeTransactionsImmediately();
        lenient().when(clock.instant()).thenReturn(Instant.parse("2026-10-31T12:00:00Z"));
        service = new CicSyncService(
            port, sessionRepository, accountRepository, memberRepository,
            accountService, encryption, txTemplate, Runnable::run, clock
        );
    }

    @Test
    void queueSync_importsEachAccountWithItsOwnType() {
        arrangeCommittableSync(accounts());

        CicSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(CicSyncStatus.SUCCESS);
        verify(accountRepository, times(2)).save(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues())
            .extracting(Account::getType)
            .containsExactly(AccountType.CHECKING, AccountType.LIVRET_A);
        Account livret = accountCaptor.getAllValues().get(1);
        assertThat(livret.getExternalAccountId()).isEqualTo(LIVRET_ID);
        assertThat(livret.getProvider()).isEqualTo("CIC");
        assertThat(livret.getCurrentBalance()).isEqualByComparingTo("5000.00");
        assertThat(livret.getCashBalance()).isNull();
        verify(accountService).upsertSnapshot(any(Account.class), eq(new BigDecimal("5000.00")), eq(LocalDate.now()));
    }

    @Test
    void queueSync_refusesAnAccountTypeOutsideCurrentAndSavings() {
        arrangeQueuedSession(activeSession(member()));
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(new CicPort.AccountData(
            "cic_1", "PEA", AccountType.PEA, new BigDecimal("10.00"), true)));

        service.queueSync(7L);

        verify(accountRepository, never()).save(any());
    }

    @Test
    void expiredSessionLogsInAgainWithTheStoredCredentialsAndThePreviousSession() {
        CicSession session = activeSession(member());
        session.setEncryptedCredentials("credentials-encrypted");
        arrangeCommittableSession(session);
        when(encryption.decrypt("credentials-encrypted")).thenReturn(CREDENTIALS_JSON);
        when(encryption.encrypt("fresh-state")).thenReturn("fresh-encrypted");
        when(port.fetchAccounts("plain-state")).thenThrow(expired());
        when(port.reauthenticate("12345678", "secret", "plain-state"))
            .thenReturn(new CicPort.InitiateResult(null, false, null, "fresh-state"));
        when(port.fetchAccounts("fresh-state")).thenReturn(accounts());

        service.queueSync(7L);

        // The previous session is what carries the remembered-device cookie.
        verify(port).reauthenticate("12345678", "secret", "plain-state");
        assertThat(session.getSessionState()).isEqualTo("fresh-encrypted");
        assertThat(session.getSyncStatus()).isEqualTo(CicSyncStatus.SUCCESS);
        verify(port, times(2)).fetchAccounts(anyString());
    }

    @Test
    void aReLoginThatAsksForANewConfirmationStopsAndAsksTheSaverToReconnect() {
        CicSession session = activeSession(member());
        session.setEncryptedCredentials("credentials-encrypted");
        arrangeQueuedSession(session);
        when(encryption.decrypt("credentials-encrypted")).thenReturn(CREDENTIALS_JSON);
        when(port.fetchAccounts("plain-state")).thenThrow(expired());
        when(port.reauthenticate("12345678", "secret", "plain-state"))
            .thenReturn(new CicPort.InitiateResult("process", true, "APP_PUSH", null));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CicSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(CicErrorCode.SESSION_EXPIRED);
        assertThat(session.isActive()).isFalse();
        // Credentials stay: the next reconnection can still re-log unattended.
        assertThat(session.getEncryptedCredentials()).isEqualTo("credentials-encrypted");
    }

    @Test
    void aRejectedStoredPasswordIsForgottenSoItIsNotReplayedDaily() {
        CicSession session = activeSession(member());
        session.setEncryptedCredentials("credentials-encrypted");
        arrangeQueuedSession(session);
        when(encryption.decrypt("credentials-encrypted")).thenReturn(CREDENTIALS_JSON);
        when(port.fetchAccounts("plain-state")).thenThrow(expired());
        when(port.reauthenticate("12345678", "secret", "plain-state"))
            .thenThrow(new SyncException("bad", null, CicErrorCode.INVALID_CREDENTIALS.name()));

        service.queueSync(7L);

        assertThat(session.getLastSyncError()).isEqualTo(CicErrorCode.INVALID_CREDENTIALS);
        assertThat(session.getEncryptedCredentials()).isNull();
        CicSyncService.SessionStatusResponse status = service.getStatus(7L);
        assertThat(status.isActive()).isFalse();
        assertThat(status.canReconnect()).isFalse();
        assertThat(status.syncStatus()).isEqualTo(CicSyncStatus.FAILED);
        assertThat(status.lastSyncError()).isEqualTo(CicErrorCode.INVALID_CREDENTIALS);
        assertThat(service.resyncReporting(7L).status()).isEqualTo(SourceSyncResult.Status.NEEDS_REAUTH);
    }

    @Test
    void expiredSessionWithoutStoredCredentialsDoesNotTryToLogIn() {
        CicSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenThrow(expired());

        service.queueSync(7L);

        assertThat(session.getLastSyncError()).isEqualTo(CicErrorCode.SESSION_EXPIRED);
        verify(port, never()).reauthenticate(anyString(), anyString(), any());
    }

    @Test
    void aFirstLoginSendsNoPreviousSession() {
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        when(port.initiateAuth("12345678", "secret", null))
            .thenReturn(new CicPort.InitiateResult("p1", true, "APP_PUSH", null));

        CicSyncService.AuthInitResponse result = service.initiateAuth("12345678", "secret", 7L);

        assertThat(result.mfaRequired()).isTrue();
        verify(port).initiateAuth(eq("12345678"), eq("secret"), isNull());
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void aReconnectionReplaysThePreviousSessionToSkipTheConfirmation() {
        CicSession expiredSession = activeSession(member());
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(expiredSession));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");
        when(port.initiateAuth("12345678", "secret", "plain-state"))
            .thenReturn(new CicPort.InitiateResult("p1", true, "APP_PUSH", null));

        service.initiateAuth("12345678", "secret", 7L);

        verify(port).initiateAuth("12345678", "secret", "plain-state");
    }

    /**
     * The saver clicked Reconnect: the stored credentials log in again, and the
     * mobile confirmation is awaited like after a typed login instead of being
     * withdrawn as the scheduled re-login does.
     */
    @Test
    void aReconnectionStartedByTheSaverUsesTheStoredCredentialsAndWaitsForTheApp() {
        CicSession expiredSession = activeSession(member());
        expiredSession.setEncryptedCredentials("credentials-encrypted");
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(expiredSession));
        when(encryption.decrypt("credentials-encrypted")).thenReturn(CREDENTIALS_JSON);
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");
        when(port.initiateAuth("12345678", "secret", "plain-state"))
            .thenReturn(new CicPort.InitiateResult("p1", true, "APP_PUSH", null));

        CicSyncService.AuthInitResponse result = service.reconnect(7L);

        assertThat(result.processId()).isEqualTo("p1");
        assertThat(result.mfaType()).isEqualTo("APP_PUSH");
        verify(port, never()).reauthenticate(anyString(), anyString(), any());
    }

    @Test
    void aReconnectionWithoutStoredCredentialsAsksTheSaverToTypeThem() {
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(activeSession(member())));

        assertThatThrownBy(() -> service.reconnect(7L))
            .isInstanceOf(SyncException.class)
            .extracting("code").isEqualTo(CicErrorCode.SESSION_EXPIRED.name());
        verify(port, never()).initiateAuth(anyString(), anyString(), any());
    }

    @Test
    void theStatusTellsWhetherStoredCredentialsCanReconnect() {
        CicSession session = activeSession(member());
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        assertThat(service.getStatus(7L).canReconnect()).isFalse();

        session.setEncryptedCredentials("credentials-encrypted");
        assertThat(service.getStatus(7L).canReconnect()).isTrue();
    }

    @Test
    void theCredentialsAreStoredOnlyOnceTheConfirmationSucceeded() {
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        when(port.initiateAuth("12345678", "secret", null))
            .thenReturn(new CicPort.InitiateResult("p2", true, "APP_PUSH", null));
        when(port.completeAuth("p2", null)).thenReturn("cookies");
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member()));
        when(encryption.encrypt(anyString())).thenAnswer(invocation ->
            "cookies".equals(invocation.getArgument(0)) ? "encrypted-state" : "encrypted-credentials");
        when(sessionRepository.saveAndFlush(any(CicSession.class))).thenAnswer(invocation -> {
            CicSession stored = invocation.getArgument(0);
            assertThat(stored.getEncryptedCredentials()).isEqualTo("encrypted-credentials");
            return stored;
        });
        lenient().when(port.fetchAccounts("cookies"))
            .thenThrow(new SyncException("transient", null, CicErrorCode.UPSTREAM_UNAVAILABLE.name()));

        service.initiateAuth("12345678", "secret", 7L);
        service.completeAuth("p2", null, 7L);

        verify(port).completeAuth("p2", null);
        verify(sessionRepository).saveAndFlush(any(CicSession.class));
    }

    @Test
    void aMistypedSmsCodeKeepsTheCredentialsForTheNextTry() {
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        when(port.initiateAuth("12345678", "secret", null))
            .thenReturn(new CicPort.InitiateResult("p4", true, "SMS", null));
        when(port.completeAuth("p4", "000000"))
            .thenThrow(new SyncException("wrong", null, CicErrorCode.INVALID_OTP.name()));
        when(port.completeAuth("p4", "123456")).thenReturn("cookies");
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member()));
        when(encryption.encrypt(anyString())).thenAnswer(invocation ->
            "cookies".equals(invocation.getArgument(0)) ? "encrypted-state" : "encrypted-credentials");
        when(sessionRepository.saveAndFlush(any(CicSession.class))).thenAnswer(invocation -> {
            CicSession stored = invocation.getArgument(0);
            assertThat(stored.getEncryptedCredentials()).isEqualTo("encrypted-credentials");
            return stored;
        });
        lenient().when(port.fetchAccounts("cookies"))
            .thenThrow(new SyncException("transient", null, CicErrorCode.UPSTREAM_UNAVAILABLE.name()));
        service.initiateAuth("12345678", "secret", 7L);

        assertThatThrownBy(() -> service.completeAuth("p4", "000000", 7L)).isInstanceOf(SyncException.class);
        service.completeAuth("p4", "123456", 7L);

        verify(sessionRepository).saveAndFlush(any(CicSession.class));
    }

    @Test
    void anotherMemberCannotCompleteSomeoneElsesLogin() {
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        when(port.initiateAuth("12345678", "secret", null))
            .thenReturn(new CicPort.InitiateResult("p3", true, "APP_PUSH", null));
        service.initiateAuth("12345678", "secret", 7L);

        assertThatThrownBy(() -> service.completeAuth("p3", null, 8L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CicErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, never()).completeAuth(anyString(), any());
    }

    @Test
    void anUnknownAttemptCannotCreateAConnection() {
        assertAttemptExpired(() -> service.completeAuth("missing", null, 7L));

        verify(port, never()).completeAuth(anyString(), any());
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void aClearedAttemptCannotCompleteEvenIfTheSidecarStillHasIt() {
        initiatePendingAttempt();
        service.clearSession(7L);

        assertAttemptExpired(() -> service.completeAuth("pending", null, 7L));

        verify(port, never()).completeAuth(anyString(), any());
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void anExpiredAttemptCannotReachTheSidecarOrStoreASession() {
        initiatePendingAttempt();
        when(clock.instant()).thenReturn(Instant.parse("2026-10-31T12:10:00Z"));

        assertAttemptExpired(() -> service.completeAuth("pending", null, 7L));

        verify(port, never()).completeAuth(anyString(), any());
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void disconnectDuringCompletionPreventsSessionPersistence() {
        initiatePendingAttempt();
        when(port.completeAuth("pending", null)).thenAnswer(invocation -> {
            service.clearSession(7L);
            return "cookies";
        });

        assertAttemptExpired(() -> service.completeAuth("pending", null, 7L));

        verify(encryption, never()).encrypt(anyString());
        verify(sessionRepository, never()).saveAndFlush(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void expiryDuringCompletionPreventsSessionPersistence() {
        initiatePendingAttempt();
        when(port.completeAuth("pending", null)).thenAnswer(invocation -> {
            when(clock.instant()).thenReturn(Instant.parse("2026-10-31T12:10:00Z"));
            return "cookies";
        });

        assertAttemptExpired(() -> service.completeAuth("pending", null, 7L));

        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void disconnectDuringInitiationPreventsRememberedDeviceSessionPersistence() {
        when(port.initiateAuth("12345678", "secret", null)).thenAnswer(invocation -> {
            service.clearSession(7L);
            return new CicPort.InitiateResult(null, false, null, "cookies");
        });

        assertAttemptExpired(() -> service.initiateAuth("12345678", "secret", 7L));

        verify(encryption, never()).encrypt(anyString());
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void disconnectDuringInitiationDoesNotRegisterALatePendingAttempt() {
        when(port.initiateAuth("12345678", "secret", null)).thenAnswer(invocation -> {
            service.clearSession(7L);
            return new CicPort.InitiateResult("late", true, "APP_PUSH", null);
        });

        assertAttemptExpired(() -> service.initiateAuth("12345678", "secret", 7L));
        assertAttemptExpired(() -> service.completeAuth("late", null, 7L));

        verify(port, never()).completeAuth(anyString(), any());
    }

    @Test
    void clearingAnotherMemberDoesNotCancelTheAttempt() {
        initiatePendingAttempt();
        service.clearSession(8L);
        when(port.completeAuth("pending", "000000"))
            .thenThrow(new SyncException("wrong", null, CicErrorCode.INVALID_OTP.name()));

        assertThatThrownBy(() -> service.completeAuth("pending", "000000", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CicErrorCode.INVALID_OTP.name()));
    }

    @Test
    void aNewLoginAfterDisconnectCanCompleteOnlyOnce() {
        service.clearSession(7L);
        initiatePendingAttempt();
        when(port.completeAuth("pending", null)).thenReturn("cookies");
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member()));
        when(encryption.encrypt(anyString())).thenReturn("encrypted");
        when(sessionRepository.saveAndFlush(any(CicSession.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        service.completeAuth("pending", null, 7L);
        assertAttemptExpired(() -> service.completeAuth("pending", null, 7L));

        verify(port).completeAuth("pending", null);
        verify(sessionRepository).saveAndFlush(any(CicSession.class));
    }

    private void initiatePendingAttempt() {
        when(port.initiateAuth("12345678", "secret", null))
            .thenReturn(new CicPort.InitiateResult("pending", true, "APP_PUSH", null));
        service.initiateAuth("12345678", "secret", 7L);
    }

    private void assertAttemptExpired(org.assertj.core.api.ThrowableAssert.ThrowingCallable completion) {
        assertThatThrownBy(completion).isInstanceOfSatisfying(SyncException.class, error ->
            assertThat(error.getCode()).isEqualTo(CicErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
    }

    @Test
    void aScheduledResyncAsksForReauthenticationOnceTheSessionIsGone() {
        CicSession session = activeSession(member());
        session.markQueued();
        session.markFailed(CicErrorCode.SESSION_EXPIRED, java.time.Instant.now());
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));

        SourceSyncResult result = service.resyncReporting(7L);

        assertThat(result.status()).isEqualTo(SourceSyncResult.Status.NEEDS_REAUTH);
        assertThat(result.source()).isEqualTo("cic");
    }

    private SyncException expired() {
        return new SyncException("expired", null, CicErrorCode.SESSION_EXPIRED.name());
    }

    private void arrangeCommittableSync(List<CicPort.AccountData> accounts) {
        arrangeCommittableSession(activeSession(member()));
        when(port.fetchAccounts("plain-state")).thenReturn(accounts);
    }

    private void arrangeCommittableSession(CicSession session) {
        arrangeQueuedSession(session);
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member()));
        when(accountRepository.findByExternalAccountIdAndMemberId(any(), eq(7L))).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            if (account.getId() == null) account.setId(11L);
            return account;
        });
    }

    private void executeTransactionsImmediately() {
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        }).when(txTemplate).execute(any(TransactionCallback.class));
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(transactionStatus);
            return null;
        }).when(txTemplate).executeWithoutResult(any());
    }

    private void arrangeQueuedSession(CicSession session) {
        lenient().when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(session.getId(), 7L))
            .thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        lenient().when(encryption.decrypt("encrypted")).thenReturn("plain-state");
    }

    private FamilyMember member() {
        return FamilyMember.builder().id(7L).displayName("Owner").build();
    }

    private CicSession activeSession(FamilyMember member) {
        return CicSession.builder()
            .id(3L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(CicSyncStatus.IDLE)
            .build();
    }

    private List<CicPort.AccountData> accounts() {
        return List.of(
            new CicPort.AccountData("cic_1000000012345601", "Compte Courant", AccountType.CHECKING,
                new BigDecimal("1234.56"), true),
            new CicPort.AccountData(LIVRET_ID, "Livret A", AccountType.LIVRET_A, new BigDecimal("5000.00"), true)
        );
    }
}
