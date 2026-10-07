package com.picsou.adapter;

import com.picsou.exception.SyncException;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * Turns a setup token into a claim URL, and an access URL into a credential-free
 * accounts request. Both must point at SimpleFIN Bridge, so a pasted token or a
 * claim response cannot make Picsou call any other host.
 */
final class SimplefinUrls {

    static final String BRIDGE_HOST = "beta-bridge.simplefin.org";
    static final int MAX_TOKEN_CHARS = 4096;
    static final int MAX_ACCESS_URL_CHARS = 8192;

    private SimplefinUrls() {}

    /** Decoded claim URL. Accepts a raw {@code https://} URL as well as Base64. */
    static URI claimUri(String setupToken) {
        if (setupToken == null || setupToken.isBlank()) {
            throw new SyncException("A SimpleFIN setup token is required.");
        }
        String trimmed = setupToken.trim();
        if (trimmed.length() > MAX_TOKEN_CHARS) {
            throw new SyncException("That SimpleFIN setup token is too long.");
        }
        String decoded = trimmed.regionMatches(true, 0, "https://", 0, "https://".length())
            ? trimmed
            : decodeToken(trimmed);
        URI uri = parse(decoded);
        assertHttps(uri, false);
        return uri;
    }

    /** Validates an access URL and returns it unchanged (trimmed) when it is safe to store. */
    static String requireAccessUrl(String accessUrl) {
        accessTarget(accessUrl, null);
        return accessUrl.trim();
    }

    /** Accounts URL with userinfo removed, plus the Basic header that carries it. */
    static AccountsRequest accountsRequest(String accessUrl, LocalDate startDate) {
        return accessTarget(accessUrl, startDate);
    }

    private static AccountsRequest accessTarget(String accessUrl, LocalDate startDate) {
        if (accessUrl == null || accessUrl.isBlank()) {
            throw new SyncException("The SimpleFIN access URL is missing. Connect again with a new setup token.");
        }
        String trimmed = accessUrl.trim();
        if (trimmed.length() > MAX_ACCESS_URL_CHARS) {
            throw new SyncException("The SimpleFIN access URL is too long.");
        }
        URI uri = parse(trimmed);
        assertHttps(uri, true);
        String userInfo = uri.getRawUserInfo();
        int colon = userInfo.indexOf(':');
        if (colon <= 0 || colon == userInfo.length() - 1) {
            throw new SyncException("The SimpleFIN access URL is missing its credentials.");
        }
        String username = urlDecode(userInfo.substring(0, colon));
        String password = urlDecode(userInfo.substring(colon + 1));
        String token = Base64.getEncoder().encodeToString(
            (username + ":" + password).getBytes(StandardCharsets.UTF_8));

        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (!path.endsWith("/")) path = path + "/";
        String query = startDate == null
            ? null
            : "version=2&start-date=" + startDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        URI accounts;
        try {
            accounts = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), path + "accounts", query, null);
        } catch (java.net.URISyntaxException ex) {
            throw new SyncException("The SimpleFIN access URL could not be used.", ex);
        }
        return new AccountsRequest(accounts, "Basic " + token, username);
    }

    private static String decodeToken(String compact) {
        String stripped = compact.replaceAll("\\s", "");
        int mod = stripped.length() % 4;
        String padded = mod == 0 ? stripped : stripped + "=".repeat(4 - mod);
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(padded);
        } catch (IllegalArgumentException standard) {
            try {
                bytes = Base64.getUrlDecoder().decode(padded);
            } catch (IllegalArgumentException url) {
                throw new SyncException("That does not look like a SimpleFIN setup token.");
            }
        }
        return new String(bytes, StandardCharsets.UTF_8).trim();
    }

    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (java.net.URISyntaxException ex) {
            throw new SyncException("That does not look like a SimpleFIN setup token.");
        }
    }

    private static void assertHttps(URI uri, boolean requireUserInfo) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new SyncException("A SimpleFIN URL must be an https address.");
        }
        if (!BRIDGE_HOST.equalsIgnoreCase(uri.getHost())) {
            throw new SyncException("Picsou only connects to SimpleFIN Bridge (" + BRIDGE_HOST + ").");
        }
        boolean hasUserInfo = uri.getRawUserInfo() != null && !uri.getRawUserInfo().isEmpty();
        if (requireUserInfo && !hasUserInfo) {
            throw new SyncException("The SimpleFIN access URL is missing its credentials.");
        }
        if (!requireUserInfo && hasUserInfo) {
            throw new SyncException("A SimpleFIN setup token must not contain credentials.");
        }
    }

    /** Percent-decodes a URI component. A literal {@code +} stays a plus; {@code %2B} becomes one too. */
    private static String urlDecode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    record AccountsRequest(URI uri, String authorization, String username) {}
}
