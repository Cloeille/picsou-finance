package com.picsou.repository;

import com.picsou.model.InstrumentLogo;
import com.picsou.model.InstrumentLogoStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface InstrumentLogoRepository extends JpaRepository<InstrumentLogo, Long> {

    Optional<InstrumentLogo> findByTicker(String ticker);

    /**
     * The tickers among {@code tickers} that must not be looked up again yet: every recorded
     * answer except a {@code FAILED} one older than {@code retryFailedBefore}.
     */
    @Query("""
        SELECT l.ticker FROM InstrumentLogo l
        WHERE l.ticker IN :tickers
          AND (l.status <> com.picsou.model.InstrumentLogoStatus.FAILED OR l.attemptedAt >= :retryFailedBefore)
        """)
    Set<String> findSettledTickers(@Param("tickers") Set<String> tickers,
                                   @Param("retryFailedBefore") Instant retryFailedBefore);

    /** Which of {@code tickers} have a mark, without loading any image bytes. */
    List<StoredLogoRef> findByStatusAndTickerIn(InstrumentLogoStatus status, Collection<String> tickers);

    /** Closed projection: Spring Data selects these three columns and nothing else. */
    interface StoredLogoRef {
        String getTicker();

        Instant getFetchedAt();

        /** Non-null exactly when a dark variant is stored. */
        String getContentTypeDark();
    }
}
