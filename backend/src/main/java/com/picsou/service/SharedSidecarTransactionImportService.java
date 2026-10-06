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
    private static final int KEY_LIMIT = 128;
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

        List<Transaction> existing = transactionRepository
            .findByAccountIdAndIsManualFalse(account.getId());
        Set<String> knownKeys = new HashSet<>();
        List<Transaction> legacyRows = new ArrayList<>();
        for (Transaction row : existing) {
            addIfPresent(knownKeys, row.getExternalId());
            addIfPresent(knownKeys, row.getExternalTransactionId());
            if (!isSidecarKey(row.getExternalTransactionId())) legacyRows.add(row);
        }

        Set<String> batchKeys = new HashSet<>();
        Set<Integer> consumedLegacy = new HashSet<>();
        int inserted = 0;
        for (int index = 0; index < transactions.size(); index++) {
            SidecarTransaction data = transactions.get(index);
            if (data == null || data.date() == null || data.amount() == null) continue;
            String sourceId = cleanId(data.externalId());
            String sourceKey = sourceId != null ? sourceId : syntheticKey(data, transactions, index);
            String externalId = sourceId != null && sourceId.length() <= 255 ? sourceId : sourceKey;
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
                .description(data.description() == null ? "" : data.description())
                .amount(data.amount())
                .counterparty(data.counterparty())
                .externalId(externalId)
                .externalTransactionId(storedKey)
                .nativeCurrency(account.getCurrency() == null ? "EUR" : account.getCurrency())
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
                && old.getAmount() != null && old.getAmount().compareTo(incoming.amount()) == 0
                && normalizeDescription(old.getDescription()).equals(description)) return i;
        }
        return -1;
    }

    private static boolean isSidecarKey(String key) {
        return key != null && (key.startsWith("bourso:") || key.startsWith("revolut:"));
    }

    private static String normalizeDescription(String description) {
        return WHITESPACE.matcher(description == null ? "" : description.trim())
            .replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    private static String cleanId(String externalId) {
        return externalId == null || externalId.isBlank() ? null : externalId;
    }

    private static void addIfPresent(Set<String> target, String key) {
        if (key != null && !key.isBlank()) target.add(key);
    }

    private static String syntheticKey(SidecarTransaction transaction, List<SidecarTransaction> batch, int index) {
        String fingerprint = sha256(transaction.date() + "|" + transaction.amount().stripTrailingZeros().toPlainString()
            + "|" + normalizeDescription(transaction.description()));
        int occurrence = 0;
        for (int i = 0; i < index; i++) {
            SidecarTransaction row = batch.get(i);
            if (row != null && cleanId(row.externalId()) == null
                && row.date() != null && row.date().equals(transaction.date())
                && row.amount() != null && row.amount().compareTo(transaction.amount()) == 0
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
