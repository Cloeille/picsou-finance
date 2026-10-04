package com.picsou.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.DiscoveredRevolutAccount;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.FamilyMember;
import com.picsou.model.RevolutSession;
import com.picsou.model.Transaction;
import com.picsou.port.RevolutPort;
import com.picsou.port.RevolutPort.RevolutAccountData;
import com.picsou.port.RevolutPort.RevolutTxn;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RevolutSessionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.sync.SyncProgressService;
import com.picsou.service.sync.SyncProvider;
import com.picsou.service.sync.SourceSyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Orchestrates the Revolut sidecar connector in its on-demand model: every {@link #sync} call
 * hands phone+passcode straight to the sidecar (which reuses a live per-member browser profile
 * session when possible, or performs an automated login with mobile push approval), then upserts
 * the harvested accounts. Java holds no standing browser session -- the durable state is a single
 * {@link RevolutSession} row per member, always upserted (with a fresh {@code lastSyncedAt}) after
 * every successful sync, doubling as "the sidecar has synced this member" marker for downstream
 * consumers. It only carries encrypted credentials when the member explicitly opted in (see
 * {@code remember} below); otherwise it is bookkeeping only.
 *
 * <p>Revolut is the <b>primary</b> source for Revolut assets; Enable Banking stays connected as a
 * <b>fallback</b> for the current account (dedup by IBAN in {@link #upsertAccount}, mirroring
 * {@code SyncService.upsertAccount}). Per the sidecar's rate-limit rule, auto-sync
 * ({@link #resyncIfSessionActive}) must never loop or retry aggressively -- a failure is logged
 * and swallowed, leaving Enable Banking to carry the gap until the next scheduled attempt.
 *
 * <p><b>Manual on-demand sync</b> ({@link #sync}) stays fully synchronous for the scheduler and
 * existing callers. The controller's manual path instead runs {@link #discover} in the
 * background (harvest only, no DB writes) and {@link #confirmSync} to persist only the accounts
 * the member selected -- see {@link SyncProgressService} for the in-memory hand-off between the
 * two. Both routes funnel through {@link #harvest} (sidecar call) and {@link #persistSelected}
 * (the actual upsert loop, scoped to a short {@link TransactionTemplate} transaction instead of
 * the previous class-level one, which used to hold a DB connection open for the whole long-running
 * sidecar call).
 */
@Service
public class RevolutSyncService {

    private static final Logger log = LoggerFactory.getLogger(RevolutSyncService.class);

    private final RevolutPort              revolutPort;
    private final RevolutSessionRepository sessionRepository;
    private final AccountRepository        accountRepository;
    private final TransactionRepository    transactionRepository;
    private final FamilyMemberRepository   familyMemberRepository;
    private final AccountService           accountService;
    private final CategorizationService    categorizationService;
    private final CryptoEncryption         encryption;
    private final ObjectMapper             objectMapper;
    private final TransactionTemplate      txTemplate;
    private final SyncProgressService      progressService;

    /** Phone/passcode used for the pending discovery, held for confirmSync's remember opt-in. */
    private final ConcurrentHashMap<Long, Credentials> pendingCredentials = new ConcurrentHashMap<>();

    public RevolutSyncService(
        RevolutPort revolutPort,
        RevolutSessionRepository sessionRepository,
        AccountRepository accountRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository familyMemberRepository,
        AccountService accountService,
        CategorizationService categorizationService,
        CryptoEncryption encryption,
        ObjectMapper objectMapper,
        TransactionTemplate txTemplate,
        SyncProgressService progressService
    ) {
        this.revolutPort         = revolutPort;
        this.sessionRepository   = sessionRepository;
        this.accountRepository   = accountRepository;
        this.transactionRepository = transactionRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.accountService      = accountService;
        this.categorizationService = categorizationService;
        this.encryption           = encryption;
        this.objectMapper         = objectMapper;
        this.txTemplate           = txTemplate;
        this.progressService      = progressService;
    }

    // ─── Sync ───────────────────────────────────────────────────────────────────

    /**
     * On-demand sync. If {@code phoneNumber}/{@code passcode} are blank, falls back to the
     * member's remembered (decrypted) credentials -- fails clearly if none are stored. Delegates
     * to the sidecar, which reuses a live browser-profile session when possible or performs an
     * automated login (mobile push approval, up to ~5 minutes). When {@code remember} is true the
     * credentials are stored encrypted for unattended daily resync; when false, any previously
     * remembered credentials for this member are forgotten.
     */
    public List<AccountResponse> sync(Long memberId, String phoneNumber, String passcode, boolean remember) {
        return sync(memberId, phoneNumber, passcode, remember, true);
    }

    private List<AccountResponse> sync(
            Long memberId, String phoneNumber, String passcode, boolean remember, boolean allowLogin) {
        boolean explicitCredentials = !isBlank(phoneNumber) && !isBlank(passcode);
        Credentials creds = resolveCredentials(memberId, phoneNumber, passcode);

        List<RevolutAccountData> harvested;
        try {
            harvested = harvest(creds.phone(), creds.passcode(), memberId, allowLogin);
        } catch (SyncException e) {
            String code = e.getCode();
            if (code == null && "SESSION_EXPIRED".equals(e.getMessage())) {
                code = "SESSION_EXPIRED";
            }
            throw new SyncException(friendly(e.getMessage()), e, code);
        }

        // Persist ALL harvested accounts (unattended/scheduler path keeps its auto-import-everything
        // behavior). The DB writes run in a short transaction rather than the previous class-level
        // one, which used to hold a connection open for the whole long-running sidecar call.
        Set<String> all = harvested.stream().map(RevolutAccountData::externalId).collect(Collectors.toSet());
        List<AccountResponse> responses = new ArrayList<>();
        txTemplate.executeWithoutResult(status -> {
            if (explicitCredentials) {
                // Voluntary reconnect: the user explicitly typed phone+passcode (a fresh-login moment,
                // mirroring TradeRepublicSyncService.completeAuth) -- lift tombstones so upsertAccount
                // updates rather than silently skips previously-deleted accounts. Scheduled resyncs
                // (stored credentials) must NOT hit this branch: the user's past delete intent still
                // stands. See docs/lessons/soft-delete-resurrection-guard-voluntary-reconnect.md.
                accountRepository.restoreSoftDeletedRevolutAccounts(memberId);
            }
            responses.addAll(persistSelected(harvested, all, memberId));
            applyPostSyncSessionState(memberId, creds.phone(), creds.passcode(), remember);
        });
        return responses;
    }

    // ─── Manual on-demand flow: discover (background) → confirmSync (persist selection) ──────────

    /**
     * Background discovery for the manual on-demand flow (run on {@code revolutSyncExecutor} by
     * {@code RevolutController}). Harvests via the sidecar with NO DB writes, builds the selection
     * preview, and hands the raw result to {@link SyncProgressService} for {@link #confirmSync}.
     * Reports terminal state (done/error) into the progress registry and never throws -- the sidecar
     * phases stream live via the adapter's poll side-channel while {@link #harvest} blocks.
     */
    public void discover(Long memberId, String phoneNumber, String passcode) {
        try {
            Credentials creds = resolveCredentials(memberId, phoneNumber, passcode);
            List<RevolutAccountData> harvested = harvest(creds.phone(), creds.passcode(), memberId, true);
            pendingCredentials.put(memberId, creds);
            progressService.setDiscovered(memberId, SyncProvider.REVOLUT,
                buildPreview(harvested, memberId), harvested);
            progressService.accountsFound(memberId, SyncProvider.REVOLUT, harvested.size());
            progressService.done(memberId, SyncProvider.REVOLUT);
        } catch (SyncException e) {
            progressService.error(memberId, SyncProvider.REVOLUT, friendly(e.getMessage()));
        } catch (Exception e) {
            log.warn("Revolut discovery failed for member {}: {}", memberId, e.getMessage());
            progressService.error(memberId, SyncProvider.REVOLUT,
                "Failed to sync Revolut accounts. Please try again later.");
        }
    }

    /**
     * Persists the subset of a completed discovery the member selected. Additive: deselecting an
     * account never deletes it here -- deletion only happens explicitly via the trash icon
     * ({@code AccountService.delete}). {@code voluntary} distinguishes an explicit Add-account
     * re-selection (lifts the soft-delete tombstone for each selected account, so re-adding a
     * previously-deleted account resurrects it) from an auto-sync confirm (tombstones are left
     * alone -- {@link #upsertAccount} already skips soft-deleted accounts, so a trash-delete stays
     * respected). {@code remember} (moved here from discovery, since it only matters once something
     * is actually persisted) stores the captured credentials iff true. Fails clearly if the
     * discovery expired (e.g. a backend restart between discovery and confirm) rather than silently
     * no-op-ing.
     */
    public List<AccountResponse> confirmSync(
            Long memberId, List<String> selectedExternalIds, boolean remember, boolean voluntary) {
        List<RevolutAccountData> discovered = progressService.takePendingDiscovery(memberId);
        if (discovered.isEmpty()) {
            throw new SyncException(
                "No Revolut accounts are pending import. Please run a sync again before importing.");
        }
        Set<String> selected = new HashSet<>(selectedExternalIds != null ? selectedExternalIds : List.of());
        Credentials creds = pendingCredentials.remove(memberId);

        List<AccountResponse> responses = new ArrayList<>();
        txTemplate.executeWithoutResult(status -> {
            if (voluntary) {
                // Per-account tombstone lift: only the accounts the member explicitly selected may be
                // resurrected -- a precise version of sync()'s blanket per-provider restore.
                for (RevolutAccountData data : discovered) {
                    if (selected.contains(data.externalId())) {
                        accountRepository.restoreSoftDeletedRevolutAccount(memberId, data.externalId(), data.iban());
                    }
                }
            }
            responses.addAll(persistSelected(discovered, selected, memberId));
            applyPostSyncSessionState(memberId,
                creds != null ? creds.phone() : null,
                creds != null ? creds.passcode() : null,
                remember && creds != null);
        });
        return responses;
    }

    // ─── Shared harvest / persist ───────────────────────────────────────────────

    /** Sidecar call only -- no DB writes. Live phases are streamed by the adapter's poll side-channel. */
    private List<RevolutAccountData> harvest(String phone, String passcode, Long memberId) {
        return revolutPort.sync(phone, passcode, memberId);
    }

    private List<RevolutAccountData> harvest(
            String phone, String passcode, Long memberId, boolean allowLogin) {
        if (allowLogin) {
            return harvest(phone, passcode, memberId);
        }
        return revolutPort.sync(phone, passcode, memberId, false);
    }

    /**
     * Upserts the selected accounts only (parents first, so a pocket's parentAccountId resolves to
     * its already-persisted wallet). Additive: deselecting an account here never deletes it --
     * deletion only happens explicitly via the trash icon ({@code AccountService.delete}). Lifting
     * tombstones for selected accounts is the caller's job (blanket in {@link #sync}, per-account in
     * {@link #confirmSync} when {@code voluntary}). Must run inside a transaction -- callers wrap it
     * in {@link #txTemplate}.
     */
    private List<AccountResponse> persistSelected(List<RevolutAccountData> discovered,
                                                  Set<String> selectedExternalIds, Long memberId) {
        CategorizationService.CategorizationContext ctx = categorizationService.loadContext(memberId);

        List<RevolutAccountData> chosen = discovered.stream()
            .filter(d -> selectedExternalIds.contains(d.externalId()))
            .sorted(Comparator.comparing(a -> a.parentExternalId() != null))
            .toList();

        Map<String, Long> accountIdByExternalId = new HashMap<>();
        List<AccountResponse> responses = new ArrayList<>();
        for (RevolutAccountData data : chosen) {
            Long parentId = data.parentExternalId() != null
                ? accountIdByExternalId.get(data.parentExternalId())
                : null;
            upsertAccount(data, memberId, parentId, ctx).ifPresent(resp -> {
                accountIdByExternalId.put(data.externalId(), resp.id());
                responses.add(resp);
            });
        }

        log.info("Revolut sync complete: {} accounts updated", responses.size());
        return responses;
    }

    // ─── Discovery helpers ──────────────────────────────────────────────────────

    private Credentials resolveCredentials(Long memberId, String phoneNumber, String passcode) {
        if (!isBlank(phoneNumber) && !isBlank(passcode)) {
            return new Credentials(phoneNumber, passcode);
        }
        return loadStoredCredentials(memberId)
            .orElseThrow(() -> new SyncException(
                "No saved Revolut credentials. Please enter your phone number and passcode."));
    }

    private List<DiscoveredRevolutAccount> buildPreview(List<RevolutAccountData> harvested, Long memberId) {
        List<DiscoveredRevolutAccount> preview = new ArrayList<>();
        for (RevolutAccountData d : harvested) {
            boolean imported = findActiveAccount(d, memberId).isPresent();
            int txCount = d.txns() != null ? d.txns().size() : 0;
            preview.add(new DiscoveredRevolutAccount(
                d.externalId(), d.name(), d.type().name(), d.currency(), d.balance(),
                d.parentExternalId(), imported, txCount));
        }
        return preview;
    }

    private Optional<Account> findActiveAccount(RevolutAccountData data, Long memberId) {
        Optional<Account> existing = Optional.empty();
        if (data.iban() != null) {
            existing = accountRepository.findByIbanAndMemberId(data.iban(), memberId);
        }
        if (existing.isEmpty()) {
            existing = accountRepository.findByExternalAccountIdAndMemberId(data.externalId(), memberId);
        }
        return existing;
    }

    /** Maps a sidecar error code to a user-facing sentence; passes through anything already a message. */
    private String friendly(String code) {
        if (code == null) {
            return "Failed to sync Revolut accounts. Please try again later.";
        }
        return switch (code) {
            case "SESSION_EXPIRED" ->
                "Your Revolut session has expired. Please reconnect with your phone number and passcode.";
            case "APPROVAL_TIMEOUT" ->
                "The mobile approval was not confirmed in time. Please try again and approve the " +
                    "push notification on your phone.";
            case "SYNC_IN_PROGRESS" ->
                "A Revolut sync is already running for this account. Please wait for it to finish " +
                    "before starting another.";
            case "BROWSER_LAUNCH_FAILED" ->
                "The Revolut browser service could not start. Please try again after restarting " +
                    "the connector.";
            case "REVOLUT_TIMEOUT" ->
                "The Revolut sync took too long. Please try again later.";
            default -> code;
        };
    }

    // ─── Status / disconnect ─────────────────────────────────────────────────────

    /**
     * {@code connected} is true when the member has completed at least one sidecar sync (the
     * {@link RevolutSession} row, upserted on every successful sync regardless of {@code remember})
     * or already has Revolut accounts from a past sync -- a fallback for rows predating this marker.
     */
    @Transactional(readOnly = true)
    public StatusResponse getStatus(Long memberId) {
        Optional<RevolutSession> session = sessionRepository.findByMemberId(memberId);
        boolean hasRevolutAccounts = !accountRepository.findRevolutWalletsByMemberId(memberId).isEmpty();
        boolean connected = session.isPresent() || hasRevolutAccounts;
        boolean remembered = session.map(RevolutSession::isRememberCredentials).orElse(false);
        Instant lastSyncedAt = session.map(RevolutSession::getLastSyncedAt).orElse(null);
        return new StatusResponse(connected, remembered, lastSyncedAt);
    }

    /** Forgets any remembered credentials. Accounts already synced are left untouched. */
    public void disconnect(Long memberId) {
        sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);
        log.info("Revolut session cleared for member {}", memberId);
    }

    // ─── Scheduler entry point ───────────────────────────────────────────────────

    /**
     * Called by SchedulerService, before the Enable Banking resync (sidecar-primary). No-op
     * unless the member has REMEMBERED credentials -- the sidecar will then either reuse a live
     * browser profile session (free, no approval) or hit APPROVAL_TIMEOUT if that profile died in
     * the meantime. Never retries or loops on failure -- swallows and logs -- so Enable Banking
     * always gets its turn and a dead session is a harmless daily no-op.
     *
     * <p>Passes blank credentials so {@link #sync} takes its own stored-credentials fallback path
     * (which does NOT lift soft-delete tombstones) -- a scheduled resync must never be mistaken
     * for a voluntary reconnect, or it would silently resurrect accounts the user deliberately
     * deleted. See docs/lessons/soft-delete-resurrection-guard-voluntary-reconnect.md.
     */
    public SourceSyncResult resyncReporting(Long memberId) {
        Optional<RevolutSession> session = sessionRepository.findByMemberId(memberId);
        if (session.isEmpty() || !session.get().isRememberCredentials()) {
            return new SourceSyncResult("revolut", SourceSyncResult.Status.SKIPPED_NOT_CONNECTED, "No remembered credentials");
        }

        try {
            sync(memberId, null, null, true, false);
            return new SourceSyncResult("revolut", SourceSyncResult.Status.SYNCED, "");
        } catch (SyncException ex) {
            return SourceSyncResult.fromSyncException("revolut", ex);
        } catch (Exception ex) {
            log.error("Revolut scheduled sync failed unexpectedly for member {}", memberId, ex);
            return new SourceSyncResult("revolut", SourceSyncResult.Status.FAILED, shortMessage(ex.getMessage()));
        }
    }

    public void resyncIfSessionActive(Long memberId) {
        resyncReporting(memberId); // ignore return; no rethrow to keep existing callers/tests
    }

    private String shortMessage(String msg) {
        if (msg == null) return "";
        // keep short, secrets already avoided in getMessage paths
        return msg.length() > 120 ? msg.substring(0, 120) : msg;
    }

    // ─── Credentials (optional, member opt-in) ───────────────────────────────────

    private record Credentials(String phone, String passcode) {}

    private Optional<Credentials> loadStoredCredentials(Long memberId) {
        return sessionRepository.findByMemberId(memberId)
            .filter(RevolutSession::isRememberCredentials)
            .flatMap(s -> decryptCredentials(s.getCredentialsEnc()));
    }

    private Optional<Credentials> decryptCredentials(String credentialsEnc) {
        if (credentialsEnc == null) {
            return Optional.empty();
        }
        try {
            String json = encryption.decrypt(credentialsEnc);
            return Optional.of(objectMapper.readValue(json, Credentials.class));
        } catch (Exception ex) {
            log.warn("Failed to decrypt/parse stored Revolut credentials: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Applies the post-sync session state. The row is ALWAYS upserted with a fresh
     * {@code lastSyncedAt} after a successful sync, regardless of {@code remember} -- this is
     * what lets downstream consumers tell "the on-demand sidecar connector already produced real
     * pockets for this member" apart from "this member merely has some provider='Revolut'
     * accounts" (which can also come from Enable Banking alone). When {@code remember} is true
     * the encrypted credentials are stored/updated for unattended daily resync; when false, any
     * previously-remembered credentials are cleared, but the row itself (and its
     * {@code lastSyncedAt} marker) stays.
     */
    private void applyPostSyncSessionState(Long memberId, String phone, String passcode, boolean remember) {
        RevolutSession session = sessionRepository.findByMemberId(memberId)
            .orElseGet(() -> RevolutSession.builder().member(loadMember(memberId)).build());

        if (remember) {
            session.setCredentialsEnc(encryption.encrypt(serializeCredentials(phone, passcode)));
            session.setRememberCredentials(true);
        } else {
            session.setCredentialsEnc(null);
            session.setRememberCredentials(false);
        }
        session.setLastSyncedAt(Instant.now());
        sessionRepository.save(session);
    }

    private String serializeCredentials(String phone, String passcode) {
        try {
            return objectMapper.writeValueAsString(new Credentials(phone, passcode));
        } catch (JsonProcessingException ex) {
            throw new SyncException("Failed to store Revolut credentials.", ex);
        }
    }

    private FamilyMember loadMember(Long memberId) {
        return familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ─── Upsert ─────────────────────────────────────────────────────────────────

    /**
     * IBAN-first matching + soft-delete guard, mirroring {@code SyncService.upsertAccount}: a
     * wallet carries an IBAN and dedups against any existing Enable Banking account for the same
     * current account; pockets/vaults have no IBAN and match on {@code externalAccountId} alone.
     */
    private Optional<AccountResponse> upsertAccount(
            RevolutAccountData data, Long memberId, Long parentAccountId,
            CategorizationService.CategorizationContext ctx) {
        Optional<Account> existing = findActiveAccount(data, memberId);

        if (existing.isEmpty()) {
            boolean softDeleted = (data.iban() != null
                    && accountRepository.existsSoftDeletedByIbanAndMemberId(data.iban(), memberId))
                || accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), memberId);
            if (softDeleted) {
                log.info("Revolut: skipping resurrection of soft-deleted account externalId={} iban={} member={}",
                    data.externalId(), data.iban(), memberId);
                return Optional.empty();
            }
        }

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(data.balance());
            account.setLastSyncedAt(Instant.now());
            account.setExternalAccountId(data.externalId());
            if (data.iban() != null) {
                account.setIban(data.iban());
            }
            if (parentAccountId != null) {
                account.setParentAccountId(parentAccountId);
            }
        } else {
            FamilyMember member = familyMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            account = Account.builder()
                .member(member)
                .name(data.name())
                .type(data.type())
                .provider("Revolut")
                .currency(data.currency() != null ? data.currency() : "EUR")
                .currentBalance(data.balance())
                .lastSyncedAt(Instant.now())
                .externalAccountId(data.externalId())
                .iban(data.iban())
                .parentAccountId(parentAccountId)
                .isManual(false)
                .color(colorFor(data.type()))
                .build();
        }

        account = accountRepository.save(account);
        accountService.upsertSnapshot(account, data.balance(), LocalDate.now());

        ingestTransactions(account, data.txns(), ctx);

        return Optional.of(accountService.toResponse(account));
    }

    /**
     * Dedup by {@code (account, externalId)} and auto-categorize via the member's rules/brand KB,
     * mirroring {@code SyncService.ingestTransactions}. The context is loaded once per sync and
     * reused across every account to avoid re-querying rules/categories per account.
     */
    private void ingestTransactions(Account account, List<RevolutTxn> txns,
                                     CategorizationService.CategorizationContext ctx) {
        if (txns == null || txns.isEmpty()) {
            return;
        }
        int inserted = 0;
        for (RevolutTxn t : txns) {
            if (t.externalId() != null
                    && transactionRepository.existsByAccountIdAndExternalId(account.getId(), t.externalId())) {
                continue;
            }
            Transaction tx = Transaction.builder()
                .account(account)
                .date(t.date())
                .description(t.description())
                .amount(t.amount())
                .counterparty(t.counterparty())
                .externalId(t.externalId())
                .nativeCurrency(account.getCurrency())
                .isManual(false)
                .build();
            categorizationService.autoCategorize(tx, ctx);
            transactionRepository.save(tx);
            inserted++;
        }
        if (inserted > 0) {
            log.info("Ingested {} new Revolut transactions for account {}", inserted, account.getId());
        }
    }

    private String colorFor(com.picsou.model.AccountType type) {
        return switch (type) {
            case SAVINGS -> "#8b5cf6"; // purple, matches vaults elsewhere
            default      -> "#6366f1"; // indigo, matches wallets/pockets
        };
    }

    // ─── Response records ───────────────────────────────────────────────────────

    public record StatusResponse(boolean connected, boolean remembered, Instant lastSyncedAt) {}
}
