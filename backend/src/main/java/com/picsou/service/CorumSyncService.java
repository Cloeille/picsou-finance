package com.picsou.service;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.CorumSession;
import com.picsou.model.CorumSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.model.ScpiFundOutcome;
import com.picsou.model.ScpiProvider;
import com.picsou.port.SidecarErrorCode;
import com.picsou.adapter.CorumAdapter;
import com.picsou.repository.CorumSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private static final BigDecimal MONEY_TOLERANCE = new BigDecimal("0.01");

    private final CorumAdapter port;
    private final CorumSessionRepository sessionRepository;
    private final FamilyMemberRepository memberRepository;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final Executor syncExecutor;
    private final ScpiFundReconciler fundReconciler;

    public CorumSyncService(
        CorumAdapter port,
        CorumSessionRepository sessionRepository,
        FamilyMemberRepository memberRepository,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("corumSyncExecutor") Executor syncExecutor,
        ScpiFundReconciler fundReconciler
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.memberRepository = memberRepository;
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.syncExecutor = syncExecutor;
        this.fundReconciler = fundReconciler;
    }

    public SessionStatusResponse authenticate(String login, String password, Long memberId) {
        String plainState = port.authenticate(login, password);
        if (plainState == null || plainState.isBlank()) {
            throw error(SidecarErrorCode.INVALID_DATA, "CORUM did not return a session", null);
        }
        SyncJob job = requireTransactionResult(txTemplate.execute(status -> {
            lockMember(memberId);
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
            lockMember(memberId);
            CorumSession session = sessionRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> error(
                    SidecarErrorCode.SESSION_EXPIRED,
                    "No active CORUM session. Please reconnect.",
                    null
                ));
            if (!session.isActive()) {
                throw error(
                    SidecarErrorCode.SESSION_EXPIRED,
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
        lockMember(memberId);
        sessionRepository.findByMemberIdForUpdate(memberId).ifPresent(sessionRepository::delete);
    }

    private void submit(SyncJob job) {
        try {
            syncExecutor.execute(() -> executeJob(job));
        } catch (RuntimeException ex) {
            markFailed(job, SidecarErrorCode.INTERNAL_ERROR);
            throw error(
                SidecarErrorCode.INTERNAL_ERROR,
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
            CorumAdapter.Snapshot snapshot = port.fetchSnapshot(job.plainState());
            commitSnapshot(job, snapshot);
        } catch (SyncException ex) {
            SidecarErrorCode code = codeOf(ex);
            markFailed(job, code);
            log.warn("CORUM sync failed (member={}; code={})", job.memberId(), code);
        } catch (Exception ex) {
            markFailed(job, SidecarErrorCode.INTERNAL_ERROR);
            log.error("CORUM sync failed unexpectedly (member={})", job.memberId(), ex);
        }
    }

    /**
     * Writes the snapshot onto the member's SCPI accounts.
     *
     * <p>A fund already linked to an account updates it. A fund seen for the first
     * time adopts the one manual SCPI account named like it, or gets a new account;
     * a fund the member ignored writes nothing. {@link ScpiFundReconciler} owns those
     * rules, and the withdrawal-price rule stays in
     * {@link ScpiPositionService#applySyncedPosition}: a holding whose withdrawal
     * price is missing still updates the share count but leaves the balance alone.
     */
    private Map<String, ScpiFundOutcome> applySnapshot(
        Long memberId, CorumAdapter.Snapshot snapshot, Instant syncedAt
    ) {
        if (snapshot == null || !snapshot.snapshotComplete()) {
            throw error(
                SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "CORUM returned an incomplete portfolio",
                null
            );
        }
        if (snapshot.holdings() == null || snapshot.holdings().isEmpty()) {
            throw error(
                SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "CORUM returned no fund holding",
                null
            );
        }

        Set<String> seenFunds = new HashSet<>();
        for (CorumAdapter.Holding holding : snapshot.holdings()) {
            if (holding == null || holding.fundCode() == null || holding.quantity() == null) {
                throw error(SidecarErrorCode.INVALID_DATA, "CORUM returned an incomplete fund", null);
            }
            if (!seenFunds.add(holding.fundCode())) {
                // Two lines for one fund would write the quantity twice.
                throw error(SidecarErrorCode.INVALID_DATA, "CORUM returned a duplicate fund", null);
            }
            if (holding.quantity().signum() < 0) {
                throw error(SidecarErrorCode.INVALID_DATA, "CORUM returned a negative share count", null);
            }
        }
        checkDeclaredTotal(snapshot);
        // Every SCPI account is EUR and the reconciler writes the figures as they
        // come. A snapshot in another currency, or with none, would be stored as
        // euros silently, so it is refused before anything is written.
        if (!"EUR".equals(snapshot.currency())) {
            throw error(SidecarErrorCode.INVALID_DATA, "CORUM returned a portfolio not in euros", null);
        }

        List<ScpiFundReconciler.SyncedFund> funds = snapshot.holdings().stream()
            .map(holding -> new ScpiFundReconciler.SyncedFund(
                holding.fundCode(),
                holding.label(),
                holding.quantity(),
                holding.subscriptionPrice(),
                holding.withdrawalPrice()
            ))
            .toList();
        return fundReconciler.reconcile(memberId, ScpiProvider.CORUM, funds, syncedAt);
    }

    /**
     * Reconciles the funds against the contract total the snapshot declares.
     *
     * <p>The sidecar already does this, and repeating it here is deliberate: the
     * sidecar is a separate deployable, so its check is exactly the thing a
     * rolling update or a stale image would leave out. The figure compared is
     * the displayed value, not the withdrawal value: entry fees sit between the
     * two, and comparing the withdrawal value to the contract would refuse
     * every real portfolio.
     */
    private void checkDeclaredTotal(CorumAdapter.Snapshot snapshot) {
        BigDecimal declared = snapshot.totalValuationEur();
        if (declared == null || declared.signum() < 0) {
            throw error(SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "CORUM returned no valid portfolio total", null);
        }
        BigDecimal computed = BigDecimal.ZERO;
        for (CorumAdapter.Holding holding : snapshot.holdings()) {
            if (holding.displayedValueEur() == null) {
                throw error(SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                    "CORUM returned a fund without a displayed value", null);
            }
            computed = computed.add(holding.displayedValueEur());
        }
        if (computed.subtract(declared).abs().compareTo(MONEY_TOLERANCE) > 0) {
            throw error(
                SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "The CORUM funds add up to " + computed.setScale(2, RoundingMode.HALF_UP)
                    + " but the snapshot declares " + declared.setScale(2, RoundingMode.HALF_UP),
                null
            );
        }
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

    private void commitSnapshot(SyncJob job, CorumAdapter.Snapshot snapshot) {
        txTemplate.executeWithoutResult(status -> {
            lockMember(job.memberId());
            Optional<CorumSession> current = sessionRepository
                .findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId());
            if (current.isEmpty() || !current.get().isActive()
                    || current.get().getSyncStatus() != CorumSyncStatus.RUNNING) {
                return;
            }
            Instant now = Instant.now();
            Map<String, ScpiFundOutcome> outcomes = applySnapshot(job.memberId(), snapshot, now);
            current.get().markSuccessful(now);
            sessionRepository.save(current.get());
            log.info("CORUM sync completed (member={}; funds={})", job.memberId(), outcomes);
        });
    }

    private void lockMember(Long memberId) {
        sessionRepository.findMemberByIdForUpdate(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
    }

    private void markFailed(SyncJob job, SidecarErrorCode code) {
        txTemplate.executeWithoutResult(status -> {
            lockMember(job.memberId());
            sessionRepository.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
                .filter(session -> session.isActive() && session.isSyncInFlight())
                .ifPresent(session -> {
                    session.markFailed(code, Instant.now());
                    sessionRepository.save(session);
                });
        });
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

    private SidecarErrorCode codeOf(SyncException exception) {
        return SyncValues.errorCode(exception, SidecarBank.CORUM, SidecarErrorCode.UPSTREAM_UNAVAILABLE);
    }

    private SyncException error(SidecarErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    public record SessionStatusResponse(
        boolean isActive,
        CorumSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        SidecarErrorCode lastSyncError
    ) {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, CorumSyncStatus.IDLE, null, null, null);
        }
    }

    private record QueueDecision(SyncJob job, SessionStatusResponse status) {}

    private record SyncJob(Long sessionId, Long memberId, String plainState) {}
}
