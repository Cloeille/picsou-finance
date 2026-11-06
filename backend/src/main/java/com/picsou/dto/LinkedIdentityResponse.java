package com.picsou.dto;

import java.time.Instant;

public record LinkedIdentityResponse(
    Long id,
    String email,
    String displayName,
    Instant createdAt,
    Instant lastLoginAt,
    boolean currentIssuer
) {}
