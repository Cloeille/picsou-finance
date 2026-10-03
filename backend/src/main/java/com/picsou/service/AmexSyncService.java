package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.AmexSession;
import com.picsou.model.AmexSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.model.Transaction;
import com.picsou.port.AmexErrorCode;
import com.picsou.port.AmexPort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.AmexSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Imports the American Express credit card: balance, and (when the sidecar returns them)
 * transactions, rewards points and direct-debit info.
 *
 * <p>Shaped like {@link BoursoSyncService}/{@link FortuneoSyncService} (queue/execute/commit,
 * short transactions around a long-running sidecar call) but deliberately thinner: a credit
 * card has no positions, no investment PnL and no portfolio reconciliation to run.
 */
@Service
public class AmexSyncService {
    private static final Logger log = LoggerFactory.getLogger(AmexSyncService.class);
    static final String PROVIDER = "American Express";
    private static final String EXTERNAL_ID_PREFIX = "amex_";
    /** The sidecar returns no stable per-transaction id, so every sync replaces this trailing
     * window rather than reconciling by id -- mirrors FortuneoSyncService's no-id fallback. */
    private static final int TRANSACTION_WINDOW_DAYS = 90;

    private final AmexPort port;
    private final AmexSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final FamilyMemberRepository memberRepository;
    private final TransactionRepository transactionRepository;
    private final AccountService accountService;
    private final FortuneoTransactionWriter transactionWriter;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final Executor syncExecutor;

    public AmexSyncService(
        AmexPort port,
        AmexSessionRepository sessionRepository,
        AccountRepository accountRepository,
        FamilyMemberRepository memberRepository,
        TransactionRepository transactionRepository,
        AccountService accountService,
        FortuneoTransactionWriter transactionWriter,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("amexSyncExecutor") Executor syncExecutor
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.memberRepository = memberRepository;
        this.transactionRepository = transactionRepository;
        this.accountService = accountService;
        this.transactionWriter = transactionWriter;
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.syncExecutor = syncExecutor;
    }

    public AuthInitResponse initiateAuth(String login, String password, String method, Long memberId) {
        AmexPort.InitiateResult result = port.initiateAuth(login, password, method);
        if (!result.mfaRequired()) {
            if (result.sessionState() == null || result.sessionState().isBlank()) {
                throw error(AmexErrorCode.INVALID_DATA, "American Express did not return a session", null);
            }
            storeSessionAndQueue(result.sessionState(), memberId);
        }
        return new AuthInitResponse(result.processId(), result.mfaRequired(), result.mfaType());
    }

    public SessionStatusResponse completeAuth(String processId, String otp, Long memberId) {
        String plainState = port.completeAuth(processId, otp);
        if (plainState == null || plainState.isBlank()) {
            throw error(AmexErrorCode.INVALID_DATA, "American Express did not return a session", null);
        }
        return storeSessionAndQueue(plainState, memberId);
    }

    public SessionStatusResponse queueSync(Long memberId) {
        return queue(memberId, false);
    }

    public SessionStatusResponse queueHistoryRecovery(Long memberId) {
        return queue(memberId, true);
    }

    private SessionStatusResponse queue(Long memberId, boolean history) {
        QueueDecision decision = requireTransactionResult(txTemplate.execute(status -> {
            AmexSession session = sessionRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> error(
                    AmexErrorCode.SESSION_EXPIRED,
                    "No active American Express session. Please reconnect.",
                    null
                ));
            if (!session.isActive()) {
                throw error(
                    AmexErrorCode.SESSION_EXPIRED,
                    "The American Express session expired. Please reconnect.",
                    null
                );
            }
            if (session.getSyncStatus() == AmexSyncStatus.QUEUED
                || session.getSyncStatus() == AmexSyncStatus.RUNNING) {
                return new QueueDecision(null, toStatus(session));
            }

            String plainState = encryption.decrypt(session.getSessionState());
            session.markQueued();
            sessionRepository.save(session);
            return new QueueDecision(
                new SyncJob(session.getId(), memberId, plainState, history),
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

            AmexSession newSession = AmexSession.create(
                member,
                encryption.encrypt(plainState),
                Instant.now()
            );
            newSession.markQueued();
            AmexSession stored = sessionRepository.saveAndFlush(newSession);
            // Initial connect imports the full provider history (same provider-max
            // request the explicit recovery action runs) -- the connect flow is already
            // the heavy operation (browser login + OTP), and every other sidecar imports
            // deeply at connect. Routine syncs (manual button, daily scheduler) keep the
            // cheap 90-day window below.
            return new SyncJob(stored.getId(), memberId, plainState, true);
        }));

        submit(job);
        return getStatus(memberId);
    }

    private void submit(SyncJob job) {
        try {
            syncExecutor.execute(() -> executeJob(job));
        } catch (RuntimeException ex) {
            markFailed(job, AmexErrorCode.INTERNAL_ERROR);
            throw error(
                AmexErrorCode.INTERNAL_ERROR,
                "Could not schedule the American Express synchronization",
                ex
            );
        }
    }

    private void executeJob(SyncJob job) {
        if (!markRunning(job)) {
            return;
        }
        try {
        // Preserve caller ownership: a history recovery is explicit and uses the saved session.
        var fetched = job.history()
            ? port.fetchTransactionHistory(job.plainState())
            : port.fetchAccounts(job.plainState());
        List<PreparedAccount> prepared = prepareAccounts(fetched);
        if (commitAccounts(job, prepared)) {
                log.info("American Express sync completed (member={}; accounts={})", job.memberId(), prepared.size());
            } else {
                log.info("Discarded stale American Express sync result (member={})", job.memberId());
            }
        } catch (SyncException ex) {
            AmexErrorCode code = codeOf(ex);
            markFailed(job, code);
            log.warn("American Express sync failed (member={}; code={})", job.memberId(), code);
        } catch (Exception ex) {
            markFailed(job, AmexErrorCode.INTERNAL_ERROR);
            log.error("American Express sync failed unexpectedly (member={})", job.memberId(), ex);
        }
    }

    private boolean markRunning(SyncJob job) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<AmexSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("American Express sync session disappeared before execution (member={})", job.memberId());
                return false;
            }
            AmexSession session = current.get();
            if (!session.isActive() || session.getSyncStatus() != AmexSyncStatus.QUEUED) {
                log.warn(
                    "American Express sync cannot start from state {} (member={}; active={})",
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

    private List<PreparedAccount> prepareAccounts(List<AmexPort.AccountData> fetched) {
        if (fetched == null || fetched.isEmpty()) {
            throw error(AmexErrorCode.INVALID_DATA, "American Express returned no account", null);
        }

        Set<String> externalIds = new HashSet<>();
        List<PreparedAccount> prepared = new ArrayList<>();
        for (AmexPort.AccountData account : fetched) {
            if (account == null || !account.snapshotComplete()) {
                throw error(AmexErrorCode.INVALID_DATA, "American Express returned an incomplete snapshot", null);
            }
            String externalId = stableExternalId(account.externalId());
            if (!externalIds.add(externalId)) {
                throw error(AmexErrorCode.INVALID_DATA, "American Express returned duplicate accounts", null);
            }
            if (account.balanceEur() == null) {
                throw error(AmexErrorCode.INVALID_DATA, "American Express returned an incomplete account balance", null);
            }

            prepared.add(new PreparedAccount(
                externalId,
                limit(account.name(), 100, "American Express"),
                account.balanceEur(),
                account.paymentDueAmount(),
                parseOptionalDate(account.dueDate()),
                account.rewardPoints(),
                prepareTransactions(account.transactions())
            ));
        }
        return List.copyOf(prepared);
    }

    /**
     * Validates the sidecar's raw transaction list and parses each date, rejecting the whole
     * sync on a malformed row rather than silently dropping money movements.
     */
    private LocalDate parseOptionalDate(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value.substring(0, 10)); }
        catch (DateTimeException ex) { return null; }
    }

    private List<PreparedTransaction> prepareTransactions(List<AmexPort.Transaction> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<PreparedTransaction> prepared = new ArrayList<>(raw.size());
        for (AmexPort.Transaction tx : raw) {
            if (tx == null || tx.date() == null || tx.amountEur() == null) {
                throw error(AmexErrorCode.INVALID_DATA, "American Express returned an incomplete transaction", null);
            }
            LocalDate date;
            try {
                date = LocalDate.parse(tx.date());
            } catch (DateTimeException ex) {
                throw error(AmexErrorCode.INVALID_DATA, "American Express returned an invalid transaction date", ex);
            }
            String externalId = tx.externalId() != null ? tx.externalId()
                : identity(date, tx.label(), tx.amountEur());
            prepared.add(new PreparedTransaction(
                externalId,
                date,
                limit(tx.label(), 255, "American Express transaction"),
                tx.amountEur()
            ));
        }
        return List.copyOf(prepared);
    }

    private String identity(LocalDate date, String description, BigDecimal amount) {
        return "amex_tx_" + Integer.toUnsignedString(Objects.hash(date, description, amount.stripTrailingZeros()), 36);
    }

    private boolean commitAccounts(SyncJob job, List<PreparedAccount> prepared) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<AmexSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("American Express sync session disappeared before commit (member={})", job.memberId());
                return false;
            }
            AmexSession session = current.get();
            if (!session.isActive()) {
                log.warn("American Express sync session became inactive before commit (member={})", job.memberId());
                return false;
            }
            if (session.getSyncStatus() != AmexSyncStatus.RUNNING) {
                log.warn(
                    "American Express sync cannot commit from state {} (member={})",
                    session.getSyncStatus(),
                    job.memberId()
                );
                return false;
            }

            FamilyMember member = memberRepository.findById(job.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            Instant syncedAt = Instant.now();
            for (PreparedAccount data : prepared) {
                upsertAccount(data, member, job.memberId(), syncedAt, job.history());
            }

            session.markSuccessful(syncedAt);
            sessionRepository.save(session);
            return true;
        }));
    }

    private void upsertAccount(PreparedAccount data, FamilyMember member, Long memberId, Instant syncedAt, boolean history) {
        Optional<Account> existing = accountRepository
            .findByExternalAccountIdAndMemberId(data.externalId(), memberId);
        if (existing.isEmpty()
            && accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), memberId)) {
            log.info("American Express skipped a soft-deleted account (member={})", memberId);
            return;
        }

        Account account = existing.orElseGet(() -> Account.builder()
            .member(member)
            .externalAccountId(data.externalId())
            .provider(PROVIDER)
            .type(AccountType.CREDIT_CARD)
            .currency("EUR")
            .isManual(false)
            .color("#2563eb")
            .build());
        account.setName(data.name());
        account.setType(AccountType.CREDIT_CARD);
        account.setProvider(PROVIDER);
        account.setCurrency("EUR");
        account.setManual(false);
        account.setCurrentBalance(data.balanceEur());
        // ponytail: AMEX enrichment is sparse; null values preserve the latest known values.
        if (data.paymentDueAmount() != null) account.setPaymentDueAmount(data.paymentDueAmount());
        if (data.paymentDueDate() != null) account.setPaymentDueDate(data.paymentDueDate());
        if (data.rewardPoints() != null) account.setRewardPoints(data.rewardPoints());
        account.setLastSyncedAt(syncedAt);
        Account savedAccount = accountRepository.save(account);

        accountService.upsertSnapshot(savedAccount, data.balanceEur(), LocalDate.now());

        syncTransactions(savedAccount, data.transactions(), history);

    }

    /**
     * Regular sync refreshes the 90-day window. Recovery instead imports the full bounded history
     * returned by AMEX and merges without deleting existing/manual rows.
     */
    private void syncTransactions(Account account, List<PreparedTransaction> transactions, boolean history) {
        if (transactions.isEmpty()) return;
        if (history) {
            List<Transaction> existing = transactionRepository.findByAccountIdAndIsManualFalse(account.getId());
            Set<String> known = new HashSet<>();
            for (Transaction tx : existing) {
                known.add(tx.getExternalId() != null ? tx.getExternalId() : identity(tx.getDate(), tx.getDescription(), tx.getAmount()));
            }
            List<Transaction> missing = transactions.stream()
                .filter(tx -> known.add(tx.externalId()))
                .map(tx -> Transaction.builder().account(account).externalId(tx.externalId())
                    .date(tx.date()).description(tx.label()).amount(tx.amountEur()).nativeCurrency("EUR").build()).toList();
            transactionRepository.saveAllAndFlush(missing);
            return;
        }
        LocalDate cutoff = LocalDate.now().minusDays(TRANSACTION_WINDOW_DAYS);
        List<Transaction> toInsert = transactions.stream()
            .filter(tx -> !tx.date().isBefore(cutoff))
            .collect(java.util.stream.Collectors.toMap(
                PreparedTransaction::externalId,
                tx -> Transaction.builder()
                    .account(account)
                    .externalId(tx.externalId())
                    .date(tx.date())
                    .description(tx.label())
                    .amount(tx.amountEur())
                    .nativeCurrency("EUR")
                    .build(),
                (first, duplicate) -> first,
                java.util.LinkedHashMap::new
            ))
            .values().stream().toList();
        transactionWriter.replaceRecentTransactions(account.getId(), cutoff, toInsert);
    }

    private void markFailed(SyncJob job, AmexErrorCode code) {
        try {
            txTemplate.executeWithoutResult(status -> {
                Optional<AmexSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                    job.sessionId(),
                    job.memberId()
                );
                if (current.isEmpty()) {
                    log.info("American Express sync session disappeared before failure was recorded (member={})", job.memberId());
                    return;
                }
                AmexSession session = current.get();
                if (!session.isSyncInFlight()) {
                    log.warn(
                        "American Express sync failure ignored from state {} (member={}; code={})",
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
                "Could not persist American Express sync failure (member={}; code={})",
                job.memberId(),
                code,
                ex
            );
        }
    }

    /**
     * Queued jobs live in a process-local executor and cannot survive a backend restart. Turn
     * persisted in-flight states into a retryable failure instead of leaving the UI polling
     * QUEUED/RUNNING forever.
     */
    @Transactional
    public void recoverInterruptedSyncs() {
        int recovered = sessionRepository.markInterruptedSyncsFailed(
            List.of(AmexSyncStatus.QUEUED, AmexSyncStatus.RUNNING),
            AmexSyncStatus.FAILED,
            Instant.now(),
            AmexErrorCode.INTERNAL_ERROR
        );
        if (recovered > 0) {
            log.warn("Recovered {} interrupted American Express sync job(s)", recovered);
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
            log.debug("Member disappeared before scheduled American Express sync (member={})", memberId);
        } catch (DataAccessException ex) {
            log.error("Database error during scheduled American Express sync (member={})", memberId, ex);
        } catch (SyncException ex) {
            log.warn(
                "Could not queue scheduled American Express sync (member={}; code={})",
                memberId,
                codeOf(ex),
                ex
            );
        } catch (RuntimeException ex) {
            log.error("Unexpected scheduled American Express sync failure (member={})", memberId, ex);
        }
    }

    private SessionStatusResponse toStatus(AmexSession session) {
        return new SessionStatusResponse(
            session.isActive(),
            session.getSyncStatus(),
            session.getLastSyncStartedAt(),
            session.getLastSyncCompletedAt(),
            session.getLastSyncError()
        );
    }

    private AmexErrorCode codeOf(SyncException exception) {
        if (exception.getCode() == null) {
            return AmexErrorCode.UPSTREAM_UNAVAILABLE;
        }
        try {
            return AmexErrorCode.valueOf(exception.getCode());
        } catch (IllegalArgumentException ignored) {
            return AmexErrorCode.UPSTREAM_UNAVAILABLE;
        }
    }

    private SyncException error(AmexErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    private String stableExternalId(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            throw error(AmexErrorCode.INVALID_DATA, "American Express returned an invalid account identifier", null);
        }
        String externalId = cleaned.startsWith(EXTERNAL_ID_PREFIX) ? cleaned : EXTERNAL_ID_PREFIX + cleaned;
        if (externalId.length() > 100) {
            throw error(AmexErrorCode.INVALID_DATA, "American Express returned an invalid account identifier", null);
        }
        return externalId;
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

    /**
     * Trims an optional provider string, mapping blank or oversized values to {@code null}.
     * The sidecar never supplies a transaction id today, but this keeps the field ready if it
     * does; distinct from {@link #limit}, whose {@code null} always means "use the fallback".
     */
    private String normalizeExternalId(String externalId) {
        if (externalId == null) {
            return null;
        }
        String trimmed = externalId.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            return null;
        }
        return trimmed;
    }

    private <T> T requireTransactionResult(T value) {
        return Objects.requireNonNull(value, "Transaction callback returned no result");
    }

    public record AuthInitResponse(String processId, boolean mfaRequired, String mfaType) {}

    public record SessionStatusResponse(
        boolean isActive,
        AmexSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        AmexErrorCode lastSyncError
    ) {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, AmexSyncStatus.IDLE, null, null, null);
        }
    }

    private record QueueDecision(SyncJob job, SessionStatusResponse status) {}
    private record SyncJob(Long sessionId, Long memberId, String plainState, boolean history) {}
    private record PreparedAccount(
        String externalId,
        String name,
        BigDecimal balanceEur,
        BigDecimal paymentDueAmount,
        LocalDate paymentDueDate,
        Long rewardPoints,
        List<PreparedTransaction> transactions
    ) {}
    private record PreparedTransaction(String externalId, LocalDate date, String label, BigDecimal amountEur) {}
}
