package com.picsou.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.BpceSession;
import com.picsou.model.CardNature;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.Debt;
import com.picsou.model.FamilyMember;
import com.picsou.model.Transaction;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.BpcePort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.budget.CategoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The sync engine shared by the BPCE banks (Caisse d'Epargne, Banque Populaire): their sidecars
 * speak one contract ({@link BpcePort}), so the login bookkeeping, the job state machine and the
 * import of current accounts, savings books (PEL included), cards, loans, PER and assurance vie live here once. Each bank keeps
 * its own service, session table, port and error code, and hands the engine a {@link BpceBank}
 * descriptor, access to its sessions, and the {@link Branding} its imported accounts carry.
 *
 * <p>Shaped like {@link BoursoSyncService}: upstream I/O happens outside any transaction and one
 * short transaction writes the whole snapshot, so a failure part-way rolls every account back.
 * Differences, all deliberate: the sync is <b>on demand only</b> (no scheduler, no auto-resync
 * hook), the password is never stored (decision D5; the banks' services may keep the identifier,
 * encrypted, on their session row) so an expired session can only be renewed by the user, and
 * unsupported contracts are counted but never created.
 *
 * <p>Not a Spring bean: each bank's service builds its own engine, so the bank's transaction
 * boundaries ({@code @Transactional}) stay on that service.
 *
 * @param <S> the bank's session entity
 * @param <R> the bank's status response
 */
public final class BpceSyncEngine<S extends BpceSession, R> {
    private static final Logger log = LoggerFactory.getLogger(BpceSyncEngine.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<UnsupportedContract>> UNSUPPORTED_TYPE = new TypeReference<>() {};

    /** Slug of the seeded TRANSFER category (money moving between the member's own accounts). */
    private static final String TRANSFER_SLUG = "virement-interne";
    /** The bank's {@code transactionTypeCode} on the current-account debit that settles a card. */
    private static final String SETTLEMENT_TYPE_CODE = "04";
    /** Slug of the default EXPENSE category for loan installments, created on demand. */
    private static final String CREDIT_SLUG = "credit";
    /** Word the bank puts in the label of the credit that pays a personal loan out. */
    private static final String DISBURSEMENT_LABEL = "FINANCEMENT";
    /** How far a loan row may sit from the date it is expected on. */
    private static final int LOAN_DATE_TOLERANCE_DAYS = 5;
    private static final BigDecimal CENT = new BigDecimal("0.01");
    /** The smallest amount that rounds to 10^12 at 8 places, one digit past NUMERIC(20, 8). */
    private static final BigDecimal DEBT_AMOUNT_BOUND = new BigDecimal("999999999999.999999995");
    /** Same width the other identified-transaction connectors use for the external id. */
    private static final int MAX_EXTERNAL_ID_LENGTH = 100;
    private static final int MAX_UNSUPPORTED = 100;
    private static final int MAX_UNSUPPORTED_ID = 128;
    /** A family or sub-family code as the sidecar sends it: short and plain, as it checks too. */
    private static final java.util.regex.Pattern UNSUPPORTED_CODE =
        java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,8}");
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

    private final BpceBank bank;
    /** The codes this bank's sidecar declares and its session table accepts. */
    private final SidecarBank codes;
    private final BpcePort port;
    private final SessionStore<S> sessions;
    private final BiFunction<S, List<UnsupportedContract>, R> statusOf;
    private final Supplier<R> inactiveStatus;
    private final BrandingSource<S> branding;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final FamilyMemberRepository memberRepository;
    private final DebtRepository debtRepository;
    private final AccountService accountService;
    private final CategorizationService categorizationService;
    private final CategoryService categoryService;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final Executor syncExecutor;
    private final CaisseEpargnePerService perService;
    private final Clock clock;
    /**
     * Logins waiting for the human to approve on the phone. Holds the process id, the member, a
     * deadline and how to build the session row once approved: never the password nor the session.
     * That builder may capture the identifier, which the bank's service then stores encrypted.
     * Process-local on purpose, a restart drops the attempt and the user starts again.
     */
    private final Map<String, PendingLogin<S>> pendingLogins = new ConcurrentHashMap<>();
    /**
     * Bumped by {@link #clearSession}: a login that was waiting on the bank or the phone when the
     * connection was removed carries an older generation and stores nothing. Checked and bumped
     * under {@link #sessionWriteLock}.
     */
    private final Map<Long, Long> authGenerations = new ConcurrentHashMap<>();
    private final Object sessionWriteLock = new Object();

    /**
     * The bank's session table, seen through the calls the engine makes on it. Each method is a
     * one-line delegation to the bank's Spring Data repository.
     */
    interface SessionStore<S> {
        Optional<S> findByMemberId(Long memberId);

        Optional<S> findByMemberIdForUpdate(Long memberId);

        Optional<S> findByIdAndMemberIdForUpdate(Long id, Long memberId);

        void save(S session);

        void saveAndFlush(S session);

        void delete(S session);

        void flush();
    }

    /**
     * Builds a new active, idle session row; not saved. Given per login rather than once per bank,
     * so whatever the login was started with (Banque Populaire's regional bank) is captured server
     * side when the login starts and lands on the row it opens, never taken from a later request.
     * {@code previous} is the row this one replaces (read under its lock, already deleted), or null:
     * a bank that keeps something across logins carries it over from there.
     */
    @FunctionalInterface
    interface SessionFactory<S> {
        S create(FamilyMember member, String encryptedState, Instant validatedAt, S previous);
    }

    /**
     * What a sync stamps on the accounts it imports: {@code account.provider}, kept up to date on
     * every sync, and a logo URL, set on a new account and only filled on one that has none.
     *
     * @param logoUrl called once per sync, on the sync thread and outside any transaction, so it may
     *                call the network; {@code null} means no logo. Should it throw anyway, the sync
     *                goes on without a logo.
     */
    record Branding(String provider, Supplier<String> logoUrl) {
        Branding {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(logoUrl, "logoUrl");
        }

        /** A label and no logo. */
        static Branding fixed(String provider) {
            return new Branding(provider, () -> null);
        }
    }

    /**
     * The {@link Branding} of a session's sync. Runs inside the transaction that queues the sync,
     * so it must only read the session: any lookup belongs in {@link Branding#logoUrl}.
     */
    @FunctionalInterface
    interface BrandingSource<S> {
        Branding brandingFor(S session);

        /** The same label for every session, no logo. */
        static <S> BrandingSource<S> fixed(String provider) {
            Branding branding = Branding.fixed(provider);
            return session -> branding;
        }
    }

    BpceSyncEngine(
        BpceBank bank,
        SidecarBank codes,
        BpcePort port,
        SessionStore<S> sessions,
        BiFunction<S, List<UnsupportedContract>, R> statusOf,
        Supplier<R> inactiveStatus,
        BrandingSource<S> branding,
        AccountRepository accountRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository memberRepository,
        DebtRepository debtRepository,
        AccountService accountService,
        CategorizationService categorizationService,
        CategoryService categoryService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        Executor syncExecutor,
        CaisseEpargnePerService perService,
        Clock clock
    ) {
        this.bank = Objects.requireNonNull(bank, "bank");
        this.codes = Objects.requireNonNull(codes, "codes");
        this.port = port;
        this.sessions = sessions;
        this.statusOf = statusOf;
        this.inactiveStatus = inactiveStatus;
        this.branding = Objects.requireNonNull(branding, "branding");
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.memberRepository = memberRepository;
        this.debtRepository = debtRepository;
        this.accountService = accountService;
        this.categorizationService = categorizationService;
        this.categoryService = categoryService;
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.syncExecutor = syncExecutor;
        this.perService = perService;
        this.clock = clock;
    }

    BpceBank bank() {
        return bank;
    }

    /**
     * Starts a login. The bank's service makes the one sidecar call (no retry) and hands its
     * answer here. No password ever reaches Picsou: the sidecar answers with the bank's keypad,
     * which the user clicks himself. A failure leaves any session already stored (valid or not)
     * exactly as it was. {@code sessionFactory} builds the row this login opens once approved.
     */
    InitiateResponse initiateAuth(
        Supplier<BpcePort.InitiateResult> initiate, Long memberId, SessionFactory<S> sessionFactory
    ) {
        Objects.requireNonNull(sessionFactory, "sessionFactory");
        long generation = authGeneration(memberId);
        BpcePort.InitiateResult result = initiate.get();
        if (result == null || result.processId() == null || result.processId().isBlank()
            || result.keypad() == null || result.expiresInSeconds() <= 0) {
            throw error(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED,
                bank.provider() + " did not return a usable sign-in challenge", null);
        }
        requireSafeKeypad(result.keypad());
        Instant now = clock.instant();
        // Swept on every start: a stale process id must never be reachable again.
        pendingLogins.values().removeIf(pending -> !pending.deadline().isAfter(now));
        registerPending(result.processId(),
            new PendingLogin<>(memberId, now.plusSeconds(result.expiresInSeconds()), null, sessionFactory, generation));
        return new InitiateResponse(result.processId(), result.keypad(), result.expiresInSeconds());
    }

    /**
     * Refuses a keypad that cannot be shown to a user as-is. Anything unexpected becomes
     * {@code KEYPAD_CHANGED} and the process is never registered, so nothing can be clicked.
     */
    private void requireSafeKeypad(BpcePort.Keypad keypad) {
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
            throw error(SidecarErrorCode.KEYPAD_CHANGED,
                bank.provider() + " changed its login page. Nothing was sent.", null);
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
    void submitKeypad(String processId, List<Integer> positions, Long memberId) {
        PendingLogin<S> pending = takePending(processId, memberId);
        if (pending.approvalExpiresAt() != null) {
            // The digits were already sent for this process: nothing more to click.
            throw attemptExpired();
        }
        if (!pending.keypadExpiresAt().isAfter(clock.instant())) {
            // Nothing was clicked anywhere: the user just took too long.
            throw error(SidecarErrorCode.KEYPAD_EXPIRED,
                "The " + bank.provider() + " keypad expired. Start again.", null);
        }
        port.submitKeypad(processId, positions);
        registerPending(processId, new PendingLogin<>(memberId, null,
            clock.instant().plusSeconds(KEYPAD_APPROVAL_WINDOW_SECONDS), pending.sessionFactory(),
            pending.generation()));
    }

    /**
     * Puts a login in the map unless {@link #clearSession} ran since it started: a disconnect
     * during a sidecar call must not leave a process that could later be completed.
     */
    private void registerPending(String processId, PendingLogin<S> pending) {
        synchronized (sessionWriteLock) {
            requireGeneration(pending.memberId(), pending.generation());
            pendingLogins.put(processId, pending);
        }
    }

    private long authGeneration(Long memberId) {
        return authGenerations.getOrDefault(memberId, 0L);
    }

    /** Must be called under {@link #sessionWriteLock}, in the same block as the write it guards. */
    private void requireGeneration(Long memberId, long generation) {
        if (authGeneration(memberId) != generation) {
            throw error(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED,
                "The " + bank.provider() + " connection was removed during the login", null);
        }
    }

    /**
     * Removes a pending login for its owner, or refuses without burning the owner's attempt.
     * Shared by the keypad and complete steps so both are single use and member-scoped.
     */
    private PendingLogin<S> takePending(String processId, Long memberId) {
        // Read, check the owner, then remove only that exact entry: someone else probing a
        // process id never takes it out, even for an instant, so the owner's concurrent call
        // cannot see it missing.
        PendingLogin<S> pending = pendingLogins.get(processId);
        if (pending == null
            || !pending.memberId().equals(memberId)
            || !pendingLogins.remove(processId, pending)) {
            throw attemptExpired();
        }
        return pending;
    }

    private SyncException attemptExpired() {
        return error(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED,
            "This " + bank.provider() + " sign-in attempt expired. Please start again.", null);
    }

    /**
     * Finishes a login the user approved on the phone, then stores the encrypted session through
     * {@link #storeSession}. A process id is single use: it is taken out of the map before the
     * sidecar is called, so a failure cannot be replayed from here. It must belong to the caller
     * and be unexpired, otherwise the sidecar is not even contacted.
     */
    R completeAuth(String processId, Long memberId) {
        PendingLogin<S> pending = takePending(processId, memberId);
        // The keypad must have been submitted: without it there is no approval to wait for.
        if (pending.approvalExpiresAt() == null
            || !pending.approvalExpiresAt().isAfter(clock.instant())) {
            throw attemptExpired();
        }
        String plainState = port.completeAuth(processId);
        if (plainState == null || plainState.isBlank()) {
            throw error(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED,
                bank.provider() + " did not return a session", null);
        }
        return storeSession(plainState, memberId, pending.sessionFactory(), pending.generation());
    }

    /**
     * Stores (encrypted) the session state produced by a login, in a row built by
     * {@code sessionFactory}; it replaces any row the member had. It does <b>not</b> queue a sync:
     * the user triggers it.
     */
    R storeSession(String plainState, Long memberId, SessionFactory<S> sessionFactory) {
        return storeSession(plainState, memberId, sessionFactory, authGeneration(memberId));
    }

    /**
     * Refused when {@link #clearSession} ran since {@code generation} was read: the user
     * disconnected while this login was in flight.
     */
    private R storeSession(String plainState, Long memberId, SessionFactory<S> sessionFactory, long generation) {
        if (plainState == null || plainState.isBlank()) {
            throw error(SidecarErrorCode.INVALID_SESSION_STATE, "No " + bank.provider() + " session was provided", null);
        }
        synchronized (sessionWriteLock) {
            requireGeneration(memberId, generation);
            requireTransactionResult(txTemplate.execute(status -> {
                FamilyMember member = memberRepository.findById(memberId)
                    .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
                Optional<S> previous = sessions.findByMemberIdForUpdate(memberId);
                previous.ifPresent(sessions::delete);
                sessions.flush();
                S created = sessionFactory.create(
                    member, encryption.encrypt(plainState), Instant.now(), previous.orElse(null));
                sessions.saveAndFlush(created);
                return created;
            }));
        }
        return getStatus(memberId);
    }

    R queueSync(Long memberId) {
        QueueDecision<R> decision = requireTransactionResult(txTemplate.execute(status -> {
            S session = sessions.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> error(
                    SidecarErrorCode.SESSION_EXPIRED,
                    "No active " + bank.provider() + " session. Please reconnect.",
                    null
                ));
            if (!session.isActive()) {
                throw error(
                    SidecarErrorCode.SESSION_EXPIRED,
                    "The " + bank.provider() + " session expired. Please reconnect.",
                    null
                );
            }
            if (session.isSyncInFlight()) {
                return new QueueDecision<>(null, toStatus(session), null);
            }

            String plainState;
            try {
                plainState = encryption.decrypt(session.getSessionState());
            } catch (RuntimeException ex) {
                // Unreadable (wrong key, corrupted): the session is dead, not a server fault.
                // Recorded inside the transaction, then thrown after it so the row is committed.
                session.markQueued();
                session.markFailed(SidecarErrorCode.INVALID_SESSION_STATE, Instant.now());
                sessions.save(session);
                log.warn("Stored {} session is unreadable (member={}; type={})",
                    bank.provider(), memberId, ex.getClass().getSimpleName());
                return new QueueDecision<R>(null, null, error(
                    SidecarErrorCode.INVALID_SESSION_STATE,
                    "The stored " + bank.provider() + " session is unusable. Please reconnect.",
                    null
                ));
            }
            session.markQueued();
            sessions.save(session);
            return new QueueDecision<>(
                new SyncJob(session.getId(), memberId, plainState, branding.brandingFor(session)),
                toStatus(session), null);
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
            markFailed(job, SidecarErrorCode.INTERNAL_ERROR);
            throw error(
                SidecarErrorCode.INTERNAL_ERROR,
                "Could not schedule the " + bank.provider() + " synchronization",
                ex
            );
        }
    }

    private void executeJob(SyncJob job) {
        if (!markRunning(job)) {
            return;
        }
        try {
            BpcePort.AccountsSnapshot fetched = port.fetchAccounts(job.plainState());
            PreparedSnapshot prepared = prepare(fetched);
            if (commit(job, prepared, logoOf(job))) {
                for (PreparedAccount account : prepared.accounts()) {
                    if (account.per() == null) continue;
                    try {
                        perService.backfill(account.per());
                    } catch (RuntimeException ex) {
                        // Market/identity enrichment is optional and runs after the account
                        // transaction. A proxy commit failure must not mark that import failed
                        // or prevent enrichment for later PER accounts.
                        log.warn("{} PER post-sync enrichment failed (member={}; type={})",
                            bank.provider(), job.memberId(), ex.getClass().getSimpleName());
                    }
                }
                log.info("{} sync completed (member={}; accounts={})",
                    bank.provider(), job.memberId(), prepared.accounts().size());
            } else {
                log.info("Discarded stale {} sync result (member={})", bank.provider(), job.memberId());
            }
        } catch (SyncException ex) {
            SidecarErrorCode code = codeOf(ex);
            markFailed(job, code);
            log.warn("{} sync failed (member={}; code={})", bank.provider(), job.memberId(), code);
        } catch (Exception ex) {
            markFailed(job, SidecarErrorCode.INTERNAL_ERROR);
            // The exception type only: its message could carry a value from the payload.
            log.error("{} sync failed unexpectedly (member={}; type={})",
                bank.provider(), job.memberId(), ex.getClass().getSimpleName());
        }
    }

    /**
     * Resolved once per sync, outside any transaction, after the snapshot was validated: a refused
     * snapshot costs no lookup. Never fails the sync: the accounts then keep the logo they have.
     */
    private String logoOf(SyncJob job) {
        try {
            return job.branding().logoUrl().get();
        } catch (RuntimeException ex) {
            log.warn("{} logo lookup failed; importing without a logo (member={}; type={})",
                bank.provider(), job.memberId(), ex.getClass().getSimpleName());
            return null;
        }
    }

    private boolean markRunning(SyncJob job) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<S> current = sessions.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId());
            if (current.isEmpty()) {
                log.info("{} sync session disappeared before execution (member={})", bank.provider(), job.memberId());
                return false;
            }
            S session = current.get();
            if (!session.isActive() || !session.isQueued()) {
                log.warn("{} sync cannot start from state {} (member={}; active={})",
                    bank.provider(), session.getSyncStatus(), job.memberId(), session.isActive());
                return false;
            }
            session.markRunning(Instant.now());
            sessions.save(session);
            return true;
        }));
    }

    /**
     * Validates the whole snapshot before anything is written: one doubtful account refuses the
     * lot, so a partial list can never overwrite a correct import.
     */
    private PreparedSnapshot prepare(BpcePort.AccountsSnapshot fetched) {
        if (fetched == null || fetched.accounts().isEmpty()) {
            throw error(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED, bank.provider() + " returned no account", null);
        }

        Set<String> externalIds = new HashSet<>();
        List<PreparedAccount> accounts = new ArrayList<>();
        for (BpcePort.AccountData account : fetched.accounts()) {
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
            if (type == AccountType.LOAN && account.balance().signum() > 0) {
                // The bank shows a loan's outstanding capital as a negative figure.
                throw invalid("a loan with a positive outstanding");
            }
            // A PER and an assurance vie are read from the insurer; both are assets.
            boolean insurerContract = type == AccountType.PER || type == AccountType.ASSURANCE_VIE;
            if (insurerContract && account.balance().signum() < 0) {
                throw invalid("an insurer contract with a negative balance");
            }
            // Picsou stores a loan positive and applies the liability sign itself.
            if (account.per() != null && !insurerContract) {
                throw invalid("insurer details on another account type");
            }
            // Validated and resolved here, outside any database transaction: it may call market providers.
            CaisseEpargnePerService.Prepared per = insurerContract
                ? perService.prepare(account.per(), account.currency())
                : null;
            BigDecimal balance = per != null
                ? per.balance()
                : type == AccountType.LOAN ? account.balance().negate() : account.balance();
            CardNature cardNature = type == AccountType.CREDIT_CARD ? natureOf(account.cardNature()) : null;
            String parentExternalId = cardNature == null || clean(account.parentExternalId()) == null
                ? null
                : prefixed(account.parentExternalId(), "an invalid card parent identifier");
            accounts.add(new PreparedAccount(
                externalId,
                limit(account.name(), 100, fallbackName(account.kind(), type)),
                type,
                balance,
                cardNature,
                parentExternalId,
                account.nextDueDate(),
                prepareTransactions(account.transactions()),
                clean(account.iban()),
                account.ibanAmbiguous(),
                type == AccountType.LOAN ? usableLoan(account.loan()) : null,
                per
            ));
        }

        List<UnsupportedContract> unsupported = new ArrayList<>();
        for (BpcePort.Unsupported item : fetched.unsupported()) {
            if (item == null || clean(item.externalId()) == null || clean(item.familyCode()) == null
                || unsupported.size() >= MAX_UNSUPPORTED) {
                throw invalid("an invalid unsupported contract list");
            }
            // Stored and shown as they are, so bounded here whatever the sidecar sends: an
            // out-of-shape code becomes "?", never a long or odd string in the status.
            unsupported.add(new UnsupportedContract(
                limit(item.externalId(), MAX_UNSUPPORTED_ID, "?"),
                unsupportedCode(item.familyCode()),
                unsupportedCode(item.subFamilyCode())));
        }
        return new PreparedSnapshot(List.copyOf(accounts), List.copyOf(unsupported));
    }

    private List<PreparedTransaction> prepareTransactions(List<BpcePort.Transaction> raw) {
        // A provider repeating an id inside one account is not a reason to fail the sync; the last
        // occurrence wins, exactly as the next sync would resolve it.
        Map<String, PreparedTransaction> byId = new LinkedHashMap<>();
        for (BpcePort.Transaction tx : raw) {
            if (tx == null || tx.date() == null || tx.amount() == null) {
                throw invalid("an incomplete transaction");
            }
            if (!"EUR".equals(tx.currency())) {
                throw invalid("a transaction that is not in EUR");
            }
            String externalId = prefixed(tx.externalId(), "an invalid transaction identifier");
            byId.put(externalId, new PreparedTransaction(
                externalId, tx.date(), tx.dueDate(), tx.amount(),
                limit(tx.label(), 255, bank.transactionLabel()), clean(tx.typeCode())));
        }
        return List.copyOf(byId.values());
    }

    private boolean commit(SyncJob job, PreparedSnapshot prepared, String logoUrl) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<S> current = sessions.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId());
            if (current.isEmpty()) {
                log.info("{} sync session disappeared before commit (member={})", bank.provider(), job.memberId());
                return false;
            }
            S session = current.get();
            if (!session.isActive()) {
                log.warn("{} sync session became inactive before commit (member={})", bank.provider(), job.memberId());
                return false;
            }
            if (!session.isRunning()) {
                log.warn("{} sync cannot commit from state {} (member={})",
                    bank.provider(), session.getSyncStatus(), job.memberId());
                return false;
            }

            FamilyMember member = memberRepository.findById(job.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            Instant syncedAt = Instant.now();
            Map<String, Map<String, Transaction>> rowsByAccount = new LinkedHashMap<>();
            Map<String, Account> savedById = new LinkedHashMap<>();
            for (PreparedAccount data : prepared.accounts()) {
                Map<String, Transaction> rows =
                    upsertAccount(data, member, job.memberId(), syncedAt, savedById,
                        job.branding().provider(), logoUrl);
                if (rows != null) {
                    rowsByAccount.put(data.externalId(), rows);
                }
            }
            upsertLoanDebts(prepared, savedById, member, job.memberId());
            tagCardSettlements(prepared, rowsByAccount, job.memberId());
            tagLoanRows(prepared, rowsByAccount, job.memberId());

            session.recordUnsupportedContracts(serializeUnsupported(prepared.unsupported()));
            session.markSuccessful(syncedAt);
            sessions.save(session);
            return true;
        }));
    }

    /**
     * The account's upserted rows by prefixed external id, or null when the user deleted it. The
     * provider label is rewritten on every sync (the connector owns it); the logo follows Enable
     * Banking's rule: set on a new account, filled on an existing one only when it has none, never
     * overwritten.
     */
    private Map<String, Transaction> upsertAccount(
        PreparedAccount data, FamilyMember member, Long memberId, Instant syncedAt, Map<String, Account> savedById,
        String provider, String logoUrl
    ) {
        Optional<Account> existing = accountRepository.findByExternalAccountIdAndMemberId(data.externalId(), memberId);
        if (existing.isEmpty()
            && accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), memberId)) {
            log.info("{} skipped a soft-deleted account (member={})", bank.provider(), memberId);
            return null;
        }

        Account account = existing.orElseGet(() -> Account.builder()
            .member(member)
            .externalAccountId(data.externalId())
            .provider(provider)
            .currency("EUR")
            .isManual(false)
            .color(colorFor(data.type()))
            .logoUrl(logoUrl)
            .build());
        account.setName(data.name());
        account.setType(data.type());
        account.setProvider(provider);
        if (account.getLogoUrl() == null && logoUrl != null) {
            account.setLogoUrl(logoUrl);
        }
        account.setCurrency("EUR");
        account.setManual(false);
        account.setCurrentBalance(data.balance());
        if (data.per() != null) {
            // The euro-fund sleeve is stored, not quoted; UC lines are revalued from market prices.
            account.setCashBalance(data.per().euroFundBalance());
        }
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
        savedById.put(data.externalId(), saved);

        if (data.per() != null) {
            perService.apply(saved, data.per(), syncedAt);
        }
        LocalDate snapshotDate = data.per() != null ? data.per().valuationDate() : LocalDate.now(clock);
        if (data.per() != null) {
            // The bank NAV is an observation, not an acquisition cost. Pair it with itself so
            // this historical snapshot does not derive its baseline from today's market quote.
            accountService.upsertSnapshot(saved, data.balance(), data.balance(), snapshotDate);
        } else {
            accountService.upsertSnapshot(saved, data.balance(), snapshotDate);
        }
        return upsertTransactions(saved, data.transactions());
    }

    /**
     * The loan details the schedule can use, or null. A missing or odd field never fails the
     * sync: the loan account is still imported, only its Debt is left as it was. An amount the
     * debt columns cannot hold counts as odd: PostgreSQL would refuse it and roll back every
     * account of the import.
     */
    private BpcePort.LoanDetails usableLoan(BpcePort.LoanDetails loan) {
        if (loan == null
            || loan.borrowedAmount() == null || loan.borrowedAmount().signum() <= 0
            || !fitsDebtAmount(loan.borrowedAmount())
            || loan.interestRate() == null || loan.interestRate().signum() < 0
            || loan.interestRate().compareTo(BigDecimal.ONE) > 0
            || loan.monthlyPayment() == null || loan.monthlyPayment().signum() <= 0
            || !fitsDebtAmount(loan.monthlyPayment())
            || (loan.insuranceMonthly() != null && loan.insuranceMonthly().signum() < 0)
            || (loan.insuranceMonthly() != null && !fitsDebtAmount(loan.insuranceMonthly()))
            || loan.firstDueDate() == null || loan.lastDueDate() == null
            || loan.lastDueDate().isBefore(loan.firstDueDate())) {
            if (loan != null) {
                log.warn("{} loan details unusable; the loan's debt is left unchanged", bank.provider());
            }
            return null;
        }
        return loan;
    }

    /**
     * The debt amounts are NUMERIC(20, 8): PostgreSQL rounds to 8 places, then refuses a 13th
     * integer digit. Compared, never rescaled: rescaling a tiny 1E-999999999 is a huge computation.
     */
    private static boolean fitsDebtAmount(BigDecimal amount) {
        return amount.compareTo(DEBT_AMOUNT_BOUND) < 0;
    }

    /**
     * Creates or updates the Debt of each loan the bank described. Only the fields the bank
     * sends are written: file fees, or anything else the user typed, stay. {@code startDate} is
     * one month before the first due date, on the loan's due day, because the schedule dates
     * installment i at {@code startDate + i months}. The debited account is the member's account
     * with that IBAN in EUR: one of this import (its IBAN is never stored), else one already stored, else
     * none. With no debit IBAN the linked account is left as it was.
     */
    private void upsertLoanDebts(
        PreparedSnapshot prepared, Map<String, Account> savedById, FamilyMember member, Long memberId
    ) {
        for (PreparedAccount data : prepared.accounts()) {
            Account loanAccount = savedById.get(data.externalId());
            BpcePort.LoanDetails loan = data.loan();
            if (data.type() != AccountType.LOAN || loan == null || loanAccount == null) {
                continue;
            }
            Debt debt = debtRepository.findByAccountId(loanAccount.getId())
                .orElseGet(() -> Debt.builder().account(loanAccount).member(member).build());
            String iban = clean(loan.linkedIban());
            // A home loan has no debit IBAN: the user's own link, often the financed property,
            // must survive a resync.
            if (iban != null) {
                debt.setLinkedAccount(debitedAccount(iban, prepared, savedById, memberId));
            }
            debt.setBorrowedAmount(loan.borrowedAmount());
            debt.setInterestRate(loan.interestRate());
            debt.setMonthlyPayment(loan.monthlyPayment());
            debt.setInsuranceMonthly(loan.insuranceMonthly());
            debt.setLenderName(bank.lenderName());
            debt.setStartDate(scheduleAnchor(loan.firstDueDate(), loan.lastDueDate()));
            debt.setEndDate(loan.lastDueDate());
            debtRepository.save(debt);
        }
    }

    /**
     * One month before the first due date, on the loan's nominal due day. plusMonths keeps a
     * clamped day: a first due date of 28 February for a loan due on the 31st would date every
     * installment on the 28th. When the first due date is the last day of its month and the last
     * due date falls on a later day, the first one was clamped, so the anchor takes the last due
     * date's day (the home-loan first due date is derived from it). A shorter anchor month clamps
     * the anchor again (31 March gives 28 February): the schedule then takes the due day back
     * from the end date, see {@link LoanAmortizationService}.
     */
    private static LocalDate scheduleAnchor(LocalDate firstDue, LocalDate lastDue) {
        LocalDate anchor = firstDue.minusMonths(1);
        boolean clamped = firstDue.getDayOfMonth() == firstDue.lengthOfMonth()
            && lastDue.getDayOfMonth() > firstDue.getDayOfMonth();
        return clamped
            ? anchor.withDayOfMonth(Math.min(lastDue.getDayOfMonth(), anchor.lengthOfMonth()))
            : anchor;
    }

    private Account debitedAccount(
        String iban, PreparedSnapshot prepared, Map<String, Account> savedById, Long memberId
    ) {
        List<PreparedAccount> inImport = prepared.accounts().stream()
            .filter(a -> iban.equals(a.iban()))
            .toList();
        if (inImport.isEmpty()) {
            return accountRepository.findByIbanAndCurrencyAndMemberId(iban, "EUR", memberId).orElse(null);
        }
        // The bank itself shows this IBAN on this import: never guess past an ambiguity there,
        // nor fall back to another account that happens to carry the same IBAN.
        if (inImport.size() != 1 || inImport.get(0).ibanAmbiguous()) {
            return null;
        }
        return savedById.get(inImport.get(0).externalId());
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
                    log.warn("{} settlement tagging skipped: no transfer category (member={})",
                        bank.provider(), memberId);
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
        log.info("{} card settlements: tagged={}, left alone={} (member={})",
            bank.provider(), tagged, skipped, memberId);
    }

    /**
     * On the current account a loan is debited from, the disbursement is money moving in from
     * the member's own loan (an internal transfer, not income) and each installment is a loan
     * expense. Same guards as {@link #tagCardSettlements}: the debited account must be in this
     * import under an unambiguous IBAN, exactly one row must match each expected event, and only
     * a row without a category is touched. Anything else stays untouched. Counts only are logged.
     */
    private void tagLoanRows(
        PreparedSnapshot prepared, Map<String, Map<String, Transaction>> rowsByAccount, Long memberId
    ) {
        LoanCategories categories = new LoanCategories(memberId);
        int tagged = 0;
        int skipped = 0;
        for (PreparedAccount data : prepared.accounts()) {
            BpcePort.LoanDetails loan = data.loan();
            if (data.type() != AccountType.LOAN || loan == null) {
                continue;
            }
            PreparedAccount debited = debitedInImport(clean(loan.linkedIban()), prepared);
            Map<String, Transaction> storedById = debited == null ? null : rowsByAccount.get(debited.externalId());
            if (storedById == null) {
                continue;
            }
            List<Transaction> changed = new ArrayList<>();
            if (loan.signatureDate() != null) {
                LocalDate from = loan.signatureDate().minusDays(LOAN_DATE_TOLERANCE_DAYS);
                List<PreparedTransaction> credits = debited.transactions().stream()
                    .filter(tx -> tx.amount().signum() > 0)
                    .filter(tx -> sameToTheCent(tx.amount(), loan.borrowedAmount()))
                    .filter(tx -> tx.label() != null
                        && tx.label().toUpperCase(java.util.Locale.ROOT).contains(DISBURSEMENT_LABEL))
                    .filter(tx -> !tx.date().isBefore(from) && !tx.date().isAfter(loan.firstDueDate()))
                    .toList();
                if (tag(credits, storedById, categories::transfer, changed)) tagged++; else skipped++;
            }
            // Always from the first due date: plusMonths on the previous one would drift a 31st to the 28th.
            for (int k = 0; !loan.firstDueDate().plusMonths(k).isAfter(loan.lastDueDate()); k++) {
                LocalDate due = loan.firstDueDate().plusMonths(k);
                LocalDate from = due.minusDays(LOAN_DATE_TOLERANCE_DAYS);
                LocalDate to = due.plusDays(LOAN_DATE_TOLERANCE_DAYS);
                List<PreparedTransaction> debits = debited.transactions().stream()
                    .filter(tx -> tx.amount().signum() < 0)
                    .filter(tx -> sameToTheCent(tx.amount().negate(), loan.monthlyPayment()))
                    .filter(tx -> !tx.date().isBefore(from) && !tx.date().isAfter(to))
                    .toList();
                if (debits.isEmpty()) {
                    continue;
                }
                if (tag(debits, storedById, categories::credit, changed)) tagged++; else skipped++;
            }
            if (!changed.isEmpty()) {
                transactionRepository.saveAllAndFlush(changed);
            }
        }
        log.info("{} loan rows: tagged={}, left alone={} (member={})", bank.provider(), tagged, skipped, memberId);
    }

    /** Tags the single candidate when it has no category yet; false when nothing was tagged. */
    private static boolean tag(
        List<PreparedTransaction> candidates, Map<String, Transaction> storedById,
        Supplier<Category> category, List<Transaction> changed
    ) {
        Transaction row = candidates.size() == 1 ? storedById.get(candidates.get(0).externalId()) : null;
        if (row == null || row.getCategoryRef() != null) {
            return false;
        }
        Category target = category.get();
        if (target == null) {
            return false;
        }
        row.setCategoryRef(target);
        changed.add(row);
        return true;
    }

    private static boolean sameToTheCent(BigDecimal amount, BigDecimal expected) {
        return amount.subtract(expected).abs().compareTo(CENT) <= 0;
    }

    /** The loan's debited account in this import, only under an IBAN the bank shows once. */
    private static PreparedAccount debitedInImport(String iban, PreparedSnapshot prepared) {
        if (iban == null) {
            return null;
        }
        List<PreparedAccount> inImport = prepared.accounts().stream()
            .filter(a -> iban.equals(a.iban()))
            .toList();
        return inImport.size() == 1 && !inImport.get(0).ibanAmbiguous() ? inImport.get(0) : null;
    }

    /** Loads each category at most once, and only when a row actually needs it. */
    private final class LoanCategories {
        private final Long memberId;
        private Category transfer;
        private boolean transferLoaded;
        private Category credit;
        private boolean creditLoaded;

        LoanCategories(Long memberId) {
            this.memberId = memberId;
        }

        Category transfer() {
            if (!transferLoaded) {
                transferLoaded = true;
                Category found = categorizationService.loadContext(memberId).categoriesBySlug().get(TRANSFER_SLUG);
                transfer = found != null && found.getKind() == CategoryKind.TRANSFER ? found : null;
                if (transfer == null) {
                    log.warn("{} loan disbursement left alone: no transfer category (member={})",
                        bank.provider(), memberId);
                }
            }
            return transfer;
        }

        Category credit() {
            if (!creditLoaded) {
                creditLoaded = true;
                credit = categoryService.findOrCreateDefault(memberId, CREDIT_SLUG)
                    .filter(c -> c.getKind() == CategoryKind.EXPENSE)
                    .orElse(null);
                if (credit == null) {
                    log.info("{} loan installments left alone: no credit category (member={})",
                        bank.provider(), memberId);
                }
            }
            return credit;
        }
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

    private void markFailed(SyncJob job, SidecarErrorCode code) {
        try {
            txTemplate.executeWithoutResult(status -> {
                Optional<S> current = sessions.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId());
                if (current.isEmpty()) {
                    log.info("{} sync session disappeared before failure was recorded (member={})",
                        bank.provider(), job.memberId());
                    return;
                }
                S session = current.get();
                if (!session.isSyncInFlight()) {
                    log.warn("{} sync failure ignored from state {} (member={}; code={})",
                        bank.provider(), session.getSyncStatus(), job.memberId(), code);
                    return;
                }
                session.markFailed(code, Instant.now());
                sessions.save(session);
            });
        } catch (RuntimeException ex) {
            log.error("Could not persist {} sync failure (member={}; code={})", bank.provider(), job.memberId(), code);
        }
    }

    R getStatus(Long memberId) {
        return sessions.findByMemberId(memberId)
            .map(this::toStatus)
            .orElseGet(inactiveStatus);
    }

    /**
     * Deletes the stored session. This only forgets the cookies in Picsou: the bank session is
     * not revoked (there is no confirmed logout call on the port) and may stay active until it
     * expires. Returns whether a stored session was there to delete. A login still in flight is
     * cancelled too, so an approval on the phone after this stores nothing.
     */
    boolean clearSession(Long memberId) {
        synchronized (sessionWriteLock) {
            authGenerations.merge(memberId, 1L, Long::sum);
            boolean removed = Boolean.TRUE.equals(txTemplate.execute(status -> {
                Optional<S> session = sessions.findByMemberIdForUpdate(memberId);
                session.ifPresent(sessions::delete);
                return session.isPresent();
            }));
            pendingLogins.values().removeIf(pending -> pending.memberId().equals(memberId));
            return removed;
        }
    }

    private R toStatus(S session) {
        return statusOf.apply(session, parseUnsupported(session.getUnsupportedContracts()));
    }

    private static String serializeUnsupported(List<UnsupportedContract> unsupported) {
        try {
            return JSON.writeValueAsString(unsupported);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not encode the unsupported contracts");
        }
    }

    private List<UnsupportedContract> parseUnsupported(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<UnsupportedContract> stored = JSON.readValue(json, UNSUPPORTED_TYPE);
            // A stored `null`, or a null entry, is as unreadable as malformed JSON: never an NPE.
            // Lists stored by an earlier version were not bounded: apply the write-side bounds on read.
            return stored == null ? List.of() : stored.stream()
                .filter(item -> item != null && item.externalId() != null && item.familyCode() != null)
                .map(item -> new UnsupportedContract(
                    limit(item.externalId(), MAX_UNSUPPORTED_ID, "?"),
                    unsupportedCode(item.familyCode()),
                    unsupportedCode(item.subFamilyCode())))
                .toList();
        } catch (JsonProcessingException ex) {
            log.warn("Stored {} unsupported-contract list is unreadable", bank.provider());
            return List.of();
        }
    }

    private CardNature natureOf(String nature) {
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

    private AccountType typeOf(String kind) {
        if (kind == null) {
            throw invalid("an account without a kind");
        }
        return switch (kind) {
            case "CURRENT_ACCOUNT" -> AccountType.CHECKING;
            case "SAVINGS", "TERM_DEPOSIT", "COOPERATIVE_SHARES" -> AccountType.SAVINGS;
            case "LIVRET_A" -> AccountType.LIVRET_A;
            case "LIVRET_JEUNE" -> AccountType.LIVRET_JEUNE;
            case "LEP" -> AccountType.LEP;
            case "LDDS" -> AccountType.LDDS;
            case "PEL" -> AccountType.PEL;
            case "PER" -> AccountType.PER;
            case "ASSURANCE_VIE" -> AccountType.ASSURANCE_VIE;
            case "LOAN" -> AccountType.LOAN;
            case "CARD" -> AccountType.CREDIT_CARD;
            // Never guess: an unknown kind means the format moved under us.
            default -> throw invalid("an unknown account kind");
        };
    }

    private String fallbackName(String kind, AccountType type) {
        if ("TERM_DEPOSIT".equals(kind)) {
            return "Compte à terme";
        }
        if ("COOPERATIVE_SHARES".equals(kind)) {
            return "Parts sociales";
        }
        return switch (type) {
            case CREDIT_CARD -> bank.cardName();
            case SAVINGS -> "Compte sur livret";
            case LIVRET_A -> "Livret A";
            case LIVRET_JEUNE -> "Livret Jeune";
            case LEP -> "LEP";
            case LDDS -> "LDDS";
            case PEL -> "PEL";
            case PER -> "PER";
            case ASSURANCE_VIE -> "Assurance vie";
            case LOAN -> bank.loanName();
            default -> bank.accountName();
        };
    }

    private static String colorFor(AccountType type) {
        return switch (type) {
            // Same colour for every savings book, as the Bourso connector does.
            case SAVINGS, LIVRET_A, LIVRET_JEUNE, LEP, LDDS, PEL -> "#f59e0b";
            case PER, ASSURANCE_VIE -> "#8b5cf6";
            case LOAN -> "#64748b";
            case CREDIT_CARD -> "#2563eb";
            default -> "#dc2626";
        };
    }

    private String prefixed(String raw, String problem) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            throw invalid(problem);
        }
        String prefix = bank.externalIdPrefix();
        String externalId = cleaned.startsWith(prefix) ? cleaned : prefix + cleaned;
        if (externalId.length() > MAX_EXTERNAL_ID_LENGTH) {
            throw invalid(problem);
        }
        return externalId;
    }

    /** The sidecar's code, or UPSTREAM_UNAVAILABLE when it is unknown or this bank never declared it. */
    private SidecarErrorCode codeOf(SyncException exception) {
        return SyncValues.errorCode(exception, codes, SidecarErrorCode.UPSTREAM_UNAVAILABLE);
    }

    private static SyncException error(SidecarErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    private SyncException invalid(String what) {
        return error(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED, bank.provider() + " returned " + what, null);
    }

    /** Null when no code was sent, the code when it is short and plain, else `?`. */
    private static String unsupportedCode(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            return null;
        }
        return UNSUPPORTED_CODE.matcher(cleaned).matches() ? cleaned : "?";
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

    private static <T> T requireTransactionResult(T value) {
        return Objects.requireNonNull(value, "Transaction callback returned no result");
    }

    /** The pending login to show: the bank's keypad, clicked by the user, and its lifetime. */
    public record InitiateResponse(String processId, BpcePort.Keypad keypad, int expiresInSeconds) {}

    /**
     * A contract the sidecar saw but Picsou does not import: counted in the status, never created.
     * {@code subFamilyCode} is null when the bank sends none, and on lists stored before it was recorded.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UnsupportedContract(String externalId, String familyCode, String subFamilyCode) {}

    /**
     * A login in flight. Only one of the two deadlines is set: the keypad's while the user clicks,
     * the approval window's once the digits were sent. Both are nulled out with the process id, so
     * nothing about the pad survives a step.
     */
    private record PendingLogin<S>(
        Long memberId, Instant keypadExpiresAt, Instant approvalExpiresAt, SessionFactory<S> sessionFactory,
        long generation
    ) {
        Instant deadline() {
            return approvalExpiresAt != null ? approvalExpiresAt : keypadExpiresAt;
        }
    }

    private record QueueDecision<R>(SyncJob job, R status, SyncException failure) {}
    private record SyncJob(Long sessionId, Long memberId, String plainState, Branding branding) {}
    private record PreparedSnapshot(List<PreparedAccount> accounts, List<UnsupportedContract> unsupported) {}
    private record PreparedAccount(
        String externalId,
        String name,
        AccountType type,
        BigDecimal balance,
        CardNature cardNature,
        String parentExternalId,
        LocalDate nextDueDate,
        List<PreparedTransaction> transactions,
        /** Never stored on the account; only used to find a loan's debited account. */
        String iban,
        boolean ibanAmbiguous,
        /** LOAN only, and only when every field the schedule needs is usable; else null. */
        BpcePort.LoanDetails loan,
        /** PER only, and only when the insurer valuation was complete; null keeps existing holdings. */
        CaisseEpargnePerService.Prepared per
    ) {}
    private record PreparedTransaction(
        String externalId, LocalDate date, LocalDate dueDate, BigDecimal amount, String label, String typeCode
    ) {}
}
