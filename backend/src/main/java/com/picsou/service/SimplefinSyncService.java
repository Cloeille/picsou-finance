package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.SimplefinConnectionStatusResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.SimplefinConnection;
import com.picsou.port.BankConnectorPort.TransactionData;
import com.picsou.port.SimplefinPort;
import com.picsou.port.SimplefinPort.SimplefinAccount;
import com.picsou.port.SimplefinPort.SimplefinAccountSet;
import com.picsou.port.SimplefinPort.SimplefinTransaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SimplefinConnectionRepository;
import com.picsou.service.sync.SourceSyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
    private final FamilyMemberRepository familyMemberRepository;
    private final AccountService accountService;
    private final BankTransactionImportService transactionImportService;
    private final CryptoEncryption encryption;
    private final SimplefinStatusWriter statusWriter;

    /** Claims the token and stores the access URL. A later sync failure does not drop it. */
    public void connect(String setupToken, Long memberId) {
        if (setupToken == null || setupToken.isBlank()) {
            throw new SyncException("A SimpleFIN setup token is required.");
        }
        String accessUrl = simplefinPort.claim(setupToken.trim());
        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

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
            log.error("SimpleFIN: cannot decrypt stored access for connection {}", stored.getId());
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
            log.warn("SimpleFIN auto-sync failed for member {}: {}", memberId, ex.getMessage());
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
                memberId, String.join("; ", set.errors()));
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

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(data.balance());
            account.setCurrency(data.currency().toUpperCase(Locale.ROOT));
            account.setLastSyncedAt(Instant.now());
        } else {
            account = Account.builder()
                .member(member)
                .name(accountName(data.connectionName(), data.name()))
                .type(AccountType.CHECKING)
                .provider(PROVIDER)
                .currency(data.currency().toUpperCase(Locale.ROOT))
                .currentBalance(data.balance())
                .lastSyncedAt(Instant.now())
                .externalAccountId(data.externalId())
                .isManual(false)
                .color(COLOR)
                .build();
        }
        account = accountRepository.save(account);
        accountService.upsertSnapshotFromNative(account, data.balance(), LocalDate.now());
        transactionImportService.importProvided(account, toTransactions(data, account.getCurrency()));
        return Optional.of(accountService.toResponse(account));
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
        return clip(combined, MAX_NAME_LEN);
    }

    /** Stops on a character boundary so a name cut at 100 is still valid text. */
    private static String clip(String value, int max) {
        if (value.length() <= max) return value;
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }

    static boolean isIsoCurrency(String code) {
        if (code == null || code.length() != 3) return false;
        try {
            Currency.getInstance(code.toUpperCase(Locale.ROOT));
            return true;
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
