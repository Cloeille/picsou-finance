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
import com.picsou.adapter.BourseDirectAdapter;
import com.picsou.port.SidecarInitiate;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.SidecarSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.service.sync.SourceSyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
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

import static com.picsou.service.SyncValues.clean;
import static com.picsou.service.SyncValues.limit;
import static com.picsou.service.SyncValues.moneyClose;
import static com.picsou.service.SyncValues.sumComplete;
import static com.picsou.service.SyncValues.weightedAverage;

@Service
public class BourseDirectSyncService {
    private static final Logger log = LoggerFactory.getLogger(BourseDirectSyncService.class);
    static final String PROVIDER = "Bourse Direct";

    private final BourseDirectAdapter port;
    private final SidecarSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final FamilyMemberRepository memberRepository;
    private final AccountService accountService;
    private final OpenFigiIsinConverter isinConverter;
    private final SecurityIdentityService identityService;
    private final CryptoEncryption encryption;
    private final SidecarSyncTemplate<List<PreparedAccount>> sync;

    public BourseDirectSyncService(
        BourseDirectAdapter port,
        SidecarSessionRepository sessionRepository,
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
        this.sync = new SidecarSyncTemplate<>(SidecarBank.BOURSE_DIRECT, new Strategy(), sessionRepository,
            memberRepository, encryption, txTemplate, syncExecutor, false);
    }

    private final class Strategy implements SidecarSyncStrategy<List<PreparedAccount>> {
        @Override
        public List<PreparedAccount> fetch(SidecarSyncJob job) {
            return prepareAccounts(port.fetchAccounts(job.plainState()));
        }

        @Override
        public void commit(SidecarSyncJob job, List<PreparedAccount> prepared, Instant syncedAt) {
            FamilyMember member = memberRepository.findById(job.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            for (PreparedAccount data : prepared) {
                upsertAccount(data, member, job.memberId(), syncedAt);
            }
        }

        @Override
        public String describe(List<PreparedAccount> prepared) {
            return "accounts=" + prepared.size();
        }
    }

    public AuthInitResponse initiateAuth(String login, String password, Long memberId) {
        SidecarInitiate result = port.initiateAuth(login, password);
        if (!result.mfaRequired()) {
            if (result.sessionState() == null || result.sessionState().isBlank()) {
                throw error(SidecarErrorCode.INVALID_DATA, "Bourse Direct did not return a session", null);
            }
            storeSessionAndQueue(result.sessionState(), memberId);
        }
        return new AuthInitResponse(result.processId(), result.mfaRequired(), result.mfaType());
    }

    public SessionStatusResponse completeAuth(String processId, String code, Long memberId) {
        String plainState = port.completeAuth(processId, code);
        if (plainState == null || plainState.isBlank()) {
            throw error(SidecarErrorCode.INVALID_DATA, "Bourse Direct did not return a session", null);
        }
        return storeSessionAndQueue(plainState, memberId);
    }

    public SessionStatusResponse queueSync(Long memberId) {
        return toStatus(sync.queue(memberId, false));
    }

    private SessionStatusResponse storeSessionAndQueue(String plainState, Long memberId) {
        sync.replaceAndQueue(memberId, plainState, false, session -> {});
        return getStatus(memberId);
    }

    private List<PreparedAccount> prepareAccounts(List<BourseDirectAdapter.AccountData> fetched) {
        if (fetched == null || fetched.isEmpty()) {
            throw error(
                SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "Bourse Direct returned no complete portfolio accounts",
                null
            );
        }

        Set<String> externalIds = new HashSet<>();
        List<PreparedAccount> prepared = new ArrayList<>();
        for (BourseDirectAdapter.AccountData account : fetched) {
            if (account == null || !account.snapshotComplete()) {
                throw error(
                    SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                    "Bourse Direct returned an incomplete portfolio",
                    null
                );
            }
            String externalId = stableExternalId(account.externalId());
            if (!externalIds.add(externalId)) {
                throw error(SidecarErrorCode.INVALID_DATA, "Bourse Direct returned duplicate accounts", null);
            }
            if (account.type() != AccountType.PEA && account.type() != AccountType.COMPTE_TITRES) {
                throw error(SidecarErrorCode.INVALID_DATA, "Bourse Direct returned an unsupported account type", null);
            }
            if (account.balanceEur() == null || account.cashBalance() == null || account.positions() == null) {
                throw error(SidecarErrorCode.INVALID_DATA, "Bourse Direct returned incomplete account values", null);
            }

            List<PreparedPosition> positions = preparePositions(account.positions());
            BigDecimal positionValue = positions.stream()
                .map(PreparedPosition::currentValueEur)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal expectedPositionValue = account.balanceEur().subtract(account.cashBalance());
            if (!moneyClose(positionValue, expectedPositionValue)) {
                throw error(
                    SidecarErrorCode.PORTFOLIO_INCOMPLETE,
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

    private List<PreparedPosition> preparePositions(List<BourseDirectAdapter.Position> rawPositions) {
        Map<String, PreparedPosition> positions = new LinkedHashMap<>();
        Map<String, String> isinByTicker = new LinkedHashMap<>();
        for (BourseDirectAdapter.Position position : rawPositions) {
            if (position == null || position.quantity() == null || position.currentValueEur() == null) {
                throw error(SidecarErrorCode.INVALID_DATA, "Bourse Direct returned an incomplete position", null);
            }
            if (position.quantity().signum() == 0) {
                continue;
            }
            String currency = normalizeCurrency(position.quoteCurrency());
            if (position.currentPrice() != null && currency == null) {
                throw error(
                    SidecarErrorCode.INVALID_DATA,
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

    private String resolveTicker(BourseDirectAdapter.Position position, Map<String, String> isinByTicker) {
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
            throw error(SidecarErrorCode.INVALID_DATA, "Bourse Direct returned an invalid instrument identifier", null);
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
            throw error(SidecarErrorCode.INVALID_DATA, "Bourse Direct returned an invalid ISIN", null);
        }
        return isin;
    }

    private PreparedPosition mergePositions(PreparedPosition left, PreparedPosition right) {
        if (!Objects.equals(left.quoteCurrency(), right.quoteCurrency())) {
            throw error(SidecarErrorCode.INVALID_DATA, "Bourse Direct returned conflicting quote currencies", null);
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

    private BigDecimal investedAmount(
        BourseDirectAdapter.AccountData account,
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

    /**
     * Browser-backed jobs cannot survive a backend restart. Turn persisted
     * in-flight states into a retryable failure instead of leaving the UI
     * polling QUEUED/RUNNING forever.
     */
    @Transactional
    public void recoverInterruptedSyncs() {
        sync.recoverInterrupted();
    }

    @Transactional(readOnly = true)
    public SessionStatusResponse getStatus(Long memberId) {
        return sync.find(memberId).map(this::toStatus).orElseGet(SessionStatusResponse::inactive);
    }

    /** Returns whether a stored session was there to delete. */
    public boolean clearSession(Long memberId) {
        return sync.clear(memberId);
    }

    public SourceSyncResult resyncReporting(Long memberId) {
        return sync.resyncReporting(memberId, "bourse-direct", log, this::getStatus, this::queueSync);
    }

    public void resyncIfSessionActive(Long memberId) {
        resyncReporting(memberId); // ignore return
    }

    private SessionStatusResponse toStatus(SidecarSession session) {
        return new SessionStatusResponse(
            session.isActive(),
            null,
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
            throw error(SidecarErrorCode.INVALID_DATA, "Bourse Direct returned an invalid account identifier", null);
        }
        String externalId = cleaned.startsWith("bd_") ? cleaned : "bd_" + cleaned;
        if (externalId.length() > 100) {
            throw error(SidecarErrorCode.INVALID_DATA, "Bourse Direct returned an invalid account identifier", null);
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
            throw error(SidecarErrorCode.INVALID_DATA, "Bourse Direct returned an invalid quote currency", null);
        }
        return currency;
    }

    public record AuthInitResponse(String processId, boolean mfaRequired, String mfaType) {}

    public record SessionStatusResponse(
        boolean isActive,
        Instant expiresAt,
        SidecarSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        SidecarErrorCode lastSyncError
    ) implements SidecarSessionView {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, null, SidecarSyncStatus.IDLE, null, null, null);
        }
    }

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
