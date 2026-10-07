package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.PriceSnapshot;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Rebuilds an account's past balance snapshots from the ledger a sync just imported.
 *
 * <p>A sync writes exactly one snapshot, dated today, so an account's chart starts the day it was
 * first connected however much history the provider returned. Everything needed to fill the gap is
 * already stored: the authoritative balance of today, a full transaction ledger, and — for
 * securities — daily prices backfilled by {@code PriceBackfillRunner}. This walks that ledger
 * backwards and writes the days it can establish.
 *
 * <p><strong>It never overwrites an existing snapshot.</strong> A same-date row may be a figure the
 * user entered by hand or one an earlier sync observed directly; either is better evidence than a
 * reconstruction, so a reconstructed value only ever fills a hole.
 */
@Service
public class BalanceHistoryReconstructor {
    private static final Logger log = LoggerFactory.getLogger(BalanceHistoryReconstructor.class);
    private static final Set<AccountType> SECURITIES_TYPES =
        Set.of(AccountType.PEA, AccountType.COMPTE_TITRES);

    private final TransactionRepository transactionRepository;
    private final BalanceSnapshotRepository snapshotRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;

    public BalanceHistoryReconstructor(
        TransactionRepository transactionRepository,
        BalanceSnapshotRepository snapshotRepository,
        PriceSnapshotRepository priceSnapshotRepository
    ) {
        this.transactionRepository = transactionRepository;
        this.snapshotRepository = snapshotRepository;
        this.priceSnapshotRepository = priceSnapshotRepository;
    }

    /**
     * Reconstructs and stores what the ledger can establish about this account's past.
     *
     * <p>Runs inside the caller's sync transaction: a reconstruction that fails rolls back with the
     * import it derives from, rather than leaving a half-written curve behind.
     *
     * @return how many snapshots were created
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int reconstruct(Account account, BigDecimal balanceToday, LocalDate today) {
        if (account.getId() == null || balanceToday == null) {
            return 0;
        }
        List<Transaction> ledger = transactionRepository.findByAccountIdAndIsManualFalse(account.getId());
        if (ledger.isEmpty()) {
            return 0;
        }
        return SECURITIES_TYPES.contains(account.getType())
            ? reconstructSecurities(account, ledger, today)
            : reconstructCash(account, ledger, balanceToday, today);
    }

    /**
     * Walks a cash ledger backwards from the balance known today.
     *
     * <p>The arithmetic is exact where the ledger is complete: the balance at the end of a day is
     * today's balance minus everything booked after it. Completeness is the assumption this rests
     * on, and it is not verifiable here — a missing entry shifts every older day by its amount,
     * silently. Fortuneo does send an {@code originalBalanceAmount} per row that looked like the
     * answer, but its value never chains (checked against the account's own ordering: zero
     * agreements out of hundreds of consecutive pairs), so its meaning is unestablished and it is
     * deliberately not relied on rather than trusted on its name.
     */
    private int reconstructCash(
        Account account,
        List<Transaction> ledger,
        BigDecimal balanceToday,
        LocalDate today
    ) {
        TreeMap<LocalDate, BigDecimal> dailyMovement = new TreeMap<>(Comparator.reverseOrder());
        for (Transaction tx : ledger) {
            if (tx.getDate() == null || tx.getAmount() == null || tx.getDate().isAfter(today)) {
                continue;
            }
            dailyMovement.merge(tx.getDate(), tx.getAmount(), BigDecimal::add);
        }
        if (dailyMovement.isEmpty()) {
            return 0;
        }

        LocalDate oldestDay = dailyMovement.lastKey();
        Set<LocalDate> existingDates = new HashSet<>();
        for (BalanceSnapshot snapshot : snapshotRepository
            .findByAccountIdAndDateBetweenOrderByDateAsc(account.getId(), oldestDay, today)) {
            existingDates.add(snapshot.getDate());
        }

        List<BalanceSnapshot> created = new ArrayList<>();
        BigDecimal running = balanceToday;
        for (Map.Entry<LocalDate, BigDecimal> day : dailyMovement.entrySet()) {
            // A cash account has no cost basis distinct from its balance -- the same convention
            // DashboardService applies to an account with no holdings.
            addIfAbsent(created, account, day.getKey(), running, running, existingDates);
            running = running.subtract(day.getValue());
        }
        return persist(account, created);
    }

    /**
     * Values the securities an account held, day by day.
     *
     * <p>Quantities come from replaying the BUY/SELL stream forward from the first trade; prices
     * come from {@code price_snapshot}, which {@code PriceBackfillRunner} fills from the market
     * data provider. A day where any held instrument has no price is skipped rather than valued at
     * a stale or missing one — a curve with a gap is honest, a curve with an invented point is not.
     * Non-trading days fall out for free: no price is stored for them.
     *
     * <p>The cash pocket is deliberately excluded: this ledger records what the account did with
     * its securities, never a transfer funding it, so cash could only be extrapolated by assuming
     * the account was never fed — which is exactly the kind of assumption that turns into a wrong
     * number nobody can spot later. The snapshot therefore carries the securities valuation, and
     * the account's own {@code cashBalance} stays the only statement about its cash.
     *
     * <p>The cost basis is replayed alongside the quantities, because the snapshot's
     * {@code invested_amount} is the whole of what {@code HistoryService} subtracts to draw the
     * P&amp;L curve: writing the day's value there — the obvious way to satisfy a NOT NULL column
     * — reports every reconstructed day as a gain of exactly zero, and the live point of today
     * then lands the account's entire gain in a single vertical step. A basis carried forward from
     * the trades that built the position gives the curve the shape it exists to show.
     */
    private int reconstructSecurities(Account account, List<Transaction> ledger, LocalDate today) {
        List<Transaction> trades = ledger.stream()
            .filter(tx -> tx.getTxType() == TransactionType.BUY || tx.getTxType() == TransactionType.SELL)
            .filter(tx -> tx.getTicker() != null && tx.getQuantity() != null && tx.getDate() != null)
            .filter(tx -> !tx.getDate().isAfter(today))
            .sorted(Comparator.comparing(Transaction::getDate))
            .toList();
        if (trades.isEmpty()) {
            return 0;
        }

        LocalDate firstDay = trades.getFirst().getDate();
        if (!firstDay.isBefore(today)) {
            return 0;
        }
        LocalDate lastDay = today.minusDays(1);
        Set<String> tickers = new HashSet<>();
        for (Transaction trade : trades) {
            tickers.add(trade.getTicker());
        }
        Map<PriceKey, BigDecimal> prices = new HashMap<>();
        for (PriceSnapshot price : priceSnapshotRepository.findByTickerInAndDateBetween(
            tickers,
            firstDay,
            lastDay
        )) {
            if (price.getTicker() != null && price.getDate() != null && price.getPriceEur() != null) {
                prices.put(new PriceKey(price.getTicker(), price.getDate()), price.getPriceEur());
            }
        }
        Set<LocalDate> existingDates = new HashSet<>();
        for (BalanceSnapshot snapshot : snapshotRepository
            .findByAccountIdAndDateBetweenOrderByDateAsc(account.getId(), firstDay, lastDay)) {
            existingDates.add(snapshot.getDate());
        }

        // Valued day by day rather than only on the days the account traded: between two trades
        // the holdings are unchanged but their price is not, and a curve that only moves when
        // something was bought would hide exactly the fluctuation it exists to show.
        List<BalanceSnapshot> created = new ArrayList<>();
        Map<String, Position> held = new LinkedHashMap<>();
        int next = 0;
        for (LocalDate day = firstDay; day.isBefore(today); day = day.plusDays(1)) {
            while (next < trades.size() && !trades.get(next).getDate().isAfter(day)) {
                apply(held, trades.get(next++));
            }
            valueOn(created, account, day, held, prices, existingDates);
        }
        return persist(account, created);
    }

    /**
     * A position as the replay knows it: how much is held, and what it cost.
     *
     * <p>{@code costBasis} is null once anything made it unknowable — a purchase the provider
     * reported without an execution price. Null propagates rather than being replaced by a zero,
     * which would read downstream as "this line was free" and print the whole position as gain.
     */
    private record Position(BigDecimal quantity, BigDecimal costBasis) {
        static final Position EMPTY = new Position(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private record PriceKey(String ticker, LocalDate date) {}

    /**
     * Books one trade into the running positions.
     *
     * <p>A purchase adds what it cost: quantity times the execution price, plus the fees, which
     * are part of the price paid and so part of what has to be earned back before the line shows
     * a gain. A sale removes cost at the position's average — the same convention as the live
     * figure, which is {@code averageBuyIn × quantity} — so selling half a line leaves half its
     * cost behind rather than its proceeds, which would let a profitable sale rewrite the history
     * of the shares that were kept.
     *
     * <p>Quantities are read as unsigned magnitudes with the direction taken from the type, which
     * is the convention the imported ledger already follows.
     */
    private void apply(Map<String, Position> held, Transaction tx) {
        Position current = held.getOrDefault(tx.getTicker(), Position.EMPTY);
        BigDecimal quantity = tx.getQuantity().abs();
        if (tx.getTxType() == TransactionType.BUY) {
            held.put(tx.getTicker(), new Position(
                current.quantity().add(quantity),
                addCost(current.costBasis(), buyCost(tx))));
            return;
        }
        BigDecimal remaining = current.quantity().subtract(quantity);
        held.put(tx.getTicker(), new Position(remaining, basisAfterSale(current, remaining)));
    }

    /** What a purchase cost, or null when the provider priced it in no way this can read. */
    private BigDecimal buyCost(Transaction tx) {
        if (tx.getPricePerUnit() == null) {
            return null;
        }
        BigDecimal cost = tx.getQuantity().abs().multiply(tx.getPricePerUnit());
        return tx.getFees() == null ? cost : cost.add(tx.getFees().abs());
    }

    private BigDecimal addCost(BigDecimal basis, BigDecimal addition) {
        return basis == null || addition == null ? null : basis.add(addition);
    }

    /** The basis left after a sale: the same average per share, over the shares still held. */
    private BigDecimal basisAfterSale(Position current, BigDecimal remaining) {
        if (remaining.signum() <= 0 || current.quantity().signum() <= 0) {
            // Nothing is still held (or less than nothing, which the ledger should not produce):
            // either way no cost stays attached to the line. Resetting to zero also means a later
            // purchase can establish a fresh basis even when the now-closed position was unknown.
            return BigDecimal.ZERO;
        }
        if (current.costBasis() == null) {
            return null;
        }
        return current.costBasis()
            .multiply(remaining)
            .divide(current.quantity(), 8, RoundingMode.HALF_UP);
    }

    /**
     * Values the day, and pairs that value with the basis of the very same positions.
     *
     * <p>Both sides are built in one pass over the same lines, for the reason
     * {@code AccountService.valuation} makes one: a value covering positions the basis does not,
     * or the reverse, prints a gain the size of the difference. A position whose cost could not
     * be replayed therefore makes the whole day's basis unknown, and an unknown basis falls back
     * to the value — the day is still drawn, it simply claims no gain rather than a wrong one.
     */
    private void valueOn(
        List<BalanceSnapshot> created,
        Account account,
        LocalDate date,
        Map<String, Position> held,
        Map<PriceKey, BigDecimal> prices,
        Set<LocalDate> existingDates
    ) {
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal basis = BigDecimal.ZERO;
        for (Map.Entry<String, Position> entry : held.entrySet()) {
            Position position = entry.getValue();
            if (position.quantity().signum() <= 0) {
                continue;
            }
            BigDecimal price = prices.get(new PriceKey(entry.getKey(), date));
            if (price == null) {
                return;
            }
            value = value.add(position.quantity().multiply(price));
            basis = addCost(basis, position.costBasis());
        }
        if (value.signum() > 0) {
            addIfAbsent(created, account, date, value, basis == null ? value : basis, existingDates);
        }
    }

    private void addIfAbsent(
        List<BalanceSnapshot> created,
        Account account,
        LocalDate date,
        BigDecimal balance,
        BigDecimal investedAmount,
        Set<LocalDate> existingDates
    ) {
        if (existingDates.contains(date)) {
            return;
        }
        created.add(snapshot(account, date, balance, investedAmount));
        existingDates.add(date);
    }

    private BalanceSnapshot snapshot(
        Account account,
        LocalDate date,
        BigDecimal balance,
        BigDecimal investedAmount
    ) {
        return BalanceSnapshot.builder()
            .account(account)
            .date(date)
            .balance(balance)
            .investedAmount(investedAmount)
            .build();
    }

    private int persist(Account account, List<BalanceSnapshot> created) {
        if (created.isEmpty()) {
            return 0;
        }
        snapshotRepository.saveAll(created);
        log.info(
            "Reconstructed {} balance snapshot(s) from the ledger (account={})",
            created.size(), account.getId()
        );
        return created.size();
    }
}
