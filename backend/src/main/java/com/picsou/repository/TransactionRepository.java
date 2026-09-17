package com.picsou.repository;

import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByAccountIdOrderByDateDesc(Long accountId);

    Optional<Transaction> findByIdAndAccountId(Long id, Long accountId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.account.id = :accountId")
    BigDecimal sumAmountByAccountId(@Param("accountId") Long accountId);

    void deleteByAccountId(Long accountId);

    void deleteByAccountIdAndIsManualFalse(Long accountId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.account.id = :accountId AND t.date > :date")
    BigDecimal sumAmountByAccountIdAndDateAfter(@Param("accountId") Long accountId, @Param("date") LocalDate date);

    /**
     * Rows a bank importer must compare a freshly fetched window against. Manual rows are
     * excluded: a user who typed a transaction the bank also reports owns their own row, and
     * silently treating the import as a duplicate of it (or the reverse) would edit their ledger.
     *
     * <p>No {@code member_id} predicate — the account id is resolved from a member-scoped
     * lookup by the only caller ({@code BankTransactionImportService}, reached from
     * {@code SyncService.upsertAccount}), the same shape as the other per-account queries here.
     */
    List<Transaction> findByAccountIdAndIsManualFalseAndDateGreaterThanEqual(Long accountId, LocalDate date);

    /**
     * Newest already-imported entry for an account, which anchors the next sync's fetch window.
     * Null when nothing was ever imported — the caller then falls back to a full first-sync window.
     */
    @Query("SELECT MAX(t.date) FROM Transaction t WHERE t.account.id = :accountId AND t.isManual = false")
    LocalDate findLatestSyncedDateByAccountId(@Param("accountId") Long accountId);

    List<Transaction> findByAccountIdAndTxTypeInOrderByDateAscIdAsc(Long accountId, List<TransactionType> types);

    /** Earliest transaction date across all accounts */
    @Query("SELECT MIN(t.date) FROM Transaction t")
    LocalDate findEarliestDate();

    /**
     * Manually entered transactions of manual accounts whose ticker is 12 characters long — the
     * length of an ISIN, which the caller confirms with {@code OpenFigiIsinConverter.isIsin}
     * before touching anything. Such a ticker is one an earlier ISIN resolution failed to convert:
     * it can never be priced, since the Yahoo provider rejects ISIN-shaped strings outright.
     *
     * <p>Synced transactions and synced accounts are excluded on purpose: their adapters re-resolve
     * every ISIN on each sync, so they repair themselves without rewriting rows a provider owns.
     *
     * <p>No {@code member_id} predicate, unlike every request-scoped query: this feeds a startup
     * maintenance pass with no caller and no member context, in the same family as
     * {@code PriceFxCleanupRunner} (purges {@code price_snapshot} wholesale) and
     * {@code SchedulerService.dailySnapshots} (iterates every member). The tenant-isolation rule
     * protects paths where a caller's identity decides what may be read; a member loop here would
     * iterate all members and touch exactly the same rows.
     */
    @Query("SELECT t FROM Transaction t "
        + "WHERE t.isManual = true AND t.account.isManual = true AND LENGTH(t.ticker) = 12")
    List<Transaction> findManualTransactionsWithIsinLengthTicker();

    /**
     * Accounts holding the given manual tickers. Read before a repair pass rewrites those tickers,
     * so the accounts whose holdings need recomputing are known while they can still be found by
     * the old value. Ids rather than entities: the caller runs outside a transaction, where a lazy
     * {@code t.account} would not be readable.
     */
    @Query("SELECT DISTINCT t.account.id FROM Transaction t "
        + "WHERE t.isManual = true AND t.account.isManual = true AND t.ticker IN :tickers")
    List<Long> findManualAccountIdsByTickerIn(@Param("tickers") Collection<String> tickers);
}
