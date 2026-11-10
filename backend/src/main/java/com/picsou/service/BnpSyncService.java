package com.picsou.service;

import com.picsou.adapter.BnpAdapter;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.SidecarTransaction;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SidecarSessionRepository;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.sync.SourceSyncResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import static com.picsou.service.SyncValues.clean;
import static com.picsou.service.SyncValues.limit;
import static com.picsou.service.SyncValues.moneyClose;
import static com.picsou.service.SyncValues.requireTransactionResult;

/**
 * BNP Paribas and Hello bank! (one platform, two base URLs). The member types the customer
 * number, clicks the six digits on the bank's shuffled keypad and confirms the strong
 * authentication; only the resulting cookie jar is kept, encrypted, in {@code sidecar_session}.
 * Nothing the member typed or clicked is stored, so an expired session is renewed by a new login.
 *
 * <p>Fail closed: an incomplete snapshot never replaces what a previous sync imported. Products
 * the connector does not read are reported by id and family only.
 */
@Service
public class BnpSyncService {

    private static final Logger log = LoggerFactory.getLogger(BnpSyncService.class);

    static final String PROVIDER = "BNP Paribas";
    static final String HELLO_BANK_PROVIDER = "Hello bank!";
    /** The names an Enable Banking link of the same bank may carry; matched by IBAN, never by name alone. */
    static final List<String> PROVIDER_FAMILY = List.of(PROVIDER, HELLO_BANK_PROVIDER, "Hello bank", "Hello Bank");
    static final String SOURCE = "bnp";

    private static final String EXTERNAL_ID_PREFIX = "bnp_";
    private static final int MAX_EXTERNAL_ID_LENGTH = 100;
    private static final int MAX_TICKER_LENGTH = 30;
    private static final int MAX_UNSUPPORTED = 100;
    private static final int KEYPAD_KEY_COUNT = 10;
    private static final int KEYPAD_COLUMNS = 5;
    private static final long MAX_ATTEMPT_SECONDS = 600;
    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");
    private static final Pattern DIGIT_KEY = Pattern.compile("[0-9]");

    private static final Set<AccountType> SUPPORTED_TYPES = EnumSet.of(
        AccountType.CHECKING, AccountType.SAVINGS, AccountType.LEP, AccountType.LIVRET_A, AccountType.LDDS,
        AccountType.LIVRET_JEUNE, AccountType.PEL, AccountType.CEL, AccountType.PEA, AccountType.COMPTE_TITRES,
        AccountType.ASSURANCE_VIE, AccountType.PER, AccountType.LOAN);

    private final BnpAdapter adapter;
    private final SidecarSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final FamilyMemberRepository memberRepository;
    private final AccountService accountService;
    private final CategorizationService categorizationService;
    private final SharedSidecarTransactionImportService transactionImporter;
    private final com.picsou.adapter.OpenFigiIsinConverter isinConverter;
    private final SecurityIdentityService identityService;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final SidecarSyncTemplate<Prepared> sync;
    private final Clock clock;
    private final Map<String, PendingLogin> pendingLogins = new ConcurrentHashMap<>();
    private final Map<Long, Long> authGenerations = new ConcurrentHashMap<>();
    private final Object sessionWriteLock = new Object();

    @org.springframework.beans.factory.annotation.Autowired
    public BnpSyncService(
        BnpAdapter adapter,
        SidecarSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        FamilyMemberRepository memberRepository,
        AccountService accountService,
        CategorizationService categorizationService,
        SharedSidecarTransactionImportService transactionImporter,
        com.picsou.adapter.OpenFigiIsinConverter isinConverter,
        SecurityIdentityService identityService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("bnpSyncExecutor") Executor syncExecutor
    ) {
        this(adapter, sessionRepository, accountRepository, holdingRepository, memberRepository, accountService,
            categorizationService, transactionImporter, isinConverter, identityService, encryption, txTemplate,
            syncExecutor, Clock.systemDefaultZone());
    }

    BnpSyncService(
        BnpAdapter adapter,
        SidecarSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        FamilyMemberRepository memberRepository,
        AccountService accountService,
        CategorizationService categorizationService,
        SharedSidecarTransactionImportService transactionImporter,
        com.picsou.adapter.OpenFigiIsinConverter isinConverter,
        SecurityIdentityService identityService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        Executor syncExecutor,
        Clock clock
    ) {
        this.adapter = adapter;
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
        this.clock = clock;
        this.sync = new SidecarSyncTemplate<>(SidecarBank.BNP, new Strategy(), sessionRepository,
            memberRepository, encryption, txTemplate, syncExecutor, false);
    }

    // ─── Login ────────────────────────────────────────────────────────────────────────────

    public InitiateResponse initiateAuth(String customerId, String brand, Long memberId) {
        long generation = authGenerations.getOrDefault(memberId, 0L);
        BnpAdapter.InitiateResult result = adapter.initiateAuth(customerId, brand);
        if (result == null || clean(result.processId()) == null || result.keypad() == null
            || result.expiresInSeconds() <= 0) {
            throw error(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED,
                "BNP Paribas did not return a usable sign-in challenge", null);
        }
        requireSafeKeypad(result.keypad());
        Instant now = clock.instant();
        long seconds = Math.min(result.expiresInSeconds(), MAX_ATTEMPT_SECONDS);
        synchronized (sessionWriteLock) {
            if (generation != authGenerations.getOrDefault(memberId, 0L)) {
                throw error(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED,
                    "This BNP Paribas sign-in attempt expired. Please start again.", null);
            }
            pendingLogins.values().removeIf(pending -> !pending.deadline().isAfter(now));
            pendingLogins.put(result.processId(), new PendingLogin(memberId, now.plusSeconds(seconds), generation,
                Stage.KEYPAD));
        }
        return new InitiateResponse(result.processId(), result.keypad(), (int) seconds);
    }

    /** Ten distinct digit keys in five columns: anything else means the page changed. */
    private void requireSafeKeypad(BnpAdapter.Keypad keypad) {
        boolean safe = keypad.columns() == KEYPAD_COLUMNS
            && keypad.keys() != null
            && keypad.keys().size() == KEYPAD_KEY_COUNT
            && new HashSet<>(keypad.keys()).size() == KEYPAD_KEY_COUNT;
        if (safe) {
            for (String key : keypad.keys()) {
                if (key == null || !DIGIT_KEY.matcher(key).matches()) {
                    safe = false;
                    break;
                }
            }
        }
        if (!safe) {
            throw error(SidecarErrorCode.KEYPAD_CHANGED, "BNP Paribas changed its login page. Nothing was sent.", null);
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void evictExpiredPendingLogins() {
        Instant now = clock.instant();
        pendingLogins.entrySet().removeIf(entry -> !entry.getValue().deadline().isAfter(now));
    }

    public KeypadResponse submitKeypad(String processId, List<Integer> positions, Long memberId) {
        PendingLogin current = ownedAttempt(processId, memberId);
        if (current.stage() != Stage.KEYPAD) {
            // The bank counts a second code against the lockout: a replay is refused here.
            throw error(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED, "The keypad was already sent for this sign-in", null);
        }
        BnpAdapter.KeypadAck ack;
        try {
            ack = adapter.submitKeypad(processId, positions);
        } catch (SyncException ex) {
            // A refused or timed-out code ends the attempt: the member starts again with a new keypad.
            pendingLogins.remove(processId);
            throw ex;
        }
        boolean codeRequired = BnpAdapter.KeypadAck.CODE_REQUIRED.equals(ack.status());
        synchronized (sessionWriteLock) {
            requireGeneration(memberId, current.generation());
            pendingLogins.replace(processId, current, current.withStage(codeRequired ? Stage.CODE : Stage.APPROVAL));
        }
        return new KeypadResponse(processId, ack.status());
    }

    public SessionStatusResponse completeAuth(String processId, String code, Long memberId) {
        PendingLogin current = ownedAttempt(processId, memberId);
        if (current.stage() == Stage.KEYPAD) {
            throw error(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED, "The keypad was not sent for this sign-in", null);
        }
        boolean codeRequired = current.stage() == Stage.CODE;
        if (codeRequired != (code != null)) {
            throw error(codeRequired ? SidecarErrorCode.INVALID_OTP : SidecarErrorCode.INVALID_DATA,
                codeRequired ? "BNP Paribas expects the code it sent" : "No code is expected for this sign-in", null);
        }
        String plainState;
        try {
            plainState = adapter.completeAuth(processId, code);
        } catch (SyncException ex) {
            // A mistyped code can be typed again; every other failure ends the attempt.
            if (!(codeRequired && SyncValues.errorCode(ex, SidecarBank.BNP, SidecarErrorCode.INTERNAL_ERROR)
                == SidecarErrorCode.INVALID_OTP)) {
                pendingLogins.remove(processId);
            }
            throw ex;
        }
        pendingLogins.remove(processId);
        if (clean(plainState) == null) {
            throw error(SidecarErrorCode.INVALID_DATA, "BNP Paribas did not return a session", null);
        }
        SidecarSyncJob job;
        synchronized (sessionWriteLock) {
            requireGeneration(memberId, current.generation());
            job = requireTransactionResult(txTemplate.execute(status ->
                sync.insertQueued(memberId, plainState, false, session -> { })));
        }
        sync.submit(job);
        return getStatus(memberId);
    }

    private PendingLogin ownedAttempt(String processId, Long memberId) {
        PendingLogin attempt = processId == null ? null : pendingLogins.get(processId);
        if (attempt == null || !attempt.memberId().equals(memberId) || !attempt.deadline().isAfter(clock.instant())) {
            throw error(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED, "BNP Paribas sign-in attempt expired", null);
        }
        return attempt;
    }

    private void requireGeneration(Long memberId, long generation) {
        if (authGenerations.getOrDefault(memberId, 0L) != generation) {
            throw error(SidecarErrorCode.AUTH_ATTEMPT_EXPIRED, "The BNP Paribas connection was removed during the login", null);
        }
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────────────────

    public SessionStatusResponse queueSync(Long memberId) {
        return toStatus(sync.queue(memberId, false));
    }

    private final class Strategy implements SidecarSyncStrategy<Prepared> {
        @Override
        public Prepared fetch(SidecarSyncJob job) {
            BnpAdapter.AccountsSnapshot snapshot = adapter.fetchAccounts(job.plainState());
            Prepared prepared = prepare(snapshot);
            storeRefreshedState(job, snapshot.sessionState());
            return prepared;
        }

        @Override
        public void commit(SidecarSyncJob job, Prepared prepared, Instant syncedAt) {
            FamilyMember member = memberRepository.findById(job.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            CategorizationService.CategorizationContext context = categorizationService.loadContext(job.memberId());
            // Read before the upserts: only what a previous sync imported is a candidate for closing.
            List<Account> imported = accountRepository.findAllByMemberIdAndProvider(job.memberId(), prepared.provider());
            for (PreparedAccount data : prepared.accounts()) {
                upsertAccount(data, prepared.provider(), member, job.memberId(), syncedAt, context);
            }
            closeUnlistedAccounts(prepared, imported, job.memberId(), syncedAt);
            storeUnsupported(job, prepared.unsupported());
        }

        @Override
        public boolean redactsUnexpectedFailures() {
            return true;
        }

        @Override
        public String describe(Prepared prepared) {
            return "accounts=" + prepared.accounts().size() + ", unsupported=" + prepared.unsupported().size();
        }
    }

    private void storeRefreshedState(SidecarSyncJob job, String plainState) {
        if (clean(plainState) == null) {
            return;
        }
        txTemplate.executeWithoutResult(status -> sessionRepository
            .findByIdAndMemberIdForUpdate(job.sessionId(), job.memberId())
            .filter(session -> session.isActive() && session.getSyncStatus() == SidecarSyncStatus.RUNNING)
            .ifPresent(session -> {
                session.updateSessionState(encryption.encrypt(plainState), Instant.now());
                sessionRepository.save(session);
            }));
    }

    private void storeUnsupported(SidecarSyncJob job, List<UnsupportedProduct> unsupported) {
        unsupportedByMember.put(job.memberId(), List.copyOf(unsupported));
    }

    /** Informational only; rebuilt by every successful sync, so a restart simply shows none until the next one. */
    private final Map<Long, List<UnsupportedProduct>> unsupportedByMember = new ConcurrentHashMap<>();

    private Prepared prepare(BnpAdapter.AccountsSnapshot snapshot) {
        if (snapshot == null || snapshot.accounts().isEmpty()) {
            throw error(SidecarErrorCode.PORTFOLIO_INCOMPLETE, "BNP Paribas returned no account", null);
        }
        String provider = "hellobank".equals(snapshot.brand()) ? HELLO_BANK_PROVIDER : PROVIDER;
        Set<String> externalIds = new HashSet<>();
        List<PreparedAccount> accounts = new ArrayList<>();
        for (BnpAdapter.AccountData account : snapshot.accounts()) {
            if (account == null || !account.snapshotComplete()) {
                throw error(SidecarErrorCode.PORTFOLIO_INCOMPLETE, "BNP Paribas returned an incomplete snapshot", null);
            }
            String externalId = stableExternalId(account.externalId());
            if (!externalIds.add(externalId)) {
                throw error(SidecarErrorCode.INVALID_DATA, "BNP Paribas returned duplicate accounts", null);
            }
            accounts.add(prepareAccount(account, externalId));
        }
        List<UnsupportedProduct> unsupported = new ArrayList<>();
        for (BnpAdapter.Unsupported item : snapshot.unsupported()) {
            if (item == null || clean(item.externalId()) == null || clean(item.familyCode()) == null) {
                throw error(SidecarErrorCode.INVALID_DATA, "BNP Paribas returned an invalid unsupported product list", null);
            }
            if (unsupported.size() >= MAX_UNSUPPORTED) {
                log.warn("BNP Paribas unsupported-product list truncated at {}", MAX_UNSUPPORTED);
                break;
            }
            unsupported.add(new UnsupportedProduct(item.externalId().trim(), item.familyCode().trim()));
        }
        return new Prepared(provider, List.copyOf(accounts), List.copyOf(unsupported));
    }

    private PreparedAccount prepareAccount(BnpAdapter.AccountData account, String externalId) {
        AccountType type = typeOf(account.kind());
        if (account.balance() == null) {
            throw error(SidecarErrorCode.INVALID_DATA, "BNP Paribas returned an account without a balance", null);
        }
        if (!"EUR".equalsIgnoreCase(clean(account.currency()))) {
            throw error(SidecarErrorCode.INVALID_DATA, "BNP Paribas returned a non-euro account", null);
        }
        BigDecimal balance = account.balance();
        if (type == AccountType.LOAN) {
            if (balance.signum() > 0) {
                throw error(SidecarErrorCode.INVALID_DATA, "BNP Paribas returned a loan with a positive outstanding", null);
            }
            // Picsou stores a loan positive and applies the liability sign itself.
            balance = balance.negate();
        } else if (type != AccountType.CHECKING && balance.signum() < 0) {
            throw error(SidecarErrorCode.INVALID_DATA, "BNP Paribas returned a negative balance on a savings product", null);
        }
        boolean securities = type == AccountType.PEA || type == AccountType.COMPTE_TITRES;
        if (!securities && (!account.positions().isEmpty() || account.cashBalance() != null)) {
            throw error(SidecarErrorCode.INVALID_DATA, "BNP Paribas reported securities on another account type", null);
        }
        List<PreparedPosition> positions = List.of();
        BigDecimal cash = null;
        BigDecimal invested = null;
        if (securities) {
            cash = account.cashBalance();
            if (cash == null || cash.signum() < 0) {
                throw error(SidecarErrorCode.INVALID_DATA, "BNP Paribas returned no usable cash balance for a securities account", null);
            }
            positions = preparePositions(account.positions());
            BigDecimal lines = positions.stream().map(PreparedPosition::valueEur).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (!moneyClose(lines.add(cash), balance)) {
                throw error(SidecarErrorCode.PORTFOLIO_INCOMPLETE, "BNP Paribas returned an incomplete portfolio", null);
            }
            invested = investedAmount(balance, cash, positions);
        }
        return new PreparedAccount(
            externalId,
            limit(account.name(), 100, "BNP Paribas account"),
            type,
            balance,
            cash,
            invested,
            positions,
            verifiedIban(account.iban()),
            account.transactions()
        );
    }

    private AccountType typeOf(String kind) {
        if (kind == null) {
            throw error(SidecarErrorCode.INVALID_DATA, "BNP Paribas returned an account without a kind", null);
        }
        AccountType type = switch (kind) {
            case "CURRENT_ACCOUNT" -> AccountType.CHECKING;
            case "SAVINGS" -> AccountType.SAVINGS;
            case "LIVRET_A" -> AccountType.LIVRET_A;
            case "LDDS" -> AccountType.LDDS;
            case "LEP" -> AccountType.LEP;
            case "LIVRET_JEUNE" -> AccountType.LIVRET_JEUNE;
            case "PEL" -> AccountType.PEL;
            case "CEL" -> AccountType.CEL;
            case "PEA" -> AccountType.PEA;
            case "COMPTE_TITRES" -> AccountType.COMPTE_TITRES;
            case "ASSURANCE_VIE" -> AccountType.ASSURANCE_VIE;
            case "PER" -> AccountType.PER;
            case "LOAN" -> AccountType.LOAN;
            // Never guess: an unknown kind means the format moved under us.
            default -> null;
        };
        if (type == null || !SUPPORTED_TYPES.contains(type)) {
            throw error(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED, "BNP Paribas returned an unknown account kind", null);
        }
        return type;
    }

    private List<PreparedPosition> preparePositions(List<BnpAdapter.Position> raw) {
        Map<String, PreparedPosition> positions = new LinkedHashMap<>();
        Map<String, String> isinByTicker = new LinkedHashMap<>();
        for (BnpAdapter.Position position : raw) {
            if (position == null || position.quantity() == null || position.value() == null) {
                throw error(SidecarErrorCode.INVALID_DATA, "BNP Paribas returned an incomplete position", null);
            }
            if (position.quantity().signum() == 0) {
                continue;
            }
            String cleaned = clean(position.isin());
            String isin = cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
            if (isin == null || !ISIN.matcher(isin).matches()) {
                throw error(SidecarErrorCode.INVALID_DATA, "BNP Paribas returned a line without a valid ISIN", null);
            }
            String ticker = resolveTicker(isin);
            if (positions.containsKey(ticker) && !isin.equals(isinByTicker.get(ticker))) {
                throw error(SidecarErrorCode.INVALID_DATA, "BNP Paribas returned two instruments under one ticker", null);
            }
            isinByTicker.put(ticker, isin);
            PreparedPosition line = new PreparedPosition(ticker, limit(position.label(), 100, ticker),
                position.quantity(), averageBuyIn(position), position.value(), position.pnl());
            positions.merge(ticker, line, this::merge);
        }
        identityService.record(isinByTicker);
        return positions.values().stream().filter(line -> line.quantity().signum() != 0).toList();
    }

    private String resolveTicker(String isin) {
        com.picsou.adapter.OpenFigiIsinConverter.TickerResult resolved = isinConverter.resolve(isin);
        String ticker = resolved != null ? clean(resolved.ticker()) : null;
        if (ticker == null || ticker.length() > MAX_TICKER_LENGTH) {
            ticker = isin;
        }
        return ticker;
    }

    private static BigDecimal averageBuyIn(BnpAdapter.Position position) {
        if (position.pnl() == null) {
            return null;
        }
        return position.value().subtract(position.pnl()).divide(position.quantity(), 8, RoundingMode.HALF_UP);
    }

    private PreparedPosition merge(PreparedPosition left, PreparedPosition right) {
        BigDecimal quantity = left.quantity().add(right.quantity());
        return new PreparedPosition(left.ticker(), left.name(), quantity,
            SyncValues.weightedAverage(left.averageBuyInEur(), left.quantity(), right.averageBuyInEur(),
                right.quantity(), quantity),
            left.valueEur().add(right.valueEur()), SyncValues.sumComplete(left.pnlEur(), right.pnlEur()));
    }

    private static BigDecimal investedAmount(BigDecimal balance, BigDecimal cash, List<PreparedPosition> positions) {
        BigDecimal invested = cash;
        for (PreparedPosition line : positions) {
            if (line.averageBuyInEur() != null) {
                invested = invested.add(line.averageBuyInEur().multiply(line.quantity()));
            } else if (line.pnlEur() != null) {
                invested = invested.add(line.valueEur().subtract(line.pnlEur()));
            } else {
                return balance;
            }
        }
        return invested;
    }

    /** The IBAN is a dedup key: anything that is not a valid IBAN is treated as absent. */
    static String verifiedIban(String raw) {
        String iban = clean(raw);
        if (iban == null) {
            return null;
        }
        String normalized = iban.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}")) {
            return null;
        }
        String rearranged = normalized.substring(4) + normalized.substring(0, 4);
        int remainder = 0;
        for (int index = 0; index < rearranged.length(); index++) {
            char character = rearranged.charAt(index);
            remainder = character >= '0' && character <= '9'
                ? (remainder * 10 + character - '0') % 97
                : (remainder * 100 + character - 'A' + 10) % 97;
        }
        return remainder == 1 ? normalized : null;
    }

    private void upsertAccount(PreparedAccount data, String provider, FamilyMember member, Long memberId,
                               Instant syncedAt, CategorizationService.CategorizationContext context) {
        Optional<Account> existing = accountRepository.findByExternalAccountIdAndMemberId(data.externalId(), memberId);
        if (existing.isEmpty() && data.iban() != null) {
            // One account per IBAN: an Enable Banking link of the same bank is adopted, not duplicated.
            existing = accountRepository.findFirstByIbanAndMemberIdAndProviderIn(data.iban(), memberId, PROVIDER_FAMILY);
        }
        if (existing.isEmpty()
            && (accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), memberId)
                || (data.iban() != null && accountRepository
                    .existsSoftDeletedByIbanAndMemberIdAndProviderIn(data.iban(), memberId, PROVIDER_FAMILY)))) {
            log.info("BNP Paribas skipped a soft-deleted account (member={})", memberId);
            return;
        }
        Account account = existing.orElseGet(() -> Account.builder()
            .member(member)
            .externalAccountId(data.externalId())
            .provider(provider)
            .currency("EUR")
            .isManual(false)
            .color(colorFor(data.type()))
            .build());
        account.setName(data.name());
        account.setType(data.type());
        account.setProvider(provider);
        account.setCurrency("EUR");
        account.setManual(false);
        account.setExternalAccountId(data.externalId());
        if (data.iban() != null) {
            account.setIban(data.iban());
        }
        account.setCurrentBalance(data.balanceEur());
        account.setCashBalance(data.cashBalance());
        account.setLastSyncedAt(syncedAt);
        Account saved = accountRepository.save(account);
        transactionImporter.importFor(saved, data.transactions(), context, SOURCE);
        holdingRepository.deleteByAccountId(saved.getId());
        holdingRepository.flush();
        holdingRepository.saveAll(data.positions().stream()
            .map(position -> AccountHolding.builder()
                .account(saved)
                .ticker(position.ticker())
                .name(position.name())
                .quantity(position.quantity())
                .averageBuyIn(position.averageBuyInEur())
                .providerValueEur(position.valueEur())
                .providerPnlEur(position.pnlEur())
                .lastSyncedAt(syncedAt)
                .build())
            .toList());
        holdingRepository.flush();
        if (data.investedAmountEur() != null) {
            accountService.upsertSnapshot(saved, data.balanceEur(), data.investedAmountEur(), LocalDate.now(clock));
        } else {
            accountService.upsertSnapshot(saved, data.balanceEur(), LocalDate.now(clock));
        }
    }

    /**
     * Zeroes the imported accounts a complete snapshot no longer lists: the customer closed or
     * transferred them. Closed at zero rather than soft deleted, which would read as removed by
     * the member and block an account that reopens.
     *
     * <p>Only a complete snapshot gets here: {@link #prepare} fails the whole sync on any
     * incomplete or invalid account, so {@code commit} never sees a partial listing and a failed
     * sync closes nothing. A loan the bank closed is dropped by the sidecar (it carries only
     * nulls), so it is closed at zero here, which is right.
     *
     * <p>Candidates are the accounts of the snapshot's own provider ({@code BNP Paribas} or
     * {@code Hello bank!}, so the two brands never close each other) whose id carries the
     * {@link #EXTERNAL_ID_PREFIX}: a manual account or an Enable Banking one is never touched. A
     * product the sidecar lists as unsupported is still held by the customer, so it keeps its
     * last imported balance. Once that list reaches its cap it may omit a product that turned
     * unsupported, so nothing is closed then (an exact-cap list is treated the same: erring toward
     * keeping a balance, never zeroing one).
     */
    private void closeUnlistedAccounts(Prepared prepared, List<Account> imported, Long memberId, Instant syncedAt) {
        if (prepared.unsupported().size() >= MAX_UNSUPPORTED) {
            log.warn("BNP Paribas unsupported-product list is capped, keeping every unlisted account (member={})", memberId);
            return;
        }
        Set<String> kept = new HashSet<>();
        prepared.accounts().forEach(account -> kept.add(account.externalId()));
        prepared.unsupported().forEach(product -> kept.add(prefixed(product.externalId())));
        for (Account account : imported) {
            String externalId = account.getExternalAccountId();
            if (externalId == null || !externalId.startsWith(EXTERNAL_ID_PREFIX) || kept.contains(externalId)) {
                continue;
            }
            if (account.getCurrentBalance() != null && account.getCurrentBalance().signum() == 0) {
                continue;
            }
            log.info("BNP Paribas no longer lists an imported account, closing it at zero (member={}; account={})",
                memberId, account.getId());
            account.setCurrentBalance(BigDecimal.ZERO);
            account.setCashBalance(null);
            account.setLastSyncedAt(syncedAt);
            Account saved = accountRepository.save(account);
            holdingRepository.deleteByAccountId(saved.getId());
            accountService.upsertSnapshot(saved, BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.now(clock));
        }
    }

    private static String colorFor(AccountType type) {
        return switch (type) {
            case PEA -> "#10b981";
            case COMPTE_TITRES -> "#3b82f6";
            case ASSURANCE_VIE, PER -> "#8b5cf6";
            case SAVINGS, LEP, LIVRET_A, LDDS, LIVRET_JEUNE, PEL, CEL -> "#f59e0b";
            case LOAN -> "#64748b";
            default -> "#16a34a";
        };
    }

    // ─── Status ───────────────────────────────────────────────────────────────────────────

    @Transactional
    public void recoverInterruptedSyncs() {
        sync.recoverInterrupted();
    }

    @Transactional(readOnly = true)
    public SessionStatusResponse getStatus(Long memberId) {
        return sync.find(memberId).map(this::toStatus).orElseGet(SessionStatusResponse::inactive);
    }

    public boolean clearSession(Long memberId) {
        synchronized (sessionWriteLock) {
            authGenerations.merge(memberId, 1L, Long::sum);
            pendingLogins.entrySet().removeIf(entry -> entry.getValue().memberId().equals(memberId));
        }
        unsupportedByMember.remove(memberId);
        return sync.clear(memberId);
    }

    public SourceSyncResult resyncReporting(Long memberId) {
        return sync.resyncReporting(memberId, SOURCE, log, this::getStatus, this::queueSync);
    }

    private SessionStatusResponse toStatus(SidecarSession session) {
        return new SessionStatusResponse(
            session.isActive(),
            session.getSyncStatus(),
            session.getLastSyncStartedAt(),
            session.getLastSyncCompletedAt(),
            session.getLastSyncError(),
            unsupportedByMember.getOrDefault(session.getMember().getId(), List.of())
        );
    }

    private static SyncException error(SidecarErrorCode code, String message, Throwable cause) {
        return SidecarSyncTemplate.error(code, message, cause);
    }

    private static String stableExternalId(String raw) {
        String cleaned = clean(raw);
        String externalId = cleaned == null ? null : prefixed(cleaned);
        if (externalId == null || externalId.length() > MAX_EXTERNAL_ID_LENGTH) {
            throw error(SidecarErrorCode.INVALID_DATA, "BNP Paribas returned an invalid account identifier", null);
        }
        return externalId;
    }

    /** The form an id is stored under, shared by accounts and unsupported products so they compare exactly. */
    private static String prefixed(String cleanedId) {
        return cleanedId.startsWith(EXTERNAL_ID_PREFIX) ? cleanedId : EXTERNAL_ID_PREFIX + cleanedId;
    }

    // ─── Types ────────────────────────────────────────────────────────────────────────────

    private enum Stage { KEYPAD, APPROVAL, CODE }

    private record PendingLogin(Long memberId, Instant deadline, long generation, Stage stage) {
        PendingLogin withStage(Stage next) {
            return new PendingLogin(memberId, deadline, generation, next);
        }
    }

    public record InitiateResponse(String processId, BnpAdapter.Keypad keypad, int expiresInSeconds) {}

    public record KeypadResponse(String processId, String status) {}

    public record UnsupportedProduct(String externalId, String familyCode) {}

    public record SessionStatusResponse(
        boolean isActive,
        SidecarSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        SidecarErrorCode lastSyncError,
        List<UnsupportedProduct> unsupported
    ) implements SidecarSessionView {
        public SessionStatusResponse {
            unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
        }

        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, SidecarSyncStatus.IDLE, null, null, null, List.of());
        }

        @JsonProperty("unsupportedCount")
        public int unsupportedCount() {
            return unsupported.size();
        }
    }

    private record Prepared(String provider, List<PreparedAccount> accounts, List<UnsupportedProduct> unsupported) {}

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
        BigDecimal valueEur,
        BigDecimal pnlEur
    ) {}
}
