package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.*;
import com.picsou.port.BankConnectorPort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RequisitionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.budget.RecurringDetectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final BankConnectorPort bankConnector;
    private final AccountRepository accountRepository;
    private final RequisitionRepository requisitionRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final AccountService accountService;
    private final TransactionRepository transactionRepository;
    private final CategorizationService categorizationService;
    private final RecurringDetectionService recurringDetectionService;
    private final RequisitionLifecycleWriter requisitionLifecycleWriter;
    private final BankLogoResolver bankLogoResolver;
    private final BankTransactionImportService bankTransactionImportService;

    /** How far back to pull transactions on each sync; dedup makes the overlap harmless. */
    private static final int TRANSACTION_LOOKBACK_DAYS = 90;

    public SyncService(
        BankConnectorPort bankConnector,
        AccountRepository accountRepository,
        RequisitionRepository requisitionRepository,
        FamilyMemberRepository familyMemberRepository,
        AccountService accountService,
        TransactionRepository transactionRepository,
        CategorizationService categorizationService,
        RecurringDetectionService recurringDetectionService,
        RequisitionLifecycleWriter requisitionLifecycleWriter,
        BankLogoResolver bankLogoResolver,
        BankTransactionImportService bankTransactionImportService
    ) {
        this.bankConnector = bankConnector;
        this.accountRepository = accountRepository;
        this.requisitionRepository = requisitionRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.accountService = accountService;
        this.transactionRepository = transactionRepository;
        this.categorizationService = categorizationService;
        this.recurringDetectionService = recurringDetectionService;
        this.requisitionLifecycleWriter = requisitionLifecycleWriter;
        this.bankLogoResolver = bankLogoResolver;
        this.bankTransactionImportService = bankTransactionImportService;
    }

    /**
     * Re-run recurring detection for a member after a sync, isolating any failure so it never
     * rolls back the freshly-ingested balances and transactions.
     */
    private void detectRecurring(Long memberId) {
        try {
            recurringDetectionService.detect(memberId, LocalDate.now());
        } catch (Exception ex) {
            log.warn("Recurring detection skipped for member {}: {}", memberId, ex.getMessage());
        }
    }

    /** Step 1: Initiate Enable Banking bank connection for a given institution. */
    public InitiateResponse initiateConnection(String institutionId, String institutionName, Long memberId) {
        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        String state = UUID.randomUUID().toString();
        BankConnectorPort.InitiateResult result = bankConnector.initiateConnection(institutionId, state);

        Requisition requisition = Requisition.builder()
            .member(member)
            .requisitionId(result.requisitionId())
            .institutionId(institutionId)
            .institutionName(institutionName)
            .logoUrl(bankLogoResolver.logoUrlOrNull(
                BankLogoResolver.countryOf(institutionId), institutionId, institutionName))
            .status(RequisitionStatus.CREATED)
            .authLink(result.authLink())
            .oauthState(state)
            .build();

        requisitionRepository.save(requisition);

        return new InitiateResponse(result.requisitionId(), result.authLink());
    }

    /** Step 2: Complete Enable Banking flow -- exchange OAuth code, fetch balances, upsert accounts. */
    public List<AccountResponse> completeConnection(String oauthCode, String state, Long memberId) {
        Requisition requisition = resolveCallbackRequisition(state, memberId);
        // The state nonce is the callback's credential; the member it was issued
        // for wins over the current user context (fixes admin impersonation:
        // initiation under ?memberId=X must complete under X too).
        Long targetMemberId = requisition.getMember().getId();

        String sessionId;
        try {
            sessionId = bankConnector.exchangeCode(oauthCode);
        } catch (SyncException ex) {
            // Code already used -> refresh the latest linked session of the SAME
            // institution (a replayed Revolut callback must not resync BNP).
            if (ex.getMessage().contains("ALREADY_AUTHORIZED")) {
                log.info("Code already used, refreshing latest linked session for {}", requisition.getInstitutionName());
                return resyncLatest(targetMemberId, requisition.getInstitutionId());
            }
            // Keep oauthState so a transient exchange failure can replay the same
            // callback, but persist FAILED independently of this transaction's rollback.
            requisitionLifecycleWriter.markFailed(requisition.getId(), targetMemberId);
            throw ex;
        }

        // The OAuth code is consumed as soon as exchangeCode succeeds. Commit its
        // session id and clear the spent nonce in a separate physical transaction,
        // before any provider fetch or account write can fail.
        requisitionLifecycleWriter.checkpointSession(requisition.getId(), targetMemberId, sessionId);

        try {
            List<BankConnectorPort.AccountData> accountDataList = bankConnector.fetchBalances(sessionId);

            if (accountDataList.isEmpty()) {
                requisitionLifecycleWriter.markFailed(requisition.getId(), targetMemberId);
                log.info("Enable Banking requisition {} ({}) returned no accounts during completion — marking retryable",
                    requisition.getId(), requisition.getInstitutionName());
                return List.of();
            }

            FamilyMember member = requisition.getMember();

            List<AccountResponse> responses = accountDataList.stream()
                .map(data -> upsertAccount(data, requisition, member, sessionId))
                .flatMap(Optional::stream)
                .toList();

            // Force deferred account/snapshot constraints to fail inside this
            // guarded block rather than during the transaction commit, where the
            // lifecycle writer would no longer have a chance to mark FAILED.
            accountRepository.flush();

            // Bring the outer transaction's managed entity in line with the
            // independently committed checkpoint only after every upsert succeeds.
            requisition.setRequisitionId(sessionId);
            requisition.setOauthState(null);
            requisition.setStatus(RequisitionStatus.LINKED);
            requisition.setLastSyncedAt(Instant.now());
            requisitionRepository.save(requisition);

            detectRecurring(member.getId());

            log.info("Completed Enable Banking sync for {}: {} accounts linked", requisition.getInstitutionName(), responses.size());
            return responses;
        } catch (RuntimeException ex) {
            // This write uses REQUIRES_NEW, so it survives even when Hibernate has
            // already marked the account transaction rollback-only.
            requisitionLifecycleWriter.markFailed(requisition.getId(), targetMemberId);
            if (ex instanceof SyncException syncException) {
                throw syncException;
            }
            throw new SyncException(
                "Synchronized bank accounts could not be saved. Please retry the connection.",
                ex
            );
        }
    }

    /** Search available institutions. */
    @Transactional(readOnly = true)
    public List<BankConnectorPort.InstitutionData> searchInstitutions(String query, String country) {
        return bankConnector.searchInstitutions(query, country);
    }

    /** Countries the active provider has institutions for, for the "which country" selector. */
    @Transactional(readOnly = true)
    public List<String> listCountries() {
        return bankConnector.listCountries();
    }

    /** Get all requisitions for a member ordered by date. */
    @Transactional(readOnly = true)
    public List<Requisition> getAllRequisitions(Long memberId) {
        return requisitionRepository.findAllByMemberId(memberId);
    }

    /** Retry fetching accounts for a FAILED requisition using the stored session_id. */
    @Transactional(noRollbackFor = SyncException.class)
    public List<AccountResponse> retrySync(Long id, Long memberId) {
        Requisition req = requisitionRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Requisition not found"));

        log.info("Retrying sync for {} (requisition #{})", req.getInstitutionName(), req.getId());
        ensureLogoUrl(req);

        List<BankConnectorPort.AccountData> accountDataList;
        try {
            accountDataList = bankConnector.fetchBalances(req.getRequisitionId());
        } catch (SyncException ex) {
            req.setStatus(RequisitionStatus.FAILED);
            requisitionRepository.save(req);
            throw ex;
        }

        FamilyMember member = req.getMember();

        List<AccountResponse> responses = accountDataList.stream()
            .map(data -> upsertAccount(data, req, member, req.getRequisitionId()))
            .flatMap(Optional::stream)
            .toList();

        if (markRetryableIfEmpty(req, accountDataList, "retry")) {
            return responses;
        }

        req.setStatus(RequisitionStatus.LINKED);
        req.setLastSyncedAt(Instant.now());
        requisitionRepository.save(req);

        detectRecurring(member.getId());

        log.info("Retry sync OK for {}: {} accounts linked", req.getInstitutionName(), responses.size());
        return responses;
    }

    /**
     * Re-initiates the Enable Banking OAuth flow for an existing requisition
     * whose session is dead (failed code exchange, expired/revoked PSD2
     * consent). The requisition row is reused: status returns to CREATED and
     * the new authorization id replaces the stale session id. Accounts are
     * preserved because {@link #upsertAccount} matches on externalAccountId.
     */
    public InitiateResponse reconnect(Long id, Long memberId) {
        Requisition req = requisitionRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Requisition not found"));

        // A LINKED requisition holds a working session id; overwriting it with a
        // fresh (unconsumed) authorization id would break scheduled syncs if the
        // user abandons the new OAuth flow. Only dead connections may reconnect.
        if (req.getStatus() == RequisitionStatus.LINKED) {
            throw new SyncException(
                "This bank connection is still active. Use sync/retry instead, or delete it to start over.");
        }

        String state = UUID.randomUUID().toString();
        BankConnectorPort.InitiateResult result = bankConnector.initiateConnection(req.getInstitutionId(), state);

        req.setRequisitionId(result.requisitionId());
        req.setAuthLink(result.authLink());
        req.setStatus(RequisitionStatus.CREATED);
        req.setOauthState(state);
        requisitionRepository.save(req);

        log.info("Re-initiated Enable Banking auth for {} (requisition {})", req.getInstitutionName(), id);
        return new InitiateResponse(result.requisitionId(), result.authLink());
    }

    /** Delete a requisition (cancel or remove a bank connection). */
    public void deleteRequisition(Long id, Long memberId) {
        Requisition req = requisitionRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Requisition not found"));
        requisitionRepository.delete(req);
        log.info("Deleted requisition {}", id);
    }

    /** Retry all FAILED Enable Banking sessions for a member (called by scheduler). */
    public void retryAllFailed(Long memberId) {
        List<Requisition> failed = requisitionRepository
            .findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.FAILED, memberId);
        for (Requisition req : failed) {
            try {
                retrySync(req.getId(), memberId);
            } catch (Exception ex) {
                log.warn("Scheduled retry failed for {} (requisition #{}): {}",
                    req.getInstitutionName(), req.getId(), ex.getMessage());
            }
        }
    }

    /** Re-sync all LINKED requisitions for a specific member (called by scheduler). */
    public void resyncAll(Long memberId) {
        List<Requisition> linked = requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId);
        for (Requisition req : linked) {
            try {
                ensureLogoUrl(req);
                List<BankConnectorPort.AccountData> accounts = bankConnector.fetchBalances(req.getRequisitionId());
                if (markRetryableIfEmpty(req, accounts, "resync")) {
                    continue;
                }
                FamilyMember member = req.getMember();
                accounts.forEach(data -> upsertAccount(data, req, member, req.getRequisitionId()));
                req.setLastSyncedAt(Instant.now());
                requisitionRepository.save(req);
                detectRecurring(member.getId());
                log.info("Auto-resync OK for {}: {} accounts", req.getInstitutionName(), accounts.size());
            } catch (Exception ex) {
                req.setStatus(RequisitionStatus.FAILED);
                requisitionRepository.save(req);
                log.warn("Auto-resync failed for {}: {}", req.getInstitutionName(), ex.getMessage());
            }
        }
    }

    /**
     * Resolves the requisition an OAuth callback belongs to. The state nonce is
     * authoritative when it matches. Requisitions created before the nonce
     * shipped DID send a state (the old connector's {@code appId_timestamp}
     * format) that was never persisted, so an unknown or missing state falls
     * back to the latest CREATED requisition <b>without a stored nonce</b> —
     * post-migration rows always carry one, so they can never be captured by a
     * crafted state, and the fallback self-retires once legacy rows are gone.
     */
    private Requisition resolveCallbackRequisition(String state, Long memberId) {
        if (state != null && !state.isBlank()) {
            Optional<Requisition> byState = requisitionRepository.findByOauthState(state);
            if (byState.isPresent()) {
                return byState.get();
            }
            log.warn("OAuth callback state not found — trying legacy (pre-nonce) requisitions for member {}", memberId);
        } else {
            log.warn("OAuth callback without state — trying legacy (pre-nonce) requisitions for member {}", memberId);
        }
        return requisitionRepository
            .findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.CREATED, memberId)
            .stream()
            .filter(r -> r.getOauthState() == null)
            .findFirst()
            .orElseThrow(() -> new SyncException(
                "Unknown or expired bank connection. Please initiate a new connection."));
    }

    /** Refresh balances for the most recent LINKED session of the given institution. */
    private List<AccountResponse> resyncLatest(Long memberId, String institutionId) {
        Requisition req = requisitionRepository
            .findByStatusAndMemberIdAndInstitutionIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId, institutionId)
            .stream().findFirst()
            .orElseThrow(() -> new SyncException("No linked session found to refresh."));

        ensureLogoUrl(req);
        FamilyMember member = req.getMember();

        List<BankConnectorPort.AccountData> accountDataList = bankConnector.fetchBalances(req.getRequisitionId());
        if (markRetryableIfEmpty(req, accountDataList, "refresh")) {
            return List.of();
        }
        List<AccountResponse> responses = accountDataList.stream()
            .map(data -> upsertAccount(data, req, member, req.getRequisitionId()))
            .flatMap(Optional::stream)
            .toList();
        req.setLastSyncedAt(Instant.now());
        requisitionRepository.save(req);
        detectRecurring(member.getId());
        log.info("Refreshed {} accounts for {}", responses.size(), req.getInstitutionName());
        return responses;
    }

    // --- Private ---

    /**
     * Enable Banking can return an empty account list while the provider is still
     * linking accounts asynchronously. Treating that as success hides the retry
     * button and makes the UI claim "synced" even though no account changed.
     */
    private boolean markRetryableIfEmpty(
        Requisition requisition,
        List<BankConnectorPort.AccountData> accountDataList,
        String operation
    ) {
        if (!accountDataList.isEmpty()) return false;

        // An already-LINKED session that suddenly returns no accounts is more likely a
        // transient provider gap than a broken link. Demoting it would make the status
        // flap LINKED → FAILED on every scheduled resync — keep it LINKED and just skip.
        if (requisition.getStatus() == RequisitionStatus.LINKED) {
            log.warn("Enable Banking requisition #{} returned no accounts during {} — keeping LINKED, skipping update",
                requisition.getId(), operation);
            return true;
        }

        requisition.setStatus(RequisitionStatus.FAILED);
        requisitionRepository.save(requisition);
        log.info("Enable Banking requisition #{} returned no accounts during {} — marking retryable",
            requisition.getId(), operation);
        return true;
    }

    /**
     * Best-effort backfill for requisitions created before bank logos were captured
     * (or whose logo lookup missed the first time): re-searches institutions, scoped
     * to the requisition's own country, and stores the match's logo, if any.
     *
     * <p>Bounded to a single attempt per requisition via {@code logoBackfillAttemptedAt}
     * — a miss (renamed institution, no provider logo) is not retried on every
     * resync/retry forever. The marker is only set once the search call actually
     * completes, so a transient network failure can still be retried next sync.
     */
    private void ensureLogoUrl(Requisition req) {
        if (req.getLogoUrl() != null || req.getLogoBackfillAttemptedAt() != null) return;
        try {
            // The throwing variant, not logoUrlOrNull: only a search that actually completed
            // may set the marker below, or a provider outage would burn the single attempt.
            Optional<String> logoUrl = bankLogoResolver.logoUrl(
                BankLogoResolver.countryOf(req.getInstitutionId()),
                req.getInstitutionId(),
                req.getInstitutionName()
            );
            req.setLogoBackfillAttemptedAt(Instant.now());
            logoUrl.ifPresent(req::setLogoUrl);
        } catch (Exception ex) {
            log.warn("Could not backfill logo for requisition {} ({}): {}", req.getId(), req.getInstitutionName(), ex.getMessage());
        }
    }

    /**
     * Returns {@link Optional#empty()} when the matching account was soft-deleted
     * by the user — we must not resurrect it on the next sync. The bank may keep
     * returning the same external id forever; that's not consent to bring it back.
     *
     * <p>Matching strategy (Enable Banking v0.16.4 uid-rotation resilience):
     * <ol>
     *   <li>If the account has an IBAN, look up by {@code (iban, memberId)} first — IBAN is
     *       stable even when the provider uid changes (e.g. Boursorama after EB v0.16.4).
     *       When matched via IBAN, the stored {@code externalAccountId} is refreshed to the
     *       current uid so future syncs stay aligned.</li>
     *   <li>Fall back to {@code (externalAccountId, memberId)} for accounts without an IBAN
     *       and for providers whose uid never changes.</li>
     * </ol>
     * Soft-delete guards follow the same two-step order.
     *
     * <p>{@code sessionId} is passed in rather than read off the requisition: during
     * {@code completeConnection} the row still carries the (now spent) authorization
     * id at this point, and the live session is only written onto it once every
     * upsert has succeeded.
     */
    private Optional<AccountResponse> upsertAccount(
        BankConnectorPort.AccountData data,
        Requisition requisition,
        FamilyMember member,
        String sessionId
    ) {
        // Step 1: locate an existing active account (IBAN-first when available)
        Optional<Account> existing = Optional.empty();
        if (data.iban() != null) {
            existing = accountRepository.findByIbanAndMemberId(data.iban(), member.getId());
        }
        if (existing.isEmpty()) {
            existing = accountRepository.findByExternalAccountIdAndMemberId(data.externalId(), member.getId());
        }

        // Step 2: soft-delete guard — refuse to resurrect an account the user removed
        if (existing.isEmpty()) {
            boolean softDeleted = (data.iban() != null &&
                accountRepository.existsSoftDeletedByIbanAndMemberId(data.iban(), member.getId()))
                || accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), member.getId());
            if (softDeleted) {
                log.info("Skipping resurrection of soft-deleted account externalId={} iban={} member={}",
                    data.externalId(), data.iban(), member.getId());
                return Optional.empty();
            }
        }

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(data.balance());
            account.setLastSyncedAt(Instant.now());
            // Refresh uid in case the provider rotated it (EB v0.16.4 Boursorama case)
            account.setExternalAccountId(data.externalId());
            if (data.iban() != null) {
                account.setIban(data.iban());
            }
            // Backfill the bank logo on a pre-logo account once the requisition has one.
            if (account.getLogoUrl() == null && requisition.getLogoUrl() != null) {
                account.setLogoUrl(requisition.getLogoUrl());
            }
            // Also on the update path, so accounts that predate V76 and the ones its
            // name-matching backfill had to leave NULL get linked on their next sync.
            account.setRequisitionId(requisition.getId());
        } else {
            account = Account.builder()
                .member(member)
                .name(data.name() != null ? data.name() : "Account")
                .type(AccountType.CHECKING)
                .provider(requisition.getInstitutionName())
                .currency(data.currency() != null ? data.currency() : "EUR")
                .currentBalance(data.balance())
                .lastSyncedAt(Instant.now())
                .externalAccountId(data.externalId())
                .iban(data.iban())
                .isManual(false)
                .color("#6366f1")
                .logoUrl(requisition.getLogoUrl())
                .requisitionId(requisition.getId())
                .build();
        }

        account = accountRepository.save(account);
        accountService.upsertSnapshotFromNative(account, data.balance(), LocalDate.now());

        // Balances first, transactions after, and never the other way round: the import
        // is best-effort (it swallows provider failures) so that an ASPSP which serves
        // balances but refuses /transactions still produces the sync the user asked for.
        bankTransactionImportService.importFor(account, sessionId);

        return Optional.of(accountService.toResponse(account));
    }

    /**
     * Pull recent transactions for a synced account, skipping any already stored
     * (dedup by {@code (account, externalId)}), and auto-categorize each new one via the
     * member's rules. The account's authoritative balance still comes from the balance
     * endpoint — we never recompute it from this partial transaction window. Failures here
     * are logged and swallowed so a transaction hiccup never breaks the balance sync.
     */
    private void ingestTransactions(Account account, String sessionId, FamilyMember member) {
        if (account.getExternalAccountId() == null) {
            return;
        }
        LocalDate from = LocalDate.now().minusDays(TRANSACTION_LOOKBACK_DAYS);
        List<BankConnectorPort.TransactionData> fetched;
        try {
            fetched = bankConnector.fetchTransactions(sessionId, account.getExternalAccountId(), from);
        } catch (Exception ex) {
            log.warn("Transaction ingestion skipped for account {}: {}", account.getId(), ex.getMessage());
            return;
        }

        // Load the member's rules + categories-by-slug once and reuse across the whole window
        // (no per-transaction queries); each new transaction is enriched + categorized in memory.
        CategorizationService.CategorizationContext categorization =
            categorizationService.loadContext(member.getId());

        int inserted = 0;
        for (BankConnectorPort.TransactionData data : fetched) {
            if (data.externalId() != null
                && transactionRepository.existsByAccountIdAndExternalId(account.getId(), data.externalId())) {
                continue;
            }
            Transaction tx = Transaction.builder()
                .account(account)
                .date(data.date())
                .description(data.description())
                .amount(data.amount())
                .counterparty(data.counterparty())
                .externalId(data.externalId())
                .nativeCurrency(data.currency() != null ? data.currency() : "EUR")
                .isManual(false)
                .build();
            categorizationService.autoCategorize(tx, categorization);
            transactionRepository.save(tx);
            inserted++;
        }
        if (inserted > 0) {
            log.info("Ingested {} new transactions for account {}", inserted, account.getId());
        }
    }

    public record InitiateResponse(String requisitionId, String authLink) {}
}
