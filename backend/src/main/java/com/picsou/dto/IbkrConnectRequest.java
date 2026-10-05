package com.picsou.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for storing Interactive Brokers Flex Web Service credentials.
 *
 * @param token   Flex Web Service token generated in Client Portal (read-only, 6h–1y)
 * @param queryId id of the pre-built "Open Positions" Flex Query
 */
public record IbkrConnectRequest(@NotBlank String token, @NotBlank String queryId) {}
