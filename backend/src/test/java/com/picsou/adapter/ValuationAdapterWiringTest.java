package com.picsou.adapter;

import com.picsou.model.PropertyKind;
import com.picsou.port.GeocodingPort;
import com.picsou.port.HousingPriceIndexPort;
import com.picsou.port.PropertyValuationPort;
import com.picsou.port.PropertyValuationPort.ValuationInput;
import com.picsou.port.PropertyValuationPort.ValuationResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how the <em>production</em> valuation adapters are built, which the stubbed-response
 * tests cannot: those inject an {@code ExchangeFunction}, and a hand-made {@code ClientResponse}
 * decodes with its own strategies rather than the WebClient's. Two regressions therefore leave
 * that whole suite green, and both shipped:
 *
 * <ul>
 *   <li><b>Constructor selection.</b> Each adapter declares two constructors and unit tests call
 *       one directly, so Spring failing to choose between them ("No default constructor found")
 *       was invisible until the container crash-looped.
 *   <li><b>Buffer limit.</b> A Cerema response carries every vintage back to 2010 with ~200
 *       indicator columns each — about 265 KB, just past WebClient's 256 KB default. Over the
 *       limit the body is never assembled, so every commune failed, and the service reported it
 *       as "no comparable transactions in this municipality" rather than as an error.
 * </ul>
 */
class ValuationAdapterWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(CeremaDv3fValuationProvider.class)
        .withBean(GeoplateformeGeocoder.class)
        .withBean(InseeBdmIndexProvider.class);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void springSelectsTheProductionConstructorWithoutAnyConfiguredUrl() {
        // No property values on purpose: every @Value carries a default, so a stripped
        // configuration must still boot.
        contextRunner.run(context -> assertThat(context)
            .hasNotFailed()
            .hasSingleBean(CeremaDv3fValuationProvider.class)
            .hasSingleBean(GeoplateformeGeocoder.class)
            .hasSingleBean(InseeBdmIndexProvider.class));
    }

    @Test
    void baseUrlsCanBeOverridden() {
        contextRunner.withPropertyValues(
                "app.valuation.cerema.base-url=http://localhost:1",
                "app.geocoding.base-url=http://localhost:1/geocodage",
                "app.valuation.insee.base-url=http://localhost:1/series")
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void adaptersExposeExactlyOneInjectableConstructor() {
        for (Class<?> adapter : new Class<?>[]{
            CeremaDv3fValuationProvider.class, GeoplateformeGeocoder.class, InseeBdmIndexProvider.class}) {

            long injectable = java.util.Arrays.stream(adapter.getDeclaredConstructors())
                .filter(c -> c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class))
                .count();

            assertThat(injectable)
                .as("%s must mark exactly one constructor @Autowired; it declares %d constructors",
                    adapter.getSimpleName(), adapter.getDeclaredConstructors().length)
                .isEqualTo(1);
        }
    }

    @Test
    void portsResolveThroughTheirInterfaces() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(
                CeremaDv3fValuationProvider.class, GeoplateformeGeocoder.class, InseeBdmIndexProvider.class);
            context.refresh();

            assertThat(context.getBean(PropertyValuationPort.class)).isNotNull();
            assertThat(context.getBean(GeocodingPort.class)).isNotNull();
            assertThat(context.getBean(HousingPriceIndexPort.class)).isNotNull();
        }
    }

    @Test
    void theProductionClientReadsACommunePayloadBiggerThanTheFrameworkDefault() throws IOException {
        // Served over a real socket so the production WebClient's own codec configuration is
        // what decodes it. With the framework default this assertion fails, and it fails the
        // way the bug did: an empty Optional, indistinguishable from "this commune has no sales".
        String payload = communePayloadLargerThan(256 * 1024);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/indicateurs/", exchange -> {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        CeremaDv3fValuationProvider provider =
            new CeremaDv3fValuationProvider("http://127.0.0.1:" + server.getAddress().getPort());

        Optional<ValuationResult> result = provider.estimate(new ValuationInput(
            "29019", "29", "FR", PropertyKind.APARTMENT,
            new BigDecimal("100"), null, (short) 5, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get().pricePerSqm()).isEqualByComparingTo("1768.75");
        assertThat(result.get().estimatedValue()).isEqualByComparingTo("176875.00");
    }

    /**
     * A response shaped like the real one — many vintages, each with a wide column set — padded
     * past {@code bytes}. The usable indicators sit on the newest vintage, so a truncated or
     * unassembled body yields nothing rather than a wrong number.
     */
    private static String communePayloadLargerThan(int bytes) {
        StringBuilder results = new StringBuilder();
        int year = 2010;
        // Filler vintages first: the adapter walks backwards, so these must not be picked.
        while (results.length() < bytes) {
            if (results.length() > 0) results.append(',');
            results.append("{\"annee\":\"").append(year++).append("\",\"echelle\":\"communes\",")
                .append("\"code\":\"29019\",\"libelle\":\"Brest\",")
                .append("\"nbtrans_cod121\":0,\"pxm2_median_cod121\":null,")
                .append("\"nbtrans_cod111\":0,\"pxm2_median_cod111\":null");
            for (int i = 0; i < 40; i++) {
                results.append(",\"filler_").append(i).append("\":").append(1000 + i);
            }
            results.append('}');
        }
        // Newest vintage last, matching the API's oldest-first ordering.
        results.append(",{\"annee\":\"").append(year).append("\",\"echelle\":\"communes\",")
            .append("\"code\":\"29019\",\"libelle\":\"Brest\",")
            .append("\"nbtrans_cod121x5\":131,\"pxm2_median_cod121x5\":1768.75,")
            .append("\"nbtrans_cod121\":1874,\"pxm2_median_cod121\":2150.54}");

        return "{\"count\":99,\"next\":null,\"previous\":null,\"results\":[" + results + "]}";
    }
}
