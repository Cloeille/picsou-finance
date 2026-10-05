package com.picsou.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code dsn} is null unless telemetry is effectively enabled (DSN configured and consent given).
 * The app serialises with {@code non_null}; the field is forced so the contract's explicit
 * {@code "dsn": null} is honoured.
 */
public record TelemetryConfigResponse(
    boolean enabled,
    @JsonInclude(JsonInclude.Include.ALWAYS) String dsn,
    String environment,
    String release
) {}
