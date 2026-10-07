package com.picsou.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.dto.TelemetryConfigResponse;
import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Opt-in anonymous telemetry. Effective state = a DSN is configured AND the instance admin
 * consented ({@code app_setting} key {@value #KEY_CONSENT}). Both gates are re-read on every
 * call, so a consent change takes effect immediately and nothing is ever sent "by default".
 *
 * <p>No identifier of any kind is attached (no user, no device id, no session). Browser events
 * arrive through {@link #tunnel(byte[])}, which re-scrubs them and forwards them from this
 * server, so the user's IP never reaches the collector.
 */
@Service
public class TelemetryService {

    public static final String KEY_CONSENT = "telemetry.consent";
    public static final int MAX_ENVELOPE_BYTES = 200 * 1024;
    public static final int MAX_IN_FLIGHT_SENDS = 16;

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    public enum Consent { ENABLED, DISABLED, UNSET }

    private final AppSettingRepository settings;
    private final TelemetrySdk sdk;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final String dsn;
    private final String environment;
    private final String release;
    private final Semaphore sendPermits = new Semaphore(MAX_IN_FLIGHT_SENDS);
    private final Set<CompletableFuture<?>> pendingSends = ConcurrentHashMap.newKeySet();
    private final Object sendLock = new Object();

    @Autowired
    public TelemetryService(AppSettingRepository settings,
                            TelemetrySdk sdk,
                            ObjectMapper mapper,
                            @Value("${app.telemetry.dsn:}") String dsn,
                            @Value("${app.telemetry.environment:production}") String environment,
                            @Value("${info.app.version:dev}") String release) {
        this(settings, sdk, mapper, dsn, environment, release, defaultHttpClient());
    }

    TelemetryService(AppSettingRepository settings, TelemetrySdk sdk, ObjectMapper mapper,
                     String dsn, String environment, String release, HttpClient httpClient) {
        this.settings = settings;
        this.sdk = sdk;
        this.mapper = mapper;
        this.dsn = dsn == null ? "" : dsn.trim();
        this.environment = environment == null || environment.isBlank() ? "production" : environment.trim();
        this.release = release == null || release.isBlank() ? "dev" : release.trim();
        this.httpClient = httpClient;
    }

    private static HttpClient defaultHttpClient() {
        // Redirects are never followed: a 30x from the collector must not move the envelope to
        // another host than the one the operator configured.
        return HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    // ---- state -------------------------------------------------------------------------------

    /** A valid DSN is configured (env {@code APP_TELEMETRY_DSN}). */
    public boolean isAvailable() {
        return TelemetryDsn.parse(dsn).isPresent();
    }

    @Transactional(readOnly = true)
    public Consent consent() {
        return settings.findByKey(KEY_CONSENT)
            .map(AppSetting::getValue)
            .map(v -> "ENABLED".equals(v) ? Consent.ENABLED : "DISABLED".equals(v) ? Consent.DISABLED : Consent.UNSET)
            .orElse(Consent.UNSET);
    }

    @Transactional(readOnly = true)
    public boolean isEnabled() {
        return isAvailable() && consent() == Consent.ENABLED;
    }

    /**
     * Persists the admin's choice and applies it in-process (SDK initialised / closed).
     *
     * @throws IllegalStateException when enabling while no DSN is configured: consent given to a
     *         collector that does not exist yet must not silently activate once one is added.
     */
    @Transactional
    public void setEnabled(boolean enabled) {
        if (enabled && !isAvailable()) {
            throw new IllegalStateException("Telemetry is not configured on this instance");
        }
        AppSetting setting = settings.findByKey(KEY_CONSENT)
            .orElseGet(() -> AppSetting.builder().key(KEY_CONSENT).build());
        setting.setValue(enabled ? Consent.ENABLED.name() : Consent.DISABLED.name());
        settings.save(setting);
        applySdkState(enabled && isAvailable());
        if (!enabled) {
            cancelPendingSends();
        }
        log.info("telemetry.consent.changed enabled={}", enabled);
    }

    public TelemetryConfigResponse config() {
        boolean available = isAvailable();
        Consent consent = consent();
        boolean enabled = available && consent == Consent.ENABLED;
        return new TelemetryConfigResponse(enabled, enabled ? dsn : null, environment, release,
            available, consent.name());
    }

    // ---- SDK lifecycle -----------------------------------------------------------------------

    @EventListener(ApplicationReadyEvent.class)
    public void initOnStartup() {
        try {
            applySdkState(isEnabled());
        } catch (RuntimeException e) {
            // Telemetry must never prevent the application from starting.
            log.error("telemetry.startup.failed", e);
        }
    }

    @PreDestroy
    void shutdown() {
        cancelPendingSends();
        sdk.close();
    }

    private void applySdkState(boolean enabled) {
        if (enabled) {
            sdk.init(dsn, environment, release);
        } else {
            sdk.close();
        }
    }

    /** Server-side error capture; a no-op unless telemetry is effectively enabled. Never throws. */
    public void captureServerError(Throwable throwable) {
        try {
            if (throwable != null && sdk.isInitialized() && isEnabled()) {
                sdk.capture(throwable);
            }
        } catch (RuntimeException e) {
            log.error("telemetry.capture.failed", e);
        }
    }

    // ---- tunnel ------------------------------------------------------------------------------

    /**
     * Sanitises a browser envelope and queues a bounded asynchronous send to the configured
     * collector. Returns false only when the in-flight send limit is full.
     */
    public boolean tunnel(byte[] envelope) {
        try {
            if (envelope == null || envelope.length == 0 || !isEnabled()) {
                return true;
            }
            Optional<TelemetryDsn> parsed = TelemetryDsn.parse(dsn);
            if (parsed.isEmpty()) {
                return true;
            }
            TelemetryDsn target = parsed.get();
            Optional<byte[]> body = TelemetryEnvelopeSanitizer.sanitize(envelope, target.raw(), mapper);
            if (body.isEmpty()) {
                return true;
            }
            HttpRequest request = HttpRequest.newBuilder(target.envelopeUri())
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-sentry-envelope")
                .header("X-Sentry-Auth", target.authHeader())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.get()))
                .build();
            synchronized (sendLock) {
                if (!isEnabled()) {
                    return true;
                }
                if (!sendPermits.tryAcquire()) {
                    return false;
                }
                CompletableFuture<HttpResponse<Void>> future;
                try {
                    future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
                } catch (RuntimeException e) {
                    sendPermits.release();
                    log.error("telemetry.tunnel.failed", e);
                    return true;
                }
                pendingSends.add(future);
                future.whenComplete((response, failure) -> {
                    pendingSends.remove(future);
                    sendPermits.release();
                    if (failure != null && !(failure instanceof java.util.concurrent.CancellationException)) {
                        log.error("telemetry.tunnel.failed", failure);
                    } else if (response != null) {
                        log.debug("telemetry.tunnel.forwarded status={}", response.statusCode());
                    }
                });
            }
        } catch (Exception e) {
            log.error("telemetry.tunnel.failed", e);
        }
        return true;
    }

    private void cancelPendingSends() {
        synchronized (sendLock) {
            for (CompletableFuture<?> pending : pendingSends) {
                pending.cancel(true);
            }
            pendingSends.clear();
        }
    }
}
