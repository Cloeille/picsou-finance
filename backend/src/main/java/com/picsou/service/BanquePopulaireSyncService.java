package com.picsou.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.picsou.config.CryptoEncryption;
import com.picsou.model.BanquePopulaireSession;
import com.picsou.model.BanquePopulaireSyncStatus;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.BanquePopulairePort;
import com.picsou.port.BanquePopulaireRegion;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BanquePopulaireSessionRepository;
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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Imports Banque Populaire current accounts and savings books, with their transactions.
 * Banque Populaire runs on the BPCE platform, so the import is the shared {@link BpceSyncEngine}'s
 * (loans and PER included, should the sidecar ever report them); this service binds it to the
 * Banque Populaire port, session table and labels, and owns the transaction boundaries. The sync
 * is <b>on demand only</b>, like Caisse d'Epargne's: no scheduler, startup or member sync. No
 * credentials are ever stored. The regional bank chosen at login is stored on the session: each
 * sync stamps its official name on the accounts as their provider, with that bank's logo from the
 * Enable Banking catalog, so they look like the ones Enable Banking would create for that bank.
 */
@Service
public class BanquePopulaireSyncService {
    private static final Logger log = LoggerFactory.getLogger(BanquePopulaireSyncService.class);

    /** The bank's generic name, used in messages, logs and fallback names; accounts carry the regional one. */
    static final String PROVIDER = "Banque Populaire";
    static final BpceBank BANK = BpceBank.named(PROVIDER, "bp_");
    /** Every regional bank is French: narrows the catalog search to one country. */
    static final String LOGO_COUNTRY = "FR";

    private final BanquePopulairePort port;
    private final BanquePopulaireSessionRepository sessionRepository;
    private final BpceSyncEngine<BanquePopulaireSession, SessionStatusResponse> engine;

    @org.springframework.beans.factory.annotation.Autowired
    public BanquePopulaireSyncService(
        BanquePopulairePort port,
        BanquePopulaireSessionRepository sessionRepository,
        AccountRepository accountRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository memberRepository,
        DebtRepository debtRepository,
        AccountService accountService,
        CategorizationService categorizationService,
        CategoryService categoryService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("banquePopulaireSyncExecutor") Executor syncExecutor,
        CaisseEpargnePerService perService,
        BankLogoResolver logoResolver
    ) {
        this(port, sessionRepository, accountRepository, transactionRepository, memberRepository, debtRepository,
            accountService, categorizationService, categoryService, encryption, txTemplate, syncExecutor,
            perService, logoResolver, Clock.systemDefaultZone());
    }

    BanquePopulaireSyncService(
        BanquePopulairePort port,
        BanquePopulaireSessionRepository sessionRepository,
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
        BankLogoResolver logoResolver,
        Clock clock
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.engine = new BpceSyncEngine<>(
            BANK, SidecarBank.BANQUE_POPULAIRE, port, new Sessions(sessionRepository),
            BanquePopulaireSyncService::toStatus, SessionStatusResponse::inactive,
            session -> brandingFor(session.getRegion(), logoResolver),
            accountRepository, transactionRepository, memberRepository, debtRepository, accountService,
            categorizationService, categoryService, encryption, txTemplate, syncExecutor, perService, clock);
    }

    /**
     * Starts a login at the customer's regional bank. One call to the sidecar, no retry. No
     * password ever reaches Picsou: the sidecar answers with the bank's keypad, which the user
     * clicks himself. A failure leaves any session already stored (valid or not) exactly as it was.
     * The region is kept with the pending login and stored on the session it opens: the later
     * steps never take one from the request.
     */
    public BpceSyncEngine.InitiateResponse initiateAuth(
        String customerId, BanquePopulaireRegion region, Long memberId
    ) {
        Objects.requireNonNull(region, "region");
        return engine.initiateAuth(() -> port.initiateAuth(customerId, region), memberId, sessionAt(region));
    }

    /** Clicks the digits the user chose. Single use, no retry; the positions are never logged. */
    public void submitKeypad(String processId, List<Integer> positions, Long memberId) {
        engine.submitKeypad(processId, positions, memberId);
    }

    /** Finishes a login the user approved on the phone and stores the encrypted session. */
    public SessionStatusResponse completeAuth(String processId, Long memberId) {
        return engine.completeAuth(processId, memberId);
    }

    /**
     * Stores (encrypted) a session state opened at {@code region}, replacing the member's session.
     * It does <b>not</b> queue a sync: the user triggers it.
     */
    public SessionStatusResponse storeSession(String plainState, BanquePopulaireRegion region, Long memberId) {
        Objects.requireNonNull(region, "region");
        return engine.storeSession(plainState, memberId, sessionAt(region));
    }

    private static BpceSyncEngine.SessionFactory<BanquePopulaireSession> sessionAt(BanquePopulaireRegion region) {
        return (member, encryptedState, validatedAt) ->
            BanquePopulaireSession.create(member, encryptedState, validatedAt, region);
    }

    /**
     * The regional bank's official name, and its logo from the Enable Banking catalog. The region
     * is read inside the queueing transaction; the lookup runs later, once per sync, and
     * {@link BankLogoResolver#logoUrlOrNull} never throws: no catalog configured, an outage or no
     * match all leave the logo {@code null}.
     */
    static BpceSyncEngine.Branding brandingFor(BanquePopulaireRegion region, BankLogoResolver logoResolver) {
        String name = region.institutionName();
        return new BpceSyncEngine.Branding(name, () -> logoResolver.logoUrlOrNull(LOGO_COUNTRY, null, name));
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
            List.of(BanquePopulaireSyncStatus.QUEUED, BanquePopulaireSyncStatus.RUNNING),
            BanquePopulaireSyncStatus.FAILED,
            Instant.now(),
            SidecarErrorCode.INTERNAL_ERROR
        );
        if (recovered > 0) {
            log.warn("Recovered {} interrupted Banque Populaire sync job(s)", recovered);
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
        BanquePopulaireSession session, List<BpceSyncEngine.UnsupportedContract> unsupported
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

    /** The Banque Populaire session table, as the engine uses it. */
    private record Sessions(BanquePopulaireSessionRepository repository)
        implements BpceSyncEngine.SessionStore<BanquePopulaireSession> {

        @Override
        public Optional<BanquePopulaireSession> findByMemberId(Long memberId) {
            return repository.findByMemberId(memberId);
        }

        @Override
        public Optional<BanquePopulaireSession> findByMemberIdForUpdate(Long memberId) {
            return repository.findByMemberIdForUpdate(memberId);
        }

        @Override
        public Optional<BanquePopulaireSession> findByIdAndMemberIdForUpdate(Long id, Long memberId) {
            return repository.findByIdAndMemberIdForUpdate(id, memberId);
        }

        @Override
        public void save(BanquePopulaireSession session) {
            repository.save(session);
        }

        @Override
        public void saveAndFlush(BanquePopulaireSession session) {
            repository.saveAndFlush(session);
        }

        @Override
        public void delete(BanquePopulaireSession session) {
            repository.delete(session);
        }

        @Override
        public void flush() {
            repository.flush();
        }
    }

    public record SessionStatusResponse(
        boolean isActive,
        BanquePopulaireSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        SidecarErrorCode lastSyncError,
        List<BpceSyncEngine.UnsupportedContract> unsupported
    ) {
        public SessionStatusResponse {
            unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
        }

        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, BanquePopulaireSyncStatus.IDLE, null, null, null, List.of());
        }

        @JsonProperty("unsupportedCount")
        public int unsupportedCount() {
            return unsupported.size();
        }
    }
}
