package com.picsou.dto;

import java.time.Instant;
import java.util.List;

/**
 * Safe projection of an {@code oauth2_authorization} row for the connected-apps management API
 * ({@code ConnectedAppsController}). {@code id} is the Spring-AS-generated authorization id
 * (a UUID string, not a JPA-managed numeric id — this table is owned by Spring Authorization
 * Server, not Hibernate). {@code lastUsedAt} is always {@code null} today: the Spring AS JDBC
 * schema (V54) does not track a last-used timestamp, only issuance; the field exists so the
 * frontend contract (Task 13) doesn't need to change if that ever becomes available.
 */
public record ConnectedAppResponse(
    String id,
    String clientName,
    List<String> scopes,
    Instant issuedAt,
    Instant lastUsedAt
) {}
