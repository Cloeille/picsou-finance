package com.picsou.service;

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
import com.picsou.adapter.AmundiAdapter;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

/**
 * Imports Amundi Épargne Salariale plans, one Picsou account per dispositif.
 *
 * <p>Shaped like {@link BourseDirectSyncService}: the browser work happens in a
 * sidecar and is slow, so authentication only queues the import, and the import
 * itself does its upstream I/O outside any transaction before replacing the
 * whole snapshot atomically. A partial read is discarded rather than merged --
 * an FCPE line that vanished for a request is not a line that was sold.
 */
@Service
public class AmundiSyncService {
    private static final Logger log = LoggerFactory.getLogger(AmundiSyncService.class);
    static final String PROVIDER = "Amundi Épargne Salariale";
    private static final String EXTERNAL_ID_PREFIX = "amundi_";
    private static final String ACCOUNT_COLOR = "#8b5cf6";
    private static final int MAX_TICKER_LENGTH = 30;

    private final AmundiAdapter port;
    private final SidecarSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final FamilyMemberRepository memberRepository;
    private final AccountService accountService;
    private final CryptoEncryption encryption;
    private final SidecarSyncTemplate<List<PreparedPlan>> sync;

    public AmundiSyncService(
        AmundiAdapter port,
        SidecarSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        FamilyMemberRepository memberRepository,
        AccountService accountService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("amundiSyncExecutor") Executor syncExecutor
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.memberRepository = memberRepository;
        this.accountService = accountService;
        this.encryption = encryption;
        this.sync = new SidecarSyncTemplate<>(SidecarBank.AMUNDI, new Strategy(), sessionRepository,
            memberRepository, encryption, txTemplate, syncExecutor, false);
    }

    private final class Strategy implements SidecarSyncStrategy<List<PreparedPlan>> {
        @Override
        public List<PreparedPlan> fetch(SidecarSyncJob job) {
            return preparePlans(port.fetchPlans(job.plainState()));
        }

        @Override
        public void commit(SidecarSyncJob job, List<PreparedPlan> prepared, Instant syncedAt) {
            FamilyMember member = memberRepository.findById(job.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            for (PreparedPlan data : prepared) {
                upsertAccount(data, member, job.memberId(), syncedAt);
            }
        }

        @Override
        public String describe(List<PreparedPlan> prepared) {
            return "plans=" + prepared.size();
        }
    }

    public AuthInitResponse initiateAuth(String login, String password, Long memberId) {
        SidecarInitiate result = port.initiateAuth(login, password);
        if (!result.mfaRequired()) {
            if (result.sessionState() == null || result.sessionState().isBlank()) {
                throw error(SidecarErrorCode.INVALID_DATA, "Amundi did not return a session", null);
            }
            storeSessionAndQueue(result.sessionState(), memberId);
        }
        return new AuthInitResponse(result.processId(), result.mfaRequired(), result.mfaType());
    }

    public SessionStatusResponse completeAuth(String processId, String code, Long memberId) {
        String plainState = port.completeAuth(processId, code);
        if (plainState == null || plainState.isBlank()) {
            throw error(SidecarErrorCode.INVALID_DATA, "Amundi did not return a session", null);
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

    private List<PreparedPlan> preparePlans(List<AmundiAdapter.PlanData> fetched) {
        if (fetched == null || fetched.isEmpty()) {
            throw error(
                SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "Amundi returned no complete savings plan",
                null
            );
        }

        Set<String> externalIds = new HashSet<>();
        List<PreparedPlan> prepared = new ArrayList<>();
        for (AmundiAdapter.PlanData plan : fetched) {
            if (plan == null || !plan.snapshotComplete()) {
                throw error(
                    SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                    "Amundi returned an incomplete savings plan",
                    null
                );
            }
            String externalId = stableExternalId(plan.externalId());
            if (!externalIds.add(externalId)) {
                throw error(SidecarErrorCode.INVALID_DATA, "Amundi returned duplicate plans", null);
            }
            if (plan.balanceEur() == null || plan.positions() == null) {
                throw error(SidecarErrorCode.INVALID_DATA, "Amundi returned incomplete plan values", null);
            }

            List<PreparedPosition> positions = preparePositions(plan.positions());
            BigDecimal positionValue = positions.stream()
                .map(PreparedPosition::valueEur)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            // The sidecar already checked this, and it stays checked here: a
            // total that disagrees with its lines means the read was partial,
            // and overwriting a good snapshot with it would erase real money.
            if (!moneyClose(positionValue, plan.balanceEur())) {
                throw error(
                    SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                    "Amundi returned an incomplete savings plan",
                    null
                );
            }

            prepared.add(new PreparedPlan(
                externalId,
                accountName(plan),
                plan.balanceEur(),
                investedAmount(plan, positions),
                positions
            ));
        }
        return List.copyOf(prepared);
    }

    private List<PreparedPosition> preparePositions(List<AmundiAdapter.Position> rawPositions) {
        Map<String, PreparedPosition> positions = new LinkedHashMap<>();
        for (AmundiAdapter.Position position : rawPositions) {
            if (position == null || position.quantity() == null || position.valueEur() == null) {
                throw error(SidecarErrorCode.INVALID_DATA, "Amundi returned an incomplete position", null);
            }
            // The sidecar keeps a line whose units are zero but which still carries a
            // valuation -- a contribution credited before it was converted into units.
            // Dropping it here would leave the plan total unreconcilable against its
            // lines, failing every sync for the same payload. Skip only what the
            // parser skips: nothing held and nothing owed.
            if (position.quantity().signum() == 0 && position.valueEur().signum() == 0) {
                continue;
            }
            String ticker = resolveTicker(position);
            PreparedPosition resolved = new PreparedPosition(
                ticker,
                limit(position.label(), 100, ticker),
                position.quantity(),
                averageBuyIn(position),
                position.unitValue(),
                position.valueEur(),
                position.pnlEur()
            );
            positions.merge(ticker, resolved, this::mergePositions);
        }
        return positions.values().stream()
            .filter(position -> position.quantity().signum() != 0 || position.valueEur().signum() != 0)
            .toList();
    }

    /**
     * FCPE units carry no purchase price, only a current valuation and an
     * unrealized gain, so the cost basis is what is left once the gain is taken
     * out. Without a gain there is nothing to derive it from -- leaving it null
     * is correct, and {@link #investedAmount} then falls back to the plan total.
     */
    private BigDecimal averageBuyIn(AmundiAdapter.Position position) {
        if (position.pnlEur() == null || position.quantity().signum() == 0) {
            return null;
        }
        return position.valueEur()
            .subtract(position.pnlEur())
            .divide(position.quantity(), 8, RoundingMode.HALF_UP);
    }

    private PreparedPosition mergePositions(PreparedPosition left, PreparedPosition right) {
        // Two lines share a ticker when they are the same fund. If the labels
        // disagree, the ISIN-less fallback collided and merging would fuse two
        // different funds into one -- refuse rather than silently corrupt.
        if (!Objects.equals(left.name(), right.name())) {
            throw error(SidecarErrorCode.INVALID_DATA, "Amundi returned colliding fund identifiers", null);
        }
        BigDecimal quantity = left.quantity().add(right.quantity());
        return new PreparedPosition(
            left.ticker(),
            left.name(),
            quantity,
            weightedAverage(left.averageBuyInEur(), left.quantity(), right.averageBuyInEur(), right.quantity(), quantity),
            left.unitValue() != null ? left.unitValue() : right.unitValue(),
            left.valueEur().add(right.valueEur()),
            sumComplete(left.pnlEur(), right.pnlEur())
        );
    }

    private BigDecimal investedAmount(AmundiAdapter.PlanData plan, List<PreparedPosition> positions) {
        BigDecimal invested = BigDecimal.ZERO;
        for (PreparedPosition position : positions) {
            if (position.pnlEur() == null) {
                return plan.balanceEur();
            }
            invested = invested.add(position.valueEur().subtract(position.pnlEur()));
        }
        return invested;
    }

    private void upsertAccount(PreparedPlan data, FamilyMember member, Long memberId, Instant syncedAt) {
        Optional<Account> existing = accountRepository
            .findByExternalAccountIdAndMemberId(data.externalId(), memberId);
        if (existing.isEmpty()
            && accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), memberId)) {
            log.info("Amundi skipped a soft-deleted account (member={})", memberId);
            return;
        }

        Account account = existing.orElseGet(() -> Account.builder()
            .member(member)
            .externalAccountId(data.externalId())
            .provider(PROVIDER)
            .currency("EUR")
            .isManual(false)
            .color(ACCOUNT_COLOR)
            .build());
        account.setName(data.name());
        account.setType(AccountType.EMPLOYEE_SAVINGS);
        account.setProvider(PROVIDER);
        account.setCurrency("EUR");
        account.setManual(false);
        account.setCurrentBalance(data.balanceEur());
        // Épargne salariale holds no spendable cash sleeve: every euro sits in
        // an FCPE. Leaving this null keeps it out of the invested-amount maths.
        account.setCashBalance(null);
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
                .currentPrice(position.unitValue())
                .quoteCurrency(position.unitValue() != null ? "EUR" : null)
                .providerValueEur(position.valueEur())
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
        return sync.resyncReporting(memberId, "amundi", log, this::getStatus, this::queueSync);
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

    private SyncException error(SidecarErrorCode code, String message, Throwable cause) {
        return SidecarSyncTemplate.error(code, message, cause);
    }

    /**
     * "PEG Groupe ACME" reads better than "PEG", and a member can hold several
     * plans of the same kind at different employers.
     */
    private String accountName(AmundiAdapter.PlanData plan) {
        String label = clean(plan.name());
        String kind = clean(plan.planKind());
        String base = label != null ? label : kind;
        if (base == null) {
            base = "Amundi";
        }
        String employer = clean(plan.employer());
        String composed = employer != null && !base.toLowerCase(Locale.ROOT).contains(employer.toLowerCase(Locale.ROOT))
            ? base + " — " + employer
            : base;
        return limit(composed, 100, "Amundi");
    }

    private String stableExternalId(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            throw error(SidecarErrorCode.INVALID_DATA, "Amundi returned an invalid plan identifier", null);
        }
        String externalId = cleaned.startsWith(EXTERNAL_ID_PREFIX) ? cleaned : EXTERNAL_ID_PREFIX + cleaned;
        if (externalId.length() > 100) {
            throw error(SidecarErrorCode.INVALID_DATA, "Amundi returned an invalid plan identifier", null);
        }
        return externalId;
    }

    /**
     * FCPEs are not in OpenFIGI's universe and Yahoo cannot quote them, so no
     * conversion is attempted: the ISIN is the ticker when there is one.
     *
     * <p>There often is not. `codeIsin` carries the AMF code on employer funds
     * (`990000093539`), which is not an ISIN and is rejected here. Falling
     * straight to the label is what made that expensive: the slug is truncated to
     * {@value #MAX_TICKER_LENGTH} characters, and two share classes of one fund
     * differ only in their last word -- "EPARGNE SOLIDAIRE DYNAMIQUE THALES - A"
     * and "- B" both slug to `EPARGNE-SOLIDAIRE-DYNAMIQUE-TH`. {@link
     * #mergePositions} then correctly refuses to fuse them, and the whole sync
     * fails on a payload that is perfectly valid.
     *
     * <p>So the fund's own `codeFonds` is tried first: short, stable, and unique
     * per fund. The label stays as the last resort, and the collision guard in
     * {@link #mergePositions} stays behind it.
     */
    private String resolveTicker(AmundiAdapter.Position position) {
        String isin = clean(position.isin());
        if (isin != null) {
            isin = isin.toUpperCase(Locale.ROOT);
            if (isin.matches("[A-Z]{2}[A-Z0-9]{9}[0-9]")) {
                return isin;
            }
        }
        String fundCode = slug(position.fundCode());
        if (fundCode != null) {
            return fundCode;
        }
        String label = slug(position.label());
        if (label == null) {
            throw error(SidecarErrorCode.INVALID_DATA, "Amundi returned a fund without any identifier", null);
        }
        return label;
    }

    /** Uppercase alphanumeric slug, capped at {@value #MAX_TICKER_LENGTH}, or null if nothing is left. */
    private String slug(String value) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return null;
        }
        String slug = cleaned.toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            return null;
        }
        return slug.length() <= MAX_TICKER_LENGTH ? slug : slug.substring(0, MAX_TICKER_LENGTH);
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

    private record PreparedPlan(
        String externalId,
        String name,
        BigDecimal balanceEur,
        BigDecimal investedAmountEur,
        List<PreparedPosition> positions
    ) {}
    private record PreparedPosition(
        String ticker,
        String name,
        BigDecimal quantity,
        BigDecimal averageBuyInEur,
        BigDecimal unitValue,
        BigDecimal valueEur,
        BigDecimal pnlEur
    ) {}
}
