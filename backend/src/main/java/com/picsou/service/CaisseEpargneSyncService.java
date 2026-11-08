package com.picsou.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.picsou.config.CryptoEncryption;
import com.picsou.model.CaisseEpargneSession;
import com.picsou.model.CaisseEpargneSyncStatus;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.CaisseEpargnePort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CaisseEpargneSessionRepository;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.budget.CategoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Imports Caisse d'Epargne current accounts, savings books, cards, loans and PER, with their
 * transactions. The import itself is the shared {@link BpceSyncEngine}'s; this service binds it to
 * the Caisse d'Epargne port, session table and labels, and owns the transaction boundaries.
 * The sync is <b>on demand only</b> and no credentials are ever stored (decision D5, cookies only).
 */
@Service
public class CaisseEpargneSyncService {
    private static final Logger log = LoggerFactory.getLogger(CaisseEpargneSyncService.class);

    static final String PROVIDER = "Caisse d'Epargne";
    static final BpceBank BANK = BpceBank.named(PROVIDER, "ce_");

    private final CaisseEpargnePort port;
    private final CaisseEpargneSessionRepository sessionRepository;
    private final BpceSyncEngine<CaisseEpargneSession, SessionStatusResponse> engine;

    @org.springframework.beans.factory.annotation.Autowired
    public CaisseEpargneSyncService(
        CaisseEpargnePort port,
        CaisseEpargneSessionRepository sessionRepository,
        AccountRepository accountRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository memberRepository,
        DebtRepository debtRepository,
        AccountService accountService,
        CategorizationService categorizationService,
        CategoryService categoryService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("caisseEpargneSyncExecutor") Executor syncExecutor,
        CaisseEpargnePerService perService
    ) {
        this(port, sessionRepository, accountRepository, transactionRepository, memberRepository, debtRepository,
            accountService, categorizationService, categoryService, encryption, txTemplate, syncExecutor,
            perService, Clock.systemDefaultZone());
    }

    CaisseEpargneSyncService(
        CaisseEpargnePort port,
        CaisseEpargneSessionRepository sessionRepository,
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
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.engine = new BpceSyncEngine<>(
            BANK, SidecarBank.CAISSE_EPARGNE, port, new Sessions(sessionRepository),
            CaisseEpargneSyncService::toStatus, SessionStatusResponse::inactive,
            BpceSyncEngine.BrandingSource.fixed(PROVIDER),
            accountRepository, transactionRepository, memberRepository, debtRepository, accountService,
            categorizationService, categoryService, encryption, txTemplate, syncExecutor, perService, clock);
    }

    /**
     * Starts a login. One call to the sidecar, no retry. No password ever reaches Picsou: the
     * sidecar answers with the bank's keypad, which the user clicks himself. A failure leaves any
     * session already stored (valid or not) exactly as it was.
     */
    public BpceSyncEngine.InitiateResponse initiateAuth(String customerId, Long memberId) {
        return engine.initiateAuth(() -> port.initiateAuth(customerId), memberId, CaisseEpargneSession::create);
    }

    /** Clicks the digits the user chose. Single use, no retry; the positions are never logged. */
    public void submitKeypad(String processId, List<Integer> positions, Long memberId) {
        engine.submitKeypad(processId, positions, memberId);
    }

    /** Finishes a login the user approved on the phone and stores the encrypted session. */
    public SessionStatusResponse completeAuth(String processId, Long memberId) {
        return engine.completeAuth(processId, memberId);
    }

    /** Stores (encrypted) a session state. It does <b>not</b> queue a sync: the user triggers it. */
    public SessionStatusResponse storeSession(String plainState, Long memberId) {
        return engine.storeSession(plainState, memberId, CaisseEpargneSession::create);
    }

    public SessionStatusResponse queueSync(Long memberId) {
        return engine.queueSync(memberId);
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
            SidecarErrorCode.INTERNAL_ERROR
        );
        if (recovered > 0) {
            log.warn("Recovered {} interrupted Caisse d'Epargne sync job(s)", recovered);
        }
    }

    @Transactional(readOnly = true)
    public SessionStatusResponse getStatus(Long memberId) {
        return engine.getStatus(memberId);
    }

    /**
     * Deletes the stored session. This only forgets the cookies in Picsou: the bank session is
     * not revoked and may stay active until it expires. Returns whether a session was deleted.
     */
    public boolean clearSession(Long memberId) {
        return engine.clearSession(memberId);
    }

    private static SessionStatusResponse toStatus(
        CaisseEpargneSession session, List<BpceSyncEngine.UnsupportedContract> unsupported
    ) {
        return new SessionStatusResponse(
            session.isActive(),
            session.getSyncStatus(),
            session.getLastSyncStartedAt(),
            session.getLastSyncCompletedAt(),
            session.getLastSyncError(),
            unsupported
        );
    }

    /** The Caisse d'Epargne session table, as the engine uses it. */
    private record Sessions(CaisseEpargneSessionRepository repository)
        implements BpceSyncEngine.SessionStore<CaisseEpargneSession> {

        @Override
        public Optional<CaisseEpargneSession> findByMemberId(Long memberId) {
            return repository.findByMemberId(memberId);
        }

        @Override
        public Optional<CaisseEpargneSession> findByMemberIdForUpdate(Long memberId) {
            return repository.findByMemberIdForUpdate(memberId);
        }

        @Override
        public Optional<CaisseEpargneSession> findByIdAndMemberIdForUpdate(Long id, Long memberId) {
            return repository.findByIdAndMemberIdForUpdate(id, memberId);
        }

        @Override
        public void save(CaisseEpargneSession session) {
            repository.save(session);
        }

        @Override
        public void saveAndFlush(CaisseEpargneSession session) {
            repository.saveAndFlush(session);
        }

        @Override
        public void delete(CaisseEpargneSession session) {
            repository.delete(session);
        }

        @Override
        public void flush() {
            repository.flush();
        }
    }

    public record SessionStatusResponse(
        boolean isActive,
        CaisseEpargneSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        SidecarErrorCode lastSyncError,
        List<BpceSyncEngine.UnsupportedContract> unsupported
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
}
