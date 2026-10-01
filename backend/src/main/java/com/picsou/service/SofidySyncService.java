package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.FamilyMember;
import com.picsou.model.ScpiPosition;
import com.picsou.model.SofidySession;
import com.picsou.model.SofidySyncStatus;
import com.picsou.port.SofidyErrorCode;
import com.picsou.port.SofidyPort;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.ScpiPositionRepository;
import com.picsou.repository.SofidySessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static com.picsou.service.SyncValues.errorCode;
import static com.picsou.service.SyncValues.requireTransactionResult;

/**
 * Syncs a Sofidy client-space portfolio into the SCPI accounts that are already
 * linked to its funds.
 *
 * <p>Shaped like {@link CorumSyncService}, with one difference the portal forces:
 * Sofidy always sends a verification code by e-mail after the password, so
 * authentication is two calls and the session only exists once the code is typed.
 * An inactive, encrypted reservation binds each pending process to its member.
 * Replacing or deleting it invalidates an authentication response in flight.
 *
 * <p>Like CORUM this does not create accounts. The manual model owns that, one
 * account per vehicle, and a Sofidy portfolio simply maps its funds onto accounts
 * the user already created.
 *
 * <p>The balance written here is the withdrawal value, computed by
 * {@link ScpiPositionService}. This service never re-derives it, and passes a null
 * subscription price: Sofidy publishes the redemption value and nothing else, so
 * there is no second figure that could be mistaken for a balance.
 */
@Service
public class SofidySyncService {
    private static final Logger log = LoggerFactory.getLogger(SofidySyncService.class);

    /**
     * What counts as a rounding difference rather than a missing fund, matching
     * the sidecar's own tolerance. A share count times a unit price rarely comes
     * back to Sofidy's printed total to the cent, and refusing on that would
     * refuse a portfolio that is in fact complete.
     */
    private static final BigDecimal MONEY_TOLERANCE = new BigDecimal("0.01");
    private static final String AUTH_START_PREFIX = "sofidy-auth-start:";
    private static final String AUTH_PROCESS_PREFIX = "sofidy-auth-process:";
    private static final Duration AUTH_TTL = Duration.ofMinutes(5);

    private final SofidyPort port;
    private final SofidySessionRepository sessionRepository;
    private final ScpiPositionRepository positionRepository;
    private final ScpiPositionService positionService;
    private final FamilyMemberRepository memberRepository;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final Executor syncExecutor;

    public SofidySyncService(
        SofidyPort port,
        SofidySessionRepository sessionRepository,
        ScpiPositionRepository positionRepository,
        ScpiPositionService positionService,
        FamilyMemberRepository memberRepository,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("sofidySyncExecutor") Executor syncExecutor
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

    /**
     * Starts a login. Sofidy always answers with a pending process id, so the
     * session is null and {@code mfaRequired} is true; the shape is kept anyway
     * because a portal that stops asking for a code would then work without a
     * change here.
     */
    public InitiateResponse initiateAuth(String associateCode, String password, Long memberId) {
        AuthAttempt attempt = reserveAuthAttempt(memberId);
        SofidyPort.InitiateResult result = port.initiateAuth(associateCode, password);
        if (result == null) {
            throw error(SofidyErrorCode.INVALID_DATA, "Sofidy did not answer the login request", null);
        }
        if (!result.mfaRequired()) {
            if (result.sessionState() == null || result.sessionState().isBlank()) {
                throw error(
                    SofidyErrorCode.INVALID_DATA,
                    "Sofidy opened no session and asked for no code",
                    null
                );
            }
            // A portal that opened a session without asking for a code would
            // still leave the panel waiting on an OTP screen, so the status is
            // folded into the init shape: the shared panel reads `mfaRequired`
            // to decide it is connected.
            SessionStatusResponse status = storeSession(memberId, result.sessionState(), attempt);
            return new InitiateResponse(
                null, false, null, status.isActive(), status.syncStatus(),
                status.lastSyncError(), status.lastSyncStartedAt(), status.lastSyncCompletedAt()
            );
        }
        if (result.processId() == null || result.processId().isBlank()) {
            throw error(
                SofidyErrorCode.INVALID_DATA,
                "Sofidy asked for a code without giving an attempt to complete",
                null
            );
        }
        txTemplate.executeWithoutResult(status -> {
            FamilyMember member = lockMember(memberId);
            SofidySession current = currentAttempt(memberId, attempt);
            sessionRepository.delete(current);
            sessionRepository.flush();
            sessionRepository.saveAndFlush(pendingSession(member,
                AUTH_PROCESS_PREFIX + result.processId()));
        });
        return InitiateResponse.pending(result.processId(), result.mfaType());
    }

    /** Finishes the login with the code from the inbox, then queues the import. */
    public SessionStatusResponse completeAuth(String processId, String code, Long memberId) {
        AuthAttempt attempt = requireTransactionResult(txTemplate.execute(status -> {
            lockMember(memberId);
            SofidySession pending = sessionRepository.findByMemberIdForUpdate(memberId)
                .filter(session -> !session.isActive() && !session.isSyncInFlight())
                .filter(session -> isCurrentAttempt(session))
                .filter(session -> processId != null && !processId.isBlank()
                    && (AUTH_PROCESS_PREFIX + processId).equals(encryption.decrypt(session.getSessionState())))
                .orElseThrow(this::expiredAuthAttempt);
            return new AuthAttempt(pending.getId(), pending.getSessionState());
        }));
        String plainState = port.completeAuth(processId, code);
        if (plainState == null || plainState.isBlank()) {
            throw error(SofidyErrorCode.INVALID_DATA, "Sofidy did not return a session", null);
        }
        return storeSession(memberId, plainState, attempt);
    }

    private AuthAttempt reserveAuthAttempt(Long memberId) {
        return requireTransactionResult(txTemplate.execute(status -> {
            FamilyMember member = lockMember(memberId);
            sessionRepository.findByMemberIdForUpdate(memberId).ifPresent(sessionRepository::delete);
            sessionRepository.flush();
            SofidySession pending = sessionRepository.saveAndFlush(pendingSession(member,
                AUTH_START_PREFIX + UUID.randomUUID()));
            return new AuthAttempt(pending.getId(), pending.getSessionState());
        }));
    }

    private SofidySession pendingSession(FamilyMember member, String marker) {
        return SofidySession.builder().member(member).active(false)
            .sessionState(encryption.encrypt(marker)).lastValidatedAt(Instant.now()).build();
    }

    private FamilyMember lockMember(Long memberId) {
        return sessionRepository.findMemberByIdForUpdate(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
    }

    private boolean isCurrentAttempt(SofidySession session) {
        return session.getLastValidatedAt() != null
            && session.getLastValidatedAt().plus(AUTH_TTL).isAfter(Instant.now());
    }

    private SofidySession currentAttempt(Long memberId, AuthAttempt attempt) {
        return sessionRepository.findByIdAndMemberIdForUpdate(attempt.sessionId(), memberId)
            .filter(session -> !session.isActive() && !session.isSyncInFlight())
            .filter(this::isCurrentAttempt)
            .filter(session -> Objects.equals(session.getSessionState(), attempt.encryptedState()))
            .orElseThrow(this::expiredAuthAttempt);
    }

    private SyncException expiredAuthAttempt() {
        return error(SofidyErrorCode.AUTH_ATTEMPT_EXPIRED,
            "This Sofidy login attempt is no longer valid. Please reconnect.", null);
    }

    private SessionStatusResponse storeSession(Long memberId, String plainState, AuthAttempt attempt) {
        SyncJob job = requireTransactionResult(txTemplate.execute(status -> {
            FamilyMember member = lockMember(memberId);
            sessionRepository.delete(currentAttempt(memberId, attempt));
            sessionRepository.flush();

            SofidySession newSession = SofidySession.create(
                member,
                encryption.encrypt(plainState),
                Instant.now()
            );
            newSession.markQueued();
            SofidySession stored = sessionRepository.saveAndFlush(newSession);
            return new SyncJob(stored.getId(), memberId, plainState);
        }));

        submit(job);
        return getStatus(memberId);
    }

    public SessionStatusResponse queueSync(Long memberId) {
        QueueDecision decision = requireTransactionResult(txTemplate.execute(status -> {
            lockMember(memberId);
            SofidySession session = sessionRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> error(
                    SofidyErrorCode.SESSION_EXPIRED,
                    "No active Sofidy session. Please reconnect.",
                    null
                ));
            if (!session.isActive()) {
                throw error(
                    SofidyErrorCode.SESSION_EXPIRED,
                    "The Sofidy session expired. Please reconnect.",
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
            markFailed(job, SofidyErrorCode.INTERNAL_ERROR);
            throw error(
                SofidyErrorCode.INTERNAL_ERROR,
                "Could not schedule the Sofidy synchronization",
                ex
            );
        }
    }

    private void executeJob(SyncJob job) {
        if (!markRunning(job)) {
            return;
        }
        try {
            SofidyPort.Snapshot snapshot = port.fetchSnapshot(job.plainState());
            commitSnapshot(job, snapshot);
        } catch (SyncException ex) {
            SofidyErrorCode code = codeOf(ex);
            markFailed(job, code);
            log.warn("Sofidy sync failed (member={}; code={})", job.memberId(), code);
        } catch (Exception ex) {
            markFailed(job, SofidyErrorCode.INTERNAL_ERROR);
            log.error("Sofidy sync failed unexpectedly (member={})", job.memberId(), ex);
        }
    }

    /**
     * Writes the snapshot onto the linked accounts.
     *
     * <p>One holding maps to at most one account, matched on Sofidy's own product
     * code the user linked when creating the account. A holding with no linked
     * account is skipped rather than creating one: the manual model owns account
     * creation, and a sync that silently opened a new account would bypass the
     * Immobilier flow a share is supposed to go through.
     *
     * <p>A holding whose withdrawal price is missing still updates the share count
     * -- that is real information -- but leaves the balance alone and reports
     * {@code PRICE_INCOMPLETE}, which is what
     * {@link ScpiPositionService#save} already does.
     */
    private int applySnapshot(Long memberId, SofidyPort.Snapshot snapshot) {
        if (snapshot == null || !snapshot.snapshotComplete()) {
            throw error(
                SofidyErrorCode.PORTFOLIO_INCOMPLETE,
                "Sofidy returned an incomplete portfolio",
                null
            );
        }
        if (snapshot.holdings() == null) {
            throw error(
                SofidyErrorCode.PORTFOLIO_INCOMPLETE,
                "Sofidy returned no fund holding",
                null
            );
        }

        Set<String> seenFunds = new HashSet<>();
        for (SofidyPort.Holding holding : snapshot.holdings()) {
            if (holding == null || holding.fundCode() == null || holding.quantity() == null) {
                throw error(SofidyErrorCode.INVALID_DATA, "Sofidy returned an incomplete fund", null);
            }
            if (!seenFunds.add(holding.fundCode())) {
                // Two lines for one fund would write the quantity twice.
                throw error(SofidyErrorCode.INVALID_DATA, "Sofidy returned a duplicate fund", null);
            }
            if (holding.quantity().signum() < 0) {
                throw error(SofidyErrorCode.INVALID_DATA, "Sofidy returned a negative share count", null);
            }
        }
        checkDeclaredTotal(snapshot);

        int updated = 0;
        for (SofidyPort.Holding holding : snapshot.holdings()) {
            Optional<ScpiPosition> linked =
                positionRepository.findByMemberIdAndSofidyFundCode(memberId, holding.fundCode());
            if (linked.isEmpty()) {
                log.info(
                    "No Picsou account is linked to this Sofidy fund (member={}; fund={})",
                    memberId,
                    holding.fundCode()
                );
                continue;
            }
            // Null subscription price: Sofidy publishes the redemption value and
            // nothing else, and the manual form treats that price as display-only.
            // A null here means "not quoted", so a price the user typed by hand
            // survives the sync instead of being deleted by it.
            positionService.applySyncedPosition(
                linked.get(),
                holding.quantity(),
                null,
                holding.withdrawalPrice(),
                snapshot.valuationDate()
            );
            updated++;
        }
        return updated + reconcileSoldFunds(memberId, snapshot.holdings());
    }

    /**
     * Reconciles the funds against the total the snapshot declares.
     *
     * <p>The sidecar already does this against the page, and repeating it here
     * is deliberate: the sidecar is a separate deployable, so its check is
     * exactly the thing a rolling update or a stale image would leave out. A
     * partial read has to be caught before a single balance is written, not
     * after.
     */
    private void checkDeclaredTotal(SofidyPort.Snapshot snapshot) {
        BigDecimal declared = snapshot.totalEur();
        if (declared == null || declared.signum() < 0) {
            throw error(SofidyErrorCode.PORTFOLIO_INCOMPLETE,
                "Sofidy returned no valid portfolio total", null);
        }
        BigDecimal computed = snapshot.holdings().stream()
            .map(SofidyPort.Holding::withdrawalValue)
            .filter(java.util.Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (computed.subtract(declared).abs().compareTo(MONEY_TOLERANCE) > 0) {
            throw error(
                SofidyErrorCode.PORTFOLIO_INCOMPLETE,
                "The Sofidy funds add up to " + computed.setScale(2, java.math.RoundingMode.HALF_UP)
                    + " but the snapshot declares " + declared.setScale(2, java.math.RoundingMode.HALF_UP),
                null
            );
        }
    }

    /**
     * Zeroes the linked positions a complete snapshot no longer lists.
     *
     * <p>A complete snapshot is authoritative in both directions: what it lists
     * was sold. Leaving a sold position at its last balance would keep a holding
     * in the net worth the member exited, which is the one error a snapshot
     * cannot be allowed to make -- the one the manual entry is not there to
     * correct. An empty snapshot is the full-exit case, and it is exactly the
     * one that has to reach every linked position.
     *
     * <p>The prices are passed through as they are: {@code 0 x anything} is 0,
     * and a price Sofidy no longer publishes should not turn a closed position
     * back into {@code PRICE_INCOMPLETE}.
     *
     * @return how many positions were closed
     */
    private int reconcileSoldFunds(Long memberId, List<SofidyPort.Holding> holdings) {
        Set<String> stillHeld = holdings.stream()
            .map(SofidyPort.Holding::fundCode)
            .collect(Collectors.toSet());
        int closed = 0;
        for (ScpiPosition position
                : positionRepository.findByAccountMemberIdAndSofidyFundCodeIsNotNull(memberId)) {
            if (stillHeld.contains(position.getSofidyFundCode())) {
                continue;
            }
            log.info(
                "Sofidy no longer lists a linked fund, closing the position "
                    + "(member={}; fund={})",
                memberId,
                position.getSofidyFundCode()
            );
            positionService.applySyncedPosition(
                position,
                BigDecimal.ZERO,
                null,
                position.getWithdrawalPriceEur(),
                null
            );
            closed++;
        }
        return closed;
    }

    private boolean markRunning(SyncJob job) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<SofidySession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("Sofidy sync session disappeared before execution (member={})", job.memberId());
                return false;
            }
            SofidySession session = current.get();
            if (!session.isActive() || session.getSyncStatus() != SofidySyncStatus.QUEUED) {
                log.warn(
                    "Sofidy sync cannot start from state {} (member={}; active={})",
                    session.getSyncStatus(), job.memberId(), session.isActive()
                );
                return false;
            }
            session.markRunning(Instant.now());
            sessionRepository.save(session);
            return true;
        }));
    }

    private void commitSnapshot(SyncJob job, SofidyPort.Snapshot snapshot) {
        txTemplate.executeWithoutResult(status -> {
            lockMember(job.memberId());
            Optional<SofidySession> current = sessionRepository
                .findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId());
            if (current.isEmpty() || !current.get().isActive()
                    || current.get().getSyncStatus() != SofidySyncStatus.RUNNING) {
                return;
            }
            int updated = applySnapshot(job.memberId(), snapshot);
            current.get().markSuccessful(Instant.now());
            sessionRepository.save(current.get());
            log.info("Sofidy sync completed (member={}; positions={})", job.memberId(), updated);
        });
    }

    private void markFailed(SyncJob job, SofidyErrorCode code) {
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

    private SessionStatusResponse toStatus(SofidySession session) {
        return new SessionStatusResponse(
            session.isActive(),
            session.getSyncStatus(),
            session.getLastSyncStartedAt(),
            session.getLastSyncCompletedAt(),
            session.getLastSyncError()
        );
    }

    private SofidyErrorCode codeOf(SyncException exception) {
        return errorCode(exception, SofidyErrorCode.class, SofidyErrorCode.UPSTREAM_UNAVAILABLE);
    }

    private SyncException error(SofidyErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    /**
     * What {@link #initiateAuth} needs the panel to show next.
     *
     * <p>It carries the session status alongside the init fields because the
     * panel writes this response straight into its status cache: a login that
     * opened a session directly has to land there already populated, or the
     * panel would show a connected account it cannot read.
     */
    public record InitiateResponse(
        String processId,
        boolean mfaRequired,
        String mfaType,
        boolean isActive,
        SofidySyncStatus syncStatus,
        SofidyErrorCode lastSyncError,
        java.time.Instant lastSyncStartedAt,
        java.time.Instant lastSyncCompletedAt
    ) {
        /** The pending state: no session exists yet, so the status is all zeroes. */
        public static InitiateResponse pending(String processId, String mfaType) {
            return new InitiateResponse(
                processId, true, mfaType, false,
                SofidySyncStatus.IDLE, null, null, null
            );
        }
    }

    public record SessionStatusResponse(
        boolean isActive,
        SofidySyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        SofidyErrorCode lastSyncError
    ) {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, SofidySyncStatus.IDLE, null, null, null);
        }
    }

    private record AuthAttempt(Long sessionId, String encryptedState) {}

    private record QueueDecision(SyncJob job, SessionStatusResponse status) {}

    private record SyncJob(Long sessionId, Long memberId, String plainState) {}
}
