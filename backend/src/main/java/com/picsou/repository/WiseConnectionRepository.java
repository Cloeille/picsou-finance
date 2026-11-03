package com.picsou.repository;

import com.picsou.model.WiseConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface WiseConnectionRepository extends JpaRepository<WiseConnection, Long> {

    Optional<WiseConnection> findByMemberId(Long memberId);

    /**
     * Takes a PostgreSQL advisory lock on this member's Wise connection until the surrounding
     * transaction ends. A second connect or sync for the same member waits here, so it reads the
     * token and jars the first one committed instead of racing it. Other members are unaffected.
     *
     * <p>The lock is on a key, not on the {@code wise_connection} row: {@code WiseStatusWriter}
     * updates that row from its own transaction while the holder is still open. Selecting from
     * the function sidesteps Hibernate's lack of a mapping for its {@code void} result.
     *
     * @return always {@code 1}; the call is made for its effect
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtext('wise_connection'), CAST(:memberId AS integer))",
        nativeQuery = true)
    int lockMember(@Param("memberId") Long memberId);
}
