package com.picsou.service;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.FamilyMember;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.port.BanquePopulairePort;
import com.picsou.port.BanquePopulaireRegion;
import com.picsou.port.SidecarErrorCode;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SidecarSessionRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.mockito.Mockito.when;

/** Several Banque Populaire connections for one member: which row a login, a sync or a disconnect acts on. */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class BanquePopulaireMultiConnectionTest {

    private static final String FIRST_ID = "11111111";
    private static final String SECOND_ID = "22222222";
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

    private final List<Runnable> submitted = new ArrayList<>();
    private BanquePopulaireSyncService service;

    @BeforeEach
    void setUp() {
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        }).when(txTemplate).execute(any(TransactionCallback.class));
        service = new BanquePopulaireSyncService(
            port, sessionRepository, accountRepository, transactionRepository,
            memberRepository, debtRepository, accountService, categorizationService, categoryService, encryption,
            txTemplate, submitted::add, org.mockito.Mockito.mock(CaisseEpargnePerService.class),
            logoResolver, Clock.fixed(T0, ZoneOffset.UTC));
        lenient().when(encryption.decrypt("enc-first")).thenReturn(credentials(FIRST_ID));
        lenient().when(encryption.decrypt("enc-second")).thenReturn(credentials(SECOND_ID));
        lenient().when(encryption.decrypt("encrypted")).thenReturn("plain-state");
    }

    private static String credentials(String customerId) {
        return "{\"customerId\":\"" + customerId + "\"}";
    }

    private static BanquePopulairePort.Keypad keypad() {
        return new BanquePopulairePort.Keypad(Collections.nCopies(10, IMAGE), 5);
    }

    private static FamilyMember member() {
        return FamilyMember.builder().id(7L).displayName("Owner").build();
    }

    private static SidecarSession row(long id, String encryptedCredentials, BanquePopulaireRegion region) {
        SidecarSession session = SidecarSession.builder()
            .id(id)
            .bank(SidecarBank.BANQUE_POPULAIRE)
            .member(member())
            .sessionState("encrypted")
            .active(true)
            .syncStatus(SidecarSyncStatus.IDLE)
            .region(region)
            .build();
        session.setEncryptedCredentials(encryptedCredentials);
        return session;
    }

    private SidecarSession first() {
        return row(3L, "enc-first", REGION);
    }

    private SidecarSession second() {
        return row(4L, "enc-second", REGION);
    }

    private void rows(SidecarSession... rows) {
        lenient().when(sessionRepository.findAllByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, 7L)).thenReturn(List.of(rows));
        for (SidecarSession row : rows) {
            lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(row.getId(), 7L)).thenReturn(Optional.of(row));
        }
    }

    /** A login from the keypad to the stored session. */
    private SidecarSession loginAndStore(String customerId, BanquePopulaireRegion region) {
        when(port.initiateAuth(customerId, region))
            .thenReturn(new BanquePopulairePort.InitiateResult("proc-1", keypad(), 90));
        when(port.completeAuth("proc-1")).thenReturn("cookies");
        lenient().when(memberRepository.findById(7L)).thenReturn(Optional.of(member()));
        lenient().when(encryption.encrypt(anyString())).thenReturn("encrypted-new");
        lenient().when(sessionRepository.saveAndFlush(any(SidecarSession.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        service.initiateAuth(customerId, region, 7L);
        service.submitKeypad("proc-1", POSITIONS, 7L);
        service.completeAuth("proc-1", 7L);

        ArgumentCaptor<SidecarSession> stored = ArgumentCaptor.forClass(SidecarSession.class);
        verify(sessionRepository).saveAndFlush(stored.capture());
        return stored.getValue();
    }

    // -- login ----------------------------------------------------------------

    @Test
    void initiateAuth_aSecondLoginWithADifferentIdentifierAddsARow() {
        rows(first());

        SidecarSession stored = loginAndStore(SECOND_ID, REGION);

        assertThat(stored.getSessionState()).isEqualTo("encrypted-new");
        verify(sessionRepository, never()).delete(any());
        verify(sessionRepository, never()).findByIdAndMemberIdForUpdate(any(), any());
    }

    @Test
    void initiateAuth_theSameIdentifierReplacesItsRow() {
        SidecarSession existing = first();
        rows(existing, second());

        loginAndStore(FIRST_ID, REGION);

        verify(sessionRepository).delete(existing);
        verify(sessionRepository, never()).delete(second());
    }

    @Test
    void initiateAuth_theSameIdentifierAtAnotherRegionAddsARow() {
        SidecarSession existing = first();
        rows(existing);

        loginAndStore(FIRST_ID, BanquePopulaireRegion.NORD);

        verify(sessionRepository, never()).delete(any());
        verify(sessionRepository, never()).findByIdAndMemberIdForUpdate(any(), any());
    }

    @Test
    void initiateAuth_aBlankIdentifierWithTwoRowsRefusesWithoutCallingTheSidecar() {
        rows(first(), second());

        assertThatThrownBy(() -> service.initiateAuth(null, null, 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.INVALID_CREDENTIALS.name()));

        verify(port, never()).initiateAuth(anyString(), any());
    }

    // -- sync -----------------------------------------------------------------

    @Test
    void queueSync_queuesEveryActiveRow() {
        SidecarSession inactive = row(5L, "enc-first", REGION);
        ReflectionTestUtils.setField(inactive, "active", false);
        SidecarSession a = first();
        SidecarSession b = second();
        rows(a, b, inactive);

        service.queueSync(7L);

        assertThat(submitted).hasSize(2);
        assertThat(a.getSyncStatus()).isEqualTo(SidecarSyncStatus.QUEUED);
        assertThat(b.getSyncStatus()).isEqualTo(SidecarSyncStatus.QUEUED);
        assertThat(inactive.getSyncStatus()).isEqualTo(SidecarSyncStatus.IDLE);
    }

    // -- disconnect -----------------------------------------------------------

    @Test
    void clearSession_withASessionIdDeletesOnlyThatRow() {
        SidecarSession a = first();
        SidecarSession b = second();
        rows(a, b);

        assertThat(service.clearSession(7L, 4L)).isTrue();

        verify(sessionRepository).delete(b);
        verify(sessionRepository, never()).delete(a);
    }

    // -- status ---------------------------------------------------------------

    @Test
    void getStatus_oneRunningAndOneFailedAggregatesToRunningWithoutAnIdOrAHint() {
        SidecarSession running = first();
        ReflectionTestUtils.setField(running, "syncStatus", SidecarSyncStatus.RUNNING);
        SidecarSession failed = second();
        ReflectionTestUtils.setField(failed, "syncStatus", SidecarSyncStatus.FAILED);
        rows(running, failed);

        BanquePopulaireSyncService.SessionStatusResponse status = service.getStatus(7L);

        assertThat(status.syncStatus()).isEqualTo(SidecarSyncStatus.RUNNING);
        assertThat(status.id()).isNull();
        assertThat(status.identifierHint()).isNull();
        assertThat(status.remembered()).isFalse();
        assertThat(service.listStatuses(7L)).extracting(r -> r.id()).containsExactly(3L, 4L);
    }
}
