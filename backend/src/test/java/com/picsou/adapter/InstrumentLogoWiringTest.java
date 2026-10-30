package com.picsou.adapter;

import com.picsou.port.InstrumentLogoPort;
import com.picsou.port.PriceProviderPort;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.InstrumentLogoRepository;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.service.InstrumentLogoService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pins the seams a unit test cannot see: {@link YahooFinancePriceProvider} now has two public
 * constructors, so Spring must pick the one that receives the shared {@link YahooCooldown}; and
 * {@link InstrumentLogoService} must resolve through {@link InstrumentLogoPort} rather than the
 * concrete adapter. Either mistake compiles and passes every other test, then fails at boot or
 * silently stops the price path's 429s from reaching the logo path.
 */
class InstrumentLogoWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(WebClient.class, () -> WebClient.builder().build())
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(YahooCooldown.class)
        .withBean(YahooFinancePriceProvider.class)
        .withBean(CoinGeckoPriceProvider.class)
        .withBean(YahooQuotePageLogoProvider.class)
        .withBean(InstrumentLogoRepository.class, () -> mock(InstrumentLogoRepository.class))
        .withBean(AccountHoldingRepository.class, () -> mock(AccountHoldingRepository.class))
        .withBean(PriceSnapshotRepository.class, () -> mock(PriceSnapshotRepository.class))
        .withBean(InstrumentLogoService.class);

    @Test
    void theServiceResolvesThroughThePort_andThePortResolvesToTheQuotePageAdapter() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(InstrumentLogoService.class)).isNotNull();
            assertThat(context.getBean(InstrumentLogoPort.class)).isInstanceOf(YahooQuotePageLogoProvider.class);
            // Two price providers, both still beans: the logo adapter did not displace either.
            assertThat(context.getBeansOfType(PriceProviderPort.class)).hasSize(2);
            assertThat(context.getBeansOfType(YahooCooldown.class)).hasSize(1);
        });
    }
}
