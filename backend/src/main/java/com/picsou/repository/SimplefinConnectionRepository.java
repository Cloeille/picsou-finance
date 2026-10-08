package com.picsou.repository;

import com.picsou.model.SimplefinConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SimplefinConnectionRepository extends JpaRepository<SimplefinConnection, Long> {

    Optional<SimplefinConnection> findByMemberId(Long memberId);
}
