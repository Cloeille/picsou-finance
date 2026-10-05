package com.picsou.dto;

import java.time.Instant;

/**
 * Interactive Brokers connection status for the settings UI.
 *
 * @param connected     whether a connection is stored for the member
 * @param connectionId  row id (null when not connected)
 * @param status        "CONNECTED" or "ERROR" (last sync outcome)
 * @param lastSyncedAt  last successful sync, null if never
 * @param maskedToken   token with only the last 4 chars visible, null when not connected
 */
public record IbkrConnectionStatusResponse(
    boolean connected,
    Long connectionId,
    String status,
    Instant lastSyncedAt,
    String maskedToken
) {}
