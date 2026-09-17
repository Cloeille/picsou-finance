package com.picsou.service;

import com.picsou.model.Transaction;
import com.picsou.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** Persists one Fortuneo transaction-window replacement inside its caller's transaction. */
@Service
public class FortuneoTransactionWriter {
    private final TransactionRepository transactionRepository;

    public FortuneoTransactionWriter(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Deletes and reinserts the synchronized window as one atomic unit.
     *
     * <p>{@link Propagation#MANDATORY} prevents a future caller from accidentally moving this
     * replacement outside the portfolio transaction. {@code saveAllAndFlush} surfaces an insert
     * failure before control returns while preserving normal transaction rollback semantics.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceRecentTransactions(
        Long accountId,
        LocalDate cutoff,
        List<Transaction> replacements
    ) {
        transactionRepository.deleteByAccountIdAndIsManualFalseAndDateGreaterThanEqual(
            accountId,
            cutoff
        );
        transactionRepository.saveAllAndFlush(replacements);
    }

    /**
     * Applies a full-history reconciliation as one atomic unit: rows the provider no longer
     * reports inside the covered date range are dropped, then every reported row is inserted
     * or updated in place through its stable external id.
     *
     * <p>The delete is flushed before the upsert so a row being replaced releases its slot in
     * the {@code (account_id, external_id)} unique index before the replacement claims it.
     * {@link Propagation#MANDATORY} keeps the whole reconciliation inside the caller's
     * portfolio transaction: a failed insert rolls the deletes back with it, so a partial or
     * rejected response can never leave the account with less history than it started with.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcileHistory(List<Transaction> obsolete, List<Transaction> upserts) {
        if (!obsolete.isEmpty()) {
            transactionRepository.deleteAll(obsolete);
            transactionRepository.flush();
        }
        transactionRepository.saveAllAndFlush(upserts);
    }
}
