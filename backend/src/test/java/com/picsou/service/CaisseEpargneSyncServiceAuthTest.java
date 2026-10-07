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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    private static final String CUSTOMER_ID = "12345678";
    private static final String IMAGE = "data:image/png;base64,iVBORw0KGgo=";
    private static final List<Integer> POSITIONS = List.of(7, 3, 0, 9, 4, 1);
    private static final Instant T0 = Instant.parse("2026-10-07T10:00:00Z");

    @Mock CaisseEpargnePort port;
    @Mock CaisseEpargneSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountService accountService;
    @Mock com.picsou.service.budget.CategorizationService categorizationService;
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
            memberRepository, accountService, categorizationService, encryption, txTemplate, Runnable::run, clock);
    }

    private static CaisseEpargnePort.Keypad keypad() {
        return new CaisseEpargnePort.Keypad(Collections.nCopies(10, IMAGE), 5);
    }

    private void challenge(String processId, int expiresIn) {
        when(port.initiateAuth(CUSTOMER_ID))
            .thenReturn(new CaisseEpargnePort.InitiateResult(processId, keypad(), expiresIn));
    }

    /** Initiate and submit the keypad: the state a login is in while the phone push is pending. */
    private void pushPending(String processId) {
        challenge(processId, 90);
        service.initiateAuth(CUSTOMER_ID, 7L);
        service.submitKeypad(processId, POSITIONS, 7L);
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
    void initiateAuth_returnsTheKeypadAndStoresNothing() {
        challenge("proc-1", 90);

        CaisseEpargneSyncService.InitiateResponse response = service.initiateAuth(CUSTOMER_ID, 7L);

        assertThat(response.processId()).isEqualTo("proc-1");
        assertThat(response.keypad().images()).hasSize(10).allMatch(IMAGE::equals);
        assertThat(response.keypad().columns()).isEqualTo(5);
        assertThat(response.expiresInSeconds()).isEqualTo(90);
        verifyNoInteractions(sessionRepository, encryption);
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void initiateAuth_callsTheSidecarExactlyOnce() {
        challenge("proc-1", 90);

        service.initiateAuth(CUSTOMER_ID, 7L);

        verify(port, org.mockito.Mockito.times(1)).initiateAuth(anyString());
    }

    @Test
    void initiateAuth_propagatesAFailureWithoutRetryAndWithoutTouchingTheStoredSession() {
        when(port.initiateAuth(CUSTOMER_ID)).thenThrow(new SyncException(
            "The identifier was refused", null, CaisseEpargneErrorCode.INVALID_CREDENTIALS.name()));

        assertThatThrownBy(() -> service.initiateAuth(CUSTOMER_ID, 7L))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.INVALID_CREDENTIALS.name());
                assertThat(String.valueOf(error.getMessage())).doesNotContain(CUSTOMER_ID);
            });

        verify(port, org.mockito.Mockito.times(1)).initiateAuth(anyString());
        // an existing valid session is neither deleted nor deactivated by a failed login
        verifyNoInteractions(sessionRepository, encryption, txTemplate);
    }

    @Test
    void initiateAuth_refusesAnAnswerWithoutAProcessIdOrALifetime() {
        for (CaisseEpargnePort.InitiateResult bad : new CaisseEpargnePort.InitiateResult[] {
            null,
            new CaisseEpargnePort.InitiateResult(null, keypad(), 90),
            new CaisseEpargnePort.InitiateResult(" ", keypad(), 90),
            new CaisseEpargnePort.InitiateResult("proc-1", keypad(), 0),
            new CaisseEpargnePort.InitiateResult("proc-1", null, 90)}) {
            when(port.initiateAuth(CUSTOMER_ID)).thenReturn(bad);

            assertThatThrownBy(() -> service.initiateAuth(CUSTOMER_ID, 7L))
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED.name()));
        }
    }

    @Test
    void initiateAuth_mapsAnyUnsafeKeypadToKeypadChangedAndNeverRegistersTheProcess() {
        List<String> notTen = new ArrayList<>(Collections.nCopies(9, IMAGE));
        List<String> elevenImages = new ArrayList<>(Collections.nCopies(11, IMAGE));
        List<String> withNull = new ArrayList<>(Collections.nCopies(10, IMAGE));
        withNull.set(3, null);
        List<String> jpeg = new ArrayList<>(Collections.nCopies(10, IMAGE));
        jpeg.set(4, "data:image/jpeg;base64,AAAA");
        List<String> svg = new ArrayList<>(Collections.nCopies(10, IMAGE));
        svg.set(0, "data:image/svg+xml;base64,AAAA");
        List<String> url = new ArrayList<>(Collections.nCopies(10, IMAGE));
        url.set(9, "https://evil.example/pad.png");
        List<String> notBase64 = new ArrayList<>(Collections.nCopies(10, IMAGE));
        notBase64.set(2, "data:image/png;base64,AAAA\" onerror=\"x");
        List<String> empty = new ArrayList<>(Collections.nCopies(10, IMAGE));
        empty.set(1, "data:image/png;base64,");
        String prefix = "data:image/png;base64,";
        List<String> tooBig = new ArrayList<>(Collections.nCopies(10, IMAGE));
        tooBig.set(6, prefix + "A".repeat(16 * 1024 - prefix.length() + 1));
        List<String> justFits = new ArrayList<>(Collections.nCopies(10, IMAGE));
        justFits.set(6, prefix + "A".repeat(16 * 1024 - prefix.length()));

        for (List<String> images : List.of(notTen, elevenImages, withNull, jpeg, svg, url, notBase64, empty, tooBig)) {
            when(port.initiateAuth(CUSTOMER_ID)).thenReturn(
                new CaisseEpargnePort.InitiateResult("proc-bad", new CaisseEpargnePort.Keypad(images, 5), 90));

            assertThatThrownBy(() -> service.initiateAuth(CUSTOMER_ID, 7L))
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.KEYPAD_CHANGED.name()));
            assertThatThrownBy(() -> service.submitKeypad("proc-bad", POSITIONS, 7L))
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        }
        when(port.initiateAuth(CUSTOMER_ID)).thenReturn(
            new CaisseEpargnePort.InitiateResult("proc-ok", new CaisseEpargnePort.Keypad(justFits, 5), 90));
        assertThat(service.initiateAuth(CUSTOMER_ID, 7L).keypad().images().get(6)).hasSize(16 * 1024);
        verify(port, never()).submitKeypad(any(), any());
    }

    @Test
    void initiateAuth_refusesAKeypadThatIsNotFiveColumns() {
        when(port.initiateAuth(CUSTOMER_ID)).thenReturn(
            new CaisseEpargnePort.InitiateResult("proc-1", new CaisseEpargnePort.Keypad(
                Collections.nCopies(10, IMAGE), 4), 90));

        assertThatThrownBy(() -> service.initiateAuth(CUSTOMER_ID, 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.KEYPAD_CHANGED.name()));
    }

    // -- keypad -------------------------------------------------------------

    @Test
    void submitKeypad_forwardsThePositionsOnceForTheOwnerOfTheProcess() {
        challenge("proc-1", 90);
        service.initiateAuth(CUSTOMER_ID, 7L);

        service.submitKeypad("proc-1", POSITIONS, 7L);

        verify(port, org.mockito.Mockito.times(1)).submitKeypad("proc-1", POSITIONS);
        verifyNoInteractions(sessionRepository, encryption);
    }

    @Test
    void submitKeypad_refusesAnUnknownOrForeignProcessWithoutCallingTheSidecar() {
        assertThatThrownBy(() -> service.submitKeypad("unknown", POSITIONS, 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        challenge("proc-1", 90);
        service.initiateAuth(CUSTOMER_ID, 7L);
        assertThatThrownBy(() -> service.submitKeypad("proc-1", POSITIONS, 8L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED.name()));

        verify(port, never()).submitKeypad(any(), any());
        // the owner can still use it
        service.submitKeypad("proc-1", POSITIONS, 7L);
        verify(port).submitKeypad("proc-1", POSITIONS);
    }

    @Test
    void submitKeypad_refusesAKeypadOlderThanItsLifetimeAsKeypadExpired() {
        challenge("proc-1", 90);
        service.initiateAuth(CUSTOMER_ID, 7L);
        clock.advanceSeconds(91);

        assertThatThrownBy(() -> service.submitKeypad("proc-1", POSITIONS, 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.KEYPAD_EXPIRED.name()));
        verify(port, never()).submitKeypad(any(), any());
    }

    @Test
    void submitKeypad_isSingleUseEvenWhenTheSidecarFailsAndKeepsItsCode() {
        for (CaisseEpargneErrorCode code : List.of(CaisseEpargneErrorCode.KEYPAD_CHANGED,
            CaisseEpargneErrorCode.INVALID_POSITIONS, CaisseEpargneErrorCode.INVALID_CREDENTIALS)) {
            challenge("proc-" + code, 90);
            service.initiateAuth(CUSTOMER_ID, 7L);
            org.mockito.Mockito.doThrow(new SyncException("failed", null, code.name()))
                .when(port).submitKeypad("proc-" + code, POSITIONS);

            assertThatThrownBy(() -> service.submitKeypad("proc-" + code, POSITIONS, 7L))
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(code.name()));
            // the sidecar released the session: a second try is refused here, not replayed
            assertThatThrownBy(() -> service.submitKeypad("proc-" + code, POSITIONS, 7L))
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
            // and the failed login can no longer be completed
            assertThatThrownBy(() -> service.completeAuth("proc-" + code, 7L))
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
            verify(port, org.mockito.Mockito.times(1)).submitKeypad("proc-" + code, POSITIONS);
        }
        verify(port, never()).completeAuth(any());
    }

    @Test
    void submitKeypad_cannotBeCalledTwiceOnceTheSidecarAccepted() {
        pushPending("proc-1");

        assertThatThrownBy(() -> service.submitKeypad("proc-1", POSITIONS, 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, org.mockito.Mockito.times(1)).submitKeypad("proc-1", POSITIONS);
    }

    @Test
    void completeAuth_requiresTheKeypadToHaveBeenSubmitted() {
        challenge("proc-1", 90);
        service.initiateAuth(CUSTOMER_ID, 7L);

        assertThatThrownBy(() -> service.completeAuth("proc-1", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, never()).completeAuth(any());
    }

    @Test
    void completeAuth_stillWorksLongAfterTheKeypadStepLifetime() {
        pushPending("proc-1");
        clock.advanceSeconds(120); // keypad TTL (90 s) is over, the push is still pending
        when(port.completeAuth("proc-1")).thenReturn("cookies-json");
        arrangeStore("cookies-json");

        service.completeAuth("proc-1", 7L);

        verify(port).completeAuth("proc-1");
    }

    @Test
    void completeAuth_refusesAPendingPushOlderThanTheApprovalWindow() {
        pushPending("proc-1");
        clock.advanceSeconds(181);

        assertThatThrownBy(() -> service.completeAuth("proc-1", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, never()).completeAuth(any());
    }

    // -- complete -----------------------------------------------------------

    @Test
    void completeAuth_storesTheEncryptedSessionThroughStoreSessionAndDoesNotSync() {
        pushPending("proc-1");
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
    void completeAuth_neverHandsAnythingButTheSessionStateToEncryption() {
        pushPending("proc-1");
        when(port.completeAuth("proc-1")).thenReturn("cookies-json");
        arrangeStore("cookies-json");

        service.completeAuth("proc-1", 7L);

        verify(encryption).encrypt("cookies-json");
        verify(encryption, never()).encrypt(CUSTOMER_ID);
        verify(encryption, never()).encrypt(org.mockito.ArgumentMatchers.contains(CUSTOMER_ID));
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
        pushPending("proc-1");

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
        pushPending("proc-1");
        clock.advanceSeconds(181);

        assertThatThrownBy(() -> service.completeAuth("proc-1", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, never()).completeAuth(any());
    }

    @Test
    void completeAuth_isSingleUseEvenWhenTheSidecarFails() {
        pushPending("proc-1");
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
        pushPending("proc-1");
        when(port.completeAuth("proc-1")).thenThrow(new SyncException(
            "Refused", null, CaisseEpargneErrorCode.INVALID_CREDENTIALS.name()));

        assertThatThrownBy(() -> service.completeAuth("proc-1", 7L)).isInstanceOf(SyncException.class);

        verifyNoInteractions(sessionRepository, encryption);
        verify(sessionRepository, never()).delete(any());
    }

    @Test
    void completeAuth_refusesABlankSessionStateWithoutReplacingTheStoredOne() {
        pushPending("proc-1");
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
            assertThat(field.getName().toLowerCase()).doesNotContain("password").doesNotContain("credential")
                .doesNotContain("customer").doesNotContain("positions");
        }
        for (var type : CaisseEpargneSyncService.class.getDeclaredClasses()) {
            for (var component : type.getRecordComponents() == null
                ? new java.lang.reflect.RecordComponent[0] : type.getRecordComponents()) {
                assertThat(component.getName().toLowerCase()).doesNotContain("password")
                    .doesNotContain("customer").doesNotContain("positions");
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
