package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.exception.SyncException;
import com.picsou.port.CmesErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CmesAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void fetchPlans_mapsTheSidecarContract() {
        CmesAdapter adapter = adapter(new ArrayList<>(), HttpStatus.OK, """
            [{
              "externalId":"cmes_12345_PEG",
              "legacyExternalIds":["cmes_12345_PEG","cmes_12345_PEG-plan-d-pargne-groupe"],
              "name":"Plan d'épargne groupe",
              "planKind":"PEG",
              "employer":"ACME INDUSTRIES",
              "balanceEur":1000.00,
              "positions":[{
                "isin":null,
                "fundCode":"1613",
                "label":"FONDS EQUILIBRE",
                "quantity":50.0000,
                "unitValue":20.0000,
                "valueEur":1000.00,
                "pnlEur":90.91
              }],
              "snapshotComplete":true
            }]
            """);

        var plans = adapter.fetchPlans("state");

        assertThat(plans).singleElement().satisfies(plan -> {
            assertThat(plan.externalId()).isEqualTo("cmes_12345_PEG");
            assertThat(plan.legacyExternalIds())
                .containsExactly("cmes_12345_PEG", "cmes_12345_PEG-plan-d-pargne-groupe");
            assertThat(plan.employer()).isEqualTo("ACME INDUSTRIES");
            assertThat(plan.positions()).singleElement().satisfies(position -> {
                assertThat(position.fundCode()).isEqualTo("1613");
                assertThat(position.isin()).isNull();
                assertThat(position.quantity()).isEqualByComparingTo("50");
            });
        });
    }

    @Test
    void initiateAuth_sendsThePreviousSessionOnlyWhenThereIsOne() throws Exception {
        List<String> bodies = new ArrayList<>();
        CmesAdapter adapter = adapter(bodies, HttpStatus.OK,
            "{\"processId\":null,\"mfaRequired\":false,\"mfaType\":null,\"sessionState\":\"s\"}");

        adapter.initiateAuth("12345678", "secret", null);
        adapter.initiateAuth("12345678", "secret", "{\"cookies\":[]}");

        JsonNode first = JSON.readTree(bodies.get(0));
        JsonNode second = JSON.readTree(bodies.get(1));
        assertThat(first.has("sessionState")).isFalse();
        assertThat(first.path("login").asText()).isEqualTo("12345678");
        assertThat(second.path("sessionState").asText()).isEqualTo("{\"cookies\":[]}");
    }

    @Test
    void completeAuth_sendsAnExplicitNullCodeForAMobileConfirmation() throws Exception {
        List<String> bodies = new ArrayList<>();
        CmesAdapter adapter = adapter(bodies, HttpStatus.OK, "{\"sessionState\":\"cookies\"}");

        assertThat(adapter.completeAuth("p1", null)).isEqualTo("cookies");
        adapter.completeAuth("p1", "123456");

        JsonNode push = JSON.readTree(bodies.get(0));
        assertThat(push.has("code")).isTrue();
        assertThat(push.get("code").isNull()).isTrue();
        assertThat(JSON.readTree(bodies.get(1)).path("code").asText()).isEqualTo("123456");
    }

    @Test
    void aWrongSmsCodeKeepsItsOwnCode() {
        CmesAdapter adapter = adapter(new ArrayList<>(), HttpStatus.BAD_REQUEST, "{\"detail\":\"INVALID_OTP\"}");

        assertThatThrownBy(() -> adapter.completeAuth("p1", "000000"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CmesErrorCode.INVALID_OTP.name()));
    }

    @Test
    void aRefusedConfirmationKeepsItsOwnCode() {
        CmesAdapter adapter = adapter(new ArrayList<>(), HttpStatus.UNAUTHORIZED,
            "{\"detail\":\"APP_VALIDATION_CANCELLED\"}");

        assertThatThrownBy(() -> adapter.completeAuth("p1", null))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CmesErrorCode.APP_VALIDATION_CANCELLED.name()));
    }

    @Test
    void springSelectsTheProductionConstructor() {
        new ApplicationContextRunner()
            .withPropertyValues("app.cmes-auth.url=http://cmes-auth:8001", "app.sidecar.api-key=test-key")
            .withBean(ObjectMapper.class)
            .withBean(SidecarWebClientFactory.class)
            .withBean(CmesAdapter.class)
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(CmesAdapter.class));
    }

    private CmesAdapter adapter(List<String> bodies, HttpStatus status, String responseBody) {
        ExchangeFunction exchange = request -> {
            MockClientHttpRequest recorder = new MockClientHttpRequest(request.method(), request.url());
            request.writeTo(recorder, ExchangeStrategies.withDefaults()).block();
            bodies.add(recorder.getBodyAsString().block());
            return Mono.just(ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(responseBody)
                .build());
        };
        return new CmesAdapter(WebClient.builder().exchangeFunction(exchange).build(), JSON);
    }
}
