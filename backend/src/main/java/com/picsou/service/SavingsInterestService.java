package com.picsou.service;

import com.picsou.dto.SavingsInterestProjection;
import com.picsou.model.Account;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.RateBasis;
import com.picsou.model.SavingsInterestConfig;
import com.picsou.model.SavingsProduct;
import com.picsou.model.Transaction;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Projects the annual interest earned on a savings book (livret) using the French
 * <em>règle de la quinzaine</em> (fortnightly earning rule).
 *
 * <h2>Key rules</h2>
 * <ul>
 *   <li>The year is divided into 24 quinzaines: 1–15 and 16–end of each month.</li>
 *   <li><strong>Deposit:</strong> starts earning from the <em>1st of the following quinzaine</em>.</li>
 *   <li><strong>Withdrawal:</strong> stops earning from the <em>1st of the quinzaine in which it occurs</em>.</li>
 *   <li>Interest per quinzaine = effective_capital × net_annual_rate / 24.</li>
 * </ul>
 *
 * <h2>Guardrail — NO balance writes</h2>
 * <p>This service is <em>read-only</em>.  Projected interest figures are never written
 * to {@code account.current_balance} or {@code balance_snapshot}.  Net worth stays
 * driven exclusively by real synced balances.</p>
 */
@Service
@Transactional(readOnly = true)
public class SavingsInterestService {

    // Divisor for per-quinzaine rate: annualRatePct / 100 / 24 = annualRatePct / 2400
    private static final BigDecimal DIVISOR = new BigDecimal("2400");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal DEFAULT_PFU_PCT = new BigDecimal("30");

    private final TransactionRepository transactionRepository;
    private final BalanceSnapshotRepository balanceSnapshotRepository;

    public SavingsInterestService(
        TransactionRepository transactionRepository,
        BalanceSnapshotRepository balanceSnapshotRepository
    ) {
        this.transactionRepository = transactionRepository;
        this.balanceSnapshotRepository = balanceSnapshotRepository;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Computes a year-to-date and full-year projected interest for the given account.
     *
     * <p>No data is written; the returned {@link SavingsInterestProjection} is purely
     * informational.</p>
     *
     * @param account the savings-book account
     * @param config  its interest configuration
     * @param asOf    reference date; determines which quinzaines are counted as YTD
     * @return a projection record (never null)
     * @throws IllegalArgumentException if the config contains an invalid combination
     *                                  (e.g. regulated product with GROSS rate basis)
     */
    public SavingsInterestProjection computeProjection(
        Account account,
        SavingsInterestConfig config,
        LocalDate asOf
    ) {
        validate(config);

        int year = asOf.getYear();
        LocalDate jan1  = LocalDate.of(year, 1, 1);
        LocalDate dec31 = LocalDate.of(year, 12, 31);

        BigDecimal netRatePct = effectiveNetRatePct(config);

        // Fetch year's transactions once and reuse for both capital resolution and quinzaine loops.
        List<Transaction> yearTxs = transactionRepository
            .findByAccountIdAndDateBetweenOrderByDateAsc(account.getId(), jan1, dec31);

        BigDecimal startingCapital = resolveStartingCapital(account, jan1, dec31, yearTxs);

        List<LocalDate> qStarts = buildQuinzaineStarts(year); // 25 boundary dates

        BigDecimal ytdInterest     = BigDecimal.ZERO;
        BigDecimal projectedInterest = BigDecimal.ZERO;
        BigDecimal cumDeposits     = BigDecimal.ZERO;
        BigDecimal cumWithdrawals  = BigDecimal.ZERO;
        BigDecimal capitalAtAsOf   = startingCapital; // updated each YTD quinzaine

        for (int k = 0; k < 24; k++) {
            LocalDate qStart = qStarts.get(k);
            LocalDate qEnd   = qStarts.get(k + 1).minusDays(1);

            // Withdrawals in this quinzaine reduce earning capital FROM the start of this quinzaine.
            cumWithdrawals = cumWithdrawals.add(sumNegativeAbs(yearTxs, qStart, qEnd));

            // Effective earning capital for this quinzaine:
            //   = startingCapital
            //   + all deposits from Q0 … Q(k-1)   [added AFTER each Q, so cumDeposits here = sum Q0..Q(k-1)]
            //   - all withdrawals from Q0 … Qk     [deducted at the START of each Q]
            BigDecimal effectiveCapital = startingCapital
                .add(cumDeposits)
                .subtract(cumWithdrawals);

            BigDecimal quinzaineInterest = effectiveCapital
                .multiply(netRatePct)
                .divide(DIVISOR, 10, RoundingMode.HALF_UP);

            boolean inYtd = !qStart.isAfter(asOf);

            if (inYtd) {
                ytdInterest      = ytdInterest.add(quinzaineInterest);
                projectedInterest = projectedInterest.add(quinzaineInterest);
                capitalAtAsOf    = effectiveCapital;
            } else {
                // Remaining quinzaines are extrapolated at the capital level as of asOf.
                BigDecimal extrapolated = capitalAtAsOf
                    .multiply(netRatePct)
                    .divide(DIVISOR, 10, RoundingMode.HALF_UP);
                projectedInterest = projectedInterest.add(extrapolated);
            }

            // Deposits in this quinzaine start earning from the NEXT quinzaine.
            cumDeposits = cumDeposits.add(sumPositive(yearTxs, qStart, qEnd));
        }

        return new SavingsInterestProjection(
            ytdInterest.setScale(2, RoundingMode.HALF_UP),
            projectedInterest.setScale(2, RoundingMode.HALF_UP),
            LocalDate.of(year, 12, 31),
            netRatePct,
            config.getRateBasis(),
            config.getRateBasis() == RateBasis.NET
        );
    }

    // ─── Validation ──────────────────────────────────────────────────────────

    /**
     * Validates a savings interest config for logical consistency.
     *
     * <ul>
     *   <li>Regulated products (LIVRET_A, LDDS, LEP) must use {@code NET} rate basis;
     *       specifying {@code GROSS} is rejected.</li>
     *   <li>{@code COMMERCIAL + GROSS} without an explicit tax rate is accepted
     *       (the computation defaults to 30 % PFU).</li>
     * </ul>
     *
     * @throws IllegalArgumentException on invalid combinations
     */
    public void validate(SavingsInterestConfig config) {
        if (isRegulated(config.getProduct()) && config.getRateBasis() == RateBasis.GROSS) {
            throw new IllegalArgumentException(
                "Regulated savings products (" + config.getProduct() + ") must use a NET rate basis. "
                    + "Their interest is tax-exempt; a GROSS/NET distinction does not apply."
            );
        }
    }

    // ─── Rate computation ────────────────────────────────────────────────────

    /**
     * Returns the effective net annual rate as a percentage.
     * <ul>
     *   <li>Regulated: returns {@link SavingsInterestConfig#getAnnualRate()} as-is (already net).</li>
     *   <li>COMMERCIAL + NET: returns the rate as-is.</li>
     *   <li>COMMERCIAL + GROSS: applies {@code annualRate × (1 − taxRate / 100)},
     *       defaulting the tax rate to 30 % (PFU) if not provided.</li>
     * </ul>
     */
    BigDecimal effectiveNetRatePct(SavingsInterestConfig config) {
        if (isRegulated(config.getProduct()) || config.getRateBasis() == RateBasis.NET) {
            return config.getAnnualRate();
        }
        // COMMERCIAL + GROSS
        BigDecimal taxPct = config.getTaxRatePct() != null
            ? config.getTaxRatePct()
            : DEFAULT_PFU_PCT;
        BigDecimal retention = BigDecimal.ONE.subtract(taxPct.divide(HUNDRED, 10, RoundingMode.HALF_UP));
        return config.getAnnualRate().multiply(retention).setScale(10, RoundingMode.HALF_UP);
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /** Returns true for the three regulated French savings products. */
    private boolean isRegulated(SavingsProduct product) {
        return product == SavingsProduct.LIVRET_A
            || product == SavingsProduct.LDDS
            || product == SavingsProduct.LEP;
    }

    /**
     * Resolves the opening capital for Jan 1 of the computation year.
     *
     * <p>Strategy (in order of preference):</p>
     * <ol>
     *   <li>Earliest {@link BalanceSnapshot} found within the year.</li>
     *   <li>Fallback: {@code current_balance − net transaction flows of the year}.</li>
     * </ol>
     */
    private BigDecimal resolveStartingCapital(
        Account account,
        LocalDate jan1,
        LocalDate dec31,
        List<Transaction> yearTxs
    ) {
        List<BalanceSnapshot> snapshots = balanceSnapshotRepository
            .findByAccountIdAndDateBetweenOrderByDateAsc(account.getId(), jan1, dec31);
        if (!snapshots.isEmpty()) {
            return snapshots.get(0).getBalance();
        }
        // Fallback: reverse-engineer the opening balance from the closing balance and net flows.
        // current_balance = startingCapital + netFlows  →  startingCapital = current_balance − netFlows
        BigDecimal netFlows = yearTxs.stream()
            .map(Transaction::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return account.getCurrentBalance().subtract(netFlows);
    }

    /**
     * Builds the 25 quinzaine boundary dates for {@code year}.
     *
     * <p>The list contains 24 quinzaine start dates (1st and 16th of each month)
     * plus a sentinel (Jan 1 of the following year) so that {@code qStarts[k+1]}
     * is always defined for {@code k = 0…23}.</p>
     */
    private List<LocalDate> buildQuinzaineStarts(int year) {
        List<LocalDate> starts = new ArrayList<>(25);
        for (int month = 1; month <= 12; month++) {
            starts.add(LocalDate.of(year, month, 1));
            starts.add(LocalDate.of(year, month, 16));
        }
        starts.add(LocalDate.of(year + 1, 1, 1)); // sentinel
        return starts;
    }

    /** Sum of amounts of positive (deposit) transactions within [from, to]. */
    private BigDecimal sumPositive(List<Transaction> txs, LocalDate from, LocalDate to) {
        return txs.stream()
            .filter(t -> !t.getDate().isBefore(from) && !t.getDate().isAfter(to))
            .map(Transaction::getAmount)
            .filter(a -> a.signum() > 0)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Sum of absolute values of negative (withdrawal) transactions within [from, to]. */
    private BigDecimal sumNegativeAbs(List<Transaction> txs, LocalDate from, LocalDate to) {
        return txs.stream()
            .filter(t -> !t.getDate().isBefore(from) && !t.getDate().isAfter(to))
            .map(Transaction::getAmount)
            .filter(a -> a.signum() < 0)
            .map(BigDecimal::abs)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
