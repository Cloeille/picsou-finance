package com.picsou.repository;

import com.picsou.model.AiCallLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiCallLogRepository extends JpaRepository<AiCallLog, Long> {

    Page<AiCallLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("select coalesce(sum(a.totalTokens),0) from AiCallLog a")
    long sumTotalTokens();

    @Modifying
    @Query(value = "DELETE FROM ai_call_log WHERE id NOT IN (SELECT id FROM ai_call_log ORDER BY created_at DESC LIMIT :keep)", nativeQuery = true)
    void pruneKeepingNewest(@Param("keep") int keep);
}
