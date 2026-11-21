package com.picsou.service.budget;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pairs the two legs of a transfer between two accounts of the same member and categorises them
 * {@value #CATEGORY_SLUG}, so cash flow and the Sankey net them instead of counting a spend and
 * an income (issue #450).
 *
 * <p>Two legs pair when they sit on two different owned accounts, carry the same currency and
 * exactly opposite amounts, and are booked at most {@value #MAX_DAY_GAP} days apart. A pair is
 * taken only when each leg is the other's single candidate: two possible partners leave both
 * alone. A leg the member categorised by hand never takes part.
 */
@Service
public class InternalTransferPairingService {

    static final String CATEGORY_SLUG = "virement-interne";
    /**
     * Matches the bank sync lookback: older rows were paired by an earlier sync. Rows up to
     * {@value #MAX_DAY_GAP} days past either end are loaded too, but only as rivals that make a
     * group ambiguous: a pair is taken only when both legs sit inside the window, so every
     * possible partner of each leg was loaded.
     */
    static final int LOOKBACK_DAYS = 90;
    static final int MAX_DAY_GAP = 3;
    private static final String TRADING = "TRADING";
    /** A loan repayment is budgeted as an expense, and peer loans book their own transfer. */
    private static final Set<AccountType> EXCLUDED_ACCOUNTS = EnumSet.of(AccountType.LOAN, AccountType.PEER_LOAN);

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final CategorizationService categorizationService;

    public InternalTransferPairingService(AccountRepository accounts, TransactionRepository transactions,
                                          CategorizationService categorizationService) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.categorizationService = categorizationService;
    }

    /**
     * Pairs the recent transfer legs and returns the number of transactions it recategorised.
     * A second run changes nothing: a leg already in a {@code TRANSFER} category keeps it.
     *
     * <p>Owned accounts only: categories are member-scoped, so a co-owned row is categorised by
     * its owner, never with another member's category.
     */
    @Transactional
    public int pairRecent(Long memberId, LocalDate today) {
        List<Long> accountIds = accounts.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(memberId).stream()
            .filter(a -> a.getId() != null && !EXCLUDED_ACCOUNTS.contains(a.getType()))
            .map(Account::getId)
            .toList();
        if (accountIds.size() < 2) {
            return 0;
        }
        LocalDate from = today.minusDays(LOOKBACK_DAYS);
        List<Transaction> legs = transactions
            .findByAccountIdInAndDateBetweenOrderByDateAsc(accountIds,
                from.minusDays(MAX_DAY_GAP), today.plusDays(MAX_DAY_GAP))
            .stream()
            .filter(InternalTransferPairingService::isCandidate)
            .toList();
        List<Transaction[]> pairs = uniquePairs(legs).stream()
            .filter(pair -> inWindow(pair[0], from, today) && inWindow(pair[1], from, today))
            .toList();
        if (pairs.isEmpty()) {
            return 0;
        }
        Category target = categorizationService.categoriesBySlug(memberId).get(CATEGORY_SLUG);
        if (target == null || target.getKind() != CategoryKind.TRANSFER) {
            return 0;
        }
        List<Transaction> changed = new ArrayList<>();
        for (Transaction[] pair : pairs) {
            for (Transaction leg : pair) {
                if (apply(leg, target)) {
                    changed.add(leg);
                }
            }
        }
        if (!changed.isEmpty()) {
            transactions.saveAll(changed);
        }
        return changed.size();
    }

    /**
     * Debit/credit pairs in which each leg is the other's only candidate. Every cash leg counts as
     * a candidate, a manual, typed or same-account one included, so it makes a group ambiguous;
     * the pair is then taken only when both legs are pairable and sit on two different accounts.
     */
    private static List<Transaction[]> uniquePairs(List<Transaction> legs) {
        Map<String, List<Transaction>> creditsByKey = new HashMap<>();
        for (Transaction leg : legs) {
            if (leg.getAmount().signum() > 0) {
                creditsByKey.computeIfAbsent(key(leg), k -> new ArrayList<>()).add(leg);
            }
        }
        Map<Transaction, List<Transaction>> candidatesByDebit = new LinkedHashMap<>();
        Map<Transaction, Integer> debitCountByCredit = new LinkedHashMap<>();
        for (Transaction debit : legs) {
            if (debit.getAmount().signum() >= 0) {
                continue;
            }
            List<Transaction> candidates = new ArrayList<>();
            for (Transaction credit : creditsByKey.getOrDefault(key(debit), List.of())) {
                if (matches(debit, credit)) {
                    candidates.add(credit);
                    debitCountByCredit.merge(credit, 1, Integer::sum);
                }
            }
            candidatesByDebit.put(debit, candidates);
        }
        List<Transaction[]> pairs = new ArrayList<>();
        candidatesByDebit.forEach((debit, candidates) -> {
            if (candidates.size() != 1) {
                return;
            }
            Transaction credit = candidates.getFirst();
            if (debitCountByCredit.get(credit) == 1 && isPairable(debit) && isPairable(credit)
                && !Objects.equals(debit.getAccount().getId(), credit.getAccount().getId())) {
                pairs.add(new Transaction[] {debit, credit});
            }
        });
        return pairs;
    }

    private static boolean inWindow(Transaction leg, LocalDate from, LocalDate to) {
        return !leg.getDate().isBefore(from) && !leg.getDate().isAfter(to);
    }

    /** Currency and absolute amount, scale-insensitive: {@code -100.00} and {@code 100} share a key. */
    private static String key(Transaction tx) {
        return tx.getNativeCurrency().toUpperCase(Locale.ROOT) + '|'
            + tx.getAmount().abs().stripTrailingZeros().toPlainString();
    }

    private static boolean matches(Transaction debit, Transaction credit) {
        return debit.getAmount().negate().compareTo(credit.getAmount()) == 0
            && Math.abs(ChronoUnit.DAYS.between(debit.getDate(), credit.getDate())) <= MAX_DAY_GAP;
    }

    /**
     * Any leg that could be the other side of a transfer. Budget-neutral rows and Trade Republic
     * {@code TRADING} rows are left out: their importer already pairs them.
     */
    private static boolean isCandidate(Transaction tx) {
        return tx.getAmount() != null && tx.getAmount().signum() != 0
            && tx.getDate() != null && tx.getAccount() != null && tx.getNativeCurrency() != null
            && !tx.isBudgetNeutral()
            && !TRADING.equalsIgnoreCase(tx.getCategory());
    }

    /**
     * Uncategorised or auto-categorised cash legs. A hand-entered row saved with a category before
     * {@code categoryManual} existed carries the user's choice all the same. Trade legs (Trade
     * Republic {@code TRADING} rows, typed buys, sells, dividends and fees) are a trade, not a
     * transfer, and a budget-neutral row stays out of every budget category.
     */
    private static boolean isPairable(Transaction tx) {
        return !tx.isCategoryManual()
            && !(tx.isManual() && tx.getCategoryRef() != null)
            && (tx.getTxType() == null || tx.getTxType() == TransactionType.DEPOSIT
                || tx.getTxType() == TransactionType.WITHDRAWAL);
    }

    /** A leg already in a transfer category (savings, investment) keeps it: it already nets. */
    private static boolean apply(Transaction leg, Category target) {
        Category current = leg.getCategoryRef();
        if (current != null && current.getKind() == CategoryKind.TRANSFER) {
            return false;
        }
        leg.setCategoryRef(target);
        leg.setAiSuggestedCategoryId(null);
        leg.setAiConfidence(null);
        return true;
    }
}
