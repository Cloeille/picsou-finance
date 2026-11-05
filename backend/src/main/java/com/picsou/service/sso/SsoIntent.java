package com.picsou.service.sso;

import java.io.Serializable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public record SsoIntent(Kind kind, String redirect, boolean rememberMe, Long userId,
                        String activationToken, Instant startedAt) implements Serializable {
    public static final String ATTRIBUTE = SsoIntent.class.getName();

    public enum Kind { LOGIN, LINK, REAUTH, ACTIVATE, SETUP }

    public String errorRedirect(String code) {
        return switch (kind) {
            case LOGIN -> loginError(code) + ("/".equals(redirect) ? "" :
                "&redirect=" + URLEncoder.encode(redirect, StandardCharsets.UTF_8));
            case LINK, REAUTH -> withQueryParameter(redirect, "ssoError=" + code);
            case ACTIVATE -> "/activate/" + URLEncoder.encode(activationToken, StandardCharsets.UTF_8) + "?ssoError=" + code;
            case SETUP -> "/setup/admin?ssoError=" + code;
        };
    }

    public static String loginError(String code) {
        return "/login?ssoError=" + code;
    }

    /** Appends to the query string, before any fragment, so the browser still sends the parameter. */
    public static String withQueryParameter(String url, String parameter) {
        int hash = url.indexOf('#');
        String beforeFragment = hash < 0 ? url : url.substring(0, hash);
        String fragment = hash < 0 ? "" : url.substring(hash);
        return beforeFragment + (beforeFragment.contains("?") ? "&" : "?") + parameter + fragment;
    }
}
