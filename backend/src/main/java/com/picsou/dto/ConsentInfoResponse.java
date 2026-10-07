package com.picsou.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response for {@code GET /api/oauth2/consent-info}, consumed by the SPA consent screen
 * (Task 11) to render the scope checkboxes for a pending {@code /oauth2/authorize} request that
 * Spring Authorization Server redirected to {@code /consent}.
 */
public record ConsentInfoResponse(
    @JsonProperty("client_name") String clientName,
    @JsonProperty("requested_scopes") List<String> requestedScopes,
    @JsonProperty("state") String state
) {}
