package com.picsou.repository;

import com.picsou.model.SecurityProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface SecurityProfileRepository extends JpaRepository<SecurityProfile, Long> {

    Optional<SecurityProfile> findByTicker(String ticker);

    /** The whole set the roll-up needs, slices included, in one query rather than N+1. */
    @Query("SELECT DISTINCT p FROM SecurityProfile p LEFT JOIN FETCH p.slices WHERE p.ticker IN :tickers")
    List<SecurityProfile> findAllWithSlicesByTickerIn(Collection<String> tickers);

    List<SecurityProfile> findByRefreshedAtBefore(Instant cutoff);

    /**
     * Tickers in {@code tickers} that have a non-null ISIN. A crypto coin never has one.
     * The table is keyed on ticker and is not assumed unique, so this is a set, not a get.
     */
    @Query("""
        SELECT DISTINCT UPPER(p.ticker) FROM SecurityProfile p
        WHERE p.isin IS NOT NULL AND p.ticker IS NOT NULL AND UPPER(p.ticker) IN :tickers
        """)
    Set<String> findTickersWithNonNullIsin(@Param("tickers") Collection<String> tickers);
}
