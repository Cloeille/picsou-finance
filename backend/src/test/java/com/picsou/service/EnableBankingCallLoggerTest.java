package com.picsou.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnableBankingCallLoggerTest {

    private final EnableBankingCallLogger logger = new EnableBankingCallLogger();

    /** The connector's own codec limit: the catalog call answers well above the 256 KB default. */
    private static final ExchangeStrategies STRATEGIES = ExchangeStrategies.builder()
        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
        .build();

    private WebClient clientAnswering(HttpStatus status, String body) {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(status, STRATEGIES)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body).build());
        return WebClient.builder()
            .baseUrl("https://api.enablebanking.test")
            .exchangeStrategies(STRATEGIES)
            .exchangeFunction(exchange)
            .filter(logger.filter())
            .build();
    }

    @Test
    void filter_recordsTheCall_andHandsTheSameBodyToTheCaller() {
        String body = "{\"accounts\": [\"uid-synthetic\"]}";

        String read = clientAnswering(HttpStatus.OK, body).get().uri("/sessions/session")
            .retrieve().bodyToMono(String.class).block();

        assertThat(read).isEqualTo(body);
        assertThat(logger.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.method()).isEqualTo("GET");
            assertThat(entry.url()).isEqualTo("https://api.enablebanking.test/sessions/session");
            assertThat(entry.requestBody()).isEmpty();
            assertThat(entry.responseStatus()).isEqualTo(200);
            assertThat(entry.responseBody()).isEqualTo(body);
        });
    }

    @Test
    void filter_recordsAnErrorResponse_andTheCallerStillSeesTheError() {
        String body = "{\"error\": \"ASPSP_ERROR\"}";

        assertThatThrownBy(() -> clientAnswering(HttpStatus.BAD_GATEWAY, body).get().uri("/accounts/uid/balances")
            .retrieve().bodyToMono(String.class).block())
            .isInstanceOf(WebClientResponseException.class)
            .satisfies(e -> assertThat(((WebClientResponseException) e).getResponseBodyAsString()).isEqualTo(body));

        assertThat(logger.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.responseStatus()).isEqualTo(502);
            assertThat(entry.responseBody()).isEqualTo(body);
        });
    }

    @Test
    void filter_recordsAnEmptyBodyAsEmpty() {
        clientAnswering(HttpStatus.NO_CONTENT, "").delete().uri("/sessions/session")
            .retrieve().toBodilessEntity().block();

        assertThat(logger.entries()).singleElement()
            .satisfies(entry -> assertThat(entry.responseBody()).isEmpty());
    }

    @Test
    void filter_capsAStoredBody_butHandsTheWholeBodyToTheCaller() {
        String body = "x".repeat(EnableBankingCallLogger.MAX_BODY_CHARS + 10);

        String read = clientAnswering(HttpStatus.OK, body).get().uri("/aspsps")
            .retrieve().bodyToMono(String.class).block();

        assertThat(read).isEqualTo(body);
        assertThat(logger.entries()).singleElement().satisfies(entry -> assertThat(entry.responseBody())
            .startsWith("x".repeat(EnableBankingCallLogger.MAX_BODY_CHARS))
            .endsWith("…[truncated, " + body.length() + " bytes in total]")
            .hasSize(EnableBankingCallLogger.MAX_BODY_CHARS
                + ("…[truncated, " + body.length() + " bytes in total]").length()));
    }

    @Test
    void log_keepsOnlyTheNewestEntries_newestFirst() {
        for (int i = 0; i < EnableBankingCallLogger.MAX_ENTRIES + 1; i++) {
            logger.log("GET", "/call/" + i, "", 200, "");
        }

        assertThat(logger.entries()).hasSize(EnableBankingCallLogger.MAX_ENTRIES);
        assertThat(logger.entries().get(0).url()).isEqualTo("/call/" + EnableBankingCallLogger.MAX_ENTRIES);
        assertThat(logger.entries().get(EnableBankingCallLogger.MAX_ENTRIES - 1).url()).isEqualTo("/call/1");
    }
}
