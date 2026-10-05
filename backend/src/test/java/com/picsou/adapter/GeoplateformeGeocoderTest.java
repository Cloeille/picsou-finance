package com.picsou.adapter;

import com.picsou.port.GeocodingPort.GeocodeResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two things that can silently ruin a valuation: losing the INSEE code (every price
 * series is keyed on it) and swapping the GeoJSON coordinate order.
 */
class GeoplateformeGeocoderTest {

    /** Trimmed real response for "1 rue de la Paix 75002 Paris". */
    private static final String PARIS_JSON = """
        {"type":"FeatureCollection","features":[{"type":"Feature",
          "geometry":{"type":"Point","coordinates":[2.33031,48.868546]},
          "properties":{"label":"1 Rue de la Paix 75002 Paris","score":0.96406,
            "housenumber":"1","id":"75102_6998_00001",
            "banId":"50d9aa1d-5049-4ec2-a439-0649f829a186","name":"1 Rue de la Paix",
            "postcode":"75002","citycode":"75102","city":"Paris","depcode":"75",
            "street":"Rue de la Paix","_type":"address"}}]}
        """;

    private GeoplateformeGeocoder geocoderReturning(Mono<ClientResponse> response) {
        ExchangeFunction exchange = request -> response;
        return new GeoplateformeGeocoder(WebClient.builder().exchangeFunction(exchange).build());
    }

    private GeoplateformeGeocoder geocoderWithJson(String json) {
        return geocoderReturning(Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(json).build()));
    }

    @Test
    void geocode_mapsInseeCodeAndCoordinates() {
        Optional<GeocodeResult> result = geocoderWithJson(PARIS_JSON).geocode("1 rue de la Paix Paris");

        assertThat(result).isPresent();
        GeocodeResult r = result.get();
        assertThat(r.inseeCode()).isEqualTo("75102");
        assertThat(r.postcode()).isEqualTo("75002");
        assertThat(r.city()).isEqualTo("Paris");
        assertThat(r.banId()).isEqualTo("50d9aa1d-5049-4ec2-a439-0649f829a186");
        assertThat(r.score()).isEqualByComparingTo("0.96406");
    }

    @Test
    void geocode_readsGeoJsonOrderLongitudeThenLatitude() {
        GeocodeResult r = geocoderWithJson(PARIS_JSON).geocode("q").orElseThrow();

        // GeoJSON is [lon, lat]. Reading them the other way round lands Paris in the Indian
        // Ocean and nothing downstream would notice -- the valuation would just use the
        // wrong commune's median.
        assertThat(r.latitude()).isEqualByComparingTo("48.868546");
        assertThat(r.longitude()).isEqualByComparingTo("2.33031");
    }

    @Test
    void departmentCode_takesTwoDigitsInMetropolitanFrance() {
        GeocodeResult r = geocoderWithJson(PARIS_JSON).geocode("q").orElseThrow();
        assertThat(r.departmentCode()).isEqualTo("75");
    }

    @Test
    void departmentCode_takesThreeDigitsOverseas() {
        // Mayotte is 976, not 97 -- and it is one of the areas DVF does not cover, so the
        // difference decides whether the estimator correctly refuses.
        GeocodeResult mayotte = new GeocodeResult(
            "Mamoudzou", null, null, null, "97611", "97600", "Mamoudzou", null);
        assertThat(mayotte.departmentCode()).isEqualTo("976");
    }

    @Test
    void geocode_emptyFeatureCollection_returnsEmpty() {
        assertThat(geocoderWithJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
            .geocode("nowhere")).isEmpty();
    }

    @Test
    void geocode_upstreamFailure_returnsEmptyInsteadOfThrowing() {
        // A geocoding outage must not take down the page that triggered it; the caller keeps
        // the stored coordinates or asks the user to confirm the address.
        GeoplateformeGeocoder geocoder =
            geocoderReturning(Mono.error(new TimeoutException("upstream slow")));

        assertThat(geocoder.geocode("1 rue de la Paix")).isEmpty();
        assertThat(geocoder.autocomplete("1 rue de la Paix", 5)).isEmpty();
    }

    @Test
    void geocode_serverError_returnsEmpty() {
        assertThat(geocoderReturning(Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body("{}").build())).geocode("q")).isEmpty();
    }

    @Test
    void autocomplete_blankQuery_makesNoCall() {
        // Guarding here keeps a cleared input from spending the shared rate-limit budget.
        List<GeocodeResult> results = geocoderReturning(
            Mono.error(new AssertionError("must not call upstream"))).autocomplete("   ", 5);
        assertThat(results).isEmpty();
    }
}
