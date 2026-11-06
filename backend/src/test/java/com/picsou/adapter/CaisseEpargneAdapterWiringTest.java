package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarTestSupport;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CaisseEpargneAdapterWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(SidecarTestSupport.BindSidecarProperties.class)
        .withPropertyValues("app.sidecar.api-key=test-key")
        .withBean(ObjectMapper.class)
        .withBean(SidecarWebClientFactory.class)
        .withBean(CaisseEpargneAdapter.class);

    @Test
    void theUrlShippedInApplicationYmlIsAValidSidecarUrl() {
        contextRunner
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(CaisseEpargneAdapter.class));
    }

    @Test
    void aMissingEntryFailsStartupNamingTheProperty() {
        contextRunner.run(context -> assertThat(context.getStartupFailure())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .rootCause()
            .hasMessageContaining("app.sidecars.caisse-epargne-auth.url"));
    }

    @Test
    void theUrlIsReadFromAppSidecars() {
        contextRunner
            .withPropertyValues("app.sidecars.caisse-epargne-auth.url=http://caisse-epargne-auth:9999")
            .run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(CaisseEpargneAdapter.class));
    }

    @Test
    void anInvalidUrlFailsStartupRatherThanBeingIgnored() {
        contextRunner
            .withPropertyValues("app.sidecars.caisse-epargne-auth.url=http://evil.example.com:8001")
            .run(context -> assertThat(context).hasFailed());
    }
}
