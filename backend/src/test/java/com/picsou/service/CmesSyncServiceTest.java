package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.CmesSession;
import com.picsou.model.CmesSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.port.CmesErrorCode;
import com.picsou.port.CmesPort;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CmesSessionRepository;
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
class CmesSyncServiceTest {
    private static final String PEG_ID = "cmes_12345_PEG";
    private static final String CREDENTIALS_JSON = "{\"login\":\"12345678\",\"password\":\"secret\"}";

    @Mock CmesPort port;
    @Mock CmesSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountService accountService;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Captor ArgumentCaptor<Account> accountCaptor;
    @Captor ArgumentCaptor<List<AccountHolding>> holdingsCaptor;

    CmesSyncService service;

    @BeforeEach
    void setUp() {
        executeTransactionsImmediately();
        service = new CmesSyncService(
            port, sessionRepository, accountRepository, holdingRepository, memberRepository,
            accountService, encryption, txTemplate, Runnable::run
        );
    }

    @Test
    void queueSync_importsEachPlanAsAnEmployeeSavingsAccountWithItsFunds() {
        arrangeCommittableSync(pegPlan());

        CmesSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(CmesSyncStatus.SUCCESS);
        verify(accountRepository).save(accountCaptor.capture());
        Account saved = accountCaptor.getValue();
        assertThat(saved.getType()).isEqualTo(AccountType.EMPLOYEE_SAVINGS);
        assertThat(saved.getProvider()).isEqualTo("Crédit Mutuel Épargne Salariale");
        assertThat(saved.getExternalAccountId()).isEqualTo(PEG_ID);
        assertThat(saved.getName()).isEqualTo("Plan d'épargne groupe — ACME INDUSTRIES");
        assertThat(saved.getCurrentBalance()).isEqualByComparingTo("1500.50");
        assertThat(saved.getCashBalance()).isNull();

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        assertThat(holdingsCaptor.getValue())
            .extracting(AccountHolding::getTicker)
            .containsExactly("CMES-160", "CMES-161");
        AccountHolding first = holdingsCaptor.getValue().getFirst();
        assertThat(first.getQuantity()).isEqualByComparingTo("50");
        assertThat(first.getCurrentPrice()).isEqualByComparingTo("20");
        assertThat(first.getProviderValueEur()).isEqualByComparingTo("1000.00");
        // Cost basis per unit = (value - gain) / units.
        assertThat(first.getAverageBuyIn()).isEqualByComparingTo("18.1818");
        // Invested = value - gain, summed over funds.
        verify(accountService).upsertSnapshot(any(Account.class), eq(new BigDecimal("1500.50")),
            eq(new BigDecimal("1422.42")), eq(LocalDate.now()));
    }

    @Test
    void queueSync_refusesAPlanWhoseFundsDoNotAddUp() {
        CmesPort.PlanData broken = new CmesPort.PlanData(PEG_ID, "Plan d'épargne groupe", "PEG", null,
            new BigDecimal("9999.99"), pegPlan().positions(), true);
        FamilyMember member = member();
        arrangeQueuedSession(activeSession(member));
        when(port.fetchPlans("plain-state")).thenReturn(List.of(broken));

        service.queueSync(7L);

        verify(accountRepository, never()).save(any());
    }

    @Test
    void expiredSessionLogsInAgainWithTheStoredCredentialsAndThePreviousSession() {
        CmesSession session = activeSession(member());
        session.setEncryptedCredentials("credentials-encrypted");
        arrangeCommittableSession(session);
        when(encryption.decrypt("credentials-encrypted")).thenReturn(CREDENTIALS_JSON);
        when(encryption.encrypt("fresh-state")).thenReturn("fresh-encrypted");
        when(port.fetchPlans("plain-state")).thenThrow(expired());
        when(port.reauthenticate("12345678", "secret", "plain-state"))
            .thenReturn(new CmesPort.InitiateResult(null, false, null, "fresh-state"));
        when(port.fetchPlans("fresh-state")).thenReturn(List.of(pegPlan()));

        service.queueSync(7L);

        // The previous session is what carries the remembered-device cookie.
        verify(port).reauthenticate("12345678", "secret", "plain-state");
        assertThat(session.getSessionState()).isEqualTo("fresh-encrypted");
        assertThat(session.getSyncStatus()).isEqualTo(CmesSyncStatus.SUCCESS);
        verify(port, times(2)).fetchPlans(anyString());
    }

    @Test
    void aReLoginThatAsksForANewConfirmationStopsAndAsksTheSaverToReconnect() {
        CmesSession session = activeSession(member());
        session.setEncryptedCredentials("credentials-encrypted");
        arrangeQueuedSession(session);
        when(encryption.decrypt("credentials-encrypted")).thenReturn(CREDENTIALS_JSON);
        when(port.fetchPlans("plain-state")).thenThrow(expired());
        when(port.reauthenticate("12345678", "secret", "plain-state"))
            .thenReturn(new CmesPort.InitiateResult("process", true, "APP_PUSH", null));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CmesSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(CmesErrorCode.SESSION_EXPIRED);
        assertThat(session.isActive()).isFalse();
        // Credentials stay: the next reconnection can still re-log unattended.
        assertThat(session.getEncryptedCredentials()).isEqualTo("credentials-encrypted");
    }

    @Test
    void aRejectedStoredPasswordIsForgottenSoItIsNotReplayedDaily() {
        CmesSession session = activeSession(member());
        session.setEncryptedCredentials("credentials-encrypted");
        arrangeQueuedSession(session);
        when(encryption.decrypt("credentials-encrypted")).thenReturn(CREDENTIALS_JSON);
        when(port.fetchPlans("plain-state")).thenThrow(expired());
        when(port.reauthenticate("12345678", "secret", "plain-state"))
            .thenThrow(new SyncException("bad", null, CmesErrorCode.INVALID_CREDENTIALS.name()));

        service.queueSync(7L);

        assertThat(session.getLastSyncError()).isEqualTo(CmesErrorCode.INVALID_CREDENTIALS);
        assertThat(session.getEncryptedCredentials()).isNull();
    }

    @Test
    void expiredSessionWithoutStoredCredentialsDoesNotTryToLogIn() {
        CmesSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchPlans("plain-state")).thenThrow(expired());

        service.queueSync(7L);

        assertThat(session.getLastSyncError()).isEqualTo(CmesErrorCode.SESSION_EXPIRED);
        verify(port, never()).reauthenticate(anyString(), anyString(), any());
    }

    @Test
    void aFirstLoginSendsNoPreviousSession() {
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        when(port.initiateAuth("12345678", "secret", null))
            .thenReturn(new CmesPort.InitiateResult("p1", true, "APP_PUSH", null));

        CmesSyncService.AuthInitResponse result = service.initiateAuth("12345678", "secret", 7L);

        assertThat(result.mfaRequired()).isTrue();
        verify(port).initiateAuth(eq("12345678"), eq("secret"), isNull());
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void aReconnectionReplaysThePreviousSessionToSkipTheConfirmation() {
        CmesSession expiredSession = activeSession(member());
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(expiredSession));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");
        when(port.initiateAuth("12345678", "secret", "plain-state"))
            .thenReturn(new CmesPort.InitiateResult("p1", true, "APP_PUSH", null));

        service.initiateAuth("12345678", "secret", 7L);

        verify(port).initiateAuth("12345678", "secret", "plain-state");
    }

    @Test
    void theCredentialsAreStoredOnlyOnceTheConfirmationSucceeded() {
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        when(port.initiateAuth("12345678", "secret", null))
            .thenReturn(new CmesPort.InitiateResult("p2", true, "APP_PUSH", null));
        when(port.completeAuth("p2", null)).thenReturn("cookies");
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member()));
        when(encryption.encrypt(anyString())).thenAnswer(invocation ->
            "cookies".equals(invocation.getArgument(0)) ? "encrypted-state" : "encrypted-credentials");
        when(sessionRepository.saveAndFlush(any(CmesSession.class))).thenAnswer(invocation -> {
            CmesSession stored = invocation.getArgument(0);
            assertThat(stored.getEncryptedCredentials()).isEqualTo("encrypted-credentials");
            return stored;
        });
        lenient().when(port.fetchPlans("cookies"))
            .thenThrow(new SyncException("transient", null, CmesErrorCode.UPSTREAM_UNAVAILABLE.name()));

        service.initiateAuth("12345678", "secret", 7L);
        service.completeAuth("p2", null, 7L);

        verify(port).completeAuth("p2", null);
        verify(sessionRepository).saveAndFlush(any(CmesSession.class));
    }

    @Test
    void aMistypedSmsCodeKeepsTheCredentialsForTheNextTry() {
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        when(port.initiateAuth("12345678", "secret", null))
            .thenReturn(new CmesPort.InitiateResult("p4", true, "SMS", null));
        when(port.completeAuth("p4", "000000"))
            .thenThrow(new SyncException("wrong", null, CmesErrorCode.INVALID_OTP.name()));
        when(port.completeAuth("p4", "123456")).thenReturn("cookies");
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member()));
        when(encryption.encrypt(anyString())).thenAnswer(invocation ->
            "cookies".equals(invocation.getArgument(0)) ? "encrypted-state" : "encrypted-credentials");
        when(sessionRepository.saveAndFlush(any(CmesSession.class))).thenAnswer(invocation -> {
            CmesSession stored = invocation.getArgument(0);
            assertThat(stored.getEncryptedCredentials()).isEqualTo("encrypted-credentials");
            return stored;
        });
        lenient().when(port.fetchPlans("cookies"))
            .thenThrow(new SyncException("transient", null, CmesErrorCode.UPSTREAM_UNAVAILABLE.name()));
        service.initiateAuth("12345678", "secret", 7L);

        assertThatThrownBy(() -> service.completeAuth("p4", "000000", 7L)).isInstanceOf(SyncException.class);
        service.completeAuth("p4", "123456", 7L);

        verify(sessionRepository).saveAndFlush(any(CmesSession.class));
    }

    @Test
    void anotherMemberCannotCompleteSomeoneElsesLogin() {
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        when(port.initiateAuth("12345678", "secret", null))
            .thenReturn(new CmesPort.InitiateResult("p3", true, "APP_PUSH", null));
        service.initiateAuth("12345678", "secret", 7L);

        assertThatThrownBy(() -> service.completeAuth("p3", null, 8L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CmesErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, never()).completeAuth(anyString(), any());
    }

    @Test
    void aScheduledResyncAsksForReauthenticationOnceTheSessionIsGone() {
        CmesSession session = activeSession(member());
        session.markQueued();
        session.markFailed(CmesErrorCode.SESSION_EXPIRED, java.time.Instant.now());
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));

        SourceSyncResult result = service.resyncReporting(7L);

        assertThat(result.status()).isEqualTo(SourceSyncResult.Status.NEEDS_REAUTH);
        assertThat(result.source()).isEqualTo("cmes");
    }

    private SyncException expired() {
        return new SyncException("expired", null, CmesErrorCode.SESSION_EXPIRED.name());
    }

    private void arrangeCommittableSync(CmesPort.PlanData... plans) {
        arrangeCommittableSession(activeSession(member()));
        when(port.fetchPlans("plain-state")).thenReturn(List.of(plans));
    }

    private void arrangeCommittableSession(CmesSession session) {
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

    private void arrangeQueuedSession(CmesSession session) {
        lenient().when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(session.getId(), 7L))
            .thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        lenient().when(encryption.decrypt("encrypted")).thenReturn("plain-state");
    }

    private FamilyMember member() {
        return FamilyMember.builder().id(7L).displayName("Owner").build();
    }

    private CmesSession activeSession(FamilyMember member) {
        return CmesSession.builder()
            .id(3L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(CmesSyncStatus.IDLE)
            .build();
    }

    private CmesPort.PlanData pegPlan() {
        return new CmesPort.PlanData(
            PEG_ID,
            "Plan d'épargne groupe",
            "PEG",
            "ACME INDUSTRIES",
            new BigDecimal("1500.50"),
            List.of(
                new CmesPort.Position(null, "160", "FONDS EQUILIBRE SOLIDAIRE", new BigDecimal("50.0000"),
                    new BigDecimal("20.0000"), new BigDecimal("1000.00"), new BigDecimal("90.91")),
                new CmesPort.Position(null, "161", "FONDS ACTIONS MONDE", new BigDecimal("25.0250"),
                    new BigDecimal("20.0000"), new BigDecimal("500.50"), new BigDecimal("-12.83"))
            ),
            true
        );
    }
}
