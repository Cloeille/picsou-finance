package com.picsou.repository;

import com.picsou.model.ScpiPosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ScpiPositionRepository extends JpaRepository<ScpiPosition, Long> {
    Optional<ScpiPosition> findByAccountId(Long accountId);
}
