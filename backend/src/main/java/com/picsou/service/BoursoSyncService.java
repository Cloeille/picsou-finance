package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.model.FamilyMember;
import com.picsou.port.SidecarErrorCode;
import com.picsou.adapter.BoursoAdapter;
import com.picsou.port.SidecarInitiate;
import com.picsou.port.SidecarTransaction;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.SidecarSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.sync.SourceSyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * Imports BoursoBank current accounts, livrets, securities accounts and
 * assurance-vie contracts (unit-linked supports as holdings, the euro fund in
 * the cash leg).
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
    private static final List<String> PROVIDER_FAMILY = List.of(PROVIDER, "Boursorama", "Boursorama Banque");
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
        AccountType.COMPTE_TITRES,
        AccountType.ASSURANCE_VIE
    );

    private final BoursoAdapter port;
    private final SidecarSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final FamilyMemberRepository memberRepository;
    private final AccountService accountService;
    private final CategorizationService categorizationService;
    private final SharedSidecarTransactionImportService transactionImporter;
    private final OpenFigiIsinConverter isinConverter;
    private final SecurityIdentityService identityService;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final SidecarSyncTemplate<List<PreparedAccount>> sync;
    private final ReceivedDividendClassifier dividendClassifier;
    private final Map<String, PendingCredentials> pendingCredentials = new java.util.concurrent.ConcurrentHashMap<>();

    public BoursoSyncService(
        BoursoAdapter port,
        SidecarSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        FamilyMemberRepository memberRepository,
        AccountService accountService,
        CategorizationService categorizationService,
        SharedSidecarTransactionImportService transactionImporter,
        OpenFigiIsinConverter isinConverter,
        SecurityIdentityService identityService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("boursoSyncExecutor") Executor syncExecutor,
        ReceivedDividendClassifier dividendClassifier
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.memberRepository = memberRepository;
        this.accountService = accountService;
        this.categorizationService = categorizationService;
        this.transactionImporter = transactionImporter;
        this.isinConverter = isinConverter;
        this.identityService = identityService;
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.sync = new SidecarSyncTemplate<>(SidecarBank.BOURSO, new Strategy(), sessionRepository,
            memberRepository, encryption, txTemplate, syncExecutor, false);
        this.dividendClassifier = dividendClassifier;
    }

    private final class Strategy implements SidecarSyncStrategy<List<PreparedAccount>> {
        @Override
        public List<PreparedAccount> fetch(SidecarSyncJob job) {
            List<BoursoAdapter.AccountData> fetched;
            try {
                fetched = port.fetchAccounts(job.plainState());
            } catch (SyncException ex) {
                if (codeOf(ex) != SidecarErrorCode.SESSION_EXPIRED) throw ex;
                fetched = reauthenticateAndFetch(job);
            }
            return prepareAccounts(fetched);
        }

        @Override
        public void commit(SidecarSyncJob job, List<PreparedAccount> prepared, Instant syncedAt) {
            FamilyMember member = memberRepository.findById(job.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            CategorizationService.CategorizationContext categorizationContext =
                categorizationService.loadContext(job.memberId());
            for (PreparedAccount data : prepared) {
                upsertAccount(data, member, job.memberId(), syncedAt, categorizationContext);
            }
        }

        @Override
        public void afterCommit(SidecarSyncJob job, List<PreparedAccount> prepared) {
            dividendClassifier.afterImport(job.memberId());
        }

        @Override
        public String describe(List<PreparedAccount> prepared) {
            return "accounts=" + prepared.size();
        }

        @Override
        public void onFailure(SidecarSession session, SidecarErrorCode code) {
            if (code == SidecarErrorCode.INVALID_CREDENTIALS) {
                session.setEncryptedCredentials(null);
            }
        }
    }

    public AuthInitResponse initiateAuth(String customerId, String password, Long memberId) {
        SidecarInitiate result = port.initiateAuth(customerId, password);
        if (!result.mfaRequired()) {
            if (result.sessionState() == null || result.sessionState().isBlank()) {
                throw error(SidecarErrorCode.INVALID_DATA, "BoursoBank did not return a session", null);
            }
            storeSessionAndQueue(result.sessionState(), memberId, customerId, password);
        } else if (result.processId() != null) {
            pendingCredentials.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
            pendingCredentials.put(result.processId(), new PendingCredentials(customerId, password, Instant.now().plusSeconds(600), memberId));
        }
        return new AuthInitResponse(result.processId(), result.mfaRequired(), result.mfaType());
    }

    public SessionStatusResponse completeAuth(String processId, Long memberId) {
        String plainState = port.completeAuth(processId);
        PendingCredentials credentials = pendingCredentials.remove(processId);
        if (credentials != null && !credentials.memberId().equals(memberId)) {
            throw error(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED, "BoursoBank authentication attempt expired", null);
        }
        if (plainState == null || plainState.isBlank()) {
            throw error(SidecarErrorCode.INVALID_DATA, "BoursoBank did not return a session", null);
        }
        boolean credentialsValid = credentials != null && credentials.expiresAt().isAfter(Instant.now());
        return storeSessionAndQueue(plainState, memberId,
            credentialsValid ? credentials.customerId() : null,
            credentialsValid ? credentials.password() : null);
    }

    public SessionStatusResponse queueSync(Long memberId) {
        return toStatus(sync.queue(memberId, false));
    }

    private SessionStatusResponse storeSessionAndQueue(String plainState, Long memberId, String customerId, String password) {
        sync.replaceAndQueue(memberId, plainState, false, session -> {
            if (customerId != null && password != null) {
                session.setEncryptedCredentials(encryption.encrypt(serializeCredentials(customerId, password)));
            }
        });
        return getStatus(memberId);
    }

    private List<BoursoAdapter.AccountData> reauthenticateAndFetch(SidecarSyncJob job) {
        Optional<SidecarSession> found = requireTransactionResult(txTemplate.execute(
            status -> sessionRepository.findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
        ));
        Optional<SidecarSession> sessionResult = found;
        SidecarSession session = sessionResult.orElse(null);
        if (session == null || !session.isActive()
            || session.getSyncStatus() != SidecarSyncStatus.RUNNING
            || session.getEncryptedCredentials() == null) {
            throw error(SidecarErrorCode.SESSION_EXPIRED, "BoursoBank session expired", null);
        }
        Credentials credentials;
        try { credentials = parseCredentials(encryption.decrypt(session.getEncryptedCredentials())); }
        catch (RuntimeException ex) { throw error(SidecarErrorCode.SESSION_EXPIRED, "BoursoBank session expired", ex); }
        SidecarInitiate result;
        try {
            result = port.initiateAuth(credentials.customerId(), credentials.password());
        } catch (SyncException ex) {
            if (codeOf(ex) == SidecarErrorCode.INVALID_CREDENTIALS) {
                clearStoredCredentials(job);
                throw ex;
            }
            throw ex;
        }
        if (result.mfaRequired()) {
            throw error(SidecarErrorCode.SESSION_EXPIRED, "BoursoBank requires interactive validation", null);
        }
        if (result.sessionState() == null || result.sessionState().isBlank()) {
            throw error(SidecarErrorCode.INVALID_DATA, "BoursoBank returned no session", null);
        }
        String newState = result.sessionState();
        Boolean updated = txTemplate.execute(status -> sessionRepository
            .findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
            .filter(current -> current.isActive() && current.getSyncStatus() == SidecarSyncStatus.RUNNING)
            .map(current -> {
                current.updateSessionState(encryption.encrypt(newState), Instant.now());
                sessionRepository.save(current);
                return true;
            })
            .orElse(false));
        if (!Boolean.TRUE.equals(updated)) throw error(SidecarErrorCode.SESSION_EXPIRED, "BoursoBank session changed", null);
        return port.fetchAccounts(newState);
    }

    private void clearStoredCredentials(SidecarSyncJob job) {
        txTemplate.executeWithoutResult(status -> sessionRepository
            .findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
            .ifPresent(current -> {
                current.setEncryptedCredentials(null);
                sessionRepository.save(current);
            }));
    }

    private String serializeCredentials(String customerId, String password) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(Map.of("customerId", customerId, "password", password));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Could not encode credentials");
        }
    }

    private Credentials parseCredentials(String value) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
            String customerId = node.path("customerId").asText();
            String password = node.path("password").asText();
            if (customerId.isBlank() || password.isBlank()) throw new IllegalArgumentException("Invalid credentials");
            return new Credentials(customerId, password);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalArgumentException("Invalid credentials", ex); }
    }

    /**
     * Validates the sidecar's accounts before anything is written: a missing, incomplete, duplicate
     * or unsupported account refuses the whole sync, and a securities account must reconcile with its
     * balance so a truncated position list never overwrites a correct portfolio.
     */
    private List<PreparedAccount> prepareAccounts(List<BoursoAdapter.AccountData> fetched) {
        if (fetched == null || fetched.isEmpty()) {
            throw error(
                SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "BoursoBank returned no account",
                null
            );
        }

        Set<String> externalIds = new HashSet<>();
        List<PreparedAccount> prepared = new ArrayList<>();
        for (BoursoAdapter.AccountData account : fetched) {
            if (account == null || !account.snapshotComplete()) {
                throw error(
                    SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                    "BoursoBank returned an incomplete snapshot",
                    null
                );
            }
            String externalId = stableExternalId(account.externalId());
            if (!externalIds.add(externalId)) {
                throw error(SidecarErrorCode.INVALID_DATA, "BoursoBank returned duplicate accounts", null);
            }
            if (account.type() == null || !SUPPORTED_TYPES.contains(account.type())) {
                throw error(SidecarErrorCode.INVALID_DATA, "BoursoBank returned an unsupported account type", null);
            }
            if (account.balanceEur() == null || account.positions() == null) {
                throw error(SidecarErrorCode.INVALID_DATA, "BoursoBank returned incomplete account values", null);
            }

            boolean holdsPositions = account.type().isInvestment();
            if (!holdsPositions && !account.positions().isEmpty()) {
                throw error(SidecarErrorCode.INVALID_DATA, "BoursoBank reported positions on a cash account", null);
            }

            List<PreparedPosition> positions = preparePositions(account.positions());
            BigDecimal cashBalance = account.cashBalance();
            BigDecimal investedAmount;
            if (holdsPositions) {
                if (cashBalance == null) {
                    throw error(SidecarErrorCode.INVALID_DATA, "BoursoBank returned no cash balance for a securities account", null);
                }
                // Re-run the sidecar's reconciliation rather than trust it: this is
                // the check that stops a truncated position list being written over
                // a correct portfolio, and it costs nothing to repeat.
                BigDecimal positionValue = positions.stream()
                    .map(PreparedPosition::currentValueEur)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal expectedValue = account.balanceEur().subtract(cashBalance);
                boolean reconciled = account.type() == AccountType.ASSURANCE_VIE
                    ? SyncValues.linesClose(positionValue, expectedValue, valuationLineCount(account))
                    : moneyClose(positionValue, expectedValue);
                if (!reconciled) {
                    throw error(
                        SidecarErrorCode.PORTFOLIO_INCOMPLETE,
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
                positions,
                account.iban(),
                account.transactions() == null ? List.of() : account.transactions()
            ));
        }
        return List.copyOf(prepared);
    }

    private List<PreparedPosition> preparePositions(List<BoursoAdapter.Position> rawPositions) {
        Map<String, PreparedPosition> positions = new LinkedHashMap<>();
        Map<String, String> isinByTicker = new LinkedHashMap<>();
        for (BoursoAdapter.Position position : rawPositions) {
            if (position == null || position.quantity() == null || position.currentValueEur() == null) {
                throw error(SidecarErrorCode.INVALID_DATA, "BoursoBank returned an incomplete position", null);
            }
            if (position.quantity().signum() == 0) {
                continue;
            }
            String currency = normalizeCurrency(position.quoteCurrency());
            if (position.currentPrice() != null && currency == null) {
                throw error(
                    SidecarErrorCode.INVALID_DATA,
                    "BoursoBank returned a quote without its currency",
                    null
                );
            }
            String isin = normalizeIsin(position.isin());
            String ticker = resolveTicker(position, isin);
            // Only lines carrying the same ISIN may merge. A line without an ISIN cannot be
            // proven to be the same instrument, so it never merges. Two ISINs on one ticker
            // (OpenFIGI giving two share classes the same symbol) would otherwise become one
            // holding filed under a single ISIN, and fund facts and composition would then
            // be looked up for the wrong fund.
            if (positions.containsKey(ticker) && (isin == null || !isin.equals(isinByTicker.get(ticker)))) {
                throw error(SidecarErrorCode.INVALID_DATA, "BoursoBank returned two instruments under one ticker", null);
            }
            if (isin != null) {
                isinByTicker.put(ticker, isin);
            }
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
    private String resolveTicker(BoursoAdapter.Position position, String isin) {
        String ticker = clean(position.symbol());
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
            throw error(SidecarErrorCode.INVALID_DATA, "BoursoBank returned an invalid instrument identifier", null);
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
            throw error(SidecarErrorCode.INVALID_DATA, "BoursoBank returned an invalid ISIN", null);
        }
        return isin;
    }

    /**
     * Merges two lines that resolved to the same ticker and carry the same ISIN.
     *
     * <p>Not {@code HoldingDedup.vwapMerge}: that helper carries no provider
     * valuation, and dropping {@code providerValueEur}/{@code providerPnlEur}
     * here is what makes an unpriceable holding read as 0 EUR downstream.
     */
    private PreparedPosition mergePositions(PreparedPosition left, PreparedPosition right) {
        if (!Objects.equals(left.quoteCurrency(), right.quoteCurrency())) {
            throw error(SidecarErrorCode.INVALID_DATA, "BoursoBank returned conflicting quote currencies", null);
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
        BoursoAdapter.AccountData account,
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

    private static boolean isVerifiedIban(String iban) {
        if (iban == null) return false;
        String normalized = iban.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}")) return false;

        String rearranged = normalized.substring(4) + normalized.substring(0, 4);
        int remainder = 0;
        for (int index = 0; index < rearranged.length(); index++) {
            char character = rearranged.charAt(index);
            if (character >= '0' && character <= '9') {
                remainder = (remainder * 10 + character - '0') % 97;
            } else {
                remainder = (remainder * 100 + character - 'A' + 10) % 97;
            }
        }
        return remainder == 1;
    }

    private void upsertAccount(PreparedAccount data, FamilyMember member, Long memberId, Instant syncedAt,
                               CategorizationService.CategorizationContext categorizationContext) {
        Optional<Account> existing = accountRepository
            .findByExternalAccountIdAndMemberId(data.externalId(), memberId);
        boolean hasVerifiedIban = isVerifiedIban(data.iban());
        if (existing.isEmpty() && hasVerifiedIban) {
            existing = accountRepository.findFirstByIbanAndMemberIdAndProviderIn(
                data.iban(), memberId, PROVIDER_FAMILY);
        }
        if (existing.isEmpty()
            && (accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), memberId)
                || (hasVerifiedIban
                    && accountRepository.existsSoftDeletedByIbanAndMemberIdAndProviderIn(
                        data.iban(), memberId, PROVIDER_FAMILY)))) {
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
        account.setExternalAccountId(data.externalId());
        if (data.iban() != null && !data.iban().isBlank()) account.setIban(data.iban());
        account.setCurrentBalance(data.balanceEur());
        account.setCashBalance(data.cashBalance());
        account.setLastSyncedAt(syncedAt);
        Account savedAccount = accountRepository.save(account);
        transactionImporter.importFor(savedAccount, data.transactions(), categorizationContext, "bourso");

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
            case ASSURANCE_VIE -> "#8b5cf6";
            case SAVINGS, LEP, LIVRET_A, LDDS, LIVRET_JEUNE, PEL, CEL -> "#f59e0b";
            default -> "#ec4899";
        };
    }

    /**
     * Queued jobs live in a process-local executor and cannot survive a backend
     * restart. Turn persisted in-flight states into a retryable failure instead
     * of leaving the UI polling QUEUED/RUNNING forever.
     */
    @Transactional
    public void recoverInterruptedSyncs() {
        sync.recoverInterrupted();
    }

    @Transactional(readOnly = true)
    public SessionStatusResponse getStatus(Long memberId) {
        return sync.find(memberId).map(this::toStatus).orElseGet(SessionStatusResponse::inactive);
    }

    /**
     * Returns whether a stored session was there to delete. A pending login is dropped too but
     * does not count: it never became a connection.
     */
    public boolean clearSession(Long memberId) {
        boolean removed = sync.clear(memberId);
        pendingCredentials.entrySet().removeIf(entry -> entry.getValue().memberId().equals(memberId));
        return removed;
    }

    public SourceSyncResult resyncReporting(Long memberId) {
        return sync.resyncReporting(memberId, "bourso", log, this::getStatus, this::queueSync);
    }

    public void resyncIfSessionActive(Long memberId) {
        resyncReporting(memberId); // ignore return
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
        return sync.codeOf(exception);
    }

    private SyncException error(SidecarErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    private String stableExternalId(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            throw error(SidecarErrorCode.INVALID_DATA, "BoursoBank returned an invalid account identifier", null);
        }
        String externalId = cleaned.startsWith(EXTERNAL_ID_PREFIX) ? cleaned : EXTERNAL_ID_PREFIX + cleaned;
        if (externalId.length() > 100) {
            throw error(SidecarErrorCode.INVALID_DATA, "BoursoBank returned an invalid account identifier", null);
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
            throw error(SidecarErrorCode.INVALID_DATA, "BoursoBank returned an invalid quote currency", null);
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

    /**
     * The rows an assurance-vie allowance is counted on, as the sidecar counted them:
     * each was rounded to the cent on its own, before rows of one fund merge into a
     * single holding, zero-unit rows are dropped and euro-fund rows are summed into
     * the cash leg. Fewer rows than positions received cannot be true.
     */
    private int valuationLineCount(BoursoAdapter.AccountData account) {
        Integer lines = account.valuationLineCount();
        if (lines == null || lines < account.positions().size()) {
            throw error(SidecarErrorCode.INVALID_DATA, "BoursoBank returned no valid line count for an assurance vie", null);
        }
        return lines;
    }

    private <T> T requireTransactionResult(T value) {
        return Objects.requireNonNull(value, "Transaction callback returned no result");
    }

    public record AuthInitResponse(String processId, boolean mfaRequired, String mfaType) {}

    public record SessionStatusResponse(
        boolean isActive,
        SidecarSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        SidecarErrorCode lastSyncError
    ) implements SidecarSessionView {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, SidecarSyncStatus.IDLE, null, null, null);
        }
    }

    private record Credentials(String customerId, String password) {}
    private record PendingCredentials(String customerId, String password, Instant expiresAt, Long memberId) {}
    private record PreparedAccount(
        String externalId,
        String name,
        AccountType type,
        BigDecimal balanceEur,
        BigDecimal cashBalance,
        BigDecimal investedAmountEur,
        List<PreparedPosition> positions,
        String iban,
        List<SidecarTransaction> transactions
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
