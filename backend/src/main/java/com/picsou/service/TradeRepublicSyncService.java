package com.picsou.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.adapter.OpenFigiIsinConverter.TickerResult;
import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.FamilyMember;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.adapter.TradeRepublicAdapter;
import com.picsou.adapter.TradeRepublicAdapter.TrAccountData;
import com.picsou.adapter.TradeRepublicAdapter.TrPosition;
import com.picsou.adapter.TradeRepublicAdapter.TrInitiate;
import com.picsou.adapter.TradeRepublicAdapter.TrSession;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.port.SidecarErrorCode;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SidecarSessionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.sync.SourceSyncResult;
import com.picsou.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import static com.picsou.service.SyncValues.requireTransactionResult;

@Service
public class TradeRepublicSyncService {

    private static final Logger log = LoggerFactory.getLogger(TradeRepublicSyncService.class);
    private static final String SOURCE = "trade-republic";
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final int FIRST_IMPORT_YEARS = 3;
    private static final int OVERLAP_DAYS = 14;
    /** The longest window tr-auth's {@code /transactions} accepts ({@code EXPORT_MAX_DAYS} there). */
    private static final int EXPORT_MAX_DAYS = 1830;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern INTERNATIONAL_PHONE = Pattern.compile("\\+\\d{6,15}");
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s.\\-()]");
    private static final int COUNTRY_PREFIX_LENGTH = 3;
    private static final int VISIBLE_PHONE_DIGITS = 2;

    private final TradeRepublicAdapter          trPort;
    private final AccountRepository             accountRepository;
    private final AccountHoldingRepository      holdingRepository;
    private final TransactionRepository         transactionRepository;
    private final FamilyMemberRepository        familyMemberRepository;
    private final AccountService                accountService;
    private final OpenFigiIsinConverter         isinConverter;
    private final SecurityIdentityService       identityService;
    private final CategorizationService         categorizationService;
    private final CategoryRepository            categoryRepository;
    private final CryptoEncryption              encryption;
    private final SidecarSyncTemplate<Prepared> sync;
    private final TransactionTemplate           txTemplate;
    /** Bumped by every disconnect, so a login that was waiting meanwhile stores nothing. */
    private final Map<Long, Long> authGenerations = new ConcurrentHashMap<>();
    private final Object sessionWriteLock = new Object();

    public TradeRepublicSyncService(
        TradeRepublicAdapter trPort,
        SidecarSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository familyMemberRepository,
        AccountService accountService,
        OpenFigiIsinConverter isinConverter,
        SecurityIdentityService identityService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        CategorizationService categorizationService,
        CategoryRepository categoryRepository,
        @Qualifier("tradeRepublicSyncExecutor") Executor syncExecutor
    ) {
        this.trPort            = trPort;
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.accountService    = accountService;
        this.isinConverter     = isinConverter;
        this.identityService   = identityService;
        this.categorizationService = categorizationService;
        this.categoryRepository = categoryRepository;
        this.txTemplate        = txTemplate;
        this.encryption        = encryption;
        this.sync = new SidecarSyncTemplate<>(SidecarBank.TRADE_REPUBLIC, new Strategy(), sessionRepository,
            familyMemberRepository, encryption, txTemplate, syncExecutor, false);
    }

    /**
     * What a sync fetched: the accounts, their resolved tickers (so the commit makes no network
     * call), and TR's CSV export of the activity (null when it was not fetched).
     */
    private record Prepared(List<TrAccountData> accounts, Map<String, TickerResult> tickers, String csv) {}

    private final class Strategy implements SidecarSyncStrategy<Prepared> {
        @Override
        public Prepared fetch(SidecarSyncJob job) {
            TrSession session = trPort.openSession(job.plainState());
            List<TrAccountData> accounts = trPort.fetchAccounts(session.sessionToken(), session.claims());
            // OpenFIGI can take seconds per ISIN: resolve here, outside the commit's row lock.
            Map<String, TickerResult> tickers = new HashMap<>();
            for (TrAccountData data : accounts) {
                for (TrPosition p : data.positions()) {
                    tickers.computeIfAbsent(p.isin(), isinConverter::resolve);
                }
            }
            return new Prepared(accounts, tickers, fetchTransactionsCsv(job));
        }

        @Override
        public void commit(SidecarSyncJob job, Prepared prepared, Instant syncedAt) {
            prepared.accounts().forEach(data -> upsertAccount(data, job.memberId(), prepared.tickers()));
        }

        /**
         * Runs after the accounts are committed, in its own transaction: a row the importer cannot
         * write rolls back the transactions only, never the balances and holdings just synced.
         */
        @Override
        public void afterCommit(SidecarSyncJob job, Prepared prepared) {
            if (prepared.csv() == null) {
                return;
            }
            try {
                ImportResult result = txTemplate.execute(status -> {
                    try (Reader reader = new StringReader(prepared.csv())) {
                        return importTransactions(reader, job.memberId());
                    } catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                });
                if (result != null) {
                    log.info("Trade Republic transactions imported (member={}; tx={}; skipped={}; nonEur={})",
                        job.memberId(), result.inserted(), result.skipped(), result.nonEur());
                }
            } catch (RuntimeException ex) {
                log.warn("Trade Republic transactions import failed (member={}; type={})",
                    job.memberId(), ex.getClass().getSimpleName());
            }
        }

        @Override
        public String describe(Prepared prepared) {
            return "accounts=" + prepared.accounts().size()
                + (prepared.csv() == null ? "" : "; transactions=fetched");
        }
    }

    /**
     * The export covering the activity since the last import, or null when the sidecar cannot
     * render it: the balances are worth syncing without the history. An expired session is the one
     * failure that still fails the sync, as the same session just failed to open.
     */
    private String fetchTransactionsCsv(SidecarSyncJob job) {
        LocalDate today = LocalDate.now(PARIS);
        try {
            return trPort.fetchTransactionsCsv(job.plainState(), transactionsWindowStart(job.memberId(), today), today);
        } catch (SyncException ex) {
            if (SidecarErrorCode.SESSION_EXPIRED.name().equals(ex.getCode())) {
                throw ex;
            }
            log.warn("Trade Republic transactions export unavailable (member={}; code={})",
                job.memberId(), ex.getCode());
            return null;
        } catch (RuntimeException ex) {
            log.warn("Trade Republic transactions export unavailable (member={}; type={})",
                job.memberId(), ex.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Newest imported TR Cash entry minus a safety overlap, or three years back for a first import.
     * The session's own completion time cannot anchor this: queueing and starting a run clears it.
     * Re-reading the overlap is safe, the importer skips rows it already holds.
     */
    private LocalDate transactionsWindowStart(Long memberId, LocalDate today) {
        LocalDate latest = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .filter(a -> "Trade Republic".equals(a.getProvider()) && a.getType() == AccountType.CHECKING)
            .findFirst()
            .map(a -> transactionRepository.findLatestSyncedDateByAccountId(a.getId()))
            .orElse(null);
        LocalDate start = latest == null ? today.minusYears(FIRST_IMPORT_YEARS) : latest.minusDays(OVERLAP_DAYS);
        // A newest entry over five years old would ask for a window the sidecar refuses with
        // INVALID_DATA on every sync, so the export never comes back. Older activity is out of
        // what one export returns; the manual upload still takes it.
        LocalDate oldest = today.minusDays(EXPORT_MAX_DAYS);
        return start.isBefore(oldest) ? oldest : start;
    }

    // --- Auth ---

    /**
     * Step 1: sends phone+PIN (or password) to TR, which asks for the second factor (an in-app
     * confirmation, a 4-digit code, or a 6-digit authenticator code). The PIN is used immediately
     * and never stored.
     */
    public AuthInitResponse initiateAuth(String phoneNumber, String pin) {
        TrInitiate result = trPort.initiateAuth(phoneNumber, pin);
        return new AuthInitResponse(result.processId(), result.flow(), result.countdownInSeconds());
    }

    /**
     * The phone number kept in the member's stored session state (active or deactivated), or empty
     * when there is no session or it predates the stored phone. Lets the member reconnect with the
     * PIN alone.
     */
    @Transactional(readOnly = true)
    public Optional<String> storedPhone(Long memberId) {
        return sync.find(memberId).map(this::phoneOf);
    }

    /**
     * Step 2: waits for the confirmation (or checks the code), stores the session and queues the
     * first import. Returns as soon as the import is queued.
     */
    public SessionStatusResponse completeAuth(String processId, String tan, Long memberId) {
        long generation = authGenerations.getOrDefault(memberId, 0L);
        String plainState = trPort.completeAuth(processId, tan);
        SidecarSyncJob job;
        synchronized (sessionWriteLock) {
            // The wait can last two minutes: a disconnect clicked meanwhile wins over the login.
            if (authGenerations.getOrDefault(memberId, 0L) != generation) {
                throw new SyncException("The Trade Republic connection was removed during the login", null,
                    SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name());
            }
            // The member re-authenticated on purpose: lift soft-delete tombstones in the same
            // transaction as the new session so the first sync updates, not skips, their accounts.
            job = requireTransactionResult(txTemplate.execute(status -> sync.insertQueued(memberId, plainState, false,
                session -> accountRepository.restoreSoftDeletedTrAccounts(memberId))));
        }
        sync.submit(job);
        log.info("Trade Republic session stored for member {}, import queued", memberId);
        return getStatus(memberId);
    }

    // --- Sync ---

    /** Queues an import of the stored session; the status says where it stands. */
    public SessionStatusResponse queueSync(Long memberId) {
        return toStatus(sync.queue(memberId, false));
    }

    // --- CSV import (fallback) ---

    /**
     * Imports account balances from a CSV file.
     * Expected format (header required):
     * <pre>
     * name,type,balance
     * PEA Trade Republic,PEA,15000.50
     * CTO Trade Republic,COMPTE_TITRES,5000.00
     * Cash TR,CHECKING,250.00
     * </pre>
     * Valid types: PEA, COMPTE_TITRES, CRYPTO, CHECKING, SAVINGS, LEP, OTHER
     */
    @Transactional
    public List<AccountResponse> importCsv(MultipartFile file, Long memberId) {
        List<AccountResponse> responses = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Skip header
                if (firstLine) {
                    firstLine = false;
                    if (line.toLowerCase().startsWith("name")) continue;
                }

                String[] parts = line.split(",", 3);
                if (parts.length < 3) {
                    log.warn("TR CSV: skipping malformed line: {}", LogSanitizer.safe(line));
                    continue;
                }

                String name    = parts[0].trim();
                String typeStr = parts[1].trim().toUpperCase();
                String balStr  = parts[2].trim();

                AccountType type;
                try {
                    type = AccountType.valueOf(typeStr);
                } catch (IllegalArgumentException ex) {
                    log.warn("TR CSV: unknown type '{}' on line '{}', using OTHER", LogSanitizer.safe(typeStr), LogSanitizer.safe(line));
                    type = AccountType.OTHER;
                }

                BigDecimal balance;
                try {
                    balance = new BigDecimal(balStr);
                } catch (NumberFormatException ex) {
                    log.warn("TR CSV: invalid balance '{}' on line '{}'", LogSanitizer.safe(balStr), LogSanitizer.safe(line));
                    continue;
                }

                // Deduplicate via a stable external ID derived from the name
                String externalId = "tr_csv_" + name.toLowerCase()
                    .replaceAll("[^a-z0-9]", "_")
                    .replaceAll("_+", "_");

                upsertAccount(new TrAccountData(externalId, name, type, balance, List.of()), memberId, null)
                    .ifPresent(responses::add);
            }

        } catch (Exception ex) {
            throw new SyncException("Failed to parse CSV: " + ex.getMessage());
        }

        log.info("TR CSV import complete: {} accounts processed", responses.size());
        return responses;
    }

    /** Slug of the EXPENSE category under which imported securities purchases are booked. */
    private static final String INVESTMENT_PURCHASE_SLUG = "investissement-titres";

    /** {@code nonEur}: rows the export states in a currency other than EUR, left out. */
    public record ImportResult(int inserted, int skipped, int nonEur) {}

    /**
     * Parses the full Trade Republic CSV export and creates double-entry transactions:
     * <ul>
     *   <li>TRADING rows → debit on TR Cash + credit on TR PEA / TR Titres (no position created).</li>
     *   <li>CASH rows (Saveback, interest, card payments, incoming transfers) → single transaction on TR Cash.</li>
     *   <li>TRANSFER_IN / TRANSFER_OUT are ignored (already covered by the TRADING double-entry).</li>
     * </ul>
     * Rows are deduplicated per-account via {@code existsByAccountIdAndExternalId};
     * re-importing the same CSV is safe and isolation between members is guaranteed.
     *
     * <p>A securities purchase is a real outflow: the cash leg stays uncategorized so cashflow
     * counts it as spending (and a sale as an inflow). Only the investment leg is tagged with the
     * member's {@code investissement} category (kind {@link com.picsou.model.CategoryKind#TRANSFER}),
     * which excludes it from cashflow — so the same movement is never counted twice — while still
     * feeding the allocation view as an investment contribution.
     *
     * @param accountId the account the import was triggered from; validated to belong to the member
     * @param file      the raw CSV export from Trade Republic
     * @param memberId  the authenticated member
     */
    @Transactional
    public ImportResult importTransactionsCsv(Long accountId, MultipartFile file, Long memberId) {
        // Authorize: the triggering account must belong to the member (guards against a spoofed {id}).
        accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.account(accountId));

        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            return importTransactions(reader, memberId);
        } catch (Exception ex) {
            log.error("Failed to read TR Transactions CSV", ex);
            throw new RuntimeException("Failed to read CSV file: " + ex.getMessage(), ex);
        }
    }

    /**
     * The parsing and insertion shared by the manual upload and the automatic sync. It owns no
     * transaction (the callers do) and never closes the reader.
     */
    private ImportResult importTransactions(Reader source, Long memberId) throws IOException {
        List<Account> trAccounts = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .filter(a -> "Trade Republic".equals(a.getProvider()))
            .toList();

        Account trCash = trAccounts.stream().filter(a -> a.getType() == AccountType.CHECKING).findFirst().orElse(null);
        Account trPea = trAccounts.stream().filter(a -> a.getType() == AccountType.PEA).findFirst().orElse(null);
        Account trTitres = trAccounts.stream().filter(a -> a.getType() == AccountType.COMPTE_TITRES).findFirst().orElse(null);

        if (trCash == null) {
            log.warn("TR CSV Import: No CHECKING account (TR Cash) found for member {}. Create TR accounts first by syncing or importing the accounts CSV.", memberId);
            return new ImportResult(0, 0, 0);
        }

        // Investment leg → seeded "investissement" TRANSFER category: excluded from cashflow (so
        // the movement is not counted a second time as income) while still feeding the allocation
        // view. Cash leg of a buy/sell → "investissement-titres" EXPENSE category so the purchase
        // shows up as spending; resolved lazily below (created on demand — see the helper).
        Map<String, Category> categoriesBySlug = categorizationService.categoriesBySlug(memberId);
        Category investmentTransferCategory = categoriesBySlug.get("investissement");
        if (investmentTransferCategory == null) {
            log.warn("TR CSV Import: 'investissement' (transfer) category not found for member {}; investment legs may skew cashflow.", memberId);
        }
        // Resolved on the first TRADING row so a CASH-only import creates nothing.
        Category investmentPurchaseCategory = categoriesBySlug.get(INVESTMENT_PURCHASE_SLUG);

        BufferedReader reader = new BufferedReader(source);
        String line;
        boolean firstLine = true;
        int insertedCount = 0;
        int skippedCount = 0;
        int nonEurCount = 0;
        int currencyColumn = -1;

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (firstLine) {
                firstLine = false;
                if (line.toLowerCase().contains("datetime") || line.toLowerCase().startsWith("\"datetime\"")) {
                    currencyColumn = parseCsvLine(line.toLowerCase()).indexOf("currency");
                    if (currencyColumn < 0) {
                        log.warn("TR CSV import: no currency column in the header, amounts read as EUR");
                    }
                    continue;
                }
            }

            List<String> parts = parseCsvLine(line);
            if (parts.size() < 19) {
                continue;
            }

            String dateStr = parts.get(1);
            String accountTypeStr = parts.get(2);
            String category = parts.get(3);
            String type = parts.get(4);
            String stockName = parts.get(6);
            String amountStr = parts.get(10);
            String description = parts.get(17);
            String externalId = parts.get(18);

            // Skip rows with no external ID — they would all collide on "_cash"/"_inv"
            if (externalId == null || externalId.isBlank()) {
                log.debug("TR CSV Import: skipping row with blank externalId (date={}, type={})", dateStr, type);
                continue;
            }

            if ("TRANSFER_IN".equalsIgnoreCase(type) || "TRANSFER_OUT".equalsIgnoreCase(type)) {
                continue;
            }

            // Every amount is stored as EUR: a row the export states in another currency is left
            // out rather than booked at the wrong value. original_currency is not checked: it names
            // what a card payment abroad was charged in, while amount stays the EUR debit.
            if (currencyColumn >= 0 && currencyColumn < parts.size()
                    && !parts.get(currencyColumn).isBlank() && !"EUR".equalsIgnoreCase(parts.get(currencyColumn))) {
                nonEurCount++;
                continue;
            }

            // Handle FR-locale decimal comma (e.g. "59,31" → "59.31")
            BigDecimal amount;
            try {
                amount = new BigDecimal(amountStr.replace(",", "."));
            } catch (NumberFormatException e) {
                log.debug("TR CSV Import: skipping row with unparseable amount '{}'", amountStr);
                continue;
            }

            // Defensive date parsing: handle both yyyy-MM-dd and ISO timestamps (yyyy-MM-ddTHH:mm:ssZ)
            LocalDate date;
            try {
                String datePart = dateStr.length() > 10 ? dateStr.substring(0, 10) : dateStr;
                date = LocalDate.parse(datePart);
            } catch (Exception e) {
                log.debug("TR CSV Import: skipping row with unparseable date '{}'", dateStr);
                continue;
            }

            // Build a readable description for the transaction
            String finalDescription = (description != null && !description.isBlank()) ? description : type;
            if ("BUY".equalsIgnoreCase(type) || "SELL".equalsIgnoreCase(type)) {
                // e.g. "BUY S&P 500 EUR (Acc)" or "SELL Dell Technologies"
                finalDescription = type + (stockName != null && !stockName.isBlank() ? " " + stockName : "");
            }

            boolean isTrading = "TRADING".equalsIgnoreCase(category);

            // --- Cash leg (always created, except TRANSFER_IN/OUT already filtered above) ---
            // Dedup is scoped by account to ensure member isolation.
            boolean cashSkipped = false;
            if (!transactionRepository.existsByAccountIdAndExternalId(trCash.getId(), externalId + "_cash")) {
                // DEPOSIT for positive amounts (Saveback, interest, incoming transfers)
                // WITHDRAWAL for negative amounts (card payments, investment debits)
                TransactionType txTypeCash = amount.compareTo(BigDecimal.ZERO) < 0
                    ? TransactionType.WITHDRAWAL
                    : TransactionType.DEPOSIT;
                // A securities purchase is a real outflow (an "investment purchase"): the cash
                // leg of a TRADING row is booked under the "Investissement" EXPENSE category so
                // it counts as spending (a sale, being positive, as an inflow). Non-TRADING
                // CASH rows stay uncategorized and count by sign. Only the investment leg below
                // is a TRANSFER, which keeps the movement from being counted twice (as income).
                if (isTrading && investmentPurchaseCategory == null) {
                    investmentPurchaseCategory = ensureInvestmentPurchaseCategory(memberId);
                }
                Transaction txCash = Transaction.builder()
                    .account(trCash)
                    .date(date)
                    .amount(amount)
                    .description(finalDescription)
                    .merchantLabel(finalDescription)
                    .type(type)
                    .category(category)
                    .categoryRef(isTrading ? investmentPurchaseCategory : null)
                    .txType(txTypeCash)
                    .externalId(externalId + "_cash")
                    .nativeCurrency("EUR")
                    .build();
                transactionRepository.save(txCash);
                insertedCount++;
            } else {
                cashSkipped = true;
            }

            // --- Investment leg (only for TRADING rows, opposite sign on the target account) ---
            if (isTrading) {
                Account targetInvestmentAccount = "PEA".equalsIgnoreCase(accountTypeStr) ? trPea : trTitres;

                if (targetInvestmentAccount != null) {
                    if (!transactionRepository.existsByAccountIdAndExternalId(targetInvestmentAccount.getId(), externalId + "_inv")) {
                        // Double-entry: the investment leg is the mirror of the cash leg, so it
                        // carries the opposite sign. BUY (cash −) → money enters investments:
                        // DEPOSIT +amount. SELL (cash +) → money leaves investments: WITHDRAWAL
                        // −amount. The positive DEPOSIT is what AllocationService reads as an
                        // investment contribution.
                        BigDecimal investAmount = amount.negate();
                        TransactionType txTypeInv = investAmount.signum() >= 0
                            ? TransactionType.DEPOSIT
                            : TransactionType.WITHDRAWAL;
                        Transaction txInv = Transaction.builder()
                            .account(targetInvestmentAccount)
                            .date(date)
                            .amount(investAmount)
                            .description(finalDescription)
                            .merchantLabel(finalDescription)
                            .type(type)
                            .category(category)
                            .categoryRef(investmentTransferCategory)
                            .txType(txTypeInv)
                            .externalId(externalId + "_inv")
                            .nativeCurrency("EUR")
                            .build();
                        transactionRepository.save(txInv);
                        insertedCount++;
                    } else if (cashSkipped) {
                        // Both legs already exist → count as fully skipped
                        skippedCount++;
                    }
                } else if (cashSkipped) {
                    // No investment account found and cash was also skipped → row fully skipped
                    skippedCount++;
                }
            } else if (cashSkipped) {
                skippedCount++;
            }
        }
        if (nonEurCount > 0) {
            log.warn("TR CSV import: {} rows not in EUR left out", nonEurCount);
        }
        log.info("TR CSV import complete: {} inserted, {} skipped (already present)", insertedCount, skippedCount);
        return new ImportResult(insertedCount, skippedCount, nonEurCount);
    }

    /**
     * Get-or-create the EXPENSE category ("Investissement") under which securities purchases are
     * booked. It is not part of the default seed set on purpose: only members who import TR trades
     * need it, and existing members are never re-seeded once they have categories. Idempotent by
     * slug — the caller only invokes this when the slug is absent from the member's categories.
     */
    private Category ensureInvestmentPurchaseCategory(Long memberId) {
        Category created = categoryRepository.save(Category.builder()
            .member(familyMemberRepository.getReferenceById(memberId))
            .slug(INVESTMENT_PURCHASE_SLUG)
            .name("Investissement")
            .kind(CategoryKind.EXPENSE)
            .color("#7c3aed")
            .icon("trending-up")
            .isDefault(true)
            .build());
        log.info("TR CSV Import: created the 'Investissement' expense category for member {}.", memberId);
        return created;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\"') {
                // RFC 4180: a doubled quote inside a quoted field is a literal quote.
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '\"') {
                    current.append('\"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString().trim());
        return result;
    }

    // --- Session status ---

    @Transactional(readOnly = true)
    public SessionStatusResponse getStatus(Long memberId) {
        return sync.find(memberId).map(this::toStatus).orElseGet(SessionStatusResponse::inactive);
    }

    /** Returns whether a stored session was there to delete. */
    public boolean clearSession(Long memberId) {
        boolean removed;
        synchronized (sessionWriteLock) {
            authGenerations.merge(memberId, 1L, Long::sum);
            removed = sync.clear(memberId);
        }
        log.info("Trade Republic session cleared for member {}", memberId);
        return removed;
    }

    /**
     * Queued jobs live in a process-local executor and cannot survive a backend restart. Turns
     * persisted in-flight states into a retryable failure instead of leaving the UI polling forever.
     */
    @Transactional
    public void recoverInterruptedSyncs() {
        sync.recoverInterrupted();
    }

    // --- Scheduler entry point ---

    /** Called by SchedulerService: queues an import when the session can run, and says why not. */
    public SourceSyncResult resyncReporting(Long memberId) {
        return sync.resyncReporting(memberId, SOURCE, log, this::getStatus, this::queueSync);
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
            session.getLastSyncError(),
            maskPhone(phoneOf(session))
        );
    }

    /** The phone in the session's decrypted state, null when absent, unreadable or not JSON. Never logged. */
    private String phoneOf(SidecarSession session) {
        try {
            String plain = encryption.decrypt(session.getSessionState());
            if (plain == null) {
                return null;
            }
            JsonNode phone = JSON.readTree(plain).get("phone");
            return phone != null && phone.isTextual() && !phone.asText().isBlank() ? phone.asText().trim() : null;
        } catch (RuntimeException | java.io.IOException ex) {
            return null;
        }
    }

    /**
     * Keeps the country prefix and the last two digits: {@code +33612345699} becomes
     * {@code +33 * ** ** ** 99}. Separators are ignored, since a state stored before the sidecar
     * dropped them can hold {@code +33 6 12 ...}. Null for anything that is not an international number.
     */
    static String maskPhone(String phone) {
        phone = phone == null ? null : PHONE_SEPARATORS.matcher(phone).replaceAll("");
        if (phone == null || !INTERNATIONAL_PHONE.matcher(phone).matches()) {
            return null;
        }
        String national = phone.substring(COUNTRY_PREFIX_LENGTH);
        if (national.length() <= VISIBLE_PHONE_DIGITS) {
            return null;
        }
        String hidden = "*".repeat(national.length() - VISIBLE_PHONE_DIGITS);
        String masked = hidden + national.substring(national.length() - VISIBLE_PHONE_DIGITS);
        StringBuilder grouped = new StringBuilder();
        int head = masked.length() % 2;
        grouped.append(masked, 0, head);
        for (int i = head; i < masked.length(); i += 2) {
            if (grouped.length() > 0) {
                grouped.append(' ');
            }
            grouped.append(masked, i, i + 2);
        }
        return phone.substring(0, COUNTRY_PREFIX_LENGTH) + " " + grouped;
    }

    // --- Private ---

    /**
     * @param tickers the resolved ticker of every position's ISIN, or null for a CSV import that
     *                keeps the account's holdings as they are
     */
    private Optional<AccountResponse> upsertAccount(TrAccountData data, Long memberId,
                                                    Map<String, TickerResult> tickers) {
        boolean replaceHoldings = tickers != null;
        log.debug("TR upsertAccount: looking for externalId={} memberId={}", data.externalId(), memberId);
        Optional<Account> existing = accountRepository.findByExternalAccountIdAndMemberId(data.externalId(), memberId);
        log.debug("TR upsertAccount: found existing={}", existing.isPresent());

        if (existing.isEmpty() &&
            accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), memberId)) {
            log.info("TR: skipping resurrection of soft-deleted account externalId={} member={}",
                data.externalId(), memberId);
            return Optional.empty();
        }

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(data.balanceEur());
            // The PEA's cash pocket is folded into balanceEur; without it on cashBalance the
            // valuation left it out of the live value while the provider-valued total kept it,
            // so the pocket read as a gain or vanished depending on which path ran. Null means
            // the adapter does not know the pocket this time (no cash account, or its frame
            // never came): the last known one stays, as the holdings do on an error frame.
            if (data.cashEur() != null) {
                account.setCashBalance(data.cashEur());
            }
            account.setLastSyncedAt(Instant.now());
        } else {
            FamilyMember member = familyMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            account = Account.builder()
                .member(member)
                .name(data.name())
                .type(data.type())
                .provider("Trade Republic")
                .currency("EUR")
                .currentBalance(data.balanceEur())
                .cashBalance(data.cashEur())
                .lastSyncedAt(Instant.now())
                .externalAccountId(data.externalId())
                .isManual(false)
                .color(colorFor(data.type()))
                .build();
        }

        try {
            account = accountRepository.save(account);
        } catch (DataIntegrityViolationException ex) {
            // Rare concurrent insert: another sync already created this account — use that row.
            account = accountRepository.findByExternalAccountIdAndMemberId(data.externalId(), memberId)
                .orElseThrow(() -> ex);
            account.setCurrentBalance(data.balanceEur());
            account.setLastSyncedAt(Instant.now());
            account = accountRepository.save(account);
            log.info("TR upsertAccount: concurrent insert resolved for externalId={}", data.externalId());
        }

        // The snapshot comes AFTER the holdings are replaced (both exits below): the 3-arg
        // upsertSnapshot derives the day's investedAmount from the holdings in the table, and
        // taken here it costed today's snapshot with the previous sync's positions, one sync
        // late in every daily point.
        if (replaceHoldings) {
            holdingRepository.deleteByAccountId(account.getId());
            holdingRepository.flush();

            if (data.positions().isEmpty()) {
                accountService.upsertSnapshot(account, data.balanceEur(), LocalDate.now());
                return Optional.of(accountService.toResponse(account));
            }

            // Deduplicate by ticker: when multiple ISINs convert to the same ticker,
            // aggregate them via VWAP to avoid unique constraint violations and
            // preserve a meaningful weighted average buy-in.
            Map<String, HoldingDedup.HoldingAgg> deduped = new HashMap<>();
            Map<String, BigDecimal> providerValuesEur = new HashMap<>();
            Set<String> incompleteProviderValues = new HashSet<>();
            Map<String, String> isinByTicker = new HashMap<>();
            for (TrPosition p : data.positions()) {
                TickerResult result = tickers.get(p.isin());
                String ticker = result.ticker();
                isinByTicker.put(ticker, p.isin());
                String name = result.name();
                BigDecimal positionValueEur = providerValueEur(p);
                if (positionValueEur == null) {
                    incompleteProviderValues.add(ticker);
                } else {
                    providerValuesEur.merge(ticker, positionValueEur, BigDecimal::add);
                }
                deduped.merge(
                    ticker,
                    new HoldingDedup.HoldingAgg(p.quantity(), p.averageBuyIn(), p.currentPrice(), name),
                    HoldingDedup::vwapMerge);
            }
            identityService.record(isinByTicker);
            for (Map.Entry<String, HoldingDedup.HoldingAgg> entry : deduped.entrySet()) {
                HoldingDedup.HoldingAgg agg = entry.getValue();
                if (agg.quantity().signum() == 0) {
                    // vwapMerge is sign-aware and can net two positions to exactly zero
                    // (shared with IBKR, which can carry short quantities) -- a flat
                    // position, not a holding to persist. TR only ever feeds positive
                    // quantities today, so this is unreachable here but keeps the
                    // invariant true regardless.
                    continue;
                }
                holdingRepository.save(AccountHolding.builder()
                    .account(account)
                    .ticker(entry.getKey())
                    .name(agg.name())
                    .quantity(agg.quantity())
                    .averageBuyIn(agg.averageBuyIn())
                    .currentPrice(agg.currentPrice())
                    .quoteCurrency("EUR")
                    .providerValueEur(incompleteProviderValues.contains(entry.getKey())
                        ? null
                        : providerValuesEur.get(entry.getKey()).setScale(8, RoundingMode.HALF_UP))
                    .lastSyncedAt(Instant.now())
                    .build());
            }
        }

        accountService.upsertSnapshot(account, data.balanceEur(), LocalDate.now());
        return Optional.of(accountService.toResponse(account));
    }

    private static BigDecimal providerValueEur(TrPosition position) {
        BigDecimal price = position.currentPrice() != null && position.currentPrice().signum() > 0
            ? position.currentPrice()
            : position.averageBuyIn();
        if (price == null || price.signum() <= 0) {
            return null;
        }
        // TradeRepublicAdapter builds balanceEur by rounding each original position
        // to cents. Preserve that exact subtotal even when several ISINs collapse to
        // one persisted ticker with different market prices.
        return price.multiply(position.quantity()).setScale(2, RoundingMode.HALF_UP);
    }

    private String colorFor(AccountType type) {
        return switch (type) {
            case PEA           -> "#10b981"; // green
            case COMPTE_TITRES -> "#3b82f6"; // blue
            case CRYPTO        -> "#f59e0b"; // amber
            case SAVINGS       -> "#8b5cf6"; // purple
            default            -> "#6366f1"; // indigo
        };
    }

    // --- Response records ---

    public record AuthInitResponse(String processId, String flow, Integer countdownInSeconds) {}

    public record SessionStatusResponse(
        boolean isActive,
        SidecarSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        SidecarErrorCode lastSyncError,
        String maskedPhone
    ) implements SidecarSessionView {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(false, SidecarSyncStatus.IDLE, null, null, null, null);
        }
    }
}
