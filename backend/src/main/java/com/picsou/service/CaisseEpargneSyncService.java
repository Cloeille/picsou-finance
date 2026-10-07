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
import com.picsou.model.CaisseEpargneSession;
import com.picsou.model.CaisseEpargneSyncStatus;
import com.picsou.model.CardNature;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.FamilyMember;
import com.picsou.model.Transaction;
import com.picsou.port.CaisseEpargneErrorCode;
import com.picsou.port.CaisseEpargnePort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CaisseEpargneSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
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
 * Imports Caisse d'Epargne current accounts, Livrets A and cards, with their transactions.
 *
 * <p>Shaped like {@link BoursoSyncService}: upstream I/O happens outside any transaction and one
 * short transaction writes the whole snapshot, so a failure part-way rolls every account back.
 * Differences, all deliberate: the sync is <b>on demand only</b> (no scheduler, no auto-resync
 * hook), no credentials are ever stored (decision D5, cookies only) so an expired session can
 * only be renewed by the user, and unsupported contracts are counted but never created.
 */
@Service
public class CaisseEpargneSyncService {
    private static final Logger log = LoggerFactory.getLogger(CaisseEpargneSyncService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<UnsupportedContract>> UNSUPPORTED_TYPE = new TypeReference<>() {};

    static final String PROVIDER = "Caisse d'Epargne";
    private static final String EXTERNAL_ID_PREFIX = "ce_";
    /** Slug of the seeded TRANSFER category (money moving between the member's own accounts). */
    private static final String TRANSFER_SLUG = "virement-interne";
    /** The bank's {@code transactionTypeCode} on the current-account debit that settles a card. */
    private static final String SETTLEMENT_TYPE_CODE = "04";
    /** Same width the other identified-transaction connectors use for the external id. */
    private static final int MAX_EXTERNAL_ID_LENGTH = 100;
    private static final int MAX_UNSUPPORTED = 100;
    /** The bank's pad is 10 images laid out in 5 columns; anything else is a changed page. */
    private static final int KEYPAD_KEY_COUNT = 10;
    private static final int KEYPAD_COLUMNS = 5;
    /** A key image is a small PNG (about 920 bytes live). Bigger is not a key image. */
    private static final int KEYPAD_MAX_IMAGE_LENGTH = 16 * 1024;
    private static final String KEYPAD_IMAGE_PREFIX = "data:image/png;base64,";
    /**
     * How long the user has to approve on the phone once the keypad has been submitted. The
     * sidecar waits 150 s, so the ceiling here is what bounds a stale process id afterwards.
     */
    private static final int KEYPAD_APPROVAL_WINDOW_SECONDS = 180;
    private static final java.util.regex.Pattern BASE64 = java.util.regex.Pattern.compile("[A-Za-z0-9+/]+={0,2}");

    private final CaisseEpargnePort port;
    private final CaisseEpargneSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final FamilyMemberRepository memberRepository;
    private final AccountService accountService;
    private final CategorizationService categorizationService;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final Executor syncExecutor;
    private final Clock clock;
    /**
     * Logins waiting for the human to approve on the phone. Holds the process id, the member and a
     * deadline: never the identifier, the password nor the session. Process-local on purpose, a
     * restart drops the attempt and the user starts again.
     */
    private final Map<String, PendingLogin> pendingLogins = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public CaisseEpargneSyncService(
        CaisseEpargnePort port,
        CaisseEpargneSessionRepository sessionRepository,
        AccountRepository accountRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository memberRepository,
        AccountService accountService,
        CategorizationService categorizationService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("caisseEpargneSyncExecutor") Executor syncExecutor
    ) {
        this(port, sessionRepository, accountRepository, transactionRepository, memberRepository,
            accountService, categorizationService, encryption, txTemplate, syncExecutor, Clock.systemUTC());
    }

    CaisseEpargneSyncService(
        CaisseEpargnePort port,
        CaisseEpargneSessionRepository sessionRepository,
        AccountRepository accountRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository memberRepository,
        AccountService accountService,
        CategorizationService categorizationService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        Executor syncExecutor,
        Clock clock
    ) {
        this.clock = clock;
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.memberRepository = memberRepository;
        this.accountService = accountService;
        this.categorizationService = categorizationService;
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.syncExecutor = syncExecutor;
    }

    /**
     * Starts a login. One call to the sidecar, no retry. No password ever reaches Picsou: the
     * sidecar answers with the bank's keypad, which the user clicks himself. A failure leaves any
     * session already stored (valid or not) exactly as it was.
     */
    public InitiateResponse initiateAuth(String customerId, Long memberId) {
        CaisseEpargnePort.InitiateResult result = port.initiateAuth(customerId);
        if (result == null || result.processId() == null || result.processId().isBlank()
            || result.keypad() == null || result.expiresInSeconds() <= 0) {
            throw error(CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED,
                "Caisse d'Epargne did not return a usable sign-in challenge", null);
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
    private void requireSafeKeypad(CaisseEpargnePort.Keypad keypad) {
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
            throw error(CaisseEpargneErrorCode.KEYPAD_CHANGED,
                "Caisse d'Epargne changed its login page. Nothing was sent.", null);
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
     * the password in disguise and are never logged. It must belong to the caller, still be within
     * its keypad lifetime, and the keypad step must not have been done yet.
     */
    public void submitKeypad(String processId, List<Integer> positions, Long memberId) {
        PendingLogin pending = takePending(processId, memberId);
        if (pending.approvalExpiresAt() != null) {
            // The digits were already sent for this process: nothing more to click.
            throw error(CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED,
                "This Caisse d'Epargne sign-in attempt expired. Please start again.", null);
        }
        if (!pending.keypadExpiresAt().isAfter(clock.instant())) {
            // Nothing was clicked anywhere: the user just took too long.
            throw error(CaisseEpargneErrorCode.KEYPAD_EXPIRED,
                "The Caisse d'Epargne keypad expired. Start again.", null);
        }
        port.submitKeypad(processId, positions);
        pendingLogins.put(processId, new PendingLogin(memberId, null,
            clock.instant().plusSeconds(KEYPAD_APPROVAL_WINDOW_SECONDS)));
    }

    /**
     * Removes a pending login for its owner, or refuses without burning the owner's attempt.
     * Shared by the keypad and complete steps so both are single use and member-scoped.
     */
    private PendingLogin takePending(String processId, Long memberId) {
        PendingLogin pending = pendingLogins.remove(processId);
        if (pending != null && !pending.memberId().equals(memberId)) {
            // Someone else probing a process id must not burn the owner's attempt.
            pendingLogins.putIfAbsent(processId, pending);
            throw error(CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED,
                "This Caisse d'Epargne sign-in attempt expired. Please start again.", null);
        }
        if (pending == null) {
            throw error(CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED,
                "This Caisse d'Epargne sign-in attempt expired. Please start again.", null);
        }
        return pending;
    }

    /**
     * Finishes a login the user approved on the phone, then stores the encrypted session through
     * {@link #storeSession}. A process id is single use: it is taken out of the map before the
     * sidecar is called, so a failure cannot be replayed from here. It must belong to the caller
     * and be unexpired, otherwise the sidecar is not even contacted.
     */
    public SessionStatusResponse completeAuth(String processId, Long memberId) {
        PendingLogin pending = takePending(processId, memberId);
        // The keypad must have been submitted: without it there is no approval to wait for.
        if (pending.approvalExpiresAt() == null
            || !pending.approvalExpiresAt().isAfter(clock.instant())) {
            throw error(CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED,
                "This Caisse d'Epargne sign-in attempt expired. Please start again.", null);
        }
        String plainState = port.completeAuth(processId);
        if (plainState == null || plainState.isBlank()) {
            throw error(CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED,
                "Caisse d'Epargne did not return a session", null);
        }
        return storeSession(plainState, memberId);
    }

    /**
     * Stores (encrypted) the session state produced by a login. It does <b>not</b> queue a sync:
     * the user triggers it.
     */
    public SessionStatusResponse storeSession(String plainState, Long memberId) {
        if (plainState == null || plainState.isBlank()) {
            throw error(CaisseEpargneErrorCode.INVALID_SESSION_STATE, "No Caisse d'Epargne session was provided", null);
        }
        requireTransactionResult(txTemplate.execute(status -> {
            FamilyMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            sessionRepository.findByMemberIdForUpdate(memberId).ifPresent(sessionRepository::delete);
            sessionRepository.flush();
            return sessionRepository.saveAndFlush(
                CaisseEpargneSession.create(member, encryption.encrypt(plainState), Instant.now()));
        }));
        return getStatus(memberId);
    }

    public SessionStatusResponse queueSync(Long memberId) {
        QueueDecision decision = requireTransactionResult(txTemplate.execute(status -> {
            CaisseEpargneSession session = sessionRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> error(
                    CaisseEpargneErrorCode.SESSION_EXPIRED,
                    "No active Caisse d'Epargne session. Please reconnect.",
                    null
                ));
            if (!session.isActive()) {
                throw error(
                    CaisseEpargneErrorCode.SESSION_EXPIRED,
                    "The Caisse d'Epargne session expired. Please reconnect.",
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
                // Recorded inside the transaction, then thrown after it so the row is committed.
                session.markQueued();
                session.markFailed(CaisseEpargneErrorCode.INVALID_SESSION_STATE, Instant.now());
                sessionRepository.save(session);
                log.warn("Stored Caisse d'Epargne session is unreadable (member={}; type={})",
                    memberId, ex.getClass().getSimpleName());
                return new QueueDecision(null, null, error(
                    CaisseEpargneErrorCode.INVALID_SESSION_STATE,
                    "The stored Caisse d'Epargne session is unusable. Please reconnect.",
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
            markFailed(job, CaisseEpargneErrorCode.INTERNAL_ERROR);
            throw error(
                CaisseEpargneErrorCode.INTERNAL_ERROR,
                "Could not schedule the Caisse d'Epargne synchronization",
                ex
            );
        }
    }

    private void executeJob(SyncJob job) {
        if (!markRunning(job)) {
            return;
        }
        try {
            CaisseEpargnePort.AccountsSnapshot fetched = port.fetchAccounts(job.plainState());
            PreparedSnapshot prepared = prepare(fetched);
            if (commit(job, prepared)) {
                log.info("Caisse d'Epargne sync completed (member={}; accounts={})",
                    job.memberId(), prepared.accounts().size());
            } else {
                log.info("Discarded stale Caisse d'Epargne sync result (member={})", job.memberId());
            }
        } catch (SyncException ex) {
            CaisseEpargneErrorCode code = codeOf(ex);
            markFailed(job, code);
            log.warn("Caisse d'Epargne sync failed (member={}; code={})", job.memberId(), code);
        } catch (Exception ex) {
            markFailed(job, CaisseEpargneErrorCode.INTERNAL_ERROR);
            // The exception type only: its message could carry a value from the payload.
            log.error("Caisse d'Epargne sync failed unexpectedly (member={}; type={})",
                job.memberId(), ex.getClass().getSimpleName());
        }
    }

    private boolean markRunning(SyncJob job) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<CaisseEpargneSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(), job.memberId());
            if (current.isEmpty()) {
                log.info("Caisse d'Epargne sync session disappeared before execution (member={})", job.memberId());
                return false;
            }
            CaisseEpargneSession session = current.get();
            if (!session.isActive() || session.getSyncStatus() != CaisseEpargneSyncStatus.QUEUED) {
                log.warn("Caisse d'Epargne sync cannot start from state {} (member={}; active={})",
                    session.getSyncStatus(), job.memberId(), session.isActive());
                return false;
            }
            session.markRunning(Instant.now());
            sessionRepository.save(session);
            return true;
        }));
    }

    /**
     * Validates the whole snapshot before anything is written: one doubtful account refuses the
     * lot, so a partial list can never overwrite a correct import.
     */
    private PreparedSnapshot prepare(CaisseEpargnePort.AccountsSnapshot fetched) {
        if (fetched == null || fetched.accounts().isEmpty()) {
            throw error(CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED, "Caisse d'Epargne returned no account", null);
        }

        Set<String> externalIds = new HashSet<>();
        List<PreparedAccount> accounts = new ArrayList<>();
        for (CaisseEpargnePort.AccountData account : fetched.accounts()) {
            if (account == null || !account.snapshotComplete()) {
                throw invalid("an incomplete snapshot");
            }
            String externalId = prefixed(account.externalId(), "an invalid account identifier");
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
            String parentExternalId = cardNature == null || clean(account.parentExternalId()) == null
                ? null
                : prefixed(account.parentExternalId(), "an invalid card parent identifier");
            accounts.add(new PreparedAccount(
                externalId,
                limit(account.name(), 100, fallbackName(type)),
                type,
                account.balance(),
                cardNature,
                parentExternalId,
                account.nextDueDate(),
                prepareTransactions(account.transactions())
            ));
        }

        List<UnsupportedContract> unsupported = new ArrayList<>();
        for (CaisseEpargnePort.Unsupported item : fetched.unsupported()) {
            if (item == null || clean(item.externalId()) == null || clean(item.familyCode()) == null
                || unsupported.size() >= MAX_UNSUPPORTED) {
                throw invalid("an invalid unsupported contract list");
            }
            unsupported.add(new UnsupportedContract(item.externalId().trim(), item.familyCode().trim()));
        }
        return new PreparedSnapshot(List.copyOf(accounts), List.copyOf(unsupported));
    }

    private List<PreparedTransaction> prepareTransactions(List<CaisseEpargnePort.Transaction> raw) {
        // A provider repeating an id inside one account is not a reason to fail the sync; the last
        // occurrence wins, exactly as the next sync would resolve it.
        Map<String, PreparedTransaction> byId = new LinkedHashMap<>();
        for (CaisseEpargnePort.Transaction tx : raw) {
            if (tx == null || tx.date() == null || tx.amount() == null) {
                throw invalid("an incomplete transaction");
            }
            if (!"EUR".equals(tx.currency())) {
                throw invalid("a transaction that is not in EUR");
            }
            String externalId = prefixed(tx.externalId(), "an invalid transaction identifier");
            byId.put(externalId, new PreparedTransaction(
                externalId, tx.date(), tx.dueDate(), tx.amount(),
                limit(tx.label(), 255, "Caisse d'Epargne transaction"), clean(tx.typeCode())));
        }
        return List.copyOf(byId.values());
    }

    private boolean commit(SyncJob job, PreparedSnapshot prepared) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<CaisseEpargneSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(), job.memberId());
            if (current.isEmpty()) {
                log.info("Caisse d'Epargne sync session disappeared before commit (member={})", job.memberId());
                return false;
            }
            CaisseEpargneSession session = current.get();
            if (!session.isActive()) {
                log.warn("Caisse d'Epargne sync session became inactive before commit (member={})", job.memberId());
                return false;
            }
            if (session.getSyncStatus() != CaisseEpargneSyncStatus.RUNNING) {
                log.warn("Caisse d'Epargne sync cannot commit from state {} (member={})",
                    session.getSyncStatus(), job.memberId());
                return false;
            }

            FamilyMember member = memberRepository.findById(job.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            Instant syncedAt = Instant.now();
            Map<String, Map<String, Transaction>> rowsByAccount = new LinkedHashMap<>();
            for (PreparedAccount data : prepared.accounts()) {
                Map<String, Transaction> rows = upsertAccount(data, member, job.memberId(), syncedAt);
                if (rows != null) {
                    rowsByAccount.put(data.externalId(), rows);
                }
            }
            tagCardSettlements(prepared, rowsByAccount, job.memberId());

            session.recordUnsupportedContracts(serializeUnsupported(prepared.unsupported()));
            session.markSuccessful(syncedAt);
            sessionRepository.save(session);
            return true;
        }));
    }

    /** The account's upserted rows by prefixed external id, or null when the user deleted it. */
    private Map<String, Transaction> upsertAccount(PreparedAccount data, FamilyMember member, Long memberId, Instant syncedAt) {
        Optional<Account> existing = accountRepository.findByExternalAccountIdAndMemberId(data.externalId(), memberId);
        if (existing.isEmpty()
            && accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), memberId)) {
            log.info("Caisse d'Epargne skipped a soft-deleted account (member={})", memberId);
            return null;
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
            // never given a parentAccountId: the dashboard skips any account with a parent
            // (pocket model), which would drop the card liability from net worth.
            account.setCardNature(data.cardNature());
            account.setPaymentDueAmount(data.balance().abs());
            account.setPaymentDueDate(data.nextDueDate());
        }
        // The IBAN is deliberately not stored: matching by IBAN is outside this slice, and a
        // stored IBAN would let the Enable Banking sync adopt this account by itself.
        Account saved = accountRepository.save(account);

        accountService.upsertSnapshot(saved, data.balance(), LocalDate.now());
        return upsertTransactions(saved, data.transactions());
    }

    /**
     * A deferred-debit card is settled on its current account by one debit per due date. When
     * that debit is found without doubt it is an internal transfer, not spending: the card
     * operations already count once as expenses on the card account.
     *
     * <p>Strict on purpose: only a due date already reached, a debit on that very day for exactly
     * the sum of the group, a settlement type code (or none), exactly one candidate and no
     * category yet. Anything else stays untouched, so a re-run changes nothing and a category the
     * user chose is never overwritten. Counts only are logged.
     */
    private void tagCardSettlements(
        PreparedSnapshot prepared, Map<String, Map<String, Transaction>> rowsByAccount, Long memberId
    ) {
        Map<String, PreparedAccount> byId = new LinkedHashMap<>();
        prepared.accounts().forEach(a -> byId.put(a.externalId(), a));
        Category transfer = null;
        boolean transferLoaded = false;
        int tagged = 0;
        int skipped = 0;
        for (PreparedAccount card : prepared.accounts()) {
            PreparedAccount parent = card.parentExternalId() == null ? null : byId.get(card.parentExternalId());
            if (card.cardNature() != CardNature.DEFERRED_DEBIT || parent == null
                || !rowsByAccount.containsKey(card.externalId()) || !rowsByAccount.containsKey(parent.externalId())) {
                continue;
            }
            Map<LocalDate, BigDecimal> groups = new java.util.TreeMap<>();
            LocalDate today = LocalDate.now(clock);
            for (PreparedTransaction tx : card.transactions()) {
                if (tx.dueDate() != null && !tx.dueDate().isAfter(today)) {
                    groups.merge(tx.dueDate(), tx.amount(), BigDecimal::add);
                }
            }
            if (groups.isEmpty()) {
                continue;
            }
            if (!transferLoaded) {
                transferLoaded = true;
                Category found = categorizationService.loadContext(memberId).categoriesBySlug().get(TRANSFER_SLUG);
                transfer = found != null && found.getKind() == CategoryKind.TRANSFER ? found : null;
                if (transfer == null) {
                    log.warn("Caisse d'Epargne settlement tagging skipped: no transfer category (member={})", memberId);
                    return;
                }
            }
            Map<String, Transaction> storedById = rowsByAccount.get(parent.externalId());
            List<Transaction> changed = new ArrayList<>();
            for (Map.Entry<LocalDate, BigDecimal> group : groups.entrySet()) {
                List<PreparedTransaction> candidates = parent.transactions().stream()
                    .filter(tx -> tx.date().equals(group.getKey()))
                    .filter(tx -> tx.amount().compareTo(group.getValue()) == 0)
                    .filter(tx -> tx.typeCode() == null || SETTLEMENT_TYPE_CODE.equals(tx.typeCode()))
                    .toList();
                Transaction row = candidates.size() == 1 ? storedById.get(candidates.get(0).externalId()) : null;
                if (row == null || row.getCategoryRef() != null) {
                    skipped++;
                    continue;
                }
                row.setCategoryRef(transfer);
                changed.add(row);
                tagged++;
            }
            if (!changed.isEmpty()) {
                transactionRepository.saveAllAndFlush(changed);
            }
        }
        log.info("Caisse d'Epargne card settlements: tagged={}, left alone={} (member={})", tagged, skipped, memberId);
    }

    /** Upsert by (account, external id); rows are never deleted, manual rows are never touched. */
    private Map<String, Transaction> upsertTransactions(Account account, List<PreparedTransaction> transactions) {
        Map<String, Transaction> upserted = new LinkedHashMap<>();
        if (transactions.isEmpty()) {
            return upserted;
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
            upserted.put(tx.externalId(), row);
        }
        transactionRepository.saveAllAndFlush(upserts);
        return upserted;
    }

    private void markFailed(SyncJob job, CaisseEpargneErrorCode code) {
        try {
            txTemplate.executeWithoutResult(status -> {
                Optional<CaisseEpargneSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                    job.sessionId(), job.memberId());
                if (current.isEmpty()) {
                    log.info("Caisse d'Epargne sync session disappeared before failure was recorded (member={})",
                        job.memberId());
                    return;
                }
                CaisseEpargneSession session = current.get();
                if (!session.isSyncInFlight()) {
                    log.warn("Caisse d'Epargne sync failure ignored from state {} (member={}; code={})",
                        session.getSyncStatus(), job.memberId(), code);
                    return;
                }
                session.markFailed(code, Instant.now());
                sessionRepository.save(session);
            });
        } catch (RuntimeException ex) {
            log.error("Could not persist Caisse d'Epargne sync failure (member={}; code={})", job.memberId(), code);
        }
    }

    /**
     * Queued jobs live in a process-local executor and cannot survive a backend restart. Turn
     * persisted in-flight states into a retryable failure instead of leaving the UI polling.
     */
    @Transactional
    public void recoverInterruptedSyncs() {
        int recovered = sessionRepository.markInterruptedSyncsFailed(
            List.of(CaisseEpargneSyncStatus.QUEUED, CaisseEpargneSyncStatus.RUNNING),
            CaisseEpargneSyncStatus.FAILED,
            Instant.now(),
            CaisseEpargneErrorCode.INTERNAL_ERROR
        );
        if (recovered > 0) {
            log.warn("Recovered {} interrupted Caisse d'Epargne sync job(s)", recovered);
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

    private SessionStatusResponse toStatus(CaisseEpargneSession session) {
        return new SessionStatusResponse(
            session.isActive(),
            session.getSyncStatus(),
            session.getLastSyncStartedAt(),
            session.getLastSyncCompletedAt(),
            session.getLastSyncError(),
            parseUnsupported(session.getUnsupportedContracts())
        );
    }

    private static String serializeUnsupported(List<UnsupportedContract> unsupported) {
        try {
            return JSON.writeValueAsString(unsupported);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not encode the unsupported contracts");
        }
    }

    private static List<UnsupportedContract> parseUnsupported(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(JSON.readValue(json, UNSUPPORTED_TYPE));
        } catch (JsonProcessingException ex) {
            log.warn("Stored Caisse d'Epargne unsupported-contract list is unreadable");
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
            case "CARD" -> AccountType.CREDIT_CARD;
            // Never guess: an unknown kind means the format moved under us.
            default -> throw invalid("an unknown account kind");
        };
    }

    private static String fallbackName(AccountType type) {
        return switch (type) {
            case CREDIT_CARD -> "Caisse d'Epargne card";
            case LIVRET_A -> "Livret A";
            default -> "Caisse d'Epargne account";
        };
    }

    private static String colorFor(AccountType type) {
        return switch (type) {
            case LIVRET_A -> "#f59e0b";
            case CREDIT_CARD -> "#2563eb";
            default -> "#dc2626";
        };
    }

    private static String prefixed(String raw, String problem) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            throw invalid(problem);
        }
        String externalId = cleaned.startsWith(EXTERNAL_ID_PREFIX) ? cleaned : EXTERNAL_ID_PREFIX + cleaned;
        if (externalId.length() > MAX_EXTERNAL_ID_LENGTH) {
            throw invalid(problem);
        }
        return externalId;
    }

    private CaisseEpargneErrorCode codeOf(SyncException exception) {
        if (exception.getCode() == null) {
            return CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE;
        }
        try {
            return CaisseEpargneErrorCode.valueOf(exception.getCode());
        } catch (IllegalArgumentException ignored) {
            return CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE;
        }
    }

    private static SyncException error(CaisseEpargneErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    private static SyncException invalid(String what) {
        return error(CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED, "Caisse d'Epargne returned " + what, null);
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
    public record InitiateResponse(String processId, CaisseEpargnePort.Keypad keypad, int expiresInSeconds) {}

    /**
     * A login in flight. Only one of the two deadlines is set: the keypad's while the user clicks,
     * the approval window's once the digits were sent. Both are nulled out with the process id, so
     * nothing about the pad survives a step.
     */
    private record PendingLogin(Long memberId, Instant keypadExpiresAt, Instant approvalExpiresAt) {
        Instant deadline() {
            return approvalExpiresAt != null ? approvalExpiresAt : keypadExpiresAt;
        }
    }

    public record UnsupportedContract(String externalId, String familyCode) {}

    public record SessionStatusResponse(
        boolean isActive,
        CaisseEpargneSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        CaisseEpargneErrorCode lastSyncError,
        List<UnsupportedContract> unsupported
    ) {
        public SessionStatusResponse {
            unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
        }

        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, CaisseEpargneSyncStatus.IDLE, null, null, null, List.of());
        }

        @JsonProperty("unsupportedCount")
        public int unsupportedCount() {
            return unsupported.size();
        }
    }

    private record QueueDecision(SyncJob job, SessionStatusResponse status, SyncException failure) {}
    private record SyncJob(Long sessionId, Long memberId, String plainState) {}
    private record PreparedSnapshot(List<PreparedAccount> accounts, List<UnsupportedContract> unsupported) {}
    private record PreparedAccount(
        String externalId,
        String name,
        AccountType type,
        BigDecimal balance,
        CardNature cardNature,
        String parentExternalId,
        LocalDate nextDueDate,
        List<PreparedTransaction> transactions
    ) {}
    private record PreparedTransaction(
        String externalId, LocalDate date, LocalDate dueDate, BigDecimal amount, String label, String typeCode
    ) {}
}
