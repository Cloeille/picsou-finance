package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.CorumSession;
import com.picsou.model.CorumSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.model.ScpiPosition;
import com.picsou.port.CorumErrorCode;
import com.picsou.port.CorumPort;
import com.picsou.repository.CorumSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.ScpiPositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import static com.picsou.service.SyncValues.errorCode;
import static com.picsou.service.SyncValues.requireTransactionResult;

/**
 * Syncs a CORUM client-space portfolio into the SCPI accounts that are already
 * linked to its funds.
 *
 * <p>Shaped like {@link AmundiSyncService}: the browser work happens in a
 * sidecar and is slow, so authentication stores the session and queues the
 * import, and the import does its upstream I/O outside any transaction before
 * writing. Unlike Amundi this does not create accounts -- the manual model owns
 * that, one account per vehicle, and a CORUM contract simply maps its funds onto
 * accounts the user already created.
 *
 * <p>The balance written here is the withdrawal value, computed by
 * {@link ScpiPositionService}. This service never re-derives it and never falls
 * back to the subscription price, because entry fees sit between the two and
 * using the wrong one overstates net wealth.
 */
@Service
public class CorumSyncService {
    private static final Logger log = LoggerFactory.getLogger(CorumSyncService.class);

    private final CorumPort port;
    private final CorumSessionRepository sessionRepository;
    private final ScpiPositionRepository positionRepository;
    private final ScpiPositionService positionService;
    private final FamilyMemberRepository memberRepository;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final Executor syncExecutor;

    public CorumSyncService(
        CorumPort port,
        CorumSessionRepository sessionRepository,
        ScpiPositionRepository positionRepository,
        ScpiPositionService positionService,
        FamilyMemberRepository memberRepository,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("corumSyncExecutor") Executor syncExecutor
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.positionRepository = positionRepository;
        this.positionService = positionService;
        this.memberRepository = memberRepository;
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.syncExecutor = syncExecutor;
    }

    public SessionStatusResponse authenticate(String login, String password, Long memberId) {
        String plainState = port.authenticate(login, password);
        if (plainState == null || plainState.isBlank()) {
            throw error(CorumErrorCode.INVALID_DATA, "CORUM did not return a session", null);
        }
        SyncJob job = requireTransactionResult(txTemplate.execute(status -> {
            FamilyMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            sessionRepository.findByMemberIdForUpdate(memberId).ifPresent(sessionRepository::delete);
            sessionRepository.flush();

            CorumSession newSession = CorumSession.create(
                member,
                encryption.encrypt(plainState),
                Instant.now()
            );
            newSession.markQueued();
            CorumSession stored = sessionRepository.saveAndFlush(newSession);
            return new SyncJob(stored.getId(), memberId, plainState);
        }));

        submit(job);
        return getStatus(memberId);
    }

    public SessionStatusResponse queueSync(Long memberId) {
        QueueDecision decision = requireTransactionResult(txTemplate.execute(status -> {
            CorumSession session = sessionRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> error(
                    CorumErrorCode.SESSION_EXPIRED,
                    "No active CORUM session. Please reconnect.",
                    null
                ));
            if (!session.isActive()) {
                throw error(
                    CorumErrorCode.SESSION_EXPIRED,
                    "The CORUM session expired. Please reconnect.",
                    null
                );
            }
            if (session.isSyncInFlight()) {
                return new QueueDecision(null, toStatus(session));
            }

            String plainState = encryption.decrypt(session.getSessionState());
            session.markQueued();
            sessionRepository.save(session);
            return new QueueDecision(
                new SyncJob(session.getId(), memberId, plainState),
                toStatus(session)
            );
        }));

        if (decision.job() != null) {
            submit(decision.job());
            return getStatus(memberId);
        }
        return decision.status();
    }

    @Transactional(readOnly = true)
    public SessionStatusResponse getStatus(Long memberId) {
        return sessionRepository.findByMemberId(memberId)
            .map(this::toStatus)
            .orElseGet(SessionStatusResponse::inactive);
    }

    @Transactional
    public void clearSession(Long memberId) {
        sessionRepository.findByMemberIdForUpdate(memberId).ifPresent(sessionRepository::delete);
    }

    private void submit(SyncJob job) {
        try {
            syncExecutor.execute(() -> executeJob(job));
        } catch (RuntimeException ex) {
            markFailed(job, CorumErrorCode.INTERNAL_ERROR);
            throw error(
                CorumErrorCode.INTERNAL_ERROR,
                "Could not schedule the CORUM synchronization",
                ex
            );
        }
    }

    private void executeJob(SyncJob job) {
        if (!markRunning(job)) {
            return;
        }
        try {
            CorumPort.Snapshot snapshot = port.fetchSnapshot(job.plainState());
            int updated = applySnapshot(job.memberId(), snapshot);
            markSuccessful(job);
            log.info("CORUM sync completed (member={}; positions={})", job.memberId(), updated);
        } catch (SyncException ex) {
            CorumErrorCode code = codeOf(ex);
            markFailed(job, code);
            log.warn("CORUM sync failed (member={}; code={})", job.memberId(), code);
        } catch (Exception ex) {
            markFailed(job, CorumErrorCode.INTERNAL_ERROR);
            log.error("CORUM sync failed unexpectedly (member={})", job.memberId(), ex);
        }
    }

    /**
     * Writes the snapshot onto the linked accounts.
     *
     * <p>One holding maps to at most one account, matched on the fund code the
     * user linked when creating the account. A holding with no linked account is
     * skipped rather than creating one: the manual model owns account creation,
     * and a sync that silently opened a new account would bypass the Immobilier
     * flow a share is supposed to go through.
     *
     * <p>A holding whose withdrawal price is missing still updates the share
     * count -- that is real information -- but leaves the balance alone and
     * reports {@code PRICE_INCOMPLETE}, which is what
     * {@link ScpiPositionService#save} already does.
     */
    private int applySnapshot(Long memberId, CorumPort.Snapshot snapshot) {
        if (snapshot == null || !snapshot.snapshotComplete()) {
            throw error(
                CorumErrorCode.PORTFOLIO_INCOMPLETE,
                "CORUM returned an incomplete portfolio",
                null
            );
        }
        if (snapshot.holdings() == null || snapshot.holdings().isEmpty()) {
            throw error(
                CorumErrorCode.PORTFOLIO_INCOMPLETE,
                "CORUM returned no fund holding",
                null
            );
        }

        Set<String> seenFunds = new HashSet<>();
        for (CorumPort.Holding holding : snapshot.holdings()) {
            if (holding == null || holding.fundCode() == null || holding.quantity() == null) {
                throw error(CorumErrorCode.INVALID_DATA, "CORUM returned an incomplete fund", null);
            }
            if (!seenFunds.add(holding.fundCode())) {
                // Two lines for one fund would write the quantity twice.
                throw error(CorumErrorCode.INVALID_DATA, "CORUM returned a duplicate fund", null);
            }
            if (holding.quantity().signum() < 0) {
                throw error(CorumErrorCode.INVALID_DATA, "CORUM returned a negative share count", null);
            }
        }

        int updated = 0;
        for (CorumPort.Holding holding : snapshot.holdings()) {
            Optional<ScpiPosition> linked =
                positionRepository.findByMemberIdAndCorumFundCode(memberId, holding.fundCode());
            if (linked.isEmpty()) {
                log.info(
                    "No Picsou account is linked to this CORUM fund (member={}; fund={})",
                    memberId,
                    holding.fundCode()
                );
                continue;
            }
            ScpiPosition position = linked.get();
            positionService.applySyncedPosition(
                position,
                holding.quantity(),
                holding.subscriptionPrice(),
                holding.withdrawalPrice(),
                holding.valuationDate()
            );
            updated++;
        }
        return updated;
    }

    private boolean markRunning(SyncJob job) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<CorumSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("CORUM sync session disappeared before execution (member={})", job.memberId());
                return false;
            }
            CorumSession session = current.get();
            if (!session.isActive() || session.getSyncStatus() != CorumSyncStatus.QUEUED) {
                log.warn(
                    "CORUM sync cannot start from state {} (member={}; active={})",
                    session.getSyncStatus(), job.memberId(), session.isActive()
                );
                return false;
            }
            session.markRunning(Instant.now());
            sessionRepository.save(session);
            return true;
        }));
    }

    private void markSuccessful(SyncJob job) {
        txTemplate.executeWithoutResult(status -> sessionRepository
            .findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
            .ifPresent(session -> {
                session.markSuccessful(Instant.now());
                sessionRepository.save(session);
            }));
    }

    private void markFailed(SyncJob job, CorumErrorCode code) {
        txTemplate.executeWithoutResult(status -> sessionRepository
            .findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
            .ifPresent(session -> {
                session.markFailed(code, Instant.now());
                sessionRepository.save(session);
            }));
    }

    private SessionStatusResponse toStatus(CorumSession session) {
        return new SessionStatusResponse(
            session.isActive(),
            session.getSyncStatus(),
            session.getLastSyncStartedAt(),
            session.getLastSyncCompletedAt(),
            session.getLastSyncError()
        );
    }

    private CorumErrorCode codeOf(SyncException exception) {
        return errorCode(exception, CorumErrorCode.class, CorumErrorCode.UPSTREAM_UNAVAILABLE);
    }

    private SyncException error(CorumErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    public record SessionStatusResponse(
        boolean isActive,
        CorumSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        CorumErrorCode lastSyncError
    ) {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, CorumSyncStatus.IDLE, null, null, null);
        }
    }

    private record QueueDecision(SyncJob job, SessionStatusResponse status) {}

    private record SyncJob(Long sessionId, Long memberId, String plainState) {}
}
