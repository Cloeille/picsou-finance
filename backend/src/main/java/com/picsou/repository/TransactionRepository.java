package com.picsou.repository;

import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByAccountIdOrderByDateDesc(Long accountId);

    /** Bulk-update the merchant label for all transactions of an account (pocket rename). */
    @Modifying
    @Query("UPDATE Transaction t SET t.merchantLabel = :label WHERE t.account.id = :accountId")
    void updateMerchantLabelByAccountId(@Param("accountId") Long accountId, @Param("label") String label);

    /**
     * Bulk-update the merchant label for all wallet transactions whose description matches
     * the pocket's UUID pattern (wallet side of pocket transfers, e.g. "To EUR MB:<uuid>").
     */
    @Modifying
    @Query("""
        UPDATE Transaction t SET t.merchantLabel = :label
        WHERE t.account.id = :walletAccountId
        AND LOWER(t.description) LIKE LOWER(CONCAT('%mb:', :pocketUuid))
        """)
    void updateMerchantLabelForPocketWalletSide(
        @Param("walletAccountId") Long walletAccountId,
        @Param("pocketUuid") String pocketUuid,
        @Param("label") String label);

    Optional<Transaction> findByIdAndAccountId(Long id, Long accountId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.account.id = :accountId")
    BigDecimal sumAmountByAccountId(@Param("accountId") Long accountId);

    void deleteByAccountId(Long accountId);

    void deleteByAccountIdAndIsManualFalse(Long accountId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.account.id = :accountId AND t.date > :date")
    BigDecimal sumAmountByAccountIdAndDateAfter(@Param("accountId") Long accountId, @Param("date") LocalDate date);

    List<Transaction> findByAccountIdAndTxTypeInOrderByDateAscIdAsc(Long accountId, List<TransactionType> types);

    /**
     * Every transaction on a set of accounts over a date window.
     *
     * <p>Member scoping is the caller's: it passes the ids it already resolved through
     * {@code AccountAccessResolver.readableAccounts}, which is the only place allowed to decide
     * what a member may see. Ordering by date keeps the counterparty-matching pass linear.
     */
    List<Transaction> findByAccountIdInAndDateBetweenOrderByDateAsc(
        Collection<Long> accountIds, LocalDate from, LocalDate to);

    /** Earliest transaction date across all accounts */
    @Query("SELECT MIN(t.date) FROM Transaction t")
    LocalDate findEarliestDate();

    // ─── Budget & Cashflow (1.1.0) ────────────────────────────────────────────

    /** Dedup guard for synced ingestion (account-scoped). */
    boolean existsByAccountIdAndExternalId(Long accountId, String externalId);

    /** Member-scoped single transaction lookup (categorize endpoint). */
    Optional<Transaction> findByIdAndAccountMemberId(Long id, Long memberId);

    /** Member-scoped batch lookup by id set — used by the async AI job. */
    List<Transaction> findAllByIdInAndAccountMemberId(Collection<Long> ids, Long memberId);

    /** Transactions belonging to a member that have no managed category yet. */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.categoryRef IS NULL
        ORDER BY t.date DESC, t.id DESC
        """)
    List<Transaction> findUncategorizedByMemberId(@Param("memberId") Long memberId);

    /**
     * The member's most recent hand/auto-categorized, labelled transactions — used as few-shot
     * examples for the AI categorizer so it learns the member's taxonomy from their own history.
     * Pass a {@code Pageable} (e.g. {@code PageRequest.of(0, 8)}) to bound the count.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.categoryRef IS NOT NULL AND t.merchantLabel IS NOT NULL
        ORDER BY t.date DESC, t.id DESC
        """)
    List<Transaction> findRecentCategorizedByMemberId(@Param("memberId") Long memberId,
                                                      org.springframework.data.domain.Pageable pageable);

    /** All member transactions in a date range (cashflow / detection input). */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.date BETWEEN :from AND :to
        ORDER BY t.date ASC, t.id ASC
        """)
    List<Transaction> findByMemberIdAndDateBetween(@Param("memberId") Long memberId,
                                                   @Param("from") LocalDate from,
                                                   @Param("to") LocalDate to);

    /** Cross-account search with optional account and category filters, newest first. */
    @Query("""
        SELECT t FROM Transaction t
        JOIN FETCH t.account
        LEFT JOIN FETCH t.categoryRef
        WHERE t.account.member.id = :memberId
        AND t.date BETWEEN :from AND :to
        AND (:accountId IS NULL OR t.account.id = :accountId)
        AND (:categoryId IS NULL OR t.categoryRef.id = :categoryId)
        ORDER BY t.date DESC, t.id DESC
        """)
    List<Transaction> searchByMember(@Param("memberId") Long memberId,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to,
                                     @Param("accountId") Long accountId,
                                     @Param("categoryId") Long categoryId);

    /** One category's transactions for a member over a range, newest first (spending drill). */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.categoryRef.id = :categoryId
        AND t.date BETWEEN :from AND :to
        ORDER BY t.date DESC, t.id DESC
        """)
    List<Transaction> findByMemberIdAndCategoryIdAndDateBetween(@Param("memberId") Long memberId,
                                                                @Param("categoryId") Long categoryId,
                                                                @Param("from") LocalDate from,
                                                                @Param("to") LocalDate to);

    /** Sum of (signed) amounts for one category over a date range — used by envelopes. */
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.categoryRef.id = :categoryId AND t.date BETWEEN :from AND :to
        """)
    BigDecimal sumByCategoryIdAndDateBetween(@Param("categoryId") Long categoryId,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);

    /** A member's transactions across several categories over a range, newest first (parent drill). */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.categoryRef.id IN :categoryIds
        AND t.date BETWEEN :from AND :to
        ORDER BY t.date DESC, t.id DESC
        """)
    List<Transaction> findByMemberIdAndCategoryIdInAndDateBetween(@Param("memberId") Long memberId,
                                                                  @Param("categoryIds") Collection<Long> categoryIds,
                                                                  @Param("from") LocalDate from,
                                                                  @Param("to") LocalDate to);

    /** Sum of (signed) amounts across several categories over a range — parent envelope rollup. */
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.categoryRef.id IN :categoryIds AND t.date BETWEEN :from AND :to
        """)
    BigDecimal sumByCategoryIdInAndDateBetween(@Param("categoryIds") Collection<Long> categoryIds,
                                               @Param("from") LocalDate from,
                                               @Param("to") LocalDate to);

    /** Sum of (signed) amounts for a member, filtered by category kind, over a range. */
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.categoryRef.kind = :kind
        AND t.date BETWEEN :from AND :to
        """)
    BigDecimal sumByMemberIdAndKindAndDateBetween(@Param("memberId") Long memberId,
                                                  @Param("kind") com.picsou.model.CategoryKind kind,
                                                  @Param("from") LocalDate from,
                                                  @Param("to") LocalDate to);

    /** Member transactions with a given category kind in a range (allocation flux). */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.categoryRef.kind = :kind
        AND t.date BETWEEN :from AND :to
        """)
    List<Transaction> findByMemberIdAndKindAndDateBetween(@Param("memberId") Long memberId,
                                                          @Param("kind") com.picsou.model.CategoryKind kind,
                                                          @Param("from") LocalDate from,
                                                          @Param("to") LocalDate to);

    /** Detach all transactions from a category before it is hard-removed/archived. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Transaction t SET t.categoryRef = NULL WHERE t.categoryRef.id = :categoryId")
    void clearCategory(@Param("categoryId") Long categoryId);

    // ─── Rule preview ────────────────────────────────────────────────────────

    /**
     * All member transactions that could be changed by a new rule:
     * those without a category or with a non-manual (auto/brand/AI) category assignment.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId
          AND (t.categoryRef IS NULL OR t.categoryManual = false)
        ORDER BY t.date DESC, t.id DESC
        """)
    List<Transaction> findChangeable(@Param("memberId") Long memberId);

    // ─── Savings livrets (1.1.0) ──────────────────────────────────────────────

    /**
     * All transactions for a specific account in a date range, oldest first.
     * Used by the savings interest projection engine to replay the capital history.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.id = :accountId
          AND t.date BETWEEN :from AND :to
        ORDER BY t.date ASC, t.id ASC
        """)
    List<Transaction> findByAccountIdAndDateBetweenOrderByDateAsc(
        @Param("accountId") Long accountId,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to
    );

    // ─── Revolut pockets (1.1.0) ─────────────────────────────────────────────

    /**
     * All transactions for an account ordered by date (oldest first), used for backfill.
     * Member-scoped via account ownership.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.id = :accountId
        ORDER BY t.date ASC, t.id ASC
        """)
    List<Transaction> findByAccountIdOrderByDateAsc(@Param("accountId") Long accountId);

    /**
     * Transactions for a specific pocket account (by id), newest first.
     * Used by the unnamed-pockets listing to show recent inflows.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.id = :accountId
        ORDER BY t.date DESC, t.id DESC
        """)
    List<Transaction> findTopByAccountIdOrderByDateDesc(
        @Param("accountId") Long accountId,
        org.springframework.data.domain.Pageable pageable
    );

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
