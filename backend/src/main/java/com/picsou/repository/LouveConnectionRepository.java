package com.picsou.repository;

import com.picsou.model.LouveConnection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LouveConnectionRepository extends JpaRepository<LouveConnection, Long> {

    Optional<LouveConnection> findByMemberId(Long memberId);

    /**
     * The member's row, locked until the transaction ends. A refresh holds it: Louve rotates
     * refresh tokens, so two runs refreshing the same one would lose the session. The wait is
     * bounded ({@code lock_timeout}, the rest of the transaction included), so slow refreshes
     * cannot pile up waiters that each hold a pooled connection; a wait that runs out fails as a
     * {@link org.springframework.dao.PessimisticLockingFailureException}.
     *
     * <p>{@code SET LOCAL} rather than the {@code jakarta.persistence.lock.timeout} hint: Hibernate
     * does not turn a positive hint into a PostgreSQL wait limit, and the row lock then waited
     * for ever ({@code LouveConnectionPostgresTest} proves the bound).
     */
    default Optional<LouveConnection> findByMemberIdForUpdate(Long memberId) {
        boundLockWait();
        return lockByMemberId(memberId);
    }

    /** Five seconds, for this transaction only. */
    @Modifying
    @Query(value = "SET LOCAL lock_timeout = '5s'", nativeQuery = true)
    void boundLockWait();

    /** Use {@link #findByMemberIdForUpdate}: this one waits as long as the database lets it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM LouveConnection c WHERE c.member.id = :memberId")
    Optional<LouveConnection> lockByMemberId(@Param("memberId") Long memberId);
}
