package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.FamilyMember;
import com.picsou.model.SofidySession;
import com.picsou.port.SofidyPort;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.ScpiPositionRepository;
import com.picsou.repository.SofidySessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class SofidyAuthSessionTest {
    @Mock SofidyPort port;
    @Mock SofidySessionRepository sessions;
    @Mock ScpiPositionRepository positions;
    @Mock ScpiPositionService positionService;
    @Mock FamilyMemberRepository members;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate tx;
    @Mock TransactionStatus transaction;
    SofidySyncService service;
    final Map<Long, SofidySession> stored = new HashMap<>();
    final AtomicLong sequence = new AtomicLong();

    @BeforeEach
    void setUp() {
        lenient().doAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0))
            .doInTransaction(transaction)).when(tx).execute(any(TransactionCallback.class));
        lenient().doAnswer(inv -> {
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(transaction);
            return null;
        }).when(tx).executeWithoutResult(any());
        lenient().when(members.findById(anyLong())).thenAnswer(inv -> Optional.of(
            FamilyMember.builder().id(inv.getArgument(0)).build()));
        lenient().when(sessions.findMemberByIdForUpdate(anyLong())).thenAnswer(inv -> Optional.of(
            FamilyMember.builder().id(inv.getArgument(0)).build()));
        lenient().when(encryption.encrypt(anyString())).thenAnswer(inv -> "encrypted:" + inv.getArgument(0));
        lenient().when(encryption.decrypt(anyString())).thenAnswer(inv ->
            ((String) inv.getArgument(0)).substring("encrypted:".length()));
        lenient().when(sessions.findByMemberIdForUpdate(anyLong())).thenAnswer(inv ->
            Optional.ofNullable(stored.get(inv.getArgument(0))));
        lenient().when(sessions.findByMemberId(anyLong())).thenAnswer(inv ->
            Optional.ofNullable(stored.get(inv.getArgument(0))));
        lenient().when(sessions.findByIdAndMemberIdForUpdate(anyLong(), anyLong())).thenAnswer(inv ->
            Optional.ofNullable(stored.get(inv.getArgument(1)))
                .filter(s -> s.getId().equals(inv.getArgument(0))));
        lenient().when(sessions.saveAndFlush(any())).thenAnswer(inv -> {
            SofidySession session = inv.getArgument(0);
            if (session.getId() == null) {
                ReflectionTestUtils.setField(session, "id", sequence.incrementAndGet());
            }
            stored.put(session.getMember().getId(), session);
            return session;
        });
        lenient().doAnswer(inv -> {
            SofidySession session = inv.getArgument(0);
            stored.remove(session.getMember().getId(), session);
            return null;
        }).when(sessions).delete(any());
        service = new SofidySyncService(port, sessions, positions, positionService, members,
            encryption, tx, command -> {});
    }

    @Test
    void completeAuth_refusesAnotherMembersProcessBeforeCallingSidecar() {
        when(port.initiateAuth("associate", "password"))
            .thenReturn(new SofidyPort.InitiateResult("process-A", true, "email", null));
        service.initiateAuth("associate", "password", 7L);

        assertThatThrownBy(() -> service.completeAuth("process-A", "123456", 8L))
            .isInstanceOf(SyncException.class)
            .extracting(ex -> ((SyncException) ex).getCode())
            .isEqualTo("AUTH_ATTEMPT_EXPIRED");
        verify(port, never()).completeAuth(any(), any());
        assertThat(stored).doesNotContainKey(8L);
    }

    @Test
    void completeAuth_acceptsOnlyBoundMemberThenConsumesAttempt() {
        initiatePending("process-A");
        when(port.completeAuth("process-A", "123456")).thenReturn("cookie-jar");

        assertThat(service.completeAuth("process-A", "123456", 7L).isActive()).isTrue();
        assertThat(stored.get(7L).isActive()).isTrue();
        assertThat(stored.get(7L).getSessionState()).isEqualTo("encrypted:cookie-jar");

        assertExpired(() -> service.completeAuth("process-A", "123456", 7L));
        verify(port, times(1)).completeAuth("process-A", "123456");
    }

    @Test
    void completeAuth_rejectsDisconnectedAttemptBeforeSidecar() {
        initiatePending("process-A");
        service.clearSession(7L);

        assertExpired(() -> service.completeAuth("process-A", "123456", 7L));
        verify(port, never()).completeAuth(any(), any());
        assertThat(stored).isEmpty();
    }

    @Test
    void completeAuth_rejectsReplacedProcessBeforeSidecar() {
        initiatePending("process-A");
        initiatePending("process-B");

        assertExpired(() -> service.completeAuth("process-A", "123456", 7L));
        verify(port, never()).completeAuth(any(), any());
        assertThat(stored.get(7L).isActive()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completeAuth_cannotRestoreDisconnectedOrReplacedSessionDuringRemoteCall(boolean replace) {
        initiatePending("process-A");
        when(port.completeAuth("process-A", "123456")).thenAnswer(inv -> {
            service.clearSession(7L);
            if (replace) {
                initiatePending("process-B");
            }
            clearInvocations(sessions);
            return "old-cookie-jar";
        });

        assertExpired(() -> service.completeAuth("process-A", "123456", 7L));

        verify(sessions, never()).saveAndFlush(any());
        verifyNoInteractions(positions, positionService);
        if (replace) {
            assertThat(stored.get(7L).isActive()).isFalse();
        } else {
            assertThat(stored).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initiateAuth_cannotRestoreSessionAfterDisconnectDuringRemoteCall(boolean immediate) {
        when(port.initiateAuth("associate", "password")).thenAnswer(inv -> {
            service.clearSession(7L);
            clearInvocations(sessions);
            return immediate
                ? new SofidyPort.InitiateResult(null, false, null, "old-cookie-jar")
                : new SofidyPort.InitiateResult("process-A", true, "email", null);
        });

        assertExpired(() -> service.initiateAuth("associate", "password", 7L));

        verify(sessions, never()).saveAndFlush(any());
        assertThat(stored).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initiateAuth_cannotOverwriteNewAttemptDuringRemoteCall(boolean immediate) {
        when(port.initiateAuth("new-associate", "new-password"))
            .thenReturn(new SofidyPort.InitiateResult("process-B", true, "email", null));
        when(port.initiateAuth("associate", "password")).thenAnswer(inv -> {
            service.initiateAuth("new-associate", "new-password", 7L);
            clearInvocations(sessions);
            return immediate
                ? new SofidyPort.InitiateResult(null, false, null, "old-cookie-jar")
                : new SofidyPort.InitiateResult("process-A", true, "email", null);
        });

        assertExpired(() -> service.initiateAuth("associate", "password", 7L));

        verify(sessions, never()).saveAndFlush(any());
        assertThat(stored.get(7L).isActive()).isFalse();
    }

    @Test
    void initiateAuth_storesImmediateSessionAndQueuesIt() {
        when(port.initiateAuth("associate", "password"))
            .thenReturn(new SofidyPort.InitiateResult(null, false, null, "cookie-jar"));

        assertThat(service.initiateAuth("associate", "password", 7L).isActive()).isTrue();
        assertThat(stored.get(7L).isActive()).isTrue();
        assertThat(stored.get(7L).getSessionState()).isEqualTo("encrypted:cookie-jar");
    }

    private void initiatePending(String processId) {
        when(port.initiateAuth("associate", "password"))
            .thenReturn(new SofidyPort.InitiateResult(processId, true, "email", null));
        assertThat(service.initiateAuth("associate", "password", 7L).isActive()).isFalse();
    }

    private void assertExpired(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(SyncException.class)
            .extracting(ex -> ((SyncException) ex).getCode()).isEqualTo("AUTH_ATTEMPT_EXPIRED");
    }
}
