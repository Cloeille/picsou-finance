package com.picsou.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.CicSession;
import com.picsou.model.CicSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.port.CicErrorCode;
import com.picsou.port.CicPort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CicSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.service.sync.SourceSyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static com.picsou.service.SyncValues.clean;
import static com.picsou.service.SyncValues.errorCode;
import static com.picsou.service.SyncValues.limit;
import static com.picsou.service.SyncValues.requireTransactionResult;

/**
 * Imports CIC current and savings accounts, balances only -- the livrets and
 * the PEL that PSD2 does not expose.
 *
 * <p>Shaped like {@link CmesSyncService}, the same platform's employee-savings
 * connector, and like {@link BoursoSyncService} for the session: the credentials are stored
 * encrypted, and a sync that finds its session expired logs in again with them,
 * handing the sidecar the previous session so it replays the remembered-device
 * cookie. Only when the site asks for a new mobile confirmation does the sync
 * stop and wait for the saver. A partial read is discarded rather than merged.
 */
@Service
public class CicSyncService {
    private static final Logger log = LoggerFactory.getLogger(CicSyncService.class);
    static final String PROVIDER = "CIC";
    private static final String SOURCE = "cic";
    private static final String EXTERNAL_ID_PREFIX = "cic_";
    /** What the sidecar may return; anything else is refused, not stored. */
    private static final Set<AccountType> SUPPORTED_TYPES = EnumSet.of(
        AccountType.CHECKING, AccountType.SAVINGS, AccountType.LIVRET_A, AccountType.LDDS,
        AccountType.LEP, AccountType.LIVRET_JEUNE, AccountType.PEL, AccountType.CEL
    );
    private static final long PENDING_CREDENTIALS_SECONDS = 600;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CicPort port;
    private final CicSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final FamilyMemberRepository memberRepository;
    private final AccountService accountService;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final Executor syncExecutor;
    private final Clock clock;
    private final Map<String, PendingCredentials> pendingCredentials = new ConcurrentHashMap<>();
    // A disconnect revokes logins already waiting on the bank, including those without a process ID yet.
    private final Map<Long, Long> authGenerations = new ConcurrentHashMap<>();
    private final Object sessionWriteLock = new Object();

    public CicSyncService(
        CicPort port,
        CicSessionRepository sessionRepository,
        AccountRepository accountRepository,
        FamilyMemberRepository memberRepository,
        AccountService accountService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("cicSyncExecutor") Executor syncExecutor,
        Clock clock
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.memberRepository = memberRepository;
        this.accountService = accountService;
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.syncExecutor = syncExecutor;
        this.clock = clock;
    }

    public AuthInitResponse initiateAuth(String login, String password, Long memberId) {
        return initiateAuth(login, password, memberId, authGenerations.getOrDefault(memberId, 0L));
    }

    private AuthInitResponse initiateAuth(String login, String password, Long memberId, long generation) {
        requireGeneration(memberId, generation);
        // A reconnection from the UI replays the remembered-device cookie too, so
        // reconnecting after an error does not ask for a confirmation again.
        CicPort.InitiateResult result = port.initiateAuth(login, password, previousPlainState(memberId));
        if (!result.mfaRequired()) {
            if (result.sessionState() == null || result.sessionState().isBlank()) {
                throw error(CicErrorCode.INVALID_DATA, "CIC did not return a session", null);
            }
            storeSessionAndQueue(result.sessionState(), memberId, login, password, generation);
        } else if (result.processId() != null) {
            evictExpiredPendingCredentials();
            synchronized (sessionWriteLock) {
                requireGeneration(memberId, generation);
                pendingCredentials.put(result.processId(), new PendingCredentials(
                    login, password, clock.instant().plusSeconds(PENDING_CREDENTIALS_SECONDS), memberId, generation));
            }
        }
        return new AuthInitResponse(result.processId(), result.mfaRequired(), result.mfaType());
    }

    /**
     * A reconnection the saver starts themselves, from the credentials stored at
     * the last login. Unlike the scheduled re-login, someone is there to approve
     * the mobile confirmation, so it goes through the same second-factor steps
     * as a typed login. Without stored credentials the saver has to type them.
     */
    public AuthInitResponse reconnect(Long memberId) {
        long generation = authGenerations.getOrDefault(memberId, 0L);
        String encrypted = sessionRepository.findByMemberId(memberId)
            .map(CicSession::getEncryptedCredentials)
            .orElse(null);
        if (encrypted == null) {
            throw error(CicErrorCode.SESSION_EXPIRED, "No stored CIC credentials", null);
        }
        Credentials credentials;
        try {
            credentials = parseCredentials(encryption.decrypt(encrypted));
        } catch (RuntimeException ex) {
            throw error(CicErrorCode.SESSION_EXPIRED, "Stored CIC credentials are unreadable", ex);
        }
        try {
            return initiateAuth(credentials.login(), credentials.password(), memberId, generation);
        } catch (SyncException ex) {
            if (codeOf(ex) == CicErrorCode.INVALID_CREDENTIALS) {
                synchronized (sessionWriteLock) {
                    if (authGenerations.getOrDefault(memberId, 0L) == generation) {
                        txTemplate.executeWithoutResult(status -> sessionRepository.findByMemberIdForUpdate(memberId)
                            // A newer login's password must survive an older rejection.
                            .filter(session -> encrypted.equals(session.getEncryptedCredentials()))
                            .ifPresent(session -> {
                                session.rejectCredentials(clock.instant());
                                sessionRepository.save(session);
                            }));
                    }
                }
            }
            throw ex;
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void evictExpiredPendingCredentials() {
        Instant now = clock.instant();
        pendingCredentials.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    /** {@code code} is the SMS code, or null for a mobile confirmation. */
    public SessionStatusResponse completeAuth(String processId, String code, Long memberId) {
        PendingCredentials credentials;
        synchronized (sessionWriteLock) {
            credentials = pendingCredentials.get(processId);
            if (credentials == null || !credentials.memberId().equals(memberId)) {
                throw error(CicErrorCode.AUTH_ATTEMPT_EXPIRED, "CIC authentication attempt expired", null);
            }
            if (!credentials.expiresAt().isAfter(clock.instant())) {
                pendingCredentials.remove(processId, credentials);
                throw error(CicErrorCode.AUTH_ATTEMPT_EXPIRED, "CIC authentication attempt expired", null);
            }
            requireGeneration(memberId, credentials.generation());
        }
        // A wrong SMS code throws INVALID_OTP before the pending credentials are
        // dropped, so the saver's next try on the same attempt still stores them.
        String plainState = port.completeAuth(processId, code);
        if (plainState == null || plainState.isBlank()) {
            throw error(CicErrorCode.INVALID_DATA, "CIC did not return a session", null);
        }
        SyncJob job;
        synchronized (sessionWriteLock) {
            requireGeneration(memberId, credentials.generation());
            if (!pendingCredentials.remove(processId, credentials)
                || !credentials.expiresAt().isAfter(clock.instant())) {
                throw error(CicErrorCode.AUTH_ATTEMPT_EXPIRED, "CIC authentication attempt expired", null);
            }
            job = insertSession(plainState, memberId, credentials.login(), credentials.password());
        }
        submit(job);
        return getStatus(memberId);
    }

    public SessionStatusResponse queueSync(Long memberId) {
        QueueDecision decision = requireTransactionResult(txTemplate.execute(status -> {
            CicSession session = sessionRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> error(
                    CicErrorCode.SESSION_EXPIRED,
                    "No active CIC session. Please reconnect.",
                    null
                ));
            if (!session.isActive()) {
                throw error(
                    CicErrorCode.SESSION_EXPIRED,
                    "The CIC session expired. Please reconnect.",
                    null
                );
            }
            if (session.getSyncStatus() == CicSyncStatus.QUEUED
                || session.getSyncStatus() == CicSyncStatus.RUNNING) {
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

    /** The stored session, even an expired one: only its remembered-device cookie is reused. */
    private String previousPlainState(Long memberId) {
        try {
            return sessionRepository.findByMemberId(memberId)
                .map(session -> encryption.decrypt(session.getSessionState()))
                .orElse(null);
        } catch (RuntimeException ex) {
            log.info("Could not reuse the previous CIC session (member={})", memberId);
            return null;
        }
    }

    private void requireGeneration(Long memberId, long generation) {
        if (authGenerations.getOrDefault(memberId, 0L) != generation) {
            throw error(CicErrorCode.AUTH_ATTEMPT_EXPIRED, "The CIC connection was removed during the login", null);
        }
    }

    private SessionStatusResponse storeSessionAndQueue(String plainState, Long memberId, String login, String password,
                                                       long generation) {
        SyncJob job;
        synchronized (sessionWriteLock) {
            requireGeneration(memberId, generation);
            job = insertSession(plainState, memberId, login, password);
        }
        submit(job);
        return getStatus(memberId);
    }

    private SyncJob insertSession(String plainState, Long memberId, String login, String password) {
        return requireTransactionResult(txTemplate.execute(status -> {
            FamilyMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            sessionRepository.findByMemberIdForUpdate(memberId).ifPresent(sessionRepository::delete);
            sessionRepository.flush();

            CicSession newSession = CicSession.create(
                member,
                encryption.encrypt(plainState),
                Instant.now()
            );
            if (login != null && password != null) {
                newSession.setEncryptedCredentials(encryption.encrypt(serializeCredentials(login, password)));
            }
            newSession.markQueued();
            CicSession stored = sessionRepository.saveAndFlush(newSession);
            return new SyncJob(stored.getId(), memberId, plainState);
        }));
    }

    private void submit(SyncJob job) {
        try {
            syncExecutor.execute(() -> executeJob(job));
        } catch (RuntimeException ex) {
            markFailed(job, CicErrorCode.INTERNAL_ERROR);
            throw error(
                CicErrorCode.INTERNAL_ERROR,
                "Could not schedule the CIC synchronization",
                ex
            );
        }
    }

    private void executeJob(SyncJob job) {
        if (!markRunning(job)) {
            return;
        }
        try {
            List<CicPort.AccountData> fetched;
            try {
                fetched = port.fetchAccounts(job.plainState());
            } catch (SyncException ex) {
                if (codeOf(ex) != CicErrorCode.SESSION_EXPIRED) throw ex;
                fetched = reauthenticateAndFetch(job);
            }
            List<PreparedAccount> prepared = prepareAccounts(fetched);
            if (commitAccounts(job, prepared)) {
                log.info("CIC sync completed (member={}; accounts={})", job.memberId(), prepared.size());
            } else {
                log.info("Discarded stale CIC sync result (member={})", job.memberId());
            }
        } catch (SyncException ex) {
            CicErrorCode code = codeOf(ex);
            markFailed(job, code);
            log.warn("CIC sync failed (member={}; code={})", job.memberId(), code);
        } catch (Exception ex) {
            markFailed(job, CicErrorCode.INTERNAL_ERROR);
            log.error("CIC sync failed unexpectedly (member={})", job.memberId(), ex);
        }
    }

    /**
     * The site's sessions last minutes, so nearly every scheduled sync lands
     * here. The stored credentials log in again, carrying the previous session
     * for its remembered-device cookie; a login that still asks for a second
     * factor cannot be finished unattended and reads as an expired session.
     */
    private List<CicPort.AccountData> reauthenticateAndFetch(SyncJob job) {
        CicSession session = requireTransactionResult(txTemplate.execute(
            status -> sessionRepository.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
        )).orElse(null);
        if (session == null || !session.isActive()
            || session.getSyncStatus() != CicSyncStatus.RUNNING
            || session.getEncryptedCredentials() == null) {
            throw error(CicErrorCode.SESSION_EXPIRED, "CIC session expired", null);
        }
        Credentials credentials;
        try {
            credentials = parseCredentials(encryption.decrypt(session.getEncryptedCredentials()));
        } catch (RuntimeException ex) {
            throw error(CicErrorCode.SESSION_EXPIRED, "CIC session expired", ex);
        }
        CicPort.InitiateResult result = port.reauthenticate(credentials.login(), credentials.password(), job.plainState());
        if (result.mfaRequired()) {
            log.info("CIC asks for a new second factor (member={}; type={})", job.memberId(), result.mfaType());
            throw error(CicErrorCode.SESSION_EXPIRED, "CIC requires a new second factor", null);
        }
        if (result.sessionState() == null || result.sessionState().isBlank()) {
            throw error(CicErrorCode.INVALID_DATA, "CIC returned no session", null);
        }
        String newState = result.sessionState();
        Boolean updated = txTemplate.execute(status -> sessionRepository
            .findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
            .filter(current -> current.isActive() && current.getSyncStatus() == CicSyncStatus.RUNNING)
            .map(current -> {
                current.updateSessionState(encryption.encrypt(newState), Instant.now());
                sessionRepository.save(current);
                return true;
            })
            .orElse(false));
        if (!Boolean.TRUE.equals(updated)) {
            throw error(CicErrorCode.SESSION_EXPIRED, "CIC session changed", null);
        }
        log.info("CIC re-logged in with stored credentials (member={})", job.memberId());
        return port.fetchAccounts(newState);
    }

    private String serializeCredentials(String login, String password) {
        try {
            return JSON.writeValueAsString(Map.of("login", login, "password", password));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not encode credentials");
        }
    }

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

    private boolean markRunning(SyncJob job) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<CicSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("CIC sync session disappeared before execution (member={})", job.memberId());
                return false;
            }
            CicSession session = current.get();
            if (!session.isActive() || session.getSyncStatus() != CicSyncStatus.QUEUED) {
                log.warn(
                    "CIC sync cannot start from state {} (member={}; active={})",
                    session.getSyncStatus(),
                    job.memberId(),
                    session.isActive()
                );
                return false;
            }
            session.markRunning(Instant.now());
            sessionRepository.save(session);
            return true;
        }));
    }

    private List<PreparedAccount> prepareAccounts(List<CicPort.AccountData> fetched) {
        if (fetched == null || fetched.isEmpty()) {
            throw error(CicErrorCode.PORTFOLIO_INCOMPLETE, "CIC returned no account", null);
        }
        Set<String> externalIds = new HashSet<>();
        List<PreparedAccount> prepared = new ArrayList<>();
        for (CicPort.AccountData account : fetched) {
            if (account == null || !account.snapshotComplete()) {
                throw error(CicErrorCode.PORTFOLIO_INCOMPLETE, "CIC returned an incomplete account list", null);
            }
            String externalId = stableExternalId(account.externalId());
            if (!externalIds.add(externalId)) {
                throw error(CicErrorCode.INVALID_DATA, "CIC returned duplicate accounts", null);
            }
            if (account.type() == null || !SUPPORTED_TYPES.contains(account.type())) {
                throw error(CicErrorCode.INVALID_DATA, "CIC returned an unsupported account type", null);
            }
            if (account.balanceEur() == null) {
                throw error(CicErrorCode.INVALID_DATA, "CIC returned an account without balance", null);
            }
            prepared.add(new PreparedAccount(
                externalId, limit(account.name(), 100, "CIC"), account.type(), account.balanceEur()));
        }
        return List.copyOf(prepared);
    }

    private boolean commitAccounts(SyncJob job, List<PreparedAccount> prepared) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<CicSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("CIC sync session disappeared before commit (member={})", job.memberId());
                return false;
            }
            CicSession session = current.get();
            if (!session.isActive()) {
                log.warn("CIC sync session became inactive before commit (member={})", job.memberId());
                return false;
            }
            if (session.getSyncStatus() != CicSyncStatus.RUNNING) {
                log.warn(
                    "CIC sync cannot commit from state {} (member={})",
                    session.getSyncStatus(),
                    job.memberId()
                );
                return false;
            }

            FamilyMember member = memberRepository.findById(job.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            Instant syncedAt = Instant.now();
            List<Account> imported = accountRepository.findAllByMemberIdAndProvider(job.memberId(), PROVIDER);
            for (PreparedAccount data : prepared) {
                upsertAccount(data, member, job.memberId(), syncedAt);
            }
            closeUnlistedAccounts(prepared, imported, job.memberId(), syncedAt);

            session.markSuccessful(syncedAt);
            sessionRepository.save(session);
            return true;
        }));
    }

    private void upsertAccount(PreparedAccount data, FamilyMember member, Long memberId, Instant syncedAt) {
        Optional<Account> existing = accountRepository
            .findByExternalAccountIdAndMemberId(data.externalId(), memberId);
        if (existing.isEmpty()
            && accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), memberId)) {
            log.info("CIC skipped a soft-deleted account (member={})", memberId);
            return;
        }

        Account account = existing.orElseGet(() -> Account.builder()
            .member(member)
            .externalAccountId(data.externalId())
            .provider(PROVIDER)
            .currency("EUR")
            .isManual(false)
            .color(data.type() == AccountType.CHECKING ? "#ec4899" : "#f59e0b")
            .build());
        account.setName(data.name());
        account.setType(data.type());
        account.setProvider(PROVIDER);
        account.setCurrency("EUR");
        account.setManual(false);
        account.setCurrentBalance(data.balanceEur());
        // A livret or a current account has one number and no cash leg.
        account.setCashBalance(null);
        account.setLastSyncedAt(syncedAt);
        Account savedAccount = accountRepository.save(account);
        // No cost basis of its own: AccountService derives it as for every balance-only connector.
        accountService.upsertSnapshot(savedAccount, data.balanceEur(), LocalDate.now());
    }

    /**
     * An account a complete fetch no longer lists (a closed livret) would keep
     * its last balance and still count in the net worth. Only a complete read
     * reaches this point ({@link #prepareAccounts} refuses a partial one). The
     * account is closed at zero rather than soft deleted: a soft-deleted account
     * reads as removed by the user and would never be imported again, while an
     * account can come back.
     */
    private void closeUnlistedAccounts(List<PreparedAccount> prepared, List<Account> imported, Long memberId,
                                       Instant syncedAt) {
        Set<String> listed = new HashSet<>();
        prepared.forEach(account -> listed.add(account.externalId()));
        for (Account account : imported) {
            String externalId = account.getExternalAccountId();
            if (externalId == null || !externalId.startsWith(EXTERNAL_ID_PREFIX) || listed.contains(externalId)) {
                continue;
            }
            if (account.getCurrentBalance() != null && account.getCurrentBalance().signum() == 0) {
                continue;
            }
            log.info("CIC no longer lists an imported account, closing it at zero (member={}; account={})",
                memberId, account.getId());
            account.setCurrentBalance(BigDecimal.ZERO);
            account.setCashBalance(null);
            account.setLastSyncedAt(syncedAt);
            Account saved = accountRepository.save(account);
            accountService.upsertSnapshot(saved, BigDecimal.ZERO, LocalDate.now());
        }
    }

    private void markFailed(SyncJob job, CicErrorCode code) {
        try {
            txTemplate.executeWithoutResult(status -> {
                Optional<CicSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                    job.sessionId(),
                    job.memberId()
                );
                if (current.isEmpty()) {
                    log.info("CIC sync session disappeared before failure was recorded (member={})", job.memberId());
                    return;
                }
                CicSession session = current.get();
                if (!session.isSyncInFlight()) {
                    log.warn(
                        "CIC sync failure ignored from state {} (member={}; code={})",
                        session.getSyncStatus(),
                        job.memberId(),
                        code
                    );
                    return;
                }
                // A rejected password must not be replayed by every scheduled sync:
                // the site locks the access after a few failures.
                if (code == CicErrorCode.INVALID_CREDENTIALS) session.setEncryptedCredentials(null);
                session.markFailed(code, Instant.now());
                sessionRepository.save(session);
            });
        } catch (RuntimeException ex) {
            log.error(
                "Could not persist CIC sync failure (member={}; code={})",
                job.memberId(),
                code,
                ex
            );
        }
    }

    /**
     * Queued jobs live in a process-local executor and cannot survive a backend
     * restart. Turn persisted in-flight states into a retryable failure instead
     * of leaving the UI polling QUEUED/RUNNING forever.
     */
    @Transactional
    public void recoverInterruptedSyncs() {
        int recovered = sessionRepository.markInterruptedSyncsFailed(
            List.of(CicSyncStatus.QUEUED, CicSyncStatus.RUNNING),
            CicSyncStatus.FAILED,
            Instant.now(),
            CicErrorCode.INTERNAL_ERROR
        );
        if (recovered > 0) {
            log.warn("Recovered {} interrupted CIC sync job(s)", recovered);
        }
    }

    @Transactional(readOnly = true)
    public SessionStatusResponse getStatus(Long memberId) {
        return sessionRepository.findByMemberId(memberId)
            .map(this::toStatus)
            .orElseGet(SessionStatusResponse::inactive);
    }

    /**
     * Returns whether a stored session was there to delete. A pending login is
     * dropped too but does not count: it never became a connection.
     */
    public boolean clearSession(Long memberId) {
        synchronized (sessionWriteLock) {
            authGenerations.merge(memberId, 1L, Long::sum);
            pendingCredentials.entrySet().removeIf(entry -> entry.getValue().memberId().equals(memberId));
            return Boolean.TRUE.equals(txTemplate.execute(status -> {
                var session = sessionRepository.findByMemberIdForUpdate(memberId);
                session.ifPresent(sessionRepository::delete);
                return session.isPresent();
            }));
        }
    }

    public SourceSyncResult resyncReporting(Long memberId) {
        try {
            SessionStatusResponse status = getStatus(memberId);
            if (!status.isActive()) {
                if (status.lastSyncError() == CicErrorCode.SESSION_EXPIRED
                    || status.lastSyncError() == CicErrorCode.INVALID_CREDENTIALS) {
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
            log.error("Database error during scheduled CIC sync (member={})", memberId, ex);
            return new SourceSyncResult(SOURCE, SourceSyncResult.Status.FAILED, "Database error");
        } catch (SyncException ex) {
            log.warn("Could not queue scheduled CIC sync (member={}; code={})", memberId, ex.getCode(), ex);
            SourceSyncResult classified = SourceSyncResult.fromSyncException(SOURCE, ex);
            return new SourceSyncResult(SOURCE, classified.status(),
                classified.status() == SourceSyncResult.Status.NEEDS_REAUTH ? ex.getCode() : "Sync failed");
        } catch (Exception ex) {
            log.error("CIC scheduled sync failed unexpectedly for member {}", memberId, ex);
            return new SourceSyncResult(SOURCE, SourceSyncResult.Status.FAILED, "Unexpected sync error");
        }
    }

    public void resyncIfSessionActive(Long memberId) {
        resyncReporting(memberId); // ignore return
    }

    private SessionStatusResponse toStatus(CicSession session) {
        return new SessionStatusResponse(
            session.isActive(),
            session.getSyncStatus(),
            session.getLastSyncStartedAt(),
            session.getLastSyncCompletedAt(),
            session.getLastSyncError(),
            session.getEncryptedCredentials() != null
        );
    }

    private CicErrorCode codeOf(SyncException exception) {
        return errorCode(exception, CicErrorCode.class, CicErrorCode.UPSTREAM_UNAVAILABLE);
    }

    private SyncException error(CicErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    private String stableExternalId(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            throw error(CicErrorCode.INVALID_DATA, "CIC returned an invalid account identifier", null);
        }
        String externalId = cleaned.startsWith(EXTERNAL_ID_PREFIX) ? cleaned : EXTERNAL_ID_PREFIX + cleaned;
        if (externalId.length() > 100) {
            throw error(CicErrorCode.INVALID_DATA, "CIC returned an invalid account identifier", null);
        }
        return externalId;
    }

    public record AuthInitResponse(String processId, boolean mfaRequired, String mfaType) {}

    public record SessionStatusResponse(
        boolean isActive,
        CicSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        CicErrorCode lastSyncError,
        /** Whether {@link #reconnect} can log in again without the saver typing their credentials. */
        boolean canReconnect
    ) {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, CicSyncStatus.IDLE, null, null, null, false);
        }
    }

    private record Credentials(String login, String password) {}
    private record PendingCredentials(String login, String password, Instant expiresAt, Long memberId, long generation) {}
    private record QueueDecision(SyncJob job, SessionStatusResponse status) {}
    private record SyncJob(Long sessionId, Long memberId, String plainState) {}
    private record PreparedAccount(String externalId, String name, AccountType type, BigDecimal balanceEur) {}
}
