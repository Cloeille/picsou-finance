package com.picsou.repository;

import com.picsou.model.AccountDisplayPref;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountDisplayPrefRepository extends JpaRepository<AccountDisplayPref, Long> {

    List<AccountDisplayPref> findByMemberId(Long memberId);

    Optional<AccountDisplayPref> findByMemberIdAndAccountId(Long memberId, Long accountId);

    /**
     * Atomic insert-or-update. Closes the check-then-insert race when two
     * requests create the row at once (double click, two tabs): a single
     * statement either inserts or merges, so the second writer never hits
     * the unique constraint. A NULL argument leaves that column as stored
     * (defaults on insert): each caller passes only the column it changes, so
     * a concurrent collapse and move never overwrite each other.
     */
    @Modifying
    @Query(value =
        "INSERT INTO account_display_pref (account_id, member_id, list_position, list_collapsed, created_at, updated_at) " +
        "VALUES (:accountId, :memberId, CAST(:position AS INTEGER), COALESCE(CAST(:collapsed AS BOOLEAN), FALSE), NOW(), NOW()) " +
        "ON CONFLICT (account_id, member_id) DO UPDATE SET " +
        "list_position = COALESCE(CAST(:position AS INTEGER), account_display_pref.list_position), " +
        "list_collapsed = COALESCE(CAST(:collapsed AS BOOLEAN), account_display_pref.list_collapsed), " +
        "updated_at = NOW()",
        nativeQuery = true)
    void upsertDisplayPref(@Param("accountId") Long accountId, @Param("memberId") Long memberId,
        @Param("position") Integer position, @Param("collapsed") Boolean collapsed);

    /**
     * Clears every position of the member (collapse states kept). Only called
     * for an explicit empty order — accounts absent from a non-empty payload
     * keep their positions.
     */
    @Modifying
    @Query(value =
        "UPDATE account_display_pref SET list_position = NULL, updated_at = NOW() WHERE member_id = :memberId",
        nativeQuery = true)
    void clearPositionsByMemberId(@Param("memberId") Long memberId);
}
