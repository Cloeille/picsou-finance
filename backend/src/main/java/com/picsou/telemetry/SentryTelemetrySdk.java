package com.picsou.telemetry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sentry.ISerializer;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;

/**
 * Programmatic Sentry initialisation — no Spring Boot starter, no autoconfiguration. Every option
 * that could widen what leaves the process is pinned off, and every event goes through the same
 * allowlist {@link TelemetryScrubber} as tunneled browser events (serialize → scrub JSON →
 * deserialize), so a field the SDK adds in a future version is dropped by default.
 *
 * <p>{@code enableUncaughtExceptionHandler} is {@code false} on purpose: it would install a
 * JVM-global default handler. Capture points are explicit instead
 * ({@code GlobalExceptionHandler}), which keeps the surface small and the behaviour predictable.
 */
@Component
public class SentryTelemetrySdk implements TelemetrySdk {

    private static final Logger log = LoggerFactory.getLogger(SentryTelemetrySdk.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final ObjectMapper mapper;

    public SentryTelemetrySdk(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public synchronized void init(String dsn, String environment, String release) {
        if (Sentry.isEnabled()) {
            return;
        }
        Sentry.init(options -> {
            options.setDsn(dsn);
            options.setRelease(release);
            options.setEnvironment(environment);
            options.setSendDefaultPii(false);
            options.setMaxBreadcrumbs(0);
            options.setAttachStacktrace(true);
            options.setEnableUncaughtExceptionHandler(false);
            options.setEnableShutdownHook(false); // closed by TelemetryService#shutdown
            options.setTracesSampleRate(null);
            options.setEnableExternalConfiguration(false); // never read SENTRY_* env / sentry.properties
            options.setEnableAutoSessionTracking(false);
            options.setSendClientReports(false);
            options.setSendModules(false);
            options.setAttachServerName(false);
            options.setServerName(null);
            options.setEnableSpotlight(false);
            options.setCacheDirPath(null);
            options.setBeforeBreadcrumb((breadcrumb, hint) -> null);
            options.setBeforeSend((event, hint) -> scrub(event, options.getSerializer(), mapper));
        });
        log.info("telemetry.sdk.initialized environment={}", environment);
    }

    @Override
    public synchronized void close() {
        if (Sentry.isEnabled()) {
            Sentry.close();
            log.info("telemetry.sdk.closed");
        }
    }

    @Override
    public boolean isInitialized() {
        return Sentry.isEnabled();
    }

    @Override
    public void capture(Throwable throwable) {
        if (Sentry.isEnabled() && throwable != null) {
            Sentry.captureException(throwable);
        }
    }

    /** @return the allowlist-rebuilt event, or {@code null} (= drop) if anything goes wrong. */
    static SentryEvent scrub(SentryEvent event, ISerializer serializer, ObjectMapper mapper) {
        try {
            StringWriter writer = new StringWriter();
            serializer.serialize(event, writer);
            Map<String, Object> scrubbed = TelemetryScrubber.scrubEvent(
                mapper.readValue(writer.toString(), MAP));
            return serializer.deserialize(
                new StringReader(mapper.writeValueAsString(scrubbed)), SentryEvent.class);
        } catch (Exception e) {
            log.error("telemetry.sdk.scrub.failed type={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** Test helper: serializer usable without a live SDK. */
    static ISerializer serializerFor(SentryOptions options) {
        return options.getSerializer();
    }
}
