package com.picsou.repository;

import com.picsou.model.WiseConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WiseConnectionRepository extends JpaRepository<WiseConnection, Long> {

    Optional<WiseConnection> findByMemberId(Long memberId);
}
