package com.picsou.service;

import com.picsou.dto.DashboardResponse.AccountPoint;
import com.picsou.dto.DashboardResponse.NetWorthIntradayPoint;
import com.picsou.dto.DashboardResponse.NetWorthPoint;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.PriceSnapshot;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.PriceSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    private final AccountRepository accountRepository;
    private final BalanceSnapshotRepository snapshotRepository;
    private final AccountHoldingRepository holdingRepository;
    private final PriceService priceService;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final AccountService accountService;
    private final AccountAccessResolver accessResolver;

    public HistoryService(
        AccountRepository accountRepository,
        BalanceSnapshotRepository snapshotRepository,
        AccountHoldingRepository holdingRepository,
        PriceService priceService,
        PriceSnapshotRepository priceSnapshotRepository,
        AccountService accountService,
        AccountAccessResolver accessResolver
    ) {
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
        this.holdingRepository = holdingRepository;
        this.priceService = priceService;
        this.priceSnapshotRepository = priceSnapshotRepository;
        this.accountService = accountService;
        this.accessResolver = accessResolver;
    }

    public List<NetWorthPoint> buildHistory(List<Long> accountIds, int months, Long memberId) {
        return buildHistory(accountIds, months, false, memberId);
    }

    /**
     * Rejects any request containing an account the member may not read, and returns each
     * account's share so the caller can weight it.
     *
     * <p>Member scoping is mandatory: a {@code null} memberId is a programming error
     * (every controller resolves {@code UserContext.currentMemberId()}, which is never
     * null), not a "skip validation" signal — failing loud here prevents a future caller
     * from accidentally returning another member's financial data.
     *
     * <p>Ownership alone is not the test: a co-owner legitimately reads an account they do not
     * own, so a positive share grants access on its own.
     *
     * <p>But a zero share is not the opposite signal, and treating it as one was a bug. The
     * administrative owner may legitimately hold none of their own account — they can transfer
     * their whole share away, and {@code shareFrom} deliberately reports that as 0 rather than
     * inventing an implicit 100%. Reading is still theirs: they administer it, and
     * {@link AccountAccessResolver#requireReadable} has always let them through on that basis.
     * This guard did not, and because it rejects the <em>whole batch</em> while
     * {@code DashboardService} passes every readable id at once, one such account 404'd the
     * entire dashboard history rather than showing itself as worth nothing. The two guards now
     * answer the same question.
     */
    private Map<Long, BigDecimal> assertReadable(List<Account> accounts, Long memberId) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId is required for member-scoped history");
        }
        Map<Long, BigDecimal> shares = accessResolver.sharesFor(accounts, memberId);
        for (Account account : accounts) {
            BigDecimal share = shares.getOrDefault(account.getId(), BigDecimal.ZERO);
            boolean owner = account.getMember() != null
                && memberId.equals(account.getMember().getId());
            if (share.signum() <= 0 && !owner) {
                throw com.picsou.exception.ResourceNotFoundException.account(account.getId());
            }
        }
        return shares;
    }

    /** The member's slice of an account-level amount. Zero-safe, null-safe. */
    private static BigDecimal weigh(BigDecimal amount, Map<Long, BigDecimal> shares, Long accountId) {
        return AccountAccessResolver.weigh(amount, shares.get(accountId));
    }

    /**
     * Build daily history with PnL for a set of accounts over the last N months.
     *
     * For each date:
     * - total = forward-filled sum of per-account balance from balance_snapshot
     *   (loans negated)
     * - invested = forward-filled sum of per-account invested_amount from balance_snapshot
     *   (loans contribute 0; non-loans use the latest snapshot row on or before that date)
     *
     * When split=true, each point also includes a per-account breakdown.
     * Today's point is replaced with live values from AccountService.liveBalanceEur
     * and AccountService.calculateInvestedAmount, so intraday changes are visible.
     */
    public List<NetWorthPoint> buildHistory(List<Long> accountIds, int months, boolean split, Long memberId) {
        List<Account> accounts = accountRepository.findAllById(accountIds);
        if (accounts.isEmpty()) return List.of();

        Map<Long, BigDecimal> shares = assertReadable(accounts, memberId);

        LocalDate from = LocalDate.now().minusMonths(months);

        Set<Long> loanIds = accounts.stream()
            .filter(a -> a.getType() == AccountType.LOAN)
            .map(Account::getId)
            .collect(Collectors.toSet());

        // Per-account forward-filled balance + invested snapshots + sorted dates.
        ForwardFillData ffData = buildPerAccountForwardFill(from, accounts);

        // Build the history points directly from forward-filled snapshots.
        List<NetWorthPoint> result = new ArrayList<>();
        for (LocalDate date : ffData.dates()) {
            BigDecimal aggTotal = BigDecimal.ZERO;
            BigDecimal aggInvested = BigDecimal.ZERO;
            BigDecimal aggPnl = BigDecimal.ZERO;
            Map<Long, AccountPoint> accountPoints = split ? new HashMap<>() : null;

            for (Account account : accounts) {
                Long accId = account.getId();
                boolean isLoan = loanIds.contains(accId);

                NavigableMap<LocalDate, BigDecimal> balMap = ffData.balanceByAccount().get(accId);
                NavigableMap<LocalDate, BigDecimal> invMap = ffData.investedByAccount().get(accId);
                var balEntry = balMap != null ? balMap.floorEntry(date) : null;
                var invEntry = invMap != null ? invMap.floorEntry(date) : null;

                // Snapshots hold 100% of the account's value; the member's share is applied
                // here, on read. Weighting at write time would mean rewriting the whole
                // history every time a split changes.
                BigDecimal rawBalance = weigh(
                    balEntry != null ? balEntry.getValue() : BigDecimal.ZERO, shares, accId);
                BigDecimal accTotal = isLoan ? rawBalance.negate() : rawBalance;
                aggTotal = aggTotal.add(accTotal);

                // Match live-path semantics: loans contribute 0 to invested; non-loans
                // use the forward-filled snapshot (falling back to balance if the row
                // predates V18 / the account has no prior snapshot).
                BigDecimal accInvested = isLoan
                    ? BigDecimal.ZERO
                    : (invEntry != null ? weigh(invEntry.getValue(), shares, accId) : rawBalance);
                aggInvested = aggInvested.add(accInvested);

                // Debt-neutral pnl (issue #18): loans contribute 0 — outstanding debt
                // is a liability, not an investment loss.
                BigDecimal accPnl = isLoan ? BigDecimal.ZERO : accTotal.subtract(accInvested);
                aggPnl = aggPnl.add(accPnl);

                if (split) {
                    accountPoints.put(accId, new AccountPoint(accTotal, accInvested, accPnl));
                }
            }

            result.add(new NetWorthPoint(date, aggTotal, aggInvested, aggPnl, accountPoints));
        }

        // Replace today's point with live-calculated values
        BigDecimal liveTotal = BigDecimal.ZERO;
        BigDecimal liveInvested = BigDecimal.ZERO;
        BigDecimal livePnl = BigDecimal.ZERO;
        Map<Long, AccountPoint> liveAccountPoints = split ? new HashMap<>() : null;

        for (Account account : accounts) {
            // One pass, not two: liveBalanceEur and calculateInvestedAmount each run the whole
            // valuation, and two runs can straddle a price-cache change — the value excluding an
            // asset the cost basis then includes is the disagreement that reported an untouched
            // account as an 85% loss, and here it would land straight in the live P&L point.
            // Both halves are then weighted by the same share, so the pairing survives.
            AccountService.Valuation valuation = accountService.valuation(account);
            BigDecimal accLive = weigh(valuation.liveEur(), shares, account.getId());
            BigDecimal accInvested = weigh(valuation.investedEur(), shares, account.getId());
            boolean isLoan = account.getType() == AccountType.LOAN;

            if (isLoan) {
                liveTotal = liveTotal.subtract(accLive);
            } else {
                liveTotal = liveTotal.add(accLive);
                liveInvested = liveInvested.add(accInvested);
            }

            // Debt-neutral pnl (issue #18): loans contribute 0.
            BigDecimal accPnl = isLoan ? BigDecimal.ZERO : accLive.subtract(accInvested);
            livePnl = livePnl.add(accPnl);

            if (split) {
                BigDecimal total = isLoan ? accLive.negate() : accLive;
                BigDecimal invested = isLoan ? BigDecimal.ZERO : accInvested;
                liveAccountPoints.put(account.getId(), new AccountPoint(total, invested, accPnl));
            }
        }

        LocalDate today = LocalDate.now();
        NetWorthPoint livePoint = new NetWorthPoint(today, liveTotal, liveInvested, livePnl, liveAccountPoints);

        boolean replaced = false;
        for (int i = result.size() - 1; i >= 0; i--) {
            if (result.get(i).date().equals(today)) {
                result.set(i, livePoint);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            result.add(livePoint);
        }

        log.info("buildHistory: {} dates, {} accounts, split={}, livePoint total={} invested={}",
            result.size(), accounts.size(), split, liveTotal, liveInvested);

        return result;
    }

    /**
     * Build hourly net worth history for the last 24 hours.
     *
     * For investment accounts (PEA, CT, Crypto): portfolio value = sum(holding.qty × intraday price at each hour)
     * plus the cash pocket held in the envelope, which also counts as invested (it is worth what it cost).
     * For bank/savings accounts: use today's balance snapshot (constant throughout the day).
     * For loans: negate the balance.
     */
    public List<NetWorthIntradayPoint> buildIntradayHistory(List<Long> accountIds, Long memberId) {
        List<Account> accounts = accountRepository.findAllById(accountIds);
        if (accounts.isEmpty()) return List.of();

        Map<Long, BigDecimal> shares = assertReadable(accounts, memberId);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = now.minusHours(24);

        // Collect all tickers and group holdings
        record HoldingData(String ticker, BigDecimal quantity, BigDecimal avgBuyEur) {}

        Map<Long, List<HoldingData>> accountHoldings = new HashMap<>();
        Map<Long, BigDecimal> accountHoldingsInvested = new HashMap<>();
        Map<Long, BigDecimal> accountCash = new HashMap<>(); // cash pocket of investment accounts, weighted
        Map<Long, BigDecimal> accountBankBalance = new HashMap<>(); // non-investment account balances
        Set<String> allTickers = new HashSet<>();
        Set<Long> loanIds = new HashSet<>();

        LocalDate today = LocalDate.now();

        for (Account account : accounts) {
            Long accId = account.getId();

            if (account.getType() == AccountType.LOAN) {
                loanIds.add(accId);
            }

            List<AccountHolding> holdings = holdingRepository.findByAccount_Id(accId);

            if (holdings.isEmpty()) {
                // Non-investment account: use today's balance snapshot or live balance
                var snapshot = snapshotRepository.findByAccountIdAndDate(accId, today);
                BigDecimal balance = snapshot.isPresent()
                    ? snapshot.get().getBalance()
                    : accountService.liveBalanceEur(account);
                // Weighted once here rather than in the hourly loop below — the value is
                // constant across the day, so there is no reason to re-apply it 24 times.
                accountBankBalance.put(accId, weigh(balance, shares, accId));
                accountHoldings.put(accId, List.of());
                accountHoldingsInvested.put(accId, BigDecimal.ZERO);
            } else {
                List<HoldingData> holdingDataList = new ArrayList<>();
                BigDecimal invested = BigDecimal.ZERO;

                for (AccountHolding h : holdings) {
                    BigDecimal qty = h.getQuantity();
                    BigDecimal avgBuy = h.getAverageBuyIn() != null ? h.getAverageBuyIn() : BigDecimal.ZERO;
                    BigDecimal avgBuyEur = priceService.toEur(avgBuy, account.getCurrency(), null);
                    String ticker = h.getTicker() != null ? h.getTicker().toUpperCase() : null;
                    holdingDataList.add(new HoldingData(ticker, qty, avgBuyEur));
                    invested = invested.add(qty.multiply(avgBuyEur));
                    if (ticker != null) allTickers.add(ticker);
                }

                // The cash pocket sits in the envelope next to the positions and is worth exactly
                // what it cost, so it goes on both sides, as valuation() does for the daily chart's
                // today point. Leaving it out of both kept the intraday gain right but printed a
                // total lower than the daily chart's by the whole pocket, so switching to 24H read
                // as a drop that never happened.
                BigDecimal cash = account.getCashBalance() != null ? account.getCashBalance() : BigDecimal.ZERO;
                accountCash.put(accId, weigh(cash, shares, accId));
                accountHoldings.put(accId, holdingDataList);
                accountHoldingsInvested.put(accId, weigh(invested.add(cash), shares, accId));
            }
        }

        // Fetch intraday prices for all tickers. Guard per ticker: the price providers swallow
        // expected upstream failures and return an empty map, so anything thrown here is a bug
        // -- but letting it escape would 500 the whole intraday chart over one bad ticker.
        // Log it loudly, drop that ticker's series, and still render the rest.
        Map<String, NavigableMap<LocalDateTime, BigDecimal>> intradayPricesByTicker = new HashMap<>();
        for (String ticker : allTickers) {
            try {
                Map<LocalDateTime, BigDecimal> prices = priceService.getIntradayPricesEur(ticker, from, now);
                if (!prices.isEmpty()) {
                    intradayPricesByTicker.put(ticker, new TreeMap<>(prices));
                }
            } catch (Exception ex) {
                log.error("Intraday price fetch failed for {} -- omitting it from the chart", ticker, ex);
            }
        }

        // Generate hourly timestamps from `from` to `now`
        List<NetWorthIntradayPoint> result = new ArrayList<>();
        for (LocalDateTime ts = from.withMinute(0).withSecond(0).withNano(0);
             !ts.isAfter(now); ts = ts.plusHours(1)) {

            BigDecimal aggTotal = BigDecimal.ZERO;
            BigDecimal aggInvested = BigDecimal.ZERO;

            for (Account account : accounts) {
                Long accId = account.getId();
                List<HoldingData> holdings = accountHoldings.getOrDefault(accId, List.of());

                if (holdings.isEmpty()) {
                    // Bank/savings/loan account: constant balance
                    BigDecimal balance = accountBankBalance.getOrDefault(accId, BigDecimal.ZERO);
                    BigDecimal value = loanIds.contains(accId) ? balance.negate() : balance;
                    aggTotal = aggTotal.add(value);
                    if (!loanIds.contains(accId)) {
                        aggInvested = aggInvested.add(value);
                    }
                } else {
                    // Investment account: compute market value at this hour
                    BigDecimal marketValue = BigDecimal.ZERO;
                    for (HoldingData hd : holdings) {
                        if (hd.ticker == null) continue;
                        NavigableMap<LocalDateTime, BigDecimal> priceMap = intradayPricesByTicker.get(hd.ticker);
                        if (priceMap != null) {
                            var entry = priceMap.floorEntry(ts);
                            if (entry != null) {
                                marketValue = marketValue.add(hd.quantity.multiply(entry.getValue()));
                            }
                        }
                    }

                    // Weighted on the account total rather than per holding: rounding once
                    // keeps this consistent with the daily chart's per-account weighting.
                    marketValue = weigh(marketValue, shares, accId)
                        .add(accountCash.getOrDefault(accId, BigDecimal.ZERO));

                    // If no intraday price found, the positions are worth zero at that hour and
                    // only the cash pocket remains (skip)
                    if (loanIds.contains(accId)) {
                        aggTotal = aggTotal.subtract(marketValue);
                    } else {
                        aggTotal = aggTotal.add(marketValue);
                        aggInvested = aggInvested.add(accountHoldingsInvested.getOrDefault(accId, BigDecimal.ZERO));
                    }
                }
            }

            result.add(new NetWorthIntradayPoint(ts, aggTotal, aggInvested));
        }

        log.info("buildIntradayHistory: {} hourly points, {} accounts, {} tickers",
            result.size(), accounts.size(), allTickers.size());

        return result;
    }

    /**
     * Compute the live PnL for a set of accounts.
     * If a fromDate is provided, also computes the portfolio value at that date
     * using historical prices from price_snapshot, and returns range-based PnL.
     */
    public com.picsou.dto.PnlResponse buildPnl(List<Long> accountIds, Long memberId, LocalDate fromDate) {
        List<Account> accounts = accountRepository.findAllById(accountIds);
        if (accounts.isEmpty()) {
            return new com.picsou.dto.PnlResponse(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
        }

        Map<Long, BigDecimal> shares = assertReadable(accounts, memberId);

        // Live values. `liveTotal` stays NET WORTH (loans negated); pnl is computed
        // debt-neutrally from non-loan value only (issue #18).
        BigDecimal liveTotal = BigDecimal.ZERO;
        BigDecimal liveInvested = BigDecimal.ZERO;
        BigDecimal liveNonLoanValue = BigDecimal.ZERO;

        // Collect all holdings for historical lookup, remembering which account each came
        // from so the range PnL below can weight it by that account's share.
        List<AccountHolding> allHoldings = new ArrayList<>();
        Map<Long, Long> accountIdByHolding = new HashMap<>();

        for (Account account : accounts) {
            List<AccountHolding> holdings = holdingRepository.findByAccount_Id(account.getId());
            allHoldings.addAll(holdings);
            for (AccountHolding h : holdings) {
                accountIdByHolding.put(h.getId(), account.getId());
            }

            // One valuation per account, for the same reason as buildHistory above: the P&L
            // printed here is value minus cost, so the two must come from the same prices.
            AccountService.Valuation valuation = accountService.valuation(account);
            BigDecimal accLive = weigh(valuation.liveEur(), shares, account.getId());

            if (account.getType() == AccountType.LOAN) {
                liveTotal = liveTotal.subtract(accLive);
            } else {
                liveTotal = liveTotal.add(accLive);
                liveNonLoanValue = liveNonLoanValue.add(accLive);
                liveInvested = liveInvested.add(
                    weigh(valuation.investedEur(), shares, account.getId()));
            }
        }

        BigDecimal pnl = liveNonLoanValue.subtract(liveInvested);
        BigDecimal pnlPercent = liveInvested.compareTo(BigDecimal.ZERO) > 0
            ? pnl.multiply(BigDecimal.valueOf(100)).divide(liveInvested, 1, java.math.RoundingMode.HALF_UP)
            : null;

        // If no fromDate, return live PnL only
        if (fromDate == null || allHoldings.isEmpty()) {
            return new com.picsou.dto.PnlResponse(liveTotal, liveInvested, pnl, pnlPercent);
        }

        // Compute the range over holdings priced on BOTH sides (live and at fromDate,
        // with weekend/holiday fallback). Cash, loans and unmatched holdings are
        // excluded from both sides so rangePnl is pure portfolio performance.
        BigDecimal valueAtFrom = BigDecimal.ZERO;
        BigDecimal liveMatchedValue = BigDecimal.ZERO;
        int matchedPrices = 0;
        // Same ticker can appear across several accounts — look each price up once.
        Map<String, Optional<PriceSnapshot>> snapByTicker = new HashMap<>();
        Map<String, BigDecimal> livePriceByTicker = new HashMap<>();
        for (AccountHolding h : allHoldings) {
            String ticker = h.getTicker();
            if (ticker == null) continue;
            Optional<PriceSnapshot> snap = snapByTicker.computeIfAbsent(ticker,
                t -> priceSnapshotRepository.findLatestByTickerBeforeOrOnDate(t, fromDate));
            if (snap.isEmpty()) continue;
            if (!livePriceByTicker.containsKey(ticker)) {
                livePriceByTicker.put(ticker, priceService.getPriceEur(ticker));
            }
            BigDecimal livePrice = livePriceByTicker.get(ticker);
            if (livePrice == null) continue;
            // Both sides weighted by the same share, so the ratio -- and therefore the
            // percentage -- is unchanged; only the absolute figures shrink to the member's part.
            Long holdingAccountId = accountIdByHolding.get(h.getId());
            valueAtFrom = valueAtFrom.add(
                weigh(h.getQuantity().multiply(snap.get().getPriceEur()), shares, holdingAccountId));
            liveMatchedValue = liveMatchedValue.add(
                weigh(h.getQuantity().multiply(livePrice), shares, holdingAccountId));
            matchedPrices++;
        }

        if (matchedPrices == 0) {
            log.warn("buildPnl: no historical prices found for {} holdings at {}", allHoldings.size(), fromDate);
            return new com.picsou.dto.PnlResponse(liveTotal, liveInvested, pnl, pnlPercent);
        }

        // Range PnL: matched holdings' live value minus their value at the from date
        BigDecimal rangePnl = liveMatchedValue.subtract(valueAtFrom);
        BigDecimal rangePnlPercent = valueAtFrom.compareTo(BigDecimal.ZERO) > 0
            ? rangePnl.multiply(BigDecimal.valueOf(100)).divide(valueAtFrom, 1, java.math.RoundingMode.HALF_UP)
            : null;

        log.info("buildPnl: fromDate={} valueAtFrom={} liveMatchedValue={} rangePnl={} rangePnlPercent={}",
            fromDate, valueAtFrom, liveMatchedValue, rangePnl, rangePnlPercent);

        return new com.picsou.dto.PnlResponse(liveTotal, liveInvested, pnl, pnlPercent, valueAtFrom, rangePnl, rangePnlPercent);
    }

    public com.picsou.dto.PnlResponse buildPnl(List<Long> accountIds, Long memberId) {
        return buildPnl(accountIds, memberId, null);
    }

    /** Per-account forward-filled snapshot data. */
    private record ForwardFillData(
        NavigableSet<LocalDate> dates,
        Map<Long, NavigableMap<LocalDate, BigDecimal>> balanceByAccount,
        Map<Long, NavigableMap<LocalDate, BigDecimal>> investedByAccount
    ) {}

    private ForwardFillData buildPerAccountForwardFill(LocalDate from, List<Account> accounts) {
        List<Long> accountIds = accounts.stream().map(Account::getId).toList();
        List<Object[]> rows = snapshotRepository.findForwardFillDataByAccountIds(from, accountIds);

        Map<Long, NavigableMap<LocalDate, BigDecimal>> balanceByAccount = new HashMap<>();
        Map<Long, NavigableMap<LocalDate, BigDecimal>> investedByAccount = new HashMap<>();
        NavigableSet<LocalDate> allDates = new TreeSet<>();

        for (Object[] row : rows) {
            Long accId = (Long) row[0];
            LocalDate date = (LocalDate) row[1];
            BigDecimal balance = (BigDecimal) row[2];
            BigDecimal invested = (BigDecimal) row[3];
            balanceByAccount.computeIfAbsent(accId, k -> new TreeMap<>()).put(date, balance);
            if (invested != null) {
                investedByAccount.computeIfAbsent(accId, k -> new TreeMap<>()).put(date, invested);
            }
            allDates.add(date);
        }

        return new ForwardFillData(allDates, balanceByAccount, investedByAccount);
    }
}
