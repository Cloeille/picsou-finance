package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.BourseDirectSession;
import com.picsou.model.BourseDirectSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.port.BourseDirectErrorCode;
import com.picsou.port.BourseDirectPort;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BourseDirectSessionRepository;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

@Service
public class BourseDirectSyncService {
    private static final Logger log = LoggerFactory.getLogger(BourseDirectSyncService.class);
    static final String PROVIDER = "Bourse Direct";
    private static final BigDecimal ABSOLUTE_RECONCILIATION_TOLERANCE = new BigDecimal("0.05");
    private static final BigDecimal RELATIVE_RECONCILIATION_TOLERANCE = new BigDecimal("0.001");

    private final BourseDirectPort port;
    private final BourseDirectSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final FamilyMemberRepository memberRepository;
    private final AccountService accountService;
    private final OpenFigiIsinConverter isinConverter;
    private final SecurityIdentityService identityService;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final Executor syncExecutor;

    public BourseDirectSyncService(
        BourseDirectPort port,
        BourseDirectSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        FamilyMemberRepository memberRepository,
        AccountService accountService,
        OpenFigiIsinConverter isinConverter,
        SecurityIdentityService identityService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("bourseDirectSyncExecutor") Executor syncExecutor
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

    public AuthInitResponse initiateAuth(String login, String password, Long memberId) {
        BourseDirectPort.InitiateResult result = port.initiateAuth(login, password);
        if (!result.mfaRequired()) {
            if (result.sessionState() == null || result.sessionState().isBlank()) {
                throw error(BourseDirectErrorCode.INVALID_DATA, "Bourse Direct did not return a session", null);
            }
            storeSessionAndQueue(result.sessionState(), memberId);
        }
        return new AuthInitResponse(result.processId(), result.mfaRequired(), result.mfaType());
    }

    public SessionStatusResponse completeAuth(String processId, String code, Long memberId) {
        String plainState = port.completeAuth(processId, code);
        if (plainState == null || plainState.isBlank()) {
            throw error(BourseDirectErrorCode.INVALID_DATA, "Bourse Direct did not return a session", null);
        }
        return storeSessionAndQueue(plainState, memberId);
    }

    public SessionStatusResponse queueSync(Long memberId) {
        QueueDecision decision = requireTransactionResult(txTemplate.execute(status -> {
            BourseDirectSession session = sessionRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> error(
                    BourseDirectErrorCode.SESSION_EXPIRED,
                    "No active Bourse Direct session. Please reconnect.",
                    null
                ));
            if (!session.isActive()) {
                throw error(
                    BourseDirectErrorCode.SESSION_EXPIRED,
                    "The Bourse Direct session expired. Please reconnect.",
                    null
                );
            }
            if (session.getSyncStatus() == BourseDirectSyncStatus.QUEUED
                || session.getSyncStatus() == BourseDirectSyncStatus.RUNNING) {
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

            BourseDirectSession newSession = BourseDirectSession.create(
                member,
                encryption.encrypt(plainState),
                Instant.now()
            );
            newSession.markQueued();
            BourseDirectSession stored = sessionRepository.saveAndFlush(newSession);
            return new SyncJob(stored.getId(), memberId, plainState);
        }));

        submit(job);
        return getStatus(memberId);
    }

    private void submit(SyncJob job) {
        try {
            syncExecutor.execute(() -> executeJob(job));
        } catch (RuntimeException ex) {
            markFailed(job, BourseDirectErrorCode.INTERNAL_ERROR);
            throw error(
                BourseDirectErrorCode.INTERNAL_ERROR,
                "Could not schedule the Bourse Direct synchronization",
                ex
            );
        }
    }

    private void executeJob(SyncJob job) {
        if (!markRunning(job)) {
            return;
        }
        try {
            List<BourseDirectPort.AccountData> fetched = port.fetchAccounts(job.plainState());
            List<PreparedAccount> prepared = prepareAccounts(fetched);
            if (commitPortfolio(job, prepared)) {
                log.info("Bourse Direct sync completed (member={}; accounts={})", job.memberId(), prepared.size());
            } else {
                log.info("Discarded stale Bourse Direct sync result (member={})", job.memberId());
            }
        } catch (SyncException ex) {
            BourseDirectErrorCode code = codeOf(ex);
            markFailed(job, code);
            log.warn("Bourse Direct sync failed (member={}; code={})", job.memberId(), code);
        } catch (Exception ex) {
            markFailed(job, BourseDirectErrorCode.INTERNAL_ERROR);
            log.error("Bourse Direct sync failed unexpectedly (member={})", job.memberId(), ex);
        }
    }

    private boolean markRunning(SyncJob job) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<BourseDirectSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("Bourse Direct sync session disappeared before execution (member={})", job.memberId());
                return false;
            }
            BourseDirectSession session = current.get();
            if (!session.isActive() || session.getSyncStatus() != BourseDirectSyncStatus.QUEUED) {
                log.warn(
                    "Bourse Direct sync cannot start from state {} (member={}; active={})",
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

    private List<PreparedAccount> prepareAccounts(List<BourseDirectPort.AccountData> fetched) {
        if (fetched == null || fetched.isEmpty()) {
            throw error(
                BourseDirectErrorCode.PORTFOLIO_INCOMPLETE,
                "Bourse Direct returned no complete portfolio accounts",
                null
            );
        }

        Set<String> externalIds = new HashSet<>();
        List<PreparedAccount> prepared = new ArrayList<>();
        for (BourseDirectPort.AccountData account : fetched) {
            if (account == null || !account.snapshotComplete()) {
                throw error(
                    BourseDirectErrorCode.PORTFOLIO_INCOMPLETE,
                    "Bourse Direct returned an incomplete portfolio",
                    null
                );
            }
            String externalId = stableExternalId(account.externalId());
            if (!externalIds.add(externalId)) {
                throw error(BourseDirectErrorCode.INVALID_DATA, "Bourse Direct returned duplicate accounts", null);
            }
            if (account.type() != AccountType.PEA && account.type() != AccountType.COMPTE_TITRES) {
                throw error(BourseDirectErrorCode.INVALID_DATA, "Bourse Direct returned an unsupported account type", null);
            }
            if (account.balanceEur() == null || account.cashBalance() == null || account.positions() == null) {
                throw error(BourseDirectErrorCode.INVALID_DATA, "Bourse Direct returned incomplete account values", null);
            }

            List<PreparedPosition> positions = preparePositions(account.positions());
            BigDecimal positionValue = positions.stream()
                .map(PreparedPosition::currentValueEur)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal expectedPositionValue = account.balanceEur().subtract(account.cashBalance());
            if (!moneyClose(positionValue, expectedPositionValue)) {
                throw error(
                    BourseDirectErrorCode.PORTFOLIO_INCOMPLETE,
                    "Bourse Direct returned an incomplete portfolio",
                    null
                );
            }

            BigDecimal investedAmount = investedAmount(account, positions);
            prepared.add(new PreparedAccount(
                externalId,
                limit(account.name(), 100, "Bourse Direct account"),
                account.type(),
                account.balanceEur(),
                account.cashBalance(),
                investedAmount,
                positions
            ));
        }
        return List.copyOf(prepared);
    }

    private List<PreparedPosition> preparePositions(List<BourseDirectPort.Position> rawPositions) {
        Map<String, PreparedPosition> positions = new LinkedHashMap<>();
        Map<String, String> isinByTicker = new LinkedHashMap<>();
        for (BourseDirectPort.Position position : rawPositions) {
            if (position == null || position.quantity() == null || position.currentValueEur() == null) {
                throw error(BourseDirectErrorCode.INVALID_DATA, "Bourse Direct returned an incomplete position", null);
            }
            if (position.quantity().signum() == 0) {
                continue;
            }
            String currency = normalizeCurrency(position.quoteCurrency());
            if (position.currentPrice() != null && currency == null) {
                throw error(
                    BourseDirectErrorCode.INVALID_DATA,
                    "Bourse Direct returned a quote without its currency",
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
        identityService.record(isinByTicker);
        return positions.values().stream()
            .filter(position -> position.quantity().signum() != 0)
            .toList();
    }

    private String resolveTicker(BourseDirectPort.Position position, Map<String, String> isinByTicker) {
        String ticker = clean(position.symbol());
        String isin = normalizeIsin(position.isin());
        if (isin != null) {
            OpenFigiIsinConverter.TickerResult resolved = isinConverter.resolve(isin);
            if (resolved != null && resolved.ticker() != null && !resolved.ticker().isBlank()) {
                ticker = resolved.ticker().trim();
            }
        }
        if (ticker == null || ticker.length() > 30) {
            if (isin != null && isin.length() <= 30) {
                ticker = isin;
            }
        }
        if (ticker == null || ticker.length() > 30) {
            throw error(BourseDirectErrorCode.INVALID_DATA, "Bourse Direct returned an invalid instrument identifier", null);
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
        isin = isin.toUpperCase(java.util.Locale.ROOT);
        if (!isin.matches("[A-Z]{2}[A-Z0-9]{10}")) {
            throw error(BourseDirectErrorCode.INVALID_DATA, "Bourse Direct returned an invalid ISIN", null);
        }
        return isin;
    }

    private PreparedPosition mergePositions(PreparedPosition left, PreparedPosition right) {
        if (!Objects.equals(left.quoteCurrency(), right.quoteCurrency())) {
            throw error(BourseDirectErrorCode.INVALID_DATA, "Bourse Direct returned conflicting quote currencies", null);
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

    private BigDecimal investedAmount(
        BourseDirectPort.AccountData account,
        List<PreparedPosition> positions
    ) {
        BigDecimal invested = account.cashBalance();
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

    private boolean commitPortfolio(SyncJob job, List<PreparedAccount> prepared) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<BourseDirectSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("Bourse Direct sync session disappeared before commit (member={})", job.memberId());
                return false;
            }
            BourseDirectSession session = current.get();
            if (!session.isActive()) {
                log.warn("Bourse Direct sync session became inactive before commit (member={})", job.memberId());
                return false;
            }
            if (session.getSyncStatus() != BourseDirectSyncStatus.RUNNING) {
                log.warn(
                    "Bourse Direct sync cannot commit from state {} (member={})",
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
            log.info("Bourse Direct skipped a soft-deleted account (member={})", memberId);
            return;
        }

        Account account = existing.orElseGet(() -> Account.builder()
            .member(member)
            .externalAccountId(data.externalId())
            .provider(PROVIDER)
            .currency("EUR")
            .isManual(false)
            .color(data.type() == AccountType.PEA ? "#10b981" : "#3b82f6")
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

        accountService.upsertSnapshot(
            savedAccount,
            data.balanceEur(),
            data.investedAmountEur(),
            LocalDate.now()
        );
    }

    private void markFailed(SyncJob job, BourseDirectErrorCode code) {
        try {
            txTemplate.executeWithoutResult(status -> {
                Optional<BourseDirectSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                    job.sessionId(),
                    job.memberId()
                );
                if (current.isEmpty()) {
                    log.info("Bourse Direct sync session disappeared before failure was recorded (member={})", job.memberId());
                    return;
                }
                BourseDirectSession session = current.get();
                if (!session.isSyncInFlight()) {
                    log.warn(
                        "Bourse Direct sync failure ignored from state {} (member={}; code={})",
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
                "Could not persist Bourse Direct sync failure (member={}; code={})",
                job.memberId(),
                code,
                ex
            );
        }
    }

    /**
     * Browser-backed jobs cannot survive a backend restart. Turn persisted
     * in-flight states into a retryable failure instead of leaving the UI
     * polling QUEUED/RUNNING forever.
     */
    @Transactional
    public void recoverInterruptedSyncs() {
        int recovered = sessionRepository.markInterruptedSyncsFailed(
            List.of(BourseDirectSyncStatus.QUEUED, BourseDirectSyncStatus.RUNNING),
            BourseDirectSyncStatus.FAILED,
            Instant.now(),
            BourseDirectErrorCode.INTERNAL_ERROR
        );
        if (recovered > 0) {
            log.warn("Recovered {} interrupted Bourse Direct sync job(s)", recovered);
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
            log.debug("Member disappeared before scheduled Bourse Direct sync (member={})", memberId);
        } catch (DataAccessException ex) {
            log.error("Database error during scheduled Bourse Direct sync (member={})", memberId, ex);
        } catch (SyncException ex) {
            log.warn(
                "Could not queue scheduled Bourse Direct sync (member={}; code={})",
                memberId,
                codeOf(ex),
                ex
            );
        } catch (RuntimeException ex) {
            log.error("Unexpected scheduled Bourse Direct sync failure (member={})", memberId, ex);
        }
    }

    private SessionStatusResponse toStatus(BourseDirectSession session) {
        return new SessionStatusResponse(
            session.isActive(),
            null,
            session.getSyncStatus(),
            session.getLastSyncStartedAt(),
            session.getLastSyncCompletedAt(),
            session.getLastSyncError()
        );
    }

    private BourseDirectErrorCode codeOf(SyncException exception) {
        if (exception.getCode() == null) {
            return BourseDirectErrorCode.UPSTREAM_UNAVAILABLE;
        }
        try {
            return BourseDirectErrorCode.valueOf(exception.getCode());
        } catch (IllegalArgumentException ignored) {
            return BourseDirectErrorCode.UPSTREAM_UNAVAILABLE;
        }
    }

    private SyncException error(BourseDirectErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    private String stableExternalId(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            throw error(BourseDirectErrorCode.INVALID_DATA, "Bourse Direct returned an invalid account identifier", null);
        }
        String externalId = cleaned.startsWith("bd_") ? cleaned : "bd_" + cleaned;
        if (externalId.length() > 100) {
            throw error(BourseDirectErrorCode.INVALID_DATA, "Bourse Direct returned an invalid account identifier", null);
        }
        return externalId;
    }

    private String normalizeCurrency(String raw) {
        String currency = clean(raw);
        if (currency == null) {
            return null;
        }
        currency = currency.toUpperCase(java.util.Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
            throw error(BourseDirectErrorCode.INVALID_DATA, "Bourse Direct returned an invalid quote currency", null);
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
        Instant expiresAt,
        BourseDirectSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        BourseDirectErrorCode lastSyncError
    ) {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, null, BourseDirectSyncStatus.IDLE, null, null, null);
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
