package com.picsou.dto;

import java.time.Instant;

/**
 * Wise connection status. The token itself is never returned.
 *
 * @param connected     whether a token is stored for the member
 * @param connectionId  row id (null when not connected)
 * @param status        "CONNECTED", "ERROR" (last sync failed) or "REAUTH_REQUIRED" (Wise refused the token)
 * @param lastSyncedAt  last successful sync, null if never
 * @param maskedToken   last four characters of the token behind a mask; null when not connected
 */
public record WiseConnectionStatusResponse(
    boolean connected,
    Long connectionId,
    String status,
    Instant lastSyncedAt,
    String maskedToken
) {}
