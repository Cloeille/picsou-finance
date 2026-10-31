package com.picsou.service;

import com.picsou.dto.EquityProfile;
import com.picsou.dto.EtfComposition;
import com.picsou.dto.SecurityInsightResponse;
import com.picsou.dto.WeightedSlice;
import com.picsou.model.SecurityProfile;
import com.picsou.model.SecuritySliceKind;
import com.picsou.port.EquityProfileProvider;
import com.picsou.repository.SecurityProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import com.picsou.model.SecurityCompositionSlice;
import com.picsou.model.SecurityProfileStatus;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecurityProfileServiceTest {

    @Mock SecurityProfileRepository repository;
    @Mock SecurityInsightService insightService;
    @Mock SecurityIdentityService identityService;

    @BeforeEach
    void echoSaves() {
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(repository.findByTicker(any())).thenReturn(Optional.empty());
    }

    /** Hand-written fakes rather than mocks, as SecurityInsightServiceTest does for its port. */
    private static EquityProfileProvider fake(String sector, String country, String source) {
        return new EquityProfileProvider() {
            @Override public boolean supports(String ticker) { return true; }
            @Override public Optional<EquityProfile> fetch(String ticker) {
                return Optional.of(new EquityProfile(sector, country, source, country != null));
            }
        };
    }

    private static EquityProfileProvider silent() {
        return new EquityProfileProvider() {
            @Override public boolean supports(String ticker) { return true; }
            @Override public Optional<EquityProfile> fetch(String ticker) { return Optional.empty(); }
        };
    }

    /**
     * Runs callbacks straight through while counting boundaries, so a test can assert that each
     * ticker gets its own transaction instead of sharing one across a network-bound loop.
     * Same shape as {@code PropertyValuationServiceTest}'s.
     */
    private static final class CountingTxManager implements PlatformTransactionManager {
        int started;
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            started++;
            return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) { }
        @Override public void rollback(TransactionStatus status) { }
    }

    private CountingTxManager txManager = new CountingTxManager();

    private SecurityProfileService serviceWith(EquityProfileProvider... providers) {
        return new SecurityProfileService(repository, insightService, identityService,
            List.of(providers), new TransactionTemplate(txManager));
    }

    @Test
    void mergesTheEquityProvidersFieldByField() {
        // The decisive behaviour: no single source has both halves. Yahoo answers the sector and
        // only Boursorama has the ISIN the country comes from, so first-provider-wins would
        // throw the country away every time the sector arrived first.
        when(insightService.getInsight("AI.PA", null, null))
            .thenReturn(new SecurityInsightResponse("AI.PA", "STOCK", null));

        SecurityProfile saved = serviceWith(
            fake("basic_materials", null, "Yahoo Finance"),
            fake(null, "FR", "Boursorama")
        ).refresh("AI.PA");

        assertThat(saved.getSectorKey()).isEqualTo("basic_materials");
        assertThat(saved.getCountryKey()).isEqualTo("FR");
        assertThat(saved.getSource()).contains("Yahoo Finance").contains("Boursorama");
    }

    @Test
    void skipsAProviderThatKnowsNothingAndKeepsAsking() {
        when(insightService.getInsight("AI.PA", null, null))
            .thenReturn(new SecurityInsightResponse("AI.PA", "STOCK", null));

        SecurityProfile saved = serviceWith(silent(), fake("technology", "US", "Yahoo Finance"))
            .refresh("AI.PA");

        assertThat(saved.getSectorKey()).isEqualTo("technology");
    }

    @Test
    void anEtfIsStoredAsSlicesRatherThanASingleSector() {
        when(insightService.getInsight("CW8.PA", null, null)).thenReturn(new SecurityInsightResponse(
            "CW8.PA", "ETF", new EtfComposition(
                List.of(new WeightedSlice("Apple", new BigDecimal("5.1"))),
                List.of(new WeightedSlice("US", new BigDecimal("70"))),
                List.of(new WeightedSlice("technology", new BigDecimal("24"))),
                "Boursorama", null)));

        SecurityProfile saved = serviceWith(fake("technology", "FR", "Yahoo")).refresh("CW8.PA");

        assertThat(saved.getSectorKey()).isNull();
        assertThat(saved.getCountryKey()).isNull();
        assertThat(saved.getSlices()).hasSize(3);
        assertThat(saved.getSlices()).anyMatch(s -> s.getKind() == SecuritySliceKind.COUNTRY);
        assertThat(saved.getSlices()).anyMatch(s -> s.getKind() == SecuritySliceKind.SECTOR);
    }

    @Test
    void aRepeatedLabelDoesNotBreakTheWholeSave() {
        // The unique key is (profile, kind, label); a provider echoing a label twice must lose
        // the duplicate line, not the entire security.
        when(insightService.getInsight("DUP", null, null)).thenReturn(new SecurityInsightResponse(
            "DUP", "ETF", new EtfComposition(List.of(), List.of(),
                List.of(new WeightedSlice("technology", new BigDecimal("20")),
                        new WeightedSlice("technology", new BigDecimal("30"))),
                "Boursorama", null)));

        SecurityProfile saved = serviceWith().refresh("DUP");

        assertThat(saved.getSlices()).hasSize(1);
    }

    @Test
    void anUnknownSecurityIsStillRecordedSoItIsNotRetriedEveryWeek() {
        when(insightService.getInsight("XYZ", null, null))
            .thenReturn(new SecurityInsightResponse("XYZ", "UNKNOWN", null));

        SecurityProfile saved = serviceWith().refresh("XYZ");

        assertThat(saved.getAssetType()).isEqualTo("UNKNOWN");
        assertThat(saved.getRefreshedAt()).isNotNull();
        assertThat(saved.getSlices()).isEmpty();
    }

    @Test
    void refreshesOnlyWhatIsStaleAndStopsAtTheBatchCap() {
        SecurityProfile fresh = SecurityProfile.builder()
            .ticker("FRESH").assetType("STOCK").refreshedAt(Instant.now()).build();
        when(repository.findAllWithSlicesByTickerIn(any())).thenReturn(List.of(fresh));
        when(insightService.getInsight(any(), any(), any()))
            .thenReturn(new SecurityInsightResponse("X", "UNKNOWN", null));

        int refreshed = serviceWith().refreshStale(List.of("FRESH", "STALE", "OTHER"), 1);

        assertThat(refreshed).isEqualTo(1);
        verify(insightService, never()).getInsight(eq("FRESH"), any(), any());
    }

    @Test
    void oneBadTickerDoesNotAbortThePass() {
        when(repository.findAllWithSlicesByTickerIn(any())).thenReturn(List.of());
        when(insightService.getInsight(eq("BOOM"), any(), any())).thenThrow(new RuntimeException("provider down"));
        when(insightService.getInsight(eq("FINE"), any(), any()))
            .thenReturn(new SecurityInsightResponse("FINE", "UNKNOWN", null));

        assertThat(serviceWith().refreshStale(List.of("BOOM", "FINE"), 10)).isEqualTo(1);
    }

    @Test
    void aFailedLookupKeepsTheLastGoodProfileInsteadOfWipingIt() {
        SecurityProfile existing = SecurityProfile.builder()
            .ticker("CW8.PA").assetType("ETF").source("Boursorama")
            .asOf(LocalDate.of(2026, 6, 30))
            .refreshedAt(Instant.parse("2026-08-01T00:00:00Z"))
            .status(SecurityProfileStatus.OK)
            .slices(new ArrayList<>(List.of(SecurityCompositionSlice.builder()
                .kind(SecuritySliceKind.COUNTRY).label("US").percent(new BigDecimal("69.70")).build())))
            .build();
        when(repository.findByTicker("CW8.PA")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(insightService.getInsight(eq("CW8.PA"), any(), any()))
            .thenThrow(new RuntimeException("boursorama 502"));

        SecurityProfile saved = serviceWith().refresh("CW8.PA");

        // The whole point. A transient scrape failure used to call replaceSlices(List.of()) and
        // stamp refreshedAt anyway, so a good look-through was destroyed and not retried for a
        // month, while source/asOf still advertised the last success.
        assertThat(saved.getSlices()).hasSize(1);
        assertThat(saved.getSource()).isEqualTo("Boursorama");
        assertThat(saved.getAsOf()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(saved.getRefreshedAt()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(saved.getStatus()).isEqualTo(SecurityProfileStatus.FAILED);
        assertThat(saved.getLastError()).contains("502");
        assertThat(saved.getLastAttemptAt()).isNotNull();
    }

    @Test
    void aFailureComesBackRoundInDaysWhereASuccessWaitsAMonth() {
        Instant now = Instant.now();
        SecurityProfile failedYesterday = SecurityProfile.builder()
            .ticker("A").assetType("ETF").status(SecurityProfileStatus.FAILED)
            .refreshedAt(now.minus(Duration.ofDays(2)))
            .lastAttemptAt(now.minus(Duration.ofDays(1))).build();
        SecurityProfile failedLastWeek = SecurityProfile.builder()
            .ticker("B").assetType("ETF").status(SecurityProfileStatus.FAILED)
            .refreshedAt(now.minus(Duration.ofDays(8)))
            .lastAttemptAt(now.minus(Duration.ofDays(7))).build();
        SecurityProfile okLastWeek = SecurityProfile.builder()
            .ticker("C").assetType("ETF").status(SecurityProfileStatus.OK)
            .refreshedAt(now.minus(Duration.ofDays(7)))
            .lastAttemptAt(now.minus(Duration.ofDays(7))).build();
        SecurityProfile seeded = SecurityProfile.builder()
            .ticker("D").assetType("UNKNOWN").isin("LU1681043599")
            .status(SecurityProfileStatus.NEVER_FETCHED).build();
        when(repository.findAllWithSlicesByTickerIn(any()))
            .thenReturn(List.of(failedYesterday, failedLastWeek, okLastWeek, seeded));
        when(insightService.getInsight(any(), any(), any()))
            .thenReturn(new SecurityInsightResponse("X", "UNKNOWN", null));
        when(repository.findByTicker(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        serviceWith().refreshStale(List.of("A", "B", "C", "D"), 10);

        // B is retried (failed a week ago, past the 3-day retry) and D is due immediately as a
        // seeded row. A failed yesterday so it waits; C succeeded a week ago so it waits a month.
        verify(insightService, never()).getInsight(eq("A"), any(), any());
        verify(insightService).getInsight(eq("B"), any(), any());
        verify(insightService, never()).getInsight(eq("C"), any(), any());
        verify(insightService).getInsight(eq("D"), any(), any());
    }

    @Test
    void eachTickerGetsItsOwnTransaction() {
        when(repository.findAllWithSlicesByTickerIn(any())).thenReturn(List.of());
        when(insightService.getInsight(any(), any(), any()))
            .thenReturn(new SecurityInsightResponse("X", "UNKNOWN", null));
        when(repository.findByTicker(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        serviceWith().refreshStale(List.of("A", "B", "C"), 10);

        // Not one transaction around a network-bound loop: that holds a connection open for the
        // whole burst and lets one constraint violation roll back the entire batch.
        assertThat(txManager.started).isEqualTo(3);
    }
}
