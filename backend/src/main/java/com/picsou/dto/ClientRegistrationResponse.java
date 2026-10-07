package com.picsou.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * RFC 7591 Dynamic Client Registration response ({@code 201 Created} body). Deliberately has no
 * {@code client_secret} / {@code client_secret_expires_at} field — every client registered through
 * this endpoint is a public PKCE client (see {@code com.picsou.config.DynamicClientRegistrationController}),
 * so no secret is ever generated.
 *
 * <p>{@code client_id_issued_at} is a JSON <em>number</em> of seconds since the epoch, per RFC 7591
 * §3.2.1 ("client_id_issued_at ... a number representing the time ... measured in seconds since
 * 1970-01-01T00:00:00Z"), not an ISO-8601 string — {@code app.jackson.write-dates-as-timestamps} is
 * {@code false} project-wide, so an {@code Instant} field here would have silently serialized as a
 * string. The controller passes {@link java.time.Instant#getEpochSecond()}.
 */
public record ClientRegistrationResponse(
    @JsonProperty("client_id") String clientId,
    @JsonProperty("client_id_issued_at") long clientIdIssuedAt,
    @JsonProperty("redirect_uris") List<String> redirectUris,
    @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod,
    @JsonProperty("grant_types") List<String> grantTypes,
    @JsonProperty("scope") String scope
) {}
