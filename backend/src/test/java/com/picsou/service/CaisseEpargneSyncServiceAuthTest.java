package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.CaisseEpargneSession;
import com.picsou.model.CaisseEpargneSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.port.CaisseEpargneErrorCode;
import com.picsou.port.CaisseEpargnePort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CaisseEpargneSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** initiateAuth / completeAuth: no password is kept, one attempt, member-scoped, session untouched on failure. */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class CaisseEpargneSyncServiceAuthTest {

    private static final String PASSWORD = "9482716350";
    private static final Instant T0 = Instant.parse("2026-10-07T10:00:00Z");

    @Mock CaisseEpargnePort port;
    @Mock CaisseEpargneSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountService accountService;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;

    private MutableClock clock;
    private CaisseEpargneSyncService service;

    @BeforeEach
    void setUp() {
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        }).when(txTemplate).execute(any(TransactionCallback.class));
        clock = new MutableClock(T0);
        service = new CaisseEpargneSyncService(
            port, sessionRepository, accountRepository, transactionRepository,
            memberRepository, accountService, encryption, txTemplate, Runnable::run, clock);
    }

    private void challenge(String processId, int expiresIn) {
        when(port.initiateAuth("12345678", PASSWORD))
            .thenReturn(new CaisseEpargnePort.InitiateResult(processId, "SECURPASS", expiresIn));
    }

    private void arrangeStore(String plainState) {
        FamilyMember member = FamilyMember.builder().id(7L).displayName("Owner").build();
        lenient().when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        lenient().when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.empty());
        lenient().when(encryption.encrypt(plainState)).thenReturn("encrypted-state");
        lenient().when(sessionRepository.saveAndFlush(any(CaisseEpargneSession.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
    }

    // -- initiate -----------------------------------------------------------

    @Test
    void initiateAuth_returnsTheSecurPassChallengeAndStoresNothing() {
        challenge("proc-1", 300);

        CaisseEpargneSyncService.InitiateResponse response = service.initiateAuth("12345678", PASSWORD, 7L);

        assertThat(response.processId()).isEqualTo("proc-1");
        assertThat(response.mfaRequired()).isTrue();
        assertThat(response.mfaType()).isEqualTo("SECURPASS");
        assertThat(response.expiresInSeconds()).isEqualTo(300);
        verifyNoInteractions(sessionRepository, encryption);
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void initiateAuth_callsTheSidecarExactlyOnce() {
        challenge("proc-1", 300);

        service.initiateAuth("12345678", PASSWORD, 7L);

        verify(port, org.mockito.Mockito.times(1)).initiateAuth(anyString(), anyString());
    }

    @Test
    void initiateAuth_propagatesAFailureWithoutRetryAndWithoutTouchingTheStoredSession() {
        when(port.initiateAuth("12345678", PASSWORD)).thenThrow(new SyncException(
            "The identifier or password was refused", null, CaisseEpargneErrorCode.INVALID_CREDENTIALS.name()));

        assertThatThrownBy(() -> service.initiateAuth("12345678", PASSWORD, 7L))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.INVALID_CREDENTIALS.name());
                assertThat(String.valueOf(error.getMessage())).doesNotContain(PASSWORD);
            });

        verify(port, org.mockito.Mockito.times(1)).initiateAuth(anyString(), anyString());
        // an existing valid session is neither deleted nor deactivated by a failed login
        verifyNoInteractions(sessionRepository, encryption, txTemplate);
    }

    @Test
    void initiateAuth_refusesAnAnswerWithoutAProcessIdOrALifetime() {
        for (CaisseEpargnePort.InitiateResult bad : new CaisseEpargnePort.InitiateResult[] {
            new CaisseEpargnePort.InitiateResult(null, "SECURPASS", 300),
            new CaisseEpargnePort.InitiateResult(" ", "SECURPASS", 300),
            new CaisseEpargnePort.InitiateResult("proc-1", "SECURPASS", 0),
            new CaisseEpargnePort.InitiateResult("proc-1", null, 300)}) {
            when(port.initiateAuth("12345678", PASSWORD)).thenReturn(bad);

            assertThatThrownBy(() -> service.initiateAuth("12345678", PASSWORD, 7L))
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED.name()));
        }
    }

    // -- complete -----------------------------------------------------------

    @Test
    void completeAuth_storesTheEncryptedSessionThroughStoreSessionAndDoesNotSync() {
        challenge("proc-1", 300);
        service.initiateAuth("12345678", PASSWORD, 7L);
        when(port.completeAuth("proc-1")).thenReturn("cookies-json");
        arrangeStore("cookies-json");

        service.completeAuth("proc-1", 7L);

        ArgumentCaptor<CaisseEpargneSession> stored = ArgumentCaptor.forClass(CaisseEpargneSession.class);
        verify(sessionRepository).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getSessionState()).isEqualTo("encrypted-state");
        assertThat(stored.getValue().getSyncStatus()).isEqualTo(CaisseEpargneSyncStatus.IDLE);
        assertThat(stored.getValue().isActive()).isTrue();
        verify(port, never()).fetchAccounts(any());
        verify(port, never()).checkSession(any());
    }

    @Test
    void completeAuth_neverHandsThePasswordAnywhereButTheInitiateCall() {
        challenge("proc-1", 300);
        service.initiateAuth("12345678", PASSWORD, 7L);
        when(port.completeAuth("proc-1")).thenReturn("cookies-json");
        arrangeStore("cookies-json");

        service.completeAuth("proc-1", 7L);

        verify(encryption).encrypt("cookies-json");
        verify(encryption, never()).encrypt(PASSWORD);
        verify(encryption, never()).encrypt(org.mockito.ArgumentMatchers.contains(PASSWORD));
    }

    @Test
    void completeAuth_refusesAProcessIdItNeverIssuedWithoutCallingTheSidecar() {
        assertThatThrownBy(() -> service.completeAuth("unknown", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED.name()));

        verify(port, never()).completeAuth(any());
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void completeAuth_refusesAnotherMembersProcessAndLeavesItUsableByItsOwner() {
        challenge("proc-1", 300);
        service.initiateAuth("12345678", PASSWORD, 7L);

        assertThatThrownBy(() -> service.completeAuth("proc-1", 8L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, never()).completeAuth(any());

        when(port.completeAuth("proc-1")).thenReturn("cookies-json");
        arrangeStore("cookies-json");
        service.completeAuth("proc-1", 7L);
        verify(port).completeAuth("proc-1");
    }

    @Test
    void completeAuth_refusesAProcessOlderThanItsLifetime() {
        challenge("proc-1", 300);
        service.initiateAuth("12345678", PASSWORD, 7L);
        clock.advanceSeconds(301);

        assertThatThrownBy(() -> service.completeAuth("proc-1", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, never()).completeAuth(any());
    }

    @Test
    void completeAuth_isSingleUseEvenWhenTheSidecarFails() {
        challenge("proc-1", 300);
        service.initiateAuth("12345678", PASSWORD, 7L);
        when(port.completeAuth("proc-1")).thenThrow(new SyncException(
            "App validation timed out", null, CaisseEpargneErrorCode.APP_VALIDATION_TIMEOUT.name()));

        assertThatThrownBy(() -> service.completeAuth("proc-1", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.APP_VALIDATION_TIMEOUT.name()));
        // the second call is refused locally: no replay of /complete
        assertThatThrownBy(() -> service.completeAuth("proc-1", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED.name()));

        verify(port, org.mockito.Mockito.times(1)).completeAuth("proc-1");
    }

    @Test
    void completeAuth_leavesAnExistingValidSessionUntouchedWhenTheLoginFails() {
        challenge("proc-1", 300);
        service.initiateAuth("12345678", PASSWORD, 7L);
        when(port.completeAuth("proc-1")).thenThrow(new SyncException(
            "Refused", null, CaisseEpargneErrorCode.INVALID_CREDENTIALS.name()));

        assertThatThrownBy(() -> service.completeAuth("proc-1", 7L)).isInstanceOf(SyncException.class);

        verifyNoInteractions(sessionRepository, encryption);
        verify(sessionRepository, never()).delete(any());
    }

    @Test
    void completeAuth_refusesABlankSessionStateWithoutReplacingTheStoredOne() {
        challenge("proc-1", 300);
        service.initiateAuth("12345678", PASSWORD, 7L);
        when(port.completeAuth("proc-1")).thenReturn(" ");

        assertThatThrownBy(() -> service.completeAuth("proc-1", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED.name()));

        verify(sessionRepository, never()).delete(any());
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void theServiceKeepsNoFieldThatCouldHoldACredential() {
        for (var field : CaisseEpargneSyncService.class.getDeclaredFields()) {
            assertThat(field.getName().toLowerCase()).doesNotContain("password").doesNotContain("credential");
        }
        for (var type : CaisseEpargneSyncService.class.getDeclaredClasses()) {
            for (var component : type.getRecordComponents() == null
                ? new java.lang.reflect.RecordComponent[0] : type.getRecordComponents()) {
                assertThat(component.getName().toLowerCase()).doesNotContain("password");
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
