package com.picsou.config;

/**
 * Empty Jackson mix-in used only to add a class to
 * {@link org.springframework.security.jackson2.SecurityJackson2Modules}' deserialization allow-list.
 *
 * <p>That module's {@code AllowlistTypeIdResolver} refuses to deserialize any polymorphic type it
 * does not trust; registering a mix-in for a class (via {@code ObjectMapper.addMixIn}) marks it
 * trusted without otherwise changing how it is (de)serialized. We use it for the JDK/enum scalar
 * types that our own claims and principal snapshot introduce into a persisted {@code
 * oauth2_authorization} row ({@code Long} for {@code uid}/{@code tv} and {@code AppUser.id}/
 * {@code tokenVersion}, {@code UserRole} for the principal's role) — values written exclusively by
 * this server (a trusted source), never by a remote party.
 */
public abstract class TrustedClassMixin {
}
