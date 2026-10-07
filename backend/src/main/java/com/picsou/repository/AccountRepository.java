package com.picsou.repository;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findAllByMemberIdOrderByCreatedAtAsc(Long memberId);
    List<Account> findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(Long memberId);
    Optional<Account> findByIdAndMemberId(Long id, Long memberId);

    /**
     * Member-scoped batch lookup by id. Used when resolving a caller-supplied list of
     * account ids (e.g. goal membership) so accounts belonging to another member are
     * never returned — closing an IDOR where {@code findAllById} ignored ownership.
     */
    List<Account> findByIdInAndMemberId(List<Long> ids, Long memberId);
    Optional<Account> findByExternalAccountIdAndMemberId(String externalAccountId, Long memberId);

    /**
     * Account-level tickers of live accounts of one type, for the hourly price warm-up — see
     * {@code SchedulerService}.
     *
     * <p>A projection rather than {@code findAll()}: one column is read, and loading every account
     * entity every hour to reach it is waste. The type split is what keeps a crypto account's own
     * ticker away from Yahoo Finance, which would answer with the share price of the equity
     * trading under the same symbol and record it in {@code price_snapshot}. Blank tickers are
     * excluded here so no caller has to remember to. {@code Account}'s {@code @SQLRestriction}
     * would cover the soft-delete predicate on its own; it is written out anyway so the intent
     * survives a refactor of that annotation.
     */
    @Query("""
        SELECT DISTINCT a.ticker FROM Account a
        WHERE a.ticker IS NOT NULL AND LENGTH(TRIM(a.ticker)) > 0
          AND a.deletedAt IS NULL AND a.type = :type
        """)
    Set<String> findDistinctTickersByType(@Param("type") AccountType type);

    /** {@link #findDistinctTickersByType} inverted: every live account that is <em>not</em> of that type. */
    @Query("""
        SELECT DISTINCT a.ticker FROM Account a
        WHERE a.ticker IS NOT NULL AND LENGTH(TRIM(a.ticker)) > 0
          AND a.deletedAt IS NULL AND a.type <> :type
        """)
    Set<String> findDistinctTickersExcludingType(@Param("type") AccountType type);

    /**
     * Returns true if any soft-deleted account exists with this external id for the member.
     * Bypasses {@code @SQLRestriction("deleted_at IS NULL")} on Account.
     * Used by sync upserts to refuse resurrecting accounts the user explicitly removed.
     */
    @Query(value =
        "SELECT EXISTS(SELECT 1 FROM account " +
        "  WHERE external_account_id = :externalId AND member_id = :memberId AND deleted_at IS NOT NULL)",
        nativeQuery = true)
    boolean existsSoftDeletedByExternalAccountIdAndMemberId(
        @Param("externalId") String externalId,
        @Param("memberId") Long memberId
    );

    /**
     * Find an active account by IBAN + member. Used as a stable match key when the
     * provider's uid changes (e.g. Enable Banking v0.16.4 identification hash rotation).
     * Only called when data.iban() is non-null; bypassed for accounts without an IBAN.
     */
    Optional<Account> findByIbanAndMemberId(String iban, Long memberId);

    /**
     * Returns true if any soft-deleted account exists with this IBAN for the member.
     * Bypasses {@code @SQLRestriction("deleted_at IS NULL")} on Account.
     * Companion to {@link #existsSoftDeletedByExternalAccountIdAndMemberId} for the IBAN path.
     */
    @Query(value =
        "SELECT EXISTS(SELECT 1 FROM account " +
        "  WHERE iban = :iban AND member_id = :memberId AND deleted_at IS NOT NULL)",
        nativeQuery = true)
    boolean existsSoftDeletedByIbanAndMemberId(
        @Param("iban") String iban,
        @Param("memberId") Long memberId
    );

    // ─── Revolut pockets (1.1.0) ──────────────────────────────────────────────

    /**
     * Find all Revolut wallet accounts for a member (provider = 'Revolut', no parent account).
     * Pocket sub-accounts are excluded (they carry a parent_account_id).
     */
    @Query("""
        SELECT a FROM Account a
        WHERE a.member.id = :memberId AND a.provider = 'Revolut'
        AND a.parentAccountId IS NULL
        """)
    List<Account> findRevolutWalletsByMemberId(@Param("memberId") Long memberId);

    /**
     * Lifts soft-delete tombstones for all Trade Republic accounts of a member.
     * Called on explicit re-authentication (completeAuth) so the upcoming sync can
     * find and update — rather than skip — previously-deleted accounts.
     * The scheduler-driven anti-resurrection guard in upsertAccount is intentionally
     * bypassed here because the user has actively chosen to reconnect.
     */
    @Modifying
    @Query(value =
        "UPDATE account SET deleted_at = NULL " +
        "WHERE member_id = :memberId AND provider = 'Trade Republic' AND deleted_at IS NOT NULL",
        nativeQuery = true)
    void restoreSoftDeletedTrAccounts(@Param("memberId") Long memberId);

    /**
     * Lifts soft-delete tombstones for all Revolut accounts of a member (wallets, pockets,
     * vaults). Called on explicit re-enrolment ({@code RevolutSyncService.completeEnrolment})
     * so the upcoming sync can find and update -- rather than skip -- previously-deleted
     * accounts. Mirrors {@link #restoreSoftDeletedTrAccounts}.
     */
    @Modifying
    @Query(value =
        "UPDATE account SET deleted_at = NULL " +
        "WHERE member_id = :memberId AND provider = 'Revolut' AND deleted_at IS NOT NULL",
        nativeQuery = true)
    void restoreSoftDeletedRevolutAccounts(@Param("memberId") Long memberId);

    /**
     * Lifts the soft-delete tombstone for a single Revolut account (by external id or, if
     * present, IBAN), mirroring the OR key used by the existence checks above. Used by the
     * manual sync + selection confirm path ({@code RevolutSyncService.confirmSync}) to restore
     * only the accounts the member explicitly selected, rather than the blanket per-provider
     * restore in {@link #restoreSoftDeletedRevolutAccounts} used on full reconnect.
     */
    @Modifying
    @Query(value =
        "UPDATE account SET deleted_at = NULL " +
        "WHERE member_id = :memberId AND provider = 'Revolut' AND deleted_at IS NOT NULL " +
        "AND (external_account_id = :externalId OR (:iban IS NOT NULL AND iban = :iban))",
        nativeQuery = true)
    void restoreSoftDeletedRevolutAccount(
        @Param("memberId") Long memberId,
        @Param("externalId") String externalId,
        @Param("iban") String iban
    );
}
