package com.picsou.dto;

import java.time.Instant;

/**
 * One row in the user's "Active sessions" list. {@code current} is true for
 * the row the request itself rides on — the persistent session whose series_id
 * matches the persistent_token cookie, or the iOS app authorization named by the
 * Bearer token's {@code aid} claim — so the UI can label it and disable revoke for
 * it. {@code userAgent} and {@code ipPrefix} are intentionally fuzzy: the goal is
 * "did I create this from my phone last Tuesday?" recognition, not forensic
 * tracking. Both are absent for an {@link Kind#IOS_APP} row.
 *
 * <p>{@code id} is opaque: a Remember Me row's numeric id as a string, or the
 * {@code oauth2_authorization} id of an app row. Clients pass it back unchanged
 * to {@code DELETE /api/auth/sessions/{id}}.
 */
public record SessionResponse(
    String id,
    Kind kind,
    String userAgent,
    String ipPrefix,
    Instant createdAt,
    Instant lastUsedAt,
    Instant expiresAt,
    boolean trustedFor2fa,
    boolean current
) {
    public enum Kind {
        /** A browser "Remember Me" persistent session. */
        REMEMBER_ME,
        /** The native iOS app, signed in through the OAuth2 authorization server. */
        IOS_APP
    }
}
