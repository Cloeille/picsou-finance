package com.picsou.service;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.FamilyMember;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.port.BanquePopulaireRegion;
import com.picsou.port.SidecarErrorCode;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SidecarSessionRepository;
import com.picsou.service.sync.SourceSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static com.picsou.support.TestEntities.owner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scenarios every sidecar bank shares, run once per bank against its real sync service.
 * Only the shared collaborators are controlled; every bank-specific one is a plain mock, and the
 * bank's adapter fails any fetch, so no scenario depends on a bank's payload.
 *
 * <p>La Banque Postale is excluded: it keeps lbp_session and its own sync service until it
 * moves to sidecar_session in a follow-up.
 *
 * <p>BNP Paribas is excluded too: its session also lives in sidecar_session, but a login hand-off (keypad,
 * then the bank's strong authentication) and its own queue and restart recovery make its sync a
 * dedicated one, covered by BnpSyncServiceTest.
 *
 * <p>Natixis Interépargne is excluded too: its row lives in sidecar_session, but its sync is not
 * the shared cycle (a login hand-off with an e-mailed code, a refresh token rotated on every read,
 * its own queue and restart recovery), covered by NatixisSyncServiceTest.
 */
@SuppressWarnings("unchecked")
class SidecarSyncContractTest {

    private static final long MEMBER = 7L;
    private static final long SESSION = 3L;

    private static final Map<SidecarBank, Class<?>> SERVICES = Map.ofEntries(
        Map.entry(SidecarBank.AMEX, AmexSyncService.class),
        Map.entry(SidecarBank.AMUNDI, AmundiSyncService.class),
        Map.entry(SidecarBank.BANQUE_POPULAIRE, BanquePopulaireSyncService.class),
        Map.entry(SidecarBank.BOURSE_DIRECT, BourseDirectSyncService.class),
        Map.entry(SidecarBank.BOURSO, BoursoSyncService.class),
        Map.entry(SidecarBank.CAISSE_EPARGNE, CaisseEpargneSyncService.class),
        Map.entry(SidecarBank.CIC, CicSyncService.class),
        Map.entry(SidecarBank.CMES, CmesSyncService.class),
        Map.entry(SidecarBank.CORUM, CorumSyncService.class),
        Map.entry(SidecarBank.FORTUNEO, FortuneoSyncService.class),
        Map.entry(SidecarBank.SOFIDY, SofidySyncService.class),
        Map.entry(SidecarBank.TRADE_REPUBLIC, TradeRepublicSyncService.class)
    );

    /** The banks SidecarSyncRecovery does not run at boot, as before this refactor. */
    private static final Set<SidecarBank> NO_BOOT_RECOVERY = EnumSet.of(SidecarBank.CORUM, SidecarBank.SOFIDY);

    /** The banks the daily scheduler does not resync. CORUM joined it with its stored login (#452). */
    private static final Set<SidecarBank> NOT_SCHEDULED =
        EnumSet.of(SidecarBank.SOFIDY, SidecarBank.CAISSE_EPARGNE, SidecarBank.BANQUE_POPULAIRE);

    private SidecarSessionRepository sessions;
    private FamilyMemberRepository members;
    private CryptoEncryption encryption;
    private final List<Runnable> submitted = new ArrayList<>();
    private Executor executor;
    private Runnable onFetch;
    private FamilyMember member;

    @BeforeEach
    void setUp() {
        sessions = mock(SidecarSessionRepository.class);
        members = mock(FamilyMemberRepository.class);
        encryption = mock(CryptoEncryption.class);
        executor = submitted::add;
        onFetch = () -> {};
        member = owner();
        when(members.findById(MEMBER)).thenReturn(Optional.of(member));
        when(members.findByIdForUpdate(MEMBER)).thenReturn(Optional.of(member));
        when(members.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(member));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");
    }

    @ParameterizedTest
    @EnumSource(value = SidecarBank.class, names = {"LBP", "NATIXIS", "BNP"}, mode = EnumSource.Mode.EXCLUDE)
    void everySidecarBankHasAService(SidecarBank bank) {
        assertThat(SERVICES).containsKey(bank);
    }

    @ParameterizedTest
    @EnumSource(value = SidecarBank.class, names = {"LBP", "NATIXIS", "BNP"}, mode = EnumSource.Mode.EXCLUDE)
    void queueingWithoutAStoredSessionAsksForAReconnect(SidecarBank bank) {
        Bank service = service(bank);

        assertThatThrownBy(() -> service.queueSync())
            .isInstanceOf(SyncException.class)
            .extracting(ex -> ((SyncException) ex).getCode())
            .isEqualTo(SidecarErrorCode.SESSION_EXPIRED.name());
        assertThat(submitted).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = SidecarBank.class, names = {"LBP", "NATIXIS", "BNP"}, mode = EnumSource.Mode.EXCLUDE)
    void queueingAnInactiveSessionAsksForAReconnect(SidecarBank bank) {
        SidecarSession session = stored(bank);
        session.markQueued();
        session.markFailed(SidecarErrorCode.SESSION_EXPIRED, java.time.Instant.now());
        Bank service = service(bank);

        assertThatThrownBy(() -> service.queueSync())
            .isInstanceOf(SyncException.class)
            .extracting(ex -> ((SyncException) ex).getCode())
            .isEqualTo(SidecarErrorCode.SESSION_EXPIRED.name());
        assertThat(submitted).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = SidecarBank.class, names = {"LBP", "NATIXIS", "BNP"}, mode = EnumSource.Mode.EXCLUDE)
    void aSecondSyncIsNotStackedOnAnInFlightSession(SidecarBank bank) {
        for (SidecarSyncStatus inFlight : List.of(SidecarSyncStatus.QUEUED, SidecarSyncStatus.RUNNING)) {
            SidecarSession session = stored(bank);
            session.markQueued();
            if (inFlight == SidecarSyncStatus.RUNNING) session.markRunning(java.time.Instant.now());

            service(bank).queueSync();

            assertThat(submitted).as("%s while %s", bank, inFlight).isEmpty();
            assertThat(session.getSyncStatus()).isEqualTo(inFlight);
        }
    }

    @ParameterizedTest
    @EnumSource(value = SidecarBank.class, names = {"LBP", "NATIXIS", "BNP"}, mode = EnumSource.Mode.EXCLUDE)
    void anUpstreamFailureIsRecordedOnTheSessionAndKeepsItConnected(SidecarBank bank) {
        SidecarSession session = stored(bank);
        Bank service = service(bank);

        service.queueSync();
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.QUEUED);
        runSubmitted();

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(session.isActive()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = SidecarBank.class, names = {"LBP", "NATIXIS", "BNP"}, mode = EnumSource.Mode.EXCLUDE)
    void aFailureForASessionDisconnectedMidFlightIsNotWrittenBack(SidecarBank bank) {
        SidecarSession session = stored(bank);
        Bank service = service(bank);
        onFetch = () -> when(sessions.findByIdAndMemberIdForUpdate(SESSION, MEMBER)).thenReturn(Optional.empty());

        service.queueSync();
        runSubmitted();

        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.RUNNING);
        assertThat(session.getLastSyncError()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = SidecarBank.class, names = {"LBP", "NATIXIS", "BNP"}, mode = EnumSource.Mode.EXCLUDE)
    void aJobWhoseSessionWasReplacedBeforeItStartsNeverFetches(SidecarBank bank) {
        stored(bank);
        Bank service = service(bank);
        boolean[] fetched = {false};
        onFetch = () -> fetched[0] = true;

        service.queueSync();
        when(sessions.findByIdAndMemberIdForUpdate(SESSION, MEMBER)).thenReturn(Optional.empty());
        runSubmitted();

        assertThat(fetched[0]).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = SidecarBank.class, names = {"LBP", "NATIXIS", "BNP"}, mode = EnumSource.Mode.EXCLUDE)
    void anExecutorThatRefusesTheJobFailsTheQueuedSession(SidecarBank bank) {
        SidecarSession session = stored(bank);
        RejectedExecutionException rejection = new RejectedExecutionException("full");
        executor = job -> { throw rejection; };
        Bank service = service(bank);

        assertThatThrownBy(() -> service.queueSync())
            .isInstanceOf(SyncException.class)
            .hasCause(rejection)
            .extracting(ex -> ((SyncException) ex).getCode())
            .isEqualTo(SidecarErrorCode.INTERNAL_ERROR.name());
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.INTERNAL_ERROR);
    }

    @ParameterizedTest
    @EnumSource(value = SidecarBank.class, names = {"LBP", "NATIXIS", "BNP"}, mode = EnumSource.Mode.EXCLUDE)
    void anUnreadableStoredSessionFailsAsTheBankDeclares(SidecarBank bank) {
        SidecarSession session = stored(bank);
        when(encryption.decrypt("encrypted")).thenThrow(new IllegalStateException("bad tag"));
        Bank service = service(bank);

        if (bank.declares(SidecarErrorCode.INVALID_SESSION_STATE)) {
            assertThatThrownBy(() -> service.queueSync())
                .extracting(ex -> ((SyncException) ex).getCode())
                .isEqualTo(SidecarErrorCode.INVALID_SESSION_STATE.name());
            assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
            assertThat(session.getLastSyncError()).isEqualTo(SidecarErrorCode.INVALID_SESSION_STATE);
            assertThat(session.isActive()).isFalse();
        } else {
            assertThatThrownBy(() -> service.queueSync()).isInstanceOf(IllegalStateException.class);
            assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.IDLE);
        }
        assertThat(submitted).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = SidecarBank.class, names = {"LBP", "NATIXIS", "BNP"}, mode = EnumSource.Mode.EXCLUDE)
    void writesLockTheMemberRowOnlyForTheBanksThatSerializeOnIt(SidecarBank bank) {
        stored(bank);

        service(bank).queueSync();

        boolean locks = bank == SidecarBank.CORUM || bank == SidecarBank.SOFIDY;
        verify(members, locks ? org.mockito.Mockito.atLeastOnce() : never()).findByIdForUpdate(MEMBER);
    }

    @ParameterizedTest
    @EnumSource(value = SidecarBank.class, names = {"LBP", "NATIXIS", "BNP"}, mode = EnumSource.Mode.EXCLUDE)
    void theStatusOfAMemberWithoutASessionIsInactive(SidecarBank bank) {
        SidecarSessionView status = service(bank).status();

        assertThat(status.isActive()).isFalse();
        assertThat(status.lastSyncError()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = SidecarBank.class, names = {"LBP", "NATIXIS", "BNP"}, mode = EnumSource.Mode.EXCLUDE)
    void clearingDeletesOnlyThisBanksSession(SidecarBank bank) {
        SidecarSession session = stored(bank);

        service(bank).clear();

        verify(sessions).findByBankAndMemberIdForUpdate(bank, MEMBER);
        verify(sessions).delete(session);
    }

    @ParameterizedTest
    @EnumSource(value = SidecarBank.class, names = {"LBP", "NATIXIS", "BNP"}, mode = EnumSource.Mode.EXCLUDE)
    void recoveryFailsOnlyThisBanksInFlightJobsWithARetryableError(SidecarBank bank) {
        Bank service = service(bank);
        if (NO_BOOT_RECOVERY.contains(bank)) {
            assertThat(service.recovers()).as("%s gained a boot recovery: add it to SidecarSyncRecovery", bank).isFalse();
            return;
        }

        service.recover();

        // Fortuneo runs the same update once per member; the others once for the bank.
        assertThat(org.mockito.Mockito.mockingDetails(sessions).getInvocations())
            .filteredOn(call -> call.getMethod().getName().equals("markInterruptedSyncsFailed"))
            .isNotEmpty()
            .allSatisfy(call -> {
                List<Object> args = List.of(call.getArguments());
                assertThat(args.get(0)).isEqualTo(bank);
                assertThat(args).contains(List.of(SidecarSyncStatus.QUEUED, SidecarSyncStatus.RUNNING),
                    SidecarSyncStatus.FAILED, SidecarErrorCode.INTERNAL_ERROR);
            });
    }

    @ParameterizedTest
    @EnumSource(value = SidecarBank.class, names = {"LBP", "NATIXIS", "BNP"}, mode = EnumSource.Mode.EXCLUDE)
    void theSchedulerSkipsAMemberWithoutASessionAndAsksAnExpiredOneToReconnect(SidecarBank bank) {
        Bank service = service(bank);
        if (NOT_SCHEDULED.contains(bank)) {
            assertThat(service.reports()).as("%s gained a scheduler entry", bank).isFalse();
            return;
        }

        assertThat(service.resyncReporting().status()).isEqualTo(SourceSyncResult.Status.SKIPPED_NOT_CONNECTED);

        SidecarSession session = stored(bank);
        session.markQueued();
        session.markFailed(SidecarErrorCode.SESSION_EXPIRED, java.time.Instant.now());
        SourceSyncResult expired = service.resyncReporting();

        assertThat(expired.status()).isEqualTo(SourceSyncResult.Status.NEEDS_REAUTH);
        assertThat(expired.message()).isEqualTo(SidecarErrorCode.SESSION_EXPIRED.name());
        assertThat(submitted).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = SidecarBank.class, names = {"LBP", "NATIXIS", "BNP"}, mode = EnumSource.Mode.EXCLUDE)
    void theSchedulerQueuesAnActiveSession(SidecarBank bank) {
        Bank service = service(bank);
        if (NOT_SCHEDULED.contains(bank)) {
            assertThat(service.reports()).as("%s gained a scheduler entry", bank).isFalse();
            return;
        }
        SidecarSession session = stored(bank);

        assertThat(service.resyncReporting().status()).isEqualTo(SourceSyncResult.Status.QUEUED);
        assertThat(session.getSyncStatus()).isEqualTo(SidecarSyncStatus.QUEUED);
        assertThat(submitted).hasSize(1);
    }

    private SidecarSession stored(SidecarBank bank) {
        SidecarSession session = SidecarSession.builder()
            .id(SESSION)
            .bank(bank)
            .member(member)
            .sessionState("encrypted")
            .region(bank == SidecarBank.BANQUE_POPULAIRE ? BanquePopulaireRegion.RIVES_DE_PARIS : null)
            .active(true)
            .syncStatus(SidecarSyncStatus.IDLE)
            .build();
        when(sessions.findByBankAndMemberId(bank, MEMBER)).thenReturn(Optional.of(session));
        when(sessions.findByBankAndMemberIdForUpdate(bank, MEMBER)).thenReturn(Optional.of(session));
        when(sessions.findByIdAndMemberIdForUpdate(SESSION, MEMBER)).thenReturn(Optional.of(session));
        return session;
    }

    private void runSubmitted() {
        List<Runnable> jobs = List.copyOf(submitted);
        submitted.clear();
        jobs.forEach(Runnable::run);
    }

    private Bank service(SidecarBank bank) {
        Class<?> type = SERVICES.get(bank);
        Constructor<?> constructor = Arrays.stream(type.getConstructors())
            .max(Comparator.comparingInt(Constructor::getParameterCount))
            .orElseThrow();
        Class<?>[] parameters = constructor.getParameterTypes();
        Object[] arguments = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            arguments[i] = collaborator(parameters[i], i == 0, bank);
        }
        try {
            return new Bank(constructor.newInstance(arguments));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Object collaborator(Class<?> type, boolean adapter, SidecarBank bank) {
        if (type == SidecarSessionRepository.class) return sessions;
        if (type == FamilyMemberRepository.class) return members;
        if (type == CryptoEncryption.class) return encryption;
        if (type == Executor.class) return (Executor) job -> executor.execute(job);
        if (type == TransactionTemplate.class) return inlineTransactions();
        if (adapter) {
            return mock(type, invocation -> {
                String call = invocation.getMethod().getName();
                if (call.startsWith("fetch") || call.equals("openSession")) {
                    onFetch.run();
                    throw new SyncException(bank.label() + " is down", null,
                        SidecarErrorCode.UPSTREAM_UNAVAILABLE.name());
                }
                return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            });
        }
        return mock(type);
    }

    private static TransactionTemplate inlineTransactions() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        TransactionStatus status = mock(TransactionStatus.class);
        doAnswer(invocation -> ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(status))
            .when(tx).execute(any());
        doAnswer(invocation -> {
            ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(status);
            return null;
        }).when(tx).executeWithoutResult(any());
        return tx;
    }

    /** One bank's public sync surface, reached by name since each service has its own status record. */
    private record Bank(Object service) {

        void queueSync() {
            call("queueSync", MEMBER);
        }

        SidecarSessionView status() {
            return (SidecarSessionView) call("getStatus", MEMBER);
        }

        void clear() {
            call("clearSession", MEMBER);
        }

        boolean recovers() {
            return find("recoverInterruptedSyncs").isPresent();
        }

        void recover() {
            call("recoverInterruptedSyncs");
        }

        boolean reports() {
            return find("resyncReporting").isPresent();
        }

        SourceSyncResult resyncReporting() {
            return (SourceSyncResult) call("resyncReporting", MEMBER);
        }

        private Optional<Method> find(String name) {
            return Arrays.stream(service.getClass().getMethods())
                .filter(method -> method.getName().equals(name) && Modifier.isPublic(method.getModifiers()))
                .findFirst();
        }

        private Object call(String name, Object... arguments) {
            Method method = find(name).orElseThrow(() -> new AssertionError(service.getClass().getSimpleName()
                + " has no public " + name));
            try {
                return method.invoke(service, arguments);
            } catch (InvocationTargetException ex) {
                if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(ex.getCause());
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}
