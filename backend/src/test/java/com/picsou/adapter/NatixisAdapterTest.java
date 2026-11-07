package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.support.WebClientStubs;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** The best-effort {@code /cancel} call; the shared error contract lives in {@link SidecarContractTest}. */
class NatixisAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration GENEROUS = Duration.ofSeconds(1);

    @Test
    void cancelPostsTheProcessIdToTheSidecar() {
        List<WebClientStubs.Call> calls = new ArrayList<>();
        NatixisAdapter adapter = adapter(WebClientStubs.recording(calls, HttpStatus.NO_CONTENT, ""), GENEROUS);

        adapter.cancelAuth("process-1");

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.path()).isEqualTo("/cancel");
            assertThat(call.body()).isEqualTo("{\"processId\":\"process-1\"}");
        });
    }

    @Test
    void aRefusedCancelIsSwallowed() {
        NatixisAdapter adapter = adapter(
            WebClientStubs.returning(HttpStatus.INTERNAL_SERVER_ERROR, "{\"detail\":\"INTERNAL_ERROR\"}"), GENEROUS);

        assertThatCode(() -> adapter.cancelAuth("process-1")).doesNotThrowAnyException();
    }

    @Test
    void anUnreachableSidecarIsSwallowed() {
        NatixisAdapter adapter = adapter(WebClientStubs.failing(new WebClientRequestException(
            new ConnectException("refused"), HttpMethod.POST,
            URI.create("http://natixis-auth/cancel"), new HttpHeaders())), GENEROUS);

        assertThatCode(() -> adapter.cancelAuth("process-1")).doesNotThrowAnyException();
    }

    @Test
    void aSilentSidecarIsCutOffByTheCancelTimeout() {
        NatixisAdapter adapter = adapter(WebClientStubs.neverResponding(), Duration.ofMillis(20));

        assertThatCode(() -> adapter.cancelAuth("process-1")).doesNotThrowAnyException();
    }

    @Test
    void positionsFromAnOlderSidecarCarryNoCompleteHistory() {
        NatixisAdapter adapter = adapter(WebClientStubs.returning("{\"plans\":[],\"sessionState\":\"s\"}"), GENEROUS);

        NatixisAdapter.PositionsData data = adapter.fetchPlans("state");

        assertThat(data.hasCompleteOperations()).isFalse();
    }

    @Test
    void positionsDecodeTheOperationsHistory() {
        NatixisAdapter adapter = adapter(WebClientStubs.returning("""
            {"plans": [], "sessionState": "s", "operationsComplete": true, "operations": [{
              "externalId": "natixis_1_D1_L1", "planExternalId": "natixis_1_PEE", "date": "2024-03-28",
              "type": "BUY", "label": "Abondement employeur", "fundLabel": "FONDS ACTIONS EUROPE I",
              "fundCode": "NIE-1002", "quantity": "12.3456", "unitValue": "25.50", "amountEur": "314.81",
              "feesEur": null}]}
            """), GENEROUS);

        NatixisAdapter.PositionsData data = adapter.fetchPlans("state");

        assertThat(data.hasCompleteOperations()).isTrue();
        assertThat(data.operations()).singleElement().satisfies(operation -> {
            assertThat(operation.date()).isEqualTo("2024-03-28");
            assertThat(operation.type()).isEqualTo("BUY");
            assertThat(operation.fundCode()).isEqualTo("NIE-1002");
            assertThat(operation.amountEur()).isEqualByComparingTo("314.81");
            assertThat(operation.feesEur()).isNull();
        });
    }

    private static NatixisAdapter adapter(WebClient client,
                                          Duration cancelTimeout) {
        return new NatixisAdapter(client, JSON, GENEROUS, GENEROUS, GENEROUS, cancelTimeout);
    }
}
