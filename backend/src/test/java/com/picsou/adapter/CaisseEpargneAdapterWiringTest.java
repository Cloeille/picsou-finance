package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CaisseEpargneAdapterWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(ObjectMapper.class)
        .withBean(SidecarWebClientFactory.class, () -> new SidecarWebClientFactory("test-key"))
        .withBean(CaisseEpargneAdapter.class);

    @Test
    void springSelectsTheProductionConstructorWithTheDefaultSidecarUrl() {
        // No app.caisse-epargne-auth.url property: the default must be a valid sidecar URL.
        contextRunner.run(context -> assertThat(context)
            .hasNotFailed()
            .hasSingleBean(CaisseEpargneAdapter.class));
    }

    @Test
    void theUrlIsReadFromAppCaisseEpargneAuthUrl() {
        contextRunner
            .withPropertyValues("app.caisse-epargne-auth.url=http://caisse-epargne-auth:9999")
            .run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(CaisseEpargneAdapter.class));
    }

    @Test
    void anInvalidUrlFailsStartupRatherThanBeingIgnored() {
        contextRunner
            .withPropertyValues("app.caisse-epargne-auth.url=http://evil.example.com:8001")
            .run(context -> assertThat(context).hasFailed());
    }
}
