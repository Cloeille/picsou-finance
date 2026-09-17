package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.exception.DegiroSessionExpiredException;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.DegiroSession;
import com.picsou.model.DegiroSessionStatus;
import com.picsou.model.FamilyMember;
import com.picsou.port.DegiroPort;
import com.picsou.port.DegiroPort.DegiroPortfolioData;
import com.picsou.port.DegiroPort.DegiroPosition;
import com.picsou.port.DegiroPort.InitiateResult;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.DegiroSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DEGIRO (compte-titres only — no PEA envelope in France) via an unofficial,
 * reverse-engineered API sidecar.
 *
 * <p><b>Deliberately no scheduled background resync</b> (unlike Bourso/Bourse
 * Direct/TR): DEGIRO's session times out after ~30 minutes with no refresh
 * token, and Picsou never stores the account's TOTP secret to re-authenticate
 * unattended — that would be a materially bigger trust step than a session
 * cookie (a durable second factor vs. a revocable token). See
 * docs/decisions/2026-08-05-degiro-session-only-no-stored-totp.md. A sync
 * attempt against an expired session flips the stored status to
 * {@code REAUTH_REQUIRED} instead of retrying or failing silently; the user
 * reconnects from the DEGIRO tab whenever they want fresh data.
 *
 * <p>v1 scope is account valuation + open positions only (same exclusion
 * Bourse Direct made) — order/transaction history is out of scope; users who
 * want historical trades can backfill them through the generic CSV importer.
 */
@Service
@Transactional
public class DegiroSyncService {

    private static final Logger log = LoggerFactory.getLogger(DegiroSyncService.class);

    private static final String EXTERNAL_ACCOUNT_ID = "degiro-portfolio";
    private static final String PROVIDER = "DEGIRO";
    private static final String COLOR = "#f97316";

    private final DegiroPort               degiroPort;
    private final DegiroSessionRepository  sessionRepository;
    private final AccountRepository        accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final FamilyMemberRepository   familyMemberRepository;
    private final AccountService           accountService;
    private final OpenFigiIsinConverter    isinConverter;
    private final SecurityIdentityService  identityService;
    private final CryptoEncryption         encryption;
    private final DegiroSessionStatusWriter statusWriter;

    public DegiroSyncService(
        DegiroPort degiroPort,
        DegiroSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        FamilyMemberRepository familyMemberRepository,
        AccountService accountService,
        OpenFigiIsinConverter isinConverter,
        SecurityIdentityService identityService,
        CryptoEncryption encryption,
        DegiroSessionStatusWriter statusWriter
    ) {
        this.degiroPort          = degiroPort;
        this.sessionRepository   = sessionRepository;
        this.accountRepository   = accountRepository;
        this.holdingRepository   = holdingRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.accountService      = accountService;
        this.isinConverter       = isinConverter;
        this.identityService     = identityService;
        this.encryption          = encryption;
        this.statusWriter        = statusWriter;
    }

    // ─── Auth ─────────────────────────────────────────────────────────────────

    public AuthInitResponse initiateAuth(String username, String password, Long memberId) {
        InitiateResult result = degiroPort.initiateAuth(username, password);

        if (!result.totpRequired()) {
            storeSessionAndSync(result.sessionBlob(), memberId);
            return new AuthInitResponse(null, false);
        }

        return new AuthInitResponse(result.processId(), true);
    }

    public SessionStatusResponse completeAuth(String processId, String code, Long memberId) {
        String blob = degiroPort.completeAuth(processId, code);
        return storeSessionAndSync(blob, memberId);
    }

    private SessionStatusResponse storeSessionAndSync(String plainBlob, Long memberId) {
        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        DegiroSession session = sessionRepository.findByMemberId(memberId).orElseGet(() ->
            DegiroSession.builder().member(member).build());
        session.setSessionBlob(encryption.encrypt(plainBlob));
        session.setStatus(DegiroSessionStatus.ACTIVE);
        session.setLastError(null);
        sessionRepository.save(session);

        log.info("DEGIRO session stored for member {}", memberId);

        try {
            doSync(plainBlob, memberId);
        } catch (DegiroSessionExpiredException ex) {
            // Deliberately NOT statusWriter here, unlike syncWithBlob: the row above was
            // written by *this* still-uncommitted transaction, so a REQUIRES_NEW write
            // would either miss it entirely (fresh row, not yet visible to another
            // connection) or block on its lock until the DB times out. Nothing rethrows
            // past this point, so this transaction commits and a direct write on the
            // managed entity is both sufficient and safe.
            log.warn("DEGIRO session expired during the initial sync for member {} — marking REAUTH_REQUIRED", memberId);
            session.setStatus(DegiroSessionStatus.REAUTH_REQUIRED);
            session.setLastError("SESSION_EXPIRED");
        } catch (Exception ex) {
            log.warn("DEGIRO initial sync after auth failed for member {}: {}", memberId, ex.getMessage());
        }

        return getSessionStatus(memberId);
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────

    /** Manual sync, triggered from the DEGIRO tab. No scheduled equivalent — see class Javadoc. */
    public AccountResponse sync(Long memberId) {
        DegiroSession stored = sessionRepository.findByMemberId(memberId)
            .orElseThrow(() -> new SyncException("No DEGIRO session. Please connect from the DEGIRO page."));
        if (stored.getStatus() == DegiroSessionStatus.REAUTH_REQUIRED) {
            throw new SyncException("Your DEGIRO session has expired. Please reconnect from the DEGIRO page.");
        }
        return syncWithBlob(encryption.decrypt(stored.getSessionBlob()), memberId);
    }

    /**
     * The sync itself, with no expiry bookkeeping. Callers decide how an expired
     * session is recorded: {@link #syncWithBlob} writes it through {@code statusWriter}
     * because it rethrows into a rollback, whereas the post-auth path in
     * {@link #storeSessionAndSync} writes the managed entity directly (see the comment
     * there — its row is not committed yet, so a REQUIRES_NEW write cannot see it).
     */
    private AccountResponse doSync(String plainBlob, Long memberId) {
        DegiroPortfolioData data = degiroPort.fetchPortfolio(plainBlob);
        AccountResponse response = upsertAccount(data, memberId);
        sessionRepository.findByMemberId(memberId).ifPresent(s -> {
            s.setLastSyncedAt(Instant.now());
            s.setStatus(DegiroSessionStatus.ACTIVE);
            s.setLastError(null);
        });
        log.info("DEGIRO sync complete for member {}", memberId);
        return response;
    }

    private AccountResponse syncWithBlob(String plainBlob, Long memberId) {
        try {
            return doSync(plainBlob, memberId);
        } catch (DegiroSessionExpiredException e) {
            log.warn("DEGIRO session expired for member {} — marking REAUTH_REQUIRED", memberId);
            // Written through statusWriter, not this managed entity: rethrowing marks this
            // @Transactional method rollback-only, so a plain save here would be discarded
            // and the next sync would sail past the REAUTH_REQUIRED guard in sync() and hit
            // the sidecar again instead of prompting the user to reconnect.
            statusWriter.markReauthRequired(memberId);
            throw e;
        }
    }

    // ─── Session status ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SessionStatusResponse getSessionStatus(Long memberId) {
        Optional<DegiroSession> session = sessionRepository.findByMemberId(memberId);
        if (session.isEmpty()) {
            // null, not FAILED: "never connected" and "a sync failed" are different states,
            // and FAILED carries a stored last_error the migration's CHECK constraint
            // requires — there is no error to report when there is no session at all.
            return new SessionStatusResponse(false, null, null);
        }
        DegiroSession s = session.get();
        boolean active = s.getStatus() == DegiroSessionStatus.ACTIVE;
        return new SessionStatusResponse(active, s.getStatus(), s.getLastSyncedAt());
    }

    public void clearSession(Long memberId) {
        sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);
        log.info("DEGIRO session cleared for member {}", memberId);
    }

    // ─── Upsert ───────────────────────────────────────────────────────────────

    private AccountResponse upsertAccount(DegiroPortfolioData data, Long memberId) {
        Map<String, HoldingDedup.HoldingAgg> deduped = new HashMap<>();
        Map<String, String> isinByTicker = new HashMap<>();
        for (DegiroPosition p : data.positions()) {
            String ticker;
            String name = p.name();
            if (p.isin() != null && !p.isin().isBlank()) {
                var resolved = isinConverter.resolve(p.isin());
                ticker = resolved.ticker();
                if (resolved.name() != null) name = resolved.name();
                isinByTicker.put(ticker, p.isin());
            } else {
                ticker = p.symbol();
            }
            deduped.merge(
                ticker,
                new HoldingDedup.HoldingAgg(p.quantity(), p.buyingPrice(), p.currentPrice(), name),
                HoldingDedup::vwapMerge);
        }
        identityService.record(isinByTicker);

        // Total account value = cash + positions, mirroring Bourse Direct's
        // balanceEur/cashBalance split — DEGIRO's API gives us cash and per-position
        // prices directly, not a pre-computed grand total, so it's summed here rather
        // than trusted from a field we haven't verified exists.
        BigDecimal positionsValueEur = deduped.values().stream()
            .filter(agg -> agg.quantity().signum() != 0)
            .map(agg -> agg.quantity().multiply(agg.currentPrice() != null ? agg.currentPrice() : BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalValueEur = data.cashEur().add(positionsValueEur);

        Optional<Account> existing =
            accountRepository.findByExternalAccountIdAndMemberId(EXTERNAL_ACCOUNT_ID, memberId);

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(totalValueEur);
            account.setCashBalance(data.cashEur());
            account.setLastSyncedAt(Instant.now());
        } else {
            FamilyMember member = familyMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            account = Account.builder()
                .member(member)
                .name("DEGIRO")
                .type(AccountType.COMPTE_TITRES)
                .provider(PROVIDER)
                .currency("EUR")
                .currentBalance(totalValueEur)
                .cashBalance(data.cashEur())
                .lastSyncedAt(Instant.now())
                .externalAccountId(EXTERNAL_ACCOUNT_ID)
                .isManual(false)
                .color(COLOR)
                .build();
        }

        account = accountRepository.save(account);
        accountService.upsertSnapshot(account, totalValueEur, LocalDate.now());

        holdingRepository.deleteByAccountId(account.getId());
        holdingRepository.flush();

        for (Map.Entry<String, HoldingDedup.HoldingAgg> entry : deduped.entrySet()) {
            HoldingDedup.HoldingAgg agg = entry.getValue();
            if (agg.quantity().signum() == 0) continue;
            holdingRepository.save(AccountHolding.builder()
                .account(account)
                .ticker(entry.getKey())
                .name(agg.name())
                .quantity(agg.quantity())
                .averageBuyIn(agg.averageBuyIn())
                .currentPrice(agg.currentPrice())
                .lastSyncedAt(Instant.now())
                .build());
        }

        return accountService.toResponse(account);
    }

    // ─── Response records ─────────────────────────────────────────────────────

    public record AuthInitResponse(String processId, boolean totpRequired) {}

    public record SessionStatusResponse(
        boolean isActive,
        DegiroSessionStatus status,
        Instant lastSyncedAt
    ) {}
}
