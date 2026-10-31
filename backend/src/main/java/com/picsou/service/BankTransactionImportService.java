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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
    private static final String HASHED_ID_PREFIX = "id:";
    private static final int EXTERNAL_TRANSACTION_ID_LIMIT = 128;
    private static final int TEXT_LIMIT = 255;
    private static final int AMOUNT_SCALE = 8;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Matches {@code transaction.category}. */
    private static final int CATEGORY_MAX = 100;

    /** {@code numeric(20,8)} holds twelve digits before the decimal point. */
    private static final BigDecimal LEDGER_LIMIT = new BigDecimal("1000000000000");

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

        // The sidecar/Budget ledger is EUR-only; SimpleFIN rows (importProvided) keep their native currency.
        List<TransactionData> eurOnly = fetched.stream()
            .filter(data -> data != null
                && DEFAULT_CURRENCY.equalsIgnoreCase(currencyOf(data, account).trim()))
            .toList();
        List<Transaction> toInsert = selectNew(account, windowStart, eurOnly);
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
     * Stores transactions already downloaded with the balances (SimpleFIN returns both
     * in one response). Same dedup as {@link #importFor}. A write failure propagates:
     * the rows are already in hand, so this is not an upstream refusal to ignore.
     *
     * @return how many new transactions were stored
     */
    public int importProvided(Account account, List<TransactionData> fetched) {
        if (account.getId() == null || fetched == null || fetched.isEmpty()) return 0;

        LocalDate windowStart = windowStart(account.getId(), LocalDate.now());
        List<Transaction> toInsert = selectNew(account, windowStart, fetched);
        if (toInsert.isEmpty()) {
            log.debug("No new transactions for account {} ({} provided, all known)", account.getId(), fetched.size());
            return 0;
        }

        transactionRepository.saveAll(toInsert);
        log.info("Imported {} new transactions for account {} ({} provided)",
            toInsert.size(), account.getId(), fetched.size());
        return toInsert.size();
    }

    /**
     * How far back one shared download should reach. SimpleFIN returns every account
     * in a single response, so a shorter per-account window would hide a bank that
     * was linked after the first sync. Dedup drops the rows already stored.
     */
    public LocalDate sharedHistoryStart() {
        return LocalDate.now().minusDays(initialHistoryDays);
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
     * Keeps only fetched entries not already stored for this account. Stable bank references
     * are reserved across the whole batch before content overlap is considered, then exact
     * transaction fingerprints are consumed one-for-one across source types and legacy rows.
     * A fingerprint consists of date, signed amount and the normalized description after its
     * 255-codepoint persistence bound; date and amount alone are not evidence that different labels
     * describe the same purchase. Identical stable-ID/content occurrences are preserved one-for-one:
     * when a page repeats indistinguishable data, the importer cannot tell overlap from separate
     * legitimate operations, so it keeps the source multiplicity rather than silently dropping rows.
     *
     * <p>The comparison window includes the requested start or the oldest entry returned by
     * the provider, whichever reaches further back: {@code date_from} is a hint, and an ASPSP
     * that ignores it must still be compared against every returned historical row.
     */
    private List<Transaction> selectNew(Account account, LocalDate windowStart, List<TransactionData> fetched) {
        if (fetched == null || fetched.isEmpty()) return List.of();
        fetched = fetched.stream().filter(java.util.Objects::nonNull).toList();
        if (fetched.isEmpty()) return List.of();

        LocalDate comparisonFrom = fetched.stream()
            .map(TransactionData::date)
            .filter(java.util.Objects::nonNull)
            .min(LocalDate::compareTo)
            .filter(oldest -> oldest.isBefore(windowStart))
            .orElse(windowStart);

        List<Transaction> existing = transactionRepository
            .findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(account.getId(), comparisonFrom);
        boolean[] consumedExisting = new boolean[existing.size()];
        boolean[] consumedFetched = new boolean[fetched.size()];
        Map<StableTransactionKey, Deque<Integer>> existingByStableIdAndFingerprint = new HashMap<>();
        Map<TransactionFingerprint, Deque<Integer>> existingByFingerprint = new HashMap<>();
        Map<TransactionFingerprint, Deque<Integer>> overlapByFingerprint = new HashMap<>();
        for (int existingIndex = 0; existingIndex < existing.size(); existingIndex++) {
            Transaction stored = existing.get(existingIndex);
            String storedId = cleanExternalId(stored.getExternalTransactionId());
            String legacyId = cleanExternalId(stored.getExternalId());
            Set<String> storedStableIds = new HashSet<>();
            if (isBankStableKey(storedId)) storedStableIds.add(boundedExternalTransactionId(storedId));
            if (!isSidecarKey(storedId) && isBankStableKey(legacyId)) {
                storedStableIds.add(boundedExternalTransactionId(legacyId));
            }
            TransactionFingerprint fingerprint = contentFingerprint(
                stored.getDate(), stored.getAmount(), stored.getDescription());
            if (fingerprint == null) continue;
            for (String stableId : storedStableIds) {
                existingByStableIdAndFingerprint.computeIfAbsent(
                    new StableTransactionKey(stableId, fingerprint), ignored -> new ArrayDeque<>()).addLast(existingIndex);
            }
            existingByFingerprint.computeIfAbsent(fingerprint, ignored -> new ArrayDeque<>()).addLast(existingIndex);
            if (storedStableIds.isEmpty()) {
                overlapByFingerprint.computeIfAbsent(fingerprint, ignored -> new ArrayDeque<>()).addLast(existingIndex);
            }
        }

        // Match stable ID/content pairs one-for-one, independent of fetch order. Repeated pairs
        // cannot be distinguished from repeated-page overlap, so preserve their multiplicity.
        for (int fetchedIndex = 0; fetchedIndex < fetched.size(); fetchedIndex++) {
            TransactionData data = fetched.get(fetchedIndex);
            if (data == null) continue;
            String externalId = cleanExternalId(data.externalId());
            if (externalId == null) continue;
            StableTransactionKey stableKey = new StableTransactionKey(boundedExternalTransactionId(externalId),
                contentFingerprint(data.date(), data.amount(), data.description()));
            int existingIndex = consumeNextAvailable(
                existingByStableIdAndFingerprint.get(stableKey), consumedExisting);
            if (existingIndex >= 0) {
                consumedExisting[existingIndex] = true;
                consumedFetched[fetchedIndex] = true;
            }
        }

        // Cross-source/legacy overlap is a strict multiset: each existing row can consume
        // exactly one incoming row. Stable IDs from this bank are not fingerprint-matched
        // against a different bank ID; sidecar and legacy rows have no shared stable ID.
        for (int fetchedIndex = 0; fetchedIndex < fetched.size(); fetchedIndex++) {
            if (consumedFetched[fetchedIndex]) continue;
            TransactionData data = fetched.get(fetchedIndex);
            if (data == null) continue;
            TransactionFingerprint fingerprint = contentFingerprint(data.date(), data.amount(), data.description());
            if (fingerprint == null) continue;
            String externalId = cleanExternalId(data.externalId());
            Deque<Integer> candidates = externalId == null
                ? existingByFingerprint.get(fingerprint)
                : overlapByFingerprint.get(fingerprint);
            int existingIndex = consumeNextAvailable(candidates, consumedExisting);
            if (existingIndex >= 0) {
                consumedExisting[existingIndex] = true;
                consumedFetched[fetchedIndex] = true;
            }
        }

        List<Transaction> toInsert = new ArrayList<>();
        for (int fetchedIndex = 0; fetchedIndex < fetched.size(); fetchedIndex++) {
            if (consumedFetched[fetchedIndex]) continue;
            TransactionData data = fetched.get(fetchedIndex);
            if (data == null) continue;
            if (!fitsLedgerAmount(data.amount())) {
                log.warn("Skipping a transaction on account {} — amount does not fit the ledger", account.getId());
                continue;
            }
            String key = dedupKey(data);
            toInsert.add(toEntity(account, data, key));
        }
        return toInsert;
    }

    private static int consumeNextAvailable(Deque<Integer> candidateIndices, boolean[] consumed) {
        if (candidateIndices == null) return -1;
        while (!candidateIndices.isEmpty()) {
            int candidate = candidateIndices.removeFirst();
            if (!consumed[candidate]) return candidate;
        }
        return -1;
    }

    private static boolean isBankStableKey(String key) {
        return key != null && !key.startsWith(FINGERPRINT_PREFIX) && !isSidecarKey(key);
    }

    private static boolean isSidecarKey(String key) {
        return key != null && (key.startsWith("bourso:") || key.startsWith("revolut:"));
    }

    private static String cleanExternalId(String externalId) {
        return externalId == null || externalId.isBlank() ? null : externalId.trim();
    }

    private static String boundedExternalTransactionId(String externalId) {
        if (externalId.codePointCount(0, externalId.length()) <= EXTERNAL_TRANSACTION_ID_LIMIT) return externalId;
        return HASHED_ID_PREFIX + sha256Hex(externalId);
    }

    private static BigDecimal normalizeAmount(BigDecimal amount) {
        return amount == null ? null : amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    private static String boundedText(String text) {
        if (text == null || text.codePointCount(0, text.length()) <= TEXT_LIMIT) return text;
        return text.substring(0, text.offsetByCodePoints(0, TEXT_LIMIT));
    }

    private static TransactionFingerprint contentFingerprint(LocalDate date, BigDecimal amount, String description) {
        if (date == null || amount == null) return null;
        String canonicalAmount = normalizeAmount(amount).stripTrailingZeros().toPlainString();
        String canonicalDescription = WHITESPACE.matcher(boundedText(description == null ? "" : description).trim())
            .replaceAll(" ").toLowerCase(Locale.ROOT);
        return new TransactionFingerprint(date, canonicalAmount, canonicalDescription);
    }

    private record TransactionFingerprint(LocalDate date, String amount, String description) {}

    private record StableTransactionKey(String externalId, TransactionFingerprint fingerprint) {}

    /**
     * The key a fetched entry is recognised by: the provider's own reference when it
     * sends one, otherwise a fingerprint of the fields a user would call identical.
     */
    static String dedupKey(TransactionData data) {
        String externalId = cleanExternalId(data.externalId());
        if (externalId != null) return boundedExternalTransactionId(externalId);
        return fingerprint(data.date(), data.amount(), data.description());
    }

    /** True when {@code value} can be stored in a {@code numeric(20,8)} money column. */
    static boolean fitsLedgerAmount(BigDecimal value) {
        return value != null && value.abs().compareTo(LEDGER_LIMIT) < 0;
    }

    /**
     * Hash of date + amount + description. Hashed rather than concatenated so the key
     * fits {@code transaction.external_transaction_id} (VARCHAR(255)) whatever the
     * description's length, and stays a fixed, index-friendly width.
     *
     * <p>The amount is rounded to the column's 8 decimals, then {@code stripTrailingZeros}
     * normalizes the scale: the provider sends {@code "12.34"} while the column round-trips
     * it as {@code 12.34000000}, and the two must hash alike or every sync would re-import
     * the same rows.
     */
    private static String fingerprint(LocalDate date, BigDecimal amount, String description) {
        String canonical = date + "|"
            + (amount == null ? "" : normalizeAmount(amount).stripTrailingZeros().toPlainString()) + "|"
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
            .description(boundedText(data.description() != null ? data.description() : ""))
            .amount(normalizeAmount(data.amount()))
            .category(clip(data.category(), CATEGORY_MAX))
            .nativeCurrency(currencyOf(data, account))
            .externalTransactionId(dedupKey)
            .isManual(false)
            .build();
    }

    /** Stops on a character boundary so a cut value is still valid text. */
    static String clip(String value, int max) {
        if (value == null || value.length() <= max) return value;
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, Math.max(end, 0));
    }

    private static String currencyOf(TransactionData data, Account account) {
        if (data.currency() != null && !data.currency().isBlank()) return data.currency();
        if (account.getCurrency() != null && !account.getCurrency().isBlank()) return account.getCurrency();
        return DEFAULT_CURRENCY;
    }
}
