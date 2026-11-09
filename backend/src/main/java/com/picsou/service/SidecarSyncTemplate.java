package com.picsou.service;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.FamilyMember;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.port.SidecarErrorCode;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SidecarSessionRepository;
import com.picsou.service.sync.SourceSyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.picsou.service.SyncValues.requireTransactionResult;

/**
 * The initiate / queue / execute / commit cycle every sidecar bank shared, written once. A sync
 * runs in three short transactions fenced on the session row: QUEUED, then RUNNING, then the
 * commit or the failure. The provider call happens between them, outside any transaction, and a
 * result whose session was disconnected or replaced meanwhile is discarded.
 */
public final class SidecarSyncTemplate<T> {

    private static final Logger log = LoggerFactory.getLogger(SidecarSyncTemplate.class);
    private static final List<SidecarSyncStatus> IN_FLIGHT = List.of(SidecarSyncStatus.QUEUED, SidecarSyncStatus.RUNNING);

    private final SidecarBank bank;
    private final SidecarSyncStrategy<T> strategy;
    private final SidecarSessionRepository sessions;
    private final FamilyMemberRepository members;
    private final CryptoEncryption encryption;
    private final TransactionTemplate tx;
    private final Executor executor;
    private final boolean locksMember;

    /**
     * @param locksMember whether every write first locks the member row, so creation and deletion
     *                    serialize even when no session row exists yet (CORUM, Sofidy)
     */
    public SidecarSyncTemplate(
        SidecarBank bank,
        SidecarSyncStrategy<T> strategy,
        SidecarSessionRepository sessions,
        FamilyMemberRepository members,
        CryptoEncryption encryption,
        TransactionTemplate tx,
        Executor executor,
        boolean locksMember
    ) {
        this.bank = bank;
        this.strategy = strategy;
        this.sessions = sessions;
        this.members = members;
        this.encryption = encryption;
        this.tx = tx;
        this.executor = executor;
        this.locksMember = locksMember;
    }

    public SidecarBank bank() {
        return bank;
    }

    public Optional<SidecarSession> find(Long memberId) {
        return sessions.findByBankAndMemberId(bank, memberId);
    }

    /**
     * Queues an import of the stored session and returns the session as it stands. An import
     * already in flight is left alone. A session whose stored state cannot be decrypted is
     * recorded as failed, for the banks that declare {@code INVALID_SESSION_STATE}.
     */
    public SidecarSession queue(Long memberId, boolean history) {
        Queued queued = requireTransactionResult(tx.execute(status -> {
            lockMemberIfNeeded(memberId);
            SidecarSession session = sessions.findByBankAndMemberIdForUpdate(bank, memberId)
                .orElseThrow(() -> error(SidecarErrorCode.SESSION_EXPIRED,
                    "No active " + bank.label() + " session. Please reconnect.", null));
            if (!session.isActive()) {
                throw error(SidecarErrorCode.SESSION_EXPIRED,
                    "The " + bank.label() + " session expired. Please reconnect.", null);
            }
            if (session.isSyncInFlight()) {
                return new Queued(session, null, null);
            }
            String plainState;
            try {
                plainState = encryption.decrypt(session.getSessionState());
            } catch (RuntimeException ex) {
                if (!bank.declares(SidecarErrorCode.INVALID_SESSION_STATE)) {
                    throw ex;
                }
                // Unreadable (wrong key, corrupted): the session is dead, not a server fault.
                // Recorded inside the transaction, then thrown after it so the row is committed.
                session.markQueued();
                session.markFailed(SidecarErrorCode.INVALID_SESSION_STATE, Instant.now());
                sessions.save(session);
                log.warn("Stored {} session is unreadable (member={}; type={})",
                    bank.label(), memberId, ex.getClass().getSimpleName());
                return new Queued(session, null, error(SidecarErrorCode.INVALID_SESSION_STATE,
                    "The stored " + bank.label() + " session is unusable. Please reconnect.", null));
            }
            session.markQueued();
            sessions.save(session);
            return new Queued(session, new SidecarSyncJob(session.getId(), memberId, plainState, history), null);
        }));
        if (queued.failure() != null) {
            throw queued.failure();
        }
        if (queued.job() == null) {
            return queued.session();
        }
        submit(queued.job());
        return find(memberId).orElse(queued.session());
    }

    /**
     * Replaces the member's session with a freshly authenticated one and queues its first
     * import. {@code prepare} sets bank-specific columns before the row is saved.
     */
    public void replaceAndQueue(
        Long memberId,
        String plainState,
        boolean history,
        Consumer<SidecarSession> prepare
    ) {
        SidecarSyncJob job = requireTransactionResult(tx.execute(status ->
            insertQueued(memberId, plainState, history, prepare)));
        submit(job);
    }

    /**
     * As {@link #replaceAndQueue}, for a caller already inside a transaction that does its own
     * fencing. The caller submits the returned job once that transaction commits.
     */
    public SidecarSyncJob insertQueued(
        Long memberId,
        String plainState,
        boolean history,
        Consumer<SidecarSession> prepare
    ) {
        FamilyMember member = locksMember ? lockMember(memberId) : members.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
        sessions.findByBankAndMemberIdForUpdate(bank, memberId).ifPresent(sessions::delete);
        sessions.flush();
        SidecarSession session = SidecarSession.create(bank, member, encryption.encrypt(plainState), Instant.now());
        prepare.accept(session);
        session.markQueued();
        SidecarSession stored = sessions.saveAndFlush(session);
        return new SidecarSyncJob(stored.getId(), memberId, plainState, history);
    }

    /** Returns whether a stored session was there to delete. */
    public boolean clear(Long memberId) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            lockMemberIfNeeded(memberId);
            Optional<SidecarSession> session = sessions.findByBankAndMemberIdForUpdate(bank, memberId);
            session.ifPresent(sessions::delete);
            return session.isPresent();
        }));
    }

    /**
     * Browser-backed jobs cannot survive a backend restart. Turns persisted in-flight states into
     * a retryable failure instead of leaving the UI polling QUEUED/RUNNING forever.
     */
    public int recoverInterrupted() {
        int recovered = sessions.markInterruptedSyncsFailed(bank, IN_FLIGHT, SidecarSyncStatus.FAILED,
            Instant.now(), SidecarErrorCode.INTERNAL_ERROR);
        if (recovered > 0) {
            log.warn("Recovered {} interrupted {} sync job(s)", recovered, bank.label());
        }
        return recovered;
    }

    /**
     * The daily scheduler's entry: queues an import when the session can run, and says why not.
     * Reads and queues through the bank service's own methods and logs on its logger, so the
     * service stays the one seam its callers and tests see.
     */
    public SourceSyncResult resyncReporting(
        Long memberId,
        String source,
        Logger serviceLog,
        Function<Long, ? extends SidecarSessionView> status,
        Consumer<Long> queue
    ) {
        try {
            SidecarSessionView current = status.apply(memberId);
            if (!current.isActive()) {
                SidecarErrorCode error = current.lastSyncError();
                if (error != null) {
                    SourceSyncResult.Status outcome = needsReauth(source, error)
                        ? SourceSyncResult.Status.NEEDS_REAUTH
                        : SourceSyncResult.Status.FAILED;
                    return new SourceSyncResult(source, outcome, error.name());
                }
                return new SourceSyncResult(source, SourceSyncResult.Status.SKIPPED_NOT_CONNECTED, "No active session");
            }
            queue.accept(memberId);
            return new SourceSyncResult(source, SourceSyncResult.Status.QUEUED, "");
        } catch (ResourceNotFoundException ex) {
            return new SourceSyncResult(source, SourceSyncResult.Status.SKIPPED_NOT_CONNECTED, "Member not found");
        } catch (DataAccessException ex) {
            serviceLog.error("Database error during scheduled {} sync (member={})", bank.label(), memberId, ex);
            return new SourceSyncResult(source, SourceSyncResult.Status.FAILED, "Database error");
        } catch (SyncException ex) {
            serviceLog.warn("Could not queue scheduled {} sync (member={}; code={})",
                bank.label(), memberId, ex.getCode(), ex);
            SourceSyncResult classified = SourceSyncResult.fromSyncException(source, ex);
            return new SourceSyncResult(source, classified.status(),
                classified.status() == SourceSyncResult.Status.NEEDS_REAUTH ? ex.getCode() : "Sync failed");
        } catch (Exception ex) {
            serviceLog.error("{} scheduled sync failed unexpectedly for member {}", bank.label(), memberId, ex);
            return new SourceSyncResult(source, SourceSyncResult.Status.FAILED, "Unexpected sync error");
        }
    }

    /** Hands a queued job to the bank's executor; a job it refuses is recorded as failed. */
    public void submit(SidecarSyncJob job) {
        try {
            executor.execute(() -> execute(job));
        } catch (RuntimeException ex) {
            log.warn("{} sync executor refused a job (member={})", bank.label(), job.memberId(), ex);
            markFailed(job, SidecarErrorCode.INTERNAL_ERROR);
            throw error(SidecarErrorCode.INTERNAL_ERROR,
                "Could not schedule the " + bank.label() + " synchronization", ex);
        }
    }

    void execute(SidecarSyncJob job) {
        if (!markRunning(job)) {
            return;
        }
        try {
            T prepared = strategy.fetch(job);
            if (commit(job, prepared)) {
                log.info("{} sync completed (member={}; {})", bank.label(), job.memberId(), strategy.describe(prepared));
                strategy.afterCommit(job, prepared);
            } else {
                log.info("Discarded stale {} sync result (member={})", bank.label(), job.memberId());
            }
        } catch (SyncException ex) {
            SidecarErrorCode code = codeOf(ex);
            markFailed(job, code);
            log.warn("{} sync failed (member={}; code={})", bank.label(), job.memberId(), code);
        } catch (Exception ex) {
            markFailed(job, SidecarErrorCode.INTERNAL_ERROR);
            if (strategy.redactsUnexpectedFailures()) {
                log.error("{} sync failed unexpectedly (member={}; type={})",
                    bank.label(), job.memberId(), ex.getClass().getSimpleName());
            } else {
                log.error("{} sync failed unexpectedly (member={})", bank.label(), job.memberId(), ex);
            }
        }
    }

    private boolean markRunning(SidecarSyncJob job) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            lockMemberIfNeeded(job.memberId());
            Optional<SidecarSession> current = sessions.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId());
            if (current.isEmpty()) {
                log.info("{} sync session disappeared before execution (member={})", bank.label(), job.memberId());
                return false;
            }
            SidecarSession session = current.get();
            if (!session.isActive() || session.getSyncStatus() != SidecarSyncStatus.QUEUED) {
                log.warn("{} sync cannot start from state {} (member={}; active={})",
                    bank.label(), session.getSyncStatus(), job.memberId(), session.isActive());
                return false;
            }
            session.markRunning(Instant.now());
            sessions.save(session);
            return true;
        }));
    }

    private boolean commit(SidecarSyncJob job, T prepared) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            lockMemberIfNeeded(job.memberId());
            Optional<SidecarSession> current = sessions.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId());
            if (current.isEmpty()) {
                log.info("{} sync session disappeared before commit (member={})", bank.label(), job.memberId());
                return false;
            }
            SidecarSession session = current.get();
            if (!session.isActive() || session.getSyncStatus() != SidecarSyncStatus.RUNNING) {
                log.warn("{} sync cannot commit from state {} (member={}; active={})",
                    bank.label(), session.getSyncStatus(), job.memberId(), session.isActive());
                return false;
            }
            Instant syncedAt = Instant.now();
            strategy.commit(job, prepared, syncedAt);
            session.markSuccessful(syncedAt);
            sessions.save(session);
            return true;
        }));
    }

    /** Records a failure on the job's own session, unless it was replaced or finished meanwhile. */
    public void markFailed(SidecarSyncJob job, SidecarErrorCode code) {
        try {
            tx.executeWithoutResult(status -> {
                lockMemberIfNeeded(job.memberId());
                Optional<SidecarSession> current = sessions.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId());
                if (current.isEmpty()) {
                    log.info("{} sync session disappeared before failure was recorded (member={})",
                        bank.label(), job.memberId());
                    return;
                }
                SidecarSession session = current.get();
                if (!session.isSyncInFlight()) {
                    log.warn("{} sync failure ignored from state {} (member={}; code={})",
                        bank.label(), session.getSyncStatus(), job.memberId(), code);
                    return;
                }
                strategy.onFailure(session, code);
                session.markFailed(code, Instant.now());
                sessions.save(session);
            });
        } catch (RuntimeException ex) {
            if (strategy.redactsUnexpectedFailures()) {
                log.error("Could not persist {} sync failure (member={}; code={}; type={})",
                    bank.label(), job.memberId(), code, ex.getClass().getSimpleName());
            } else {
                log.error("Could not persist {} sync failure (member={}; code={})", bank.label(), job.memberId(), code, ex);
            }
        }
    }

    public SidecarErrorCode codeOf(SyncException exception) {
        return SyncValues.errorCode(exception, bank, SidecarErrorCode.UPSTREAM_UNAVAILABLE);
    }

    public static SyncException error(SidecarErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    public FamilyMember lockMember(Long memberId) {
        return members.findByIdForUpdate(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
    }

    private void lockMemberIfNeeded(Long memberId) {
        if (locksMember) {
            lockMember(memberId);
        }
    }

    private static boolean needsReauth(String source, SidecarErrorCode error) {
        return SourceSyncResult.fromSyncException(source, new SyncException(error.name(), null, error.name()))
            .status() == SourceSyncResult.Status.NEEDS_REAUTH;
    }

    private record Queued(SidecarSession session, SidecarSyncJob job, SyncException failure) {}
}
