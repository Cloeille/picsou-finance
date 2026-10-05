package com.picsou.repository;

import com.picsou.model.CryptoExchangePosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CryptoExchangePositionRepository extends JpaRepository<CryptoExchangePosition, Long> {

    List<CryptoExchangePosition> findByAccountIdOrderByProductAscTickerAsc(Long accountId);

    /**
     * Deletes an account's positions <em>immediately</em>, unlike a derived {@code deleteBy…}.
     *
     * <p>Load-bearing for the delete-then-insert rewrite in {@code CryptoExchangeSyncService}: a
     * derived delete only queues removals, and Hibernate's action queue flushes inserts before
     * deletes, so re-inserting the same {@code (account, product, ticker)} in one transaction
     * collided with the rows still in the table — a unique-constraint violation on every sync
     * after the first. A JPQL bulk delete runs as a statement, so the rows are gone before any
     * insert. {@code clearAutomatically} drops the now-stale managed entities from the context.
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM CryptoExchangePosition p WHERE p.account.id = :accountId")
    void deleteAllForAccount(@Param("accountId") Long accountId);
}
