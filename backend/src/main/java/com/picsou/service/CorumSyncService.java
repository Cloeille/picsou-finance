package com.picsou.service;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.model.ScpiFundOutcome;
import com.picsou.model.ScpiProvider;
import com.picsou.port.SidecarErrorCode;
import com.picsou.adapter.CorumAdapter;
import com.picsou.repository.SidecarSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;


/**
 * Syncs a CORUM client-space portfolio into the SCPI accounts that are already
 * linked to its funds.
 *
 * <p>Shaped like {@link AmundiSyncService}: the browser work happens in a
 * sidecar and is slow, so authentication stores the session and queues the
 * import, and the import does its upstream I/O outside any transaction before
 * writing. Unlike Amundi this does not create accounts -- the manual model owns
 * that, one account per vehicle, and a CORUM contract simply maps its funds onto
 * accounts the user already created.
 *
 * <p>The balance written here is the withdrawal value, computed by
 * {@link ScpiPositionService}. This service never re-derives it and never falls
 * back to the subscription price, because entry fees sit between the two and
 * using the wrong one overstates net wealth.
 */
@Service
public class CorumSyncService {
    private static final Logger log = LoggerFactory.getLogger(CorumSyncService.class);
    private static final BigDecimal MONEY_TOLERANCE = new BigDecimal("0.01");

    private final CorumAdapter port;
    private final SidecarSessionRepository sessionRepository;
    private final FamilyMemberRepository memberRepository;
    private final CryptoEncryption encryption;
    private final SidecarSyncTemplate<CorumAdapter.Snapshot> sync;
    private final ScpiFundReconciler fundReconciler;

    public CorumSyncService(
        CorumAdapter port,
        SidecarSessionRepository sessionRepository,
        FamilyMemberRepository memberRepository,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("corumSyncExecutor") Executor syncExecutor,
        ScpiFundReconciler fundReconciler
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.memberRepository = memberRepository;
        this.encryption = encryption;
        this.sync = new SidecarSyncTemplate<>(SidecarBank.CORUM, new Strategy(), sessionRepository,
            memberRepository, encryption, txTemplate, syncExecutor, true);
        this.fundReconciler = fundReconciler;
    }

    private final class Strategy implements SidecarSyncStrategy<CorumAdapter.Snapshot> {
        @Override
        public CorumAdapter.Snapshot fetch(SidecarSyncJob job) {
            return port.fetchSnapshot(job.plainState());
        }

        @Override
        public void commit(SidecarSyncJob job, CorumAdapter.Snapshot snapshot, Instant syncedAt) {
            Map<String, ScpiFundOutcome> outcomes = applySnapshot(job.memberId(), snapshot, syncedAt);
            log.info("CORUM fund outcomes (member={}; funds={})", job.memberId(), outcomes);
        }
    }

    public SessionStatusResponse authenticate(String login, String password, Long memberId) {
        String plainState = port.authenticate(login, password);
        if (plainState == null || plainState.isBlank()) {
            throw error(SidecarErrorCode.INVALID_DATA, "CORUM did not return a session", null);
        }
        return toStatus(sync.replaceAndQueue(memberId, plainState, false, session -> {}));
    }

    public SessionStatusResponse queueSync(Long memberId) {
        return toStatus(sync.queue(memberId, false));
    }

    @Transactional(readOnly = true)
    public SessionStatusResponse getStatus(Long memberId) {
        return sync.find(memberId).map(this::toStatus).orElseGet(SessionStatusResponse::inactive);
    }

    @Transactional
    public void clearSession(Long memberId) {
        sync.clear(memberId);
    }

    /**
     * Writes the snapshot onto the member's SCPI accounts.
     *
     * <p>A fund already linked to an account updates it. A fund seen for the first
     * time adopts the one manual SCPI account named like it, or gets a new account;
     * a fund the member ignored writes nothing. {@link ScpiFundReconciler} owns those
     * rules, and the withdrawal-price rule stays in
     * {@link ScpiPositionService#applySyncedPosition}: a holding whose withdrawal
     * price is missing still updates the share count but leaves the balance alone.
     */
    private Map<String, ScpiFundOutcome> applySnapshot(
        Long memberId, CorumAdapter.Snapshot snapshot, Instant syncedAt
    ) {
        if (snapshot == null || !snapshot.snapshotComplete()) {
            throw error(
                SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "CORUM returned an incomplete portfolio",
                null
            );
        }
        if (snapshot.holdings() == null || snapshot.holdings().isEmpty()) {
            throw error(
                SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "CORUM returned no fund holding",
                null
            );
        }

        Set<String> seenFunds = new HashSet<>();
        for (CorumAdapter.Holding holding : snapshot.holdings()) {
            if (holding == null || holding.fundCode() == null || holding.quantity() == null) {
                throw error(SidecarErrorCode.INVALID_DATA, "CORUM returned an incomplete fund", null);
            }
            if (!seenFunds.add(holding.fundCode())) {
                // Two lines for one fund would write the quantity twice.
                throw error(SidecarErrorCode.INVALID_DATA, "CORUM returned a duplicate fund", null);
            }
            if (holding.quantity().signum() < 0) {
                throw error(SidecarErrorCode.INVALID_DATA, "CORUM returned a negative share count", null);
            }
        }
        checkDeclaredTotal(snapshot);
        // Every SCPI account is EUR and the reconciler writes the figures as they
        // come. A snapshot in another currency, or with none, would be stored as
        // euros silently, so it is refused before anything is written.
        if (!"EUR".equals(snapshot.currency())) {
            throw error(SidecarErrorCode.INVALID_DATA, "CORUM returned a portfolio not in euros", null);
        }

        List<ScpiFundReconciler.SyncedFund> funds = snapshot.holdings().stream()
            .map(holding -> new ScpiFundReconciler.SyncedFund(
                holding.fundCode(),
                holding.label(),
                holding.quantity(),
                holding.subscriptionPrice(),
                holding.withdrawalPrice()
            ))
            .toList();
        return fundReconciler.reconcile(memberId, ScpiProvider.CORUM, funds, syncedAt);
    }

    /**
     * Reconciles the funds against the contract total the snapshot declares.
     *
     * <p>The sidecar already does this, and repeating it here is deliberate: the
     * sidecar is a separate deployable, so its check is exactly the thing a
     * rolling update or a stale image would leave out. The figure compared is
     * the displayed value, not the withdrawal value: entry fees sit between the
     * two, and comparing the withdrawal value to the contract would refuse
     * every real portfolio.
     */
    private void checkDeclaredTotal(CorumAdapter.Snapshot snapshot) {
        BigDecimal declared = snapshot.totalValuationEur();
        if (declared == null || declared.signum() < 0) {
            throw error(SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "CORUM returned no valid portfolio total", null);
        }
        BigDecimal computed = BigDecimal.ZERO;
        for (CorumAdapter.Holding holding : snapshot.holdings()) {
            if (holding.displayedValueEur() == null) {
                throw error(SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                    "CORUM returned a fund without a displayed value", null);
            }
            computed = computed.add(holding.displayedValueEur());
        }
        if (computed.subtract(declared).abs().compareTo(MONEY_TOLERANCE) > 0) {
            throw error(
                SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "The CORUM funds add up to " + computed.setScale(2, RoundingMode.HALF_UP)
                    + " but the snapshot declares " + declared.setScale(2, RoundingMode.HALF_UP),
                null
            );
        }
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

    public record SessionStatusResponse(
        boolean isActive,
        SidecarSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        SidecarErrorCode lastSyncError
    ) {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, SidecarSyncStatus.IDLE, null, null, null);
        }
    }

}
