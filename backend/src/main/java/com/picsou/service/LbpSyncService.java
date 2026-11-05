package com.picsou.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.CardNature;
import com.picsou.model.FamilyMember;
import com.picsou.model.LbpSession;
import com.picsou.model.LbpSyncStatus;
import com.picsou.model.Transaction;
import com.picsou.port.LbpErrorCode;
import com.picsou.port.LbpPort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.LbpSessionRepository;
import com.picsou.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Imports La Banque Postale current accounts, regulated livrets and cards, with their
 * transactions.
 *
 * <p>Upstream I/O happens outside any transaction and one short transaction writes the whole
 * snapshot, so a failure part-way rolls every account back. Differences from the Enable
 * Banking path, all deliberate: the sync is <b>on demand only</b>, the account product comes
 * from the sidecar's explicit {@code kind} instead of name detection, no credentials are ever
 * stored (cookies only) so an expired session can only be renewed by the user, and an
 * unsupported product is counted but never created.
 */
@Service
public class LbpSyncService {
    private static final Logger log = LoggerFactory.getLogger(LbpSyncService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<UnsupportedProduct>> UNSUPPORTED_TYPE = new TypeReference<>() {};

    static final String PROVIDER = "La Banque Postale";
    private static final String EXTERNAL_ID_PREFIX = "lbp_";
    private static final int MAX_EXTERNAL_ID_LENGTH = 100;
    private static final int MAX_UNSUPPORTED = 100;
    /** The bank's pad is 10 images laid out in 5 columns; anything else is a changed page. */
    private static final int KEYPAD_KEY_COUNT = 10;
    private static final int KEYPAD_COLUMNS = 5;
    /** A key image is a small PNG. Bigger is not a key image. */
    private static final int KEYPAD_MAX_IMAGE_LENGTH = 16 * 1024;
    private static final String KEYPAD_IMAGE_PREFIX = "data:image/png;base64,";
    /** How long the user has to type the Certicode code once the keypad was submitted. */
    private static final int OTP_WINDOW_SECONDS = 180;
    private static final java.util.regex.Pattern BASE64 = java.util.regex.Pattern.compile("[A-Za-z0-9+/]+={0,2}");

    private final LbpPort port;
    private final LbpSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final FamilyMemberRepository memberRepository;
    private final AccountService accountService;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final Executor syncExecutor;
    private final Clock clock;
    /**
     * Logins waiting for the Certicode code. Holds the process id, the member and a deadline:
     * never the identifier, the password or the code. Process-local on purpose, a restart drops
     * the attempt and the user starts again.
     */
    private final Map<String, PendingLogin> pendingLogins = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public LbpSyncService(
        LbpPort port,
        LbpSessionRepository sessionRepository,
        AccountRepository accountRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository memberRepository,
        AccountService accountService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("lbpSyncExecutor") Executor syncExecutor
    ) {
        this(port, sessionRepository, accountRepository, transactionRepository, memberRepository, accountService,
            encryption, txTemplate, syncExecutor, Clock.systemUTC());
    }

    LbpSyncService(
        LbpPort port,
        LbpSessionRepository sessionRepository,
        AccountRepository accountRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository memberRepository,
        AccountService accountService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        Executor syncExecutor,
        Clock clock
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.memberRepository = memberRepository;
        this.accountService = accountService;
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.syncExecutor = syncExecutor;
        this.clock = clock;
    }

    /**
     * Starts a login. One call to the sidecar, no retry. No password ever reaches Picsou: the
     * sidecar answers with the bank's keypad, which the user clicks himself. A failure leaves any
     * session already stored (valid or not) exactly as it was.
     */
    public InitiateResponse initiateAuth(String customerId, Long memberId) {
        LbpPort.InitiateResult result = port.initiateAuth(customerId);
        if (result == null || result.processId() == null || result.processId().isBlank()
            || result.keypad() == null || result.expiresInSeconds() <= 0) {
            throw error(LbpErrorCode.UPSTREAM_FORMAT_CHANGED,
                "La Banque Postale did not return a usable sign-in challenge", null);
        }
        requireSafeKeypad(result.keypad());
        Instant now = clock.instant();
        // Swept on every start: a stale process id must never be reachable again.
        pendingLogins.values().removeIf(pending -> !pending.deadline().isAfter(now));
        pendingLogins.put(result.processId(),
            new PendingLogin(memberId, now.plusSeconds(result.expiresInSeconds()), null));
        return new InitiateResponse(result.processId(), result.keypad(), result.expiresInSeconds());
    }

    /**
     * Refuses a keypad that cannot be shown to a user as-is. Anything unexpected becomes
     * {@code KEYPAD_CHANGED} and the process is never registered, so nothing can be clicked.
     */
    private void requireSafeKeypad(LbpPort.Keypad keypad) {
        boolean safe = keypad != null
            && keypad.columns() == KEYPAD_COLUMNS
            && keypad.images() != null
            && keypad.images().size() == KEYPAD_KEY_COUNT;
        if (safe) {
            for (String image : keypad.images()) {
                if (!isDisplayableKeyImage(image)) {
                    safe = false;
                    break;
                }
            }
        }
        if (!safe) {
            throw error(LbpErrorCode.KEYPAD_CHANGED,
                "La Banque Postale changed its login page. Nothing was sent.", null);
        }
    }

    /**
     * PNG data URI only, over the size cap refused, and the payload must be plain base64: the
     * string is handed to the UI, so nothing else may ride along in it.
     */
    private static boolean isDisplayableKeyImage(String image) {
        if (image == null || !image.startsWith(KEYPAD_IMAGE_PREFIX)
            || image.length() > KEYPAD_MAX_IMAGE_LENGTH) {
            return false;
        }
        String payload = image.substring(KEYPAD_IMAGE_PREFIX.length());
        return !payload.isEmpty() && BASE64.matcher(payload).matches();
    }

    /**
     * Clicks the digits the user chose in the pop-up. Single use and no retry: the process is
     * taken out before the sidecar is called, so a failure is never replayed. The positions are
     * the password in disguise and are never logged.
     */
    public void submitKeypad(String processId, List<Integer> positions, Long memberId) {
        PendingLogin pending = takePending(processId, memberId);
        if (pending.otpExpiresAt() != null) {
            // The digits were already sent for this process: nothing more to click.
            throw error(LbpErrorCode.AUTH_ATTEMPT_EXPIRED,
                "This La Banque Postale sign-in attempt expired. Please start again.", null);
        }
        if (!pending.keypadExpiresAt().isAfter(clock.instant())) {
            // Nothing was clicked anywhere: the user just took too long.
            throw error(LbpErrorCode.KEYPAD_EXPIRED,
                "The La Banque Postale keypad expired. Start again.", null);
        }
        port.submitKeypad(processId, positions);
        pendingLogins.put(processId, new PendingLogin(memberId, null,
            clock.instant().plusSeconds(OTP_WINDOW_SECONDS)));
    }

    /**
     * Submits the Certicode code, then stores the encrypted session through
     * {@link #storeSession}. A process id is single use: it is taken out of the map before the
     * sidecar is called, so a failure cannot be replayed from here.
     */
    public SessionStatusResponse submitOtp(String processId, String code, Long memberId) {
        PendingLogin pending = takePending(processId, memberId);
        if (pending.otpExpiresAt() == null || !pending.otpExpiresAt().isAfter(clock.instant())) {
            throw error(LbpErrorCode.OTP_EXPIRED,
                "The one-time code was not entered in time. Start again.", null);
        }
        String plainState = port.submitOtp(processId, code);
        if (plainState == null || plainState.isBlank()) {
            throw error(LbpErrorCode.UPSTREAM_FORMAT_CHANGED,
                "La Banque Postale did not return a session", null);
        }
        return storeSession(plainState, memberId);
    }

    /**
     * Removes a pending login for its owner, or refuses without burning the owner's attempt.
     * Shared by the keypad and OTP steps so both are single use and member-scoped.
     */
    private PendingLogin takePending(String processId, Long memberId) {
        PendingLogin pending = pendingLogins.remove(processId);
        if (pending != null && !pending.memberId().equals(memberId)) {
            // Someone else probing a process id must not burn the owner's attempt.
            pendingLogins.putIfAbsent(processId, pending);
            throw error(LbpErrorCode.AUTH_ATTEMPT_EXPIRED,
                "This La Banque Postale sign-in attempt expired. Please start again.", null);
        }
        if (pending == null) {
            throw error(LbpErrorCode.AUTH_ATTEMPT_EXPIRED,
                "This La Banque Postale sign-in attempt expired. Please start again.", null);
        }
        return pending;
    }

    /**
     * Stores (encrypted) the session state produced by a login. It does <b>not</b> queue a sync:
     * the user triggers it.
     */
    public SessionStatusResponse storeSession(String plainState, Long memberId) {
        if (plainState == null || plainState.isBlank()) {
            throw error(LbpErrorCode.INVALID_SESSION_STATE,
                "No La Banque Postale session was provided", null);
        }
        requireTransactionResult(txTemplate.execute(status -> {
            FamilyMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            sessionRepository.findByMemberIdForUpdate(memberId).ifPresent(sessionRepository::delete);
            sessionRepository.flush();
            return sessionRepository.saveAndFlush(
                LbpSession.create(member, encryption.encrypt(plainState), clock.instant()));
        }));
        return getStatus(memberId);
    }

    public SessionStatusResponse queueSync(Long memberId) {
        QueueDecision decision = requireTransactionResult(txTemplate.execute(status -> {
            LbpSession session = sessionRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> error(
                    LbpErrorCode.SESSION_EXPIRED,
                    "No active La Banque Postale session. Please reconnect.",
                    null
                ));
            if (!session.isActive()) {
                throw error(
                    LbpErrorCode.SESSION_EXPIRED,
                    "The La Banque Postale session expired. Please reconnect.",
                    null
                );
            }
            if (session.isSyncInFlight()) {
                return new QueueDecision(null, toStatus(session), null);
            }

            String plainState;
            try {
                plainState = encryption.decrypt(session.getSessionState());
            } catch (RuntimeException ex) {
                // Unreadable (wrong key, corrupted): the session is dead, not a server fault.
                session.markQueued();
                session.markFailed(LbpErrorCode.INVALID_SESSION_STATE, clock.instant());
                sessionRepository.save(session);
                log.warn("Stored La Banque Postale session is unreadable (member={}; type={})",
                    memberId, ex.getClass().getSimpleName());
                return new QueueDecision(null, null, error(
                    LbpErrorCode.INVALID_SESSION_STATE,
                    "The stored La Banque Postale session is unusable. Please reconnect.",
                    null
                ));
            }
            session.markQueued();
            sessionRepository.save(session);
            return new QueueDecision(new SyncJob(session.getId(), memberId, plainState), toStatus(session), null);
        }));

        if (decision.failure() != null) {
            throw decision.failure();
        }
        if (decision.job() != null) {
            submit(decision.job());
            return getStatus(memberId);
        }
        return decision.status();
    }

    private void submit(SyncJob job) {
        try {
            syncExecutor.execute(() -> executeJob(job));
        } catch (RuntimeException ex) {
            markFailed(job, LbpErrorCode.INTERNAL_ERROR);
            throw error(
                LbpErrorCode.INTERNAL_ERROR,
                "Could not schedule the La Banque Postale synchronization",
                ex
            );
        }
    }

    private void executeJob(SyncJob job) {
        if (!markRunning(job)) {
            return;
        }
        try {
            LbpPort.AccountsSnapshot fetched = port.fetchAccounts(job.plainState());
            PreparedSnapshot prepared = prepare(fetched);
            if (commit(job, prepared)) {
                log.info("La Banque Postale sync completed (member={}; accounts={})",
                    job.memberId(), prepared.accounts().size());
            } else {
                log.info("Discarded stale La Banque Postale sync result (member={})", job.memberId());
            }
        } catch (SyncException ex) {
            LbpErrorCode code = codeOf(ex);
            markFailed(job, code);
            log.warn("La Banque Postale sync failed (member={}; code={})", job.memberId(), code);
        } catch (Exception ex) {
            markFailed(job, LbpErrorCode.INTERNAL_ERROR);
            // The exception type only: its message could carry a value from the payload.
            log.error("La Banque Postale sync failed unexpectedly (member={}; type={})",
                job.memberId(), ex.getClass().getSimpleName());
        }
    }

    private boolean markRunning(SyncJob job) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<LbpSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(), job.memberId());
            if (current.isEmpty()) {
                log.info("La Banque Postale sync session disappeared before execution (member={})",
                    job.memberId());
                return false;
            }
            LbpSession session = current.get();
            if (!session.isActive() || session.getSyncStatus() != LbpSyncStatus.QUEUED) {
                log.warn("La Banque Postale sync cannot start from state {} (member={}; active={})",
                    session.getSyncStatus(), job.memberId(), session.isActive());
                return false;
            }
            session.markRunning(clock.instant());
            sessionRepository.save(session);
            return true;
        }));
    }

    /**
     * Validates the whole snapshot before anything is written: one doubtful account refuses the
     * lot, so a partial list can never overwrite a correct import.
     */
    private PreparedSnapshot prepare(LbpPort.AccountsSnapshot fetched) {
        if (fetched == null || fetched.accounts().isEmpty()) {
            throw invalid("no account");
        }

        Set<String> externalIds = new HashSet<>();
        List<PreparedAccount> accounts = new ArrayList<>();
        for (LbpPort.AccountData account : fetched.accounts()) {
            if (account == null || !account.snapshotComplete()) {
                throw invalid("an incomplete snapshot");
            }
            String externalId = prefixed(account.externalId());
            if (!externalIds.add(externalId)) {
                throw invalid("duplicate accounts");
            }
            AccountType type = typeOf(account.kind());
            if (account.balance() == null) {
                throw invalid("an account without a balance");
            }
            if (!"EUR".equals(account.currency())) {
                throw invalid("an account that is not in EUR");
            }
            if (type == AccountType.CREDIT_CARD && account.balance().signum() > 0) {
                // A card balance is its outstanding: money owed, never a credit.
                throw invalid("a card with a positive outstanding");
            }
            CardNature cardNature = type == AccountType.CREDIT_CARD ? natureOf(account.cardNature()) : null;
            accounts.add(new PreparedAccount(
                externalId,
                limit(account.name(), 100, fallbackName(type)),
                type,
                account.balance(),
                cardNature,
                account.nextDueDate(),
                prepareTransactions(account.transactions())
            ));
        }

        List<UnsupportedProduct> unsupported = new ArrayList<>();
        for (LbpPort.Unsupported item : fetched.unsupported()) {
            if (item == null || clean(item.externalId()) == null || clean(item.familyCode()) == null
                || unsupported.size() >= MAX_UNSUPPORTED) {
                throw invalid("an invalid unsupported product list");
            }
            unsupported.add(new UnsupportedProduct(item.externalId().trim(), item.familyCode().trim()));
        }
        return new PreparedSnapshot(List.copyOf(accounts), List.copyOf(unsupported));
    }

    private List<PreparedTransaction> prepareTransactions(List<LbpPort.Transaction> raw) {
        // A provider repeating an id inside one account is not a reason to fail the sync; the
        // last occurrence wins, exactly as the next sync would resolve it.
        Map<String, PreparedTransaction> byId = new LinkedHashMap<>();
        for (LbpPort.Transaction tx : raw) {
            if (tx == null || tx.date() == null || tx.amount() == null) {
                throw invalid("an incomplete transaction");
            }
            if (!"EUR".equals(tx.currency())) {
                throw invalid("a transaction that is not in EUR");
            }
            String externalId = prefixed(tx.externalId());
            byId.put(externalId, new PreparedTransaction(
                externalId, tx.date(), tx.amount(),
                limit(tx.label(), 255, "La Banque Postale transaction")));
        }
        return List.copyOf(byId.values());
    }

    private boolean commit(SyncJob job, PreparedSnapshot prepared) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<LbpSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(), job.memberId());
            if (current.isEmpty()) {
                log.info("La Banque Postale sync session disappeared before commit (member={})",
                    job.memberId());
                return false;
            }
            LbpSession session = current.get();
            if (!session.isActive()) {
                log.warn("La Banque Postale sync session became inactive before commit (member={})",
                    job.memberId());
                return false;
            }
            if (session.getSyncStatus() != LbpSyncStatus.RUNNING) {
                log.warn("La Banque Postale sync cannot commit from state {} (member={})",
                    session.getSyncStatus(), job.memberId());
                return false;
            }

            FamilyMember member = memberRepository.findById(job.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            Instant syncedAt = clock.instant();
            for (PreparedAccount data : prepared.accounts()) {
                upsertAccount(data, member, job.memberId(), syncedAt);
            }

            session.recordUnsupportedProducts(serializeUnsupported(prepared.unsupported()));
            session.markSuccessful(syncedAt);
            sessionRepository.save(session);
            return true;
        }));
    }

    private void upsertAccount(PreparedAccount data, FamilyMember member, Long memberId, Instant syncedAt) {
        Optional<Account> existing = accountRepository.findByExternalAccountIdAndMemberId(
            data.externalId(), memberId);
        if (existing.isEmpty()
            && accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), memberId)) {
            log.info("La Banque Postale skipped a soft-deleted account (member={})", memberId);
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
        account.setCurrentBalance(data.balance());
        account.setLastSyncedAt(syncedAt);
        if (data.cardNature() != null) {
            // Same shape as the Amex card: what is still to be debited, and when. The card is
            // never given a parentAccountId: the dashboard skips any account with a parent.
            account.setCardNature(data.cardNature());
            account.setPaymentDueAmount(data.balance().abs());
            account.setPaymentDueDate(data.nextDueDate());
        }
        // The IBAN is deliberately not stored: it would let the Enable Banking sync adopt these
        // accounts by itself while both connectors are configured.
        Account saved = accountRepository.save(account);
        accountService.upsertSnapshot(saved, data.balance(), LocalDate.now(clock));
        upsertTransactions(saved, data.transactions());
    }

    /** Upsert by (account, external id); rows are never deleted, manual rows are never touched. */
    private void upsertTransactions(Account account, List<PreparedTransaction> transactions) {
        if (transactions.isEmpty()) {
            return;
        }
        Map<String, Transaction> storedById = new LinkedHashMap<>();
        if (account.getId() != null) {
            for (Transaction row : transactionRepository.findByAccountIdAndIsManualFalse(account.getId())) {
                if (row.getExternalId() != null) {
                    storedById.put(row.getExternalId(), row);
                }
            }
        }
        List<Transaction> upserts = new ArrayList<>(transactions.size());
        for (PreparedTransaction tx : transactions) {
            Transaction row = storedById.get(tx.externalId());
            if (row == null) {
                row = Transaction.builder()
                    .account(account)
                    .externalId(tx.externalId())
                    .nativeCurrency("EUR")
                    .build();
            }
            row.setDate(tx.date());
            row.setDescription(tx.label());
            row.setAmount(tx.amount());
            upserts.add(row);
        }
        transactionRepository.saveAllAndFlush(upserts);
    }

    private void markFailed(SyncJob job, LbpErrorCode code) {
        try {
            txTemplate.executeWithoutResult(status -> {
                Optional<LbpSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                    job.sessionId(), job.memberId());
                if (current.isEmpty()) {
                    log.info("La Banque Postale sync session disappeared before failure was recorded (member={})",
                        job.memberId());
                    return;
                }
                LbpSession session = current.get();
                if (!session.isSyncInFlight()) {
                    log.warn("La Banque Postale sync failure ignored from state {} (member={}; code={})",
                        session.getSyncStatus(), job.memberId(), code);
                    return;
                }
                session.markFailed(code, clock.instant());
                sessionRepository.save(session);
            });
        } catch (RuntimeException ex) {
            log.error("Could not persist La Banque Postale sync failure (member={}; code={})",
                job.memberId(), code);
        }
    }

    /**
     * Queued jobs live in a process-local executor and cannot survive a backend restart. Turn
     * persisted in-flight states into a retryable failure instead of leaving the UI polling.
     */
    @Transactional
    public void recoverInterruptedSyncs() {
        int recovered = sessionRepository.markInterruptedSyncsFailed(
            List.of(LbpSyncStatus.QUEUED, LbpSyncStatus.RUNNING),
            LbpSyncStatus.FAILED,
            clock.instant(),
            LbpErrorCode.INTERNAL_ERROR
        );
        if (recovered > 0) {
            log.warn("Recovered {} interrupted La Banque Postale sync job(s)", recovered);
        }
    }

    @Transactional(readOnly = true)
    public SessionStatusResponse getStatus(Long memberId) {
        return sessionRepository.findByMemberId(memberId)
            .map(this::toStatus)
            .orElseGet(SessionStatusResponse::inactive);
    }

    /**
     * Deletes the stored session. This only forgets the cookies in Picsou: the bank session is
     * not revoked (there is no confirmed logout call on the port) and may stay active until it
     * expires. Returns whether a stored session was there to delete.
     */
    public boolean clearSession(Long memberId) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            var session = sessionRepository.findByMemberIdForUpdate(memberId);
            session.ifPresent(sessionRepository::delete);
            return session.isPresent();
        }));
    }

    private SessionStatusResponse toStatus(LbpSession session) {
        return new SessionStatusResponse(
            session.isActive(),
            session.getSyncStatus(),
            session.getLastSyncStartedAt(),
            session.getLastSyncCompletedAt(),
            session.getLastSyncError(),
            parseUnsupported(session.getUnsupportedProducts())
        );
    }

    private static String serializeUnsupported(List<UnsupportedProduct> unsupported) {
        try {
            return JSON.writeValueAsString(unsupported);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not encode the unsupported products");
        }
    }

    private static List<UnsupportedProduct> parseUnsupported(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(JSON.readValue(json, UNSUPPORTED_TYPE));
        } catch (JsonProcessingException ex) {
            log.warn("Stored La Banque Postale unsupported-product list is unreadable");
            return List.of();
        }
    }

    private static CardNature natureOf(String nature) {
        if (nature == null) {
            return null;
        }
        try {
            return CardNature.valueOf(nature);
        } catch (IllegalArgumentException ex) {
            // Never guess: an unknown nature means the format moved under us.
            throw invalid("an unknown card nature");
        }
    }

    private static AccountType typeOf(String kind) {
        if (kind == null) {
            throw invalid("an account without a kind");
        }
        return switch (kind) {
            case "CURRENT_ACCOUNT" -> AccountType.CHECKING;
            case "LIVRET_A" -> AccountType.LIVRET_A;
            case "LDDS" -> AccountType.LDDS;
            case "LEP" -> AccountType.LEP;
            case "CSL" -> AccountType.SAVINGS;
            case "LIVRET_JEUNE" -> AccountType.LIVRET_JEUNE;
            case "PEL" -> AccountType.PEL;
            case "CEL" -> AccountType.CEL;
            case "CARD" -> AccountType.CREDIT_CARD;
            // Never guess: an unknown kind means the format moved under us.
            default -> throw invalid("an unknown account kind");
        };
    }

    private static String fallbackName(AccountType type) {
        return switch (type) {
            case CREDIT_CARD -> "La Banque Postale card";
            case LIVRET_A -> "Livret A";
            case LDDS -> "LDDS";
            case LEP -> "LEP";
            case LIVRET_JEUNE -> "Livret Jeune";
            case PEL -> "PEL";
            case CEL -> "CEL";
            case SAVINGS -> "Compte sur livret";
            default -> "La Banque Postale account";
        };
    }

    private static String colorFor(AccountType type) {
        return switch (type) {
            // Same colour for every regulated savings book, as the Bourso connector does.
            case LIVRET_A, LDDS, LEP, LIVRET_JEUNE, PEL, CEL, SAVINGS -> "#f59e0b";
            case CREDIT_CARD -> "#2563eb";
            default -> "#dc2626";
        };
    }

    private static String prefixed(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            throw invalid("an invalid identifier");
        }
        String externalId = cleaned.startsWith(EXTERNAL_ID_PREFIX) ? cleaned : EXTERNAL_ID_PREFIX + cleaned;
        if (externalId.length() > MAX_EXTERNAL_ID_LENGTH) {
            throw invalid("an invalid identifier");
        }
        return externalId;
    }

    private LbpErrorCode codeOf(SyncException exception) {
        if (exception.getCode() == null) {
            return LbpErrorCode.UPSTREAM_UNAVAILABLE;
        }
        try {
            return LbpErrorCode.valueOf(exception.getCode());
        } catch (IllegalArgumentException ignored) {
            return LbpErrorCode.UPSTREAM_UNAVAILABLE;
        }
    }

    private static SyncException error(LbpErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    private static SyncException invalid(String what) {
        return error(LbpErrorCode.UPSTREAM_FORMAT_CHANGED, "La Banque Postale returned " + what, null);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String limit(String value, int maxLength, String fallback) {
        String cleaned = clean(value);
        if (cleaned == null) {
            cleaned = fallback;
        }
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    private <T> T requireTransactionResult(T value) {
        return Objects.requireNonNull(value, "Transaction callback returned no result");
    }

    /** The pending login to show: the bank's keypad, clicked by the user, and its lifetime. */
    public record InitiateResponse(String processId, LbpPort.Keypad keypad, int expiresInSeconds) {}

    /**
     * A login in flight. Only one of the two deadlines is set: the keypad's while the user
     * clicks, the OTP window's once the digits were sent.
     */
    private record PendingLogin(Long memberId, Instant keypadExpiresAt, Instant otpExpiresAt) {
        Instant deadline() {
            return otpExpiresAt != null ? otpExpiresAt : keypadExpiresAt;
        }
    }

    public record UnsupportedProduct(String externalId, String familyCode) {}

    public record SessionStatusResponse(
        boolean isActive,
        LbpSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        LbpErrorCode lastSyncError,
        List<UnsupportedProduct> unsupported
    ) {
        public SessionStatusResponse {
            unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
        }

        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, LbpSyncStatus.IDLE, null, null, null, List.of());
        }

        @JsonProperty("unsupportedCount")
        public int unsupportedCount() {
            return unsupported.size();
        }
    }

    private record QueueDecision(SyncJob job, SessionStatusResponse status, SyncException failure) {}
    private record SyncJob(Long sessionId, Long memberId, String plainState) {}
    private record PreparedSnapshot(List<PreparedAccount> accounts, List<UnsupportedProduct> unsupported) {}
    private record PreparedAccount(
        String externalId,
        String name,
        AccountType type,
        BigDecimal balance,
        CardNature cardNature,
        LocalDate nextDueDate,
        List<PreparedTransaction> transactions
    ) {}
    private record PreparedTransaction(
        String externalId, LocalDate date, BigDecimal amount, String label
    ) {}
}
