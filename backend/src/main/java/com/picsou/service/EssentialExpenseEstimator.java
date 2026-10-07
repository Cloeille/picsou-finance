package com.picsou.service;

import com.picsou.dto.EssentialExpenseEstimateResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Estimates what a member must spend each month, from the debits on their current accounts.
 *
 * <p>The schema has no transfer marker — {@code TransactionType} runs
 * DEPOSIT/WITHDRAWAL/BUY/SELL/DIVIDEND/FEE and {@code category} is free text only the Finary
 * importer fills — so internal movements have to be inferred. Every rule below therefore leans
 * towards <em>over</em>-estimating: an over-sized emergency fund costs a little yield, an
 * under-sized one is the failure this whole figure exists to prevent.
 *
 * <p>The result is offered, never stored. Accepting it is an explicit write by the member.
 */
@Service
@Transactional(readOnly = true)
public class EssentialExpenseEstimator {

    private static final Logger log = LoggerFactory.getLogger(EssentialExpenseEstimator.class);

    /** How far back to look. Six complete months smooths quarterly bills without going stale. */
    private static final int WINDOW_MONTHS = 6;

    /** How far apart the two legs of one transfer may be booked by two different banks. */
    private static final int TRANSFER_MATCH_DAYS = 3;

    /**
     * Last resort only, and only when counterparty matching found nothing. Label matching cannot
     * be trusted — "VIREMENT" covers rent as readily as a savings top-up — so the patterns stay
     * narrow enough that a false positive is unlikely, at the cost of missing plenty.
     */
    private static final List<Pattern> TRANSFER_LABELS = Stream.of(
        "VIR(EMENT)?\\s+INTERNE",
        "VIR(EMENT)?\\s+DE\\s+COMPTE\\s+A\\s+COMPTE",
        "VERS\\s+(LE\\s+)?LIVRET",
        "EPARGNE\\s+AUTO",
        "TRANSFER\\s+TO\\s+(MY|SELF)",
        "INTERNAL\\s+TRANSFER"
    ).map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE)).toList();

    /** Investment legs: moving money into a position is not consumption. */
    private static final Set<TransactionType> INVESTMENT_TYPES =
        EnumSet.of(TransactionType.BUY, TransactionType.SELL, TransactionType.DEPOSIT);

    private final AccountAccessResolver accessResolver;
    private final TransactionRepository transactionRepository;
    private final PriceService priceService;

    public EssentialExpenseEstimator(AccountAccessResolver accessResolver,
                                     TransactionRepository transactionRepository,
                                     PriceService priceService) {
        this.accessResolver = accessResolver;
        this.transactionRepository = transactionRepository;
        this.priceService = priceService;
    }

    public EssentialExpenseEstimateResponse estimate(Long memberId) {
        List<Account> readable = accessResolver.readableAccounts(memberId);
        if (readable.isEmpty()) {
            return new EssentialExpenseEstimateResponse(null, 0, 0);
        }
        Map<Long, BigDecimal> shares = accessResolver.sharesFor(readable, memberId);

        // The current month is excluded on purpose. A partial month drags the mean down by up to
        // a sixth, in precisely the direction that under-sizes an emergency fund.
        YearMonth lastComplete = YearMonth.now().minusMonths(1);
        YearMonth first = lastComplete.minusMonths(WINDOW_MONTHS - 1L);
        LocalDate from = first.atDay(1);
        LocalDate to = lastComplete.atEndOfMonth();

        Map<Long, Account> byId = new HashMap<>();
        readable.forEach(a -> byId.put(a.getId(), a));

        // Credits are collected across *every* readable account, not just current ones: the other
        // leg of a savings top-up lands on the livret, which is the whole point of matching.
        List<Transaction> all =
            transactionRepository.findByAccountIdInAndDateBetweenOrderByDateAsc(byId.keySet(), from, to);

        Map<Long, List<Transaction>> creditsByCents = new HashMap<>();
        for (Transaction t : all) {
            if (t.getAmount() != null && t.getAmount().signum() > 0) {
                creditsByCents.computeIfAbsent(cents(t.getAmount()), k -> new ArrayList<>()).add(t);
            }
        }
        Set<Long> consumedCredits = new HashSet<>();

        Map<YearMonth, BigDecimal> monthlyTotals = new HashMap<>();
        int excludedTransfers = 0;

        for (Transaction t : all) {
            Account account = byId.get(t.getAccount().getId());
            if (account.getType() != AccountType.CHECKING) continue;
            if (t.getAmount() == null || t.getAmount().signum() >= 0) continue;

            if (t.getTicker() != null && !t.getTicker().isBlank()) continue;
            if (t.getTxType() != null && INVESTMENT_TYPES.contains(t.getTxType())) continue;

            if (matchCounterparty(t, creditsByCents, consumedCredits)) {
                excludedTransfers++;
                log.debug("Excluding {} on {} as an internal transfer (counterparty match)",
                    t.getAmount(), t.getDate());
                continue;
            }
            if (looksLikeTransferLabel(t.getDescription())) {
                excludedTransfers++;
                log.debug("Excluding {} on {} as an internal transfer (label match: {})",
                    t.getAmount(), t.getDate(), t.getDescription());
                continue;
            }

            BigDecimal eur = priceService.toEur(t.getAmount().abs(), t.getNativeCurrency(), null);
            if (eur == null) continue;
            BigDecimal weighted = AccountAccessResolver.weigh(eur, shares.get(account.getId()));
            // Registered here rather than before the filters above, so the divisor counts only
            // months that fed the numerator. A month whose sole debit was an internal transfer
            // is not a month of near-zero spending, it is a month with nothing to learn from,
            // and dividing by it halves the estimate — the one direction this class must not err
            // in, since a safety net too small is the failure mode.
            monthlyTotals.merge(YearMonth.from(t.getDate()), weighted, BigDecimal::add);
        }

        int monthsObserved = monthlyTotals.size();
        if (monthsObserved == 0) {
            return new EssentialExpenseEstimateResponse(null, 0, excludedTransfers);
        }

        // Divided by the months actually observed, never by WINDOW_MONTHS: an account synced two
        // months ago would otherwise report a third of what the member really spends.
        BigDecimal total = monthlyTotals.values().stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal estimate = total.divide(BigDecimal.valueOf(monthsObserved), 2, RoundingMode.HALF_UP);

        return new EssentialExpenseEstimateResponse(estimate, monthsObserved, excludedTransfers);
    }

    /**
     * True when some credit of the same amount landed on a <em>different</em> account within a few
     * days — real double-entry matching, which catches a livret top-up, a PEA funding or a card
     * account sweep whatever the wording or the bank.
     *
     * <p>A matched credit is consumed so a single incoming salary cannot excuse six debits that
     * happen to share its amount.
     */
    private boolean matchCounterparty(Transaction debit,
                                      Map<Long, List<Transaction>> creditsByCents,
                                      Set<Long> consumed) {
        List<Transaction> candidates = creditsByCents.get(cents(debit.getAmount().abs()));
        if (candidates == null) return false;
        for (Transaction credit : candidates) {
            if (consumed.contains(credit.getId())) continue;
            if (Objects.equals(credit.getAccount().getId(), debit.getAccount().getId())) continue;
            if (Math.abs(ChronoUnit.DAYS.between(debit.getDate(), credit.getDate())) > TRANSFER_MATCH_DAYS) {
                continue;
            }
            consumed.add(credit.getId());
            return true;
        }
        return false;
    }

    private boolean looksLikeTransferLabel(String description) {
        if (description == null || description.isBlank()) return false;
        return TRANSFER_LABELS.stream().anyMatch(p -> p.matcher(description).find());
    }

    /** Amounts keyed to the cent, so 500.00 and 500.004 land in the same bucket. */
    private static Long cents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }
}
