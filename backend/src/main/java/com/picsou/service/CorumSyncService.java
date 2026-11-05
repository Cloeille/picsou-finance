package com.picsou.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.config.CryptoEncryption;
import com.picsou.config.RateLimitConfig;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.CorumSession;
import com.picsou.model.CorumSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.model.ScpiFundOutcome;
import com.picsou.model.ScpiProvider;
import com.picsou.port.CorumErrorCode;
import com.picsou.port.CorumPort;
import com.picsou.repository.CorumSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.service.sync.SourceSyncResult;
import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static com.picsou.service.SyncValues.errorCode;
import static com.picsou.service.SyncValues.requireTransactionResult;

/**
 * Syncs a CORUM client-space portfolio: the funds of its real-estate contract
 * onto SCPI accounts, and each CORUM Life (assurance-vie) contract onto its own
 * account.
 *
 * <p>Shaped like {@link AmundiSyncService}: the browser work happens in a
 * sidecar and is slow, so authentication stores the session and queues the
 * import, and the import does its upstream I/O outside any transaction before
 * writing. {@link ScpiFundReconciler} maps each real-estate fund onto one SCPI
 * account, adopting or creating it; {@link CorumLifeWriter} does the same for a
 * life contract. A login may hold either part or both, but not neither.
 *
 * <p>The two parts are validated before either is written, and both are written
 * in one transaction: a refused life contract leaves the SCPI accounts as they
 * were, and the reverse.
 *
 * <p>The session is handled like {@link CmesSyncService}'s: when the member asks
 * for it at login, the credentials are stored encrypted, and a sync that finds
 * its session expired logs in again with them, once, before reading. Without
 * stored credentials an expired session still waits for the member.
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
    private static final String SOURCE = "corum";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CorumPort port;
    private final CorumSessionRepository sessionRepository;
    private final FamilyMemberRepository memberRepository;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final Executor syncExecutor;
    private final ScpiFundReconciler fundReconciler;
    private final CorumLifeWriter lifeWriter;
    /**
     * Each member's unattended re-logins, throttled on the CORUM login budget
     * ({@link RateLimitConfig#createCorumAuthBucket}) apart from the {@code /api/corum/sync}
     * bucket: every re-login is a real credential check against CORUM.
     */
    private final Map<Long, Bucket> reloginBuckets;
    /**
     * Bumped by {@link #clearSession}: a login that was waiting on CORUM when the
     * connection was removed carries an older generation and stores nothing, so
     * a disconnect cannot be undone by a login (and its password) landing after it.
     * Bumped and checked under the member row lock.
     */
    private final Map<Long, Long> authGenerations = new ConcurrentHashMap<>();

    public CorumSyncService(
        CorumPort port,
        CorumSessionRepository sessionRepository,
        FamilyMemberRepository memberRepository,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("corumSyncExecutor") Executor syncExecutor,
        ScpiFundReconciler fundReconciler,
        CorumLifeWriter lifeWriter,
        @Qualifier("corumReloginBuckets") Map<Long, Bucket> reloginBuckets
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.memberRepository = memberRepository;
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.syncExecutor = syncExecutor;
        this.fundReconciler = fundReconciler;
        this.lifeWriter = lifeWriter;
        this.reloginBuckets = reloginBuckets;
    }

    /**
     * Logs in and queues the import. With {@code remember} the credentials are
     * stored encrypted for the unattended re-login; without it none are, and any
     * stored by an earlier login go with the session this one replaces.
     */
    public SessionStatusResponse authenticate(String login, String password, boolean remember, Long memberId) {
        long generation = authGeneration(memberId);
        String plainState = port.authenticate(login, password);
        if (plainState == null || plainState.isBlank()) {
            throw error(CorumErrorCode.INVALID_DATA, "CORUM did not return a session", null);
        }
        SyncJob job = requireTransactionResult(txTemplate.execute(status -> {
            lockMember(memberId);
            if (authGeneration(memberId) != generation) {
                throw error(CorumErrorCode.SESSION_EXPIRED, "The CORUM connection was removed during the login", null);
            }
            FamilyMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            sessionRepository.findByMemberIdForUpdate(memberId).ifPresent(sessionRepository::delete);
            sessionRepository.flush();

            CorumSession newSession = CorumSession.create(
                member,
                encryption.encrypt(plainState),
                Instant.now()
            );
            if (remember) {
                newSession.setEncryptedCredentials(encryption.encrypt(serializeCredentials(login, password)));
            }
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

    /**
     * Deletes the session and the credentials stored with it. A login still
     * waiting on CORUM is cancelled too: it stores nothing once it lands.
     */
    @Transactional
    public void clearSession(Long memberId) {
        lockMember(memberId);
        authGenerations.merge(memberId, 1L, Long::sum);
        sessionRepository.findByMemberIdForUpdate(memberId).ifPresent(sessionRepository::delete);
    }

    /**
     * The daily sync's entry, shaped like {@link CmesSyncService#resyncReporting}: an
     * active session is queued (and logs in again by itself if it has expired and
     * credentials are stored); an inactive one is reported, never retried.
     */
    public SourceSyncResult resyncReporting(Long memberId) {
        try {
            SessionStatusResponse status = getStatus(memberId);
            if (!status.isActive()) {
                if (status.lastSyncError() == CorumErrorCode.SESSION_EXPIRED
                    || status.lastSyncError() == CorumErrorCode.INVALID_CREDENTIALS) {
                    return new SourceSyncResult(SOURCE, SourceSyncResult.Status.NEEDS_REAUTH,
                        status.lastSyncError().name());
                }
                if (status.lastSyncError() != null) {
                    return new SourceSyncResult(SOURCE, SourceSyncResult.Status.FAILED,
                        status.lastSyncError().name());
                }
                return new SourceSyncResult(SOURCE, SourceSyncResult.Status.SKIPPED_NOT_CONNECTED, "No active session");
            }
            queueSync(memberId);
            return new SourceSyncResult(SOURCE, SourceSyncResult.Status.QUEUED, "");
        } catch (ResourceNotFoundException ex) {
            return new SourceSyncResult(SOURCE, SourceSyncResult.Status.SKIPPED_NOT_CONNECTED, "Member not found");
        } catch (DataAccessException ex) {
            log.error("Database error during scheduled CORUM sync (member={})", memberId, ex);
            return new SourceSyncResult(SOURCE, SourceSyncResult.Status.FAILED, "Database error");
        } catch (SyncException ex) {
            log.warn("Could not queue scheduled CORUM sync (member={}; code={})", memberId, ex.getCode(), ex);
            SourceSyncResult classified = SourceSyncResult.fromSyncException(SOURCE, ex);
            return new SourceSyncResult(SOURCE, classified.status(),
                classified.status() == SourceSyncResult.Status.NEEDS_REAUTH ? ex.getCode() : "Sync failed");
        } catch (Exception ex) {
            log.error("CORUM scheduled sync failed unexpectedly for member {}", memberId, ex);
            return new SourceSyncResult(SOURCE, SourceSyncResult.Status.FAILED, "Unexpected sync error");
        }
    }

    private long authGeneration(Long memberId) {
        return authGenerations.getOrDefault(memberId, 0L);
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

    /**
     * Runs a queued sync to its end: fetches the portfolio, logging in again once if the session expired,
     * and commits it. A failure is recorded on the session rather than thrown, so a run never leaves it RUNNING.
     */
    private void executeJob(SyncJob job) {
        if (!markRunning(job)) {
            return;
        }
        try {
            CorumPort.Portfolio portfolio;
            try {
                portfolio = port.fetchPortfolio(job.plainState());
            } catch (SyncException ex) {
                if (codeOf(ex) != CorumErrorCode.SESSION_EXPIRED) throw ex;
                // One re-login per run, never a loop: its own failure ends the run.
                portfolio = reauthenticateAndFetch(job);
            }
            commitPortfolio(job, portfolio);
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
     * The stored session has expired. With stored credentials the sync logs in
     * again through the sidecar, replaces the session and reads with the new one;
     * without them the run ends as SESSION_EXPIRED and the member reconnects. A
     * rejected password surfaces as INVALID_CREDENTIALS, which {@link #markFailed}
     * turns into cleared credentials and an inactive session. Credentials that no
     * longer decrypt or parse are cleared too, since no run could ever use them.
     *
     * <p>A member's re-logins draw on their own bucket; once it is spent the run
     * ends as UPSTREAM_UNAVAILABLE without calling CORUM. That code keeps the
     * session active and the credentials stored, so a later run logs in again
     * once the budget has refilled: being throttled is no reason to make the
     * member type the password again.
     */
    private CorumPort.Portfolio reauthenticateAndFetch(SyncJob job) {
        CorumSession session = requireTransactionResult(txTemplate.execute(
            status -> sessionRepository.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
        )).orElse(null);
        if (session == null || !session.isActive()
            || session.getSyncStatus() != CorumSyncStatus.RUNNING
            || session.getEncryptedCredentials() == null) {
            throw error(CorumErrorCode.SESSION_EXPIRED, "CORUM session expired", null);
        }
        Credentials credentials;
        try {
            credentials = parseCredentials(encryption.decrypt(session.getEncryptedCredentials()));
        } catch (RuntimeException ex) {
            // Only the type: neither the stored value nor what it decrypted to belongs in a log.
            log.warn("CORUM stored credentials are unreadable and were cleared (member={}; type={})",
                job.memberId(), ex.getClass().getSimpleName());
            clearStoredCredentials(job);
            throw error(CorumErrorCode.SESSION_EXPIRED, "CORUM session expired", null);
        }
        if (!reloginBuckets.computeIfAbsent(job.memberId(), key -> RateLimitConfig.createCorumAuthBucket())
                .tryConsume(1)) {
            log.warn("CORUM re-login throttled (member={})", job.memberId());
            throw error(CorumErrorCode.UPSTREAM_UNAVAILABLE, "Too many CORUM re-logins. Please try again later.", null);
        }
        String newState;
        try {
            newState = port.authenticate(credentials.login(), credentials.password());
        } catch (SyncException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Not a translated sidecar error: its message or causes may quote the
            // request, password included, so neither is kept nor logged.
            log.error("CORUM re-login failed unexpectedly (member={}; type={})",
                job.memberId(), ex.getClass().getName());
            throw error(CorumErrorCode.INTERNAL_ERROR, "CORUM re-login failed", null);
        }
        if (newState == null || newState.isBlank()) {
            throw error(CorumErrorCode.INVALID_DATA, "CORUM returned no session", null);
        }
        Boolean updated = txTemplate.execute(status -> {
            lockMember(job.memberId());
            return sessionRepository.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
                .filter(current -> current.isActive() && current.getSyncStatus() == CorumSyncStatus.RUNNING)
                .map(current -> {
                    current.updateSessionState(encryption.encrypt(newState), Instant.now());
                    sessionRepository.save(current);
                    return true;
                })
                .orElse(false);
        });
        if (!Boolean.TRUE.equals(updated)) {
            throw error(CorumErrorCode.SESSION_EXPIRED, "CORUM session changed", null);
        }
        log.info("CORUM re-logged in with stored credentials (member={})", job.memberId());
        return port.fetchPortfolio(newState);
    }

    /**
     * The login and password as the JSON that {@link #parseCredentials} reads back. Plaintext: it is
     * encrypted before it is stored.
     */
    private String serializeCredentials(String login, String password) {
        try {
            return JSON.writeValueAsString(Map.of("login", login, "password", password));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not encode credentials");
        }
    }

    /**
     * Reads credentials back from {@link #serializeCredentials}. Malformed JSON or a blank login or
     * password is refused, so a damaged value is never replayed as empty credentials.
     */
    private Credentials parseCredentials(String value) {
        try {
            JsonNode node = JSON.readTree(value);
            String login = node.path("login").asText();
            String password = node.path("password").asText();
            if (login.isBlank() || password.isBlank()) throw new IllegalArgumentException("Invalid credentials");
            return new Credentials(login, password);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid credentials", ex);
        }
    }

    /**
     * Refuses a portfolio that cannot be written as it is, before anything is.
     * The real-estate part is optional, but when present it must hold at least
     * one fund; the life part is validated by {@link CorumLifeWriter#validate}.
     */
    private void validatePortfolio(CorumPort.Portfolio portfolio) {
        if (portfolio == null) {
            throw error(CorumErrorCode.PORTFOLIO_INCOMPLETE, "CORUM returned no portfolio", null);
        }
        lifeWriter.validate(portfolio.life());
        if (portfolio.realEstate() == null) {
            if (portfolio.life().isEmpty()) {
                throw error(
                    CorumErrorCode.PORTFOLIO_INCOMPLETE,
                    "CORUM returned neither a real-estate nor a life contract",
                    null
                );
            }
            return;
        }
        validateRealEstate(portfolio.realEstate());
    }

    /**
     * Refuses a real-estate snapshot that cannot be written as it is: incomplete, without funds, a fund
     * without a code or quantity, a duplicate or negative one, a declared total that does not match, or a
     * currency other than EUR.
     */
    private void validateRealEstate(CorumPort.Snapshot snapshot) {
        if (!snapshot.snapshotComplete()) {
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
        checkDeclaredTotal(snapshot);
        // Every SCPI account is EUR and the reconciler writes the figures as they
        // come. A snapshot in another currency, or with none, would be stored as
        // euros silently, so it is refused before anything is written.
        if (!"EUR".equals(snapshot.currency())) {
            throw error(CorumErrorCode.INVALID_DATA, "CORUM returned a portfolio not in euros", null);
        }
    }

    /**
     * Writes a validated real-estate contract onto the member's SCPI accounts.
     *
     * <p>A fund already linked to an account updates it. A fund seen for the first
     * time adopts the one manual SCPI account named like it, or gets a new account;
     * a fund the member ignored writes nothing. {@link ScpiFundReconciler} owns those
     * rules, and the withdrawal-price rule stays in
     * {@link ScpiPositionService#applySyncedPosition}: a holding whose withdrawal
     * price is missing still updates the share count but leaves the balance alone.
     */
    private Map<String, ScpiFundOutcome> applyRealEstate(
        Long memberId, CorumPort.Snapshot snapshot, Instant syncedAt
    ) {
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
    private void checkDeclaredTotal(CorumPort.Snapshot snapshot) {
        BigDecimal declared = snapshot.totalValuationEur();
        if (declared == null || declared.signum() < 0) {
            throw error(CorumErrorCode.PORTFOLIO_INCOMPLETE,
                "CORUM returned no valid portfolio total", null);
        }
        BigDecimal computed = BigDecimal.ZERO;
        for (CorumPort.Holding holding : snapshot.holdings()) {
            if (holding.displayedValueEur() == null) {
                throw error(CorumErrorCode.PORTFOLIO_INCOMPLETE,
                    "CORUM returned a fund without a displayed value", null);
            }
            computed = computed.add(holding.displayedValueEur());
        }
        if (computed.subtract(declared).abs().compareTo(MONEY_TOLERANCE) > 0) {
            throw error(
                CorumErrorCode.PORTFOLIO_INCOMPLETE,
                "The CORUM funds add up to " + computed.setScale(2, RoundingMode.HALF_UP)
                    + " but the snapshot declares " + declared.setScale(2, RoundingMode.HALF_UP),
                null
            );
        }
    }

    /**
     * Moves the job's session from QUEUED to RUNNING under the member lock. False when the session is gone,
     * inactive or no longer queued (disconnected or replaced meanwhile), in which case the job does nothing.
     */
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

    /**
     * Writes the portfolio in one transaction under the member lock, only if the session is still active
     * and RUNNING, so a disconnect or replacement meanwhile discards the snapshot. Both parts are validated
     * before either is written, and a failure rolls the whole sync back.
     */
    private void commitPortfolio(SyncJob job, CorumPort.Portfolio portfolio) {
        txTemplate.executeWithoutResult(status -> {
            lockMember(job.memberId());
            Optional<CorumSession> current = sessionRepository
                .findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId());
            if (current.isEmpty() || !current.get().isActive()
                    || current.get().getSyncStatus() != CorumSyncStatus.RUNNING) {
                return;
            }
            // Both parts are checked before either is written; a failure past this
            // point rolls the whole transaction back, so the sync is all-or-nothing.
            validatePortfolio(portfolio);
            Instant now = Instant.now();
            Map<String, ScpiFundOutcome> funds = portfolio.realEstate() == null
                ? Map.of()
                : applyRealEstate(job.memberId(), portfolio.realEstate(), now);
            Map<String, CorumLifeWriter.Outcome> contracts =
                lifeWriter.write(job.memberId(), portfolio.life(), now);
            // Outcome counts only: the map is keyed by contract number, which stays out of the logs.
            Map<CorumLifeWriter.Outcome, Long> lifeOutcomes = contracts.values().stream()
                .collect(Collectors.groupingBy(o -> o,
                    () -> new EnumMap<>(CorumLifeWriter.Outcome.class), Collectors.counting()));
            // The panel reads this count, as it reads each SCPI fund's AMBIGUOUS outcome:
            // a contract written nowhere must not show as a plain success.
            current.get().markSuccessful(now,
                lifeOutcomes.getOrDefault(CorumLifeWriter.Outcome.AMBIGUOUS, 0L).intValue());
            sessionRepository.save(current.get());
            log.info("CORUM sync completed (member={}; funds={}; lifeContracts={})",
                job.memberId(), funds, lifeOutcomes);
        });
    }

    private void lockMember(Long memberId) {
        sessionRepository.findMemberByIdForUpdate(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
    }

    /**
     * Records the failure on the session if it is still in flight. A rejected password also clears the
     * stored credentials, so no later sync replays it.
     */
    private void markFailed(SyncJob job, CorumErrorCode code) {
        txTemplate.executeWithoutResult(status -> {
            lockMember(job.memberId());
            sessionRepository.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
                .filter(session -> session.isActive() && session.isSyncInFlight())
                .ifPresent(session -> {
                    // A rejected password must not be replayed by every scheduled
                    // sync: the site may lock the access after a few failures.
                    if (code == CorumErrorCode.INVALID_CREDENTIALS) session.setEncryptedCredentials(null);
                    session.markFailed(code, Instant.now());
                    sessionRepository.save(session);
                });
        });
    }

    /** Clears the job's stored credentials, under the member lock, if the session is still in flight. */
    private void clearStoredCredentials(SyncJob job) {
        txTemplate.executeWithoutResult(status -> {
            lockMember(job.memberId());
            sessionRepository.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
                .filter(CorumSession::isSyncInFlight)
                .ifPresent(current -> {
                    current.setEncryptedCredentials(null);
                    sessionRepository.save(current);
                });
        });
    }

    private SessionStatusResponse toStatus(CorumSession session) {
        return new SessionStatusResponse(
            session.isActive(),
            session.getSyncStatus(),
            session.getLastSyncStartedAt(),
            session.getLastSyncCompletedAt(),
            session.getLastSyncError(),
            session.getLastSyncLifeAmbiguous(),
            session.getEncryptedCredentials() != null
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
        CorumErrorCode lastSyncError,
        /** CORUM Life contracts the last successful sync left unwritten (AMBIGUOUS). */
        int lifeAmbiguousCount,
        /** Whether credentials are stored for the unattended re-login. Never the credentials themselves. */
        boolean credentialsRemembered
    ) {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, CorumSyncStatus.IDLE, null, null, null, 0, false);
        }
    }

    private record Credentials(String login, String password) {
        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }

    private record QueueDecision(SyncJob job, SessionStatusResponse status) {}

    private record SyncJob(Long sessionId, Long memberId, String plainState) {}
}
