package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AmundiAdapterWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withPropertyValues("app.amundi-auth.url=http://amundi-auth:8001")
        .withBean(ObjectMapper.class)
        .withBean(com.picsou.adapter.sidecar.SidecarWebClientFactory.class,
            () -> new com.picsou.adapter.sidecar.SidecarWebClientFactory("test-key"))
        .withBean(AmundiAdapter.class);

    @Test
    void springSelectsTheProductionConstructor() {
        contextRunner.run(context -> assertThat(context)
            .hasNotFailed()
            .hasSingleBean(AmundiAdapter.class));
    }
}
