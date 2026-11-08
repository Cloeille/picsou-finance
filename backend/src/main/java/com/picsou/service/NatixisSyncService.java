package com.picsou.service;

import com.picsou.adapter.NatixisAdapter;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.SidecarInitiate;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SidecarSessionRepository;
import com.picsou.repository.TransactionRepository;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
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
import static com.picsou.service.SyncValues.limit;
import static com.picsou.service.SyncValues.moneyClose;
import static com.picsou.service.SyncValues.requireTransactionResult;
import static com.picsou.service.SyncValues.sumComplete;
import static com.picsou.service.SyncValues.weightedAverage;

/**
 * Imports Natixis Interépargne plans, one Picsou account per plan.
 *
 * <p>Shaped like {@link CmesSyncService} without its stored credentials: Natixis
 * asks for a code on every login, so a password could never log in again
 * unattended. The session is a refresh token the sidecar may rotate on each
 * read; the one {@code /positions} returns replaces the stored one every time.
 * Once Natixis refuses it, the session goes inactive until the saver
 * reconnects. A partial read is discarded rather than merged.
 */
@Service
public class NatixisSyncService {
    private static final Logger log = LoggerFactory.getLogger(NatixisSyncService.class);
    static final String PROVIDER = "Natixis Interépargne";
    static final String SOURCE = "natixis";
    private static final String EXTERNAL_ID_PREFIX = "natixis_";
    private static final String ACCOUNT_COLOR = "#8b5cf6";
    private static final int MAX_TICKER_LENGTH = 30;
    /** The sidecar forgets a login attempt after 600 s. */
    private static final long PENDING_ATTEMPT_SECONDS = 600;
    /**
     * An in-flight job untouched for this long has no live worker behind it. Well above
     * the adapter's 120 s positions call, so a job another backend sharing the database
     * just started is left alone.
     */
    static final Duration STALE_SYNC_AFTER = Duration.ofMinutes(10);
    /** Hex characters of the label hash that keeps two label-only fund tickers apart. */
    private static final int LABEL_HASH_LENGTH = 8;
    /**
     * Natixis lists operations by the date the demand was made, but an operation is dated when
     * the units were bought or sold, days later (2 to 16 days observed). A stored operation dated
     * within this margin of the first listing date may belong to a demand listed just before it:
     * it is outside what the read covered, so it is never deleted on its absence.
     */
    private static final int SETTLEMENT_MARGIN_DAYS = 90;

    private final NatixisAdapter port;
    private final SidecarSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;
    private final FamilyMemberRepository memberRepository;
    private final AccountService accountService;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final Executor syncExecutor;
    /**
     * Which member opened each login attempt, so one member cannot finish
     * another's. Holds no secret: the sidecar keeps the login itself.
     */
    private final Map<String, PendingAttempt> pendingAttempts = new ConcurrentHashMap<>();
    /**
     * Bumped by {@link #clearSession}: a login that was waiting on the code when
     * the connection was removed carries an older generation and stores nothing.
     * Checked and bumped under {@link #sessionWriteLock}.
     */
    private final Map<Long, Long> authGenerations = new ConcurrentHashMap<>();
    private final Object sessionWriteLock = new Object();

    /** When this backend built the service: rows touched earlier belong to a previous process. */
    private final Instant startedAt;

    public NatixisSyncService(
        NatixisAdapter port,
        SidecarSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository memberRepository,
        AccountService accountService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("natixisSyncExecutor") Executor syncExecutor
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.memberRepository = memberRepository;
        this.accountService = accountService;
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.syncExecutor = syncExecutor;
        this.startedAt = Instant.now();
    }

    public AuthInitResponse initiateAuth(String email, String password, Long memberId) {
        long generation = authGeneration(memberId);
        SidecarInitiate result = port.initiateAuth(email, password);
        // Natixis always asks for a second factor: an answer without an attempt
        // to complete is not one this connector knows how to finish.
        if (!result.mfaRequired() || result.processId() == null || result.processId().isBlank()) {
            throw error(SidecarErrorCode.INVALID_DATA, "Natixis did not return a login attempt", null);
        }
        evictExpiredPendingAttempts();
        boolean removedMeanwhile;
        synchronized (sessionWriteLock) {
            removedMeanwhile = authGeneration(memberId) != generation;
            if (!removedMeanwhile) {
                pendingAttempts.put(result.processId(), new PendingAttempt(
                    memberId, generation, Instant.now().plusSeconds(PENDING_ATTEMPT_SECONDS)));
            }
        }
        if (removedMeanwhile) {
            // The connection was removed while the sidecar opened this attempt: drop it there too.
            port.cancelAuth(result.processId());
            throw error(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED, "The Natixis connection was removed during the login", null);
        }
        return new AuthInitResponse(result.processId(), true, result.mfaType());
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void evictExpiredPendingAttempts() {
        Instant now = Instant.now();
        pendingAttempts.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    public SessionStatusResponse completeAuth(String processId, String code, Long memberId) {
        PendingAttempt attempt = pendingAttempts.get(processId);
        if (attempt == null
            || !attempt.memberId().equals(memberId)
            || !attempt.expiresAt().isAfter(Instant.now())) {
            throw error(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED, "Natixis authentication attempt expired", null);
        }
        // A wrong code throws INVALID_OTP before the attempt is dropped, so the
        // saver can type it again on the same attempt.
        String plainState = port.completeAuth(processId, code);
        pendingAttempts.remove(processId);
        if (plainState == null || plainState.isBlank()) {
            throw error(SidecarErrorCode.INVALID_DATA, "Natixis did not return a session", null);
        }
        SyncJob job;
        synchronized (sessionWriteLock) {
            requireGeneration(memberId, attempt.generation());
            job = insertSession(plainState, memberId);
        }
        submit(job);
        return getStatus(memberId);
    }

    public SessionStatusResponse queueSync(Long memberId) {
        QueueDecision decision = requireTransactionResult(txTemplate.execute(status -> {
            SidecarSession session = sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.NATIXIS, memberId)
                .orElseThrow(() -> error(
                    SidecarErrorCode.SESSION_EXPIRED,
                    "No active Natixis session. Please reconnect.",
                    null
                ));
            if (!session.isActive()) {
                throw error(
                    SidecarErrorCode.SESSION_EXPIRED,
                    "The Natixis session expired. Please reconnect.",
                    null
                );
            }
            if (session.isSyncInFlight()) {
                return new QueueDecision(null, toStatus(session));
            }

            String plainState = encryption.decrypt(session.getSessionState());
            session.markQueued();
            sessionRepository.save(session);
            return new QueueDecision(new SyncJob(session.getId(), memberId, plainState), toStatus(session));
        }));

        if (decision.job() != null) {
            submit(decision.job());
            return getStatus(memberId);
        }
        return decision.status();
    }

    private long authGeneration(Long memberId) {
        return authGenerations.getOrDefault(memberId, 0L);
    }

    /** Must be called under {@link #sessionWriteLock}, in the same block as the write it guards. */
    private void requireGeneration(Long memberId, long generation) {
        if (authGeneration(memberId) != generation) {
            throw error(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED, "The Natixis connection was removed during the login", null);
        }
    }

    private SyncJob insertSession(String plainState, Long memberId) {
        return requireTransactionResult(txTemplate.execute(status -> {
            FamilyMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.NATIXIS, memberId).ifPresent(sessionRepository::delete);
            sessionRepository.flush();

            SidecarSession newSession = SidecarSession.create(SidecarBank.NATIXIS, member, encryption.encrypt(plainState), Instant.now());
            newSession.markQueued();
            SidecarSession stored = sessionRepository.saveAndFlush(newSession);
            return new SyncJob(stored.getId(), memberId, plainState);
        }));
    }

    private void submit(SyncJob job) {
        try {
            syncExecutor.execute(() -> executeJob(job));
        } catch (RuntimeException ex) {
            markFailed(job, SidecarErrorCode.INTERNAL_ERROR);
            throw error(SidecarErrorCode.INTERNAL_ERROR, "Could not schedule the Natixis synchronization", ex);
        }
    }

    private void executeJob(SyncJob job) {
        boolean running;
        try {
            running = markRunning(job);
        } catch (RuntimeException ex) {
            // Left QUEUED, the session would refuse every later sync and keep the UI
            // polling. A failure is retryable; if even that write fails, the stale-job
            // sweep in recoverInterruptedSyncs picks it up.
            log.warn("Natixis sync could not start (member={})", job.memberId(), ex);
            markFailed(job, SidecarErrorCode.UPSTREAM_UNAVAILABLE);
            return;
        }
        if (!running) {
            return;
        }
        try {
            NatixisAdapter.PositionsData fetched = port.fetchPlans(job.plainState());
            if (fetched == null) {
                throw error(SidecarErrorCode.INVALID_DATA, "Natixis returned no answer", null);
            }
            // Stored before the plans are checked: the old refresh token may
            // already be spent, so even a read discarded below must not lose it.
            storeRefreshedState(job, fetched.sessionState());
            List<PreparedPlan> prepared = preparePlans(fetched.plans());
            OperationsHistory operations = prepareOperations(fetched);
            if (commitPlans(job, prepared, operations)) {
                log.info("Natixis sync completed (member={}; plans={})", job.memberId(), prepared.size());
            } else {
                log.info("Discarded stale Natixis sync result (member={})", job.memberId());
            }
        } catch (SyncException ex) {
            SidecarErrorCode code = codeOf(ex);
            markFailed(job, code);
            log.warn("Natixis sync failed (member={}; code={})", job.memberId(), code);
        } catch (Exception ex) {
            markFailed(job, SidecarErrorCode.INTERNAL_ERROR);
            log.error("Natixis sync failed unexpectedly (member={})", job.memberId(), ex);
        }
    }

    private void storeRefreshedState(SyncJob job, String plainState) {
        if (plainState == null || plainState.isBlank()) {
            log.warn("Natixis returned no session state, keeping the stored one (member={})", job.memberId());
            return;
        }
        txTemplate.executeWithoutResult(status -> sessionRepository
            .findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
            .filter(SidecarSession::isActive)
            .ifPresent(session -> {
                session.updateSessionState(encryption.encrypt(plainState), Instant.now());
                sessionRepository.save(session);
            }));
    }

    private boolean markRunning(SyncJob job) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<SidecarSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("Natixis sync session disappeared before execution (member={})", job.memberId());
                return false;
            }
            SidecarSession session = current.get();
            if (!session.isActive() || session.getSyncStatus() != SidecarSyncStatus.QUEUED) {
                log.warn(
                    "Natixis sync cannot start from state {} (member={}; active={})",
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

    /**
     * An empty list is a saver who closed every plan, not a partial read: the sidecar only
     * answers once the products total matches, so {@link #closeUnlistedPlans} zeroes them all.
     */
    private List<PreparedPlan> preparePlans(List<NatixisAdapter.PlanData> fetched) {
        if (fetched == null) {
            throw error(SidecarErrorCode.PORTFOLIO_INCOMPLETE, "Natixis returned no complete plan", null);
        }

        Set<String> externalIds = new HashSet<>();
        List<PreparedPlan> prepared = new ArrayList<>();
        for (NatixisAdapter.PlanData plan : fetched) {
            if (plan == null || !plan.snapshotComplete()) {
                throw error(SidecarErrorCode.PORTFOLIO_INCOMPLETE, "Natixis returned an incomplete plan", null);
            }
            String externalId = stableExternalId(plan.externalId());
            if (!externalIds.add(externalId)) {
                throw error(SidecarErrorCode.INVALID_DATA, "Natixis returned duplicate plans", null);
            }
            if (plan.balanceEur() == null || plan.positions() == null) {
                throw error(SidecarErrorCode.INVALID_DATA, "Natixis returned incomplete plan values", null);
            }

            List<PreparedPosition> positions = preparePositions(plan.positions());
            BigDecimal positionValue = positions.stream()
                .map(PreparedPosition::valueEur)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            // The sidecar already checked this, and it stays checked here: a
            // total that disagrees with its lines means the read was partial,
            // and overwriting a good snapshot with it would erase real money.
            if (!moneyClose(positionValue, plan.balanceEur())) {
                throw error(SidecarErrorCode.PORTFOLIO_INCOMPLETE, "Natixis returned an incomplete plan", null);
            }

            prepared.add(new PreparedPlan(
                externalId,
                accountName(plan),
                plan.balanceEur(),
                investedAmount(plan, positions),
                positions
            ));
        }
        return List.copyOf(prepared);
    }

    private List<PreparedPosition> preparePositions(List<NatixisAdapter.Position> rawPositions) {
        Map<String, PreparedPosition> positions = new LinkedHashMap<>();
        for (NatixisAdapter.Position position : rawPositions) {
            if (position == null || position.quantity() == null || position.valueEur() == null) {
                throw error(SidecarErrorCode.INVALID_DATA, "Natixis returned an incomplete position", null);
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

    /** FCPE units carry no purchase price: the cost basis is the valuation minus the unrealized gain. */
    private BigDecimal averageBuyIn(NatixisAdapter.Position position) {
        if (position.pnlEur() == null || position.quantity().signum() == 0) {
            return null;
        }
        return position.valueEur()
            .subtract(position.pnlEur())
            .divide(position.quantity(), 8, RoundingMode.HALF_UP);
    }

    private PreparedPosition mergePositions(PreparedPosition left, PreparedPosition right) {
        // Two lines share a ticker when they are the same fund. If the labels
        // disagree, the identifier collided: refuse rather than fuse two funds.
        if (!Objects.equals(left.name(), right.name())) {
            throw error(SidecarErrorCode.INVALID_DATA, "Natixis returned colliding fund identifiers", null);
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

    private BigDecimal investedAmount(NatixisAdapter.PlanData plan, List<PreparedPosition> positions) {
        BigDecimal invested = BigDecimal.ZERO;
        for (PreparedPosition position : positions) {
            if (position.pnlEur() == null) {
                return plan.balanceEur();
            }
            invested = invested.add(position.valueEur().subtract(position.pnlEur()));
        }
        return invested;
    }

    /** @param operations null when the history is not complete: stored operations are then left alone */
    private boolean commitPlans(SyncJob job, List<PreparedPlan> prepared, OperationsHistory operations) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            // Lock order: the member's Natixis accounts, then the session row. Deleting an
            // account takes them in that order too -- AccountConnectionService soft-deletes
            // the account, then clearSession locks the session -- so locking the session
            // first here, then writing the accounts, could deadlock against a deletion.
            List<Account> imported = accountRepository.findAllByMemberIdAndProviderForUpdate(
                job.memberId(), PROVIDER);
            Optional<SidecarSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("Natixis sync session disappeared before commit (member={})", job.memberId());
                return false;
            }
            SidecarSession session = current.get();
            if (!session.isActive() || session.getSyncStatus() != SidecarSyncStatus.RUNNING) {
                log.warn(
                    "Natixis sync cannot commit from state {} (member={}; active={})",
                    session.getSyncStatus(),
                    job.memberId(),
                    session.isActive()
                );
                return false;
            }

            FamilyMember member = memberRepository.findById(job.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            Instant syncedAt = Instant.now();
            for (PreparedPlan data : prepared) {
                upsertAccount(data, member, job.memberId(), syncedAt);
            }
            closeUnlistedPlans(prepared, imported, job.memberId(), syncedAt);
            if (operations != null) {
                reconcileOperations(operations.operations(), operations.deletableFrom(), job.memberId());
            }

            session.markSuccessful(syncedAt);
            sessionRepository.save(session);
            return true;
        }));
    }

    private void upsertAccount(PreparedPlan data, FamilyMember member, Long memberId, Instant syncedAt) {
        Optional<Account> existing = accountRepository.findByExternalAccountIdAndMemberId(data.externalId(), memberId);
        if (existing.isEmpty()
            && accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), memberId)) {
            log.info("Natixis skipped a soft-deleted account (member={})", memberId);
            return;
        }

        Account account = existing.orElseGet(() -> Account.builder()
            .member(member)
            .provider(PROVIDER)
            // The sidecar rejects any amount whose currencyCode is not EUR.
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

        accountService.upsertSnapshot(savedAccount, data.balanceEur(), data.investedAmountEur(), LocalDate.now());
    }

    /**
     * Validates the operations history before the commit, like the plans. Returns null when
     * there is no complete history to apply -- an older sidecar, a history Natixis could not
     * serve in full, or a line this backend cannot read -- so the stored operations stay as
     * they are. A bad history never fails the plans: they are valid on their own.
     */
    private OperationsHistory prepareOperations(NatixisAdapter.PositionsData fetched) {
        if (!fetched.hasCompleteOperations()) {
            return null;
        }
        try {
            List<PreparedOperation> prepared = new ArrayList<>();
            for (NatixisAdapter.Operation operation : fetched.operations()) {
                prepared.add(prepareOperation(operation));
            }
            return new OperationsHistory(List.copyOf(prepared), deletableFrom(fetched.operationsFrom()));
        } catch (SyncException | DateTimeParseException ex) {
            log.warn("Natixis returned an unreadable operations history, keeping the stored one");
            return null;
        }
    }

    /**
     * The first date an operation may be deleted on its absence: the read's first listing date
     * plus the settlement margin. Null -- delete nothing -- when the sidecar did not say what it
     * covered (an older sidecar) or said it in a form this backend cannot read.
     */
    private static LocalDate deletableFrom(String operationsFrom) {
        if (operationsFrom == null) {
            return null;
        }
        try {
            return LocalDate.parse(operationsFrom).plusDays(SETTLEMENT_MARGIN_DAYS);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private PreparedOperation prepareOperation(NatixisAdapter.Operation operation) {
        if (operation == null || operation.quantity() == null || operation.quantity().signum() <= 0
            || operation.amountEur() == null || operation.amountEur().signum() < 0
            || operation.feesEur() != null && operation.feesEur().signum() < 0) {
            throw error(SidecarErrorCode.INVALID_DATA, "Natixis returned an incomplete operation", null);
        }
        String externalId = clean(operation.externalId());
        if (externalId != null && !externalId.startsWith(EXTERNAL_ID_PREFIX)) {
            // The prefix is what marks a row as this connector's in reconcileOperations.
            externalId = EXTERNAL_ID_PREFIX + externalId;
        }
        String planId = clean(operation.planExternalId());
        String date = clean(operation.date());
        if (externalId == null || externalId.length() > 100 || planId == null || date == null) {
            throw error(SidecarErrorCode.INVALID_DATA, "Natixis returned an operation without identifiers", null);
        }
        TransactionType type = switch (String.valueOf(operation.type())) {
            case "BUY" -> TransactionType.BUY;
            case "SELL" -> TransactionType.SELL;
            default -> throw error(SidecarErrorCode.INVALID_DATA, "Natixis returned an unknown operation type", null);
        };
        // The holdings' own resolution, so a sale pairs with the buys of the same fund.
        String ticker = resolveTicker(new NatixisAdapter.Position(null, operation.fundCode(), operation.fundLabel(),
            operation.quantity(), operation.unitValue(), operation.amountEur(), null));
        BigDecimal fees = operation.feesEur();
        BigDecimal net = fees != null ? fees : BigDecimal.ZERO;
        BigDecimal unitValue = operation.unitValue() != null
            ? operation.unitValue()
            : operation.amountEur().divide(operation.quantity(), 8, RoundingMode.HALF_UP);
        // The repo's ledger sign (TransactionAmountCalculator): a buy is an outflow of the
        // amount plus its fees, a sale an inflow of the proceeds less its fees.
        BigDecimal amount = type == TransactionType.BUY
            ? operation.amountEur().add(net).negate()
            : operation.amountEur().subtract(net);
        return new PreparedOperation(
            externalId,
            planId.startsWith(EXTERNAL_ID_PREFIX) ? planId : EXTERNAL_ID_PREFIX + planId,
            LocalDate.parse(date),
            type,
            limit(operation.label(), 255, type == TransactionType.BUY ? "Investissement" : "Désinvestissement"),
            limit(operation.fundLabel(), 100, ticker),
            ticker,
            operation.quantity(),
            unitValue,
            fees,
            amount
        );
    }

    /**
     * Mirrors the complete history onto each plan account, keyed on Natixis' stable
     * operation id. History only: balances, holdings and snapshots stay the plan
     * snapshot's. Deletes only this connector's rows dated on or after {@code deletableFrom},
     * i.e. inside what the read covered; manual rows and anything older are never touched,
     * and nothing is deleted when {@code deletableFrom} is null.
     */
    private void reconcileOperations(List<PreparedOperation> operations, LocalDate deletableFrom, Long memberId) {
        Map<String, Account> accounts = new LinkedHashMap<>();
        for (Account account : accountRepository.findAllByMemberIdAndProvider(memberId, PROVIDER)) {
            String externalId = account.getExternalAccountId();
            if (externalId != null && externalId.startsWith(EXTERNAL_ID_PREFIX)) {
                accounts.put(externalId, account);
            }
        }
        Map<String, Map<String, PreparedOperation>> byAccount = new LinkedHashMap<>();
        accounts.keySet().forEach(id -> byAccount.put(id, new LinkedHashMap<>()));
        int unmatched = 0;
        for (PreparedOperation operation : operations) {
            Map<String, PreparedOperation> reported = byAccount.get(operation.planExternalId());
            if (reported == null) {
                unmatched++;
                continue;
            }
            reported.put(operation.externalId(), operation);
        }
        if (unmatched > 0) {
            log.info("Natixis skipped {} operation(s) of plans without an account (member={})", unmatched, memberId);
        }

        List<Transaction> obsolete = new ArrayList<>();
        List<Transaction> upserts = new ArrayList<>();
        for (Map.Entry<String, Map<String, PreparedOperation>> entry : byAccount.entrySet()) {
            Account account = accounts.get(entry.getKey());
            Map<String, PreparedOperation> reported = entry.getValue();
            Map<String, Transaction> storedById = new LinkedHashMap<>();
            for (Transaction row : transactionRepository.findByAccountIdAndIsManualFalse(account.getId())) {
                String externalId = row.getExternalId();
                if (externalId == null || !externalId.startsWith(EXTERNAL_ID_PREFIX)) {
                    continue;
                }
                if (reported.containsKey(externalId)) {
                    storedById.put(externalId, row);
                } else if (deletableFrom != null && row.getDate() != null && !row.getDate().isBefore(deletableFrom)) {
                    obsolete.add(row);
                }
            }
            // Oldest first, buys before sales on a day: same-day rows replay in id order
            // (RealizedPnlService), so a sale inserted ahead of its buy would read as an over-sell.
            reported.values().stream()
                .sorted(Comparator.comparing(PreparedOperation::date)
                    .thenComparing(operation -> operation.txType() == TransactionType.SELL))
                .forEach(operation -> upserts.add(toTransaction(
                    operation, storedById.get(operation.externalId()), account)));
        }
        if (!obsolete.isEmpty()) {
            transactionRepository.deleteAll(obsolete);
            transactionRepository.flush();
        }
        transactionRepository.saveAllAndFlush(upserts);
    }

    private Transaction toTransaction(PreparedOperation operation, Transaction stored, Account account) {
        Transaction row = stored != null ? stored : Transaction.builder()
            .account(account)
            .externalId(operation.externalId())
            .isManual(false)
            .build();
        row.setDate(operation.date());
        row.setTxType(operation.txType());
        row.setDescription(operation.description());
        row.setName(operation.name());
        row.setTicker(operation.ticker());
        row.setQuantity(operation.quantity());
        row.setPricePerUnit(operation.unitValue());
        row.setFees(operation.fees());
        row.setAmount(operation.amount());
        row.setNativeCurrency("EUR");
        // Fund purchases and sales inside the plan, not money in or out of the budget:
        // kept off cash flow, recurring detection and categorization (Transaction#budgetNeutral).
        row.setBudgetNeutral(true);
        return row;
    }

    /**
     * Zeroes the imported plans a complete fetch no longer lists: the saver
     * closed or transferred them. Closed at zero rather than soft deleted, which
     * would read as removed by the user and block a plan that reopens.
     */
    private void closeUnlistedPlans(List<PreparedPlan> prepared, List<Account> imported, Long memberId,
                                    Instant syncedAt) {
        Set<String> listed = new HashSet<>();
        prepared.forEach(plan -> listed.add(plan.externalId()));
        for (Account account : imported) {
            String externalId = account.getExternalAccountId();
            if (externalId == null || !externalId.startsWith(EXTERNAL_ID_PREFIX) || listed.contains(externalId)) {
                continue;
            }
            if (account.getCurrentBalance() != null && account.getCurrentBalance().signum() == 0) {
                continue;
            }
            log.info("Natixis no longer lists an imported plan, closing it at zero (member={}; account={})",
                memberId, account.getId());
            account.setCurrentBalance(BigDecimal.ZERO);
            account.setCashBalance(null);
            account.setLastSyncedAt(syncedAt);
            Account saved = accountRepository.save(account);
            holdingRepository.deleteByAccountId(saved.getId());
            accountService.upsertSnapshot(saved, BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.now());
        }
    }

    private void markFailed(SyncJob job, SidecarErrorCode code) {
        try {
            txTemplate.executeWithoutResult(status -> {
                Optional<SidecarSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                    job.sessionId(),
                    job.memberId()
                );
                if (current.isEmpty()) {
                    log.info("Natixis sync session disappeared before failure was recorded (member={})", job.memberId());
                    return;
                }
                SidecarSession session = current.get();
                if (!session.isSyncInFlight()) {
                    log.warn(
                        "Natixis sync failure ignored from state {} (member={}; code={})",
                        session.getSyncStatus(),
                        job.memberId(),
                        code
                    );
                    return;
                }
                session.markFailed(code, Instant.now());
                sessionRepository.save(session);
            });
        } catch (RuntimeException ex) {
            log.error("Could not persist Natixis sync failure (member={}; code={})", job.memberId(), code, ex);
        }
    }

    /**
     * Queued jobs live in a process-local executor and cannot survive a backend
     * restart. At boot, every in-flight job last touched before this backend
     * started is an orphan of the previous process: it is failed (retryable) at
     * once, so "Sync all" and the scheduled sync are not held up for the stale
     * window. A job queued since the start is left alone.
     */
    @Transactional
    public void recoverSyncsInterruptedByRestart() {
        markInterruptedSyncsFailed(startedAt);
    }

    /**
     * The periodic sweep, for a job whose worker died without a restart: only
     * jobs untouched for {@link #STALE_SYNC_AFTER} are failed, as a live one
     * keeps updating its row.
     */
    @Transactional
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void recoverInterruptedSyncs() {
        markInterruptedSyncsFailed(Instant.now().minus(STALE_SYNC_AFTER));
    }

    private void markInterruptedSyncsFailed(Instant touchedBefore) {
        int recovered = sessionRepository.markInterruptedSyncsFailedBefore(
            SidecarBank.NATIXIS,
            List.of(SidecarSyncStatus.QUEUED, SidecarSyncStatus.RUNNING),
            SidecarSyncStatus.FAILED,
            Instant.now(),
            SidecarErrorCode.INTERNAL_ERROR,
            touchedBefore
        );
        if (recovered > 0) {
            log.warn("Recovered {} interrupted Natixis sync job(s)", recovered);
        }
    }

    @Transactional(readOnly = true)
    public SessionStatusResponse getStatus(Long memberId) {
        return sessionRepository.findByBankAndMemberId(SidecarBank.NATIXIS, memberId)
            .map(this::toStatus)
            .orElseGet(SessionStatusResponse::inactive);
    }

    /**
     * Returns whether a stored session was there to delete. A login waiting on
     * its code is cancelled too, so finishing it cannot bring the connection back.
     */
    public boolean clearSession(Long memberId) {
        boolean removed;
        List<String> dropped = new ArrayList<>();
        synchronized (sessionWriteLock) {
            authGenerations.merge(memberId, 1L, Long::sum);
            removed = Boolean.TRUE.equals(txTemplate.execute(status -> {
                var session = sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.NATIXIS, memberId);
                session.ifPresent(sessionRepository::delete);
                return session.isPresent();
            }));
            pendingAttempts.entrySet().removeIf(entry -> {
                if (!entry.getValue().memberId().equals(memberId)) {
                    return false;
                }
                dropped.add(entry.getKey());
                return true;
            });
        }
        // Outside the lock: a slow sidecar must not hold up other members' logins.
        // Best effort, never throws: the sidecar forgets the attempt after 600 s anyway.
        dropped.forEach(port::cancelAuth);
        return removed;
    }

    public SourceSyncResult resyncReporting(Long memberId) {
        try {
            SessionStatusResponse status = getStatus(memberId);
            if (!status.isActive()) {
                if (status.lastSyncError() == SidecarErrorCode.SESSION_EXPIRED
                    || status.lastSyncError() == SidecarErrorCode.INVALID_CREDENTIALS) {
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
            log.error("Database error during scheduled Natixis sync (member={})", memberId, ex);
            return new SourceSyncResult(SOURCE, SourceSyncResult.Status.FAILED, "Database error");
        } catch (SyncException ex) {
            log.warn("Could not queue scheduled Natixis sync (member={}; code={})", memberId, ex.getCode(), ex);
            SourceSyncResult classified = SourceSyncResult.fromSyncException(SOURCE, ex);
            return new SourceSyncResult(SOURCE, classified.status(),
                classified.status() == SourceSyncResult.Status.NEEDS_REAUTH ? ex.getCode() : "Sync failed");
        } catch (Exception ex) {
            log.error("Natixis scheduled sync failed unexpectedly for member {}", memberId, ex);
            return new SourceSyncResult(SOURCE, SourceSyncResult.Status.FAILED, "Unexpected sync error");
        }
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
        return SyncValues.errorCode(exception, SidecarBank.NATIXIS, SidecarErrorCode.UPSTREAM_UNAVAILABLE);
    }

    private SyncException error(SidecarErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    /** "Plan d'Épargne Entreprise — ACME" reads better than the plan label alone. */
    private String accountName(NatixisAdapter.PlanData plan) {
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
            throw error(SidecarErrorCode.INVALID_DATA, "Natixis returned an invalid plan identifier", null);
        }
        String externalId = cleaned.startsWith(EXTERNAL_ID_PREFIX) ? cleaned : EXTERNAL_ID_PREFIX + cleaned;
        if (externalId.length() > 100) {
            throw error(SidecarErrorCode.INVALID_DATA, "Natixis returned an invalid plan identifier", null);
        }
        return externalId;
    }

    /**
     * Employer funds are not quoted anywhere Picsou can price them: the ISIN is
     * the ticker when there is one, else the AMF fund code prefixed to keep it
     * apart from a real symbol. Without either (an older sidecar), the label slug
     * plus a short hash of the full label: two funds whose labels share the first
     * {@value #MAX_TICKER_LENGTH} characters keep distinct, stable tickers instead of
     * tripping the collision guard in {@link #mergePositions}.
     */
    private String resolveTicker(NatixisAdapter.Position position) {
        String isin = clean(position.isin());
        if (isin != null) {
            isin = isin.toUpperCase(Locale.ROOT);
            if (isin.matches("[A-Z]{2}[A-Z0-9]{9}[0-9]")) {
                return isin;
            }
        }
        String fundCode = slug(position.fundCode());
        if (fundCode != null) {
            return slug("NATIXIS-" + fundCode);
        }
        String label = slug(position.label());
        if (label == null) {
            throw error(SidecarErrorCode.INVALID_DATA, "Natixis returned a fund without any identifier", null);
        }
        String hash = labelHash(clean(position.label()));
        int room = MAX_TICKER_LENGTH - hash.length() - 1;
        String prefix = label.length() <= room ? label : label.substring(0, room).replaceAll("-+$", "");
        return prefix + "-" + hash;
    }

    private static String labelHash(String label) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(label.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest).substring(0, LABEL_HASH_LENGTH);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
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
        SidecarSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        SidecarErrorCode lastSyncError
    ) {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, SidecarSyncStatus.IDLE, null, null, null);
        }
    }

    private record PendingAttempt(Long memberId, long generation, Instant expiresAt) {}
    private record QueueDecision(SyncJob job, SessionStatusResponse status) {}
    /** A complete history to apply, and the first date its absences may delete from. */
    private record OperationsHistory(List<PreparedOperation> operations, LocalDate deletableFrom) {}

    /** Carries the decrypted session state: its {@code toString()} must never print it. */
    private record SyncJob(Long sessionId, Long memberId, String plainState) {
        @Override
        public String toString() {
            return "SyncJob[sessionId=" + sessionId + ", memberId=" + memberId + ", plainState=<redacted>]";
        }
    }
    private record PreparedPlan(
        String externalId,
        String name,
        BigDecimal balanceEur,
        BigDecimal investedAmountEur,
        List<PreparedPosition> positions
    ) {}
    private record PreparedOperation(
        String externalId,
        String planExternalId,
        LocalDate date,
        TransactionType txType,
        String description,
        String name,
        String ticker,
        BigDecimal quantity,
        BigDecimal unitValue,
        BigDecimal fees,
        BigDecimal amount
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
