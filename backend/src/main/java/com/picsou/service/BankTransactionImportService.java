package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.Transaction;
import com.picsou.port.BankConnectorPort;
import com.picsou.port.BankConnectorPort.TransactionData;
import com.picsou.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Imports the transactions of one bank-synced account from the active
 * {@link BankConnectorPort}.
 *
 * <p>Split out of {@code SyncService} because the two have different failure
 * semantics: a balance that cannot be fetched means the connection is broken and
 * must go back to FAILED, whereas transactions are supplementary — several ASPSPs
 * serve balances happily and reject {@code /transactions} outright. A transaction
 * failure therefore only logs (see {@link #importFor}); it never demotes a working
 * requisition nor rolls back the balances that did arrive.
 *
 * <p>The ledger stays read-only input for synced accounts: balances and holdings
 * are still whatever the provider reports, exactly as before. These rows only feed
 * the account's transaction list.
 */
@Service
public class BankTransactionImportService {

    private static final Logger log = LoggerFactory.getLogger(BankTransactionImportService.class);

    /**
     * How far back the very first import of an account reaches. PSD2 grants ~90 days
     * of history without a fresh SCA, so asking for more mostly buys HTTP 429s and
     * partial answers from ASPSPs that cap the window themselves.
     */
    private static final int DEFAULT_INITIAL_HISTORY_DAYS = 90;

    /**
     * How far before the newest stored entry a follow-up sync re-reads. Banks book
     * card payments and direct debits several days after the fact, so a window that
     * started at the newest stored date would skip every entry that landed behind it.
     * Re-read rows are dropped by the dedup below, so the only cost is bandwidth.
     */
    private static final int OVERLAP_DAYS = 7;

    /** Fallback when neither the provider nor the account names a currency. */
    private static final String DEFAULT_CURRENCY = "EUR";

    /** Marks a key as a locally computed fingerprint rather than a provider reference. */
    private static final String FINGERPRINT_PREFIX = "fp:";

    private final BankConnectorPort bankConnector;
    private final TransactionRepository transactionRepository;
    private final int initialHistoryDays;

    public BankTransactionImportService(
        BankConnectorPort bankConnector,
        TransactionRepository transactionRepository,
        @Value("${app.bank-sync.initial-transaction-history-days:" + DEFAULT_INITIAL_HISTORY_DAYS + "}")
        int initialHistoryDays
    ) {
        this.bankConnector = bankConnector;
        this.transactionRepository = transactionRepository;
        this.initialHistoryDays = initialHistoryDays;
    }

    /**
     * Fetches and stores the transactions this account does not have yet.
     *
     * <p>Never throws: any provider failure is logged and reported as zero imported
     * rows. Balances have already been written by the caller at this point, and an
     * ASPSP that refuses {@code /transactions} must not cost the user the sync they
     * asked for. The failure is logged at WARN with the account and the provider
     * message — the original bug was precisely that nothing appeared in the logs.
     *
     * @return how many new transactions were stored
     */
    public int importFor(Account account, String sessionId) {
        if (account.getExternalAccountId() == null || sessionId == null) return 0;

        LocalDate windowStart = windowStart(account.getId(), LocalDate.now());

        List<TransactionData> fetched;
        try {
            fetched = bankConnector.fetchTransactions(sessionId, account.getExternalAccountId(), windowStart);
        } catch (RuntimeException ex) {
            log.warn("Could not fetch transactions for account {} ({}) since {}: {}",
                account.getId(), account.getName(), windowStart, ex.getMessage());
            return 0;
        }

        if (fetched.isEmpty()) return 0;

        List<Transaction> toInsert = selectNew(account, windowStart, fetched);
        if (toInsert.isEmpty()) {
            log.debug("No new transactions for account {} ({} fetched, all known)", account.getId(), fetched.size());
            return 0;
        }

        transactionRepository.saveAll(toInsert);
        log.info("Imported {} new transactions for account {} ({} fetched since {})",
            toInsert.size(), account.getId(), fetched.size(), windowStart);
        return toInsert.size();
    }

    /**
     * Start of the window to request. First import of an account: {@link #initialHistoryDays}
     * back. Afterwards: {@link #OVERLAP_DAYS} before the newest entry already stored, so
     * late-booked entries are still picked up without re-downloading the whole history on
     * every scheduled sync.
     *
     * <p>Package-private for the test that pins both branches without a provider.
     */
    LocalDate windowStart(Long accountId, LocalDate today) {
        LocalDate latest = transactionRepository.findLatestSyncedDateByAccountId(accountId);
        if (latest == null) return today.minusDays(initialHistoryDays);

        LocalDate incremental = latest.minusDays(OVERLAP_DAYS);
        // A stored entry dated in the future (a bank's value-dated entry, a clock skew)
        // must not push the window past today and stall the import at that date forever.
        LocalDate capped = today.minusDays(initialHistoryDays);
        return incremental.isAfter(today) ? capped : incremental;
    }

    /**
     * Keeps only the fetched entries not already stored for this account, comparing on
     * {@link #dedupKey}.
     *
     * <p>The comparison set covers the requested window <em>or</em> the oldest entry the
     * provider actually returned, whichever reaches further back: {@code date_from} is a
     * hint, and an ASPSP that ignores it and replays its whole history would otherwise be
     * compared against a set that cannot contain those older rows — re-importing every one
     * of them on every sync.
     *
     * <p>Duplicates <em>within</em> the fetched batch are filtered too: a bank that repeats
     * an entry across two continuation pages would otherwise store it twice, since neither
     * copy is in the database yet.
     */
    private List<Transaction> selectNew(Account account, LocalDate windowStart, List<TransactionData> fetched) {
        LocalDate comparisonFrom = fetched.stream()
            .map(TransactionData::date)
            .filter(java.util.Objects::nonNull)
            .min(LocalDate::compareTo)
            .filter(oldest -> oldest.isBefore(windowStart))
            .orElse(windowStart);

        Set<String> known = new HashSet<>();
        for (Transaction existing : transactionRepository
            .findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(account.getId(), comparisonFrom)) {
            known.add(keyOf(existing));
        }

        List<Transaction> toInsert = new ArrayList<>();
        for (TransactionData data : fetched) {
            String key = dedupKey(data);
            if (!known.add(key)) continue;
            toInsert.add(toEntity(account, data, key));
        }
        return toInsert;
    }

    /**
     * The key a fetched entry is recognised by: the provider's own reference when it
     * sends one, otherwise a fingerprint of the fields a user would call identical.
     */
    static String dedupKey(TransactionData data) {
        if (data.externalId() != null && !data.externalId().isBlank()) return data.externalId().trim();
        return fingerprint(data.date(), data.amount(), data.description());
    }

    /**
     * The same key for an already-stored row. Rows written before this feature shipped
     * (Finary imports) carry no external id, so they are matched on their fingerprint —
     * otherwise a bank connector added to such an account would re-import history the
     * user is already looking at.
     */
    private static String keyOf(Transaction transaction) {
        if (transaction.getExternalTransactionId() != null && !transaction.getExternalTransactionId().isBlank()) {
            return transaction.getExternalTransactionId();
        }
        return fingerprint(transaction.getDate(), transaction.getAmount(), transaction.getDescription());
    }

    /**
     * Hash of date + amount + description. Hashed rather than concatenated so the key
     * fits {@code transaction.external_transaction_id} (VARCHAR(128)) whatever the
     * description's length, and stays a fixed, index-friendly width.
     *
     * <p>{@code stripTrailingZeros} normalizes the scale: the provider sends
     * {@code "12.34"} while the column round-trips it as {@code 12.34000000}, and the
     * two must hash alike or every sync would re-import the same rows.
     */
    private static String fingerprint(LocalDate date, BigDecimal amount, String description) {
        String canonical = date + "|"
            + (amount == null ? "" : amount.stripTrailingZeros().toPlainString()) + "|"
            + (description == null ? "" : description.trim());
        return FINGERPRINT_PREFIX + sha256Hex(canonical);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JDK; unreachable on any supported runtime.
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /**
     * {@code txType} is deliberately left null, like every other synced row (Finary's
     * importer does the same): it drives manual-account holdings and the instrument
     * label in the UI, neither of which applies to a provider-owned cash entry.
     */
    private Transaction toEntity(Account account, TransactionData data, String dedupKey) {
        return Transaction.builder()
            .account(account)
            .date(data.date())
            .description(data.description() != null ? data.description() : "")
            .amount(data.amount())
            .category(data.category())
            .nativeCurrency(currencyOf(data, account))
            .externalTransactionId(dedupKey)
            .isManual(false)
            .build();
    }

    private static String currencyOf(TransactionData data, Account account) {
        if (data.currency() != null && !data.currency().isBlank()) return data.currency();
        if (account.getCurrency() != null && !account.getCurrency().isBlank()) return account.getCurrency();
        return DEFAULT_CURRENCY;
    }
}
