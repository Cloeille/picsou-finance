package com.picsou.config;

import com.picsou.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * OIDC single sign-on settings ({@code app.sso.*}, fed by the {@code SSO_*} env vars; see
 * docs/briefs/2026-10-31-sso-login-design.md). Always registered, like {@link OAuthClientProperties};
 * every value is a raw string and none can fail startup. {@link #evaluate} is the single source of
 * truth for "is SSO on" — shared with {@link SsoEnabledCondition} so the two cannot disagree.
 */
@Component
@ConfigurationProperties(prefix = "app.sso")
public class SsoProperties implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SsoProperties.class);

    static final String DEFAULT_REVALIDATE_AFTER = "24h";
    private static final int MAX_PROVIDER_NAME_LENGTH = 40;

    /** Result of {@link #evaluate}: {@code problem} is non-null only for a configured-but-invalid setup. */
    public record Evaluation(boolean enabled, Duration revalidateAfter, String problem) {}

    private String issuerUri;
    private String clientId;
    private String clientSecret;
    private String providerName = "SSO";
    private String scopes = "openid,profile,email";
    private String groupsClaim = "groups";
    private String adminGroup;
    private String sessionRevalidateAfter = DEFAULT_REVALIDATE_AFTER;
    private String idpLogout;

    public String getIssuerUri() { return issuerUri; }
    public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }

    public String getGroupsClaim() { return groupsClaim; }
    public void setGroupsClaim(String groupsClaim) { this.groupsClaim = groupsClaim; }

    public String getAdminGroup() { return adminGroup; }
    public void setAdminGroup(String adminGroup) { this.adminGroup = adminGroup; }

    public String getSessionRevalidateAfter() { return sessionRevalidateAfter; }
    public void setSessionRevalidateAfter(String sessionRevalidateAfter) { this.sessionRevalidateAfter = sessionRevalidateAfter; }

    public String getIdpLogout() { return idpLogout; }
    public void setIdpLogout(String idpLogout) { this.idpLogout = idpLogout; }

    /**
     * Blank issuer: disabled, no problem. Otherwise the issuer must be an absolute http(s) URI,
     * the client id non-blank and the revalidation window a positive duration; the first violation
     * is returned as the problem and SSO stays disabled.
     */
    public static Evaluation evaluate(String issuerUri, String clientId, String revalidateAfterRaw) {
        Duration fallback = DurationStyle.detectAndParse(DEFAULT_REVALIDATE_AFTER);
        if (isBlank(issuerUri)) {
            return new Evaluation(false, fallback, null);
        }
        if (!isAbsoluteHttpUri(issuerUri.trim())) {
            return new Evaluation(false, fallback, "issuer-uri is not an absolute http(s) URI");
        }
        if (isBlank(clientId)) {
            return new Evaluation(false, fallback, "client-id is required when issuer-uri is set");
        }
        Duration revalidateAfter;
        try {
            revalidateAfter = DurationStyle.detectAndParse(
                isBlank(revalidateAfterRaw) ? DEFAULT_REVALIDATE_AFTER : revalidateAfterRaw.trim());
        } catch (IllegalArgumentException e) {
            return new Evaluation(false, fallback, "session-revalidate-after is not a valid duration");
        }
        if (revalidateAfter.isZero() || revalidateAfter.isNegative()) {
            return new Evaluation(false, fallback, "session-revalidate-after must be positive");
        }
        return new Evaluation(true, revalidateAfter, null);
    }

    private static boolean isAbsoluteHttpUri(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return uri.isAbsolute() && uri.getHost() != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * A plain-http issuer is accepted (a local IdP on a trusted network is a legitimate setup), but
     * off the loopback interface the authorization code, ID token and client secret cross the
     * network in cleartext.
     */
    static boolean isInsecureIssuer(String issuerUri) {
        try {
            URI uri = new URI(issuerUri.trim());
            return "http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !isLoopbackHost(uri.getHost());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** Names resolve through DNS and so prove nothing: only {@code localhost} and loopback IP literals count. */
    private static boolean isLoopbackHost(String host) {
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        boolean ipLiteral = host.startsWith("[") || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        try {
            return ipLiteral && InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Evaluation evaluation() {
        return evaluate(issuerUri, clientId, sessionRevalidateAfter);
    }

    public boolean enabled() { return evaluation().enabled(); }

    public boolean adminGroupManaged() { return enabled() && adminGroupConfigured(); }

    /** Independent of {@link #enabled()}: invalid SSO settings must never reopen password bootstrap. */
    public boolean adminGroupConfigured() { return !isBlank(adminGroup); }

    public String issuerUri() { return issuerUri == null ? "" : issuerUri.trim(); }

    public String clientId() { return clientId == null ? "" : clientId.trim(); }

    public String clientSecret() { return clientSecret == null ? "" : clientSecret.trim(); }

    public boolean publicClient() { return clientSecret().isEmpty(); }

    /** {@code openid} first, then the configured scopes in order, trimmed and de-duplicated. */
    public List<String> effectiveScopes() {
        Set<String> result = new LinkedHashSet<>();
        result.add("openid");
        if (scopes != null) {
            for (String scope : scopes.split(",")) {
                String trimmed = scope.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return List.copyOf(result);
    }

    public Duration revalidateAfter() { return evaluation().revalidateAfter(); }

    /** Lenient, same truthy set as {@code HSTS_ENABLED}: a typo must never take the backend down. */
    public boolean idpLogout() { return SecurityConfig.parseHstsEnabled(idpLogout); }

    public String providerName() {
        if (isBlank(providerName)) {
            return "SSO";
        }
        String trimmed = providerName.trim();
        return trimmed.length() > MAX_PROVIDER_NAME_LENGTH
            ? trimmed.substring(0, MAX_PROVIDER_NAME_LENGTH)
            : trimmed;
    }

    public String groupsClaim() { return isBlank(groupsClaim) ? "groups" : groupsClaim; }

    public String adminGroup() { return adminGroup == null ? "" : adminGroup.trim(); }

    @Override
    public void afterPropertiesSet() {
        Evaluation evaluation = evaluation();
        if (evaluation.problem() != null) {
            log.error("SSO configuration invalid ({}); SSO is disabled", evaluation.problem());
        } else if (evaluation.enabled()) {
            log.info("SSO enabled: issuer={} provider={} adminGroupManaged={}",
                LogSanitizer.safe(issuerUri()), LogSanitizer.safe(providerName()), adminGroupManaged());
            if (isInsecureIssuer(issuerUri())) {
                log.warn("SSO issuer {} uses plain http on a non-loopback host: the authorization code, "
                    + "ID token and client secret travel unencrypted. Use an https issuer.",
                    LogSanitizer.safe(issuerUri()));
            }
        }
    }
}
