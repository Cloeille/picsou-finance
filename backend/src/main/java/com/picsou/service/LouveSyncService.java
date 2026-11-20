package com.picsou.service;

import com.picsou.adapter.LouveAdapter;
import com.picsou.adapter.LouveMcpClient;
import com.picsou.adapter.LouveOAuthClient;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.FamilyMember;
import com.picsou.model.LouveConnection;
import com.picsou.model.ScpiFundOutcome;
import com.picsou.model.ScpiProvider;
import com.picsou.port.SidecarErrorCode;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.LouveConnectionRepository;
import com.picsou.service.sync.SourceSyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Syncs the SCPI side of a Louve Invest portfolio, read from Louve's official MCP server.
 *
 * <p>Laid out like the other container-less API connectors ({@link WiseSyncService},
 * {@link SimplefinSyncService}, {@link IbkrSyncService}): one {@code louve_connection} row per
 * member holding the encrypted secret, a status and the last sync time; {@code connect} (here an
 * OAuth start and callback), {@code sync}, {@code resyncReporting} for the daily run, and
 * {@code deleteConnection}. The sync runs in the caller's thread and answers when it is done.
 *
 * <p>The connection is an OAuth 2.1 authorization-code flow with PKCE. {@link #startConnection}
 * stores a pending attempt (state nonce, encrypted verifier, redirect URI, client id) on the row,
 * like {@code requisition.oauth_state} for Enable Banking; an active connection keeps its tokens
 * meanwhile. {@link #completeConnection} checks the issuer and the state, spends the attempt
 * before exchanging the code, stores the token set and runs the first sync.
 *
 * <p>Unlike Wise, no transaction spans a network call except the refresh, which holds the
 * connection row lock on purpose: Louve rotates refresh tokens, so the new set is committed
 * before it is used and two runs (the 08:00 sync and a click) never refresh the same token.
 */
@Service
public class LouveSyncService {

    private static final Logger log = LoggerFactory.getLogger(LouveSyncService.class);
    /** Source name in {@link MemberSyncService}. */
    public static final String SOURCE = "louve";
    static final long ATTEMPT_TTL_MINUTES = 10;
    private static final Duration ATTEMPT_TTL = Duration.ofMinutes(ATTEMPT_TTL_MINUTES);
    /** The callback path of the SPA; the redirect URI is the instance origin plus this. */
    public static final String CALLBACK_PATH = "/sync/louve/callback";
    /** Codes after which only a new consent helps; the connection turns {@code REAUTH_REQUIRED}. */
    private static final Set<String> REAUTH_CODES = Set.of(
        SidecarErrorCode.SESSION_EXPIRED.name(), SidecarErrorCode.INVALID_SESSION_STATE.name());

    private final LouveAdapter adapter;
    private final LouveOAuthClient oauth;
    private final LouveConnectionRepository connections;
    private final FamilyMemberRepository members;
    private final CryptoEncryption encryption;
    private final TransactionTemplate tx;
    private final ScpiFundReconciler fundReconciler;
    private final LouveClientRegistry clients;
    private final Clock clock;

    public LouveSyncService(
        LouveAdapter adapter,
        LouveOAuthClient oauth,
        LouveConnectionRepository connections,
        FamilyMemberRepository members,
        CryptoEncryption encryption,
        TransactionTemplate tx,
        ScpiFundReconciler fundReconciler,
        LouveClientRegistry clients,
        Clock clock
    ) {
        this.adapter = adapter;
        this.oauth = oauth;
        this.connections = connections;
        this.members = members;
        this.encryption = encryption;
        this.tx = tx;
        this.fundReconciler = fundReconciler;
        this.clients = clients;
        this.clock = clock;
    }

    /** What the panel needs to send the browser to Louve. */
    public record StartResponse(String authorizeUrl) {}

    /** The panel's view: never a token. {@code connected} is true once a token set is stored. */
    public record ConnectionStatusResponse(boolean connected, String status, Instant lastSyncedAt) {}

    // -- connection ------------------------------------------------------------------------

    /**
     * Records a pending attempt for {@code redirectUri} (validated by the controller) and returns
     * Louve's consent URL. A previous pending attempt is replaced; stored tokens are kept until a
     * new consent replaces them.
     */
    public StartResponse startConnection(Long memberId, String redirectUri) {
        String clientId = clients.clientIdFor(redirectUri);
        String verifier = LouveOAuthClient.newVerifier();
        String state = LouveOAuthClient.newState();
        inTransaction(() -> {
            FamilyMember member = lockMember(memberId);
            LouveConnection connection = connections.findByMemberIdForUpdate(memberId)
                .orElseGet(() -> LouveConnection.builder().member(member).build());
            connection.setOauthState(state);
            connection.setOauthVerifier(encryption.encrypt(verifier));
            connection.setOauthRedirectUri(redirectUri);
            connection.setOauthClientId(clientId);
            connection.setOauthStartedAt(clock.instant());
            connections.save(connection);
            return null;
        });
        return new StartResponse(oauth.authorizeUri(clientId, redirectUri, state,
            LouveOAuthClient.challenge(verifier)).toString());
    }

    /**
     * Finishes the connection from Louve's callback, then runs the first sync. The {@code iss}
     * must name Louve (RFC 9207), the {@code state} must be the member's live attempt (constant
     * time, {@value #ATTEMPT_TTL_MINUTES} minutes), and the attempt is spent before the exchange,
     * so a replayed callback finds nothing. The grant this one replaces is revoked.
     */
    public ConnectionStatusResponse completeConnection(Long memberId, String code, String state, String issuer) {
        if (!LouveOAuthClient.ISSUER.equals(issuer)) {
            throw error(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED,
                "This answer did not come from Louve Invest. Start the connection again.");
        }
        Attempt attempt = inTransaction(() -> {
            LouveConnection connection = connections.findByMemberIdForUpdate(memberId)
                .orElseThrow(LouveSyncService::expiredAttempt);
            boolean live = connection.getOauthState() != null && connection.getOauthStartedAt() != null
                && connection.getOauthStartedAt().plus(ATTEMPT_TTL).isAfter(clock.instant());
            if (!live || !constantTimeEquals(connection.getOauthState(), state)) {
                throw expiredAttempt();
            }
            String verifier = decryptOrNull(connection.getOauthVerifier());
            Attempt spent = new Attempt(connection.getId(), connection.getGrantGeneration(),
                connection.getOauthClientId(), connection.getOauthRedirectUri(), verifier);
            connection.clearAttempt();
            connections.save(connection);
            if (verifier == null) {
                throw expiredAttempt();
            }
            return spent;
        });

        String plainState;
        try {
            plainState = adapter.exchangeCode(attempt.clientId(), attempt.redirectUri(), code, attempt.verifier());
        } catch (LouveOAuthClient.InvalidClientException ex) {
            forgetQuietly(ex);
            throw ex;
        }
        Optional<String> replaced;
        try {
            replaced = inTransaction(() -> {
                // Still the row, and the generation, this attempt was spent on: a disconnect, or a
                // newer consent stored while this code was being exchanged, wins over it.
                LouveConnection connection = connections.findByMemberIdForUpdate(memberId)
                    .filter(row -> row.getId().equals(attempt.connectionId())
                        && row.getGrantGeneration() == attempt.generation())
                    .orElseThrow(LouveSyncService::expiredAttempt);
                Optional<String> previous = tokenStateOf(connection);
                connection.setTokenState(encryption.encrypt(plainState));
                connection.setGrantGeneration(connection.getGrantGeneration() + 1);
                connection.setStatus(LouveConnection.STATUS_CONNECTED);
                connections.save(connection);
                return previous;
            });
        } catch (RuntimeException ex) {
            // Only the grant this callback just obtained: the connection that won keeps its own.
            adapter.revoke(plainState);
            throw ex;
        }
        replaced.ifPresent(this::revokeAfterCommit);
        try {
            sync(memberId);
        } catch (SyncException ex) {
            // Connected all the same: the status says the first sync failed, and the panel shows it.
            log.warn("First Louve Invest sync failed (member={}; code={})", memberId, ex.getCode());
        }
        return getStatus(memberId);
    }

    // -- sync ------------------------------------------------------------------------------

    /**
     * Reads the portfolio and writes it onto the member's SCPI accounts. A refused grant turns the
     * connection {@code REAUTH_REQUIRED}; any other failure {@code ERROR}. Either way the tokens
     * stay stored, so a disconnect can still revoke them.
     */
    public ConnectionStatusResponse sync(Long memberId) {
        LouveConnection connection = connections.findByMemberId(memberId)
            .filter(row -> row.getTokenState() != null)
            .orElseThrow(() -> error(SidecarErrorCode.SESSION_EXPIRED,
                "No Louve Invest connection. Connect first."));
        Run run = new Run(memberId, connection.getId(), connection.getGrantGeneration());
        try {
            String used = freshState(run, null);
            LouveAdapter.Snapshot snapshot;
            try {
                snapshot = adapter.read(used);
            } catch (LouveMcpClient.Unauthorized ex) {
                log.info("Louve Invest refused the access token before its expiry, refreshing once");
                snapshot = adapter.read(freshState(run, used));
            }
            commit(run, snapshot);
        } catch (Transient ex) {
            // Superseded or busy: nothing was written, and the connection's status is not this run's.
            log.info("Louve Invest sync not applied (member={}; reason={})", memberId, ex.reason());
            throw ex;
        } catch (SyncException ex) {
            markStatus(run, REAUTH_CODES.contains(ex.getCode())
                ? LouveConnection.STATUS_REAUTH_REQUIRED : LouveConnection.STATUS_ERROR);
            log.warn("Louve Invest sync failed (member={}; code={})", memberId, ex.getCode());
            throw ex;
        } catch (RuntimeException ex) {
            markStatus(run, LouveConnection.STATUS_ERROR);
            // By type only: an unexpected exception may carry a payload fragment.
            log.error("Louve Invest sync failed unexpectedly (member={}; type={})",
                memberId, ex.getClass().getSimpleName());
            throw error(SidecarErrorCode.INTERNAL_ERROR, "Louve Invest sync failed. Try again in a moment.");
        }
        return getStatus(memberId);
    }

    /**
     * The state to read with, refreshed and committed first when needed, under the row lock.
     *
     * @param refused the state Louve just refused, or null for the run's first read. When another
     *                run rotated the tokens in between (same grant), its state is returned as it is.
     */
    String freshState(Run run, String refused) {
        try {
            return inTransaction(() -> {
                LouveConnection connection = current(run);
                String plain = decryptOrNull(connection.getTokenState());
                if (plain == null) {
                    throw error(SidecarErrorCode.INVALID_SESSION_STATE,
                        "The stored Louve Invest access is unusable. Please reconnect.");
                }
                boolean rotatedMeanwhile = refused != null && !plain.equals(refused);
                if (rotatedMeanwhile || (refused == null && !adapter.needsRefresh(plain))) {
                    return plain;
                }
                String next = adapter.refresh(plain);
                connection.setTokenState(encryption.encrypt(next));
                connections.save(connection);
                return next;
            });
        } catch (LouveOAuthClient.InvalidClientException ex) {
            // Outside the rolled-back transaction, so the registration really goes.
            forgetQuietly(ex);
            throw ex;
        }
    }

    /**
     * Writes the snapshot in one transaction, member row locked like the CORUM and Sofidy
     * commits, so two writers cannot create the same account. A connection removed during the
     * read, or replaced by a new consent (another row or generation), writes nothing.
     */
    private void commit(Run run, LouveAdapter.Snapshot snapshot) {
        LouvePortfolioMapper.Mapped mapped = LouvePortfolioMapper.map(snapshot);
        Long memberId = run.memberId();
        inTransaction(() -> {
            lockMember(memberId);
            LouveConnection connection = current(run);
            // One instant for the funds and the connection: the panel matches the run by it.
            Instant syncedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Map<String, ScpiFundOutcome> outcomes = fundReconciler.reconcile(
                memberId, ScpiProvider.LOUVE, mapped.funds(), mapped.unsupported(), syncedAt);
            connection.setStatus(LouveConnection.STATUS_CONNECTED);
            connection.setLastSyncedAt(syncedAt);
            connections.save(connection);
            log.info("Louve Invest fund outcomes (member={}; funds={})", memberId, outcomes);
            return null;
        });
    }

    /**
     * The daily 08:00 entry: no connection is {@code SKIPPED_NOT_CONNECTED}; a refused grant is
     * {@code NEEDS_REAUTH} without calling Louve again; otherwise the sync runs and its failure
     * is classified like the other API connectors'.
     */
    public SourceSyncResult resyncReporting(Long memberId) {
        try {
            Optional<LouveConnection> connection = connections.findByMemberId(memberId)
                .filter(row -> row.getTokenState() != null);
            if (connection.isEmpty()) {
                return new SourceSyncResult(SOURCE, SourceSyncResult.Status.SKIPPED_NOT_CONNECTED, "No connection");
            }
            if (LouveConnection.STATUS_REAUTH_REQUIRED.equals(connection.get().getStatus())) {
                return new SourceSyncResult(SOURCE, SourceSyncResult.Status.NEEDS_REAUTH,
                    SidecarErrorCode.SESSION_EXPIRED.name());
            }
            sync(memberId);
            return new SourceSyncResult(SOURCE, SourceSyncResult.Status.SYNCED, "");
        } catch (SyncException ex) {
            return SourceSyncResult.fromSyncException(SOURCE, ex);
        } catch (RuntimeException ex) {
            log.error("Louve Invest scheduled sync failed unexpectedly (member={}; type={})",
                memberId, ex.getClass().getSimpleName());
            return new SourceSyncResult(SOURCE, SourceSyncResult.Status.FAILED, "Unexpected sync error");
        }
    }

    public ConnectionStatusResponse getStatus(Long memberId) {
        return connections.findByMemberId(memberId)
            .map(row -> new ConnectionStatusResponse(row.getTokenState() != null, row.getStatus(), row.getLastSyncedAt()))
            .orElseGet(() -> new ConnectionStatusResponse(false, null, null));
    }

    /**
     * Deletes the connection, then revokes its grant on Louve (best effort, outside the
     * transaction), whatever the status: a connection Louve already refused may still hold a
     * live refresh token. A row with only a pending attempt holds no token.
     */
    public boolean deleteConnection(Long memberId) {
        Optional<Optional<String>> removed = inTransaction(() -> {
            lockMember(memberId);
            return Optional.ofNullable(connections.findByMemberIdForUpdate(memberId)
                .map(connection -> {
                    Optional<String> tokens = tokenStateOf(connection);
                    connections.delete(connection);
                    return tokens;
                })
                .orElse(null));
        });
        removed.flatMap(tokens -> tokens).ifPresent(this::revokeAfterCommit);
        log.info("Louve Invest connection cleared for member {}", memberId);
        return removed.isPresent();
    }

    /**
     * Takes the member lock before an account deletion writes, the order {@link #commit} uses, so a
     * deletion and a running sync cannot deadlock. Joins the deletion's transaction; the lock is
     * held until it ends.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockMemberForAccountDeletion(Long memberId) {
        lockMember(memberId);
    }

    // -- helpers ---------------------------------------------------------------------------

    /** The run's connection, locked: the same row, the same grant, still holding tokens. */
    private LouveConnection current(Run run) {
        return connections.findByMemberIdForUpdate(run.memberId())
            .filter(row -> run.matches(row) && row.getTokenState() != null)
            .orElseThrow(() -> new Transient("superseded",
                "The Louve Invest connection changed during the sync. Nothing was written."));
    }

    /**
     * Revokes a grant once the row that held it is gone for good: after the surrounding
     * transaction commits (an account deletion runs this one inside its own), at once otherwise.
     * A rolled-back deletion keeps both the row and a live grant. The token set was decrypted
     * before the commit, so nothing is read from the database afterwards.
     */
    private void revokeAfterCommit(String plainState) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    adapter.revoke(plainState);
                }
            });
        } else {
            adapter.revoke(plainState);
        }
    }

    /** Records a failure on the run's own connection only, never on one that replaced it. */
    private void markStatus(Run run, String status) {
        try {
            inTransaction(() -> {
                connections.findByMemberIdForUpdate(run.memberId())
                    .filter(row -> run.matches(row))
                    .ifPresent(connection -> {
                        connection.setStatus(status);
                        connections.save(connection);
                    });
                return null;
            });
        } catch (RuntimeException ex) {
            log.error("Could not record the Louve Invest sync status (type={})", ex.getClass().getSimpleName());
        }
    }

    /**
     * Drops the refused registration without ever replacing the refusal: whatever the forget step
     * does (a database error, a lock), the caller still sees invalid_client.
     */
    private void forgetQuietly(LouveOAuthClient.InvalidClientException refusal) {
        try {
            clients.forget(refusal.redirectUri(), refusal.clientId());
        } catch (RuntimeException ex) {
            log.warn("Could not drop the refused Louve Invest client registration (type={})",
                ex.getClass().getSimpleName());
        }
    }

    /** The token set a row holds, whatever its status; empty when it holds none or cannot be read. */
    private Optional<String> tokenStateOf(LouveConnection connection) {
        return connection.getTokenState() == null
            ? Optional.empty()
            : Optional.ofNullable(decryptOrNull(connection.getTokenState()));
    }

    private String decryptOrNull(String encrypted) {
        if (encrypted == null) {
            return null;
        }
        try {
            return encryption.decrypt(encrypted);
        } catch (RuntimeException ex) {
            log.warn("Stored Louve Invest secret is unreadable (type={})", ex.getClass().getSimpleName());
            return null;
        }
    }

    private FamilyMember lockMember(Long memberId) {
        return members.findByIdForUpdate(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
    }

    /**
     * Runs {@code work} in a transaction. A row lock not obtained in time (another run holds it)
     * is a temporary contention, not a failure of the connection: nothing was written.
     */
    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        try {
            return tx.execute(status -> work.get());
        } catch (PessimisticLockingFailureException | jakarta.persistence.PessimisticLockException
                 | jakarta.persistence.LockTimeoutException ex) {
            throw new Transient("busy", "Another Louve Invest operation is running. Try again in a moment.");
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null || expected == null || expected.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static SyncException expiredAttempt() {
        return error(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED,
            "This Louve Invest connection attempt is no longer valid. Start again.");
    }

    private static SyncException error(SidecarErrorCode code, String message) {
        return new SyncException(message, null, code.name());
    }

    private record Attempt(Long connectionId, long generation, String clientId, String redirectUri, String verifier) {}

    /** One sync run: the row and the grant it started under. A rotation keeps the grant. */
    record Run(Long memberId, Long connectionId, long generation) {
        boolean matches(LouveConnection row) {
            return row.getId().equals(connectionId) && row.getGrantGeneration() == generation;
        }
    }

    /**
     * A run that wrote nothing for a reason that is not the connection's: it was superseded by a
     * new consent or a disconnect, or the row lock was busy. The connection's status is left as it
     * is, and the daily sync reports it as a retryable failure.
     */
    public static final class Transient extends SyncException {
        private final String reason;

        Transient(String reason, String message) {
            super(message, null, SidecarErrorCode.UPSTREAM_UNAVAILABLE.name());
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
