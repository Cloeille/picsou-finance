package com.picsou.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Helpers for keeping sensitive values out of log statements (defense-in-depth
 * against log aggregators / retention systems with weaker access control than
 * the primary database).
 */
public final class LogSanitizer {

    private static final Logger log = LoggerFactory.getLogger(LogSanitizer.class);

    private LogSanitizer() {}

    /**
     * Returns a short, stable, non-reversible fingerprint of an opaque secret
     * identifier (e.g. an Enable Banking session id) that is safe to log.
     *
     * <p>The raw value is never emitted. Two log lines referring to the same id
     * still share the same fingerprint, so they can be correlated while
     * debugging without exposing the id itself. Returns {@code "<none>"} for a
     * null or blank input.
     */
    public static String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "<none>";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            // 4 bytes -> 8 hex chars: enough to correlate, far too little to reverse.
            return "sha256:" + HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JVM algorithm (JCA spec), so this is
            // effectively unreachable. Surface it if the impossible happens, but
            // NEVER throw: this helper only feeds log statements, and a throwing
            // logger would turn a benign log call into a broken sync/auth flow.
            // Fail closed — redact rather than leak the raw value.
            log.error("SHA-256 unavailable — cannot fingerprint value for logging; redacting", e);
            return "<redacted>";
        }
    }
}
