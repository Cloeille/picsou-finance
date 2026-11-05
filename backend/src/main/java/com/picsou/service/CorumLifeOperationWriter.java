package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.port.CorumPort;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes the operations of one CORUM Life contract as the transactions of its account.
 *
 * <p>Only a payment ({@code scheduledPayment}, {@code subscription}) moves money into the
 * contract from outside: it is a {@code DEPOSIT} in the member's {@code investissement} transfer
 * category, so the budget sees it as money set aside. Everything else happens inside the
 * contract and is written {@code budgetNeutral}: a coupon ({@code DIVIDEND}, listed with the
 * dividends received), the management fees ({@code FEE}), a profit participation, the two legs
 * of a switch between funds, and any type not known here. Those rows show on the account page
 * only; no budget, cashflow or categorisation query reads them. No row is ever BUY or SELL: the
 * holdings are CORUM's own figures, never rebuilt from this ledger.
 *
 * <p>The list is reconciled the way {@code FortuneoSyncService.reconcileFullHistory} reconciles
 * a full history: upsert by CORUM's operation id, never touching a row's category on update (the
 * member may have moved it), and delete the synced rows CORUM no longer reports between its oldest
 * reported date and today. A null or empty list keeps every stored row: it far more likely means
 * "not read" than "every operation was reversed", and so does a list in which two operations
 * still share a key.
 *
 * <p>{@link Propagation#MANDATORY}: it runs inside the CORUM sync transaction, so a failure here
 * rolls the valuation of the same run back with it.
 */
@Service
public class CorumLifeOperationWriter {

    static final String EXTERNAL_ID_PREFIX = "corum:";
    static final String INVESTMENT_SLUG = "investissement";
    private static final String TICKER_PREFIX = "CORUM-LIFE-";
    private static final int DESCRIPTION_MAX = 255;
    private static final int NAME_MAX = 100;
    private static final int TICKER_MAX = 30;
    private static final Logger log = LoggerFactory.getLogger(CorumLifeOperationWriter.class);

    private static final Set<String> PAYMENTS = Set.of("scheduledPayment", "subscription");
    /** Neutral English fallbacks; the UI labels a row by its {@code type}. */
    private static final Map<String, String> DESCRIPTIONS = Map.of(
        "scheduledPayment", "Scheduled payment",
        "subscription", "Subscription payment",
        "couponDetachment", "Coupon",
        "administrativeManagementCosts", "Management fees",
        "profitParticipation", "Profit participation",
        "arbitrage", "Switch between funds"
    );
    private static final String UNKNOWN_DESCRIPTION = "CORUM Life operation";

    private final TransactionRepository transactionRepository;
    private final CategoryService categoryService;

    public CorumLifeOperationWriter(TransactionRepository transactionRepository, CategoryService categoryService) {
        this.transactionRepository = transactionRepository;
        this.categoryService = categoryService;
    }

    /** What one reconciliation did, as counts only: the rows carry the member's money. */
    public record Result(int inserted, int updated, int deleted, int unknownTypes) {
        static final Result KEPT = new Result(0, 0, 0, 0);
    }

    /**
     * The stable key of each operation, in list order: {@code corum:<id>}, or
     * {@code corum:<id>:+} / {@code corum:<id>:-} when two operations of one list share an id
     * (the two legs of a switch can). Null when a key is still repeated after that: two rows
     * cannot both own one key, so the caller writes none of that list and keeps the stored rows.
     */
    static List<String> externalIds(List<CorumPort.LifeOperation> operations) {
        Map<String, Integer> idCounts = new HashMap<>();
        for (CorumPort.LifeOperation operation : operations) {
            idCounts.merge(operation.operationId().trim(), 1, Integer::sum);
        }
        List<String> keys = new ArrayList<>(operations.size());
        Set<String> seen = new HashSet<>();
        for (CorumPort.LifeOperation operation : operations) {
            String id = operation.operationId().trim();
            String key = EXTERNAL_ID_PREFIX + id;
            if (idCounts.get(id) > 1) {
                key += operation.amountEur().signum() < 0 ? ":-" : ":+";
            }
            if (!seen.add(key)) {
                return null;
            }
            keys.add(key);
        }
        return keys;
    }

    /**
     * Reconciles the account's synced rows against {@code operations}, already accepted by
     * {@code CorumLifeWriter.validate}.
     *
     * @param holdings the contract's lines, which name the fund a coupon came from
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result reconcile(
        Long memberId, Account account, List<CorumPort.LifeOperation> operations,
        List<CorumPort.LifeHolding> holdings
    ) {
        if (operations == null || operations.isEmpty()) {
            return Result.KEPT;
        }
        List<String> keys = externalIds(operations);
        if (keys == null) {
            // CorumLifeWriter drops such a list first; kept here so a key can never own two rows.
            return Result.KEPT;
        }
        Map<String, CorumPort.LifeOperation> reported = new LinkedHashMap<>();
        for (int i = 0; i < operations.size(); i++) {
            reported.put(keys.get(i), operations.get(i));
        }
        LocalDate from = operations.stream().map(CorumPort.LifeOperation::date)
            .min(LocalDate::compareTo).orElseThrow();
        LocalDate to = LocalDate.now();

        Map<String, Transaction> storedById = new HashMap<>();
        List<Transaction> obsolete = new ArrayList<>();
        for (Transaction row : transactionRepository.findByAccountIdAndIsManualFalse(account.getId())) {
            String externalId = row.getExternalId();
            if (externalId != null && reported.containsKey(externalId)) {
                storedById.put(externalId, row);
            } else if (externalId != null && externalId.startsWith(EXTERNAL_ID_PREFIX)
                    && !row.getDate().isBefore(from) && !row.getDate().isAfter(to)) {
                // Only rows CORUM wrote can be declared gone: an adopted account may carry
                // synced rows from another importer (Finary) that CORUM never reported.
                obsolete.add(row);
            }
        }

        Map<String, String> fundNames = fundNames(holdings);
        Category investment = null;
        boolean investmentLoaded = false;
        int inserted = 0;
        int unknown = 0;
        List<Transaction> upserts = new ArrayList<>(reported.size());
        for (Map.Entry<String, CorumPort.LifeOperation> entry : reported.entrySet()) {
            CorumPort.LifeOperation operation = entry.getValue();
            String type = operation.type().trim();
            if (!DESCRIPTIONS.containsKey(type)) {
                unknown++;
            }
            Transaction row = storedById.get(entry.getKey());
            if (row == null) {
                row = Transaction.builder()
                    .account(account)
                    .externalId(entry.getKey())
                    .externalTransactionId(entry.getKey())
                    .nativeCurrency("EUR")
                    .build();
                if (PAYMENTS.contains(type)) {
                    if (!investmentLoaded) {
                        investment = investmentCategory(memberId);
                        investmentLoaded = true;
                    }
                    // Set on insert only: a category the member chose later is theirs.
                    row.setCategoryRef(investment);
                }
                inserted++;
            }
            apply(row, operation, type, fundNames);
            upserts.add(row);
        }

        if (!obsolete.isEmpty()) {
            transactionRepository.deleteAll(obsolete);
            // Releases the deleted rows' (account, external id) slots before an upsert reclaims one.
            transactionRepository.flush();
        }
        transactionRepository.saveAllAndFlush(upserts);
        return new Result(inserted, upserts.size() - inserted, obsolete.size(), unknown);
    }

    /** Everything CORUM owns on the row; the category and its manual flag are never touched here. */
    private static void apply(
        Transaction row, CorumPort.LifeOperation operation, String type, Map<String, String> fundNames
    ) {
        boolean payment = PAYMENTS.contains(type);
        String ticker = "couponDetachment".equals(type) ? couponTicker(operation.fundCodes()) : null;
        row.setDate(operation.date());
        row.setDescription(truncate(DESCRIPTIONS.getOrDefault(type, UNKNOWN_DESCRIPTION), DESCRIPTION_MAX));
        row.setAmount(operation.amountEur());
        row.setType(type);
        row.setTxType(txTypeOf(type));
        row.setTicker(ticker);
        row.setName(ticker == null ? null : truncate(
            fundNames.getOrDefault(ticker, "CORUM " + ticker.substring(TICKER_PREFIX.length())), NAME_MAX));
        row.setBudgetNeutral(!payment);
    }

    private static TransactionType txTypeOf(String type) {
        return switch (type) {
            case "scheduledPayment", "subscription" -> TransactionType.DEPOSIT;
            case "couponDetachment" -> TransactionType.DIVIDEND;
            case "administrativeManagementCosts" -> TransactionType.FEE;
            // A profit participation, a switch leg or a type not known here moves nothing in or out.
            default -> null;
        };
    }

    /**
     * The holding a coupon belongs to, as the life writer names it, when CORUM names exactly one
     * fund. A coupon credited for several funds at once stays unattributed rather than guessed.
     */
    private static String couponTicker(List<String> fundCodes) {
        if (fundCodes == null || fundCodes.size() != 1 || SyncValues.clean(fundCodes.getFirst()) == null) {
            return null;
        }
        String ticker = TICKER_PREFIX + fundCodes.getFirst().trim().toUpperCase(Locale.ROOT);
        return ticker.length() <= TICKER_MAX ? ticker : null;
    }

    private static Map<String, String> fundNames(List<CorumPort.LifeHolding> holdings) {
        Map<String, String> names = new HashMap<>();
        if (holdings == null) {
            return names;
        }
        for (CorumPort.LifeHolding holding : holdings) {
            String label = SyncValues.clean(holding.label());
            if (label != null) {
                names.put(TICKER_PREFIX + holding.productCode().trim().toUpperCase(Locale.ROOT), label);
            }
        }
        return names;
    }

    /**
     * The member's seeded transfer category for investments. An archived one is not revived and
     * one turned into another kind is not used: the payment then stays uncategorized.
     */
    private Category investmentCategory(Long memberId) {
        Category category = categoryService.findOrCreateDefault(memberId, INVESTMENT_SLUG)
            .filter(c -> c.getKind() == CategoryKind.TRANSFER)
            .orElse(null);
        if (category == null) {
            log.info("CORUM Life payments left uncategorized: no investment category (member={})", memberId);
        }
        return category;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
