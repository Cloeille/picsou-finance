package com.picsou.service;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.FamilyMember;
import com.picsou.model.ScpiFundOutcome;
import com.picsou.model.ScpiPosition;
import com.picsou.model.ScpiProvider;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.port.SidecarInitiate;
import com.picsou.port.SidecarErrorCode;
import com.picsou.adapter.SofidyAdapter;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.ScpiPositionRepository;
import com.picsou.repository.SidecarSessionRepository;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

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

    private final SofidyAdapter port;
    private final SidecarSessionRepository sessionRepository;
    private final ScpiPositionRepository positionRepository;
    private final ScpiPositionService positionService;
    private final FamilyMemberRepository memberRepository;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final SidecarSyncTemplate<SofidyAdapter.Snapshot> sync;
    private final ScpiFundReconciler fundReconciler;

    public SofidySyncService(
        SofidyAdapter port,
        SidecarSessionRepository sessionRepository,
        ScpiPositionRepository positionRepository,
        ScpiPositionService positionService,
        FamilyMemberRepository memberRepository,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("sofidySyncExecutor") Executor syncExecutor,
        ScpiFundReconciler fundReconciler
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.positionRepository = positionRepository;
        this.positionService = positionService;
        this.memberRepository = memberRepository;
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.sync = new SidecarSyncTemplate<>(SidecarBank.SOFIDY, new Strategy(), sessionRepository,
            memberRepository, encryption, txTemplate, syncExecutor, true);
        this.fundReconciler = fundReconciler;
    }

    private final class Strategy implements SidecarSyncStrategy<SofidyAdapter.Snapshot> {
        @Override
        public SofidyAdapter.Snapshot fetch(SidecarSyncJob job) {
            return port.fetchSnapshot(job.plainState());
        }

        @Override
        public void commit(SidecarSyncJob job, SofidyAdapter.Snapshot snapshot, Instant syncedAt) {
            Map<String, ScpiFundOutcome> outcomes = applySnapshot(job.memberId(), snapshot, syncedAt);
            log.info("Sofidy fund outcomes (member={}; funds={})", job.memberId(), outcomes);
        }
    }

    /**
     * Starts a login. Sofidy always answers with a pending process id, so the
     * session is null and {@code mfaRequired} is true; the shape is kept anyway
     * because a portal that stops asking for a code would then work without a
     * change here.
     */
    public InitiateResponse initiateAuth(String associateCode, String password, Long memberId) {
        AuthAttempt attempt = reserveAuthAttempt(memberId);
        SidecarInitiate result = port.initiateAuth(associateCode, password);
        if (result == null) {
            throw error(SidecarErrorCode.INVALID_DATA, "Sofidy did not answer the login request", null);
        }
        if (!result.mfaRequired()) {
            if (result.sessionState() == null || result.sessionState().isBlank()) {
                throw error(
                    SidecarErrorCode.INVALID_DATA,
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
                SidecarErrorCode.INVALID_DATA,
                "Sofidy asked for a code without giving an attempt to complete",
                null
            );
        }
        txTemplate.executeWithoutResult(status -> {
            FamilyMember member = lockMember(memberId);
            SidecarSession current = currentAttempt(memberId, attempt);
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
            SidecarSession pending = sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.SOFIDY, memberId)
                .filter(session -> !session.isActive() && !session.isSyncInFlight())
                .filter(session -> isCurrentAttempt(session))
                .filter(session -> processId != null && !processId.isBlank()
                    && (AUTH_PROCESS_PREFIX + processId).equals(encryption.decrypt(session.getSessionState())))
                .orElseThrow(this::expiredAuthAttempt);
            return new AuthAttempt(pending.getId(), pending.getSessionState());
        }));
        String plainState = port.completeAuth(processId, code);
        if (plainState == null || plainState.isBlank()) {
            throw error(SidecarErrorCode.INVALID_DATA, "Sofidy did not return a session", null);
        }
        return storeSession(memberId, plainState, attempt);
    }

    private AuthAttempt reserveAuthAttempt(Long memberId) {
        return requireTransactionResult(txTemplate.execute(status -> {
            FamilyMember member = lockMember(memberId);
            sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.SOFIDY, memberId).ifPresent(sessionRepository::delete);
            sessionRepository.flush();
            SidecarSession pending = sessionRepository.saveAndFlush(pendingSession(member,
                AUTH_START_PREFIX + UUID.randomUUID()));
            return new AuthAttempt(pending.getId(), pending.getSessionState());
        }));
    }

    private SidecarSession pendingSession(FamilyMember member, String marker) {
        return SidecarSession.builder().bank(SidecarBank.SOFIDY).member(member).active(false)
            .sessionState(encryption.encrypt(marker)).lastValidatedAt(Instant.now()).build();
    }

    private FamilyMember lockMember(Long memberId) {
        return sync.lockMember(memberId);
    }

    private boolean isCurrentAttempt(SidecarSession session) {
        return session.getLastValidatedAt() != null
            && session.getLastValidatedAt().plus(AUTH_TTL).isAfter(Instant.now());
    }

    private SidecarSession currentAttempt(Long memberId, AuthAttempt attempt) {
        return sessionRepository.findByIdAndMemberIdForUpdate(attempt.sessionId(), memberId)
            .filter(session -> !session.isActive() && !session.isSyncInFlight())
            .filter(this::isCurrentAttempt)
            .filter(session -> Objects.equals(session.getSessionState(), attempt.encryptedState()))
            .orElseThrow(this::expiredAuthAttempt);
    }

    private SyncException expiredAuthAttempt() {
        return error(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED,
            "This Sofidy login attempt is no longer valid. Please reconnect.", null);
    }

    private SessionStatusResponse storeSession(Long memberId, String plainState, AuthAttempt attempt) {
        SidecarSyncJob job = requireTransactionResult(txTemplate.execute(status -> {
            lockMember(memberId);
            currentAttempt(memberId, attempt);
            return sync.insertQueued(memberId, plainState, false, session -> {});
        }));
        sync.submit(job);
        return getStatus(memberId);
    }

    public SessionStatusResponse queueSync(Long memberId) {
        return toStatus(sync.queue(memberId, false));
    }

    @Transactional(readOnly = true)
    public SessionStatusResponse getStatus(Long memberId) {
        return sync.find(memberId).map(this::toStatus).orElseGet(SessionStatusResponse::inactive);
    }

    @Transactional
    public void clearSession(Long memberId) {
        sync.clear(memberId);
    }

    /**
     * Writes the snapshot onto the member's SCPI accounts.
     *
     * <p>A fund already linked to an account updates it. A fund seen for the first
     * time adopts the one manual SCPI account named like it, or gets a new account;
     * a fund the member ignored writes nothing. {@link ScpiFundReconciler} owns those
     * rules. Sofidy quotes no subscription price, so null is passed and a price the
     * user typed by hand survives the sync.
     *
     * <p>A linked fund Sofidy no longer lists is then closed at zero shares by
     * {@link #reconcileSoldFunds}; its account is kept, never deleted.
     */
    private Map<String, ScpiFundOutcome> applySnapshot(
        Long memberId, SofidyAdapter.Snapshot snapshot, Instant syncedAt
    ) {
        if (snapshot == null || !snapshot.snapshotComplete()) {
            throw error(
                SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "Sofidy returned an incomplete portfolio",
                null
            );
        }
        if (snapshot.holdings() == null) {
            throw error(
                SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "Sofidy returned no fund holding",
                null
            );
        }

        Set<String> seenFunds = new HashSet<>();
        for (SofidyAdapter.Holding holding : snapshot.holdings()) {
            if (holding == null || holding.fundCode() == null || holding.quantity() == null) {
                throw error(SidecarErrorCode.INVALID_DATA, "Sofidy returned an incomplete fund", null);
            }
            if (!seenFunds.add(holding.fundCode())) {
                // Two lines for one fund would write the quantity twice.
                throw error(SidecarErrorCode.INVALID_DATA, "Sofidy returned a duplicate fund", null);
            }
            if (holding.quantity().signum() < 0) {
                throw error(SidecarErrorCode.INVALID_DATA, "Sofidy returned a negative share count", null);
            }
        }
        checkDeclaredTotal(snapshot);
        // Every SCPI account is EUR and the reconciler writes the figures as they
        // come. A snapshot in another currency, or with none, would be stored as
        // euros silently, so it is refused before anything is written.
        if (!"EUR".equals(snapshot.currency())) {
            throw error(SidecarErrorCode.INVALID_DATA, "Sofidy returned a portfolio not in euros", null);
        }

        List<ScpiFundReconciler.SyncedFund> funds = snapshot.holdings().stream()
            .map(holding -> new ScpiFundReconciler.SyncedFund(
                holding.fundCode(),
                holding.label(),
                holding.quantity(),
                null,
                holding.withdrawalPrice()
            ))
            .toList();
        Map<String, ScpiFundOutcome> outcomes =
            fundReconciler.reconcile(memberId, ScpiProvider.SOFIDY, funds, syncedAt);
        reconcileSoldFunds(memberId, snapshot.holdings());
        return outcomes;
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
    private void checkDeclaredTotal(SofidyAdapter.Snapshot snapshot) {
        BigDecimal declared = snapshot.totalEur();
        if (declared == null || declared.signum() < 0) {
            throw error(SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "Sofidy returned no valid portfolio total", null);
        }
        BigDecimal computed = snapshot.holdings().stream()
            .map(SofidyAdapter.Holding::withdrawalValue)
            .filter(java.util.Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (computed.subtract(declared).abs().compareTo(MONEY_TOLERANCE) > 0) {
            throw error(
                SidecarErrorCode.PORTFOLIO_INCOMPLETE,
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
    private int reconcileSoldFunds(Long memberId, List<SofidyAdapter.Holding> holdings) {
        Set<String> stillHeld = holdings.stream()
            .map(SofidyAdapter.Holding::fundCode)
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

    private SessionStatusResponse toStatus(SidecarSession session) {
        return new SessionStatusResponse(
            session.isActive(),
            session.getSyncStatus(),
            session.getLastSyncStartedAt(),
            session.getLastSyncCompletedAt(),
            session.getLastSyncError()
        );
    }

    private SidecarErrorCode codeOf(SyncException exception) {
        return sync.codeOf(exception);
    }

    private SyncException error(SidecarErrorCode code, String message, Throwable cause) {
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
        SidecarSyncStatus syncStatus,
        SidecarErrorCode lastSyncError,
        java.time.Instant lastSyncStartedAt,
        java.time.Instant lastSyncCompletedAt
    ) {
        /** The pending state: no session exists yet, so the status is all zeroes. */
        public static InitiateResponse pending(String processId, String mfaType) {
            return new InitiateResponse(
                processId, true, mfaType, false,
                SidecarSyncStatus.IDLE, null, null, null
            );
        }
    }

    public record SessionStatusResponse(
        boolean isActive,
        SidecarSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        SidecarErrorCode lastSyncError
    ) implements SidecarSessionView {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, SidecarSyncStatus.IDLE, null, null, null);
        }
    }

    private record AuthAttempt(Long sessionId, String encryptedState) {}

}
