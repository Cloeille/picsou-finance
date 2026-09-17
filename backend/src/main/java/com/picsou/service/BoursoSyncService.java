package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.BoursoSession;
import com.picsou.model.BoursoSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.port.BoursoErrorCode;
import com.picsou.port.BoursoPort;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BoursoSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Imports BoursoBank current accounts, livrets and securities accounts.
 *
 * <p>Shaped like {@link BourseDirectSyncService}: authentication only queues the
 * import, upstream I/O happens outside any transaction, and one short
 * transaction replaces every account's holdings and writes the daily snapshot.
 * A snapshot that does not reconcile is refused wholesale rather than allowed to
 * overwrite the last known-good portfolio.
 */
@Service
public class BoursoSyncService {
    private static final Logger log = LoggerFactory.getLogger(BoursoSyncService.class);
    static final String PROVIDER = "BoursoBank";
    private static final String EXTERNAL_ID_PREFIX = "bourso_";
    private static final BigDecimal ABSOLUTE_RECONCILIATION_TOLERANCE = new BigDecimal("0.05");
    private static final BigDecimal RELATIVE_RECONCILIATION_TOLERANCE = new BigDecimal("0.001");
    private static final int MAX_TICKER_LENGTH = 30;

    /** The account types the sidecar is allowed to return; loans are out of scope. */
    private static final Set<AccountType> SUPPORTED_TYPES = EnumSet.of(
        AccountType.CHECKING,
        AccountType.SAVINGS,
        AccountType.LEP,
        AccountType.LIVRET_A,
        AccountType.LDDS,
        AccountType.LIVRET_JEUNE,
        AccountType.PEL,
        AccountType.CEL,
        AccountType.PEA,
        AccountType.COMPTE_TITRES
    );

    private final BoursoPort port;
    private final BoursoSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final FamilyMemberRepository memberRepository;
    private final AccountService accountService;
    private final OpenFigiIsinConverter isinConverter;
    private final SecurityIdentityService identityService;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final Executor syncExecutor;

    public BoursoSyncService(
        BoursoPort port,
        BoursoSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        FamilyMemberRepository memberRepository,
        AccountService accountService,
        OpenFigiIsinConverter isinConverter,
        SecurityIdentityService identityService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("boursoSyncExecutor") Executor syncExecutor
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.memberRepository = memberRepository;
        this.accountService = accountService;
        this.isinConverter = isinConverter;
        this.identityService = identityService;
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.syncExecutor = syncExecutor;
    }

    public AuthInitResponse initiateAuth(String customerId, String password, Long memberId) {
        BoursoPort.InitiateResult result = port.initiateAuth(customerId, password);
        if (!result.mfaRequired()) {
            if (result.sessionState() == null || result.sessionState().isBlank()) {
                throw error(BoursoErrorCode.INVALID_DATA, "BoursoBank did not return a session", null);
            }
            storeSessionAndQueue(result.sessionState(), memberId);
        }
        return new AuthInitResponse(result.processId(), result.mfaRequired(), result.mfaType());
    }

    public SessionStatusResponse completeAuth(String processId, Long memberId) {
        String plainState = port.completeAuth(processId);
        if (plainState == null || plainState.isBlank()) {
            throw error(BoursoErrorCode.INVALID_DATA, "BoursoBank did not return a session", null);
        }
        return storeSessionAndQueue(plainState, memberId);
    }

    public SessionStatusResponse queueSync(Long memberId) {
        QueueDecision decision = requireTransactionResult(txTemplate.execute(status -> {
            BoursoSession session = sessionRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> error(
                    BoursoErrorCode.SESSION_EXPIRED,
                    "No active BoursoBank session. Please reconnect.",
                    null
                ));
            if (!session.isActive()) {
                throw error(
                    BoursoErrorCode.SESSION_EXPIRED,
                    "The BoursoBank session expired. Please reconnect.",
                    null
                );
            }
            if (session.getSyncStatus() == BoursoSyncStatus.QUEUED
                || session.getSyncStatus() == BoursoSyncStatus.RUNNING) {
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

    private SessionStatusResponse storeSessionAndQueue(String plainState, Long memberId) {
        SyncJob job = requireTransactionResult(txTemplate.execute(status -> {
            FamilyMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            sessionRepository.findByMemberIdForUpdate(memberId).ifPresent(sessionRepository::delete);
            sessionRepository.flush();

            BoursoSession newSession = BoursoSession.create(
                member,
                encryption.encrypt(plainState),
                Instant.now()
            );
            newSession.markQueued();
            BoursoSession stored = sessionRepository.saveAndFlush(newSession);
            return new SyncJob(stored.getId(), memberId, plainState);
        }));

        submit(job);
        return getStatus(memberId);
    }

    private void submit(SyncJob job) {
        try {
            syncExecutor.execute(() -> executeJob(job));
        } catch (RuntimeException ex) {
            markFailed(job, BoursoErrorCode.INTERNAL_ERROR);
            throw error(
                BoursoErrorCode.INTERNAL_ERROR,
                "Could not schedule the BoursoBank synchronization",
                ex
            );
        }
    }

    private void executeJob(SyncJob job) {
        if (!markRunning(job)) {
            return;
        }
        try {
            List<BoursoPort.AccountData> fetched = port.fetchAccounts(job.plainState());
            List<PreparedAccount> prepared = prepareAccounts(fetched);
            if (commitAccounts(job, prepared)) {
                log.info("BoursoBank sync completed (member={}; accounts={})", job.memberId(), prepared.size());
            } else {
                log.info("Discarded stale BoursoBank sync result (member={})", job.memberId());
            }
        } catch (SyncException ex) {
            BoursoErrorCode code = codeOf(ex);
            markFailed(job, code);
            log.warn("BoursoBank sync failed (member={}; code={})", job.memberId(), code);
        } catch (Exception ex) {
            markFailed(job, BoursoErrorCode.INTERNAL_ERROR);
            log.error("BoursoBank sync failed unexpectedly (member={})", job.memberId(), ex);
        }
    }

    private boolean markRunning(SyncJob job) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<BoursoSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("BoursoBank sync session disappeared before execution (member={})", job.memberId());
                return false;
            }
            BoursoSession session = current.get();
            if (!session.isActive() || session.getSyncStatus() != BoursoSyncStatus.QUEUED) {
                log.warn(
                    "BoursoBank sync cannot start from state {} (member={}; active={})",
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

    private List<PreparedAccount> prepareAccounts(List<BoursoPort.AccountData> fetched) {
        if (fetched == null || fetched.isEmpty()) {
            throw error(
                BoursoErrorCode.PORTFOLIO_INCOMPLETE,
                "BoursoBank returned no account",
                null
            );
        }

        Set<String> externalIds = new HashSet<>();
        List<PreparedAccount> prepared = new ArrayList<>();
        for (BoursoPort.AccountData account : fetched) {
            if (account == null || !account.snapshotComplete()) {
                throw error(
                    BoursoErrorCode.PORTFOLIO_INCOMPLETE,
                    "BoursoBank returned an incomplete snapshot",
                    null
                );
            }
            String externalId = stableExternalId(account.externalId());
            if (!externalIds.add(externalId)) {
                throw error(BoursoErrorCode.INVALID_DATA, "BoursoBank returned duplicate accounts", null);
            }
            if (account.type() == null || !SUPPORTED_TYPES.contains(account.type())) {
                throw error(BoursoErrorCode.INVALID_DATA, "BoursoBank returned an unsupported account type", null);
            }
            if (account.balanceEur() == null || account.positions() == null) {
                throw error(BoursoErrorCode.INVALID_DATA, "BoursoBank returned incomplete account values", null);
            }

            boolean holdsPositions = account.type().isInvestment();
            if (!holdsPositions && !account.positions().isEmpty()) {
                throw error(BoursoErrorCode.INVALID_DATA, "BoursoBank reported positions on a cash account", null);
            }

            List<PreparedPosition> positions = preparePositions(account.positions());
            BigDecimal cashBalance = account.cashBalance();
            BigDecimal investedAmount;
            if (holdsPositions) {
                if (cashBalance == null) {
                    throw error(BoursoErrorCode.INVALID_DATA, "BoursoBank returned no cash balance for a securities account", null);
                }
                // Re-run the sidecar's reconciliation rather than trust it: this is
                // the check that stops a truncated position list being written over
                // a correct portfolio, and it costs nothing to repeat.
                BigDecimal positionValue = positions.stream()
                    .map(PreparedPosition::currentValueEur)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (!moneyClose(positionValue, account.balanceEur().subtract(cashBalance))) {
                    throw error(
                        BoursoErrorCode.PORTFOLIO_INCOMPLETE,
                        "BoursoBank returned an incomplete portfolio",
                        null
                    );
                }
                investedAmount = investedAmount(account, cashBalance, positions);
            } else {
                // A livret or a current account has one number and no cash leg;
                // sending 0 rather than null would report a phantom cash balance.
                cashBalance = null;
                investedAmount = null;
            }

            prepared.add(new PreparedAccount(
                externalId,
                limit(account.name(), 100, "BoursoBank account"),
                account.type(),
                account.balanceEur(),
                cashBalance,
                investedAmount,
                positions
            ));
        }
        return List.copyOf(prepared);
    }

    private List<PreparedPosition> preparePositions(List<BoursoPort.Position> rawPositions) {
        Map<String, PreparedPosition> positions = new LinkedHashMap<>();
        Map<String, String> isinByTicker = new LinkedHashMap<>();
        for (BoursoPort.Position position : rawPositions) {
            if (position == null || position.quantity() == null || position.currentValueEur() == null) {
                throw error(BoursoErrorCode.INVALID_DATA, "BoursoBank returned an incomplete position", null);
            }
            if (position.quantity().signum() == 0) {
                continue;
            }
            String currency = normalizeCurrency(position.quoteCurrency());
            if (position.currentPrice() != null && currency == null) {
                throw error(
                    BoursoErrorCode.INVALID_DATA,
                    "BoursoBank returned a quote without its currency",
                    null
                );
            }
            String ticker = resolveTicker(position, isinByTicker);
            PreparedPosition resolved = new PreparedPosition(
                ticker,
                limit(position.label(), 100, ticker),
                position.quantity(),
                position.buyingPriceEur(),
                position.currentPrice(),
                currency,
                position.currentValueEur(),
                position.pnlEur()
            );
            positions.merge(ticker, resolved, this::mergePositions);
        }
        // The ISIN is the identifier the composition and fund-facts lookups actually resolve;
        // without this it dies here, converted into a ticker and forgotten.
        identityService.record(isinByTicker);
        return positions.values().stream()
            .filter(position -> position.quantity().signum() != 0)
            .toList();
    }

    /**
     * BoursoBank's trading board exposes its own instrument symbol, not an ISIN,
     * so the sidecar resolves the ISIN separately and may legitimately fail. A
     * position without one keeps the symbol as its ticker: it will not be priced
     * by Yahoo, and {@code AccountService.PROVIDER_VALUED} then falls back to
     * BoursoBank's own valuation instead of reading the line as zero.
     */
    private String resolveTicker(BoursoPort.Position position, Map<String, String> isinByTicker) {
        String ticker = clean(position.symbol());
        String isin = normalizeIsin(position.isin());
        if (isin != null) {
            OpenFigiIsinConverter.TickerResult resolved = isinConverter.resolve(isin);
            if (resolved != null && resolved.ticker() != null && !resolved.ticker().isBlank()) {
                ticker = resolved.ticker().trim();
            }
        }
        if ((ticker == null || ticker.length() > MAX_TICKER_LENGTH)
            && isin != null && isin.length() <= MAX_TICKER_LENGTH) {
            ticker = isin;
        }
        if (ticker == null || ticker.length() > MAX_TICKER_LENGTH) {
            throw error(BoursoErrorCode.INVALID_DATA, "BoursoBank returned an invalid instrument identifier", null);
        }
        if (isin != null) {
            isinByTicker.put(ticker, isin);
        }
        return ticker;
    }

    private String normalizeIsin(String raw) {
        String isin = clean(raw);
        if (isin == null) {
            return null;
        }
        isin = isin.toUpperCase(Locale.ROOT);
        if (!isin.matches("[A-Z]{2}[A-Z0-9]{10}")) {
            throw error(BoursoErrorCode.INVALID_DATA, "BoursoBank returned an invalid ISIN", null);
        }
        return isin;
    }

    /**
     * Merges two lines that resolved to the same ticker.
     *
     * <p>Not {@code HoldingDedup.vwapMerge}: that helper carries no provider
     * valuation, and dropping {@code providerValueEur}/{@code providerPnlEur}
     * here is what makes an unpriceable holding read as 0 EUR downstream.
     */
    private PreparedPosition mergePositions(PreparedPosition left, PreparedPosition right) {
        if (!Objects.equals(left.quoteCurrency(), right.quoteCurrency())) {
            throw error(BoursoErrorCode.INVALID_DATA, "BoursoBank returned conflicting quote currencies", null);
        }
        BigDecimal quantity = left.quantity().add(right.quantity());
        return new PreparedPosition(
            left.ticker(),
            right.name() != null ? right.name() : left.name(),
            quantity,
            weightedAverage(left.averageBuyInEur(), left.quantity(), right.averageBuyInEur(), right.quantity(), quantity),
            weightedAverage(left.currentPrice(), left.quantity(), right.currentPrice(), right.quantity(), quantity),
            left.quoteCurrency(),
            left.currentValueEur().add(right.currentValueEur()),
            sumComplete(left.pnlEur(), right.pnlEur())
        );
    }

    private BigDecimal weightedAverage(
        BigDecimal left,
        BigDecimal leftQuantity,
        BigDecimal right,
        BigDecimal rightQuantity,
        BigDecimal totalQuantity
    ) {
        if (left == null || right == null || totalQuantity.signum() == 0) {
            return null;
        }
        return left.multiply(leftQuantity)
            .add(right.multiply(rightQuantity))
            .divide(totalQuantity, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal sumComplete(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : left.add(right);
    }

    /**
     * Cost basis of a securities account: cash plus every line's own basis.
     * A single line whose basis cannot be established falls back to the account
     * total, because a partial basis paired with a full valuation reports a gain
     * the size of the missing positions.
     */
    private BigDecimal investedAmount(
        BoursoPort.AccountData account,
        BigDecimal cashBalance,
        List<PreparedPosition> positions
    ) {
        BigDecimal invested = cashBalance;
        for (PreparedPosition position : positions) {
            BigDecimal costBasis = null;
            if (position.averageBuyInEur() != null) {
                costBasis = position.averageBuyInEur().multiply(position.quantity());
            } else if (position.pnlEur() != null) {
                costBasis = position.currentValueEur().subtract(position.pnlEur());
            }
            if (costBasis == null) {
                return account.balanceEur();
            }
            invested = invested.add(costBasis);
        }
        return invested;
    }

    private boolean commitAccounts(SyncJob job, List<PreparedAccount> prepared) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<BoursoSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("BoursoBank sync session disappeared before commit (member={})", job.memberId());
                return false;
            }
            BoursoSession session = current.get();
            if (!session.isActive()) {
                log.warn("BoursoBank sync session became inactive before commit (member={})", job.memberId());
                return false;
            }
            if (session.getSyncStatus() != BoursoSyncStatus.RUNNING) {
                log.warn(
                    "BoursoBank sync cannot commit from state {} (member={})",
                    session.getSyncStatus(),
                    job.memberId()
                );
                return false;
            }

            FamilyMember member = memberRepository.findById(job.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            Instant syncedAt = Instant.now();
            for (PreparedAccount data : prepared) {
                upsertAccount(data, member, job.memberId(), syncedAt);
            }

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
            log.info("BoursoBank skipped a soft-deleted account (member={})", memberId);
            return;
        }

        Account account = existing.orElseGet(() -> Account.builder()
            .member(member)
            .externalAccountId(data.externalId())
            .provider(PROVIDER)
            .currency("EUR")
            .isManual(false)
            .color(colorFor(data.type()))
            .build());
        account.setName(data.name());
        account.setType(data.type());
        account.setProvider(PROVIDER);
        account.setCurrency("EUR");
        account.setManual(false);
        account.setCurrentBalance(data.balanceEur());
        account.setCashBalance(data.cashBalance());
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
                .currentPrice(position.currentPrice())
                .quoteCurrency(position.quoteCurrency())
                .providerValueEur(position.currentValueEur())
                .providerPnlEur(position.pnlEur())
                .lastSyncedAt(syncedAt)
                .build())
            .toList();
        holdingRepository.saveAll(holdings);
        holdingRepository.flush();

        if (data.investedAmountEur() != null) {
            accountService.upsertSnapshot(
                savedAccount,
                data.balanceEur(),
                data.investedAmountEur(),
                LocalDate.now()
            );
        } else {
            // A cash account has no cost basis of its own; let AccountService
            // derive it exactly as it does for every other balance-only connector.
            accountService.upsertSnapshot(savedAccount, data.balanceEur(), LocalDate.now());
        }
    }

    private static String colorFor(AccountType type) {
        return switch (type) {
            case PEA -> "#10b981";
            case COMPTE_TITRES -> "#3b82f6";
            case SAVINGS, LEP, LIVRET_A, LDDS, LIVRET_JEUNE, PEL, CEL -> "#f59e0b";
            default -> "#ec4899";
        };
    }

    private void markFailed(SyncJob job, BoursoErrorCode code) {
        try {
            txTemplate.executeWithoutResult(status -> {
                Optional<BoursoSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                    job.sessionId(),
                    job.memberId()
                );
                if (current.isEmpty()) {
                    log.info("BoursoBank sync session disappeared before failure was recorded (member={})", job.memberId());
                    return;
                }
                BoursoSession session = current.get();
                if (!session.isSyncInFlight()) {
                    log.warn(
                        "BoursoBank sync failure ignored from state {} (member={}; code={})",
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
            log.error(
                "Could not persist BoursoBank sync failure (member={}; code={})",
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
            List.of(BoursoSyncStatus.QUEUED, BoursoSyncStatus.RUNNING),
            BoursoSyncStatus.FAILED,
            Instant.now(),
            BoursoErrorCode.INTERNAL_ERROR
        );
        if (recovered > 0) {
            log.warn("Recovered {} interrupted BoursoBank sync job(s)", recovered);
        }
    }

    @Transactional(readOnly = true)
    public SessionStatusResponse getStatus(Long memberId) {
        return sessionRepository.findByMemberId(memberId)
            .map(this::toStatus)
            .orElseGet(SessionStatusResponse::inactive);
    }

    public void clearSession(Long memberId) {
        txTemplate.executeWithoutResult(status ->
            sessionRepository.findByMemberIdForUpdate(memberId).ifPresent(sessionRepository::delete)
        );
    }

    public void resyncIfSessionActive(Long memberId) {
        try {
            SessionStatusResponse status = getStatus(memberId);
            if (!status.isActive()) {
                return;
            }
            queueSync(memberId);
        } catch (ResourceNotFoundException ex) {
            log.debug("Member disappeared before scheduled BoursoBank sync (member={})", memberId);
        } catch (DataAccessException ex) {
            log.error("Database error during scheduled BoursoBank sync (member={})", memberId, ex);
        } catch (SyncException ex) {
            log.warn(
                "Could not queue scheduled BoursoBank sync (member={}; code={})",
                memberId,
                codeOf(ex),
                ex
            );
        } catch (RuntimeException ex) {
            log.error("Unexpected scheduled BoursoBank sync failure (member={})", memberId, ex);
        }
    }

    private SessionStatusResponse toStatus(BoursoSession session) {
        return new SessionStatusResponse(
            session.isActive(),
            session.getSyncStatus(),
            session.getLastSyncStartedAt(),
            session.getLastSyncCompletedAt(),
            session.getLastSyncError()
        );
    }

    private BoursoErrorCode codeOf(SyncException exception) {
        if (exception.getCode() == null) {
            return BoursoErrorCode.UPSTREAM_UNAVAILABLE;
        }
        try {
            return BoursoErrorCode.valueOf(exception.getCode());
        } catch (IllegalArgumentException ignored) {
            return BoursoErrorCode.UPSTREAM_UNAVAILABLE;
        }
    }

    private SyncException error(BoursoErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    private String stableExternalId(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            throw error(BoursoErrorCode.INVALID_DATA, "BoursoBank returned an invalid account identifier", null);
        }
        String externalId = cleaned.startsWith(EXTERNAL_ID_PREFIX) ? cleaned : EXTERNAL_ID_PREFIX + cleaned;
        if (externalId.length() > 100) {
            throw error(BoursoErrorCode.INVALID_DATA, "BoursoBank returned an invalid account identifier", null);
        }
        return externalId;
    }

    private String normalizeCurrency(String raw) {
        String currency = clean(raw);
        if (currency == null) {
            return null;
        }
        currency = currency.toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
            throw error(BoursoErrorCode.INVALID_DATA, "BoursoBank returned an invalid quote currency", null);
        }
        return currency;
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String limit(String value, int maxLength, String fallback) {
        String cleaned = clean(value);
        if (cleaned == null) {
            cleaned = fallback;
        }
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    private boolean moneyClose(BigDecimal actual, BigDecimal expected) {
        BigDecimal tolerance = ABSOLUTE_RECONCILIATION_TOLERANCE.max(
            expected.abs().multiply(RELATIVE_RECONCILIATION_TOLERANCE)
        );
        return actual.subtract(expected).abs().compareTo(tolerance) <= 0;
    }

    private <T> T requireTransactionResult(T value) {
        return Objects.requireNonNull(value, "Transaction callback returned no result");
    }

    public record AuthInitResponse(String processId, boolean mfaRequired, String mfaType) {}

    public record SessionStatusResponse(
        boolean isActive,
        BoursoSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        BoursoErrorCode lastSyncError
    ) {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, BoursoSyncStatus.IDLE, null, null, null);
        }
    }

    private record QueueDecision(SyncJob job, SessionStatusResponse status) {}
    private record SyncJob(Long sessionId, Long memberId, String plainState) {}
    private record PreparedAccount(
        String externalId,
        String name,
        AccountType type,
        BigDecimal balanceEur,
        BigDecimal cashBalance,
        BigDecimal investedAmountEur,
        List<PreparedPosition> positions
    ) {}
    private record PreparedPosition(
        String ticker,
        String name,
        BigDecimal quantity,
        BigDecimal averageBuyInEur,
        BigDecimal currentPrice,
        String quoteCurrency,
        BigDecimal currentValueEur,
        BigDecimal pnlEur
    ) {}
}
