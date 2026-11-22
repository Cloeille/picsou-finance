package com.picsou.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

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
        return clientWith(exchange);
    }

    private WebClient clientWith(ExchangeFunction exchange) {
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
            .endsWith("…[truncated, " + body.length() + " characters in total]")
            .hasSize(EnableBankingCallLogger.MAX_BODY_CHARS
                + ("…[truncated, " + body.length() + " characters in total]").length()));
    }

    @Test
    void filter_neverStoresHalfOfASurrogatePair() {
        // An emoji straddling the cap: keeping its high half would make the log unwritable as JSON.
        String body = "x".repeat(EnableBankingCallLogger.MAX_BODY_CHARS - 1) + "\uD83C\uDFE6" + "tail";

        clientAnswering(HttpStatus.OK, body).get().uri("/aspsps").retrieve().bodyToMono(String.class).block();

        String stored = logger.entries().get(0).responseBody();
        assertThat(stored).startsWith("x".repeat(EnableBankingCallLogger.MAX_BODY_CHARS - 1) + "…[truncated");
        assertThat(stored.chars().anyMatch(c -> Character.isSurrogate((char) c))).isFalse();
    }

    @Test
    void log_dropsTheOldestEntries_onceAllBodiesTogetherExceedTheBudget() {
        String capped = "x".repeat(EnableBankingCallLogger.MAX_BODY_CHARS);
        int fitting = EnableBankingCallLogger.MAX_TOTAL_BODY_CHARS / EnableBankingCallLogger.MAX_BODY_CHARS;
        for (int i = 0; i < fitting + 1; i++) {
            logger.log("GET", "/aspsps/" + i, "", 200, capped);
        }

        assertThat(logger.entries()).hasSize(fitting);
        assertThat(logger.entries().get(0).url()).isEqualTo("/aspsps/" + fitting);
        assertThat(logger.entries().get(fitting - 1).url()).isEqualTo("/aspsps/1");
    }

    @Test
    void clear_resetsTheBudget() {
        String capped = "x".repeat(EnableBankingCallLogger.MAX_BODY_CHARS);
        int fitting = EnableBankingCallLogger.MAX_TOTAL_BODY_CHARS / EnableBankingCallLogger.MAX_BODY_CHARS;
        for (int i = 0; i < fitting; i++) logger.log("GET", "/before/" + i, "", 200, capped);
        logger.clear();

        for (int i = 0; i < fitting; i++) logger.log("GET", "/after/" + i, "", 200, capped);

        assertThat(logger.entries()).hasSize(fitting);
    }

    @Test
    void filter_handsTheCallerAProviderCharsetBodyIntact() {
        // The filter decodes with the declared charset and re-encodes in UTF-8: the rebuilt
        // response must say UTF-8, or the connector would read the holder name as mojibake.
        String body = "{\"name\": \"Hélène Synthétique\"}";
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK, STRATEGIES)
            .header("Content-Type", "application/json;charset=ISO-8859-1")
            .header("Content-Length", String.valueOf(body.getBytes(StandardCharsets.ISO_8859_1).length))
            .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(body.getBytes(StandardCharsets.ISO_8859_1))))
            .build());

        String read = clientWith(exchange).get().uri("/accounts/uid/details")
            .retrieve().bodyToMono(String.class).block();

        assertThat(read).isEqualTo(body);
        assertThat(logger.entries().get(0).responseBody()).isEqualTo(body);
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
