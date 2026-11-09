package com.picsou.service;

import com.picsou.adapter.LbpAdapter;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.LbpSessionRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The keypad the sidecar returned must be exactly ten distinct digits before it reaches the UI. */
@ExtendWith(MockitoExtension.class)
class LbpSyncServiceAuthTest {
    private static final long MEMBER = 7L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC);

    @Mock LbpAdapter adapter;
    @Mock LbpSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountService accountService;
    @Mock SavingsAutoConfigService savingsAutoConfig;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock Executor executor;

    private LbpSyncService service;

    @BeforeEach
    void setUp() {
        service = new LbpSyncService(adapter, sessionRepository, accountRepository, transactionRepository,
            memberRepository, accountService, savingsAutoConfig, encryption, txTemplate, executor, CLOCK);
    }

    private void keypad(List<String> keys) {
        when(adapter.initiateAuth("1234567890")).thenReturn(
            new LbpAdapter.InitiateResult("proc-1", new LbpAdapter.Keypad(keys, 5), 300));
    }

    @Test
    void acceptsTenDistinctDigitsAndReturnsThemToTheUi() {
        keypad(List.of("5", "3", "9", "8", "6", "1", "0", "7", "2", "4"));

        LbpSyncService.InitiateResponse response = service.initiateAuth("1234567890", MEMBER);

        assertThat(response.processId()).isEqualTo("proc-1");
        assertThat(response.keypad().keys()).containsExactly("5", "3", "9", "8", "6", "1", "0", "7", "2", "4");
        assertThat(response.expiresInSeconds()).isEqualTo(300);
    }

    @Test
    void completeAuth_pastTheApprovalWindow_expiresWithoutCallingTheSidecar() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-06T10:00:00Z"));
        LbpSyncService timed = new LbpSyncService(adapter, sessionRepository, accountRepository,
            transactionRepository, memberRepository, accountService, savingsAutoConfig,
            encryption, txTemplate, executor, clock);
        List<String> keys = List.of("5", "3", "9", "8", "6", "1", "0", "7", "2", "4");
        when(adapter.initiateAuth("1234567890")).thenReturn(
            new LbpAdapter.InitiateResult("proc-1", new LbpAdapter.Keypad(keys, 5), 300));
        LbpSyncService.InitiateResponse response = timed.initiateAuth("1234567890", MEMBER);
        timed.submitKeypad(response.processId(), List.of(0, 1, 2, 3, 4, 5), MEMBER);
        clock.advanceSeconds(181);

        assertThatThrownBy(() -> timed.completeAuth(response.processId(), MEMBER))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo("AUTH_ATTEMPT_EXPIRED"));

        verify(adapter, never()).completeAuth(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void evictExpiredPendingLogins_dropsOnlyDeadAttempts() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-06T10:00:00Z"));
        LbpSyncService timed = new LbpSyncService(adapter, sessionRepository, accountRepository,
            transactionRepository, memberRepository, accountService, savingsAutoConfig,
            encryption, txTemplate, executor, clock);
        List<String> keys = List.of("5", "3", "9", "8", "6", "1", "0", "7", "2", "4");
        when(adapter.initiateAuth("1234567890")).thenReturn(
            new LbpAdapter.InitiateResult("proc-1", new LbpAdapter.Keypad(keys, 5), 300),
            new LbpAdapter.InitiateResult("proc-2", new LbpAdapter.Keypad(keys, 5), 300));
        timed.initiateAuth("1234567890", MEMBER);
        clock.advanceSeconds(60);
        timed.initiateAuth("1234567890", MEMBER);
        clock.advanceSeconds(250);

        timed.evictExpiredPendingLogins();

        // proc-1 expired and was swept: gone, not merely stale.
        assertThatThrownBy(() -> timed.submitKeypad("proc-1", List.of(0, 1, 2, 3, 4, 5), MEMBER))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo("AUTH_ATTEMPT_EXPIRED"));
        // proc-2 is still live and usable.
        timed.submitKeypad("proc-2", List.of(0, 1, 2, 3, 4, 5), MEMBER);
        verify(adapter).submitKeypad("proc-2", List.of(0, 1, 2, 3, 4, 5));
        verify(adapter, never()).submitKeypad(org.mockito.ArgumentMatchers.eq("proc-1"),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void clearingTheSessionCancelsAPendingLogin() {
        keypad(List.of("5", "3", "9", "8", "6", "1", "0", "7", "2", "4"));
        LbpSyncService.InitiateResponse response = service.initiateAuth("1234567890", MEMBER);
        service.submitKeypad(response.processId(), List.of(0, 1, 2, 3, 4, 5), MEMBER);

        service.clearSession(MEMBER);

        assertThatThrownBy(() -> service.completeAuth(response.processId(), MEMBER))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo("AUTH_ATTEMPT_EXPIRED"));
    }

    @Test
    void aDisconnectDuringTheBrowserLaunchCancelsTheAttempt() {
        when(adapter.initiateAuth("1234567890")).thenAnswer(invocation -> {
            service.clearSession(MEMBER);
            return new LbpAdapter.InitiateResult("proc-1",
                new LbpAdapter.Keypad(List.of("5", "3", "9", "8", "6", "1", "0", "7", "2", "4"), 5), 300);
        });

        assertThatThrownBy(() -> service.initiateAuth("1234567890", MEMBER))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo("AUTH_ATTEMPT_EXPIRED"));

        // Nothing was registered: the keypad cannot be used to drive the abandoned login.
        assertThatThrownBy(() -> service.submitKeypad("proc-1", List.of(0, 1, 2, 3, 4, 5), MEMBER))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo("AUTH_ATTEMPT_EXPIRED"));
    }

    @Test
    void refusesADuplicateNonDigitOrIncompleteKeypad() {
        record Case(String name, List<String> keys) {}
        for (Case c : List.of(
            new Case("duplicate", List.of("5", "5", "9", "8", "6", "1", "0", "7", "2", "4")),
            new Case("non-digit", List.of("5", "3", "9", "8", "6", "1", "0", "7", "2", "x")),
            new Case("incomplete", List.of("5", "3", "9", "8", "6", "1", "0", "7", "2")),
            new Case("too-many", List.of("5", "3", "9", "8", "6", "1", "0", "7", "2", "4", "1")))) {
            keypad(c.keys());

            assertThatThrownBy(() -> service.initiateAuth("1234567890", MEMBER))
                .as(c.name())
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo("KEYPAD_CHANGED"));
        }
    }

    @Test
    void refusesAKeypadWiderThanFiveColumns() {
        when(adapter.initiateAuth("1234567890")).thenReturn(
            new LbpAdapter.InitiateResult("proc-1",
                new LbpAdapter.Keypad(List.of("5", "3", "9", "8", "6", "1", "0", "7", "2", "4"), 4), 300));

        assertThatThrownBy(() -> service.initiateAuth("1234567890", MEMBER))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo("KEYPAD_CHANGED"));
    }

    @Test
    void aDuplicateKeypadCallKeepsTheApprovalWaitingForComplete() {
        keypad(List.of("5", "3", "9", "8", "6", "1", "0", "7", "2", "4"));
        LbpSyncService.InitiateResponse response = service.initiateAuth("1234567890", MEMBER);
        service.submitKeypad(response.processId(), List.of(0, 1, 2, 3, 4, 5), MEMBER);

        assertThatThrownBy(() -> service.submitKeypad(response.processId(), List.of(0, 1, 2, 3, 4, 5), MEMBER))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo("AUTH_ATTEMPT_EXPIRED"));

        // The approval-phase entry survived the duplicate: /complete proceeds past the
        // pending lookup and fails on the missing session state, not on a lost login.
        assertThatThrownBy(() -> service.completeAuth(response.processId(), MEMBER))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo("UPSTREAM_FORMAT_CHANGED"));
    }

    @Test
    void aForeignProbeDoesNotBurnTheOwnersAttempt() {
        keypad(List.of("5", "3", "9", "8", "6", "1", "0", "7", "2", "4"));
        LbpSyncService.InitiateResponse response = service.initiateAuth("1234567890", MEMBER);

        assertThatThrownBy(() -> service.completeAuth(response.processId(), 999L))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo("AUTH_ATTEMPT_EXPIRED"));

        // The owner can still submit their keypad afterwards.
        service.submitKeypad(response.processId(), List.of(0, 1, 2, 3, 4, 5), MEMBER);
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
