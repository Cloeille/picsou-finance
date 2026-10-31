package com.picsou.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.CmesSession;
import com.picsou.model.CmesSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.port.CmesErrorCode;
import com.picsou.port.CmesPort;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CmesSessionRepository;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static com.picsou.service.SyncValues.clean;
import static com.picsou.service.SyncValues.errorCode;
import static com.picsou.service.SyncValues.limit;
import static com.picsou.service.SyncValues.moneyClose;
import static com.picsou.service.SyncValues.requireTransactionResult;
import static com.picsou.service.SyncValues.sumComplete;
import static com.picsou.service.SyncValues.weightedAverage;

/**
 * Imports Crédit Mutuel Épargne Salariale plans (also CIC's), one Picsou
 * account per plan.
 *
 * <p>Shaped like {@link AmundiSyncService} for the plans and like
 * {@link BoursoSyncService} for the session: the credentials are stored
 * encrypted, and a sync that finds its session expired logs in again with them,
 * handing the sidecar the previous session so it replays the remembered-device
 * cookie. Only when the site asks for a new mobile confirmation does the sync
 * stop and wait for the saver. A partial read is discarded rather than merged.
 */
@Service
public class CmesSyncService {
    private static final Logger log = LoggerFactory.getLogger(CmesSyncService.class);
    static final String PROVIDER = "Crédit Mutuel Épargne Salariale";
    private static final String SOURCE = "cmes";
    private static final String EXTERNAL_ID_PREFIX = "cmes_";
    private static final String ACCOUNT_COLOR = "#8b5cf6";
    private static final int MAX_TICKER_LENGTH = 30;
    private static final long PENDING_CREDENTIALS_SECONDS = 600;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CmesPort port;
    private final CmesSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final FamilyMemberRepository memberRepository;
    private final AccountService accountService;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final Executor syncExecutor;
    private final Map<String, PendingCredentials> pendingCredentials = new ConcurrentHashMap<>();
    /**
     * Bumped by {@link #clearSession}: a login that was waiting on the bank when
     * the connection was removed carries an older generation and stores nothing.
     * Checked and bumped under {@link #sessionWriteLock}.
     */
    private final Map<Long, Long> authGenerations = new ConcurrentHashMap<>();
    private final Object sessionWriteLock = new Object();

    public CmesSyncService(
        CmesPort port,
        CmesSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        FamilyMemberRepository memberRepository,
        AccountService accountService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("cmesSyncExecutor") Executor syncExecutor
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.memberRepository = memberRepository;
        this.accountService = accountService;
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.syncExecutor = syncExecutor;
    }

    public AuthInitResponse initiateAuth(String login, String password, Long memberId) {
        long generation = authGeneration(memberId);
        // A reconnection from the UI replays the remembered-device cookie too, so
        // reconnecting after an error does not ask for a confirmation again.
        CmesPort.InitiateResult result = port.initiateAuth(login, password, previousPlainState(memberId));
        if (!result.mfaRequired()) {
            if (result.sessionState() == null || result.sessionState().isBlank()) {
                throw error(CmesErrorCode.INVALID_DATA, "CMES did not return a session", null);
            }
            storeSessionAndQueue(result.sessionState(), memberId, login, password, generation);
        } else if (result.processId() != null) {
            evictExpiredPendingCredentials();
            pendingCredentials.put(result.processId(), new PendingCredentials(
                login, password, Instant.now().plusSeconds(PENDING_CREDENTIALS_SECONDS), memberId, generation));
        }
        return new AuthInitResponse(result.processId(), result.mfaRequired(), result.mfaType());
    }

    /**
     * Drops the passwords of abandoned second factors. They are held in memory
     * only for the length of an attempt, so they must not wait for the next
     * login to be cleared.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void evictExpiredPendingCredentials() {
        evictPendingCredentialsExpiredAt(Instant.now());
    }

    void evictPendingCredentialsExpiredAt(Instant now) {
        pendingCredentials.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    /** {@code code} is the SMS code, or null for a mobile confirmation. */
    public SessionStatusResponse completeAuth(String processId, String code, Long memberId) {
        // Without live credentials (expired, or lost to a backend restart) the
        // attempt cannot store a session able to log in again unattended, and
        // completing it would replace the stored session and its credentials.
        PendingCredentials credentials = pendingCredentials.get(processId);
        if (credentials == null
            || !credentials.memberId().equals(memberId)
            || !credentials.expiresAt().isAfter(Instant.now())) {
            throw error(CmesErrorCode.AUTH_ATTEMPT_EXPIRED, "CMES authentication attempt expired", null);
        }
        // A wrong SMS code throws INVALID_OTP before the pending credentials are
        // dropped, so the saver's next try on the same attempt still stores them.
        String plainState = port.completeAuth(processId, code);
        pendingCredentials.remove(processId);
        if (plainState == null || plainState.isBlank()) {
            throw error(CmesErrorCode.INVALID_DATA, "CMES did not return a session", null);
        }
        return storeSessionAndQueue(
            plainState, memberId, credentials.login(), credentials.password(), credentials.generation());
    }

    public SessionStatusResponse queueSync(Long memberId) {
        QueueDecision decision = requireTransactionResult(txTemplate.execute(status -> {
            CmesSession session = sessionRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> error(
                    CmesErrorCode.SESSION_EXPIRED,
                    "No active CMES session. Please reconnect.",
                    null
                ));
            if (!session.isActive()) {
                throw error(
                    CmesErrorCode.SESSION_EXPIRED,
                    "The CMES session expired. Please reconnect.",
                    null
                );
            }
            if (session.getSyncStatus() == CmesSyncStatus.QUEUED
                || session.getSyncStatus() == CmesSyncStatus.RUNNING) {
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
            log.info("Could not reuse the previous CMES session (member={})", memberId);
            return null;
        }
    }

    private long authGeneration(Long memberId) {
        return authGenerations.getOrDefault(memberId, 0L);
    }

    private SessionStatusResponse storeSessionAndQueue(String plainState, Long memberId, String login, String password,
                                                       long generation) {
        SyncJob job;
        synchronized (sessionWriteLock) {
            if (authGeneration(memberId) != generation) {
                throw error(CmesErrorCode.AUTH_ATTEMPT_EXPIRED, "The CMES connection was removed during the login", null);
            }
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

            CmesSession newSession = CmesSession.create(
                member,
                encryption.encrypt(plainState),
                Instant.now()
            );
            if (login != null && password != null) {
                newSession.setEncryptedCredentials(encryption.encrypt(serializeCredentials(login, password)));
            }
            newSession.markQueued();
            CmesSession stored = sessionRepository.saveAndFlush(newSession);
            return new SyncJob(stored.getId(), memberId, plainState);
        }));
    }

    private void submit(SyncJob job) {
        try {
            syncExecutor.execute(() -> executeJob(job));
        } catch (RuntimeException ex) {
            markFailed(job, CmesErrorCode.INTERNAL_ERROR);
            throw error(
                CmesErrorCode.INTERNAL_ERROR,
                "Could not schedule the CMES synchronization",
                ex
            );
        }
    }

    private void executeJob(SyncJob job) {
        if (!markRunning(job)) {
            return;
        }
        try {
            List<CmesPort.PlanData> fetched;
            try {
                fetched = port.fetchPlans(job.plainState());
            } catch (SyncException ex) {
                if (codeOf(ex) != CmesErrorCode.SESSION_EXPIRED) throw ex;
                fetched = reauthenticateAndFetch(job);
            }
            List<PreparedPlan> prepared = preparePlans(fetched);
            if (commitPlans(job, prepared)) {
                log.info("CMES sync completed (member={}; plans={})", job.memberId(), prepared.size());
            } else {
                log.info("Discarded stale CMES sync result (member={})", job.memberId());
            }
        } catch (SyncException ex) {
            CmesErrorCode code = codeOf(ex);
            markFailed(job, code);
            log.warn("CMES sync failed (member={}; code={})", job.memberId(), code);
        } catch (Exception ex) {
            markFailed(job, CmesErrorCode.INTERNAL_ERROR);
            log.error("CMES sync failed unexpectedly (member={})", job.memberId(), ex);
        }
    }

    /**
     * The site's sessions last minutes, so nearly every scheduled sync lands
     * here. The stored credentials log in again, carrying the previous session
     * for its remembered-device cookie; a login that still asks for a second
     * factor cannot be finished unattended and reads as an expired session.
     */
    private List<CmesPort.PlanData> reauthenticateAndFetch(SyncJob job) {
        CmesSession session = requireTransactionResult(txTemplate.execute(
            status -> sessionRepository.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
        )).orElse(null);
        if (session == null || !session.isActive()
            || session.getSyncStatus() != CmesSyncStatus.RUNNING
            || session.getEncryptedCredentials() == null) {
            throw error(CmesErrorCode.SESSION_EXPIRED, "CMES session expired", null);
        }
        Credentials credentials;
        try {
            credentials = parseCredentials(encryption.decrypt(session.getEncryptedCredentials()));
        } catch (RuntimeException ex) {
            throw error(CmesErrorCode.SESSION_EXPIRED, "CMES session expired", ex);
        }
        CmesPort.InitiateResult result = port.reauthenticate(credentials.login(), credentials.password(), job.plainState());
        if (result.mfaRequired()) {
            log.info("CMES asks for a new second factor (member={}; type={})", job.memberId(), result.mfaType());
            throw error(CmesErrorCode.SESSION_EXPIRED, "CMES requires a new second factor", null);
        }
        if (result.sessionState() == null || result.sessionState().isBlank()) {
            throw error(CmesErrorCode.INVALID_DATA, "CMES returned no session", null);
        }
        String newState = result.sessionState();
        Boolean updated = txTemplate.execute(status -> sessionRepository
            .findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
            .filter(current -> current.isActive() && current.getSyncStatus() == CmesSyncStatus.RUNNING)
            .map(current -> {
                current.updateSessionState(encryption.encrypt(newState), Instant.now());
                sessionRepository.save(current);
                return true;
            })
            .orElse(false));
        if (!Boolean.TRUE.equals(updated)) {
            throw error(CmesErrorCode.SESSION_EXPIRED, "CMES session changed", null);
        }
        log.info("CMES re-logged in with stored credentials (member={})", job.memberId());
        return port.fetchPlans(newState);
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
            Optional<CmesSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("CMES sync session disappeared before execution (member={})", job.memberId());
                return false;
            }
            CmesSession session = current.get();
            if (!session.isActive() || session.getSyncStatus() != CmesSyncStatus.QUEUED) {
                log.warn(
                    "CMES sync cannot start from state {} (member={}; active={})",
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

    private List<PreparedPlan> preparePlans(List<CmesPort.PlanData> fetched) {
        if (fetched == null || fetched.isEmpty()) {
            throw error(CmesErrorCode.PORTFOLIO_INCOMPLETE, "CMES returned no complete plan", null);
        }

        Set<String> externalIds = new HashSet<>();
        List<PreparedPlan> prepared = new ArrayList<>();
        for (CmesPort.PlanData plan : fetched) {
            if (plan == null || !plan.snapshotComplete()) {
                throw error(CmesErrorCode.PORTFOLIO_INCOMPLETE, "CMES returned an incomplete plan", null);
            }
            String externalId = stableExternalId(plan.externalId());
            if (!externalIds.add(externalId)) {
                throw error(CmesErrorCode.INVALID_DATA, "CMES returned duplicate plans", null);
            }
            if (plan.balanceEur() == null || plan.positions() == null) {
                throw error(CmesErrorCode.INVALID_DATA, "CMES returned incomplete plan values", null);
            }

            List<PreparedPosition> positions = preparePositions(plan.positions());
            BigDecimal positionValue = positions.stream()
                .map(PreparedPosition::valueEur)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            // The sidecar already checked this, and it stays checked here: a
            // total that disagrees with its lines means the read was partial,
            // and overwriting a good snapshot with it would erase real money.
            if (!moneyClose(positionValue, plan.balanceEur())) {
                throw error(CmesErrorCode.PORTFOLIO_INCOMPLETE, "CMES returned an incomplete plan", null);
            }

            prepared.add(new PreparedPlan(
                externalId,
                plan.legacyExternalIds() == null
                    ? List.of()
                    : plan.legacyExternalIds().stream().map(this::stableExternalId).distinct().toList(),
                accountName(plan),
                plan.balanceEur(),
                investedAmount(plan, positions),
                positions
            ));
        }
        return List.copyOf(prepared);
    }

    private List<PreparedPosition> preparePositions(List<CmesPort.Position> rawPositions) {
        Map<String, PreparedPosition> positions = new LinkedHashMap<>();
        for (CmesPort.Position position : rawPositions) {
            if (position == null || position.quantity() == null || position.valueEur() == null) {
                throw error(CmesErrorCode.INVALID_DATA, "CMES returned an incomplete position", null);
            }
            if (position.quantity().signum() == 0 && position.valueEur().signum() == 0) {
                continue;
            }
            String ticker = resolveTicker(position);
            PreparedPosition resolved = new PreparedPosition(
                ticker,
                limit(position.label(), 100, ticker),
                position.quantity(),
                averageBuyIn(position),
                position.unitValue(),
                position.valueEur(),
                position.pnlEur()
            );
            positions.merge(ticker, resolved, this::mergePositions);
        }
        return List.copyOf(positions.values());
    }

    /**
     * FCPE units carry no purchase price, only a current valuation and an
     * unrealized gain, so the cost basis is what is left once the gain is taken
     * out. Without a gain there is nothing to derive it from.
     */
    private BigDecimal averageBuyIn(CmesPort.Position position) {
        if (position.pnlEur() == null || position.quantity().signum() == 0) {
            return null;
        }
        return position.valueEur()
            .subtract(position.pnlEur())
            .divide(position.quantity(), 8, RoundingMode.HALF_UP);
    }

    private PreparedPosition mergePositions(PreparedPosition left, PreparedPosition right) {
        // Two lines share a ticker when they are the same fund. If the labels
        // disagree, the identifier collided and merging would fuse two
        // different funds into one -- refuse rather than silently corrupt.
        if (!Objects.equals(left.name(), right.name())) {
            throw error(CmesErrorCode.INVALID_DATA, "CMES returned colliding fund identifiers", null);
        }
        BigDecimal quantity = left.quantity().add(right.quantity());
        return new PreparedPosition(
            left.ticker(),
            left.name(),
            quantity,
            weightedAverage(left.averageBuyInEur(), left.quantity(), right.averageBuyInEur(), right.quantity(), quantity),
            left.unitValue() != null ? left.unitValue() : right.unitValue(),
            left.valueEur().add(right.valueEur()),
            sumComplete(left.pnlEur(), right.pnlEur())
        );
    }

    private BigDecimal investedAmount(CmesPort.PlanData plan, List<PreparedPosition> positions) {
        BigDecimal invested = BigDecimal.ZERO;
        for (PreparedPosition position : positions) {
            if (position.pnlEur() == null) {
                return plan.balanceEur();
            }
            invested = invested.add(position.valueEur().subtract(position.pnlEur()));
        }
        return invested;
    }

    private boolean commitPlans(SyncJob job, List<PreparedPlan> prepared) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<CmesSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("CMES sync session disappeared before commit (member={})", job.memberId());
                return false;
            }
            CmesSession session = current.get();
            if (!session.isActive()) {
                log.warn("CMES sync session became inactive before commit (member={})", job.memberId());
                return false;
            }
            if (session.getSyncStatus() != CmesSyncStatus.RUNNING) {
                log.warn(
                    "CMES sync cannot commit from state {} (member={})",
                    session.getSyncStatus(),
                    job.memberId()
                );
                return false;
            }

            FamilyMember member = memberRepository.findById(job.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            Instant syncedAt = Instant.now();
            List<Account> imported = accountRepository.findAllByMemberIdAndProvider(job.memberId(), PROVIDER);
            // Resolved before any write: an ambiguous match fails the sync with
            // every account as it was.
            Map<String, Account> inherited = inheritedAccounts(prepared, imported);
            for (PreparedPlan data : prepared) {
                upsertAccount(data, inherited.get(data.externalId()), member, job.memberId(), syncedAt);
            }
            closeUnlistedPlans(prepared, imported, job.memberId(), syncedAt);

            session.markSuccessful(syncedAt);
            sessionRepository.save(session);
            return true;
        }));
    }

    /**
     * Picks, for each plan without an account under its new id, the account an
     * earlier build imported it as, so its history follows the plan instead of
     * a fresh account starting next to a zeroed one.
     *
     * <p>Earlier ids hung on the family and on the plans listed beside it at
     * the time, so the id alone proves nothing: {@code cmes_<n>_PEE} may be a
     * PEE closed since, which the remaining plan of the family now also names.
     * An account matches a plan only if it carries one of the plan's earlier ids
     * <em>and</em> the name every build gave that plan. An account two plans
     * match fails the sync rather than hand one plan's history to the other. A
     * plan two accounts match (it changed id once already) takes the one synced
     * last; the other closes at zero with the unlisted plans.
     */
    private Map<String, Account> inheritedAccounts(List<PreparedPlan> prepared, List<Account> imported) {
        Set<String> current = new HashSet<>();
        prepared.forEach(plan -> current.add(plan.externalId()));
        Map<String, Account> inherited = new HashMap<>();
        for (Account account : imported) {
            String externalId = account.getExternalAccountId();
            if (externalId == null || current.contains(externalId)) {
                continue;
            }
            List<PreparedPlan> claimants = prepared.stream()
                .filter(plan -> plan.legacyExternalIds().contains(externalId)
                    && plan.name().equals(account.getName()))
                .toList();
            if (claimants.size() > 1) {
                throw error(CmesErrorCode.INVALID_DATA, "Several CMES plans match one earlier account", null);
            }
            if (claimants.size() == 1) {
                inherited.merge(claimants.getFirst().externalId(), account, CmesSyncService::syncedLast);
            }
        }
        return inherited;
    }

    private static Account syncedLast(Account left, Account right) {
        Comparator<Account> bySync = Comparator.comparing(
            Account::getLastSyncedAt, Comparator.nullsFirst(Comparator.naturalOrder()));
        return bySync.compare(left, right) >= 0 ? left : right;
    }

    /**
     * {@code inherited} is the account an earlier build imported the plan as,
     * from {@link #inheritedAccounts}; it takes the plan's new id. A deleted
     * account stays deleted under either id, matched on the name too. A
     * deletion under the new id wins over an earlier account still live: after
     * an upgrade that left two earlier accounts, deleting the moved one must
     * not let the other bring the plan back.
     */
    private void upsertAccount(PreparedPlan data, Account inherited, FamilyMember member, Long memberId,
                               Instant syncedAt) {
        Optional<Account> existing = accountRepository.findByExternalAccountIdAndMemberId(data.externalId(), memberId);
        if (existing.isEmpty()
            && (accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), memberId)
                || (inherited == null
                    && !data.legacyExternalIds().isEmpty()
                    && accountRepository.existsSoftDeletedByExternalAccountIdInAndNameAndMemberId(
                        data.legacyExternalIds(), data.name(), memberId)))) {
            log.info("CMES skipped a soft-deleted account (member={})", memberId);
            return;
        }
        existing = existing.or(() -> Optional.ofNullable(inherited));

        Account account = existing.orElseGet(() -> Account.builder()
            .member(member)
            .provider(PROVIDER)
            .currency("EUR")
            .isManual(false)
            .color(ACCOUNT_COLOR)
            .build());
        account.setExternalAccountId(data.externalId());
        account.setName(data.name());
        account.setType(AccountType.EMPLOYEE_SAVINGS);
        account.setProvider(PROVIDER);
        account.setCurrency("EUR");
        account.setManual(false);
        account.setCurrentBalance(data.balanceEur());
        // Every euro of an employee savings plan sits in a fund: no cash sleeve.
        account.setCashBalance(null);
        account.setLastSyncedAt(syncedAt);
        Account savedAccount = accountRepository.save(account);

        holdingRepository.deleteByAccountId(savedAccount.getId());
        holdingRepository.flush();
        List<AccountHolding> holdings = data.positions().stream()
            .map(position -> AccountHolding.builder()
                .account(savedAccount)
                .ticker(position.ticker())
                .name(position.name())
                .quantity(position.quantity())
                .averageBuyIn(position.averageBuyInEur())
                .currentPrice(position.unitValue())
                .quoteCurrency(position.unitValue() != null ? "EUR" : null)
                .providerValueEur(position.valueEur())
                .providerPnlEur(position.pnlEur())
                .lastSyncedAt(syncedAt)
                .build())
            .toList();
        holdingRepository.saveAll(holdings);
        holdingRepository.flush();

        accountService.upsertSnapshot(
            savedAccount,
            data.balanceEur(),
            data.investedAmountEur(),
            LocalDate.now()
        );
    }

    /**
     * Zeroes the imported plans a complete fetch no longer lists: the saver
     * closed or transferred them, and their last balance would otherwise stay
     * in the net worth. Only complete fetches reach this point (see
     * {@link #preparePlans}). The account is closed at zero rather than soft
     * deleted: a soft-deleted account reads as removed by the user and would
     * never be imported again, while a plan can reopen.
     */
    private void closeUnlistedPlans(List<PreparedPlan> prepared, List<Account> imported, Long memberId,
                                    Instant syncedAt) {
        Set<String> listed = new HashSet<>();
        prepared.forEach(plan -> listed.add(plan.externalId()));
        // An inherited account already carries its plan's new id here.
        for (Account account : imported) {
            String externalId = account.getExternalAccountId();
            if (externalId == null || !externalId.startsWith(EXTERNAL_ID_PREFIX) || listed.contains(externalId)) {
                continue;
            }
            if (account.getCurrentBalance() != null && account.getCurrentBalance().signum() == 0) {
                continue;
            }
            log.info("CMES no longer lists an imported plan, closing it at zero (member={}; account={})",
                memberId, account.getId());
            account.setCurrentBalance(BigDecimal.ZERO);
            account.setCashBalance(null);
            account.setLastSyncedAt(syncedAt);
            Account saved = accountRepository.save(account);
            holdingRepository.deleteByAccountId(saved.getId());
            accountService.upsertSnapshot(saved, BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.now());
        }
    }

    private void markFailed(SyncJob job, CmesErrorCode code) {
        try {
            txTemplate.executeWithoutResult(status -> {
                Optional<CmesSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                    job.sessionId(),
                    job.memberId()
                );
                if (current.isEmpty()) {
                    log.info("CMES sync session disappeared before failure was recorded (member={})", job.memberId());
                    return;
                }
                CmesSession session = current.get();
                if (!session.isSyncInFlight()) {
                    log.warn(
                        "CMES sync failure ignored from state {} (member={}; code={})",
                        session.getSyncStatus(),
                        job.memberId(),
                        code
                    );
                    return;
                }
                // A rejected password must not be replayed by every scheduled sync:
                // the site locks the access after a few failures.
                if (code == CmesErrorCode.INVALID_CREDENTIALS) session.setEncryptedCredentials(null);
                session.markFailed(code, Instant.now());
                sessionRepository.save(session);
            });
        } catch (RuntimeException ex) {
            log.error(
                "Could not persist CMES sync failure (member={}; code={})",
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
            List.of(CmesSyncStatus.QUEUED, CmesSyncStatus.RUNNING),
            CmesSyncStatus.FAILED,
            Instant.now(),
            CmesErrorCode.INTERNAL_ERROR
        );
        if (recovered > 0) {
            log.warn("Recovered {} interrupted CMES sync job(s)", recovered);
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
     * dropped too but does not count: it never became a connection. A login
     * still waiting on the bank is cancelled as well, so the saver's phone
     * approval cannot bring the credentials back after the removal.
     */
    public boolean clearSession(Long memberId) {
        synchronized (sessionWriteLock) {
            authGenerations.merge(memberId, 1L, Long::sum);
            boolean removed = Boolean.TRUE.equals(txTemplate.execute(status -> {
                var session = sessionRepository.findByMemberIdForUpdate(memberId);
                session.ifPresent(sessionRepository::delete);
                return session.isPresent();
            }));
            pendingCredentials.entrySet().removeIf(entry -> entry.getValue().memberId().equals(memberId));
            return removed;
        }
    }

    public SourceSyncResult resyncReporting(Long memberId) {
        try {
            SessionStatusResponse status = getStatus(memberId);
            if (!status.isActive()) {
                if (status.lastSyncError() == CmesErrorCode.SESSION_EXPIRED
                    || status.lastSyncError() == CmesErrorCode.INVALID_CREDENTIALS) {
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
            log.error("Database error during scheduled CMES sync (member={})", memberId, ex);
            return new SourceSyncResult(SOURCE, SourceSyncResult.Status.FAILED, "Database error");
        } catch (SyncException ex) {
            log.warn("Could not queue scheduled CMES sync (member={}; code={})", memberId, ex.getCode(), ex);
            SourceSyncResult classified = SourceSyncResult.fromSyncException(SOURCE, ex);
            return new SourceSyncResult(SOURCE, classified.status(),
                classified.status() == SourceSyncResult.Status.NEEDS_REAUTH ? ex.getCode() : "Sync failed");
        } catch (Exception ex) {
            log.error("CMES scheduled sync failed unexpectedly for member {}", memberId, ex);
            return new SourceSyncResult(SOURCE, SourceSyncResult.Status.FAILED, "Unexpected sync error");
        }
    }

    private SessionStatusResponse toStatus(CmesSession session) {
        return new SessionStatusResponse(
            session.isActive(),
            session.getSyncStatus(),
            session.getLastSyncStartedAt(),
            session.getLastSyncCompletedAt(),
            session.getLastSyncError()
        );
    }

    private CmesErrorCode codeOf(SyncException exception) {
        return errorCode(exception, CmesErrorCode.class, CmesErrorCode.UPSTREAM_UNAVAILABLE);
    }

    private SyncException error(CmesErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    /** "Plan d'épargne groupe — ACME" reads better than the plan label alone. */
    private String accountName(CmesPort.PlanData plan) {
        String label = clean(plan.name());
        String kind = clean(plan.planKind());
        String base = label != null ? label : kind;
        if (base == null) {
            base = "Épargne salariale";
        }
        String employer = clean(plan.employer());
        String composed = employer != null && !base.toLowerCase(Locale.ROOT).contains(employer.toLowerCase(Locale.ROOT))
            ? base + " — " + employer
            : base;
        return limit(composed, 100, "Épargne salariale");
    }

    private String stableExternalId(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            throw error(CmesErrorCode.INVALID_DATA, "CMES returned an invalid plan identifier", null);
        }
        String externalId = cleaned.startsWith(EXTERNAL_ID_PREFIX) ? cleaned : EXTERNAL_ID_PREFIX + cleaned;
        if (externalId.length() > 100) {
            throw error(CmesErrorCode.INVALID_DATA, "CMES returned an invalid plan identifier", null);
        }
        return externalId;
    }

    /**
     * Employer funds are not quoted anywhere Picsou can price them, so no
     * conversion is attempted: the ISIN is the ticker when there is one, else
     * the fund's own code prefixed to keep it apart from a real symbol, and the
     * label as the last resort, behind the collision guard in {@link #mergePositions}.
     */
    private String resolveTicker(CmesPort.Position position) {
        String isin = clean(position.isin());
        if (isin != null) {
            isin = isin.toUpperCase(Locale.ROOT);
            if (isin.matches("[A-Z]{2}[A-Z0-9]{9}[0-9]")) {
                return isin;
            }
        }
        String fundCode = slug(position.fundCode());
        if (fundCode != null) {
            return slug("CMES-" + fundCode);
        }
        String label = slug(position.label());
        if (label == null) {
            throw error(CmesErrorCode.INVALID_DATA, "CMES returned a fund without any identifier", null);
        }
        return label;
    }

    /** Uppercase alphanumeric slug, capped at {@value #MAX_TICKER_LENGTH}, or null if nothing is left. */
    private String slug(String value) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return null;
        }
        String slug = cleaned.toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            return null;
        }
        return slug.length() <= MAX_TICKER_LENGTH ? slug : slug.substring(0, MAX_TICKER_LENGTH);
    }

    public record AuthInitResponse(String processId, boolean mfaRequired, String mfaType) {}

    public record SessionStatusResponse(
        boolean isActive,
        CmesSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        CmesErrorCode lastSyncError
    ) {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, CmesSyncStatus.IDLE, null, null, null);
        }
    }

    private record Credentials(String login, String password) {}
    private record PendingCredentials(String login, String password, Instant expiresAt, Long memberId,
                                      long generation) {}
    private record QueueDecision(SyncJob job, SessionStatusResponse status) {}
    private record SyncJob(Long sessionId, Long memberId, String plainState) {}
    private record PreparedPlan(
        String externalId,
        List<String> legacyExternalIds,
        String name,
        BigDecimal balanceEur,
        BigDecimal investedAmountEur,
        List<PreparedPosition> positions
    ) {}
    private record PreparedPosition(
        String ticker,
        String name,
        BigDecimal quantity,
        BigDecimal averageBuyInEur,
        BigDecimal unitValue,
        BigDecimal valueEur,
        BigDecimal pnlEur
    ) {}
}
