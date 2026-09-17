package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
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
import com.picsou.model.TradeRepublicSession;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.port.TradeRepublicPort;
import com.picsou.port.TradeRepublicPort.TrAccountData;
import com.picsou.port.TradeRepublicPort.TrPosition;
import com.picsou.port.TradeRepublicPort.TrTokens;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TradeRepublicSessionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Transactional
public class TradeRepublicSyncService {

    private static final Logger log = LoggerFactory.getLogger(TradeRepublicSyncService.class);

    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tr-sync");
        t.setDaemon(true);
        return t;
    });

    private final TradeRepublicPort             trPort;
    private final TradeRepublicSessionRepository sessionRepository;
    private final AccountRepository             accountRepository;
    private final AccountHoldingRepository      holdingRepository;
    private final TransactionRepository         transactionRepository;
    private final FamilyMemberRepository        familyMemberRepository;
    private final AccountService                accountService;
    private final OpenFigiIsinConverter         isinConverter;
    private final SecurityIdentityService       identityService;
    private final CryptoEncryption              encryption;
    private final TransactionTemplate           txTemplate;
    private final CategorizationService         categorizationService;
    private final CategoryRepository            categoryRepository;

    public TradeRepublicSyncService(
        TradeRepublicPort trPort,
        TradeRepublicSessionRepository sessionRepository,
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
        CategoryRepository categoryRepository
    ) {
        this.trPort            = trPort;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.accountService    = accountService;
        this.isinConverter     = isinConverter;
        this.identityService   = identityService;
        this.encryption        = encryption;
        this.txTemplate        = txTemplate;
        this.categorizationService = categorizationService;
        this.categoryRepository = categoryRepository;
    }

    // --- Auth ---

    /**
     * Step 1: Sends phone+PIN to TR, triggers SMS. Returns processId.
     * Credentials are used immediately and never stored.
     */
    @Transactional(readOnly = true)
    public AuthInitResponse initiateAuth(String phoneNumber, String pin) {
        String processId = trPort.initiateAuth(phoneNumber, pin);
        return new AuthInitResponse(processId);
    }

    /**
     * Step 2: Exchanges 2FA code for session + refresh tokens, stores them.
     * Returns immediately -- sync runs in background.
     */
    public SessionStatusResponse completeAuth(String processId, String tan, Long memberId) {
        TrTokens tokens = trPort.completeAuth(processId, tan);

        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        // Delete any existing sessions for this member
        sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);

        TradeRepublicSession session = TradeRepublicSession.builder()
            .member(member)
            .sessionToken(encryption.encrypt(tokens.sessionToken()))
            .refreshToken(encryption.encrypt(tokens.refreshToken()))
            .expiresAt(Instant.now().plus(2, ChronoUnit.HOURS))
            .build();
        sessionRepository.save(session);

        log.info("Trade Republic session stored for member {} (refresh token: {}), firing background sync",
                 memberId, tokens.refreshToken() != null ? "yes" : "no");

        // Lift soft-delete tombstones so the upcoming sync can update (not skip) previously-deleted accounts.
        // The user explicitly re-authenticated, meaning they want their TR accounts back.
        accountRepository.restoreSoftDeletedTrAccounts(memberId);

        String plainToken = tokens.sessionToken();
        Long sessionId = session.getId();
        CompletableFuture.runAsync(() -> {
            try {
                txTemplate.executeWithoutResult(status -> {
                    TradeRepublicSession savedSession = sessionRepository.findById(sessionId).orElse(null);
                    syncWithToken(plainToken, savedSession, memberId);
                });
                log.info("Trade Republic background sync complete");
            } catch (Exception ex) {
                log.error("Trade Republic background sync failed: {}", ex.getMessage());
            }
        }, syncExecutor);

        return new SessionStatusResponse(true, session.getExpiresAt());
    }

    // --- Sync ---

    /** Manual or scheduled sync using the stored session, with auto-refresh. */
    public List<AccountResponse> sync(Long memberId) {
        TradeRepublicSession stored = sessionRepository.findByMemberId(memberId)
            .orElseThrow(() -> new SyncException("No Trade Republic session. Please connect from the Trade Republic page."));
        return syncWithToken(encryption.decrypt(stored.getSessionToken()), stored, memberId);
    }

    private List<AccountResponse> syncWithToken(String sessionToken, TradeRepublicSession stored, Long memberId) {
        try {
            List<TrAccountData> accounts = trPort.fetchAccounts(sessionToken);
            List<AccountResponse> responses = accounts.stream()
                .map(data -> upsertAccount(data, memberId, true))
                .flatMap(Optional::stream)
                .toList();
            log.info("Trade Republic sync complete: {} accounts updated", responses.size());
            return responses;
        } catch (SyncException e) {
            if ("SESSION_EXPIRED".equals(e.getMessage())) {
                // Try to refresh via stored refresh token
                if (stored != null && stored.getRefreshToken() != null) {
                    log.info("TR session expired -- attempting refresh with stored refresh token");
                    return refreshAndRetry(stored, memberId);
                }
                log.warn("TR session expired -- no refresh token available, clearing session");
                sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);
                throw new SyncException(
                    "Your Trade Republic session has expired. Please reconnect from the Trade Republic page.");
            }
            throw e;
        }
    }

    private List<AccountResponse> refreshAndRetry(TradeRepublicSession stored, Long memberId) {
        try {
            TrTokens newTokens = trPort.refreshSession(encryption.decrypt(stored.getRefreshToken()));
            stored.setSessionToken(encryption.encrypt(newTokens.sessionToken()));
            if (newTokens.refreshToken() != null) {
                stored.setRefreshToken(encryption.encrypt(newTokens.refreshToken()));
            }
            stored.setExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS));
            sessionRepository.save(stored);
            log.info("TR session refreshed -- retrying sync");
            return syncWithToken(newTokens.sessionToken(), null, memberId); // null = no retry on next expiry
        } catch (SyncException ex) {
            if ("SESSION_EXPIRED".equals(ex.getMessage())) {
                log.warn("TR refresh rejected -- clearing session");
                sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);
                throw new SyncException(
                    "Your Trade Republic session has expired and could not be refreshed. Please reconnect.");
            }
            // Transient failure (sidecar down, timeout): keep the session so the
            // next sync can retry the refresh instead of forcing a re-auth.
            log.warn("TR refresh failed transiently -- keeping session: {}", ex.getMessage());
            throw ex;
        }
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
                    log.warn("TR CSV: skipping malformed line: {}", line);
                    continue;
                }

                String name    = parts[0].trim();
                String typeStr = parts[1].trim().toUpperCase();
                String balStr  = parts[2].trim();

                AccountType type;
                try {
                    type = AccountType.valueOf(typeStr);
                } catch (IllegalArgumentException ex) {
                    log.warn("TR CSV: unknown type '{}' on line '{}', using OTHER", typeStr, line);
                    type = AccountType.OTHER;
                }

                BigDecimal balance;
                try {
                    balance = new BigDecimal(balStr);
                } catch (NumberFormatException ex) {
                    log.warn("TR CSV: invalid balance '{}' on line '{}'", balStr, line);
                    continue;
                }

                // Deduplicate via a stable external ID derived from the name
                String externalId = "tr_csv_" + name.toLowerCase()
                    .replaceAll("[^a-z0-9]", "_")
                    .replaceAll("_+", "_");

                upsertAccount(new TrAccountData(externalId, name, type, balance, List.of()), memberId, false)
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

    public record ImportResult(int inserted, int skipped) {}

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
    public ImportResult importTransactionsCsv(Long accountId, MultipartFile file, Long memberId) {
        // Authorize: the triggering account must belong to the member (guards against a spoofed {id}).
        accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.account(accountId));

        List<Account> trAccounts = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .filter(a -> "Trade Republic".equals(a.getProvider()))
            .toList();

        Account trCash = trAccounts.stream().filter(a -> a.getType() == AccountType.CHECKING).findFirst().orElse(null);
        Account trPea = trAccounts.stream().filter(a -> a.getType() == AccountType.PEA).findFirst().orElse(null);
        Account trTitres = trAccounts.stream().filter(a -> a.getType() == AccountType.COMPTE_TITRES).findFirst().orElse(null);

        if (trCash == null) {
            log.warn("TR CSV Import: No CHECKING account (TR Cash) found for member {}. Create TR accounts first by syncing or importing the accounts CSV.", memberId);
            return new ImportResult(0, 0);
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

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            boolean firstLine = true;
            int insertedCount = 0;
            int skippedCount = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (firstLine) {
                    firstLine = false;
                    if (line.toLowerCase().contains("datetime") || line.toLowerCase().startsWith("\"datetime\"")) {
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
            log.info("TR CSV import complete: {} inserted, {} skipped (already present)", insertedCount, skippedCount);
            return new ImportResult(insertedCount, skippedCount);
        } catch (Exception ex) {
            log.error("Failed to read TR Transactions CSV", ex);
            throw new RuntimeException("Failed to read CSV file: " + ex.getMessage(), ex);
        }
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
    public SessionStatusResponse getSessionStatus(Long memberId) {
        Optional<TradeRepublicSession> session = sessionRepository.findByMemberId(memberId);
        if (session.isEmpty()) {
            return new SessionStatusResponse(false, null);
        }
        TradeRepublicSession s = session.get();
        // A session past its (heuristic) expiry is still operationally active as
        // long as a refresh token exists: the next sync refreshes it transparently,
        // and a genuinely rejected refresh deletes the row — making this false.
        boolean withinWindow = s.getExpiresAt() == null || s.getExpiresAt().isAfter(Instant.now());
        boolean active = withinWindow || s.getRefreshToken() != null;
        return new SessionStatusResponse(active, s.getExpiresAt());
    }

    public void clearSession(Long memberId) {
        sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);
        log.info("Trade Republic session cleared for member {}", memberId);
    }

    // --- Scheduler entry point ---

    /**
     * Called by SchedulerService. No-op if no session exists for this member.
     * An expired session token is not a reason to skip: syncWithToken routes
     * SESSION_EXPIRED through refreshAndRetry using the stored refresh token,
     * which is the normal path for any sync happening hours after auth.
     */
    public void resyncIfSessionActive(Long memberId) {
        Optional<TradeRepublicSession> session = sessionRepository.findByMemberId(memberId);
        if (session.isEmpty()) return;

        TradeRepublicSession s = session.get();
        try {
            syncWithToken(encryption.decrypt(s.getSessionToken()), s, memberId);
        } catch (SyncException ex) {
            // Expected upstream flakiness (expired session, sidecar down) — WARN, as elsewhere.
            log.warn("Trade Republic auto-sync failed for member {}: {}", memberId, ex.getMessage());
        } catch (RuntimeException ex) {
            // Anything else is a bug: the message alone is "null" for an NPE, so log the trace.
            log.error("Unexpected Trade Republic auto-sync failure for member {}", memberId, ex);
        }
    }

    // --- Private ---

    private Optional<AccountResponse> upsertAccount(TrAccountData data, Long memberId, boolean replaceHoldings) {
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
        accountService.upsertSnapshot(account, data.balanceEur(), LocalDate.now());

        if (replaceHoldings) {
            holdingRepository.deleteByAccountId(account.getId());
            holdingRepository.flush();

            if (data.positions().isEmpty()) {
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
                var result = isinConverter.resolve(p.isin());
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

    public record AuthInitResponse(String processId) {}

    public record SessionStatusResponse(boolean isActive, Instant expiresAt) {}
}
