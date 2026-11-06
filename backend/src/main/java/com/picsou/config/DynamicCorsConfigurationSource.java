package com.picsou.config;

import com.picsou.repository.AppSettingRepository;
import com.picsou.service.SetupService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Resolves CORS configuration per-request by looking up
 * {@code cors.allowed-origins} in {@code app_setting}, falling back to the
 * {@code ALLOWED_ORIGINS} env var when the DB value is missing (fresh install
 * or env-only operator).
 *
 * <p>Why per-request, not per-startup: the setup wizard writes CORS origins
 * at Step 2 on a running JVM. Re-reading on each CORS preflight lets the
 * new setting take effect without a container restart. The lookup is a
 * single indexed {@code findByKey}, dwarfed by the cost of the request
 * itself — no caching headache, no stale reads.
 *
 * <p>First-install fallback: when nothing has been written yet, this source
 * returns the env-driven origins so the operator's {@code ALLOWED_ORIGINS}
 * (or the {@code *} default) keeps working until the wizard writes its
 * first entry.
 */
public class DynamicCorsConfigurationSource implements CorsConfigurationSource {

    private final AppSettingRepository settingRepository;
    private final List<String> fallbackOrigins;

    public DynamicCorsConfigurationSource(AppSettingRepository settingRepository,
                                          @Value("${app.cors.allowed-origins:}") String fallbackOrigins) {
        this.settingRepository = settingRepository;
        this.fallbackOrigins = sanitize(parseCsv(fallbackOrigins));
    }

    @Override
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/") ? originPolicy() : null;
    }

    /**
     * The origin policy of the API routes, for callers outside them (the SSO chain's cross-site
     * check) that must apply the same allow-list.
     */
    public CorsConfiguration originPolicy() {
        List<String> origins = resolveOrigins();
        if (origins.isEmpty()) {
            return null;
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Accept"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        return config;
    }

    private List<String> resolveOrigins() {
        return settingRepository.findByKey(SetupService.KEY_CORS_ALLOWED_ORIGINS)
            .map(s -> sanitize(parseCsv(s.getValue())))
            .filter(list -> !list.isEmpty())
            .orElse(fallbackOrigins);
    }

    /**
     * Strip wildcard entries: a wildcard origin is incompatible with
     * {@code allowCredentials=true} (cross-origin credentialed reads). If the
     * operator set {@code *}, fail closed (no cross-origin allowed) rather
     * than silently echoing every origin.
     */
    private static List<String> sanitize(List<String> origins) {
        return origins.stream()
            .filter(o -> !o.contains("*"))
            .toList();
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null) return List.of();
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
