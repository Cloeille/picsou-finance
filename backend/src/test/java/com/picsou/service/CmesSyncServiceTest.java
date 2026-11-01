package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.CmesSession;
import com.picsou.model.CmesSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.CmesPort;
import com.picsou.port.SidecarInitiate;
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
class CmesSyncServiceTest {
    private static final String PEG_ID = "cmes_12345_PEG";
    private static final String LABEL_ID = "cmes_12345_plan-d-pargne-groupe-0123456789abcdef";
    private static final String PEG_SHARED_ID = "cmes_12345_PEG-plan-d-pargne-groupe";
    private static final String PEG_NAME = "Plan d'épargne groupe — ACME INDUSTRIES";
    private static final String PEE_ID = "cmes_12345_PEE";
    private static final String PEE_LABEL = "Plan d'épargne entreprise";
    private static final String PEE_NAME = PEE_LABEL + " — ACME INDUSTRIES";
    private static final String PEE_NEW_ID = "cmes_12345_plan-d-pargne-entreprise-1111111111111111";
    private static final String PEI_LABEL = "Plan d'épargne interentreprises";
    private static final String PEI_ID = "cmes_12345_plan-d-pargne-interentreprises-2222222222222222";
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
        CmesPort.PlanData broken = new CmesPort.PlanData(PEG_ID, List.of(), "Plan d'épargne groupe", "PEG", null,
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
            .thenReturn(new SidecarInitiate(null, false, null, "fresh-state"));
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
            .thenReturn(new SidecarInitiate("process", true, "APP_PUSH", null));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CmesSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.SESSION_EXPIRED);
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
            .thenThrow(new SyncException("bad", null, SidecarErrorCode.INVALID_CREDENTIALS.name()));

        service.queueSync(7L);

        assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.INVALID_CREDENTIALS);
        assertThat(session.getEncryptedCredentials()).isNull();
        // Without credentials the next scheduled run could only fail: ask the saver now.
        assertThat(session.isActive()).isFalse();
    }

    @Test
    void expiredSessionWithoutStoredCredentialsDoesNotTryToLogIn() {
        CmesSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchPlans("plain-state")).thenThrow(expired());

        service.queueSync(7L);

        assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.SESSION_EXPIRED);
        verify(port, never()).reauthenticate(anyString(), anyString(), any());
    }

    @Test
    void aFirstLoginSendsNoPreviousSession() {
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        when(port.initiateAuth("12345678", "secret", null))
            .thenReturn(new SidecarInitiate("p1", true, "APP_PUSH", null));

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
            .thenReturn(new SidecarInitiate("p1", true, "APP_PUSH", null));

        service.initiateAuth("12345678", "secret", 7L);

        verify(port).initiateAuth("12345678", "secret", "plain-state");
    }

    @Test
    void theCredentialsAreStoredOnlyOnceTheConfirmationSucceeded() {
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        when(port.initiateAuth("12345678", "secret", null))
            .thenReturn(new SidecarInitiate("p2", true, "APP_PUSH", null));
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
            .thenThrow(new SyncException("transient", null, SidecarErrorCode.UPSTREAM_UNAVAILABLE.name()));

        service.initiateAuth("12345678", "secret", 7L);
        service.completeAuth("p2", null, 7L);

        verify(port).completeAuth("p2", null);
        verify(sessionRepository).saveAndFlush(any(CmesSession.class));
    }

    @Test
    void aMistypedSmsCodeKeepsTheCredentialsForTheNextTry() {
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        when(port.initiateAuth("12345678", "secret", null))
            .thenReturn(new SidecarInitiate("p4", true, "SMS", null));
        when(port.completeAuth("p4", "000000"))
            .thenThrow(new SyncException("wrong", null, SidecarErrorCode.INVALID_OTP.name()));
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
            .thenThrow(new SyncException("transient", null, SidecarErrorCode.UPSTREAM_UNAVAILABLE.name()));
        service.initiateAuth("12345678", "secret", 7L);

        assertThatThrownBy(() -> service.completeAuth("p4", "000000", 7L)).isInstanceOf(SyncException.class);
        service.completeAuth("p4", "123456", 7L);

        verify(sessionRepository).saveAndFlush(any(CmesSession.class));
    }

    @Test
    void anotherMemberCannotCompleteSomeoneElsesLogin() {
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        when(port.initiateAuth("12345678", "secret", null))
            .thenReturn(new SidecarInitiate("p3", true, "APP_PUSH", null));
        service.initiateAuth("12345678", "secret", 7L);

        assertThatThrownBy(() -> service.completeAuth("p3", null, 8L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, never()).completeAuth(anyString(), any());
    }

    @Test
    void aCompletionWithoutLivePendingCredentialsKeepsTheStoredSession() {
        // A backend restart forgets the pending credentials while the sidecar
        // still holds the attempt.
        assertThatThrownBy(() -> service.completeAuth("p5", null, 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, never()).completeAuth(anyString(), any());
        verify(sessionRepository, never()).delete(any());
    }

    @Test
    void aConfirmationThatLandsAfterTheConnectionWasRemovedStoresNothing() {
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        when(port.initiateAuth("12345678", "secret", null))
            .thenReturn(new SidecarInitiate("p7", true, "APP_PUSH", null));
        service.initiateAuth("12345678", "secret", 7L);
        // The last CMES account is deleted while the bank waits for the phone.
        when(port.completeAuth("p7", null)).thenAnswer(invocation -> {
            service.clearSession(7L);
            return "cookies";
        });
        arrangeStorableSession();

        assertThatThrownBy(() -> service.completeAuth("p7", null, 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(sessionRepository, never()).saveAndFlush(any());
        verify(encryption, never()).encrypt(anyString());
    }

    @Test
    void aRememberedDeviceLoginThatLandsAfterTheConnectionWasRemovedStoresNothing() {
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        when(port.initiateAuth("12345678", "secret", null)).thenAnswer(invocation -> {
            service.clearSession(7L);
            return new SidecarInitiate(null, false, null, "cookies");
        });
        arrangeStorableSession();

        assertThatThrownBy(() -> service.initiateAuth("12345678", "secret", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(sessionRepository, never()).saveAndFlush(any());
        verify(encryption, never()).encrypt(anyString());
    }

    @Test
    void aSecondFactorLoginThatLandsAfterTheConnectionWasRemovedKeepsNoCredentials() {
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        when(port.initiateAuth("12345678", "secret", null)).thenAnswer(invocation -> {
            service.clearSession(7L);
            return new SidecarInitiate("p7", true, "APP_PUSH", null);
        });

        assertThatThrownBy(() -> service.initiateAuth("12345678", "secret", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        // No pending credentials were kept: the attempt cannot be completed.
        assertThatThrownBy(() -> service.completeAuth("p7", null, 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, never()).completeAuth(anyString(), any());
    }

    @Test
    void aLoginStartedAfterTheConnectionWasRemovedIsStored() {
        service.clearSession(7L);
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        when(port.initiateAuth("12345678", "secret", null))
            .thenReturn(new SidecarInitiate(null, false, null, "cookies"));
        arrangeStorableSession();

        service.initiateAuth("12345678", "secret", 7L);

        verify(sessionRepository).saveAndFlush(any(CmesSession.class));
    }

    @Test
    void expiredPendingCredentialsAreEvictedWithoutWaitingForANewLogin() {
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.empty());
        when(port.initiateAuth("12345678", "secret", null))
            .thenReturn(new SidecarInitiate("p6", true, "SMS", null));
        when(port.completeAuth("p6", "000000"))
            .thenThrow(new SyncException("wrong", null, SidecarErrorCode.INVALID_OTP.name()));
        service.initiateAuth("12345678", "secret", 7L);

        // Still live: kept, and the attempt reaches the sidecar.
        service.evictPendingCredentialsExpiredAt(Instant.now());
        assertThatThrownBy(() -> service.completeAuth("p6", "000000", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.INVALID_OTP.name()));

        // Past its ten minutes: gone, with no new login needed to clear it.
        service.evictPendingCredentialsExpiredAt(Instant.now().plusSeconds(601));
        assertThatThrownBy(() -> service.completeAuth("p6", "000000", 7L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));
        verify(port, times(1)).completeAuth(anyString(), any());
    }

    @Test
    void aPlanACompleteFetchNoLongerListsIsClosedAtZero() {
        arrangeCommittableSync(pegPlan());
        Account closedPlan = Account.builder()
            .id(12L)
            .externalAccountId("cmes_12345_PEE")
            .provider(CmesSyncService.PROVIDER)
            .currentBalance(new BigDecimal("800.00"))
            .build();
        Account listedPlan = Account.builder()
            .id(11L)
            .externalAccountId(PEG_ID)
            .provider(CmesSyncService.PROVIDER)
            .currentBalance(new BigDecimal("1500.50"))
            .build();
        when(accountRepository.findAllByMemberIdAndProvider(7L, CmesSyncService.PROVIDER))
            .thenReturn(List.of(closedPlan, listedPlan));

        service.queueSync(7L);

        assertThat(closedPlan.getCurrentBalance()).isEqualByComparingTo("0");
        assertThat(closedPlan.getDeletedAt()).isNull();
        verify(holdingRepository).deleteByAccountId(12L);
        verify(accountService).upsertSnapshot(closedPlan, BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.now());
        // The listed plan only goes through the regular upsert.
        assertThat(listedPlan.getCurrentBalance()).isEqualByComparingTo("1500.50");
    }

    @Test
    void aFailedFetchClosesNoPlan() {
        arrangeQueuedSession(activeSession(member()));
        when(port.fetchPlans("plain-state"))
            .thenThrow(new SyncException("partial", null, SidecarErrorCode.PORTFOLIO_INCOMPLETE.name()));

        service.queueSync(7L);

        verify(accountRepository, never()).findAllByMemberIdAndProvider(any(), any());
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void anAccountStillCarryingThePlansEarlierIdMovesToItsNewIdWithItsHistory() {
        arrangeCommittableSync(pegPlan(LABEL_ID, List.of(PEG_ID, PEG_SHARED_ID)));
        Account imported = importedPlan(20L, PEG_ID, PEG_NAME, "1400.00");
        when(accountRepository.findAllByMemberIdAndProvider(7L, CmesSyncService.PROVIDER))
            .thenReturn(List.of(imported));

        service.queueSync(7L);

        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue()).isSameAs(imported);
        assertThat(imported.getExternalAccountId()).isEqualTo(LABEL_ID);
        // Moved, not closed: the plan is still listed under its new id.
        assertThat(imported.getCurrentBalance()).isEqualByComparingTo("1500.50");
        verify(accountService).upsertSnapshot(eq(imported), eq(new BigDecimal("1500.50")), any(), eq(LocalDate.now()));
    }

    @Test
    void aPlanNowAloneOfItsFamilyDoesNotTakeTheHistoryOfAPlanClosedSince() {
        // The last old sync listed only the PEE, as cmes_12345_PEE; it has closed
        // since, and the interentreprise plan that opened names the same old id.
        arrangeCommittableSync(peePlan(PEI_ID, PEI_LABEL));
        Account closedPee = importedPlan(20L, PEE_ID, PEE_NAME, "800.00");
        when(accountRepository.findAllByMemberIdAndProvider(7L, CmesSyncService.PROVIDER))
            .thenReturn(List.of(closedPee));

        service.queueSync(7L);

        assertThat(closedPee.getExternalAccountId()).isEqualTo(PEE_ID);
        assertThat(closedPee.getName()).isEqualTo(PEE_NAME);
        assertThat(closedPee.getCurrentBalance()).isEqualByComparingTo("0");
        verify(accountService).upsertSnapshot(closedPee, BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.now());
        verify(accountRepository, times(2)).save(accountCaptor.capture());
        Account opened = accountCaptor.getAllValues().getFirst();
        assertThat(opened).isNotSameAs(closedPee);
        assertThat(opened.getExternalAccountId()).isEqualTo(PEI_ID);
        assertThat(opened.getCurrentBalance()).isEqualByComparingTo("1500.50");
    }

    @Test
    void aPlanListedBesideANewPlanOfItsFamilyKeepsItsHistory() {
        // The last old sync listed the PEE alone, as cmes_12345_PEE; the
        // interentreprise plan opened since. Both name the old id.
        arrangeCommittableSync(peePlan(PEE_NEW_ID, PEE_LABEL), peePlan(PEI_ID, PEI_LABEL));
        Account pee = importedPlan(20L, PEE_ID, PEE_NAME, "800.00");
        when(accountRepository.findAllByMemberIdAndProvider(7L, CmesSyncService.PROVIDER))
            .thenReturn(List.of(pee));

        CmesSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(CmesSyncStatus.SUCCESS);
        assertThat(pee.getExternalAccountId()).isEqualTo(PEE_NEW_ID);
        assertThat(pee.getCurrentBalance()).isEqualByComparingTo("1500.50");
        // Moved, so not closed: no zero snapshot splits its history.
        verify(accountService, never()).upsertSnapshot(pee, BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.now());
        verify(accountRepository, times(2)).save(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues())
            .extracting(Account::getExternalAccountId)
            .containsExactly(PEE_NEW_ID, PEI_ID);
    }

    @Test
    void aPlanThatChangedIdOnceAlreadyTakesTheAccountSyncedLast() {
        arrangeCommittableSync(pegPlan(LABEL_ID, List.of(PEG_ID, PEG_SHARED_ID)));
        Account first = importedPlan(20L, PEG_ID, PEG_NAME, "0");
        first.setLastSyncedAt(Instant.parse("2026-10-24T06:00:00Z"));
        Account second = importedPlan(21L, PEG_SHARED_ID, PEG_NAME, "1400.00");
        second.setLastSyncedAt(Instant.parse("2026-10-31T06:00:00Z"));
        when(accountRepository.findAllByMemberIdAndProvider(7L, CmesSyncService.PROVIDER))
            .thenReturn(List.of(first, second));

        service.queueSync(7L);

        assertThat(second.getExternalAccountId()).isEqualTo(LABEL_ID);
        assertThat(second.getCurrentBalance()).isEqualByComparingTo("1500.50");
        assertThat(first.getExternalAccountId()).isEqualTo(PEG_ID);
    }

    @Test
    void anEarlierAccountTwoPlansMatchFailsTheSyncBeforeAnyWrite() {
        arrangeQueuedSession(activeSession(member()));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member()));
        when(port.fetchPlans("plain-state"))
            .thenReturn(List.of(pegPlan(LABEL_ID, List.of(PEG_ID)), pegPlan(PEG_SHARED_ID, List.of(PEG_ID))));
        Account imported = importedPlan(20L, PEG_ID, PEG_NAME, "1400.00");
        when(accountRepository.findAllByMemberIdAndProvider(7L, CmesSyncService.PROVIDER))
            .thenReturn(List.of(imported));

        CmesSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(CmesSyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(SidecarErrorCode.INVALID_DATA);
        assertThat(imported.getExternalAccountId()).isEqualTo(PEG_ID);
        assertThat(imported.getCurrentBalance()).isEqualByComparingTo("1400.00");
        verify(accountRepository, never()).save(any());
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void aPlanWhoseAccountWasDeletedUnderItsEarlierIdIsNotImportedAgain() {
        arrangeQueuedSession(activeSession(member()));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member()));
        when(port.fetchPlans("plain-state")).thenReturn(List.of(pegPlan(LABEL_ID, List.of(PEG_ID, PEG_SHARED_ID))));
        when(accountRepository.findByExternalAccountIdAndMemberId(any(), eq(7L))).thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByExternalAccountIdInAndNameAndMemberId(
            List.of(PEG_ID, PEG_SHARED_ID), PEG_NAME, 7L)).thenReturn(true);

        CmesSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(CmesSyncStatus.SUCCESS);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void aPlanDeletedUnderItsNewIdIsNotRevivedThroughAnEarlierAccount() {
        // The upgrade moved the newer of two earlier accounts to the new id and
        // left the older one at zero; the saver then deleted the moved account.
        arrangeQueuedSession(activeSession(member()));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member()));
        when(port.fetchPlans("plain-state")).thenReturn(List.of(pegPlan(LABEL_ID, List.of(PEG_ID, PEG_SHARED_ID))));
        when(accountRepository.findByExternalAccountIdAndMemberId(LABEL_ID, 7L)).thenReturn(Optional.empty());
        Account older = importedPlan(20L, PEG_ID, PEG_NAME, "0");
        when(accountRepository.findAllByMemberIdAndProvider(7L, CmesSyncService.PROVIDER))
            .thenReturn(List.of(older));
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(LABEL_ID, 7L)).thenReturn(true);

        CmesSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(CmesSyncStatus.SUCCESS);
        assertThat(older.getExternalAccountId()).isEqualTo(PEG_ID);
        assertThat(older.getCurrentBalance()).isEqualByComparingTo("0");
        verify(accountRepository, never()).save(any());
    }

    @Test
    void aScheduledResyncAsksForReauthenticationOnceTheSessionIsGone() {
        CmesSession session = activeSession(member());
        session.markQueued();
        session.markFailed(SidecarErrorCode.SESSION_EXPIRED, java.time.Instant.now());
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));

        SourceSyncResult result = service.resyncReporting(7L);

        assertThat(result.status()).isEqualTo(SourceSyncResult.Status.NEEDS_REAUTH);
        assertThat(result.source()).isEqualTo("cmes");
    }

    private SyncException expired() {
        return new SyncException("expired", null, SidecarErrorCode.SESSION_EXPIRED.name());
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

    /** Lenient: a login the disconnection cancelled never reaches these. */
    private void arrangeStorableSession() {
        lenient().when(memberRepository.findById(7L)).thenReturn(Optional.of(member()));
        lenient().when(encryption.encrypt(anyString())).thenReturn("encrypted");
        lenient().when(sessionRepository.saveAndFlush(any(CmesSession.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(port.fetchPlans("cookies"))
            .thenThrow(new SyncException("transient", null, SidecarErrorCode.UPSTREAM_UNAVAILABLE.name()));
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

    private Account importedPlan(Long id, String externalId, String name, String balance) {
        return Account.builder()
            .id(id)
            .externalAccountId(externalId)
            .name(name)
            .provider(CmesSyncService.PROVIDER)
            .currentBalance(new BigDecimal(balance))
            .build();
    }

    private CmesPort.PlanData pegPlan() {
        return pegPlan(PEG_ID, List.of());
    }

    private CmesPort.PlanData pegPlan(String externalId, List<String> legacyExternalIds) {
        return plan(externalId, legacyExternalIds, "Plan d'épargne groupe", "PEG");
    }

    /** A PEE-family plan with the two ids earlier builds could have given it. */
    private CmesPort.PlanData peePlan(String externalId, String label) {
        String slug = label.equals(PEE_LABEL) ? "plan-d-pargne-entreprise" : "plan-d-pargne-interentreprises";
        return plan(externalId, List.of(PEE_ID, PEE_ID + "-" + slug), label, "PEE");
    }

    private CmesPort.PlanData plan(String externalId, List<String> legacyExternalIds, String label, String kind) {
        return new CmesPort.PlanData(
            externalId,
            legacyExternalIds,
            label,
            kind,
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
