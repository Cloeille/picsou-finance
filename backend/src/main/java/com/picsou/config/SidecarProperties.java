package com.picsou.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Base URLs of the connector sidecars, keyed by compose service name
 * ({@code app.sidecars.<name>.url}).
 *
 * <p>{@code application.yml} is the only place a default URL exists. A consumer that asks
 * for a sidecar missing from it fails with the property name rather than silently falling
 * back to a hostname hard-coded next to the code that uses it.
 */
@ConfigurationProperties(prefix = "app")
public record SidecarProperties(Map<String, Endpoint> sidecars) {

    public record Endpoint(String url) {}

    public SidecarProperties {
        sidecars = sidecars == null ? Map.of() : Map.copyOf(sidecars);
    }

    /** Programmatic equivalent of the YAML map, for tests that point a sidecar at a stub server. */
    public static SidecarProperties of(Map<String, String> urlsByService) {
        Map<String, Endpoint> endpoints = new LinkedHashMap<>();
        urlsByService.forEach((name, url) -> endpoints.put(name, new Endpoint(url)));
        return new SidecarProperties(endpoints);
    }

    public Optional<String> findUrl(String serviceName) {
        return Optional.ofNullable(sidecars.get(serviceName)).map(Endpoint::url);
    }

    /**
     * @throws IllegalStateException if no URL is configured for {@code serviceName}
     */
    public String url(String serviceName) {
        return findUrl(serviceName).orElseThrow(() -> new IllegalStateException(
            "No URL configured for sidecar '" + serviceName + "': set app.sidecars." + serviceName + ".url"));
    }
}
