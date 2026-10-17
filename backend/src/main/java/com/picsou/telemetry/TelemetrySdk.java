package com.picsou.telemetry;

/**
 * Seam over the Sentry Java SDK so the consent logic is testable without touching the real
 * (global, static) SDK. The only production implementation is {@link SentryTelemetrySdk}.
 */
public interface TelemetrySdk {

    /** Initialises the SDK; a no-op if already initialised. */
    void init(String dsn, String environment, String release);

    /** Closes the SDK and drops anything still queued. */
    void close();

    boolean isInitialized();

    /** Captures an exception; a no-op if the SDK is not initialised. */
    void capture(Throwable throwable);
}
