package com.picsou.repository;

import com.picsou.model.BudgetSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BudgetSettingsRepository extends JpaRepository<BudgetSettings, Long> {

    Optional<BudgetSettings> findByMemberId(Long memberId);
}
