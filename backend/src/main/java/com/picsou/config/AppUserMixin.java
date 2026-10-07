package com.picsou.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;

/**
 * Jackson mix-in that lets {@link org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService}
 * (de)serialize an {@link com.picsou.model.AppUser} principal into the {@code oauth2_authorization}
 * table.
 *
 * <p>Spring Security's hardened {@code SecurityJackson2Modules} ObjectMapper only (de)serializes an
 * allow-list of trusted classes (a deserialization-gadget defense); a custom principal type is
 * rejected with "not in the allowlist" unless a mix-in explicitly opts it in. This matters the
 * moment the authorization server persists an authorization whose principal is an {@code AppUser}
 * (the consent-required remote-MCP flow parks one at {@code GET /oauth2/authorize}; the iOS refresh
 * flow reads one back) — see {@code Oauth2ConsentHandshakeIntegrationTest}.
 *
 * <p>Only the four fields the token customizer needs are persisted
 * ({@code id}, {@code username}, {@code role}, {@code tokenVersion}) — a minimal principal snapshot.
 * {@code member} (a lazy JPA graph), {@code passwordHash} (must never land in this table), and the
 * audit timestamps are deliberately excluded via {@link JsonIncludeProperties}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonIncludeProperties({"id", "username", "role", "tokenVersion"})
@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.ANY,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE
)
public abstract class AppUserMixin {
}
