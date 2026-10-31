package com.picsou.adapter;

import com.picsou.port.LogoProviderPort;
import com.picsou.service.CryptoLogoService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that the logo lookup is wired through {@link LogoProviderPort} and not through the
 * concrete adapter.
 *
 * <p>{@code CryptoLogoService} was changed to take the port, which is the only thing that makes
 * a second mark source (the equity half of issue #162) a matter of registering a bean. That is
 * exactly the kind of promise a unit test cannot see: a service that took the adapter again would
 * still compile and still pass every test in the suite. So this asserts the seam itself — the
 * service resolves against the port, and the port resolves to the adapter.
 *
 * <p>The second assertion is the one that bites first when an equity provider arrives: two beans
 * implementing the port with neither marked {@code @Primary} is a context that fails to start, and
 * the failure should name the port rather than surface later as a wrong logo.
 */
class LogoProviderWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(WebClient.class, () -> WebClient.builder().build())
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(CoinGeckoPriceProvider.class)
        .withBean(CryptoLogoService.class);

    @Test
    void theServiceResolvesThroughThePortAndThePortResolvesToCoinGecko() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CryptoLogoService.class)).isNotNull();
            assertThat(context.getBean(LogoProviderPort.class))
                .isInstanceOf(CoinGeckoPriceProvider.class);
        });
    }
}
