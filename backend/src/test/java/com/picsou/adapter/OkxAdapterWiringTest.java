package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class OkxAdapterWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(ObjectMapper.class)
        .withBean(Clock.class, () -> OkxAdapterTest.CLOCK)
        .withBean(OkxAdapter.class);

    @Test
    void springWiresTheProductionHostsInRegionDisplayOrder() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(OkxAdapter.class);
            OkxAdapter adapter = context.getBean(OkxAdapter.class);
            assertThat(adapter.regions()).containsExactly("GLOBAL", "EEA", "US");
            assertThat(adapter.regions()).map(adapter::hostForRegion).containsExactly(
                URI.create("https://www.okx.com"), URI.create("https://eea.okx.com"), URI.create("https://us.okx.com"));
        });
    }
}
