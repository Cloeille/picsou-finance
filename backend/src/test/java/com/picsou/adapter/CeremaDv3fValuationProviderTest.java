package com.picsou.adapter;

import com.picsou.exception.ValuationProviderException;
import com.picsou.model.PropertyKind;
import com.picsou.model.ValuationConfidence;
import com.picsou.port.PropertyValuationPort.ValuationInput;
import com.picsou.port.PropertyValuationPort.ValuationResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Field naming and vintage selection are the fragile parts here: DV3F encodes the property
 * type in the column name ({@code pxm2_median_cod111}), returns vintages oldest-first, and
 * answers {@code count: 0} — not an error — for areas it structurally does not cover.
 */
class CeremaDv3fValuationProviderTest {

    /** Two vintages, oldest first, as the real API returns them. */
    private static final String BORDEAUX_JSON = """
        {"count":2,"results":[
          {"annee":"2024","echelle":"communes","code":"33063","libelle":"Bordeaux",
           "nbtrans_cod111":900,"pxm2_median_cod111":4700.00,
           "pxm2_q25_cod111":3900.00,"pxm2_q75_cod111":5700.00,
           "nbtrans_cod121":3000,"pxm2_median_cod121":4000.00},
          {"annee":"2025","echelle":"communes","code":"33063","libelle":"Bordeaux",
           "nbtrans_cod111":1048,"pxm2_median_cod111":4864.165,
           "pxm2_q25_cod111":4042.6475,"pxm2_q75_cod111":5906.425,
           "nbtrans_cod121":3345,"pxm2_median_cod121":4114.89,
           "pxm2_q25_cod121":3455.88,"pxm2_q75_cod121":4863.64,
           "nbtrans_cod121x3":687,"pxm2_median_cod121x3":3811.19,
           "pxm2_q25_cod121x3":3277.37,"pxm2_q75_cod121x3":4511.845,
           "nbtrans_cod121x1":5,"pxm2_median_cod121x1":9999.00}
        ]}
        """;

    private static final String EMPTY_JSON = "{\"count\":0,\"next\":null,\"previous\":null,\"results\":[]}";

    private CeremaDv3fValuationProvider providerReturning(Mono<ClientResponse> response) {
        ExchangeFunction exchange = request -> response;
        return new CeremaDv3fValuationProvider(WebClient.builder().exchangeFunction(exchange).build());
    }

    private CeremaDv3fValuationProvider providerWithJson(String json) {
        return providerReturning(Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(json).build()));
    }

    /** Serves a different body per call, so the commune→department fallback can be observed. */
    private CeremaDv3fValuationProvider providerWithSequence(String... bodies) {
        Deque<String> queue = new ArrayDeque<>(java.util.List.of(bodies));
        ExchangeFunction exchange = request -> {
            String body = queue.isEmpty() ? EMPTY_JSON : queue.poll();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body).build());
        };
        return new CeremaDv3fValuationProvider(WebClient.builder().exchangeFunction(exchange).build());
    }

    private static ValuationInput house(String insee, String area) {
        return new ValuationInput(insee, insee.substring(0, 2), "FR", PropertyKind.HOUSE,
            new BigDecimal(area), null, null, null, null, null);
    }

    private static ValuationInput apartment(String insee, String area, Short rooms) {
        return new ValuationInput(insee, insee.substring(0, 2), "FR", PropertyKind.APARTMENT,
            new BigDecimal(area), null, rooms, null, null, null);
    }

    // ─── Coverage ────────────────────────────────────────────────────────────

    @Test
    void supports_refusesAlsaceMoselleAndMayotte() {
        CeremaDv3fValuationProvider provider = providerWithJson(EMPTY_JSON);

        // These keep the livre foncier registry, so DGFiP holds no transactions at all.
        // Refusing up front is what lets the UI say why instead of showing "no recent sales".
        assertThat(provider.supports(house("67482", "100"))).isFalse(); // Strasbourg
        assertThat(provider.supports(house("68066", "100"))).isFalse(); // Mulhouse
        assertThat(provider.supports(house("57463", "100"))).isFalse(); // Metz
        assertThat(provider.supports(new ValuationInput("97611", "976", "FR", PropertyKind.HOUSE,
            new BigDecimal("100"), null, null, null, null, null))).isFalse();

        assertThat(provider.supports(house("33063", "100"))).isTrue();
    }

    @Test
    void supports_refusesKindsWithoutAReliableMedian() {
        CeremaDv3fValuationProvider provider = providerWithJson(EMPTY_JSON);
        for (PropertyKind kind : new PropertyKind[]{
            PropertyKind.BUILDING, PropertyKind.LAND, PropertyKind.PARKING, PropertyKind.COMMERCIAL}) {
            assertThat(provider.supports(new ValuationInput("33063", "33", "FR", kind,
                new BigDecimal("100"), null, null, null, null, null)))
                .as("kind %s", kind)
                .isFalse();
        }
    }

    @Test
    void supports_refusesMissingLivingArea() {
        CeremaDv3fValuationProvider provider = providerWithJson(EMPTY_JSON);
        assertThat(provider.supports(new ValuationInput("33063", "33", "FR", PropertyKind.HOUSE,
            null, null, null, null, null, null))).isFalse();
    }

    @Test
    void supports_refusesNonFrenchProperties() {
        CeremaDv3fValuationProvider provider = providerWithJson(EMPTY_JSON);
        assertThat(provider.supports(new ValuationInput("00000", "00", "BE", PropertyKind.HOUSE,
            new BigDecimal("100"), null, null, null, null, null))).isFalse();
    }

    // ─── Estimation ──────────────────────────────────────────────────────────

    @Test
    void estimate_usesTheFreshestVintage() {
        ValuationResult r = providerWithJson(BORDEAUX_JSON).estimate(house("33063", "100")).orElseThrow();

        // Results arrive oldest-first; 2025 must win over 2024.
        assertThat(r.sourceYear()).isEqualTo((short) 2025);
        assertThat(r.pricePerSqm()).isEqualByComparingTo("4864.165");
        assertThat(r.estimatedValue()).isEqualByComparingTo("486416.50");
        assertThat(r.lowValue()).isEqualByComparingTo("404264.75");
        assertThat(r.highValue()).isEqualByComparingTo("590642.50");
        assertThat(r.sampleSize()).isEqualTo(1048);
        assertThat(r.confidence()).isEqualTo(ValuationConfidence.HIGH);
        assertThat(r.scale()).isEqualTo("communes");
    }

    @Test
    void estimate_apartment_prefersTheRoomCountSeries() {
        ValuationResult r = providerWithJson(BORDEAUX_JSON)
            .estimate(apartment("33063", "66", (short) 3)).orElseThrow();

        // A commune-wide flat median mixes studios with five-room apartments; the per-room
        // series is markedly closer for a specific property.
        assertThat(r.pricePerSqm()).isEqualByComparingTo("3811.19");
        assertThat(r.sampleSize()).isEqualTo(687);
    }

    @Test
    void estimate_apartment_fallsBackWhenTheRoomBucketIsThin() {
        // cod121x1 exists here but rests on 5 transactions. A narrower bucket is only better
        // while it still has a real sample behind it.
        ValuationResult r = providerWithJson(BORDEAUX_JSON)
            .estimate(apartment("33063", "25", (short) 1)).orElseThrow();

        assertThat(r.pricePerSqm()).isEqualByComparingTo("4114.89");
        assertThat(r.sampleSize()).isEqualTo(3345);
    }

    @Test
    void estimate_apartment_withoutRoomCount_usesTheCommuneMedian() {
        ValuationResult r = providerWithJson(BORDEAUX_JSON)
            .estimate(apartment("33063", "70", null)).orElseThrow();
        assertThat(r.pricePerSqm()).isEqualByComparingTo("4114.89");
    }

    @Test
    void estimate_bigApartment_clampsToTheLastRoomBucket() {
        // Series stop at x5; a seven-room flat must fold into it rather than miss entirely.
        String json = BORDEAUX_JSON.replace("\"nbtrans_cod121x3\":687", "\"nbtrans_cod121x5\":82")
            .replace("\"pxm2_median_cod121x3\":3811.19", "\"pxm2_median_cod121x5\":3577.475");
        ValuationResult r = providerWithJson(json)
            .estimate(apartment("33063", "150", (short) 7)).orElseThrow();

        // 82 is above the room-series threshold, so the x5 bucket is used.
        assertThat(r.pricePerSqm()).isEqualByComparingTo("3577.475");
    }

    // ─── Fallback and failure ────────────────────────────────────────────────

    @Test
    void estimate_emptyCommune_fallsBackToDepartmentWithLowConfidence() {
        // Small communes often have no usable sample. A department median spans very
        // different markets, so it is reported but never as high confidence.
        String departmentJson = """
            {"count":1,"results":[
              {"annee":"2025","echelle":"departements","code":"33","libelle":"Gironde",
               "nbtrans_cod111":10195,"pxm2_median_cod111":2800.00}]}
            """;
        ValuationResult r = providerWithSequence(EMPTY_JSON, departmentJson)
            .estimate(house("33999", "100")).orElseThrow();

        assertThat(r.scale()).isEqualTo("departements");
        assertThat(r.confidence()).isEqualTo(ValuationConfidence.LOW);
        assertThat(r.estimatedValue()).isEqualByComparingTo("280000.00");
    }

    @Test
    void estimate_noDataAnywhere_returnsEmpty() {
        assertThat(providerWithJson(EMPTY_JSON).estimate(house("33999", "100"))).isEmpty();
    }

    @Test
    void estimate_transientOutage_reportsAProviderFailure() {
        // This host is still preprod and returns sporadic 503s. The caller keeps the previous
        // valuation either way, but it must be able to tell "the source is down" from "this
        // commune has no sales" -- swallowing the difference once surfaced a 256 KB buffer
        // limit to users as "no comparable transactions".
        CeremaDv3fValuationProvider provider = providerReturning(
            Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{}").build()));

        assertThatThrownBy(() -> provider.estimate(house("33063", "100")))
            .isInstanceOf(ValuationProviderException.class);
    }

    @Test
    void estimate_timeout_reportsAProviderFailure() {
        CeremaDv3fValuationProvider provider = providerReturning(Mono.error(new TimeoutException("slow")));

        assertThatThrownBy(() -> provider.estimate(house("33063", "100")))
            .isInstanceOf(ValuationProviderException.class);
    }

    @Test
    void confidence_reflectsSampleSize() {
        String thin = """
            {"count":1,"results":[{"annee":"2025","echelle":"communes","code":"33063",
              "nbtrans_cod111":9,"pxm2_median_cod111":3000.00}]}
            """;
        assertThat(providerWithJson(thin).estimate(house("33063", "100")).orElseThrow().confidence())
            .isEqualTo(ValuationConfidence.LOW);

        String medium = thin.replace("\"nbtrans_cod111\":9", "\"nbtrans_cod111\":30");
        assertThat(providerWithJson(medium).estimate(house("33063", "100")).orElseThrow().confidence())
            .isEqualTo(ValuationConfidence.MEDIUM);
    }
}
