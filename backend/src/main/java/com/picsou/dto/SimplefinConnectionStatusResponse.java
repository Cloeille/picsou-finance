package com.picsou.dto;

import java.time.Instant;

/**
 * SimpleFIN connection status. The access URL itself is never returned.
 *
 * @param maskedToken last four characters of the access username, or a fixed mask
 */
public record SimplefinConnectionStatusResponse(
    boolean connected,
    Long connectionId,
    String status,
    Instant lastSyncedAt,
    String maskedToken
) {}
