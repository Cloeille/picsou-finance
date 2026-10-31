package com.picsou.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the single first-party OAuth2 client (the native iOS app).
 * Follows the {@link AppProperties} (Enable Banking) precedent: env-var-backed placeholders
 * under the {@code app.*} tree. Sensible defaults let a fresh install work with no extra env.
 */
@Component
@ConfigurationProperties(prefix = "app.oauth")
public class OAuthClientProperties {

    /** Public client id used by the iOS app. */
    private String clientId = "picsou-ios";

    /** Custom-scheme redirect URI registered by the iOS app for the auth-code callback. */
    private String redirectUri = "picsou://callback";

    /** Access-token lifetime (minutes). Mirrors the cookie access-token TTL. */
    private long accessTokenTtlMinutes = 15;

    /** Refresh-token lifetime (days). Rotating; drives how long a device stays signed in. */
    private long refreshTokenTtlDays = 30;

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }

    public long getAccessTokenTtlMinutes() { return accessTokenTtlMinutes; }
    public void setAccessTokenTtlMinutes(long accessTokenTtlMinutes) { this.accessTokenTtlMinutes = accessTokenTtlMinutes; }

    public long getRefreshTokenTtlDays() { return refreshTokenTtlDays; }
    public void setRefreshTokenTtlDays(long refreshTokenTtlDays) { this.refreshTokenTtlDays = refreshTokenTtlDays; }
}
