package com.picsou.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.CaisseEpargnePort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.SidecarSessionRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Imports Caisse d'Epargne current accounts, savings books, cards, loans and PER, with their
 * transactions. The import itself is the shared {@link BpceSyncEngine}'s; this service binds it to
 * the Caisse d'Epargne port, session table and labels, and owns the transaction boundaries.
 * The sync is <b>on demand only</b>. The password is never stored; the session cookies and the
 * identifier of the last successful login are (encrypted), see the caisse-epargne ADR.
 */
@Service
public class CaisseEpargneSyncService {
    private static final Logger log = LoggerFactory.getLogger(CaisseEpargneSyncService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String PROVIDER = "Caisse d'Epargne";
    static final BpceBank BANK = BpceBank.named(PROVIDER, "ce_");

    /**
     * A PER or an assurance vie imported from the insurer's app: valued from the insurer's own unit
     * prices rather than the generic quote path. One definition for the sync and for the three
     * services that value, snapshot and chart these accounts, so they cannot disagree. A manual
     * account never qualifies, whatever free-text provider the member typed.
     */
    static boolean isInsurerContract(Account account) {
        AccountType type = account.getType();
        String p = account.getProvider(), id = account.getExternalAccountId();
        return (type == AccountType.PER || type == AccountType.ASSURANCE_VIE) && !account.isManual()
            && (PROVIDER.equals(p) || (p != null && p.startsWith(BanquePopulaireSyncService.PROVIDER))
                || (id != null && id.startsWith(BanquePopulaireSyncService.BANK.externalIdPrefix())));
    }


    private final CaisseEpargnePort port;
    private final SidecarSessionRepository sessionRepository;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final BpceSyncEngine<SidecarSession, SessionStatusResponse> engine;

    @org.springframework.beans.factory.annotation.Autowired
    public CaisseEpargneSyncService(
        CaisseEpargnePort port,
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
        @Qualifier("caisseEpargneSyncExecutor") Executor syncExecutor,
        CaisseEpargnePerService perService
    ) {
        this(port, sessionRepository, accountRepository, transactionRepository, memberRepository, debtRepository,
            accountService, categorizationService, categoryService, encryption, txTemplate, syncExecutor,
            perService, Clock.systemDefaultZone());
    }

    CaisseEpargneSyncService(
        CaisseEpargnePort port,
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
        Clock clock
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.engine = new BpceSyncEngine<>(
            BANK, SidecarBank.CAISSE_EPARGNE, port, new Sessions(sessionRepository),
            this::toStatus, SessionStatusResponse::inactive,
            BpceSyncEngine.BrandingSource.fixed(PROVIDER),
            accountRepository, transactionRepository, memberRepository, debtRepository, accountService,
            categorizationService, categoryService, encryption, txTemplate, syncExecutor, perService, clock);
    }

    /**
     * Starts a login. One call to the sidecar, no retry. No password ever reaches Picsou: the
     * sidecar answers with the bank's keypad, which the user clicks himself. A failure leaves any
     * session already stored (valid or not) exactly as it was.
     *
     * <p>A blank {@code customerId} reuses the identifier remembered from the last successful
     * login. If the bank rejects that remembered identifier, it is forgotten so it is never
     * submitted again. The identifier that logs in is stored with the session once approved.
     */
    public BpceSyncEngine.InitiateResponse initiateAuth(String customerId, Long memberId) {
        StoredIdentifier stored = customerId == null || customerId.isBlank()
            ? storedCustomerId(memberId)
            : null;
        String identifier = stored != null ? stored.customerId() : customerId;
        return engine.initiateAuth(() -> {
            try {
                return port.initiateAuth(identifier);
            } catch (SyncException ex) {
                if (stored != null && SyncValues.errorCode(ex, SidecarBank.CAISSE_EPARGNE, SidecarErrorCode.UPSTREAM_UNAVAILABLE)
                    == SidecarErrorCode.INVALID_CREDENTIALS) {
                    forgetStoredCustomerId(memberId, stored.ciphertext());
                }
                throw ex;
            }
        }, memberId, sessionRemembering(identifier));
    }

    /** A session row that also stores the identifier that logged in, encrypted. */
    private BpceSyncEngine.SessionFactory<SidecarSession> sessionRemembering(String customerId) {
        return (member, encryptedState, validatedAt, previous) -> {
            SidecarSession session = SidecarSession.create(SidecarBank.CAISSE_EPARGNE, member, encryptedState, validatedAt);
            session.setEncryptedCredentials(encryptCustomerId(customerId));
            return session;
        };
    }

    /** The remembered identifier, or a refusal when there is none (or it cannot be read). */
    private StoredIdentifier storedCustomerId(Long memberId) {
        String encrypted = sessionRepository.findByBankAndMemberId(SidecarBank.CAISSE_EPARGNE, memberId)
            .map(SidecarSession::getEncryptedCredentials)
            .orElse(null);
        if (encrypted != null) {
            Optional<String> customerId = readCustomerId(encrypted);
            if (customerId.isPresent()) {
                return new StoredIdentifier(customerId.get(), encrypted);
            }
            forgetStoredCustomerId(memberId, encrypted);
        }
        throw new SyncException("No Caisse d'Epargne identifier is remembered. Please enter it.", null,
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
            log.warn("Stored Caisse d'Epargne identifier is unreadable (type={})", ex.getClass().getSimpleName());
        }
        return Optional.empty();
    }

    /**
     * Forgets the remembered identifier, but only if it is still the one that was read: a login
     * that succeeded meanwhile stores a new ciphertext (fresh IV), which must survive.
     */
    private void forgetStoredCustomerId(Long memberId, String readCiphertext) {
        txTemplate.execute(status -> {
            sessionRepository.findByBankAndMemberIdForUpdate(SidecarBank.CAISSE_EPARGNE, memberId)
                .filter(session -> readCiphertext.equals(session.getEncryptedCredentials()))
                .ifPresent(session -> {
                    session.setEncryptedCredentials(null);
                    sessionRepository.save(session);
                });
            return null;
        });
    }

    /** The remembered identifier and the ciphertext it was read from. */
    private record StoredIdentifier(String customerId, String ciphertext) {
        @Override
        public String toString() {
            return "StoredIdentifier[redacted]";
        }
    }

    private String encryptCustomerId(String customerId) {
        try {
            return encryption.encrypt(JSON.writeValueAsString(Map.of("customerId", customerId)));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not encode the Caisse d'Epargne identifier");
        }
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
     * Stores (encrypted) a session state. It does <b>not</b> queue a sync: the user triggers it.
     * Keeps any identifier already remembered.
     */
    public SessionStatusResponse storeSession(String plainState, Long memberId) {
        return engine.storeSession(plainState, memberId, (member, encryptedState, validatedAt, previous) -> {
            SidecarSession session = SidecarSession.create(SidecarBank.CAISSE_EPARGNE, member, encryptedState, validatedAt);
            session.setEncryptedCredentials(previous == null ? null : previous.getEncryptedCredentials());
            return session;
        });
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
            SidecarBank.CAISSE_EPARGNE,
            List.of(SidecarSyncStatus.QUEUED, SidecarSyncStatus.RUNNING),
            SidecarSyncStatus.FAILED,
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

    /** The Caisse d'Epargne rows of the shared sidecar session table, as the engine uses them. */
    private record Sessions(SidecarSessionRepository repository)
        implements BpceSyncEngine.SessionStore<SidecarSession> {

        @Override
        public Optional<SidecarSession> findByMemberId(Long memberId) {
            return repository.findByBankAndMemberId(SidecarBank.CAISSE_EPARGNE, memberId);
        }

        @Override
        public Optional<SidecarSession> findByMemberIdForUpdate(Long memberId) {
            return repository.findByBankAndMemberIdForUpdate(SidecarBank.CAISSE_EPARGNE, memberId);
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
