package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.SimplefinConnectionStatusResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.SimplefinConnection;
import com.picsou.port.BankConnectorPort.TransactionData;
import com.picsou.port.SimplefinPort;
import com.picsou.port.SimplefinPort.SimplefinAccount;
import com.picsou.port.SimplefinPort.SimplefinAccountSet;
import com.picsou.port.SimplefinPort.SimplefinTransaction;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SimplefinConnectionRepository;
import com.picsou.service.sync.SourceSyncResult;
import com.picsou.util.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Claims a SimpleFIN setup token and maps the account set onto Picsou accounts
 * and posted transactions. One connection per member. Enable Banking is untouched.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SimplefinSyncService {

    /** Stamped on every imported account so Sync All and deletion can find the connection. */
    public static final String PROVIDER = "SimpleFIN";
    /** Source name in {@link MemberSyncService} and the MCP sync tools. */
    public static final String SOURCE = "simplefin";

    private static final int MAX_NAME_LEN = 100;
    private static final String COLOR = "#0d9488";

    private final SimplefinPort simplefinPort;
    private final SimplefinConnectionRepository connectionRepository;
    private final AccountRepository accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final PriceService priceService;
    private final FamilyMemberRepository familyMemberRepository;
    private final AccountService accountService;
    private final BankTransactionImportService transactionImportService;
    private final CryptoEncryption encryption;
    private final SimplefinStatusWriter statusWriter;

    /** Claims the token and stores the access URL. A later sync failure does not drop it. */
    public void connect(String setupToken, Long memberId) {
        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
        String accessUrl = simplefinPort.claim(setupToken);

        SimplefinConnection connection = connectionRepository.findByMemberId(memberId)
            .orElseGet(() -> SimplefinConnection.builder().member(member).build());
        connection.setAccessUrl(encryption.encrypt(accessUrl));
        connection.setStatus("CONNECTED");
        connectionRepository.save(connection);
        log.info("SimpleFIN access stored for member {}", memberId);
    }

    @Transactional(readOnly = true)
    public SimplefinConnectionStatusResponse getConnectionStatus(Long memberId) {
        Optional<SimplefinConnection> connection = connectionRepository.findByMemberId(memberId);
        if (connection.isEmpty()) {
            return new SimplefinConnectionStatusResponse(false, null, null, null, null);
        }
        SimplefinConnection stored = connection.get();
        String masked;
        try {
            masked = mask(encryption.decrypt(stored.getAccessUrl()));
        } catch (RuntimeException ex) {
            log.error("SimpleFIN: cannot decrypt stored access for connection {}", stored.getId(), ex);
            return new SimplefinConnectionStatusResponse(true, stored.getId(), "ERROR", stored.getLastSyncedAt(), "••••");
        }
        return new SimplefinConnectionStatusResponse(
            true, stored.getId(), stored.getStatus(), stored.getLastSyncedAt(), masked);
    }

    public boolean deleteConnection(Long memberId) {
        Optional<SimplefinConnection> connection = connectionRepository.findByMemberId(memberId);
        connection.ifPresent(connectionRepository::delete);
        log.info("SimpleFIN connection cleared for member {}", memberId);
        return connection.isPresent();
    }

    public List<AccountResponse> sync(Long memberId) {
        SimplefinConnection connection = connectionRepository.findByMemberId(memberId)
            .orElseThrow(() -> new SyncException("No SimpleFIN connection. Connect with a setup token first."));
        return syncWithConnection(connection, memberId);
    }

    /**
     * Scheduler and Sync All entry point. Reports failures instead of throwing; the caller still
     * wraps this because a rollback-only transaction can escape as {@code UnexpectedRollbackException}.
     */
    public SourceSyncResult resyncReporting(Long memberId) {
        try {
            Optional<SimplefinConnection> connection = connectionRepository.findByMemberId(memberId);
            if (connection.isEmpty()) {
                return new SourceSyncResult(SOURCE, SourceSyncResult.Status.SKIPPED_NOT_CONNECTED, "No connection");
            }
            syncWithConnection(connection.get(), memberId);
            return new SourceSyncResult(SOURCE, SourceSyncResult.Status.SYNCED, "");
        } catch (SyncException ex) {
            log.warn("SimpleFIN scheduled sync failed for member {} (code={})", memberId, ex.getCode(), ex);
            return SourceSyncResult.fromSyncException(SOURCE, ex);
        } catch (RuntimeException ex) {
            log.error("SimpleFIN auto-sync hit an unexpected error for member {}", memberId, ex);
            return new SourceSyncResult(SOURCE, SourceSyncResult.Status.FAILED, "Unexpected sync error");
        }
    }

    private List<AccountResponse> syncWithConnection(SimplefinConnection connection, Long memberId) {
        try {
            return doSync(connection, memberId);
        } catch (RuntimeException ex) {
            log.error("SimpleFIN sync failed for connection {} — marking ERROR status", connection.getId(), ex);
            statusWriter.markError(connection.getId());
            if (ex instanceof SyncException sync) throw sync;
            throw new SyncException("SimpleFIN sync failed. Try again in a moment.", ex);
        }
    }

    private List<AccountResponse> doSync(SimplefinConnection connection, Long memberId) {
        String accessUrl;
        try {
            accessUrl = encryption.decrypt(connection.getAccessUrl());
        } catch (RuntimeException ex) {
            throw new SyncException(
                "Could not decrypt the stored SimpleFIN access. The encryption key may have changed. "
                    + "Connect again with a new setup token.", ex);
        }

        LocalDate start = bridgeStart(transactionImportService.sharedHistoryStart(), LocalDate.now(ZoneOffset.UTC));
        SimplefinAccountSet set = simplefinPort.fetchAccounts(accessUrl, start);
        if (!set.errors().isEmpty()) {
            log.warn("SimpleFIN reported partial error(s) for member {}: {}",
                memberId, LogSanitizer.safe(String.join("; ", set.errors())));
        }

        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        List<AccountResponse> responses = new ArrayList<>();
        for (SimplefinAccount data : set.accounts()) {
            if (!isIsoCurrency(data.currency())) {
                log.info("SimpleFIN: skipping account {} — currency is not ISO 4217", data.externalId());
                continue;
            }
            if (!BankTransactionImportService.fitsLedgerAmount(data.balance())) {
                log.info("SimpleFIN: skipping account {} — balance does not fit the ledger", data.externalId());
                continue;
            }
            upsertAccount(data, member).ifPresent(responses::add);
        }

        connection.setStatus("CONNECTED");
        connection.setLastSyncedAt(Instant.now());
        connectionRepository.save(connection);
        log.info("SimpleFIN sync complete for member {}: {} account(s) updated", memberId, responses.size());
        return responses;
    }

    private Optional<AccountResponse> upsertAccount(SimplefinAccount data, FamilyMember member) {
        Optional<Account> existing = accountRepository
            .findByExternalAccountIdAndMemberId(data.externalId(), member.getId());
        if (existing.isEmpty()
            && accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), member.getId())) {
            log.info("SimpleFIN: skipping resurrection of soft-deleted account {} for member {}",
                data.externalId(), member.getId());
            return Optional.empty();
        }

        String currency = data.currency().toUpperCase(Locale.ROOT);
        // Mapped once: a new account's type depends on it, and the write below reuses it.
        SimplefinHoldings.Mapped mapped = SimplefinHoldings.map(data.holdings(), currency);
        LocalDate today = LocalDate.now();

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(data.balance());
            account.setCurrency(currency);
            account.setLastSyncedAt(Instant.now());
            // Backfills accounts synced before the institution was recorded. The type is never
            // touched: it is the member's, whatever the bridge reports.
            applyInstitution(account, data);
        } else {
            account = Account.builder()
                .member(member)
                .name(accountName(data.connectionName(), data.name()))
                // A new account that holds a position is a brokerage account; a cash-management
                // account whose only holding is a sweep fund stays a checking account. The member
                // can retype it afterwards, and a retyped account keeps its type on later syncs.
                .type(mapped.positions().isEmpty() ? AccountType.CHECKING : AccountType.BROKERAGE)
                .provider(PROVIDER)
                .currency(currency)
                .currentBalance(data.balance())
                .lastSyncedAt(Instant.now())
                .externalAccountId(data.externalId())
                .isManual(false)
                .color(COLOR)
                .build();
            applyInstitution(account, data);
        }
        account = accountRepository.save(account);
        // Holdings first: the snapshot below derives the day's invested amount from them.
        replaceHoldings(account, data, mapped, today);
        accountService.upsertSnapshotFromNative(account, data.balance(), today);
        transactionImportService.importProvided(account, toTransactions(data, account.getCurrency()));
        return Optional.of(accountService.toResponse(account));
    }

    /**
     * Brings the account's positions and cash in line with the bridge's, or leaves all of it as it
     * was. Nothing is written unless the whole portfolio can be carried over:
     * <ul>
     *   <li>an empty list never wipes: the bridge answers {@code []} for cash accounts and for a
     *       partial failure alike, and a wrong wipe would erase real positions where a stale line
     *       is corrected on the next good sync;</li>
     *   <li>a list the parser or the mapping could only read in part never replaces the stored
     *       one, for the same reason;</li>
     *   <li>a currency with no EUR rate, or a converted figure that does not fit its column,
     *       leaves everything as it was too: one rule, never a half-converted portfolio.</li>
     * </ul>
     *
     * <p>Prices stay in the position's currency ({@code quoteCurrency}); the buy-in, the line's
     * value and the cash are stored in EUR, the unit the valuation reads them in. The line's value
     * is what {@link AccountService#valuation} falls back to for a position the price feed cannot
     * resolve (a 401(k) fund known only by CUSIP), so the account is not shown short of it.
     *
     * <p>Positions are updated in place, by ticker: a buy-in the member entered by hand survives a
     * sync where the bridge has none (Fidelity sends 0.00 for every line).
     */
    private void replaceHoldings(Account account, SimplefinAccount data, SimplefinHoldings.Mapped mapped,
                                 LocalDate today) {
        if (!account.getType().isInvestment()) {
            if (!data.holdings().isEmpty()) {
                log.info("SimpleFIN: account {} reports positions but is typed {}; positions not imported",
                    account.getId(), account.getType());
            }
            return;
        }
        if (!data.holdingsComplete()) {
            log.warn("SimpleFIN: account {} sent a holdings list that was cut or had unreadable rows; "
                + "holdings and cash left as they were", account.getId());
            return;
        }
        if (!mapped.complete()) {
            log.warn("SimpleFIN: account {} holds a position Picsou cannot store (short, mixed currencies, "
                + "no symbol or a figure past the column); holdings and cash left as they were", account.getId());
            return;
        }
        if (mapped.positions().isEmpty() && mapped.sweepByCurrency().isEmpty()) return;

        EurPortfolio portfolio = inEur(account, mapped, data.balance());
        if (portfolio == null) return;

        // The account is a managed entity inside this transaction, so this is written with it.
        account.setCashBalance(portfolio.cash());

        // Updated in place and only the unreported tickers deleted: a ticker is never both, so the
        // unique (account_id, ticker) key cannot clash whatever order Hibernate flushes in.
        Map<String, AccountHolding> stored = new HashMap<>();
        holdingRepository.findByAccount_Id(account.getId()).forEach(h -> stored.put(h.getTicker(), h));
        Set<String> reported = portfolio.lines().stream()
            .map(line -> line.position().ticker()).collect(Collectors.toSet());
        holdingRepository.deleteAll(stored.values().stream().filter(h -> !reported.contains(h.getTicker())).toList());

        Instant now = account.getLastSyncedAt();
        for (EurLine line : portfolio.lines()) {
            SimplefinHoldings.Position p = line.position();
            AccountHolding holding = stored.getOrDefault(p.ticker(),
                AccountHolding.builder().account(account).ticker(p.ticker()).build());
            if (p.name() != null) holding.setName(p.name());
            holding.setQuantity(p.quantity());
            // The bridge's buy-in, or else whatever is stored (the member's own entry).
            if (line.buyInEur() != null) holding.setAverageBuyIn(line.buyInEur());
            holding.setCurrentPrice(p.currentPrice());
            holding.setQuoteCurrency(p.currency());
            holding.setProviderValueEur(line.valueEur());
            holding.setProviderPriceDate(line.valueEur() == null ? null : today);
            holding.setLastSyncedAt(now);
            holdingRepository.save(holding);
        }
    }

    private record EurLine(SimplefinHoldings.Position position, BigDecimal valueEur, BigDecimal buyInEur) {}

    private record EurPortfolio(BigDecimal cash, List<EurLine> lines) {}

    /**
     * The mapped portfolio converted to EUR, or null (logged) when any currency it needs has no
     * rate or a converted figure would not fit {@code numeric(20,8)}. {@code PriceService.toEur}
     * would hand back an unconverted amount, which the valuation would then read as EUR.
     *
     * <p>The cash is worked out here, in EUR, because the balance, the positions and the sweep
     * can each be in another currency:
     * <ul>
     *   <li>every position has a market value: the balance minus the positions, never below zero.
     *       It already contains the sweep, whatever its currency, and any cash the bridge left
     *       out of the list;</li>
     *   <li>a position has none: only the sweep. The remainder may be that position's own worth,
     *       which the price feed values separately, so putting it into cash would count it twice.
     *       Cash the bridge left out of the list is not counted then.</li>
     * </ul>
     */
    private EurPortfolio inEur(Account account, SimplefinHoldings.Mapped mapped, BigDecimal balance) {
        boolean residual = mapped.positions().stream().allMatch(p -> p.marketValue() != null);
        Set<String> currencies = new LinkedHashSet<>(mapped.sweepByCurrency().keySet());
        mapped.positions().forEach(p -> currencies.add(p.currency()));
        if (residual) currencies.add(account.getCurrency());
        Map<String, BigDecimal> rates = new HashMap<>();
        for (String currency : currencies) {
            BigDecimal rate = "EUR".equals(currency) ? BigDecimal.ONE : priceService.getFxRateEur(currency);
            if (rate == null) {
                log.warn("SimpleFIN: no EUR rate for {}; holdings and cash of account {} left as they were",
                    currency, account.getId());
                return null;
            }
            rates.put(currency, rate);
        }

        List<EurLine> lines = new ArrayList<>();
        BigDecimal held = BigDecimal.ZERO;
        boolean fits = true;
        for (SimplefinHoldings.Position p : mapped.positions()) {
            BigDecimal rate = rates.get(p.currency());
            BigDecimal value = p.marketValue() == null ? null : eur(p.marketValue(), rate);
            BigDecimal buyIn = p.averageBuyIn() == null ? null : eur(p.averageBuyIn(), rate);
            fits &= SimplefinHoldings.fits(value) && SimplefinHoldings.fits(buyIn);
            if (value != null) held = held.add(value);
            lines.add(new EurLine(p, value, buyIn));
        }

        BigDecimal cash = BigDecimal.ZERO;
        if (residual) {
            cash = eur(balance, rates.get(account.getCurrency())).subtract(held).max(BigDecimal.ZERO);
        } else {
            log.info("SimpleFIN: account {} has a position without a market value, so only the sweep counts "
                + "as cash; any cash the bridge left out of the list is not counted", account.getId());
            for (var entry : mapped.sweepByCurrency().entrySet()) {
                cash = cash.add(eur(entry.getValue(), rates.get(entry.getKey())));
            }
        }
        if (!fits || !SimplefinHoldings.fits(cash)) {
            log.warn("SimpleFIN: a converted figure of account {} does not fit the ledger; "
                + "holdings and cash left as they were", account.getId());
            return null;
        }
        return new EurPortfolio(cash.setScale(8, RoundingMode.HALF_UP), lines);
    }

    private static BigDecimal eur(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * Records which bank the bridge says an account belongs to. A value the bridge stops sending
     * is kept rather than cleared: a missing field is more likely a partial response than a bank
     * that stopped existing.
     */
    private static void applyInstitution(Account account, SimplefinAccount data) {
        String name = data.connectionName() == null ? "" : data.connectionName().trim();
        if (!name.isEmpty()) {
            account.setInstitutionName(BankTransactionImportService.clip(name, MAX_NAME_LEN));
        }
        if (data.institutionDomain() != null) {
            account.setInstitutionDomain(data.institutionDomain());
        }
    }

    private static List<TransactionData> toTransactions(SimplefinAccount data, String currency) {
        List<TransactionData> rows = new ArrayList<>();
        for (SimplefinTransaction tx : data.transactions()) {
            if (tx.date() == null || tx.amount() == null) continue;
            String description = tx.description() == null || tx.description().isBlank()
                ? "Transaction" : tx.description();
            rows.add(new TransactionData(tx.externalId(), tx.date(), description, tx.amount(), currency, null));
        }
        return rows;
    }

    /**
     * The bridge rejects an inclusive 90-day span ("exceeds limit of 90 days").
     * A request that starts 89 days ago is the longest window it accepts.
     */
    static LocalDate bridgeStart(LocalDate requested, LocalDate today) {
        LocalDate limit = today.minusDays(89);
        return requested.isBefore(limit) ? limit : requested;
    }

    static String accountName(String connectionName, String accountName) {
        String account = accountName == null || accountName.isBlank() ? "Account" : accountName.trim();
        String bank = connectionName == null ? "" : connectionName.trim();
        String combined = bank.isEmpty() || account.toLowerCase(Locale.ROOT).contains(bank.toLowerCase(Locale.ROOT))
            ? account
            : bank + " — " + account;
        return BankTransactionImportService.clip(combined, MAX_NAME_LEN);
    }

    /** Real currencies only: XAU, XDR, XXX and the like have no minor unit and are skipped. */
    static boolean isIsoCurrency(String code) {
        if (code == null || code.length() != 3) return false;
        try {
            return Currency.getInstance(code.toUpperCase(Locale.ROOT)).getDefaultFractionDigits() >= 0;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** Last four characters of the username embedded in the access URL. */
    static String mask(String accessUrl) {
        try {
            String userInfo = URI.create(accessUrl).getUserInfo();
            if (userInfo == null || userInfo.isBlank()) return "••••";
            String username = userInfo.split(":", 2)[0];
            if (username.length() <= 4) return "••••";
            return "••••" + username.substring(username.length() - 4);
        } catch (RuntimeException ex) {
            return "••••";
        }
    }
}
