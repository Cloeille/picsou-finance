package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.TransactionRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class HoldingComputeService {

    private final TransactionRepository transactionRepository;
    private final AccountHoldingRepository accountHoldingRepository;

    @Transactional
    public void recomputeHoldings(Account account) {
        List<Transaction> transactions = transactionRepository
                .findByAccountIdAndTxTypeInOrderByDateAscIdAsc(
                        account.getId(),
                        List.of(TransactionType.BUY, TransactionType.SELL));

        // Per-ticker accumulators
        Map<String, BigDecimal> netQuantity = new HashMap<>();
        Map<String, BigDecimal> vwapNumerator = new HashMap<>();   // Σ(qty_i × price_i) for BUY transactions
        Map<String, BigDecimal> vwapDenominator = new HashMap<>();  // Σ(qty_i) for BUY transactions
        // Most-recent human-readable name per ticker. Transactions arrive date-ASC,
        // so the last write for a ticker is its newest name.
        Map<String, String> latestName = new HashMap<>();

        for (Transaction tx : transactions) {
            String ticker = tx.getTicker();
            if (ticker == null || ticker.isBlank()) {
                continue;
            }
            if (tx.getName() != null) {
                latestName.put(ticker, tx.getName());
            }
            BigDecimal qty = tx.getQuantity();
            if (qty == null) {
                continue;
            }

            if (tx.getTxType() == TransactionType.BUY) {
                netQuantity.merge(ticker, qty, BigDecimal::add);
                // null price treated as 0; averageBuyIn will be 0, which is intentional
                BigDecimal price = tx.getPricePerUnit() != null ? tx.getPricePerUnit() : BigDecimal.ZERO;
                // Fees fold into the VWAP numerator (PMP cost basis), null fees treated as 0.
                BigDecimal fees = tx.getFees() != null ? tx.getFees() : BigDecimal.ZERO;
                vwapNumerator.merge(ticker, qty.multiply(price).add(fees), BigDecimal::add);
                vwapDenominator.merge(ticker, qty, BigDecimal::add);
            } else { // SELL
                netQuantity.merge(ticker, qty.negate(), BigDecimal::add);
            }
        }

        Map<String, AccountHolding> existingByTicker =
            accountHoldingRepository.findByAccount_Id(account.getId())
                .stream()
                .collect(java.util.stream.Collectors.toMap(AccountHolding::getTicker, h -> h));

        List<AccountHolding> toSave = new ArrayList<>();
        List<AccountHolding> toDelete = new ArrayList<>();

        for (Map.Entry<String, BigDecimal> entry : netQuantity.entrySet()) {
            String ticker = entry.getKey();
            BigDecimal qty = entry.getValue();

            Optional<AccountHolding> existing = Optional.ofNullable(existingByTicker.get(ticker));

            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                existing.ifPresent(toDelete::add);
            } else {
                BigDecimal denominator = vwapDenominator.getOrDefault(ticker, BigDecimal.ZERO);
                BigDecimal averageBuyIn = null;
                if (denominator.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal numerator = vwapNumerator.getOrDefault(ticker, BigDecimal.ZERO);
                    averageBuyIn = numerator.divide(denominator, 8, RoundingMode.HALF_UP);
                }

                AccountHolding holding = existing.orElseGet(() -> AccountHolding.builder()
                        .account(account)
                        .ticker(ticker)
                        .build());
                holding.setQuantity(qty);
                holding.setAverageBuyIn(averageBuyIn);
                // Only overwrite the label when a transaction actually carries a name,
                // so a nameless manual entry never erases a name set by bank sync.
                String name = latestName.get(ticker);
                if (name != null) {
                    holding.setName(name);
                }
                toSave.add(holding);
            }
        }

        accountHoldingRepository.deleteAll(toDelete);
        accountHoldingRepository.saveAll(toSave);
    }
}
