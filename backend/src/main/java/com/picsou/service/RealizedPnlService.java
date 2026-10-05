package com.picsou.service;

import com.picsou.dto.RealizedPnlResponse;
import com.picsou.dto.RealizedPnlResponse.ClosedLot;
import com.picsou.dto.RealizedPnlResponse.TickerRealized;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes realized profit/loss on the fly from an account's ordered BUY/SELL transaction stream —
 * never persisted, mirroring {@code LoanAmortizationService} (the transaction stream is the source
 * of truth). Uses a moving-average (PMP) cost basis: each BUY adds {@code qty*price + fees} to the
 * cost pool; each SELL realizes {@code (qty*price - fees) - avgCost*qtySold}. Over-sells are clamped
 * (no fabricated negative cost) and flagged. All math is in the account's own currency — no re-pricing.
 *
 * <p>This is deliberately a separate series from the unrealized {@code HistoryService.buildPnl};
 * realized gains are never mixed into {@code pnl}/{@code rangePnl}.
 */
@Service
@RequiredArgsConstructor
public class RealizedPnlService {

    private static final int SCALE = 8;

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public RealizedPnlResponse compute(Long accountId, Long memberId) {
        Account account = accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.account(accountId));

        List<Transaction> stream = transactionRepository.findByAccountIdAndTxTypeInOrderByDateAscIdAsc(
            accountId, List.of(TransactionType.BUY, TransactionType.SELL));

        // Moving-average state per ticker.
        Map<String, BigDecimal> qtyHeld = new HashMap<>();
        Map<String, BigDecimal> costPool = new HashMap<>();
        Map<String, String> latestName = new HashMap<>();

        // Realized accumulators (LinkedHashMap keeps first-sell order for a stable response).
        Map<String, BigDecimal> realizedByTicker = new LinkedHashMap<>();
        Map<String, BigDecimal> qtySoldByTicker = new HashMap<>();
        Map<String, BigDecimal> proceedsByTicker = new HashMap<>();
        Map<String, BigDecimal> costByTicker = new HashMap<>();
        Set<String> warnings = new HashSet<>();
        List<ClosedLot> lots = new ArrayList<>();

        for (Transaction tx : stream) {
            String ticker = tx.getTicker();
            if (ticker == null || ticker.isBlank()) {
                continue;
            }
            BigDecimal qty = tx.getQuantity();
            if (qty == null || qty.signum() <= 0) {
                continue;
            }
            if (tx.getName() != null) {
                latestName.put(ticker, tx.getName());
            }
            BigDecimal price = tx.getPricePerUnit() != null ? tx.getPricePerUnit() : BigDecimal.ZERO;
            BigDecimal fees = tx.getFees() != null ? tx.getFees() : BigDecimal.ZERO;

            if (tx.getTxType() == TransactionType.BUY) {
                costPool.merge(ticker, qty.multiply(price).add(fees), BigDecimal::add);
                qtyHeld.merge(ticker, qty, BigDecimal::add);
                continue;
            }

            // SELL — realize against the moving-average cost.
            BigDecimal held = qtyHeld.getOrDefault(ticker, BigDecimal.ZERO);
            BigDecimal pool = costPool.getOrDefault(ticker, BigDecimal.ZERO);
            BigDecimal avgCost = held.signum() > 0
                ? pool.divide(held, SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

            boolean overSell = qty.compareTo(held) > 0;
            BigDecimal costedQty = overSell ? held.max(BigDecimal.ZERO) : qty;
            BigDecimal costOut = scale(avgCost.multiply(costedQty));
            BigDecimal proceeds = scale(qty.multiply(price).subtract(fees));
            BigDecimal realized = scale(proceeds.subtract(costOut));
            if (overSell) {
                warnings.add(ticker);
            }

            // Draw down the pool; never let held/pool go negative (no fabricated cost).
            BigDecimal newHeld = held.subtract(qty);
            qtyHeld.put(ticker, newHeld.signum() < 0 ? BigDecimal.ZERO : newHeld);
            BigDecimal newPool = pool.subtract(costOut);
            costPool.put(ticker, newPool.signum() < 0 ? BigDecimal.ZERO : newPool);

            lots.add(new ClosedLot(ticker, latestName.get(ticker), tx.getDate(), qty, avgCost, proceeds, realized));
            realizedByTicker.merge(ticker, realized, BigDecimal::add);
            qtySoldByTicker.merge(ticker, qty, BigDecimal::add);
            proceedsByTicker.merge(ticker, proceeds, BigDecimal::add);
            costByTicker.merge(ticker, costOut, BigDecimal::add);
        }

        List<TickerRealized> byTicker = new ArrayList<>();
        BigDecimal realizedTotal = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : realizedByTicker.entrySet()) {
            String ticker = e.getKey();
            realizedTotal = realizedTotal.add(e.getValue());
            byTicker.add(new TickerRealized(
                ticker,
                latestName.get(ticker),
                e.getValue(),
                qtySoldByTicker.get(ticker),
                proceedsByTicker.get(ticker),
                costByTicker.get(ticker),
                warnings.contains(ticker)));
        }

        return new RealizedPnlResponse(account.getCurrency(), scale(realizedTotal), byTicker, lots);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
