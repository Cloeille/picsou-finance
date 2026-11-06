package com.picsou.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.CorumAdapter;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.config.CryptoEncryption;
import com.picsou.config.RateLimitConfig;
import com.picsou.exception.SyncException;
import com.picsou.model.ScpiFundOutcome;
import com.picsou.model.ScpiProvider;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.port.SidecarErrorCode;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SidecarSessionRepository;
import com.picsou.service.sync.SourceSyncResult;
import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

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

    private final CorumAdapter port;
    private final SidecarSessionRepository sessionRepository;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final SidecarSyncTemplate<CorumAdapter.Portfolio> sync;
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
        CorumAdapter port,
        SidecarSessionRepository sessionRepository,
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
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.sync = new SidecarSyncTemplate<>(SidecarBank.CORUM, new Strategy(), sessionRepository,
            memberRepository, encryption, txTemplate, syncExecutor, true);
        this.fundReconciler = fundReconciler;
        this.lifeWriter = lifeWriter;
        this.reloginBuckets = reloginBuckets;
    }

    private final class Strategy implements SidecarSyncStrategy<CorumAdapter.Portfolio> {
        /**
         * Reads the portfolio, logging in again once if the session expired, and validates both
         * parts before the commit writes either.
         */
        @Override
        public CorumAdapter.Portfolio fetch(SidecarSyncJob job) {
            CorumAdapter.Portfolio portfolio;
            try {
                portfolio = port.fetchPortfolio(job.plainState());
            } catch (SyncException ex) {
                if (codeOf(ex) != SidecarErrorCode.SESSION_EXPIRED) throw ex;
                // One re-login per run, never a loop: its own failure ends the run.
                portfolio = reauthenticateAndFetch(job);
            }
            validatePortfolio(portfolio);
            return portfolio;
        }

        /**
         * Writes both parts in the template's one transaction, so a failure rolls the whole sync
         * back, and records the life contracts left unwritten on the session.
         */
        @Override
        public void commit(SidecarSyncJob job, CorumAdapter.Portfolio portfolio, Instant syncedAt) {
            Map<String, ScpiFundOutcome> funds = portfolio.realEstate() == null
                ? Map.of()
                : applyRealEstate(job.memberId(), portfolio.realEstate(), syncedAt);
            Map<String, CorumLifeWriter.Outcome> contracts =
                lifeWriter.write(job.memberId(), portfolio.life(), syncedAt);
            // Outcome counts only: the map is keyed by contract number, which stays out of the logs.
            Map<CorumLifeWriter.Outcome, Long> lifeOutcomes = contracts.values().stream()
                .collect(Collectors.groupingBy(o -> o,
                    () -> new EnumMap<>(CorumLifeWriter.Outcome.class), Collectors.counting()));
            // The panel reads this count, as it reads each SCPI fund's AMBIGUOUS outcome:
            // a contract written nowhere must not show as a plain success.
            int ambiguous = lifeOutcomes.getOrDefault(CorumLifeWriter.Outcome.AMBIGUOUS, 0L).intValue();
            sessionRepository.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
                .ifPresent(session -> session.recordLifeAmbiguous(ambiguous));
            log.info("CORUM outcomes (member={}; funds={}; lifeContracts={})", job.memberId(), funds, lifeOutcomes);
        }

        /** A rejected password must not be replayed by every scheduled sync: the site may lock the access. */
        @Override
        public void onFailure(SidecarSession session, SidecarErrorCode code) {
            if (code == SidecarErrorCode.INVALID_CREDENTIALS) {
                session.setEncryptedCredentials(null);
            }
        }
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
            throw error(SidecarErrorCode.INVALID_DATA, "CORUM did not return a session", null);
        }
        SidecarSyncJob job = requireTransactionResult(txTemplate.execute(status -> {
            sync.lockMember(memberId);
            if (authGeneration(memberId) != generation) {
                throw error(SidecarErrorCode.SESSION_EXPIRED, "The CORUM connection was removed during the login", null);
            }
            return sync.insertQueued(memberId, plainState, false, session -> {
                if (remember) {
                    session.setEncryptedCredentials(encryption.encrypt(serializeCredentials(login, password)));
                }
            });
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

    /**
     * Deletes the session and the credentials stored with it. A login still
     * waiting on CORUM is cancelled too: it stores nothing once it lands.
     *
     * @return whether there was a session to delete
     */
    @Transactional
    public boolean clearSession(Long memberId) {
        sync.lockMember(memberId);
        authGenerations.merge(memberId, 1L, Long::sum);
        return sync.clear(memberId);
    }

    /**
     * Takes the member lock the sync's commit takes before it writes the accounts, so a
     * deletion of a CORUM account locks in the same order as the sync and the two cannot
     * deadlock. Joins the caller's transaction; the lock is held until it ends.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockMemberForAccountDeletion(Long memberId) {
        sync.lockMember(memberId);
    }

    /**
     * The daily sync's entry, shaped like {@link CmesSyncService#resyncReporting}: an
     * active session is queued (and logs in again by itself if it has expired and
     * credentials are stored); an inactive one is reported, never retried.
     */
    public SourceSyncResult resyncReporting(Long memberId) {
        return sync.resyncReporting(memberId, SOURCE, log, this::getStatus, this::queueSync);
    }

    private long authGeneration(Long memberId) {
        return authGenerations.getOrDefault(memberId, 0L);
    }

    /**
     * The stored session has expired. With stored credentials the sync logs in
     * again through the sidecar, replaces the session and reads with the new one;
     * without them the run ends as SESSION_EXPIRED and the member reconnects. A
     * rejected password surfaces as INVALID_CREDENTIALS, which {@link Strategy#onFailure}
     * turns into cleared credentials and an inactive session. Credentials that no
     * longer decrypt or parse are cleared too, since no run could ever use them.
     *
     * <p>A member's re-logins draw on their own bucket; once it is spent the run
     * ends as UPSTREAM_UNAVAILABLE without calling CORUM. That code keeps the
     * session active and the credentials stored, so a later run logs in again
     * once the budget has refilled: being throttled is no reason to make the
     * member type the password again.
     */
    private CorumAdapter.Portfolio reauthenticateAndFetch(SidecarSyncJob job) {
        SidecarSession session = requireTransactionResult(txTemplate.execute(
            status -> sessionRepository.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
        )).orElse(null);
        if (session == null || !session.isActive()
            || session.getSyncStatus() != SidecarSyncStatus.RUNNING
            || session.getEncryptedCredentials() == null) {
            throw error(SidecarErrorCode.SESSION_EXPIRED, "CORUM session expired", null);
        }
        Credentials credentials;
        try {
            credentials = parseCredentials(encryption.decrypt(session.getEncryptedCredentials()));
        } catch (RuntimeException ex) {
            // Only the type: neither the stored value nor what it decrypted to belongs in a log.
            log.warn("CORUM stored credentials are unreadable and were cleared (member={}; type={})",
                job.memberId(), ex.getClass().getSimpleName());
            clearStoredCredentials(job);
            throw error(SidecarErrorCode.SESSION_EXPIRED, "CORUM session expired", null);
        }
        if (!reloginBuckets.computeIfAbsent(job.memberId(), key -> RateLimitConfig.createCorumAuthBucket())
                .tryConsume(1)) {
            log.warn("CORUM re-login throttled (member={})", job.memberId());
            throw error(SidecarErrorCode.UPSTREAM_UNAVAILABLE, "Too many CORUM re-logins. Please try again later.", null);
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
            throw error(SidecarErrorCode.INTERNAL_ERROR, "CORUM re-login failed", null);
        }
        if (newState == null || newState.isBlank()) {
            throw error(SidecarErrorCode.INVALID_DATA, "CORUM returned no session", null);
        }
        Boolean updated = txTemplate.execute(status -> {
            sync.lockMember(job.memberId());
            return sessionRepository.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
                .filter(current -> current.isActive() && current.getSyncStatus() == SidecarSyncStatus.RUNNING)
                .map(current -> {
                    current.updateSessionState(encryption.encrypt(newState), Instant.now());
                    sessionRepository.save(current);
                    return true;
                })
                .orElse(false);
        });
        if (!Boolean.TRUE.equals(updated)) {
            throw error(SidecarErrorCode.SESSION_EXPIRED, "CORUM session changed", null);
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
    private void validatePortfolio(CorumAdapter.Portfolio portfolio) {
        if (portfolio == null) {
            throw error(SidecarErrorCode.PORTFOLIO_INCOMPLETE, "CORUM returned no portfolio", null);
        }
        lifeWriter.validate(portfolio.life());
        if (portfolio.realEstate() == null) {
            if (portfolio.life().isEmpty()) {
                throw error(
                    SidecarErrorCode.PORTFOLIO_INCOMPLETE,
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
    private void validateRealEstate(CorumAdapter.Snapshot snapshot) {
        if (!snapshot.snapshotComplete()) {
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
        Long memberId, CorumAdapter.Snapshot snapshot, Instant syncedAt
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

    /** Clears the job's stored credentials, under the member lock, if the session is still in flight. */
    private void clearStoredCredentials(SidecarSyncJob job) {
        txTemplate.executeWithoutResult(status -> {
            sync.lockMember(job.memberId());
            sessionRepository.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
                .filter(SidecarSession::isSyncInFlight)
                .ifPresent(current -> {
                    current.setEncryptedCredentials(null);
                    sessionRepository.save(current);
                });
        });
    }

    private SessionStatusResponse toStatus(SidecarSession session) {
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

    private SidecarErrorCode codeOf(SyncException exception) {
        return sync.codeOf(exception);
    }

    private SyncException error(SidecarErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    public record SessionStatusResponse(
        boolean isActive,
        SidecarSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        SidecarErrorCode lastSyncError,
        /** CORUM Life contracts the last successful sync left unwritten (AMBIGUOUS). */
        int lifeAmbiguousCount,
        /** Whether credentials are stored for the unattended re-login. Never the credentials themselves. */
        boolean credentialsRemembered
    ) implements SidecarSessionView {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, SidecarSyncStatus.IDLE, null, null, null, 0, false);
        }
    }

    private record Credentials(String login, String password) {
        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }
}
