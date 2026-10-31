package com.picsou.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * RFC 7591 Dynamic Client Registration request body ({@code POST /oauth2/register}, unauthenticated
 * — see {@code com.picsou.config.DynamicClientRegistrationController}). Every field is
 * attacker-controlled input; the controller validates {@code redirectUris} and {@code scope} before
 * a {@link org.springframework.security.oauth2.server.authorization.client.RegisteredClient} is
 * ever created.
 *
 * <p>{@code tokenEndpointAuthMethod} and {@code grantTypes} are accepted (so a spec-compliant client
 * can send them) but never trusted: Picsou only ever issues public, PKCE-only clients, so the
 * controller always coerces the auth method to {@code none} and always registers exactly
 * {@code authorization_code} + {@code refresh_token}, regardless of what is requested here.
 */
public record ClientRegistrationRequest(
    @JsonProperty("client_name") String clientName,
    @JsonProperty("redirect_uris") List<String> redirectUris,
    @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod,
    @JsonProperty("grant_types") List<String> grantTypes,
    @JsonProperty("scope") String scope
) {}
