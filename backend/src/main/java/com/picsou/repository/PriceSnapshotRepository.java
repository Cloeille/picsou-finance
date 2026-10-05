package com.picsou.repository;

import com.picsou.model.PriceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PriceSnapshotRepository extends JpaRepository<PriceSnapshot, Long> {

    Optional<PriceSnapshot> findByTickerAndDate(String ticker, LocalDate date);

    @Query("""
        SELECT ps FROM PriceSnapshot ps
        WHERE ps.ticker = :ticker AND ps.date <= :date
        ORDER BY ps.date DESC
        LIMIT 1
        """)
    Optional<PriceSnapshot> findLatestByTickerBeforeOrOnDate(
        @Param("ticker") String ticker,
        @Param("date") LocalDate date
    );

    /**
     * Rows for {@code tickers} within a short recent window, newest first per ticker.
     *
     * <p>Backs {@code PriceService}'s last-known-price fallback. JPQL has no {@code DISTINCT ON},
     * so the caller reduces to one row per ticker in memory — bounded work, since the window is a
     * handful of days and there is at most one row per ticker per day
     * ({@code uk_price_snapshot_ticker_date}). One query for the whole set is the point: the
     * fallback fires exactly when the price API is rate-limiting us, and a per-ticker query would
     * answer a request storm with a query storm.
     */
    @Query("""
        SELECT ps FROM PriceSnapshot ps
        WHERE ps.ticker IN :tickers AND ps.date BETWEEN :from AND :to
        ORDER BY ps.ticker, ps.date DESC
        """)
    List<PriceSnapshot> findRecentByTickers(
        @Param("tickers") Set<String> tickers,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to
    );

    @Query("""
        SELECT ps FROM PriceSnapshot ps
        WHERE ps.ticker IN :tickers AND ps.date BETWEEN :from AND :to
        ORDER BY ps.ticker, ps.date
        """)
    List<PriceSnapshot> findByTickerInAndDateBetween(
        @Param("tickers") Set<String> tickers,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to
    );

    /**
     * Which of {@code tickers} a price provider has ever answered for.
     *
     * <p>Used by {@code InstrumentLogoService} as proof that a ticker is a real quoted symbol
     * before spending a quote-page request on it: a fund code or a cash line no provider can
     * price never gets a row here.
     */
    @Query("SELECT DISTINCT ps.ticker FROM PriceSnapshot ps WHERE ps.ticker IN :tickers")
    Set<String> findPricedTickers(@Param("tickers") Set<String> tickers);

    @Modifying
    @Query("""
        DELETE FROM PriceSnapshot ps
        WHERE ps.ticker = :ticker AND ps.date = :date
        """)
    void deleteByTickerAndDate(@Param("ticker") String ticker, @Param("date") LocalDate date);

    @Modifying
    @Query("DELETE FROM PriceSnapshot")
    int deleteAllSnapshots();
}
