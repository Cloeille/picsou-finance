package com.picsou.repository;

import com.picsou.model.CryptoAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CryptoAssetRepository extends JpaRepository<CryptoAsset, String> {

    /**
     * Inserts an AUTO row unless the ticker exists; returns the rows inserted (0 or 1). A single
     * statement, so a concurrent insert is a no-op rather than a constraint violation that would
     * mark the surrounding transaction rollback-only.
     */
    @Modifying
    @Query(value = """
        INSERT INTO crypto_asset (ticker, coingecko_id, name, source)
        VALUES (:ticker, :id, :name, 'AUTO')
        ON CONFLICT (ticker) DO NOTHING
        """, nativeQuery = true)
    int insertAutoIfAbsent(@Param("ticker") String ticker, @Param("id") String id, @Param("name") String name);
}
