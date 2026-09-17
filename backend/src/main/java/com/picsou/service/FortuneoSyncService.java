package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.FortuneoSession;
import com.picsou.model.FortuneoSyncStatus;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.port.FortuneoErrorCode;
import com.picsou.port.FortuneoPort;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.FortuneoSessionRepository;
import com.picsou.repository.TransactionRepository;
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
import java.util.concurrent.RejectedExecutionException;

@Service
public class FortuneoSyncService {
    private static final Logger log = LoggerFactory.getLogger(FortuneoSyncService.class);
    static final String PROVIDER = "Fortuneo";
    private static final BigDecimal ABSOLUTE_RECONCILIATION_TOLERANCE = new BigDecimal("0.05");
    private static final BigDecimal RELATIVE_RECONCILIATION_TOLERANCE = new BigDecimal("0.001");
    private static final Set<AccountType> INVESTMENT_TYPES = Set.of(AccountType.PEA, AccountType.COMPTE_TITRES);
    private static final Set<AccountType> CASH_TYPES = Set.of(AccountType.CHECKING, AccountType.SAVINGS);
    private static final int TRANSACTION_WINDOW_DAYS = 90;

    private final FortuneoPort port;
    private final FortuneoSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final FortuneoTransactionWriter transactionWriter;
    private final TransactionRepository transactionRepository;
    private final FamilyMemberRepository memberRepository;
    private final AccountService accountService;
    private final PriceService priceService;
    private final BalanceHistoryReconstructor historyReconstructor;
    private final OpenFigiIsinConverter isinConverter;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final Executor syncExecutor;

    public FortuneoSyncService(
        FortuneoPort port,
        FortuneoSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        FortuneoTransactionWriter transactionWriter,
        TransactionRepository transactionRepository,
        FamilyMemberRepository memberRepository,
        AccountService accountService,
        PriceService priceService,
        BalanceHistoryReconstructor historyReconstructor,
        OpenFigiIsinConverter isinConverter,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("fortuneoSyncExecutor") Executor syncExecutor
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.transactionWriter = transactionWriter;
        this.transactionRepository = transactionRepository;
        this.memberRepository = memberRepository;
        this.accountService = accountService;
        this.priceService = priceService;
        this.historyReconstructor = historyReconstructor;
        this.isinConverter = isinConverter;
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.syncExecutor = syncExecutor;
    }

    public AuthInitResponse initiateAuth(String login, String password, Long memberId) {
        FortuneoPort.InitiateResult result = port.initiateAuth(login, password);
        if (!result.mfaRequired()) {
            if (result.sessionState() == null || result.sessionState().isBlank()) {
                throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo did not return a session", null);
            }
            storeSessionAndQueue(result.sessionState(), memberId);
        }
        return new AuthInitResponse(result.processId(), result.mfaRequired(), result.mfaType());
    }

    public SessionStatusResponse completeAuth(String processId, String code, Long memberId) {
        String plainState = port.completeAuth(processId, code);
        if (plainState == null || plainState.isBlank()) {
            throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo did not return a session", null);
        }
        return storeSessionAndQueue(plainState, memberId);
    }

    public SessionStatusResponse queueSync(Long memberId) {
        QueueDecision decision = requireTransactionResult(txTemplate.execute(status -> {
            FortuneoSession session = sessionRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> error(
                    FortuneoErrorCode.SESSION_EXPIRED,
                    "No active Fortuneo session. Please reconnect.",
                    null
                ));
            if (!session.isActive()) {
                throw error(
                    FortuneoErrorCode.SESSION_EXPIRED,
                    "The Fortuneo session expired. Please reconnect.",
                    null
                );
            }
            if (session.getSyncStatus() == FortuneoSyncStatus.QUEUED
                || session.getSyncStatus() == FortuneoSyncStatus.RUNNING) {
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

            FortuneoSession newSession = FortuneoSession.create(
                member,
                encryption.encrypt(plainState),
                Instant.now()
            );
            newSession.markQueued();
            FortuneoSession stored = sessionRepository.saveAndFlush(newSession);
            return new SyncJob(stored.getId(), memberId, plainState);
        }));

        submit(job);
        return getStatus(memberId);
    }

    private void submit(SyncJob job) {
        try {
            syncExecutor.execute(() -> executeJob(job));
        } catch (RejectedExecutionException ex) {
            log.warn("Fortuneo sync executor rejected a job (member={})", job.memberId(), ex);
            failSubmission(job, ex);
        } catch (RuntimeException ex) {
            log.error("Fortuneo sync job submission failed (member={})", job.memberId(), ex);
            failSubmission(job, ex);
        }
    }

    private void failSubmission(SyncJob job, RuntimeException cause) {
        // The QUEUED write has already committed so the worker can observe it. If the
        // executor did not accept a real task, immediately transition that exact fenced
        // session to FAILED; otherwise the UI would poll a phantom job forever.
        markFailed(job, FortuneoErrorCode.INTERNAL_ERROR);
        throw error(
            FortuneoErrorCode.INTERNAL_ERROR,
            "Could not schedule the Fortuneo synchronization",
            cause
        );
    }

    private void executeJob(SyncJob job) {
        if (!markRunning(job)) {
            return;
        }
        try {
            List<FortuneoPort.AccountData> fetched = port.fetchAccounts(job.plainState());
            List<PreparedAccount> prepared = prepareAccounts(fetched);
            backfillPrices(prepared);
            if (commitPortfolio(job, prepared)) {
                log.info("Fortuneo sync completed (member={})", job.memberId());
            } else {
                log.info("Discarded stale Fortuneo sync result (member={})", job.memberId());
            }
        } catch (SyncException ex) {
            FortuneoErrorCode code = codeOf(ex);
            markFailed(job, code);
            log.warn("Fortuneo sync failed (member={}; code={})", job.memberId(), code);
        } catch (Exception ex) {
            markFailed(job, FortuneoErrorCode.INTERNAL_ERROR);
            log.error("Fortuneo sync failed unexpectedly (member={})", job.memberId(), ex);
        }
    }

    private boolean markRunning(SyncJob job) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<FortuneoSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("Fortuneo sync session disappeared before execution (member={})", job.memberId());
                return false;
            }
            FortuneoSession session = current.get();
            if (!session.isActive() || session.getSyncStatus() != FortuneoSyncStatus.QUEUED) {
                log.warn(
                    "Fortuneo sync cannot start from state {} (member={}; active={})",
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

    private List<PreparedAccount> prepareAccounts(List<FortuneoPort.AccountData> fetched) {
        if (fetched == null || fetched.isEmpty()) {
            throw error(
                FortuneoErrorCode.PORTFOLIO_INCOMPLETE,
                "Fortuneo returned no complete portfolio accounts",
                null
            );
        }

        Set<String> externalIds = new HashSet<>();
        List<PreparedAccount> prepared = new ArrayList<>();
        for (FortuneoPort.AccountData account : fetched) {
            if (account == null || !account.snapshotComplete()) {
                throw error(
                    FortuneoErrorCode.PORTFOLIO_INCOMPLETE,
                    "Fortuneo returned an incomplete portfolio",
                    null
                );
            }
            String externalId = stableExternalId(account.externalId());
            if (!externalIds.add(externalId)) {
                throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned duplicate accounts", null);
            }
            if (!INVESTMENT_TYPES.contains(account.type()) && !CASH_TYPES.contains(account.type())) {
                throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned an unsupported account type", null);
            }
            if (account.balanceEur() == null || account.cashBalance() == null || account.positions() == null) {
                throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned incomplete account values", null);
            }

            List<PreparedPosition> positions = preparePositions(account.positions());
            BigDecimal investedAmount;
            if (CASH_TYPES.contains(account.type())) {
                // Cash accounts carry no positions: the whole balance is cash, and there is
                // nothing to reconcile against a sum of position valuations.
                if (!positions.isEmpty()) {
                    throw error(
                        FortuneoErrorCode.INVALID_DATA,
                        "Fortuneo returned positions for a cash account",
                        null
                    );
                }
                if (!moneyClose(account.cashBalance(), account.balanceEur())) {
                    throw error(
                        FortuneoErrorCode.INVALID_DATA,
                        "Fortuneo returned a cash account whose balance does not match its cash",
                        null
                    );
                }
                investedAmount = account.balanceEur();
            } else {
                BigDecimal positionValue = positions.stream()
                    .map(PreparedPosition::currentValueEur)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal expectedPositionValue = account.balanceEur().subtract(account.cashBalance());
                if (!moneyClose(positionValue, expectedPositionValue)) {
                    throw error(
                        FortuneoErrorCode.PORTFOLIO_INCOMPLETE,
                        "Fortuneo returned an incomplete portfolio",
                        null
                    );
                }
                investedAmount = investedAmount(account, positions);
            }

            List<FortuneoPort.Transaction> transactions = prepareTransactions(account.transactions());
            prepared.add(new PreparedAccount(
                externalId,
                limit(account.name(), 100, "Fortuneo account"),
                account.type(),
                account.balanceEur(),
                account.cashBalance(),
                investedAmount,
                positions,
                transactions
            ));
        }
        return List.copyOf(prepared);
    }

    private List<FortuneoPort.Transaction> prepareTransactions(List<FortuneoPort.Transaction> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<FortuneoPort.Transaction> prepared = new ArrayList<>(raw.size());
        for (FortuneoPort.Transaction tx : raw) {
            if (tx == null || tx.date() == null || tx.amount() == null) {
                throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned an incomplete transaction", null);
            }
            prepared.add(new FortuneoPort.Transaction(
                tx.date(),
                tx.label(),
                tx.amount(),
                tx.category(),
                normalizeExternalId(tx.externalId()),
                normalizeOptional(tx.type()),
                validatedTxType(tx.txType()),
                tx.quantity(),
                tx.unitPrice(),
                tx.fees(),
                normalizeIsin(tx.isin())
            ));
        }
        return List.copyOf(prepared);
    }

    private List<PreparedPosition> preparePositions(List<FortuneoPort.Position> rawPositions) {
        Map<String, PreparedPosition> positions = new LinkedHashMap<>();
        for (FortuneoPort.Position position : rawPositions) {
            if (position == null || position.quantity() == null || position.currentValueEur() == null) {
                throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned an incomplete position", null);
            }
            if (position.quantity().signum() == 0) {
                continue;
            }
            String currency = normalizeCurrency(position.quoteCurrency());
            if (position.currentPrice() != null && currency == null) {
                throw error(
                    FortuneoErrorCode.INVALID_DATA,
                    "Fortuneo returned a quote without its currency",
                    null
                );
            }
            String ticker = resolveTicker(position);
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
        return positions.values().stream()
            .filter(position -> position.quantity().signum() != 0)
            .toList();
    }

    private String resolveTicker(FortuneoPort.Position position) {
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
            throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned an invalid instrument identifier", null);
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
            throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned an invalid ISIN", null);
        }
        return isin;
    }

    private PreparedPosition mergePositions(PreparedPosition left, PreparedPosition right) {
        if (!Objects.equals(left.quoteCurrency(), right.quoteCurrency())) {
            throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned conflicting quote currencies", null);
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
        FortuneoPort.AccountData account,
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

    /**
     * Fetches a year of daily prices for the instruments this sync is about to import.
     *
     * <p>{@code BalanceHistoryReconstructor} values a past day from {@code price_snapshot} and
     * skips any day one held instrument has no price for, so without this a first sync
     * reconstructs nothing at all: the only thing that ever filled that table was
     * {@code PriceBackfillRunner}, which runs once at startup over the tickers held *then* and
     * therefore cannot know about an account connected afterwards. The PEA's chart stayed a
     * single point until the app was restarted and the account synced a second time.
     *
     * <p>Sold-out instruments are included alongside the ones still held: the reconstruction
     * replays the ledger from its first trade, so a line bought and closed last spring is held on
     * those days and its missing price would drop them from the curve entirely.
     *
     * <p>Runs before the commit, outside any transaction: these are calls out to a market-data
     * provider, and holding a database transaction open across them would pin a connection for
     * their whole duration. A failure is logged and swallowed — prices are what the history is
     * drawn from, not what the portfolio import is, and refusing a good sync over an unreachable
     * price API would trade a complete chart for no data whatsoever.
     */
    private void backfillPrices(List<PreparedAccount> prepared) {
        Set<String> tickers = new HashSet<>();
        for (PreparedAccount account : prepared) {
            for (PreparedPosition position : account.positions()) {
                addTicker(tickers, position.ticker());
            }
            for (FortuneoPort.Transaction tx : account.transactions()) {
                addTicker(tickers, tickerOf(tx));
            }
        }
        if (tickers.isEmpty()) {
            return;
        }
        try {
            int saved = priceService.backfillHistoricalPrices(tickers, LocalDate.now().minusMonths(12));
            log.info("Fortuneo backfilled {} price(s) for {} instrument(s)", saved, tickers.size());
        } catch (RuntimeException ex) {
            log.warn("Fortuneo price backfill failed; the reconstructed history will be shorter", ex);
        }
    }

    private static void addTicker(Set<String> tickers, String ticker) {
        if (ticker != null && !ticker.isBlank()) {
            tickers.add(ticker.trim());
        }
    }

    private boolean commitPortfolio(SyncJob job, List<PreparedAccount> prepared) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<FortuneoSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("Fortuneo sync session disappeared before commit (member={})", job.memberId());
                return false;
            }
            FortuneoSession session = current.get();
            if (!session.isActive()) {
                log.warn("Fortuneo sync session became inactive before commit (member={})", job.memberId());
                return false;
            }
            if (session.getSyncStatus() != FortuneoSyncStatus.RUNNING) {
                log.warn(
                    "Fortuneo sync cannot commit from state {} (member={})",
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
            log.info("Fortuneo skipped a soft-deleted account (member={})", memberId);
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

        syncTransactions(savedAccount, data.transactions());

        accountService.upsertSnapshot(
            savedAccount,
            data.balanceEur(),
            data.investedAmountEur(),
            LocalDate.now()
        );

        // A sync writes one point, dated today, so an account's chart starts the day it was
        // connected however much history the provider returned. The ledger just imported can
        // establish the days before it -- see BalanceHistoryReconstructor for what it will and
        // will not infer.
        historyReconstructor.reconstruct(savedAccount, data.balanceEur(), LocalDate.now());
    }

    /**
     * Accepts only a transaction type Picsou actually defines.
     *
     * <p>{@code null} is a legitimate value -- the sidecar leaves an operation untyped rather
     * than guessing at one it does not recognise. An unknown *non-null* value is a different
     * matter: it means the two sides disagree on the contract, and silently dropping it would
     * hide that while quietly changing what the ledger reports.
     */
    private String validatedTxType(String txType) {
        String cleaned = normalizeOptional(txType);
        if (cleaned == null) {
            return null;
        }
        try {
            return TransactionType.valueOf(cleaned).name();
        } catch (IllegalArgumentException exc) {
            throw error(
                FortuneoErrorCode.INVALID_DATA,
                "Fortuneo returned an unknown transaction type",
                exc
            );
        }
    }

    private static String normalizeExternalId(String externalId) {
        return normalizeOptional(externalId);
    }

    /**
     * Trims an optional provider string, mapping blank or oversized values to {@code null}.
     *
     * <p>Distinct from {@link #limit}, which substitutes a fallback label: here {@code null}
     * is a meaningful value (no id, no type) and must survive rather than become a placeholder.
     */
    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            return null;
        }
        return trimmed;
    }

    /**
     * Imports the transactions of one account.
     *
     * <p>When every entry carries Fortuneo's stable identifier the whole history is imported
     * and reconciled by id, so a first sync backfills everything the provider still returns and
     * later syncs update the same rows instead of appending copies. Without ids there is no way
     * to tell a re-sent row from a new one, so the historical rolling-window replacement is kept
     * -- degrading is strictly safer than deduplicating on a guessed key.
     */
    private void syncTransactions(Account account, List<FortuneoPort.Transaction> transactions) {
        if (transactions.isEmpty()) {
            // Deliberately leaves existing rows alone rather than clearing anything:
            // an empty response is far more likely to mean "nothing fetched" than
            // "every transaction was reversed", and wiping real history on a thin
            // response is not recoverable from here.
            return;
        }
        boolean allIdentified = transactions.stream().allMatch(tx -> tx.externalId() != null);
        if (allIdentified) {
            reconcileFullHistory(account, transactions);
            return;
        }
        log.info(
            "Fortuneo returned transactions without stable ids; "
            + "falling back to the {}-day window import",
            TRANSACTION_WINDOW_DAYS
        );
        replaceRecentTransactions(account, transactions);
    }

    /**
     * Reconciles the reported history against what is stored, keyed on the provider's id.
     *
     * <p>Deletion is deliberately bounded to the date range the response covers. A partial
     * response -- Fortuneo answering with only the last few entries, or only one page --
     * therefore rewrites just the days it covers and cannot erase older history it never
     * mentioned. Rows carrying no id inside that range are dropped too: those are leftovers from
     * the pre-id window import that the response re-sends with an id, and keeping them would
     * duplicate every one of them.
     *
     * <p>The range runs from the oldest reported entry to <em>today</em>, not to the newest
     * reported one. This call fetches the provider's whole visible feed, so it is authoritative
     * up to now: a stored row more recent than anything reported is one the provider has stopped
     * showing -- a reversal, or a pending entry that settled under a new id -- and keeping it
     * would strand a duplicate forever. <strong>A future chunked backfill must not reuse this
     * rule</strong>: a chunk covering only 2024 is authoritative for 2024 alone, and must pass
     * its own explicit upper bound instead, or it would erase everything after it.
     */
    private void reconcileFullHistory(Account account, List<FortuneoPort.Transaction> transactions) {
        Map<String, FortuneoPort.Transaction> reported = new LinkedHashMap<>();
        for (FortuneoPort.Transaction tx : transactions) {
            // A provider repeating an id inside one response is not a reason to fail the sync;
            // the last occurrence wins, exactly as a follow-up sync would resolve it.
            reported.put(tx.externalId(), tx);
        }
        LocalDate from = transactions.stream().map(FortuneoPort.Transaction::date)
            .min(LocalDate::compareTo).orElseThrow();
        LocalDate to = LocalDate.now();

        List<Transaction> stored = transactionRepository.findByAccountIdAndIsManualFalse(account.getId());
        Map<String, Transaction> storedById = new LinkedHashMap<>();
        List<Transaction> obsolete = new ArrayList<>();
        for (Transaction row : stored) {
            String externalId = row.getExternalId();
            if (externalId != null && reported.containsKey(externalId)) {
                storedById.put(externalId, row);
                continue;
            }
            if (!row.getDate().isBefore(from) && !row.getDate().isAfter(to)) {
                obsolete.add(row);
            }
        }

        List<Transaction> upserts = new ArrayList<>(reported.size());
        for (Map.Entry<String, FortuneoPort.Transaction> entry : reported.entrySet()) {
            FortuneoPort.Transaction tx = entry.getValue();
            String description = limit(tx.label(), 255, "Fortuneo transaction");
            Transaction row = storedById.get(entry.getKey());
            if (row == null) {
                row = Transaction.builder()
                    .account(account)
                    .externalId(entry.getKey())
                    .date(tx.date())
                    .description(description)
                    .amount(tx.amount())
                    .category(tx.category())
                    .type(tx.type())
                    .txType(txTypeOf(tx))
                    .ticker(tickerOf(tx))
                    .name(instrumentNameOf(tx))
                    .quantity(tx.quantity())
                    .pricePerUnit(tx.unitPrice())
                    .fees(tx.fees())
                    .nativeCurrency("EUR")
                    .build();
            } else {
                row.setDate(tx.date());
                row.setDescription(description);
                row.setAmount(tx.amount());
                row.setCategory(tx.category());
                row.setType(tx.type());
                row.setTxType(txTypeOf(tx));
                row.setTicker(tickerOf(tx));
                row.setName(instrumentNameOf(tx));
                row.setQuantity(tx.quantity());
                row.setPricePerUnit(tx.unitPrice());
                row.setFees(tx.fees());
            }
            upserts.add(row);
        }
        transactionWriter.reconcileHistory(obsolete, upserts);
    }

    /**
     * Replaces the trailing {@link #TRANSACTION_WINDOW_DAYS}-day window of non-manual
     * transactions on every sync, keeping older history and any user-entered rows intact.
     * Mirrors BoursoSyncService's transaction replacement strategy.
     */
    private void replaceRecentTransactions(Account account, List<FortuneoPort.Transaction> transactions) {
        if (transactions.isEmpty()) {
            // Deliberately leaves existing rows alone rather than clearing the window:
            // an empty response is far more likely to mean "nothing fetched" than
            // "every recent transaction was reversed", and wiping real history on a
            // thin response is not recoverable from here.
            return;
        }
        LocalDate cutoff = LocalDate.now().minusDays(TRANSACTION_WINDOW_DAYS);
        // Delete only inside the window. Deleting every non-manual row and re-saving
        // the older ones (BoursoSyncService's approach) re-saves managed entities whose
        // rows have just been deleted, which merges onto a missing row and throws
        // StaleObjectStateException when older history must be retained.
        // Insert only what was deleted. The provider returns more history than the
        // window, and inserting it all while deleting only inside the window appends a
        // fresh copy of every older row on each sync, as reproduced by the
        // regression test below.
        List<Transaction> toInsert = transactions.stream()
            .filter(tx -> !tx.date().isBefore(cutoff))
            .map(tx -> Transaction.builder()
                .account(account)
                .date(tx.date())
                .description(limit(tx.label(), 255, "Fortuneo transaction"))
                .amount(tx.amount())
                .category(tx.category())
                .type(tx.type())
                .txType(txTypeOf(tx))
                .ticker(tickerOf(tx))
                .name(instrumentNameOf(tx))
                .quantity(tx.quantity())
                .pricePerUnit(tx.unitPrice())
                .fees(tx.fees())
                .nativeCurrency("EUR")
                .build())
            .toList();
        transactionWriter.replaceRecentTransactions(account.getId(), cutoff, toInsert);
    }

    /**
     * The prepared record holds the type as the validated string it was checked against, so
     * this re-parse cannot fail: {@link #validatedTxType} already rejected anything unknown.
     */
    private static TransactionType txTypeOf(FortuneoPort.Transaction tx) {
        return tx.txType() == null ? null : TransactionType.valueOf(tx.txType());
    }

    /**
     * Resolves the instrument a ledger row concerns, reusing the same ISIN converter the
     * positions go through so a trade and the holding it built land on the same ticker --
     * which is what lets {@code RealizedPnlService} pair them at all.
     *
     * <p>Falls back to the ISIN itself when the converter has no answer: an unresolvable
     * instrument is still a stable key, and losing it entirely would silently drop the row
     * from every per-instrument computation.
     */
    private String tickerOf(FortuneoPort.Transaction tx) {
        if (tx.isin() == null) {
            return null;
        }
        OpenFigiIsinConverter.TickerResult resolved = isinConverter.resolve(tx.isin());
        if (resolved != null && resolved.ticker() != null && !resolved.ticker().isBlank()) {
            return limit(resolved.ticker().trim(), 30, tx.isin());
        }
        return limit(tx.isin(), 30, tx.isin());
    }

    /** The provider's own label for the instrument, kept only where there is an instrument. */
    private String instrumentNameOf(FortuneoPort.Transaction tx) {
        return tx.isin() == null ? null : limit(tx.label(), 100, "Fortuneo instrument");
    }

    private String colorFor(AccountType type) {
        return switch (type) {
            case PEA -> "#10b981";
            case COMPTE_TITRES -> "#3b82f6";
            case CHECKING -> "#f59e0b";
            case SAVINGS -> "#8b5cf6";
            default -> "#6b7280";
        };
    }

    private void markFailed(SyncJob job, FortuneoErrorCode code) {
        try {
            txTemplate.executeWithoutResult(status -> {
                Optional<FortuneoSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                    job.sessionId(),
                    job.memberId()
                );
                if (current.isEmpty()) {
                    log.info("Fortuneo sync session disappeared before failure was recorded (member={})", job.memberId());
                    return;
                }
                FortuneoSession session = current.get();
                if (!session.isSyncInFlight()) {
                    log.warn(
                        "Fortuneo sync failure ignored from state {} (member={}; code={})",
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
                "Could not persist Fortuneo sync failure (member={}; code={})",
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
        Instant completedAt = Instant.now();
        int recovered = 0;
        for (FamilyMember member : memberRepository.findAllByOrderByCreatedAtAsc()) {
            recovered += sessionRepository.markInterruptedSyncsFailed(
                member.getId(),
                List.of(FortuneoSyncStatus.QUEUED, FortuneoSyncStatus.RUNNING),
                FortuneoSyncStatus.FAILED,
                completedAt,
                FortuneoErrorCode.INTERNAL_ERROR
            );
        }
        if (recovered > 0) {
            log.warn("Recovered {} interrupted Fortuneo sync job(s)", recovered);
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
            log.debug("Member disappeared before scheduled Fortuneo sync (member={})", memberId);
        } catch (DataAccessException ex) {
            log.error("Database error during scheduled Fortuneo sync (member={})", memberId, ex);
        } catch (SyncException ex) {
            log.warn(
                "Could not queue scheduled Fortuneo sync (member={}; code={})",
                memberId,
                codeOf(ex),
                ex
            );
        } catch (RuntimeException ex) {
            log.error("Unexpected scheduled Fortuneo sync failure (member={})", memberId, ex);
        }
    }

    private SessionStatusResponse toStatus(FortuneoSession session) {
        return new SessionStatusResponse(
            session.isActive(),
            null,
            session.getSyncStatus(),
            session.getLastSyncStartedAt(),
            session.getLastSyncCompletedAt(),
            session.getLastSyncError()
        );
    }

    private FortuneoErrorCode codeOf(SyncException exception) {
        if (exception.getCode() == null) {
            return FortuneoErrorCode.UPSTREAM_UNAVAILABLE;
        }
        try {
            return FortuneoErrorCode.valueOf(exception.getCode());
        } catch (IllegalArgumentException ignored) {
            return FortuneoErrorCode.UPSTREAM_UNAVAILABLE;
        }
    }

    private SyncException error(FortuneoErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    private String stableExternalId(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned an invalid account identifier", null);
        }
        String externalId = cleaned.startsWith("ft_") ? cleaned : "ft_" + cleaned;
        if (externalId.length() > 100) {
            throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned an invalid account identifier", null);
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
            throw error(FortuneoErrorCode.INVALID_DATA, "Fortuneo returned an invalid quote currency", null);
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
        FortuneoSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        FortuneoErrorCode lastSyncError
    ) {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, null, FortuneoSyncStatus.IDLE, null, null, null);
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
        List<PreparedPosition> positions,
        List<FortuneoPort.Transaction> transactions
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
