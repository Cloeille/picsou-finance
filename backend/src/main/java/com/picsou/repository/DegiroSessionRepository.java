package com.picsou.repository;

import com.picsou.model.DegiroSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DegiroSessionRepository extends JpaRepository<DegiroSession, Long> {
    Optional<DegiroSession> findByMemberId(Long memberId);
}
