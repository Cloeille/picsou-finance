package com.picsou.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for writing/clearing auth cookies. Centralised so
 * AuthController, MfaController, and PersistentTokenAuthFilter all emit
 * identical cookie attributes — diverging on a single attribute (HttpOnly,
 * SameSite, Secure) is a silent auth bypass risk.
 *
 * <p>Cookie names handled here:
 * <ul>
 *   <li>{@code access_token} — short-lived JWT, every API call</li>
 *   <li>{@code refresh_token} — rotates access token at /api/auth/refresh</li>
 *   <li>{@code mfa_challenge_token} — single-purpose token for /api/auth/mfa/verify</li>
 *   <li>{@code persistent_token} — long-lived "Remember Me" / trusted-device</li>
 *   <li>{@code reauth_proof} — short-lived proof of a fresh re-authentication</li>
 * </ul>
 */
@Component
public class AuthCookieWriter {

    public static final String ACCESS_COOKIE = "access_token";
    public static final String REFRESH_COOKIE = "refresh_token";
    public static final String MFA_CHALLENGE_COOKIE = "mfa_challenge_token";
    public static final String PERSISTENT_COOKIE = "persistent_token";
    public static final String REAUTH_PROOF_COOKIE = "reauth_proof";

    private final SecureCookieProvider secureCookieProvider;
    private final JwtUtil jwtUtil;

    public AuthCookieWriter(SecureCookieProvider secureCookieProvider, JwtUtil jwtUtil) {
        this.secureCookieProvider = secureCookieProvider;
        this.jwtUtil = jwtUtil;
    }

    /**
     * Sets {@code access_token}/{@code refresh_token}. When {@code persistent} is false
     * (no "Remember Me"), both are written as browser-session cookies (no {@code Max-Age}
     * attribute at all) so they cannot outlive the browser being closed — otherwise a
     * 7-day refresh_token would let a non-"Remember Me" login be silently resurrected
     * after the tab/browser was closed (e.g. via {@code POST /auth/refresh}).
     */
    public void setAccessAndRefresh(HttpServletResponse response, String accessToken, String refreshToken, boolean persistent) {
        addCookie(response, ACCESS_COOKIE, accessToken, persistent ? (int) jwtUtil.getAccessExpirySeconds() : null);
        addCookie(response, REFRESH_COOKIE, refreshToken, persistent ? (int) jwtUtil.getRefreshExpirySeconds() : null);
    }

    public void setMfaChallenge(HttpServletResponse response, String challengeToken) {
        addCookie(response, MFA_CHALLENGE_COOKIE, challengeToken, (int) jwtUtil.getMfaChallengeExpirySeconds());
    }

    public void clearMfaChallenge(HttpServletResponse response) {
        addCookie(response, MFA_CHALLENGE_COOKIE, "", 0);
    }

    public void setReauthProof(HttpServletResponse response, String token) {
        addCookie(response, REAUTH_PROOF_COOKIE, token, (int) jwtUtil.getReauthProofExpirySeconds());
    }

    public void clearReauthProof(HttpServletResponse response) {
        addCookie(response, REAUTH_PROOF_COOKIE, "", 0);
    }

    public void setPersistent(HttpServletResponse response, String cookieValue, long maxAgeSeconds) {
        addCookie(response, PERSISTENT_COOKIE, cookieValue, (int) maxAgeSeconds);
    }

    public void clearPersistent(HttpServletResponse response) {
        addCookie(response, PERSISTENT_COOKIE, "", 0);
    }

    /**
     * Clears the authenticated-session cookies (access + refresh + persistent)
     * but leaves the {@code mfa_challenge} cookie untouched. Use this when a
     * request has proven a password yet is NOT (or not yet) an authenticated
     * session — e.g. the MFA-required branch of {@code /auth/login}: any session
     * cookies still on a shared/reused browser may belong to a DIFFERENT identity
     * (a left-over "Remember Me" admin), which must not bleed through while the
     * second factor is pending or abandoned.
     */
    public void clearSessionCookies(HttpServletResponse response) {
        addCookie(response, ACCESS_COOKIE, "", 0);
        addCookie(response, REFRESH_COOKIE, "", 0);
        addCookie(response, PERSISTENT_COOKIE, "", 0);
    }

    public void clearAuthCookies(HttpServletResponse response) {
        addCookie(response, ACCESS_COOKIE, "", 0);
        addCookie(response, REFRESH_COOKIE, "", 0);
        addCookie(response, MFA_CHALLENGE_COOKIE, "", 0);
        addCookie(response, PERSISTENT_COOKIE, "", 0);
        addCookie(response, REAUTH_PROOF_COOKIE, "", 0);
    }

    /** {@code maxAge == null} omits the Max-Age attribute entirely, producing a browser-session cookie. */
    private void addCookie(HttpServletResponse response, String name, String value, Integer maxAge) {
        String cookieHeader = String.format(
            "%s=%s;%s Path=/; HttpOnly; SameSite=Lax%s",
            name, value, maxAge != null ? " Max-Age=" + maxAge + ";" : "", secureCookieProvider.isSecure() ? "; Secure" : ""
        );
        response.addHeader("Set-Cookie", cookieHeader);
    }
}
