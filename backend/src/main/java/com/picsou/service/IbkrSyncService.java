package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.adapter.OpenFigiIsinConverter.TickerResult;
import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.IbkrConnectionStatusResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.IbkrConnection;
import com.picsou.port.IbkrFlexPort;
import com.picsou.port.IbkrFlexPort.IbkrAccountData;
import com.picsou.port.IbkrFlexPort.IbkrPosition;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.IbkrConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates Interactive Brokers sync: stores the encrypted Flex credentials,
 * pulls Open Positions once a day and maps them onto Picsou accounts + holdings.
 *
 * <p>Lives in {@code com.picsou.service} alongside the other broker sync services
 * (Trade Republic, Bourso) so it can reuse {@link AccountService}'s package-private
 * {@code upsertSnapshot} / {@code toResponse} helpers. Mirrors
 * {@code TradeRepublicSyncService}'s broker→holdings pattern (delete + recompute,
 * VWAP de-dup, ISIN→ticker via {@link OpenFigiIsinConverter}). The one IBKR-specific
 * twist is currency: IBKR reports cost basis in each security's native currency, so
 * {@code averageBuyIn} is converted to the account's base currency via
 * {@code fxRateToBase}. Net worth itself never depends on that conversion — it is
 * recomputed live in EUR from tickers by {@link AccountService#liveBalanceEur}
 * (Yahoo/CoinGecko, FX-converted), exactly like every other holdings account.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class IbkrSyncService {

    private final IbkrFlexPort ibkrFlexPort;
    private final IbkrConnectionRepository connectionRepository;
    private final AccountRepository accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final AccountService accountService;
    private final OpenFigiIsinConverter isinConverter;
    private final SecurityIdentityService identityService;
    private final CryptoEncryption encryption;
    private final IbkrStatusWriter statusWriter;

    /** Column limits of {@code account_holding} (see V11): ticker VARCHAR(30), name VARCHAR(100). */
    private static final int MAX_TICKER_LEN = 30;
    private static final int MAX_NAME_LEN = 100;

    /**
     * Asset categories we price and cost-basis correctly (stocks/ETFs, mutual funds,
     * bonds, crypto). Everything else (options, futures, CFDs, warrants, ...) is a
     * derivative whose per-share cost basis ignores the contract multiplier and whose
     * mark rarely resolves through Yahoo/CoinGecko — see {@link #isReportable}.
     */
    private static final Set<String> REPORTABLE_ASSET_CATEGORIES = Set.of("STK", "FUND", "BOND", "CRYPTO");

    // ---------------------------------------------------------------------------
    // Connection management
    // ---------------------------------------------------------------------------

    /** Stores (or replaces) the encrypted Flex token + query id for a member. */
    public void connect(String token, String queryId, Long memberId) {
        if (token == null || token.isBlank() || queryId == null || queryId.isBlank()) {
            throw new IllegalArgumentException("Both the Flex token and the query id are required.");
        }
        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        IbkrConnection connection = connectionRepository.findByMemberId(memberId)
            .orElseGet(() -> IbkrConnection.builder().member(member).build());
        connection.setToken(encryption.encrypt(token.trim()));
        connection.setQueryId(encryption.encrypt(queryId.trim()));
        connection.setStatus("CONNECTED");
        connectionRepository.save(connection);
        log.info("IBKR Flex credentials stored for member {}", memberId);
    }

    @Transactional(readOnly = true)
    public IbkrConnectionStatusResponse getConnectionStatus(Long memberId) {
        Optional<IbkrConnection> connection = connectionRepository.findByMemberId(memberId);
        if (connection.isEmpty()) {
            return new IbkrConnectionStatusResponse(false, null, null, null, null);
        }
        IbkrConnection c = connection.get();
        String maskedToken;
        try {
            maskedToken = maskToken(encryption.decrypt(c.getToken()));
        } catch (RuntimeException ex) {
            // A rotated encryption key / corrupted row must degrade into a visible ERROR
            // status, not a 500 on the read-only endpoint the Sync page always calls.
            log.error("IBKR: cannot decrypt stored token for connection {}", c.getId(), ex);
            return new IbkrConnectionStatusResponse(true, c.getId(), "ERROR", c.getLastSyncedAt(), "••••");
        }
        return new IbkrConnectionStatusResponse(true, c.getId(), c.getStatus(), c.getLastSyncedAt(), maskedToken);
    }

    public void deleteConnection(Long memberId) {
        connectionRepository.findByMemberId(memberId).ifPresent(connectionRepository::delete);
        log.info("IBKR connection cleared for member {}", memberId);
    }

    // ---------------------------------------------------------------------------
    // Sync
    // ---------------------------------------------------------------------------

    /** Manual or scheduled sync using the stored connection. */
    public List<AccountResponse> sync(Long memberId) {
        IbkrConnection connection = connectionRepository.findByMemberId(memberId)
            .orElseThrow(() -> new SyncException(
                "No Interactive Brokers connection. Please connect from the Interactive Brokers page."));
        return syncWithConnection(connection, memberId);
    }

    /**
     * Scheduler entry point — no-op if the member has no connection. Swallows and logs
     * sync failures so they do not propagate — but this is best effort, NOT a hard
     * no-throw contract: when a repository call fails inside the sync, the shared
     * transaction is marked rollback-only through the repository's own proxy, and
     * Spring throws {@code UnexpectedRollbackException} at THIS method's proxy exit,
     * after the catch below. Call sites must wrap accordingly (SchedulerService does).
     */
    public void resyncIfConnected(Long memberId) {
        try {
            Optional<IbkrConnection> connection = connectionRepository.findByMemberId(memberId);
            if (connection.isEmpty()) {
                return;
            }
            syncWithConnection(connection.get(), memberId);
        } catch (SyncException ex) {
            // Expected operational failures (expired token, IBKR down, non-EUR guard):
            // the message is the whole story, status is already ERROR.
            log.warn("IBKR auto-sync failed for member {}: {}", memberId, ex.getMessage());
        } catch (RuntimeException ex) {
            // Anything else is a bug or infrastructure problem — keep the stack trace.
            log.error("IBKR auto-sync hit an unexpected error for member {}", memberId, ex);
        }
    }

    private List<AccountResponse> syncWithConnection(IbkrConnection connection, Long memberId) {
        try {
            return doSync(connection, memberId);
        } catch (RuntimeException ex) {
            // Any failure — decryption, the Flex fetch, the non-EUR base-currency guard,
            // a DB error while mapping — must leave a visible ERROR status, on both the
            // manual and the scheduled path. A plain save here would be lost on the
            // manual path: the rethrow marks this @Transactional method rollback-only,
            // so the status write never commits. statusWriter runs in its own
            // REQUIRES_NEW transaction, which survives the rollback.
            log.error("IBKR sync failed for connection {} — marking ERROR status", connection.getId(), ex);
            statusWriter.markError(connection.getId());
            throw ex;
        }
    }

    private List<AccountResponse> doSync(IbkrConnection connection, Long memberId) {
        String token;
        String queryId;
        try {
            token = encryption.decrypt(connection.getToken());
            queryId = encryption.decrypt(connection.getQueryId());
        } catch (RuntimeException ex) {
            throw new SyncException("Could not decrypt the stored IBKR credentials — the encryption "
                + "key may have changed. Please reconnect with a fresh token.", ex);
        }

        List<IbkrAccountData> accounts = ibkrFlexPort.fetchOpenPositions(token, queryId);

        List<AccountResponse> responses = accounts.stream()
            .map(data -> upsertAccount(data, memberId))
            .flatMap(Optional::stream)
            .toList();

        connection.setStatus("CONNECTED");
        connection.setLastSyncedAt(Instant.now());
        connectionRepository.save(connection);

        log.info("IBKR sync complete for member {}: {} account(s) updated", memberId, responses.size());
        return responses;
    }

    // ---------------------------------------------------------------------------
    // Mapping
    // ---------------------------------------------------------------------------

    private Optional<AccountResponse> upsertAccount(IbkrAccountData data, Long memberId) {
        String externalId = "ibkr_" + data.accountId();

        Optional<Account> existing = accountRepository.findByExternalAccountIdAndMemberId(externalId, memberId);
        if (existing.isEmpty()
            && accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(externalId, memberId)) {
            log.info("IBKR: skipping resurrection of soft-deleted account {} for member {}", externalId, memberId);
            return Optional.empty();
        }

        requireEurBaseCurrency(data);

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setLastSyncedAt(Instant.now());
        } else {
            FamilyMember member = familyMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            account = Account.builder()
                .member(member)
                .name("IBKR " + data.accountId())
                .type(AccountType.COMPTE_TITRES)
                .provider("Interactive Brokers")
                .currency("EUR")
                .currentBalance(BigDecimal.ZERO)
                .lastSyncedAt(Instant.now())
                .externalAccountId(externalId)
                .isManual(false)
                .color("#d81222") // IBKR red
                .build();
        }
        account = accountRepository.save(account);

        // Replace holdings wholesale — the statement is the full current picture.
        holdingRepository.deleteByAccountId(account.getId());
        holdingRepository.flush();

        // De-dup by resolved ticker (VWAP) exactly like Trade Republic: several IBKR
        // positions (or lot rows across accounts) can map to one ticker.
        Map<String, HoldingDedup.HoldingAgg> deduped = new HashMap<>();
        Map<String, String> isinByTicker = new HashMap<>();
        for (IbkrPosition p : data.positions()) {
            if (!isReportable(p)) {
                continue;
            }
            TickerResult resolved = resolveTicker(p);
            String ticker = resolved.ticker();
            if (ticker == null || ticker.isBlank()) {
                continue;
            }
            if (p.isin() != null && OpenFigiIsinConverter.isIsin(p.isin())) {
                isinByTicker.put(ticker, p.isin());
            }
            if (ticker.length() > MAX_TICKER_LEN) {
                // Options / futures carry long symbols (e.g. "SPY 240119C00470000") that
                // overflow account_holding.ticker (VARCHAR 30) and never price through Yahoo
                // anyway — skip the position rather than fail the whole account's sync.
                log.warn("IBKR: skipping position with over-long ticker '{}' ({} chars)", ticker, ticker.length());
                continue;
            }
            deduped.merge(
                ticker,
                new HoldingDedup.HoldingAgg(p.position(), eurCostBasis(p), eurMark(p), resolved.name()),
                HoldingDedup::vwapMerge);
        }
        int persisted = 0;
        identityService.record(isinByTicker);
        for (Map.Entry<String, HoldingDedup.HoldingAgg> entry : deduped.entrySet()) {
            HoldingDedup.HoldingAgg agg = entry.getValue();
            if (agg.quantity().signum() == 0) {
                // Mixed-sign positions (e.g. a short covered by an equal-and-opposite
                // long lot) can net to exactly zero in vwapMerge -- a flat position, not
                // a holding to persist.
                continue;
            }
            holdingRepository.save(AccountHolding.builder()
                .account(account)
                .ticker(entry.getKey())
                .name(clip(agg.name(), MAX_NAME_LEN))
                .quantity(agg.quantity())
                .averageBuyIn(agg.averageBuyIn())
                .currentPrice(agg.currentPrice())
                .lastSyncedAt(Instant.now())
                .build());
            persisted++;
        }
        holdingRepository.flush();

        // Net-worth-critical figures use the trusted live-EUR path (Yahoo/CoinGecko,
        // FX-converted), never IBKR's base currency — so net worth is right even if the
        // user's IBKR base currency is not EUR. With ZERO holdings the live path would
        // fall back to the account's stored currentBalance (see liveBalanceEur), which
        // for a fully liquidated statement account is exactly the stale value we must
        // NOT keep — a holdings-driven COMPTE_TITRES with nothing held is worth 0 here
        // (cash is not modeled: CASH lines are skipped by isReportable).
        BigDecimal liveEur = persisted == 0 ? BigDecimal.ZERO : accountService.liveBalanceEur(account);
        account.setCurrentBalance(liveEur);
        account = accountRepository.save(account);
        accountService.upsertSnapshot(account, liveEur, LocalDate.now());

        return Optional.of(accountService.toResponse(account));
    }

    /**
     * Guards against a non-EUR IBKR account base currency. {@code averageBuyIn} is
     * stored converted via {@code fxRateToBase} into the account's IBKR base currency
     * (see {@link #eurCostBasis}) -- if that base currency is not actually EUR, every
     * invested-amount / per-holding P&L% figure derived from it is silently wrong. Net
     * worth itself is unaffected (see the class javadoc: it is repriced live in EUR from
     * tickers, never from the stored cost basis). Fail fast rather than persist
     * quietly-wrong holdings for this account; a statement without the
     * {@code AccountInformation} section (no {@code baseCurrency}) keeps today's
     * assume-EUR behavior, with a once-per-account-sync WARN.
     */
    private void requireEurBaseCurrency(IbkrAccountData data) {
        String baseCurrency = data.baseCurrency();
        if (baseCurrency == null) {
            log.warn("IBKR: account {} statement has no AccountInformation/baseCurrency; assuming EUR",
                data.accountId());
            return;
        }
        if (!"EUR".equalsIgnoreCase(baseCurrency)) {
            throw new SyncException("IBKR account base currency is " + baseCurrency + "; Picsou currently "
                + "supports EUR-based accounts — change the base currency in IBKR Account Settings, "
                + "or reconnect after conversion support lands.");
        }
    }

    /**
     * Whether a position should become a holding: non-zero quantity, summary-level
     * (not a per-lot duplicate), not a cash line, and an asset category we can price
     * and cost-basis correctly ({@link #REPORTABLE_ASSET_CATEGORIES}, or absent). A
     * ticker source (ISIN or symbol) is required so we never persist a nameless holding.
     */
    private boolean isReportable(IbkrPosition p) {
        if (p.position() == null || p.position().signum() == 0) {
            return false;
        }
        // Flex can emit both a SUMMARY row and per-tax-lot ("LOT") rows when lots are
        // enabled on the query. Keep summary/absent, drop LOT, to avoid double counting.
        if (p.levelOfDetail() != null && "LOT".equalsIgnoreCase(p.levelOfDetail())) {
            return false;
        }
        if (p.assetCategory() != null && "CASH".equalsIgnoreCase(p.assetCategory())) {
            return false;
        }
        // Allowlist rather than the old over-long-ticker guard alone: standard OCC
        // option symbols (e.g. "SPY 240119C00470000", 19 chars) are well under the
        // 30-char column limit and used to pass straight through as a stock holding.
        // Skip (and warn) anything outside the categories we handle correctly so
        // day-1 real data reveals any category we got wrong.
        if (p.assetCategory() != null
            && !REPORTABLE_ASSET_CATEGORIES.contains(p.assetCategory().toUpperCase(Locale.ROOT))) {
            log.warn("IBKR: skipping unsupported assetCategory '{}' for symbol '{}'",
                p.assetCategory(), p.symbol());
            return false;
        }
        return (p.isin() != null && !p.isin().isBlank()) || (p.symbol() != null && !p.symbol().isBlank());
    }

    /**
     * Resolves an IBKR position to a price-able ticker + display name. Prefers the
     * ISIN (via OpenFIGI) so the holding prices through Yahoo/CoinGecko; falls back to
     * the IBKR symbol + description when there is no usable ISIN (e.g. some derivatives).
     */
    private TickerResult resolveTicker(IbkrPosition p) {
        if (p.isin() != null && OpenFigiIsinConverter.isIsin(p.isin())) {
            TickerResult result = isinConverter.resolve(p.isin());
            // OpenFIGI may return the ISIN unchanged (miss) and a null name — fall back
            // to the IBKR symbol/description, which at least price-resolves for US tickers.
            if (result.ticker() != null && !OpenFigiIsinConverter.isIsin(result.ticker())) {
                return result;
            }
            String ticker = p.symbol() != null ? p.symbol() : result.ticker();
            String name = result.name() != null ? result.name() : p.description();
            return new TickerResult(ticker, name);
        }
        return new TickerResult(p.symbol(), p.description());
    }

    /**
     * Cost basis per unit in the account base currency (≈EUR). Null when unknown —
     * including a non-EUR position WITHOUT {@code fxRateToBase}: "FX Rate to Base" is a
     * column the user must tick on the Flex Query, and treating a missing rate as 1 would
     * silently store a USD (or worse, JPY) price as if it were EUR. An unknown cost basis
     * (no invested/P&L figures) is honest; a wrong one is not.
     */
    private BigDecimal eurCostBasis(IbkrPosition p) {
        if (p.costBasisPrice() == null) {
            return null;
        }
        BigDecimal rate = fxToBaseOrNull(p);
        return rate == null ? null : p.costBasisPrice().multiply(rate);
    }

    /**
     * Mark price per unit in the account base currency (≈EUR). Informational only:
     * the stored {@code currentPrice} is never trusted for EUR valuation — the
     * dashboard recomputes live from the ticker (see {@link AccountService}).
     */
    private BigDecimal eurMark(IbkrPosition p) {
        if (p.markPrice() == null) {
            return null;
        }
        BigDecimal rate = fxToBaseOrNull(p);
        return rate == null ? null : p.markPrice().multiply(rate);
    }

    /**
     * FX rate to the account base currency, or null when it is genuinely unknown:
     * absent rate + non-EUR position currency. Absent rate + EUR (or unspecified)
     * currency keeps the historical assume-1 behavior.
     */
    private BigDecimal fxToBaseOrNull(IbkrPosition p) {
        if (p.fxRateToBase() != null) {
            return p.fxRateToBase();
        }
        if (p.currency() != null && !"EUR".equalsIgnoreCase(p.currency())) {
            log.warn("IBKR: position {} is in {} but the statement has no fxRateToBase — enable "
                + "the 'FX Rate to Base' column on the Flex Query; storing cost basis as unknown",
                p.symbol(), p.currency());
            return null;
        }
        return BigDecimal.ONE;
    }

    /** Truncates {@code s} to {@code max} chars so it fits its DB column; null-safe. */
    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Shows only the last 4 characters of the token, e.g. "••••••3F29". */
    private String maskToken(String token) {
        if (token == null || token.length() <= 4) {
            return "••••";
        }
        return "••••••" + token.substring(token.length() - 4);
    }
}
