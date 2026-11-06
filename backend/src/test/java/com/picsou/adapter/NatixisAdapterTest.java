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

    private static NatixisAdapter adapter(WebClient client,
                                          Duration cancelTimeout) {
        return new NatixisAdapter(client, JSON, GENEROUS, GENEROUS, GENEROUS, cancelTimeout);
    }
}
