package com.picsou.service;

import com.picsou.dto.HoldingLogoUrls;
import com.picsou.model.AccountType;
import com.picsou.model.InstrumentLogo;
import com.picsou.model.InstrumentLogoStatus;
import com.picsou.port.InstrumentLogoPort;
import com.picsou.port.InstrumentLogoPort.Image;
import com.picsou.port.InstrumentLogoPort.Lookup;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.InstrumentLogoRepository;
import com.picsou.repository.InstrumentLogoRepository.StoredLogoRef;
import com.picsou.repository.PriceSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstrumentLogoServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final Image PNG = new Image(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "image/png");
    private static final Image PNG_DARK = new Image(new byte[] {(byte) 0x89, 'P', 'N', 'G', 1}, "image/png");

    @Mock InstrumentLogoRepository repository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock PriceSnapshotRepository priceSnapshotRepository;
    @Mock InstrumentLogoPort logoPort;

    private final List<Runnable> queued = new ArrayList<>();
    private InstrumentLogoService service;

    @BeforeEach
    void setUp() {
        service = serviceWith(true, queued::add);
        lenient().when(logoPort.supports(anyString())).thenAnswer(inv -> !inv.getArgument(0, String.class).equals("BTC"));
        lenient().when(repository.findByTicker(anyString())).thenReturn(Optional.empty());
        lenient().when(repository.findSettledTickers(any(), any())).thenReturn(Set.of());
        lenient().when(holdingRepository.findDistinctTickersByAccountType(AccountType.CRYPTO)).thenReturn(Set.of());
        lenient().when(priceSnapshotRepository.findPricedTickers(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private InstrumentLogoService serviceWith(boolean enabled, Executor executor) {
        return new InstrumentLogoService(repository, holdingRepository, priceSnapshotRepository, logoPort,
            Clock.fixed(NOW, ZoneOffset.UTC), enabled, executor, Duration.ZERO);
    }

    private void held(String... tickers) {
        when(holdingRepository.findDistinctTickers()).thenReturn(Set.of(tickers));
    }

    private List<InstrumentLogo> saved(int times) {
        ArgumentCaptor<InstrumentLogo> captor = ArgumentCaptor.forClass(InstrumentLogo.class);
        verify(repository, times(times)).save(captor.capture());
        return captor.getAllValues();
    }

    // ── which tickers are looked up ─────────────────────────────────────────────

    @Test
    void dueTickers_skipCryptoAccounts_unsupportedSymbols_andTickersNoProviderHasPriced() {
        held("aapl", "BTC", "SOL", "1RAKKPA", "MC.PA");
        // SOL sits in a crypto account; BTC is refused by the port; 1RAKKPA is a fund code no
        // provider ever priced.
        when(holdingRepository.findDistinctTickersByAccountType(AccountType.CRYPTO)).thenReturn(Set.of("SOL"));
        when(priceSnapshotRepository.findPricedTickers(any())).thenReturn(Set.of("AAPL", "MC.PA"));

        assertThat(service.dueTickers()).containsExactly("AAPL", "MC.PA");
    }

    @Test
    void dueTickers_excludeEverythingAlreadySettled_withTheSevenDayRetryCutoff() {
        held("AAPL", "MC.PA", "NOLOGO");
        when(repository.findSettledTickers(any(), eq(NOW.minus(Duration.ofDays(7)))))
            .thenReturn(Set.of("AAPL", "NOLOGO"));

        assertThat(service.dueTickers()).containsExactly("MC.PA");
    }

    @Test
    void dueTickers_areCappedPerPass() {
        String[] many = IntStream.range(0, 25).mapToObj(i -> "T" + (char) ('A' + i)).toArray(String[]::new);
        held(many);

        assertThat(service.dueTickers()).hasSize(InstrumentLogoService.BATCH);
    }

    @Test
    void dueTickers_neverQueryTheDatabaseFurther_whenNothingIsHeld() {
        held();

        assertThat(service.dueTickers()).isEmpty();
        verifyNoInteractions(priceSnapshotRepository);
        verify(repository, never()).findSettledTickers(any(), any());
    }

    // ── what a pass records ─────────────────────────────────────────────────────

    @Test
    void aFoundMark_isStoredWithBothVariants() {
        held("AAPL");
        when(logoPort.lookup("AAPL")).thenReturn(new Lookup.Found(PNG, PNG_DARK));

        assertThat(service.resolvePending()).isEqualTo(1);

        InstrumentLogo logo = saved(1).get(0);
        assertThat(logo.getTicker()).isEqualTo("AAPL");
        assertThat(logo.getStatus()).isEqualTo(InstrumentLogoStatus.STORED);
        assertThat(logo.getImage()).isEqualTo(PNG.bytes());
        assertThat(logo.getImageDark()).isEqualTo(PNG_DARK.bytes());
        assertThat(logo.getContentTypeDark()).isEqualTo("image/png");
        assertThat(logo.getAttemptedAt()).isEqualTo(NOW);
        assertThat(logo.getFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void aPermanentMiss_isRecordedAsAbsent_andThePassGoesOn() {
        held("AAPL", "MC.PA");
        when(logoPort.lookup("AAPL")).thenReturn(new Lookup.Absent("no logo on the quote page"));
        when(logoPort.lookup("MC.PA")).thenReturn(new Lookup.Found(PNG, null));

        assertThat(service.resolvePending()).isEqualTo(1);

        List<InstrumentLogo> rows = saved(2);
        assertThat(rows.get(0).getStatus()).isEqualTo(InstrumentLogoStatus.ABSENT);
        assertThat(rows.get(0).getImage()).isNull();
        assertThat(rows.get(0).getAttemptedAt()).isEqualTo(NOW);
        assertThat(rows.get(1).getStatus()).isEqualTo(InstrumentLogoStatus.STORED);
        assertThat(rows.get(1).getImageDark()).isNull();
    }

    @Test
    void anUnansweredLookup_isRecordedAsFailed_andStopsThePass() {
        held("AAPL", "MC.PA", "MSFT");
        when(logoPort.lookup("AAPL")).thenReturn(new Lookup.Unavailable("HTTP 503"));

        service.resolvePending();

        assertThat(saved(1).get(0).getStatus()).isEqualTo(InstrumentLogoStatus.FAILED);
        verify(logoPort, never()).lookup("MC.PA");
        verify(logoPort, never()).lookup("MSFT");
    }

    @Test
    void aLookupThatThrows_isRecordedAsFailed_andThePassGoesOn() {
        held("AAPL", "MC.PA");
        when(logoPort.lookup("AAPL")).thenThrow(new IllegalStateException("boom"));
        when(logoPort.lookup("MC.PA")).thenReturn(new Lookup.Found(PNG, null));

        assertThat(service.resolvePending()).isEqualTo(1);

        List<InstrumentLogo> rows = saved(2);
        assertThat(rows.get(0).getTicker()).isEqualTo("AAPL");
        assertThat(rows.get(0).getStatus()).isEqualTo(InstrumentLogoStatus.FAILED);
        assertThat(rows.get(0).getAttemptedAt()).isEqualTo(NOW);
        assertThat(rows.get(1).getTicker()).isEqualTo("MC.PA");
        assertThat(rows.get(1).getStatus()).isEqualTo(InstrumentLogoStatus.STORED);
    }

    @Test
    void aRateLimit_recordsNothing_andStopsThePass() {
        held("AAPL", "MC.PA");
        when(logoPort.lookup("AAPL")).thenReturn(new Lookup.RateLimited());

        assertThat(service.resolvePending()).isZero();

        verify(repository, never()).save(any());
        verify(logoPort, never()).lookup("MC.PA");
    }

    @Test
    void aFailedRow_isUpdatedInPlace_whenTheRetrySucceeds() {
        held("AAPL");
        InstrumentLogo failed = InstrumentLogo.builder()
            .id(7L).ticker("AAPL").status(InstrumentLogoStatus.FAILED).attemptedAt(NOW.minus(Duration.ofDays(8))).build();
        when(repository.findByTicker("AAPL")).thenReturn(Optional.of(failed));
        when(logoPort.lookup("AAPL")).thenReturn(new Lookup.Found(PNG, null));

        service.resolvePending();

        InstrumentLogo row = saved(1).get(0);
        assertThat(row.getId()).isEqualTo(7L);
        assertThat(row.getStatus()).isEqualTo(InstrumentLogoStatus.STORED);
    }

    // ── triggering ──────────────────────────────────────────────────────────────

    @Test
    void requestResolution_runsOffTheCallersThread_andNeverTwiceAtOnce() {
        service.requestResolution();
        service.requestResolution();

        assertThat(queued).hasSize(1);
        verifyNoInteractions(holdingRepository, logoPort);

        held();
        queued.get(0).run();
        service.requestResolution();
        assertThat(queued).hasSize(2);
    }

    @Test
    void requestResolution_isANoOp_whenSwitchedOff() {
        List<Runnable> offQueue = new ArrayList<>();
        serviceWith(false, offQueue::add).requestResolution();

        assertThat(offQueue).isEmpty();
    }

    @Test
    void requestResolution_survivesABugInThePass() {
        when(holdingRepository.findDistinctTickers()).thenThrow(new IllegalStateException("boom"));
        service.requestResolution();

        queued.get(0).run();
        service.requestResolution();
        assertThat(queued).hasSize(2);
    }

    // ── what the read path hands the UI ─────────────────────────────────────────

    @Test
    void storedUrls_pointAtPicsou_carryAVersion_andOfferDarkOnlyWhenStored() {
        when(repository.findByStatusAndTickerIn(eq(InstrumentLogoStatus.STORED), any())).thenReturn(List.of(
            ref("AAPL", Instant.ofEpochSecond(1759400000), "image/png"),
            ref("MC.PA", Instant.ofEpochSecond(1759400100), null)));

        var urls = service.storedUrls(List.of("aapl", "MC.PA", "NOLOGO"));

        assertThat(urls).containsOnlyKeys("AAPL", "MC.PA");
        assertThat(urls.get("AAPL")).isEqualTo(new HoldingLogoUrls(
            "/api/instrument-logos/AAPL?v=1759400000",
            "/api/instrument-logos/AAPL?v=1759400000&variant=dark"));
        assertThat(urls.get("MC.PA")).isEqualTo(new HoldingLogoUrls("/api/instrument-logos/MC.PA?v=1759400100", null));
    }

    @Test
    void storedUrls_queryOnceForTheWholePage_upperCased() {
        when(repository.findByStatusAndTickerIn(eq(InstrumentLogoStatus.STORED), any())).thenReturn(List.of());

        service.storedUrls(List.of("aapl", "mc.pa"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(repository, times(1)).findByStatusAndTickerIn(eq(InstrumentLogoStatus.STORED), captor.capture());
        assertThat(new TreeSet<>(captor.getValue())).containsExactly("AAPL", "MC.PA");
    }

    @Test
    void storedUrls_isEmptyWithoutTouchingTheDatabase_forNoTickers() {
        assertThat(service.storedUrls(List.of())).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void image_servesTheDarkVariantWhenAsked_andFallsBackToLight() {
        InstrumentLogo withDark = InstrumentLogo.builder().ticker("AAPL").status(InstrumentLogoStatus.STORED)
            .image(PNG.bytes()).contentType("image/png").imageDark(PNG_DARK.bytes()).contentTypeDark("image/png")
            .attemptedAt(NOW).fetchedAt(NOW).build();
        InstrumentLogo lightOnly = InstrumentLogo.builder().ticker("MC.PA").status(InstrumentLogoStatus.STORED)
            .image(PNG.bytes()).contentType("image/png").attemptedAt(NOW).fetchedAt(NOW).build();
        when(repository.findByTicker("AAPL")).thenReturn(Optional.of(withDark));
        when(repository.findByTicker("MC.PA")).thenReturn(Optional.of(lightOnly));

        assertThat(service.image("aapl", true).orElseThrow().bytes()).isEqualTo(PNG_DARK.bytes());
        assertThat(service.image("AAPL", false).orElseThrow().bytes()).isEqualTo(PNG.bytes());
        assertThat(service.image("MC.PA", true).orElseThrow()).satisfies(img -> {
            assertThat(img.bytes()).isEqualTo(PNG.bytes());
            assertThat(img.dark()).isFalse();
        });
    }

    @Test
    void image_servesNothingForAMissOrAFailure() {
        when(repository.findByTicker("NOLOGO")).thenReturn(Optional.of(InstrumentLogo.builder()
            .ticker("NOLOGO").status(InstrumentLogoStatus.ABSENT).attemptedAt(NOW).build()));

        assertThat(service.image("NOLOGO", false)).isEmpty();
        assertThat(service.image("UNKNOWN", false)).isEmpty();
        assertThat(service.image(" ", false)).isEmpty();
    }

    private static StoredLogoRef ref(String ticker, Instant fetchedAt, String darkType) {
        return new StoredLogoRef() {
            @Override public String getTicker() { return ticker; }
            @Override public Instant getFetchedAt() { return fetchedAt; }
            @Override public String getContentTypeDark() { return darkType; }
        };
    }
}
