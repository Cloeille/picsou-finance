package com.picsou.dto;

import com.picsou.model.ScpiFundOutcome;
import com.picsou.model.ScpiProvider;

import java.time.Instant;

/**
 * One fund a sync has seen. {@code accountId} is null when no account is linked
 * to it -- an ambiguous or ignored fund, or one that has not synced yet.
 * Every fund written by the same sync run shares one {@code lastSyncedAt}.
 */
public record ScpiFundResponse(
    ScpiProvider provider,
    String fundCode,
    String label,
    boolean ignored,
    ScpiFundOutcome lastOutcome,
    Instant lastSeenAt,
    Instant lastSyncedAt,
    Long accountId,
    String accountName
) {}
