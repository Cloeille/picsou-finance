package com.picsou.adapter.sidecar;

import com.picsou.config.SidecarProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/** Builds the sidecar factory a test needs without booting the packaged {@code application.yml}. */
public final class SidecarTestSupport {

    public static final String API_KEY = "test-key";

    private SidecarTestSupport() {
    }

    /** A factory whose {@code serviceName} sidecar answers at {@code url}. */
    public static SidecarWebClientFactory factory(String serviceName, String url) {
        return factory(serviceName, url, API_KEY);
    }

    /** Same, with an explicit shared key ({@code null} or blank means the backend has none). */
    public static SidecarWebClientFactory factory(String serviceName, String url, String apiKey) {
        return new SidecarWebClientFactory(SidecarProperties.of(Map.of(serviceName, url)), apiKey);
    }

    /** Binds {@code app.sidecars.*} in an {@code ApplicationContextRunner}. */
    @Configuration
    @EnableConfigurationProperties(SidecarProperties.class)
    public static class BindSidecarProperties {
    }
}
