package com.picsou.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.BanquePopulairePort;
import com.picsou.port.BanquePopulaireRegion;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SidecarSessionRepository;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Imports Banque Populaire current accounts, savings books, term deposits and cooperative shares, with their transactions.
 * Banque Populaire runs on the BPCE platform, so the import is the shared {@link BpceSyncEngine}'s
 * (loans and PER included, should the sidecar ever report them); this service binds it to the
 * Banque Populaire port, its rows of {@code sidecar_session} and labels, and owns the transaction boundaries. The sync
 * is <b>on demand only</b>, like Caisse d'Epargne's: no scheduler, startup or member sync. The
 * password is never stored; like Caisse d'Epargne, the identifier of the last successful login is
 * kept encrypted so reconnections can reuse it. The regional bank chosen at login is stored on the
 * session: each sync stamps its official name on the accounts as their provider, with that bank's
 * logo from the Enable Banking catalog, so they look like the ones Enable Banking would create for
 * that bank.
 */
@Service
public class BanquePopulaireSyncService {
    private static final Logger log = LoggerFactory.getLogger(BanquePopulaireSyncService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The bank's generic name, used in messages, logs and fallback names; accounts carry the regional one. */
    static final String PROVIDER = "Banque Populaire";
    static final BpceBank BANK = BpceBank.named(PROVIDER, "bp_");
    /** Every regional bank is French: narrows the catalog search to one country. */
    static final String LOGO_COUNTRY = "FR";

    private final BanquePopulairePort port;
    private final SidecarSessionRepository sessionRepository;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final BpceSyncEngine<SidecarSession, SessionStatusResponse> engine;

    @org.springframework.beans.factory.annotation.Autowired
    public BanquePopulaireSyncService(
        BanquePopulairePort port,
        SidecarSessionRepository sessionRepository,
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
        SidecarSessionRepository sessionRepository,
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
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.engine = new BpceSyncEngine<>(
            BANK, SidecarBank.BANQUE_POPULAIRE, port, new Sessions(sessionRepository),
            this::toStatus, SessionStatusResponse::inactive,
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
     *
     * <p>A blank {@code customerId} reuses the identifier and the regional bank remembered from the
     * last successful login ({@code region} is then ignored, so the remembered identifier never goes
     * to another regional bank). If the bank rejects that remembered identifier, it is forgotten so
     * it is never submitted again. The identifier that logs in is stored with the session once approved.
     */
    public BpceSyncEngine.InitiateResponse initiateAuth(
        String customerId, BanquePopulaireRegion region, Long memberId
    ) {
        StoredLogin stored = customerId == null || customerId.isBlank()
            ? storedLogin(memberId)
            : null;
        String identifier = stored != null ? stored.customerId() : customerId;
        BanquePopulaireRegion effectiveRegion = stored != null
            ? stored.region()
            : Objects.requireNonNull(region, "region");
        return engine.initiateAuth(() -> {
            try {
                return port.initiateAuth(identifier, effectiveRegion);
            } catch (SyncException ex) {
                if (stored != null && SyncValues.errorCode(ex, SidecarBank.BANQUE_POPULAIRE, SidecarErrorCode.UPSTREAM_UNAVAILABLE)
                    == SidecarErrorCode.INVALID_CREDENTIALS) {
                    forgetStoredCustomerId(memberId, stored.ciphertext());
                }
                throw ex;
            }
        }, memberId, sessionRemembering(identifier, effectiveRegion));
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
     * It does <b>not</b> queue a sync: the user triggers it. Keeps any identifier already remembered.
     */
    public SessionStatusResponse storeSession(String plainState, BanquePopulaireRegion region, Long memberId) {
        Objects.requireNonNull(region, "region");
        return engine.storeSession(plainState, memberId, sessionAt(region));
    }

    private static BpceSyncEngine.SessionFactory<SidecarSession> sessionAt(BanquePopulaireRegion region) {
        return (member, encryptedState, validatedAt, previous) -> {
            SidecarSession session = SidecarSession.createBanquePopulaire(member, encryptedState, validatedAt, region);
            if (previous != null) {
                session.setEncryptedCredentials(previous.getEncryptedCredentials());
            }
            return session;
        };
    }

    /** A session row that also stores the identifier that logged in, encrypted. */
    private BpceSyncEngine.SessionFactory<SidecarSession> sessionRemembering(
        String customerId, BanquePopulaireRegion region
    ) {
        return (member, encryptedState, validatedAt, previous) -> {
            SidecarSession session = SidecarSession.createBanquePopulaire(member, encryptedState, validatedAt, region);
            session.setEncryptedCredentials(encryptCustomerId(customerId));
            return session;
        };
    }

    /** The remembered login (identifier and region), or a refusal when there is none (or it cannot be read). */
    private StoredLogin storedLogin(Long memberId) {
        Optional<SidecarSession> sessionOpt = sessionRepository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, memberId);
        if (sessionOpt.isPresent()) {
            SidecarSession session = sessionOpt.get();
            String encrypted = session.getEncryptedCredentials();
            if (encrypted != null) {
                Optional<String> customerId = readCustomerId(encrypted);
                if (customerId.isPresent()) {
                    return new StoredLogin(customerId.get(), session.getRegion(), encrypted);
                }
                forgetStoredCustomerId(memberId, encrypted);
            }
        }
        throw new SyncException("No Banque Populaire identifier is remembered. Please enter it.", null,
            SidecarErrorCode.INVALID_CREDENTIALS.name());
    }

    /** The identifier inside a stored ciphertext, or empty when it is unreadable or blank. */
    private Optional<String> readCustomerId(String encrypted) {
        try {
            String customerId = JSON.readTree(encryption.decrypt(encrypted)).path("customerId").asText("");
            if (!customerId.isBlank()) {
                return Optional.of(customerId);
            }
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Stored Banque Populaire identifier is unreadable (type={})", ex.getClass().getSimpleName());
        }
        return Optional.empty();
    }

    /**
     * Forgets the remembered identifier, but only if it is still the one that was read: a login
     * that succeeded meanwhile stores a new ciphertext (fresh IV), which must survive.
     */
    private void forgetStoredCustomerId(Long memberId, String readCiphertext) {
        txTemplate.execute(status -> {
            sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.BANQUE_POPULAIRE, memberId)
                .filter(session -> readCiphertext.equals(session.getEncryptedCredentials()))
                .ifPresent(session -> {
                    session.setEncryptedCredentials(null);
                    sessionRepository.save(session);
                });
            return null;
        });
    }

    /** The remembered identifier, region and the ciphertext it was read from. */
    private record StoredLogin(String customerId, BanquePopulaireRegion region, String ciphertext) {
        @Override
        public String toString() {
            return "StoredLogin[redacted]";
        }
    }

    private String encryptCustomerId(String customerId) {
        try {
            return encryption.encrypt(JSON.writeValueAsString(Map.of("customerId", customerId)));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not encode the Banque Populaire identifier");
        }
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
            SidecarBank.BANQUE_POPULAIRE,
            List.of(SidecarSyncStatus.QUEUED, SidecarSyncStatus.RUNNING),
            SidecarSyncStatus.FAILED,
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

    /** Whether the stored identifier is readable is part of the answer, so it is decrypted here; nothing is written. */
    private SessionStatusResponse toStatus(
        SidecarSession session, List<BpceSyncEngine.UnsupportedContract> unsupported
    ) {
        String encryptedCredentials = session.getEncryptedCredentials();
        return new SessionStatusResponse(
            session.isActive(),
            session.getSyncStatus(),
            session.getLastSyncStartedAt(),
            session.getLastSyncCompletedAt(),
            session.getLastSyncError(),
            unsupported,
            encryptedCredentials != null && readCustomerId(encryptedCredentials).isPresent()
        );
    }

    /** The Banque Populaire rows of the shared sidecar session table, as the engine uses them. */
    private record Sessions(SidecarSessionRepository repository)
        implements BpceSyncEngine.SessionStore<SidecarSession> {

        @Override
        public Optional<SidecarSession> findByMemberId(Long memberId) {
            return repository.findByBankAndMemberId(SidecarBank.BANQUE_POPULAIRE, memberId);
        }

        @Override
        public Optional<SidecarSession> findByMemberIdForUpdate(Long memberId) {
            return repository.findByBankAndMemberIdForUpdate(SidecarBank.BANQUE_POPULAIRE, memberId);
        }

        @Override
        public Optional<SidecarSession> findByIdAndMemberIdForUpdate(Long id, Long memberId) {
            return repository.findByIdAndMemberIdForUpdate(id, memberId);
        }

        @Override
        public void save(SidecarSession session) {
            repository.save(session);
        }

        @Override
        public void saveAndFlush(SidecarSession session) {
            repository.saveAndFlush(session);
        }

        @Override
        public void delete(SidecarSession session) {
            repository.delete(session);
        }

        @Override
        public void flush() {
            repository.flush();
        }
    }

    public record SessionStatusResponse(
        boolean isActive,
        SidecarSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        SidecarErrorCode lastSyncError,
        List<BpceSyncEngine.UnsupportedContract> unsupported,
        /** An identifier is remembered: a reconnect can send a blank one. Never the value itself. */
        boolean remembered
    ) implements SidecarSessionView {
        public SessionStatusResponse {
            unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
        }

        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, SidecarSyncStatus.IDLE, null, null, null, List.of(), false);
        }

        @JsonProperty("unsupportedCount")
        public int unsupportedCount() {
            return unsupported.size();
        }
    }
}
