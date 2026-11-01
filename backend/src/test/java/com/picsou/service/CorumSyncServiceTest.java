package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.CorumSession;
import com.picsou.model.CorumSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.port.CorumPort;
import com.picsou.port.SidecarErrorCode;
import com.picsou.repository.CorumSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.ScpiPositionRepository;
import com.picsou.repository.ScpiProviderFundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class CorumSyncServiceTest {
    @Mock CorumPort port;
    @Mock CorumSessionRepository sessionRepository;
    @Mock ScpiPositionRepository positionRepository;
    @Mock ScpiPositionService positionService;
    @Mock FamilyMemberRepository memberRepository;
    @Mock CryptoEncryption encryption;
    @Mock AccountRepository accountRepository;
    @Mock ScpiProviderFundRepository fundRepository;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    CorumSyncService service;

    @BeforeEach
    void setUp() {
        lenient().when(sessionRepository.findMemberByIdForUpdate(7L))
            .thenReturn(Optional.of(FamilyMember.builder().id(7L).build()));
        lenient().doAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0))
            .doInTransaction(transactionStatus)).when(txTemplate).execute(any(TransactionCallback.class));
        lenient().doAnswer(inv -> {
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(transactionStatus);
            return null;
        }).when(txTemplate).executeWithoutResult(any());
        ScpiFundReconciler reconciler = new ScpiFundReconciler(
            positionRepository, positionService, accountRepository, fundRepository, memberRepository);
        service = new CorumSyncService(port, sessionRepository, memberRepository, encryption,
            txTemplate, Runnable::run, reconciler);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void queueSync_discardsSnapshotAfterDisconnectOrReplacement(boolean replace, boolean remoteFailure) throws Exception {
        FamilyMember member = FamilyMember.builder().id(7L).build();
        CorumSession old = CorumSession.builder().id(3L).member(member)
            .sessionState("encrypted").build();
        AtomicReference<CorumSession> current = new AtomicReference<>(old);
        when(sessionRepository.findByMemberIdForUpdate(7L))
            .thenAnswer(inv -> Optional.ofNullable(current.get()));
        when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L))
            .thenAnswer(inv -> Optional.ofNullable(current.get()).filter(s -> s.getId().equals(3L)));
        when(sessionRepository.findByMemberId(7L))
            .thenAnswer(inv -> Optional.ofNullable(current.get()));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");
        doAnswer(inv -> { current.set(null); return null; }).when(sessionRepository).delete(old);
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(port.fetchSnapshot("plain-state")).thenAnswer(inv -> {
            fetching.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            if (remoteFailure) {
                throw new SyncException("Expired while fetching", null, "SESSION_EXPIRED");
            }
            return new CorumPort.Snapshot("contract", "PP", "EUR", new BigDecimal("600"), null,
                true, List.of(new CorumPort.Holding("FUND", "Test fund", new BigDecimal("2"),
                    new BigDecimal("300"), null, null, null)));
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { service.queueSync(7L); } catch (Throwable ex) { failure.set(ex); }
        });
        worker.start();
        try {
            assertThat(fetching.await(5, TimeUnit.SECONDS)).isTrue();
            service.clearSession(7L);
            if (replace) {
                current.set(CorumSession.builder().id(4L).member(member)
                    .sessionState("new-session").build());
            }
            clearInvocations(sessionRepository);
        } finally {
            release.countDown();
            worker.join(5000);
        }
        assertThat(worker.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        var order = inOrder(sessionRepository);
        order.verify(sessionRepository).findMemberByIdForUpdate(7L);
        order.verify(sessionRepository).findByIdAndMemberIdForUpdate(3L, 7L);
        verifyNoInteractions(positionRepository, positionService);
        verify(sessionRepository, never()).save(any());
        assertThat(old.getSyncStatus()).isEqualTo(CorumSyncStatus.RUNNING);
        if (replace) {
            assertThat(current.get().getSyncStatus()).isEqualTo(CorumSyncStatus.IDLE);
        }
    }

    @org.junit.jupiter.api.Test
    void queueSync_refusesASnapshotWhoseDisplayedValuesMissTheEnvelope() {
        CorumSession session = activeSession();
        when(port.fetchSnapshot("plain-state")).thenReturn(new CorumPort.Snapshot(
            "contract", "PP", "EUR", new BigDecimal("600.00"), null, true,
            List.of(holding("US", "100.00", "150"))
        ));

        service.queueSync(7L);

        verifyNoInteractions(positionRepository, positionService);
        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.SidecarErrorCode.PORTFOLIO_INCOMPLETE);
    }

    @org.junit.jupiter.api.Test
    void queueSync_acceptsDisplayedTotalEvenWhenWithdrawalValueIsLower() {
        CorumSession session = activeSession();
        when(port.fetchSnapshot("plain-state")).thenReturn(new CorumPort.Snapshot(
            "contract", "PP", "EUR", new BigDecimal("340.00"), null, true,
            List.of(holding("US", "340.00", "150"))
        ));
        // A linked position always has its account (account_id is NOT NULL).
        com.picsou.model.ScpiPosition linked = com.picsou.model.ScpiPosition.builder()
            .account(com.picsou.model.Account.builder().id(11L).build())
            .corumFundCode("US")
            .build();
        when(positionRepository.findByMemberIdAndCorumFundCode(7L, "US"))
            .thenReturn(Optional.of(linked));

        service.queueSync(7L);

        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.SUCCESS);
        verify(positionService).applySyncedPosition(
            any(), eq(new BigDecimal("2")), eq(new BigDecimal("170")),
            eq(new BigDecimal("150")), eq(null)
        );
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"USD", "eur", ""})
    void queueSync_refusesASnapshotNotInEuros(String currency) {
        CorumSession session = activeSession();
        when(port.fetchSnapshot("plain-state")).thenReturn(new CorumPort.Snapshot(
            "contract", "PP", currency, new BigDecimal("340.00"), null, true,
            List.of(holding("US", "340.00", "150"))
        ));

        service.queueSync(7L);

        verifyNoInteractions(positionRepository, positionService);
        assertThat(session.getSyncStatus()).isEqualTo(CorumSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(com.picsou.port.SidecarErrorCode.INVALID_DATA);
    }

    private CorumSession activeSession() {
        FamilyMember member = FamilyMember.builder().id(7L).build();
        CorumSession session = CorumSession.builder().id(3L).member(member)
            .sessionState("encrypted").build();
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L)).thenReturn(Optional.of(session));
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");
        return session;
    }

    private static CorumPort.Holding holding(String code, String displayed, String withdrawal) {
        return new CorumPort.Holding(
            code, "CORUM " + code, new BigDecimal("2"),
            new BigDecimal(withdrawal), new BigDecimal("170"),
            new BigDecimal(displayed), LocalDate.of(2026, 9, 26)
        );
    }
}
