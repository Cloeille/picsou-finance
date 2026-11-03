package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.WiseConnectionStatusResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.WiseConnection;
import com.picsou.port.WisePort;
import com.picsou.port.WisePort.WiseJar;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.WiseConnectionRepository;
import com.picsou.service.sync.SourceSyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Imports Wise jars as savings accounts from a member's personal API token. One token per
 * member. Wise current balances keep coming from Enable Banking.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class WiseSyncService {

    /** Stamped on every imported jar so Sync All and deletion can find the connection. */
    public static final String PROVIDER = "Wise Jars";
    /** Source name in {@link MemberSyncService} and the MCP sync tools. */
    public static final String SOURCE = "wise";
    /** Prefix of every jar's external id; account deletion recognises the connection by it. */
    public static final String EXTERNAL_ID_PREFIX = "wise_jar_";

    public static final String STATUS_CONNECTED = "CONNECTED";
    public static final String STATUS_ERROR = "ERROR";
    /** Wise refused the stored token; the member must paste a new one. */
    public static final String STATUS_REAUTH_REQUIRED = "REAUTH_REQUIRED";

    private static final String TOKEN_REFUSED_CODE = "SESSION_EXPIRED";

    private static final int MAX_NAME_LEN = 100;
    private static final String COLOR = "#9fe870";

    private final WisePort wisePort;
    private final WiseConnectionRepository connectionRepository;
    private final AccountRepository accountRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final AccountService accountService;
    private final CryptoEncryption encryption;
    private final WiseStatusWriter statusWriter;

    /**
     * Reads the jars once to prove the token works, then stores it and imports them.
     * A refused token stores nothing.
     */
    public List<AccountResponse> connect(String apiToken, Long memberId) {
        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
        String token = apiToken.trim();
        List<WiseJar> jars = wisePort.fetchJars(token);

        WiseConnection connection = connectionRepository.findByMemberId(memberId)
            .orElseGet(() -> WiseConnection.builder().member(member).build());
        connection.setApiToken(encryption.encrypt(token));
        log.info("Wise token stored for member {}", memberId);
        return importJars(jars, connection, member);
    }

    @Transactional(readOnly = true)
    public WiseConnectionStatusResponse getConnectionStatus(Long memberId) {
        Optional<WiseConnection> connection = connectionRepository.findByMemberId(memberId);
        if (connection.isEmpty()) {
            return new WiseConnectionStatusResponse(false, null, null, null, null);
        }
        WiseConnection stored = connection.get();
        String masked;
        try {
            masked = mask(encryption.decrypt(stored.getApiToken()));
        } catch (RuntimeException ex) {
            log.error("Wise: cannot decrypt stored token for connection {}", stored.getId(), ex);
            return new WiseConnectionStatusResponse(true, stored.getId(), STATUS_ERROR, stored.getLastSyncedAt(), "••••");
        }
        return new WiseConnectionStatusResponse(
            true, stored.getId(), stored.getStatus(), stored.getLastSyncedAt(), masked);
    }

    public boolean deleteConnection(Long memberId) {
        Optional<WiseConnection> connection = connectionRepository.findByMemberId(memberId);
        connection.ifPresent(connectionRepository::delete);
        log.info("Wise connection cleared for member {}", memberId);
        return connection.isPresent();
    }

    public List<AccountResponse> sync(Long memberId) {
        WiseConnection connection = connectionRepository.findByMemberId(memberId)
            .orElseThrow(() -> new SyncException("No Wise connection. Connect with an API token first."));
        return syncWithConnection(connection, memberId);
    }

    /**
     * Scheduler and Sync All entry point. Reports failures instead of throwing; the caller still
     * wraps this because a rollback-only transaction can escape as {@code UnexpectedRollbackException}.
     */
    public SourceSyncResult resyncReporting(Long memberId) {
        try {
            Optional<WiseConnection> connection = connectionRepository.findByMemberId(memberId);
            if (connection.isEmpty()) {
                return new SourceSyncResult(SOURCE, SourceSyncResult.Status.SKIPPED_NOT_CONNECTED, "No connection");
            }
            syncWithConnection(connection.get(), memberId);
            return new SourceSyncResult(SOURCE, SourceSyncResult.Status.SYNCED, "");
        } catch (SyncException ex) {
            log.warn("Wise scheduled sync failed for member {} (code={})", memberId, ex.getCode(), ex);
            return SourceSyncResult.fromSyncException(SOURCE, ex);
        } catch (RuntimeException ex) {
            log.error("Wise auto-sync hit an unexpected error for member {}", memberId, ex);
            return new SourceSyncResult(SOURCE, SourceSyncResult.Status.FAILED, "Unexpected sync error");
        }
    }

    private List<AccountResponse> syncWithConnection(WiseConnection connection, Long memberId) {
        try {
            String token;
            try {
                token = encryption.decrypt(connection.getApiToken());
            } catch (RuntimeException ex) {
                throw new SyncException(
                    "Could not decrypt the stored Wise token. The encryption key may have changed. "
                        + "Connect again with a new token.", ex);
            }
            List<WiseJar> jars = wisePort.fetchJars(token);
            FamilyMember member = familyMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            return importJars(jars, connection, member);
        } catch (RuntimeException ex) {
            boolean tokenRefused = ex instanceof SyncException sync && TOKEN_REFUSED_CODE.equals(sync.getCode());
            log.error("Wise sync failed for connection {} (token refused: {})", connection.getId(), tokenRefused, ex);
            if (tokenRefused) statusWriter.markReauthRequired(connection.getId());
            else statusWriter.markError(connection.getId());
            if (ex instanceof SyncException sync) throw sync;
            throw new SyncException("Wise sync failed. Try again in a moment.", ex);
        }
    }

    private List<AccountResponse> importJars(List<WiseJar> jars, WiseConnection connection, FamilyMember member) {
        List<AccountResponse> responses = new ArrayList<>();
        for (WiseJar jar : jars) {
            if (!SimplefinSyncService.isIsoCurrency(jar.currency())) {
                log.info("Wise: skipping jar {} — currency is not ISO 4217", jar.balanceId());
                continue;
            }
            if (!BankTransactionImportService.fitsLedgerAmount(jar.totalWorth())) {
                log.info("Wise: skipping jar {} — balance does not fit the ledger", jar.balanceId());
                continue;
            }
            upsertJar(jar, member).ifPresent(responses::add);
        }
        connection.setStatus(STATUS_CONNECTED);
        connection.setLastSyncedAt(Instant.now());
        connectionRepository.save(connection);
        log.info("Wise sync complete for member {}: {} jar(s) updated", member.getId(), responses.size());
        return responses;
    }

    private Optional<AccountResponse> upsertJar(WiseJar jar, FamilyMember member) {
        String externalId = EXTERNAL_ID_PREFIX + jar.balanceId();
        Optional<Account> existing = accountRepository.findByExternalAccountIdAndMemberId(externalId, member.getId());
        if (existing.isEmpty()
            && accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(externalId, member.getId())) {
            log.info("Wise: skipping resurrection of soft-deleted jar {} for member {}", externalId, member.getId());
            return Optional.empty();
        }

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(jar.totalWorth());
            account.setCurrency(jar.currency());
            account.setLastSyncedAt(Instant.now());
        } else {
            account = Account.builder()
                .member(member)
                .name(accountName(jar))
                .type(AccountType.SAVINGS)
                .provider(PROVIDER)
                .currency(jar.currency())
                .currentBalance(jar.totalWorth())
                .lastSyncedAt(Instant.now())
                .externalAccountId(externalId)
                .isManual(false)
                .color(COLOR)
                .build();
        }
        account = accountRepository.save(account);
        accountService.upsertSnapshotFromNative(account, jar.totalWorth(), LocalDate.now());
        return Optional.of(accountService.toResponse(account));
    }

    /** The jar's own name: account cards already show the {@link #PROVIDER} beneath it. */
    static String accountName(WiseJar jar) {
        String name = jar.name() == null || jar.name().isBlank() ? "Jar " + jar.currency() : jar.name().trim();
        return BankTransactionImportService.clip(jar.business() ? name + " (Business)" : name, MAX_NAME_LEN);
    }

    static String mask(String token) {
        if (token == null || token.length() <= 8) return "••••";
        return "••••" + token.substring(token.length() - 4);
    }
}
