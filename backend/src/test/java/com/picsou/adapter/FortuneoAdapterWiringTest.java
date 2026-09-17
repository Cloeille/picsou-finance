package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FortuneoAdapterWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withPropertyValues("app.fortuneo-auth.url=http://fortuneo-auth:8001")
        .withBean(ObjectMapper.class)
        .withBean(FortuneoAdapter.class);

    @Test
    void springSelectsTheProductionConstructor() {
        contextRunner.run(context -> assertThat(context)
            .hasNotFailed()
            .hasSingleBean(FortuneoAdapter.class));
    }

    @Test
    void rejectsPlainHttpToARemoteSidecar() {
        contextRunner
            .withPropertyValues("app.fortuneo-auth.url=http://192.168.1.50:8001")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void acceptsHttpsToARemoteSidecar() {
        contextRunner
            .withPropertyValues("app.fortuneo-auth.url=https://fortuneo.example.test")
            .run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(FortuneoAdapter.class));
    }
}
