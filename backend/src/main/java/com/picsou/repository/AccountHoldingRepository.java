package com.picsou.repository;

import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AccountHoldingRepository extends JpaRepository<AccountHolding, Long> {

    List<AccountHolding> findByAccountIdOrderByCurrentPriceDesc(Long accountId);

    List<AccountHolding> findByAccount_Id(Long accountId);

    /**
     * Whether an account the member may read holds any line at all.
     *
     * <p>Separate from {@link #findByAccount_Id} because the aggregations only need the yes/no
     * to pick a branch, and the branch that says yes re-reads the lines through
     * {@code AccountService.getHoldings} anyway — materialising them twice per account.
     *
     * <p>The member predicate is deliberately <em>not</em> {@code h.account.member.id = :memberId}
     * alone. {@code Account.member} stores only the owning member, so that form would answer
     * "no holdings" for a joint account owned by someone else — a household's shared brokerage
     * account would silently drop out of the pyramid. Readability here means the same thing it
     * means in {@code AccountAccessResolver.readableAccounts}: owned, or co-owned through
     * {@code account_ownership}. Soft-deleted accounts are already excluded by the
     * {@code @SQLRestriction} on {@code Account}.
     */
    @Query("""
        SELECT CASE WHEN COUNT(h.id) > 0 THEN TRUE ELSE FALSE END
        FROM AccountHolding h
        WHERE h.account.id = :accountId
          AND (h.account.member.id = :memberId
               OR EXISTS (SELECT 1 FROM AccountOwnership o
                          WHERE o.account.id = :accountId AND o.member.id = :memberId))
        """)
    boolean existsForReadableAccount(@Param("accountId") Long accountId,
                                     @Param("memberId") Long memberId);

    Optional<AccountHolding> findByAccountIdAndTicker(Long accountId, String ticker);

    void deleteByAccountId(Long accountId);

    void deleteByAccountIdAndTickerNotIn(Long accountId, Collection<String> tickers);

    /**
     * Drops the holdings of {@code accountIds} that are keyed by one of {@code tickers}.
     *
     * <p>For the ISIN repair pass: {@code HoldingComputeService.recomputeHoldings} rebuilds a
     * holding for every ticker its transactions mention, but leaves alone one whose ticker they no
     * longer mention at all — deliberately, since a synced account owns holdings no transaction
     * backs. Renaming a ticker makes the old key exactly that kind of orphan, so the pass that
     * renames it is the one that has to remove it.
     */
    void deleteByAccountIdInAndTickerIn(Collection<Long> accountIds, Collection<String> tickers);

    /**
     * Every ticker held in a <em>live</em> account.
     *
     * <p>The join and the {@code deletedAt} filter are load-bearing: deleting an account only
     * stamps {@code deleted_at} ({@code AccountService.delete}), and its holdings stay behind.
     * Querying {@code AccountHolding} alone therefore keeps returning them forever — and both
     * callers spend a price-provider request per ticker, hourly, against free tiers that answer
     * bursts with 429s. {@code Account}'s {@code @SQLRestriction} would cover this on its own;
     * the predicate is written out anyway so the intent survives a refactor of that annotation.
     */
    @Query("""
        SELECT DISTINCT h.ticker FROM AccountHolding h
        JOIN h.account a
        WHERE h.ticker IS NOT NULL AND a.deletedAt IS NULL
        """)
    Set<String> findDistinctTickers();

    /** {@link #findDistinctTickers} narrowed to one account type — see {@code SchedulerService}. */
    @Query("""
        SELECT DISTINCT h.ticker FROM AccountHolding h
        JOIN h.account a
        WHERE h.ticker IS NOT NULL AND a.deletedAt IS NULL AND a.type = :type
        """)
    Set<String> findDistinctTickersByAccountType(@Param("type") AccountType type);
}
