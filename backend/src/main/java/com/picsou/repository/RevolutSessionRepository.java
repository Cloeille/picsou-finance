package com.picsou.repository;

import com.picsou.model.RevolutSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RevolutSessionRepository extends JpaRepository<RevolutSession, Long> {

    Optional<RevolutSession> findByMemberId(Long memberId);

    Optional<RevolutSession> findTopByOrderByCreatedAtDesc();
}
