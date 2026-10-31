package com.picsou.adapter;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/** A real {@link YahooFinancePriceProvider} answering from canned JSON, for tests outside this package. */
public final class YahooFinanceStubs {

    private YahooFinanceStubs() {}

    /** {@code routeToJson} maps a request URL to a response body, or to null for a 404. */
    public static YahooFinancePriceProvider provider(Function<String, String> routeToJson) {
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            String body = routeToJson.apply(request.url().toString());
            HttpStatus status = body == null ? HttpStatus.NOT_FOUND : HttpStatus.OK;
            return Mono.just(ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body == null ? "{\"error\":\"not found\"}" : body).build());
        }).build();
        return new YahooFinancePriceProvider(client);
    }
}
