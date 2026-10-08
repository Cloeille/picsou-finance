package com.picsou.dto;

import java.time.Instant;

/**
 * SimpleFIN connection status. The access URL itself is never returned.
 *
 * @param connected     whether a connection is stored for the member
 * @param connectionId  row id (null when not connected)
 * @param status        "CONNECTED" or "ERROR" (last sync outcome)
 * @param lastSyncedAt  last successful sync, null if never
 * @param maskedToken   last four characters of the access username, or a fixed mask; null when not connected
 */
public record SimplefinConnectionStatusResponse(
    boolean connected,
    Long connectionId,
    String status,
    Instant lastSyncedAt,
    String maskedToken
) {}
