package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.exception.SyncException;
import com.picsou.port.CicErrorCode;
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

class CicAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void fetchAccounts_mapsTheSidecarContract() {
        CicAdapter adapter = adapter(new ArrayList<>(), HttpStatus.OK, """
            {"accounts":[{"externalId":"cic_1000000012345602","name":"Livret A","type":"LIVRET_A",
              "balanceEur":5000.00,"snapshotComplete":true}],
             "categoriesRead":["CHECKING","SAVINGS"]}
            """);

        var listing = adapter.fetchAccounts("state");

        assertThat(listing.categoriesRead()).containsExactlyInAnyOrder("CHECKING", "SAVINGS");
        assertThat(listing.accounts()).singleElement().satisfies(account -> {
            assertThat(account.externalId()).isEqualTo("cic_1000000012345602");
            assertThat(account.type()).isEqualTo(com.picsou.model.AccountType.LIVRET_A);
            assertThat(account.balanceEur()).isEqualByComparingTo("5000");
            assertThat(account.snapshotComplete()).isTrue();
        });
    }

    @Test
    void fetchAccounts_fallsBackToTheBareListOfAnOlderSidecar() {
        List<String> paths = new ArrayList<>();
        ExchangeFunction exchange = request -> {
            paths.add(request.url().getPath());
            if (request.url().getPath().equals("/accounts/listing")) {
                return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"detail\":\"Not Found\"}")
                    .build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("""
                    [{"externalId":"cic_1000000012345602","name":"Livret A","type":"LIVRET_A",
                      "balanceEur":5000.00,"snapshotComplete":true}]
                    """)
                .build());
        };
        CicAdapter adapter = new CicAdapter(WebClient.builder().exchangeFunction(exchange).build(), JSON);

        var listing = adapter.fetchAccounts("state");

        assertThat(paths).containsExactly("/accounts/listing", "/accounts");
        assertThat(listing.accounts()).hasSize(1);
        assertThat(listing.categoriesRead()).isNull();
    }

    @Test
    void fetchAccounts_keepsAnErrorOfTheListingItself() {
        CicAdapter adapter = adapter(new ArrayList<>(), HttpStatus.UNAUTHORIZED, "{\"detail\":\"SESSION_EXPIRED\"}");

        assertThatThrownBy(() -> adapter.fetchAccounts("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CicErrorCode.SESSION_EXPIRED.name()));
    }

    @Test
    void fetchAccounts_refusesAnEmptyListing() {
        CicAdapter adapter = adapter(new ArrayList<>(), HttpStatus.OK, "{\"accounts\":[],\"categoriesRead\":[\"SAVINGS\"]}");

        assertThatThrownBy(() -> adapter.fetchAccounts("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CicErrorCode.PORTFOLIO_INCOMPLETE.name()));
    }

    @Test
    void initiateAuth_sendsThePreviousSessionOnlyWhenThereIsOne() throws Exception {
        List<String> bodies = new ArrayList<>();
        CicAdapter adapter = adapter(bodies, HttpStatus.OK,
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
        CicAdapter adapter = adapter(bodies, HttpStatus.OK, "{\"sessionState\":\"cookies\"}");

        assertThat(adapter.completeAuth("p1", null)).isEqualTo("cookies");
        adapter.completeAuth("p1", "123456");

        JsonNode push = JSON.readTree(bodies.get(0));
        assertThat(push.has("code")).isTrue();
        assertThat(push.get("code").isNull()).isTrue();
        assertThat(JSON.readTree(bodies.get(1)).path("code").asText()).isEqualTo("123456");
    }

    @Test
    void aWrongSmsCodeKeepsItsOwnCode() {
        CicAdapter adapter = adapter(new ArrayList<>(), HttpStatus.BAD_REQUEST, "{\"detail\":\"INVALID_OTP\"}");

        assertThatThrownBy(() -> adapter.completeAuth("p1", "000000"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CicErrorCode.INVALID_OTP.name()));
    }

    @Test
    void aRefusedConfirmationKeepsItsOwnCode() {
        CicAdapter adapter = adapter(new ArrayList<>(), HttpStatus.UNAUTHORIZED,
            "{\"detail\":\"APP_VALIDATION_CANCELLED\"}");

        assertThatThrownBy(() -> adapter.completeAuth("p1", null))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CicErrorCode.APP_VALIDATION_CANCELLED.name()));
    }

    @Test
    void springSelectsTheProductionConstructor() {
        new ApplicationContextRunner()
            .withPropertyValues("app.cic-auth.url=http://cic-auth:8001", "app.sidecar.api-key=test-key")
            .withBean(ObjectMapper.class)
            .withBean(SidecarWebClientFactory.class)
            .withBean(CicAdapter.class)
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(CicAdapter.class));
    }

    private CicAdapter adapter(List<String> bodies, HttpStatus status, String responseBody) {
        ExchangeFunction exchange = request -> {
            MockClientHttpRequest recorder = new MockClientHttpRequest(request.method(), request.url());
            request.writeTo(recorder, ExchangeStrategies.withDefaults()).block();
            bodies.add(recorder.getBodyAsString().block());
            return Mono.just(ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(responseBody)
                .build());
        };
        return new CicAdapter(WebClient.builder().exchangeFunction(exchange).build(), JSON);
    }
}
