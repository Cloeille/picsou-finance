package com.picsou.adapter;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Map;

/** A real {@link CoinGeckoPriceProvider} that never leaves the JVM, for tests outside this package. */
public final class CoinGeckoStubs {

    private CoinGeckoStubs() {}

    /** A registry holding exactly {@code coins}, backed by a CoinGecko that answers every call with {@code []}. */
    public static CoinGeckoPriceProvider provider(CryptoAssetRegistry registry) {
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(
            ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("[]").build())).build();
        return new CoinGeckoPriceProvider(client, registry);
    }

    public static CryptoAssetRegistry registry(Map<String, CryptoAssetRegistry.Coin> coins) {
        return CryptoAssetRegistry.of(coins);
    }

    /** The composite over an offline CoinGecko and the given Yahoo, sharing one registry. */
    public static CompositePriceProvider composite(YahooFinancePriceProvider yahoo, CryptoAssetRegistry registry) {
        return new CompositePriceProvider(provider(registry), yahoo, registry, Clock.systemUTC());
    }
}
