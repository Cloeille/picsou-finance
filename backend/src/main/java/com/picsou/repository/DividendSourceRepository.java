package com.picsou.repository;

import com.picsou.model.DividendSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DividendSourceRepository extends JpaRepository<DividendSource, Long> {

    Optional<DividendSource> findByTicker(String ticker);

    List<DividendSource> findByTickerIn(Collection<String> tickers);

    /** The forecast's read: sources and their ex-dates in one query rather than N+1. */
    @Query("""
        SELECT DISTINCT s FROM DividendSource s
        LEFT JOIN FETCH s.events
        WHERE s.ticker IN :tickers
        """)
    List<DividendSource> findAllWithEventsByTickerIn(@Param("tickers") Collection<String> tickers);
}
