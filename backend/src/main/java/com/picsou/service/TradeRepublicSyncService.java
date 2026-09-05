package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.TradeRepublicSession;
import com.picsou.port.TradeRepublicPort;
import com.picsou.port.TradeRepublicPort.TrAccountData;
import com.picsou.port.TradeRepublicPort.TrPosition;
import com.picsou.port.TradeRepublicPort.TrTokens;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TradeRepublicSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final FamilyMemberRepository        familyMemberRepository;
    private final AccountService                accountService;
    private final OpenFigiIsinConverter         isinConverter;
    private final CryptoEncryption              encryption;
    private final TransactionTemplate           txTemplate;

    public TradeRepublicSyncService(
        TradeRepublicPort trPort,
        TradeRepublicSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        FamilyMemberRepository familyMemberRepository,
        AccountService accountService,
        OpenFigiIsinConverter isinConverter,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate
    ) {
        this.trPort            = trPort;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.accountService    = accountService;
        this.isinConverter     = isinConverter;
        this.encryption        = encryption;
        this.txTemplate        = txTemplate;
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

        account = accountRepository.save(account);

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
            for (TrPosition p : data.positions()) {
                var result = isinConverter.resolve(p.isin());
                String ticker = result.ticker();
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

    public record AuthInitResponse(String processId) {}

    public record SessionStatusResponse(boolean isActive, Instant expiresAt) {}
}
