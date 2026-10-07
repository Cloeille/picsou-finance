package com.picsou.repository;

import com.picsou.model.SavingsInterestConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SavingsInterestConfigRepository extends JpaRepository<SavingsInterestConfig, Long> {

    Optional<SavingsInterestConfig> findByAccountId(Long accountId);
}
