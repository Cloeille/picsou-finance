package com.picsou.config;

public final class SafeRedirect {
    private SafeRedirect() {}

    public static String sanitize(String raw, String fallback) {
        if (raw == null || !raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\")
                || raw.length() > 2048 || raw.chars().anyMatch(Character::isISOControl)) {
            return fallback;
        }
        return raw;
    }
}
