package com.picsou.repository;

import com.picsou.model.SavingsInterestConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface SavingsInterestConfigRepository extends JpaRepository<SavingsInterestConfig, Long> {

    Optional<SavingsInterestConfig> findByAccountId(Long accountId);

    /**
     * Inserts the default configuration only when the account has none. A concurrent
     * manual save wins without raising: catching a unique-constraint violation would
     * leave the surrounding snapshot transaction aborted on PostgreSQL, so the conflict
     * is resolved inside the statement.
     *
     * @return 1 when a row was created, 0 when one already existed
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        INSERT INTO savings_interest_config (account_id, product, annual_rate, rate_basis, ceiling)
        VALUES (:accountId, CAST(:product AS savings_product), :annualRate,
                CAST(:rateBasis AS rate_basis), :ceiling)
        ON CONFLICT (account_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
        @Param("accountId") Long accountId,
        @Param("product") String product,
        @Param("annualRate") BigDecimal annualRate,
        @Param("rateBasis") String rateBasis,
        @Param("ceiling") BigDecimal ceiling
    );
}
