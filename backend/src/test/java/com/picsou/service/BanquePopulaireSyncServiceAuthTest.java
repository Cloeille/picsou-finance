package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.port.BanquePopulairePort;
import com.picsou.port.BanquePopulaireRegion;
import com.picsou.port.SidecarErrorCode;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.SidecarSessionRepository;
import com.picsou.repository.DebtRepository;
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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** initiateAuth / completeAuth: no password is kept, one attempt, member-scoped, session untouched on failure. */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class BanquePopulaireSyncServiceAuthTest {

    private static final String CUSTOMER_ID = "12345678";
    private static final BanquePopulaireRegion REGION = BanquePopulaireRegion.RIVES_DE_PARIS;
    private static final String IMAGE = "data:image/png;base64,iVBORw0KGgo=";
    private static final List<Integer> POSITIONS = List.of(7, 3, 0, 9, 4, 1);
    private static final Instant T0 = Instant.parse("2026-10-07T10:00:00Z");

    @Mock BanquePopulairePort port;
    @Mock SidecarSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock DebtRepository debtRepository;
    @Mock AccountService accountService;
    @Mock com.picsou.service.budget.CategorizationService categorizationService;
    @Mock com.picsou.service.budget.CategoryService categoryService;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Mock BankLogoResolver logoResolver;
    @Mock CaisseEpargnePerService perService;

    private MutableClock clock;
    private BanquePopulaireSyncService service;

    @BeforeEach
    void setUp() {
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        }).when(txTemplate).execute(any(TransactionCallback.class));
        clock = new MutableClock(T0);
        service = new BanquePopulaireSyncService(
            port, sessionRepository, accountRepository, transactionRepository,
            memberRepository, debtRepository, accountService, categorizationService, categoryService, encryption, txTemplate, Runnable::run,
            perService, logoResolver, clock);
    }

    private static BanquePopulairePort.Keypad keypad() {
        return new BanquePopulairePort.Keypad(Collections.nCopies(10, IMAGE), 5);
    }

    private void challenge(String processId, int expiresIn) {
        when(port.initiateAuth(CUSTOMER_ID, REGION))
            .thenReturn(new BanquePopulairePort.InitiateResult(processId, keypad(), expiresIn));
    }

    /** Initiate and submit the keypad: the state a login is in while the phone push is pending. */
    private void pushPending(String processId) {
        challenge(processId, 90);
        service.initiateAuth(CUSTOMER_ID, REGION, 7L);
        service.submitKeypad(processId, POSITIONS, 7L);
    }

    private void arrangeStore(String plainState) {
        FamilyMember member = FamilyMember.builder().id(7L).displayName("Owner").build();
        lenient().when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        lenient().when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.empty());
        lenient().when(encryption.encrypt(plainState)).thenReturn("encrypted-state");
        lenient().when(sessionRepository.saveAndFlush(any(SidecarSession.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.empty());
    }

    // -- initiate -----------------------------------------------------------

    @Test
    void initiateAuth_returnsTheKeypadAndStoresNothing() {
        challenge("proc-1", 90);

        BpceSyncEngine.InitiateResponse response = service.initiateAuth(CUSTOMER_ID, REGION, 7L);

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

        service.initiateAuth(CUSTOMER_ID, REGION, 7L);

        verify(port, org.mockito.Mockito.times(1)).initiateAuth(anyString(), any());
    }

    @Test
    void initiateAuth_propagatesAFailureWithoutRetryAndWithoutTouchingTheStoredSession() {
        when(port.initiateAuth(CUSTOMER_ID, REGION)).thenThrow(new SyncException(
            "The identifier was refused", null, SidecarErrorCode.INVALID_CREDENTIALS.name()));

        assertThatThrownBy(() -> service.initiateAuth(CUSTOMER_ID, REGION, 7L))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.INVALID_CREDENTIALS.name());
                assertThat(String.valueOf(error.getMessage())).doesNotContain(CUSTOMER_ID);
            });

        verify(port, org.mockito.Mockito.times(1)).initiateAuth(anyString(), any());
        // an existing valid session is neither deleted nor deactivated by a failed login
        verifyNoInteractions(sessionRepository, encryption, txTemplate);
    }

    @Test
    void initiateAuth_refusesAnAnswerWithoutAProcessIdOrALifetime() {
        for (BanquePopulairePort.InitiateResult bad : new BanquePopulairePort.InitiateResult[] {
            null,
            new BanquePopulairePort.InitiateResult(null, keypad(), 90),
            new BanquePopulairePort.InitiateResult(" ", keypad(), 90),
            new BanquePopulairePort.InitiateResult("proc-1", keypad(), 0),
            new BanquePopulairePort.InitiateResult("proc-1", null, 90)}) {
            when(port.initiateAuth(CUSTOMER_ID, REGION)).thenReturn(bad);

            assertThatThrownBy(() -> service.initiateAuth(CUSTOMER_ID, REGION, 7L))
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED.name()));
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
            when(port.initiateAuth(CUSTOMER_ID, REGION)).thenReturn(
                new BanquePopulairePort.InitiateResult("proc-bad", new BanquePopulairePort.Keypad(images, 5), 90));

            assertThatThrownBy(() -> service.initiateAuth(CUSTOMER_ID, REGION, 7L))
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(SidecarErrorCode.KEYPAD_CHANGED.name()));
            assertThatThrownBy(() -> service.submitKeypad("proc-bad", POSITIONS, 7L))
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        }
        when(port.initiateAuth(CUSTOMER_ID, REGION)).thenReturn(
            new BanquePopulairePort.InitiateResult("proc-ok", new BanquePopulairePort.Keypad(justFits, 5), 90));
        assertThat(service.initiateAuth(CUSTOMER_ID, REGION, 7L).keypad().images().get(6)).hasSize(16 * 1024);
        verify(port, never()).submitKeypad(any(), any());
    }

    @Test
    void initiateAuth_refusesAKeypadThatIsNotFiveColumns() {
        when(port.initiateAuth(CUSTOMER_ID, REGION)).thenReturn(
            new BanquePopulairePort.InitiateResult("proc-1", new BanquePopulairePort.Keypad(
                Collections.nCopies(10, IMAGE), 4), 90));

        assertThatThrownBy(() -> service.initiateAuth(CUSTOMER_ID, REGION, 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.KEYPAD_CHANGED.name()));
    }

    // -- keypad -------------------------------------------------------------

    @Test
    void submitKeypad_forwardsThePositionsOnceForTheOwnerOfTheProcess() {
        challenge("proc-1", 90);
        service.initiateAuth(CUSTOMER_ID, REGION, 7L);

        service.submitKeypad("proc-1", POSITIONS, 7L);

        verify(port, org.mockito.Mockito.times(1)).submitKeypad("proc-1", POSITIONS);
        verifyNoInteractions(sessionRepository, encryption);
    }

    @Test
    void submitKeypad_refusesAnUnknownOrForeignProcessWithoutCallingTheSidecar() {
        assertThatThrownBy(() -> service.submitKeypad("unknown", POSITIONS, 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        challenge("proc-1", 90);
        service.initiateAuth(CUSTOMER_ID, REGION, 7L);
        assertThatThrownBy(() -> service.submitKeypad("proc-1", POSITIONS, 8L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));

        verify(port, never()).submitKeypad(any(), any());
        // the owner can still use it
        service.submitKeypad("proc-1", POSITIONS, 7L);
        verify(port).submitKeypad("proc-1", POSITIONS);
    }

    @Test
    void submitKeypad_refusesAKeypadOlderThanItsLifetimeAsKeypadExpired() {
        challenge("proc-1", 90);
        service.initiateAuth(CUSTOMER_ID, REGION, 7L);
        clock.advanceSeconds(91);

        assertThatThrownBy(() -> service.submitKeypad("proc-1", POSITIONS, 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.KEYPAD_EXPIRED.name()));
        verify(port, never()).submitKeypad(any(), any());
    }

    @Test
    void submitKeypad_isSingleUseEvenWhenTheSidecarFailsAndKeepsItsCode() {
        for (SidecarErrorCode code : List.of(SidecarErrorCode.KEYPAD_CHANGED,
            SidecarErrorCode.INVALID_POSITIONS, SidecarErrorCode.INVALID_CREDENTIALS)) {
            challenge("proc-" + code, 90);
            service.initiateAuth(CUSTOMER_ID, REGION, 7L);
            org.mockito.Mockito.doThrow(new SyncException("failed", null, code.name()))
                .when(port).submitKeypad("proc-" + code, POSITIONS);

            assertThatThrownBy(() -> service.submitKeypad("proc-" + code, POSITIONS, 7L))
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(code.name()));
            // the sidecar released the session: a second try is refused here, not replayed
            assertThatThrownBy(() -> service.submitKeypad("proc-" + code, POSITIONS, 7L))
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
            // and the failed login can no longer be completed
            assertThatThrownBy(() -> service.completeAuth("proc-" + code, 7L))
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
            verify(port, org.mockito.Mockito.times(1)).submitKeypad("proc-" + code, POSITIONS);
        }
        verify(port, never()).completeAuth(any());
    }

    @Test
    void submitKeypad_cannotBeCalledTwiceOnceTheSidecarAccepted() {
        pushPending("proc-1");

        assertThatThrownBy(() -> service.submitKeypad("proc-1", POSITIONS, 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, org.mockito.Mockito.times(1)).submitKeypad("proc-1", POSITIONS);
    }

    @Test
    void completeAuth_requiresTheKeypadToHaveBeenSubmitted() {
        challenge("proc-1", 90);
        service.initiateAuth(CUSTOMER_ID, REGION, 7L);

        assertThatThrownBy(() -> service.completeAuth("proc-1", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
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
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, never()).completeAuth(any());
    }

    // -- complete -----------------------------------------------------------

    @Test
    void completeAuth_storesTheEncryptedSessionThroughStoreSessionAndDoesNotSync() {
        pushPending("proc-1");
        when(port.completeAuth("proc-1")).thenReturn("cookies-json");
        arrangeStore("cookies-json");

        service.completeAuth("proc-1", 7L);

        ArgumentCaptor<SidecarSession> stored = ArgumentCaptor.forClass(SidecarSession.class);
        verify(sessionRepository).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getSessionState()).isEqualTo("encrypted-state");
        assertThat(stored.getValue().getRegion()).isEqualTo(REGION);
        assertThat(stored.getValue().getSyncStatus()).isEqualTo(SidecarSyncStatus.IDLE);
        assertThat(stored.getValue().isActive()).isTrue();
        verify(port, never()).fetchAccounts(any());
        verify(port, never()).checkSession(any());
    }

    @Test
    void completeAuth_encryptsTheSessionAndTheIdentifierButNeverTheKeypadPositions() {
        pushPending("proc-1");
        when(port.completeAuth("proc-1")).thenReturn("cookies-json");
        arrangeStore("cookies-json");
        when(encryption.encrypt("{\"customerId\":\"" + CUSTOMER_ID + "\"}")).thenReturn("encrypted-id");

        service.completeAuth("proc-1", 7L);

        ArgumentCaptor<String> encrypted = ArgumentCaptor.forClass(String.class);
        verify(encryption, org.mockito.Mockito.times(2)).encrypt(encrypted.capture());
        assertThat(encrypted.getAllValues())
            .containsExactlyInAnyOrder("cookies-json", "{\"customerId\":\"" + CUSTOMER_ID + "\"}");
        ArgumentCaptor<SidecarSession> stored = ArgumentCaptor.forClass(SidecarSession.class);
        verify(sessionRepository).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getEncryptedCredentials()).isEqualTo("encrypted-id");
        assertThat(stored.getValue().getRegion()).isEqualTo(REGION);
    }

    // -- remembered identifier ----------------------------------------------

    private SidecarSession storedSession(String encryptedCredentials) {
        return storedSession(encryptedCredentials, REGION);
    }

    private SidecarSession storedSession(String encryptedCredentials, BanquePopulaireRegion region) {
        SidecarSession session = SidecarSession.createBanquePopulaire(
            FamilyMember.builder().id(7L).displayName("Owner").build(), "encrypted-state", T0, region);
        session.setEncryptedCredentials(encryptedCredentials);
        return session;
    }

    @Test
    void initiateAuth_withoutAnIdentifierReusesTheRememberedOneAndItsRegion() {
        when(sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.of(storedSession("encrypted-id", BanquePopulaireRegion.SUD)));
        when(encryption.decrypt("encrypted-id")).thenReturn("{\"customerId\":\"" + CUSTOMER_ID + "\"}");
        when(port.initiateAuth(CUSTOMER_ID, BanquePopulaireRegion.SUD))
            .thenReturn(new BanquePopulairePort.InitiateResult("proc-1", keypad(), 90));

        service.initiateAuth(null, null, 7L);

        verify(port).initiateAuth(CUSTOMER_ID, BanquePopulaireRegion.SUD);
    }

    @Test
    void initiateAuth_withoutAnIdentifierAlwaysUsesTheRememberedRegion() {
        when(sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.of(storedSession("encrypted-id", BanquePopulaireRegion.SUD)));
        when(encryption.decrypt("encrypted-id")).thenReturn("{\"customerId\":\"" + CUSTOMER_ID + "\"}");
        when(port.initiateAuth(CUSTOMER_ID, BanquePopulaireRegion.SUD))
            .thenReturn(new BanquePopulairePort.InitiateResult("proc-1", keypad(), 90));

        service.initiateAuth(null, BanquePopulaireRegion.VAL_DE_FRANCE, 7L);

        verify(port).initiateAuth(CUSTOMER_ID, BanquePopulaireRegion.SUD);
        verify(port, never()).initiateAuth(CUSTOMER_ID, BanquePopulaireRegion.VAL_DE_FRANCE);
    }

    @Test
    void initiateAuth_withoutAnIdentifierAndNoneRememberedRefusesWithoutCallingTheBank() {
        when(sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiateAuth(null, REGION, 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.INVALID_CREDENTIALS.name()));
        verify(port, never()).initiateAuth(any(), any());
    }

    @Test
    void initiateAuth_forgetsARememberedIdentifierTheBankRejects() {
        SidecarSession session = storedSession("encrypted-id");
        when(sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted-id")).thenReturn("{\"customerId\":\"" + CUSTOMER_ID + "\"}");
        when(port.initiateAuth(CUSTOMER_ID, REGION)).thenThrow(new SyncException(
            "The identifier was refused", null, SidecarErrorCode.INVALID_CREDENTIALS.name()));

        assertThatThrownBy(() -> service.initiateAuth(null, null, 7L)).isInstanceOf(SyncException.class);

        assertThat(session.getEncryptedCredentials()).isNull();
        verify(sessionRepository).save(session);
    }

    @Test
    void initiateAuth_aRejectionKeepsAnIdentifierAConcurrentLoginJustStored() {
        SidecarSession session = storedSession("encrypted-id");
        when(sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted-id")).thenReturn("{\"customerId\":\"" + CUSTOMER_ID + "\"}");
        when(port.initiateAuth(CUSTOMER_ID, REGION)).thenAnswer(call -> {
            // Another login succeeds while the bank answers this one: a new identifier is stored.
            session.setEncryptedCredentials("encrypted-newer-id");
            throw new SyncException("The identifier was refused", null,
                SidecarErrorCode.INVALID_CREDENTIALS.name());
        });

        assertThatThrownBy(() -> service.initiateAuth(null, null, 7L)).isInstanceOf(SyncException.class);

        assertThat(session.getEncryptedCredentials()).isEqualTo("encrypted-newer-id");
        verify(sessionRepository, never()).save(any());
        verify(sessionRepository).findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L);
        verify(sessionRepository).findByBankAndMemberIdForUpdate(SidecarBank.BANQUE_POPULAIRE, 7L);
        verifyNoMoreInteractions(sessionRepository);
    }

    @Test
    void initiateAuth_keepsTheRememberedIdentifierOnATransientFailure() {
        SidecarSession session = storedSession("encrypted-id");
        when(sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted-id")).thenReturn("{\"customerId\":\"" + CUSTOMER_ID + "\"}");
        when(port.initiateAuth(CUSTOMER_ID, REGION)).thenThrow(new SyncException(
            "Sidecar down", null, SidecarErrorCode.UPSTREAM_UNAVAILABLE.name()));

        assertThatThrownBy(() -> service.initiateAuth(null, null, 7L)).isInstanceOf(SyncException.class);

        assertThat(session.getEncryptedCredentials()).isEqualTo("encrypted-id");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void storeSession_withoutANewIdentifierKeepsTheRememberedOne() {
        arrangeStore("cookies-json");
        when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.of(storedSession("encrypted-id")));

        service.storeSession("cookies-json", REGION, 7L);

        ArgumentCaptor<SidecarSession> stored = ArgumentCaptor.forClass(SidecarSession.class);
        verify(sessionRepository).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getEncryptedCredentials()).isEqualTo("encrypted-id");
        assertThat(stored.getValue().getRegion()).isEqualTo(REGION);
    }

    @Test
    void status_saysAnIdentifierIsRememberedWithoutExposingIt() {
        when(sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.of(storedSession("encrypted-id")));
        when(encryption.decrypt("encrypted-id")).thenReturn("{\"customerId\":\"" + CUSTOMER_ID + "\"}");

        BanquePopulaireSyncService.SessionStatusResponse status = service.getStatus(7L);

        assertThat(status.remembered()).isTrue();
        assertThat(status.toString()).doesNotContain("encrypted-id").doesNotContain(CUSTOMER_ID);
        verify(sessionRepository).findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L);
        verifyNoMoreInteractions(sessionRepository);
    }

    @Test
    void status_doesNotRememberAnIdentifierThatCannotBeRead() {
        when(sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.of(storedSession("encrypted-id")));
        when(encryption.decrypt("encrypted-id")).thenThrow(new IllegalStateException("wrong key"));

        assertThat(service.getStatus(7L).remembered()).isFalse();

        // a GET never writes: the unreadable value is only forgotten by the next initiate
        verify(sessionRepository).findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L);
        verifyNoMoreInteractions(sessionRepository);
        verifyNoInteractions(txTemplate);
    }

    @Test
    void status_doesNotRememberAStoredValueWithoutAnIdentifier() {
        when(sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.of(storedSession("encrypted-id")));
        when(encryption.decrypt("encrypted-id")).thenReturn("{\"customerId\":\" \"}");

        assertThat(service.getStatus(7L).remembered()).isFalse();
        verifyNoMoreInteractions(sessionRepository);
    }

    @Test
    void status_doesNotRememberWhenNothingIsStored() {
        when(sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(Optional.of(storedSession(null)));

        assertThat(service.getStatus(7L).remembered()).isFalse();
        verifyNoInteractions(encryption);
    }

    // -- disconnect while a login is in flight -------------------------------

    @Test
    void clearSession_deletesTheRowAndTheRememberedIdentifierWithIt() {
        SidecarSession session = storedSession("encrypted-id");
        when(sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.BANQUE_POPULAIRE, 7L))
            .thenReturn(Optional.of(session));

        assertThat(service.clearSession(7L)).isTrue();

        verify(sessionRepository).delete(session);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void clearSession_cancelsALoginWaitingOnThePhoneApproval() {
        pushPending("proc-1");

        service.clearSession(7L);

        assertThatThrownBy(() -> service.completeAuth("proc-1", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, never()).completeAuth(any());
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void clearSession_whileTheApprovalIsBeingAwaitedStoresNothing() {
        pushPending("proc-1");
        when(port.completeAuth("proc-1")).thenAnswer(call -> {
            // The user disconnects while the sidecar waits on the phone, then approves.
            service.clearSession(7L);
            return "cookies-json";
        });
        arrangeStore("cookies-json");

        assertThatThrownBy(() -> service.completeAuth("proc-1", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));

        verify(sessionRepository, never()).saveAndFlush(any());
        verify(sessionRepository, never()).delete(any());
        verifyNoInteractions(encryption);
    }

    @Test
    void clearSession_whileTheKeypadIsBeingSubmittedLeavesNothingToComplete() {
        challenge("proc-1", 90);
        service.initiateAuth(CUSTOMER_ID, REGION, 7L);
        org.mockito.Mockito.doAnswer(call -> {
            service.clearSession(7L);
            return null;
        }).when(port).submitKeypad("proc-1", POSITIONS);

        assertThatThrownBy(() -> service.submitKeypad("proc-1", POSITIONS, 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        assertThatThrownBy(() -> service.completeAuth("proc-1", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, never()).completeAuth(any());
    }

    @Test
    void clearSession_whileTheLoginIsBeingInitiatedRegistersNothing() {
        when(port.initiateAuth(CUSTOMER_ID, REGION)).thenAnswer(call -> {
            service.clearSession(7L);
            return new BanquePopulairePort.InitiateResult("proc-1", keypad(), 90);
        });

        assertThatThrownBy(() -> service.initiateAuth(CUSTOMER_ID, REGION, 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        assertThatThrownBy(() -> service.submitKeypad("proc-1", POSITIONS, 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, never()).submitKeypad(any(), any());
    }

    @Test
    void clearSession_doesNotCancelAnotherMembersLoginNorTheNextOne() {
        pushPending("proc-1");
        service.clearSession(8L);
        when(port.completeAuth("proc-1")).thenReturn("cookies-json");
        arrangeStore("cookies-json");

        service.completeAuth("proc-1", 7L);
        verify(sessionRepository).saveAndFlush(any(SidecarSession.class));

        // a login started after the disconnect is a new attempt and can be stored
        service.clearSession(7L);
        pushPending("proc-2");
        when(port.completeAuth("proc-2")).thenReturn("cookies-json");

        service.completeAuth("proc-2", 7L);
        verify(sessionRepository, org.mockito.Mockito.times(2)).saveAndFlush(any(SidecarSession.class));
    }

    @Test
    void storeSession_withoutALoginInFlightIsNotCancelledByAnEarlierDisconnect() {
        arrangeStore("cookies-json");
        service.clearSession(7L);

        service.storeSession("cookies-json", REGION, 7L);

        verify(sessionRepository).saveAndFlush(any(SidecarSession.class));
    }

    @Test
    void completeAuth_refusesAProcessIdItNeverIssuedWithoutCallingTheSidecar() {
        assertThatThrownBy(() -> service.completeAuth("unknown", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));

        verify(port, never()).completeAuth(any());
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void completeAuth_refusesAnotherMembersProcessAndLeavesItUsableByItsOwner() {
        pushPending("proc-1");

        assertThatThrownBy(() -> service.completeAuth("proc-1", 8L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
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
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, never()).completeAuth(any());
    }

    @Test
    void completeAuth_isSingleUseEvenWhenTheSidecarFails() {
        pushPending("proc-1");
        when(port.completeAuth("proc-1")).thenThrow(new SyncException(
            "App validation timed out", null, SidecarErrorCode.APP_VALIDATION_TIMEOUT.name()));

        assertThatThrownBy(() -> service.completeAuth("proc-1", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.APP_VALIDATION_TIMEOUT.name()));
        // the second call is refused locally: no replay of /complete
        assertThatThrownBy(() -> service.completeAuth("proc-1", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));

        verify(port, org.mockito.Mockito.times(1)).completeAuth("proc-1");
    }

    @Test
    void completeAuth_leavesAnExistingValidSessionUntouchedWhenTheLoginFails() {
        pushPending("proc-1");
        when(port.completeAuth("proc-1")).thenThrow(new SyncException(
            "Refused", null, SidecarErrorCode.INVALID_CREDENTIALS.name()));

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
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED.name()));

        verify(sessionRepository, never()).delete(any());
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void theServiceKeepsNoFieldThatCouldHoldThePassword() {
        // The pending logins live in the shared engine, so it is checked too.
        for (Class<?> owner : List.of(BanquePopulaireSyncService.class, BpceSyncEngine.class)) {
            for (var field : owner.getDeclaredFields()) {
                assertThat(field.getName().toLowerCase()).as(owner.getSimpleName() + "." + field.getName())
                    .doesNotContain("password").doesNotContain("credential")
                    .doesNotContain("customer").doesNotContain("positions");
            }
            for (var type : owner.getDeclaredClasses()) {
                for (var component : type.getRecordComponents() == null
                    ? new java.lang.reflect.RecordComponent[0] : type.getRecordComponents()) {
                    assertThat(component.getName().toLowerCase()).as(type.getSimpleName() + "." + component.getName())
                        .doesNotContain("password").doesNotContain("positions");
                    // Only the identifier read back from the session carries it: the pending
                    // login keeps it inside the session factory, never in a field of its own.
                    if (component.getName().toLowerCase().contains("customer")) {
                        assertThat(type.getSimpleName()).isEqualTo("StoredLogin");
                    }
                }
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
