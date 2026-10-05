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
import com.picsou.service.sync.SourceSyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

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
    private static final String POSTED_TX_ID_PREFIX = "amex_tx_";
    /**
     * A pending charge is stored under its own prefix, the only rows a routine sync may delete:
     * AMEX reports it again under a posted identity once it settles, or drops a cancelled hold.
     */
    private static final String PENDING_TX_ID_PREFIX = "amex_txp_";
    /** Routine syncs only upsert reported rows dated inside this trailing window. */
    private static final int TRANSACTION_WINDOW_DAYS = 90;
    /**
     * AMEX usually settles a charge within a few business days; a week also covers a weekend
     * and bank holidays. Requiring the exact amount keeps that window from pairing unrelated
     * charges.
     */
    private static final int SETTLEMENT_WINDOW_DAYS = 7;
    /** How recent a row from the earlier id format must be to be treated as possibly pending. */
    private static final int LEGACY_PENDING_WINDOW_DAYS = 7;
    private static final Pattern CURRENT_TX_ID = Pattern.compile("^amex_txp?_[0-9a-f]{32}$");

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
                prepareTransactions(account.transactions()),
                account.pendingComplete()
            ));
        }
        return List.copyOf(prepared);
    }

    private LocalDate parseOptionalDate(String value) {
        if (value == null || value.length() < 10) return null;
        try { return LocalDate.parse(value.substring(0, 10)); }
        catch (DateTimeException ex) { return null; }
    }

    /**
     * Validates the sidecar's raw transaction list and parses each date, rejecting the whole
     * sync on a malformed row rather than silently dropping money movements.
     */
    private List<PreparedTransaction> prepareTransactions(List<AmexPort.Transaction> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<PreparedTransaction> prepared = new ArrayList<>(raw.size());
        Map<String, Integer> occurrences = new HashMap<>();
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
            String label = limit(tx.label(), 255, "American Express transaction");
            // Two identical purchases on the same day (two metro tickets) are two rows: number
            // each repeat of a tuple in response order so both survive and a re-sync of the
            // same data maps back onto the same ids.
            String externalId = tx.externalId();
            if (externalId == null) {
                String base = identity(tx.pending(), date, label, tx.amountEur(), 0);
                externalId = identity(tx.pending(), date, label, tx.amountEur(), occurrences.merge(base, 1, Integer::sum) - 1);
            }
            prepared.add(new PreparedTransaction(externalId, date, label, tx.amountEur()));
        }
        return List.copyOf(prepared);
    }

    private String identity(boolean pending, LocalDate date, String description, BigDecimal amount, int occurrence) {
        String key = date + "|" + description + "|" + amount.stripTrailingZeros().toPlainString() + "|" + occurrence;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return (pending ? PENDING_TX_ID_PREFIX : POSTED_TX_ID_PREFIX) + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JDK; unreachable on any supported runtime.
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
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

        syncTransactions(savedAccount, data.transactions(), data.pendingComplete(), history);

    }

    /**
     * Regular sync upserts the 90-day window and deletes only stored pending charges the pending
     * feed no longer reports. Recovery instead imports the full bounded history returned by AMEX
     * and merges without updating or deleting existing/manual rows, except stored pending charges
     * an answered pending feed no longer reports, purged exactly as a routine sync does.
     */
    private void syncTransactions(
        Account account,
        List<PreparedTransaction> transactions,
        boolean pendingComplete,
        boolean history
    ) {
        if (transactions.isEmpty()) return;
        Set<String> returned = new HashSet<>();
        transactions.forEach(tx -> returned.add(tx.externalId()));
        List<Transaction> stored = rekeyLegacyRows(
            account, transactionRepository.findByAccountIdAndIsManualFalse(account.getId()), returned);
        // The posted feed is one page, cut on a date that is not the one rows carry, so a posted
        // row missing from it proves nothing: posted rows are never deleted here. A stored
        // pending charge the pending feed no longer returns has settled or was cancelled, but
        // only an answered pending feed says so.
        List<Transaction> obsolete = new ArrayList<>();
        List<Transaction> storedPosted = new ArrayList<>();
        for (Transaction row : stored) {
            String externalId = row.getExternalId();
            if (externalId == null) continue;
            if (!externalId.startsWith(PENDING_TX_ID_PREFIX)) {
                storedPosted.add(row);
            } else if (pendingComplete && !returned.contains(externalId)) {
                obsolete.add(row);
            }
        }

        if (history) {
            Set<String> known = new HashSet<>();
            for (Transaction tx : stored) {
                known.add(tx.getExternalId() != null ? tx.getExternalId() : identity(false, tx.getDate(), tx.getDescription(), tx.getAmount(), 0));
            }
            List<Transaction> missing = new ArrayList<>(transactions.stream()
                .filter(tx -> known.add(tx.externalId()))
                .map(tx -> Transaction.builder().account(account).externalId(tx.externalId())
                    .date(tx.date()).description(tx.label()).amount(tx.amountEur()).nativeCurrency("EUR").build()).toList());
            addMissing(missing, carryOverSettledCharges(obsolete, postedRows(storedPosted, missing)));
            if (!obsolete.isEmpty()) {
                transactionRepository.deleteAll(obsolete);
                transactionRepository.flush();
            }
            transactionRepository.saveAllAndFlush(missing);
            return;
        }
        LocalDate cutoff = LocalDate.now().minusDays(TRANSACTION_WINDOW_DAYS);
        Map<String, PreparedTransaction> reported = new LinkedHashMap<>();
        for (PreparedTransaction tx : transactions) {
            if (!tx.date().isBefore(cutoff)) reported.putIfAbsent(tx.externalId(), tx);
        }
        Map<String, Transaction> storedById = new HashMap<>();
        for (Transaction row : stored) {
            if (row.getExternalId() != null && reported.containsKey(row.getExternalId())) {
                storedById.put(row.getExternalId(), row);
            }
        }

        List<Transaction> upserts = new ArrayList<>(reported.size());
        List<Transaction> inserted = new ArrayList<>();
        for (PreparedTransaction tx : reported.values()) {
            Transaction row = storedById.get(tx.externalId());
            if (row == null) {
                row = Transaction.builder()
                    .account(account)
                    .externalId(tx.externalId())
                    .nativeCurrency("EUR")
                    .build();
                inserted.add(row);
            }
            row.setDate(tx.date());
            row.setDescription(tx.label());
            row.setAmount(tx.amountEur());
            upserts.add(row);
        }
        addMissing(upserts, carryOverSettledCharges(obsolete, postedRows(storedPosted, inserted)));
        transactionWriter.reconcileHistory(obsolete, upserts);
    }

    /** Posted rows a settled pending may have become: stored on any earlier sync, or new now. */
    private static List<Transaction> postedRows(List<Transaction> storedPosted, List<Transaction> inserted) {
        List<Transaction> posted = new ArrayList<>(storedPosted);
        for (Transaction row : inserted) {
            if (!row.getExternalId().startsWith(PENDING_TX_ID_PREFIX)) posted.add(row);
        }
        return posted;
    }

    /** Appends the rows of {@code extra} not already in {@code target}, by identity. */
    private static void addMissing(List<Transaction> target, List<Transaction> extra) {
        Set<Transaction> present = Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(target);
        for (Transaction row : extra) {
            if (present.add(row)) target.add(row);
        }
    }

    /**
     * A settled charge comes back as a posted row while its pending row is deleted, so what the
     * user set on the pending row (category, recurring series) is copied onto the posted row it
     * became. That posted row may be new in this sync or stored by an earlier one: the pending
     * outlives it when the pending feed failed in between, or when a history import stored it.
     * Same date, label, amount and occurrence give the same hash suffix under both prefixes;
     * otherwise AMEX may have rewritten the label or moved the date on settlement, so the closest
     * posted row with the exact amount within {@link #SETTLEMENT_WINDOW_DAYS} that can still
     * receive the edit is taken, each row paired at most once. A posted row that already has a
     * category (or, for a pending with only a series, a series) is not a candidate: it is either
     * another pending's settlement or categorised by the user, and pairing it would copy nothing.
     *
     * @return the posted rows that received an edit, so the caller saves them
     */
    private List<Transaction> carryOverSettledCharges(List<Transaction> obsoletePending, List<Transaction> posted) {
        List<Transaction> edited = obsoletePending.stream().filter(AmexSyncService::hasUserFields).toList();
        if (edited.isEmpty() || posted.isEmpty()) return List.of();
        Map<String, Transaction> postedBySuffix = new HashMap<>();
        for (Transaction row : posted) {
            String suffix = identitySuffix(row.getExternalId(), POSTED_TX_ID_PREFIX);
            if (suffix != null) postedBySuffix.put(suffix, row);
        }
        Set<Transaction> paired = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Transaction> receivers = new ArrayList<>();
        for (Transaction pending : edited) {
            Transaction same = postedBySuffix.get(identitySuffix(pending.getExternalId(), PENDING_TX_ID_PREFIX));
            if (same != null) {
                carryUserFields(pending, same);
                paired.add(pending);
                paired.add(same);
                receivers.add(same);
            }
        }

        record Pair(Transaction pending, Transaction posted, long days) {}
        List<Pair> candidates = new ArrayList<>();
        for (Transaction pending : edited) {
            if (paired.contains(pending)) continue;
            for (Transaction row : posted) {
                long days = Math.abs(ChronoUnit.DAYS.between(pending.getDate(), row.getDate()));
                if (!paired.contains(row)
                    && row.getAmount().compareTo(pending.getAmount()) == 0
                    && days <= SETTLEMENT_WINDOW_DAYS
                    && canReceive(pending, row)) {
                    candidates.add(new Pair(pending, row, days));
                }
            }
        }
        candidates.sort(Comparator.comparingLong(Pair::days)
            .thenComparing(pair -> pair.pending().getDate())
            .thenComparing(pair -> pair.pending().getExternalId())
            .thenComparing(pair -> pair.posted().getExternalId()));
        for (Pair pair : candidates) {
            if (paired.contains(pair.pending()) || paired.contains(pair.posted())) continue;
            carryUserFields(pair.pending(), pair.posted());
            paired.add(pair.pending());
            paired.add(pair.posted());
            receivers.add(pair.posted());
        }
        return receivers;
    }

    private static boolean hasUserFields(Transaction row) {
        return row.getCategoryRef() != null || row.getRecurringSeriesId() != null;
    }

    private static boolean canReceive(Transaction pending, Transaction posted) {
        if (pending.getCategoryRef() != null) {
            return posted.getCategoryRef() == null && !posted.isCategoryManual();
        }
        return posted.getRecurringSeriesId() == null;
    }

    private static String identitySuffix(String externalId, String prefix) {
        return externalId != null && externalId.startsWith(prefix) ? externalId.substring(prefix.length()) : null;
    }

    /** Copies what is set on {@code from} and still unset on {@code to}; never overwrites. */
    private static void carryUserFields(Transaction from, Transaction to) {
        if (from.getCategoryRef() != null && to.getCategoryRef() == null && !to.isCategoryManual()) {
            to.setCategoryRef(from.getCategoryRef());
            to.setCategory(from.getCategory());
            to.setCategoryManual(from.isCategoryManual());
        }
        if (from.getRecurringSeriesId() != null && to.getRecurringSeriesId() == null) {
            to.setRecurringSeriesId(from.getRecurringSeriesId());
        }
    }

    /**
     * Rows synced before ids became SHA-256 based carry {@code amex_tx_} + a base-36 32-bit hash
     * and would never match a current id: every posted row would be inserted again, and a
     * pending one never purged. They are re-keyed once to the current identity, numbering
     * repeats of a tuple in date then row order as {@link #prepareTransactions} does. The old
     * format stored no pending state, so a legacy row dated within
     * {@link #LEGACY_PENDING_WINDOW_DAYS} that the response does not report under its posted id
     * is re-keyed as pending: the pending lifecycle then deletes it once an answered pending feed
     * stops reporting it, carrying its edits to the posted row it settled into. A real posted
     * charge that recent is on the latest 100-posting page unless over 100 charges posted
     * since, so the window keeps that risk out of reach. A legacy row whose new id is already
     * held is merged into the holder, keeping the holder and its edits.
     */
    private List<Transaction> rekeyLegacyRows(Account account, List<Transaction> stored, Set<String> returned) {
        List<Transaction> legacy = stored.stream()
            .filter(row -> row.getExternalId() != null
                && row.getExternalId().startsWith("amex_tx")
                && !CURRENT_TX_ID.matcher(row.getExternalId()).matches())
            .sorted(Comparator.comparing(Transaction::getDate)
                .thenComparing(Transaction::getId, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        if (legacy.isEmpty()) return stored;

        Set<Transaction> legacyRows = Collections.newSetFromMap(new IdentityHashMap<>());
        legacyRows.addAll(legacy);
        Map<String, Transaction> holders = new HashMap<>();
        for (Transaction row : stored) {
            if (row.getExternalId() != null && !legacyRows.contains(row)) holders.put(row.getExternalId(), row);
        }
        LocalDate pendingCutoff = LocalDate.now().minusDays(LEGACY_PENDING_WINDOW_DAYS);
        Map<String, Integer> occurrences = new HashMap<>();
        List<Transaction> rekeyed = new ArrayList<>();
        Set<Transaction> merged = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Transaction row : legacy) {
            String base = identity(false, row.getDate(), row.getDescription(), row.getAmount(), 0);
            int occurrence = occurrences.merge(base, 1, Integer::sum) - 1;
            String posted = identity(false, row.getDate(), row.getDescription(), row.getAmount(), occurrence);
            String pending = identity(true, row.getDate(), row.getDescription(), row.getAmount(), occurrence);
            boolean stillPending = returned.contains(pending)
                || (!returned.contains(posted) && !row.getDate().isBefore(pendingCutoff));
            String target = stillPending ? pending : posted;
            Transaction holder = holders.get(target);
            if (holder != null) {
                carryUserFields(row, holder);
                merged.add(row);
                continue;
            }
            row.setExternalId(target);
            holders.put(target, row);
            rekeyed.add(row);
        }
        if (!merged.isEmpty()) {
            transactionRepository.deleteAll(merged);
            transactionRepository.flush();
        }
        transactionRepository.saveAllAndFlush(rekeyed);
        log.info("American Express re-keyed {} earlier transaction id(s) and merged {} duplicate(s) (account={})",
            rekeyed.size(), merged.size(), account.getId());
        return stored.stream().filter(row -> !merged.contains(row)).toList();
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
        resyncReporting(memberId);
    }

    public SourceSyncResult resyncReporting(Long memberId) {
        try {
            SessionStatusResponse status = getStatus(memberId);
            if (!status.isActive()) {
                if (status.lastSyncError() == AmexErrorCode.SESSION_EXPIRED) {
                    return new SourceSyncResult("amex", SourceSyncResult.Status.NEEDS_REAUTH,
                        AmexErrorCode.SESSION_EXPIRED.name());
                }
                if (status.lastSyncError() != null) {
                    return new SourceSyncResult("amex", SourceSyncResult.Status.FAILED,
                        status.lastSyncError().name());
                }
                return new SourceSyncResult("amex", SourceSyncResult.Status.SKIPPED_NOT_CONNECTED, "No active session");
            }
            SessionStatusResponse queued = queueSync(memberId);
            String syncStatus = queued.syncStatus() == null ? "" : queued.syncStatus().name();
            if ("QUEUED".equals(syncStatus) || "RUNNING".equals(syncStatus)) {
                return new SourceSyncResult("amex", SourceSyncResult.Status.QUEUED, "");
            }
            if ("SUCCESS".equals(syncStatus)) {
                return new SourceSyncResult("amex", SourceSyncResult.Status.SYNCED, "");
            }
            if (queued.lastSyncError() == AmexErrorCode.SESSION_EXPIRED) {
                return new SourceSyncResult("amex", SourceSyncResult.Status.NEEDS_REAUTH,
                    AmexErrorCode.SESSION_EXPIRED.name());
            }
            if ("FAILED".equals(syncStatus)) {
                return new SourceSyncResult("amex", SourceSyncResult.Status.FAILED,
                    queued.lastSyncError() == null ? "Sync failed" : queued.lastSyncError().name());
            }
            return new SourceSyncResult("amex", SourceSyncResult.Status.SKIPPED, "Sync already complete");
        } catch (ResourceNotFoundException ex) {
            log.debug("Member disappeared before scheduled American Express sync (member={})", memberId);
            return new SourceSyncResult("amex", SourceSyncResult.Status.SKIPPED_NOT_CONNECTED, "Member not found");
        } catch (DataAccessException ex) {
            log.error("Database error during scheduled American Express sync (member={})", memberId, ex);
            return new SourceSyncResult("amex", SourceSyncResult.Status.FAILED, "Database error");
        } catch (SyncException ex) {
            log.warn(
                "Could not queue scheduled American Express sync (member={}; code={})",
                memberId,
                codeOf(ex),
                ex
            );
            return SourceSyncResult.fromSyncException("amex", ex);
        } catch (RuntimeException ex) {
            log.error("Unexpected scheduled American Express sync failure (member={})", memberId, ex);
            return new SourceSyncResult("amex", SourceSyncResult.Status.FAILED,
                "Unexpected sync error");
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
        List<PreparedTransaction> transactions,
        boolean pendingComplete
    ) {}
    private record PreparedTransaction(String externalId, LocalDate date, String label, BigDecimal amountEur) {}
}
