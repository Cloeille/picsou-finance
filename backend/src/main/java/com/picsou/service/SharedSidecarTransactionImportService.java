package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.Transaction;
import com.picsou.port.SidecarTransaction;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared, append-only transaction ingestion for bank sidecars. */
@Service
public class SharedSidecarTransactionImportService {
    private static final String TRANSFER_CATEGORY = "virement-interne";
    private static final String EUR = "EUR";
    private static final int KEY_LIMIT = 128;
    private static final int TEXT_LIMIT = 255;
    private static final int AMOUNT_SCALE = 8;
    private static final int EXTERNAL_ID_LIMIT = 255;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final TransactionRepository transactionRepository;
    private final CategorizationService categorizationService;

    public SharedSidecarTransactionImportService(TransactionRepository transactionRepository,
                                                 CategorizationService categorizationService) {
        this.transactionRepository = transactionRepository;
        this.categorizationService = categorizationService;
    }

    /**
     * Imports a snapshot for one already member-scoped account. Fingerprints are used only against
     * pre-existing legacy rows; freshly fetched rows are distinguished by their provider IDs.
     */
    public int importFor(Account account, List<SidecarTransaction> transactions,
                         CategorizationService.CategorizationContext context, String providerKeyPrefix) {
        if (transactions == null || transactions.isEmpty()) return 0;
        if (!EUR.equals(currencyOf(null, account))) return 0;

        Set<String> snapshotIds = new HashSet<>();
        for (SidecarTransaction data : transactions) {
            if (data == null) continue;
            String sourceId = cleanId(data.externalId());
            addIfPresent(snapshotIds, sourceId);
            if (sourceId != null) addIfPresent(snapshotIds, boundedExternalId(sourceId));
        }

        List<Transaction> existing = transactionRepository
            .findByAccountIdAndIsManualFalse(account.getId());
        Set<String> knownKeys = new HashSet<>();
        List<Transaction> legacyRows = new ArrayList<>();
        for (Transaction row : existing) {
            addIfPresent(knownKeys, row.getExternalId());
            addIfPresent(knownKeys, row.getExternalTransactionId());
            if (!isSidecarKey(row.getExternalTransactionId())) legacyRows.add(row);
        }

        Set<Integer> consumedLegacy = new HashSet<>();
        for (int i = 0; i < legacyRows.size(); i++) {
            Transaction legacy = legacyRows.get(i);
            if (snapshotIds.contains(legacy.getExternalId())
                || snapshotIds.contains(legacy.getExternalTransactionId())) {
                consumedLegacy.add(i);
            }
        }

        Set<String> batchKeys = new HashSet<>();
        int inserted = 0;
        for (int index = 0; index < transactions.size(); index++) {
            SidecarTransaction data = transactions.get(index);
            if (data == null || data.date() == null || data.amount() == null) continue;
            // The Budget ledger is EUR-only. A Revolut exchange lists its foreign leg on the EUR
            // pocket; booking it as EUR inflated every flow view by up to 10^8 (#251).
            String currency = currencyOf(data, account);
            if (!EUR.equals(currency)) continue;
            String sourceId = cleanId(data.externalId());
            String sourceKey = sourceId != null ? sourceId : syntheticKey(data, transactions, index);
            String externalId = boundedExternalId(sourceId == null ? sourceKey : sourceId);
            String storedKey = providerKeyPrefix + ":" + sourceKey;
            if (storedKey.length() > KEY_LIMIT) storedKey = providerKeyPrefix + ":" + sha256(sourceKey);

            if (!batchKeys.add(storedKey) || knownKeys.contains(externalId) || knownKeys.contains(storedKey)) continue;
            int overlap = findLegacyOverlap(legacyRows, consumedLegacy, data);
            if (overlap >= 0) {
                consumedLegacy.add(overlap);
                // The historical Enable Banking reference remains untouched. Keep its category,
                // including a user's manual choice, rather than editing an existing ledger row.
                continue;
            }

            Transaction transaction = Transaction.builder()
                .account(account)
                .date(data.date())
                .description(boundedText(data.description() == null ? "" : data.description()))
                .amount(normalizeAmount(data.amount()))
                .counterparty(boundedText(data.counterparty()))
                .externalId(externalId)
                .externalTransactionId(storedKey)
                .nativeCurrency(currency)
                .isManual(false)
                .build();
            categorizationService.autoCategorize(transaction, context);
            if ("revolut".equals(providerKeyPrefix) && "TRANSFER".equalsIgnoreCase(data.kind())) {
                Category transfer = context.categoriesBySlug().get(TRANSFER_CATEGORY);
                if (transfer != null && transfer.getKind() == CategoryKind.TRANSFER) {
                    transaction.setCategoryRef(transfer);
                }
            }
            transactionRepository.save(transaction);
            knownKeys.add(externalId);
            knownKeys.add(storedKey);
            inserted++;
        }
        return inserted;
    }

    private static int findLegacyOverlap(List<Transaction> legacyRows, Set<Integer> consumed,
                                         SidecarTransaction incoming) {
        String description = normalizeDescription(incoming.description());
        for (int i = 0; i < legacyRows.size(); i++) {
            if (consumed.contains(i)) continue;
            Transaction old = legacyRows.get(i);
            if (old.getDate() != null && old.getDate().equals(incoming.date())
                && old.getAmount() != null
                && normalizeAmount(old.getAmount()).compareTo(normalizeAmount(incoming.amount())) == 0
                && normalizeDescription(old.getDescription()).equals(description)) return i;
        }
        return -1;
    }

    /** The leg's own currency when the sidecar reports one, else the account's, else EUR. */
    private static String currencyOf(SidecarTransaction data, Account account) {
        String currency = data == null || data.currency() == null || data.currency().isBlank()
            ? account.getCurrency() : data.currency();
        return currency == null || currency.isBlank() ? EUR : currency.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isSidecarKey(String key) {
        return key != null && (key.startsWith("bourso:") || key.startsWith("revolut:"));
    }

    private static String normalizeDescription(String description) {
        String bounded = boundedText(description == null ? "" : description);
        return WHITESPACE.matcher(bounded.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    private static BigDecimal normalizeAmount(BigDecimal amount) {
        return amount == null ? null : amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    private static String cleanId(String externalId) {
        return externalId == null || externalId.isBlank() ? null : externalId;
    }

    private static String boundedExternalId(String externalId) {
        return externalId.codePointCount(0, externalId.length()) <= EXTERNAL_ID_LIMIT
            ? externalId
            : sha256(externalId);
    }

    private static String boundedText(String value) {
        if (value == null || value.codePointCount(0, value.length()) <= TEXT_LIMIT) return value;
        return value.substring(0, value.offsetByCodePoints(0, TEXT_LIMIT));
    }

    private static void addIfPresent(Set<String> target, String key) {
        if (key != null && !key.isBlank()) target.add(key);
    }

    private static String syntheticKey(SidecarTransaction transaction, List<SidecarTransaction> batch, int index) {
        String fingerprint = sha256(transaction.date() + "|" + normalizeAmount(transaction.amount()).stripTrailingZeros().toPlainString()
            + "|" + normalizeDescription(transaction.description()));
        int occurrence = 0;
        for (int i = 0; i < index; i++) {
            SidecarTransaction row = batch.get(i);
            if (row != null && cleanId(row.externalId()) == null
                && row.date() != null && row.date().equals(transaction.date())
                && row.amount() != null
                && normalizeAmount(row.amount()).compareTo(normalizeAmount(transaction.amount())) == 0
                && normalizeDescription(row.description()).equals(normalizeDescription(transaction.description()))) {
                occurrence++;
            }
        }
        return "missing:" + fingerprint + ":" + occurrence;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
