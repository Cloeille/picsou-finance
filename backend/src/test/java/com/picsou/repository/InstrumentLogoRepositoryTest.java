package com.picsou.repository;

import com.picsou.model.InstrumentLogo;
import com.picsou.model.InstrumentLogoStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two queries that decide whether a ticker is looked up again, and the one that decides
 * whether a holding gets a URL. All three live in JPQL, so a mocked repository would prove
 * nothing about them. Schema hand-rolled as in {@link TransactionRepositoryTest}.
 */
@DataJpaTest
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none"
})
@Sql("classpath:sql/instrument-logo-repository-test-schema.sql")
class InstrumentLogoRepositoryTest {

    private static final Instant RETRY_CUTOFF = Instant.parse("2026-09-24T00:00:00Z");

    @Autowired InstrumentLogoRepository repository;
    @Autowired PriceSnapshotRepository priceSnapshotRepository;

    @Test
    void settledTickers_includeStoredAndPermanentMisses_andRecentFailures() {
        Set<String> settled = repository.findSettledTickers(
            Set.of("AAPL", "MC.PA", "NOLOGO", "RECENT", "STALE", "NEVER"), RETRY_CUTOFF);

        // NOLOGO's miss is a year old and still settled: an ABSENT answer is never retried, which
        // is what stops an hourly pass from re-fetching the same empty page forever.
        assertThat(settled).containsExactlyInAnyOrder("AAPL", "MC.PA", "NOLOGO", "RECENT");
    }

    @Test
    void settledTickers_leaveAFailureOlderThanTheCutoffDueAgain() {
        assertThat(repository.findSettledTickers(Set.of("STALE"), RETRY_CUTOFF)).isEmpty();
    }

    @Test
    void storedRefs_carryOnlyStoredRows_andSayWhetherADarkVariantExists() {
        List<InstrumentLogoRepository.StoredLogoRef> refs = repository.findByStatusAndTickerIn(
            InstrumentLogoStatus.STORED, Set.of("AAPL", "MC.PA", "NOLOGO", "RECENT"));

        assertThat(refs).extracting(InstrumentLogoRepository.StoredLogoRef::getTicker)
            .containsExactlyInAnyOrder("AAPL", "MC.PA");
        assertThat(refs).filteredOn(r -> r.getTicker().equals("AAPL")).singleElement()
            .satisfies(r -> {
                assertThat(r.getContentTypeDark()).isEqualTo("image/png");
                assertThat(r.getFetchedAt()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
            });
        assertThat(refs).filteredOn(r -> r.getTicker().equals("MC.PA")).singleElement()
            .satisfies(r -> assertThat(r.getContentTypeDark()).isNull());
    }

    @Test
    void pricedTickers_areTheOnesAProviderHasAnsweredFor() {
        assertThat(priceSnapshotRepository.findPricedTickers(Set.of("AAPL", "IWDA.AS", "1RAKKPA")))
            .containsExactlyInAnyOrder("AAPL", "IWDA.AS");
    }

    @Test
    void aFailedRowIsUpdatedInPlaceWhenItLaterResolves() {
        InstrumentLogo stale = repository.findByTicker("STALE").orElseThrow();
        stale.setStatus(InstrumentLogoStatus.STORED);
        stale.setImage(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        stale.setContentType("image/png");
        stale.setAttemptedAt(Instant.parse("2026-10-01T10:00:00Z"));
        stale.setFetchedAt(Instant.parse("2026-10-01T10:00:00Z"));
        repository.saveAndFlush(stale);

        assertThat(repository.findByStatusAndTickerIn(InstrumentLogoStatus.STORED, Set.of("STALE")))
            .extracting(InstrumentLogoRepository.StoredLogoRef::getTicker)
            .containsExactly("STALE");
        assertThat(repository.count()).isEqualTo(5);
    }
}
