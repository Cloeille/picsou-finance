package com.picsou.repository;

import com.picsou.model.SecurityProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SecurityProfileRepository extends JpaRepository<SecurityProfile, Long> {

    Optional<SecurityProfile> findByTicker(String ticker);

    /** The whole set the roll-up needs, slices included, in one query rather than N+1. */
    @Query("SELECT DISTINCT p FROM SecurityProfile p LEFT JOIN FETCH p.slices WHERE p.ticker IN :tickers")
    List<SecurityProfile> findAllWithSlicesByTickerIn(Collection<String> tickers);

    List<SecurityProfile> findByRefreshedAtBefore(Instant cutoff);
}
