package com.picsou.repository;

import com.picsou.model.BalanceSnapshot;
import com.picsou.model.SnapshotSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BalanceSnapshotRepository extends JpaRepository<BalanceSnapshot, Long> {

    List<BalanceSnapshot> findByAccountIdAndDateBetweenOrderByDateAsc(
        Long accountId, LocalDate from, LocalDate to
    );

    List<BalanceSnapshot> findByAccountIdOrderByDateAsc(Long accountId);

    Optional<BalanceSnapshot> findByAccountIdAndDate(Long accountId, LocalDate date);

    /** Latest snapshot per account for dashboard aggregate */
    @Query("""
        SELECT s FROM BalanceSnapshot s
        WHERE s.account.id = :accountId
          AND s.date = (SELECT MAX(s2.date) FROM BalanceSnapshot s2 WHERE s2.account.id = :accountId)
        """)
    Optional<BalanceSnapshot> findLatestByAccountId(@Param("accountId") Long accountId);

    /** Aggregate daily net worth and invested capital: sum of all account balances per day */
    @Query(value = """
        SELECT s.date, SUM(s.balance), SUM(s.invested_amount)
        FROM balance_snapshot s
        WHERE s.date >= :from
        GROUP BY s.date
        ORDER BY s.date ASC
        """, nativeQuery = true)
    List<Object[]> findDailyNetWorth(@Param("from") LocalDate from);

    /** Raw snapshot data for forward-fill: account_id, date, balance, invested_amount */
    @Query("SELECT s.account.id, s.date, s.balance, s.investedAmount FROM BalanceSnapshot s WHERE s.date >= :from ORDER BY s.account.id, s.date")
    List<Object[]> findForwardFillData(@Param("from") LocalDate from);

    /** Raw snapshot data for forward-fill filtered by account IDs */
    @Query("SELECT s.account.id, s.date, s.balance, s.investedAmount FROM BalanceSnapshot s WHERE s.date >= :from AND s.account.id IN :accountIds ORDER BY s.account.id, s.date")
    List<Object[]> findForwardFillDataByAccountIds(@Param("from") LocalDate from, @Param("accountIds") List<Long> accountIds);

    /** Last 3 months of snapshots for a given account (for monthly contribution calc) */
    @Query("""
        SELECT s FROM BalanceSnapshot s
        WHERE s.account.id = :accountId
          AND s.date >= :from
        ORDER BY s.date ASC
        """)
    List<BalanceSnapshot> findRecentByAccountId(
        @Param("accountId") Long accountId,
        @Param("from") LocalDate from
    );

    /** Latest snapshot on or before a given date (for per-month contribution calc) */
    Optional<BalanceSnapshot> findFirstByAccountIdAndDateLessThanEqualOrderByDateDesc(
        Long accountId, LocalDate date
    );

    /** Latest hand-entered statement on or before a date (anchor for manual ledger balances). */
    Optional<BalanceSnapshot> findFirstByAccountIdAndSourceAndDateLessThanEqualOrderByDateDesc(
        Long accountId, SnapshotSource source, LocalDate date
    );
}
